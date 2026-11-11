package dev.degoogle.app.recovery

import dev.degoogle.app.domain.DeviceState
import dev.degoogle.app.domain.DeviceProfile
import dev.degoogle.app.domain.StateDetector
import dev.degoogle.app.domain.SystemFacts
import dev.degoogle.app.root.BackendRunner
import dev.degoogle.app.root.RebootSource

/** Action that can run without intervention after an unexpected boot. */
enum class AutoRecoveryAction {
    NONE,
    CLEAN_RESIDUE,
    RESTORE_STOCK,
}

data class RecoveryAssessment(
    val state: DeviceState,
    val action: AutoRecoveryAction,
    val reason: String,
)

/**
 * Pure policy that prevents an arbitrary ERROR state from triggering a
 * destructive operation. Recover only states the backend can prove:
 *
 * - RESTORE_PREPARED before a verified restart: no mounts are active, but PM does not see GSF/Store yet;
 * - ERROR with a known legacy mount: the old manual script left masks at the
 *   approved targets exactly;
 * - STOCK with known residue: recovery is complete; only temporary
 *   unmounted payloads remain.
 */
object RecoveryPolicy {
    fun assess(facts: SystemFacts, profile: DeviceProfile? = null): RecoveryAssessment {
        val state = StateDetector.detect(facts)
        val legacyMount = facts.mountGmsIsLegacy || facts.mountGsfIsLegacy || facts.mountStoreIsLegacy

        return when {
            state == DeviceState.RESTORE_PREPARED && facts.stockReindexReady -> RecoveryAssessment(
                state = state,
                action = AutoRecoveryAction.NONE,
                reason = "stock reindex is ready after the verified userspace restart",
            )
            state == DeviceState.RESTORE_PREPARED -> RecoveryAssessment(
                state = state,
                action = AutoRecoveryAction.RESTORE_STOCK,
                reason = "rollback without Package Manager reindexing",
            )
            state == DeviceState.ERROR && legacyMount -> RecoveryAssessment(
                state = state,
                action = AutoRecoveryAction.RESTORE_STOCK,
                reason = "known legacy mask still mounted",
            )
            state == DeviceState.STOCK && facts.maskResiduePresent -> RecoveryAssessment(
                state = state,
                action = AutoRecoveryAction.CLEAN_RESIDUE,
                reason = "unmounted masks still hold known payloads",
            )
            else -> RecoveryAssessment(state, AutoRecoveryAction.NONE, "no safe automatic recovery")
        }
    }
}

enum class AutoRecoveryStatus {
    NOT_NEEDED,
    CLEANED,
    REBOOT_REQUESTED,
    FAILED,
}

data class AutoRecoveryResult(
    val status: AutoRecoveryStatus,
    val assessment: RecoveryAssessment,
    val facts: SystemFacts,
    val message: String,
    val stderr: String = "",
) {
    val succeeded: Boolean
        get() = status != AutoRecoveryStatus.FAILED
}

/**
 * Orchestrates recovery at startup and when the Activity opens.
 * Restore is idempotent, and the microG backup is outside its target.
 */
class AutoRecoveryCoordinator(
    private val backend: BackendRunner,
) {
    suspend fun runIfNeeded(wipeData: Boolean = true): AutoRecoveryResult {
        val probe = backend.probe()
        if (!probe.raw.succeeded) {
            val facts = probe.facts
            return AutoRecoveryResult(
                status = AutoRecoveryStatus.FAILED,
                assessment = RecoveryAssessment(
                    state = DeviceState.ERROR,
                    action = AutoRecoveryAction.NONE,
                    reason = "probe failed",
                ),
                facts = facts,
                message = "Probe failed: ${probe.raw.stderr.ifBlank { "root/backend unavailable" }}",
                stderr = probe.raw.stderr,
            )
        }

        val facts = probe.facts
        val assessment = RecoveryPolicy.assess(facts)
        return when (assessment.action) {
            AutoRecoveryAction.NONE -> AutoRecoveryResult(
                status = AutoRecoveryStatus.NOT_NEEDED,
                assessment = assessment,
                facts = facts,
                message = assessment.reason,
            )

            AutoRecoveryAction.CLEAN_RESIDUE -> {
                val result = backend.cleanupResidue()
                AutoRecoveryResult(
                    status = if (result.succeeded) AutoRecoveryStatus.CLEANED else AutoRecoveryStatus.FAILED,
                    assessment = assessment,
                    // A failed operational command may not emit the full protocol.
                    // Preserve the verified probe so BootReceiver does not mistake
                    // an unmount/cache failure for unavailable root and repeat a
                    // destructive operation unnecessarily.
                    facts = facts,
                    message = if (result.succeeded) {
                        "Known temporary residues removed; backup preserved"
                    } else {
                        "Temporary residue cleanup failed (exit=${result.exitCode})"
                    },
                    stderr = result.stderr,
                )
            }

            AutoRecoveryAction.RESTORE_STOCK -> {
                val restore = backend.restoreStock(wipeData)
                if (!restore.succeeded) {
                    val detail = restore.stderr.lineSequence()
                        .map(String::trim)
                        .lastOrNull(String::isNotEmpty)
                    return AutoRecoveryResult(
                        status = AutoRecoveryStatus.FAILED,
                        assessment = assessment,
                        facts = facts,
                        message = buildString {
                            append("Stock restore failed (exit=${restore.exitCode})")
                            if (detail != null) append(": ").append(detail)
                        },
                        stderr = restore.stderr,
                    )
                }
                val reboot = backend.softReboot(RebootSource.AUTOMATIC)
                AutoRecoveryResult(
                    status = if (reboot.succeeded) {
                        AutoRecoveryStatus.REBOOT_REQUESTED
                    } else {
                        AutoRecoveryStatus.FAILED
                    },
                    assessment = assessment,
                    facts = facts,
                    message = if (reboot.succeeded) {
                        "Stock environment prepared; userspace reboot requested for Package Manager reindexing"
                    } else {
                        "Stock environment prepared, but userspace reboot failed"
                    },
                    stderr = listOf(restore.stderr, reboot.stderr)
                        .filter(String::isNotBlank)
                        .joinToString("\n"),
                )
            }
        }
    }
}
