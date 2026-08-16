package dev.degoogle.app.recovery

import dev.degoogle.app.domain.DeviceProfile
import dev.degoogle.app.domain.DeviceProfiles
import dev.degoogle.app.domain.DeviceState
import dev.degoogle.app.domain.StateDetector
import dev.degoogle.app.domain.SystemFacts
import dev.degoogle.app.root.BackendRunner
import dev.degoogle.app.root.RebootSource

/** Ação que pode ser executada sem intervenção depois de um boot inesperado. */
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
 * Política pura para impedir que um estado ERROR arbitrário vire uma operação
 * destrutiva. Só recuperamos estados que o backend consegue provar:
 *
 * - RESTORE_PREPARED: não há mounts, mas o PM ainda não reenxerga GSF/Store;
 * - ERROR com mount legado conhecido: o script manual antigo deixou uma
 *   máscara exatamente nos alvos homologados;
 * - STOCK com resíduos conhecidos: a recuperação já terminou e só faltam
 *   payloads temporários desmontados.
 */
object RecoveryPolicy {
    fun assess(facts: SystemFacts, profile: DeviceProfile?): RecoveryAssessment {
        val state = StateDetector.detect(facts, profile)
        val legacyMount = facts.mountGmsIsLegacy || facts.mountGsfIsLegacy || facts.mountStoreIsLegacy

        return when {
            state == DeviceState.RESTORE_PREPARED -> RecoveryAssessment(
                state = state,
                action = AutoRecoveryAction.RESTORE_STOCK,
                reason = "rollback sem reindexamento do Package Manager",
            )
            state == DeviceState.ERROR && legacyMount -> RecoveryAssessment(
                state = state,
                action = AutoRecoveryAction.RESTORE_STOCK,
                reason = "máscara legada conhecida ainda montada",
            )
            state == DeviceState.STOCK && facts.maskResiduePresent -> RecoveryAssessment(
                state = state,
                action = AutoRecoveryAction.CLEAN_RESIDUE,
                reason = "máscaras desmontadas ainda contêm payloads conhecidos",
            )
            else -> RecoveryAssessment(state, AutoRecoveryAction.NONE, "nenhuma recuperação automática segura")
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
 * Orquestra a recuperação tanto no arranque quanto quando a Activity é aberta.
 * A operação de restore é idempotente e o backup do microG fica fora do alvo.
 */
class AutoRecoveryCoordinator(
    private val backend: BackendRunner,
    private val profileResolver: (SystemFacts) -> DeviceProfile? = { facts ->
        DeviceProfiles.matching(facts.manufacturer, facts.model, facts.androidSdk)
    },
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
                    reason = "probe falhou",
                ),
                facts = facts,
                message = "Probe failed: ${probe.raw.stderr.ifBlank { "root/backend unavailable" }}",
                stderr = probe.raw.stderr,
            )
        }

        val facts = probe.facts
        val assessment = RecoveryPolicy.assess(facts, profileResolver(facts))
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
                    // Comando operacional que falha pode não emitir o
                    // protocolo completo. Preserve o probe comprovado para
                    // que o BootReceiver não confunda falha de unmount/cache
                    // com root ainda indisponível e repita uma operação
                    // destrutiva sem necessidade.
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
