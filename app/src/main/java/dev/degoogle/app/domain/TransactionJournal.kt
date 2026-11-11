package dev.degoogle.app.domain

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class TransactionState {
    IDLE,
    PREFLIGHT_OK,
    BACKUP_STARTED,
    BACKUP_COMPLETE,
    GMS_MASKED,
    GSF_MASKED,
    STORE_MASKED,
    PACKAGE_CACHE_INVALIDATED,
    REBOOT_REQUESTED,
    POST_BOOT_VALIDATING,
    COMMITTED,
    ROLLBACK_REQUIRED,
    ROLLBACK_RUNNING,
    GMS_UNMOUNTED,
    GSF_UNMOUNTED,
    STORE_UNMOUNTED,
    REINDEX_PENDING,
    RESTORED,
    FAILED,
}

@Serializable
data class TransactionJournal(
    val schemaVersion: Int = 1,
    val operationId: String,
    val timestamp: Long,
    val deviceFingerprint: String,
    val state: TransactionState,
    val detail: String = "",
    val operationMode: OperationMode? = null,
)

/** Atomic journal in the app private space; idempotent writes per state. */
class TransactionJournalStore(
    private val file: File,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    companion object {
        /**
         * Keeps the Kotlin journal outside `files/transaction` because that
         * directory is managed by the root backend and may end up owned by root:root.
         */
        fun forAppFiles(filesDir: File): TransactionJournalStore =
            TransactionJournalStore(File(filesDir, "app-transaction/journal.json"))
    }

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    fun write(journal: TransactionJournal): Boolean = runCatching {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(TransactionJournal.serializer(), journal))
        if (!tmp.renameTo(file)) error("journal atomic rename failed")
    }.isSuccess

    fun update(
        operationId: String,
        fingerprint: String,
        state: TransactionState,
        detail: String = "",
        operationMode: OperationMode? = read()?.operationMode,
    ): Boolean = write(
        TransactionJournal(
            operationId = operationId,
            timestamp = clock(),
            deviceFingerprint = fingerprint,
            state = state,
            detail = detail,
            operationMode = operationMode,
        ),
    )

    fun read(): TransactionJournal? = runCatching {
        if (!file.isFile) return null
        json.decodeFromString(TransactionJournal.serializer(), file.readText())
    }.getOrNull()

    fun requiresRecovery(): Boolean = read()?.state in setOf(
        TransactionState.PREFLIGHT_OK,
        TransactionState.BACKUP_STARTED,
        TransactionState.BACKUP_COMPLETE,
        TransactionState.GMS_MASKED,
        TransactionState.GSF_MASKED,
        TransactionState.PACKAGE_CACHE_INVALIDATED,
        TransactionState.REBOOT_REQUESTED,
        TransactionState.POST_BOOT_VALIDATING,
        TransactionState.ROLLBACK_REQUIRED,
        TransactionState.ROLLBACK_RUNNING,
        TransactionState.GMS_UNMOUNTED,
        TransactionState.GSF_UNMOUNTED,
        TransactionState.STORE_UNMOUNTED,
        TransactionState.REINDEX_PENDING,
        TransactionState.FAILED,
    )

    fun clear(): Boolean = runCatching { file.delete() || !file.exists() }.getOrDefault(false)
}

/** Conservative reconciliation of a transaction that survived a reboot. */
object TransactionReconciliation {
    private val ACTIVE_STATES = setOf(
        DeviceState.MICROG_ACTIVE,
        DeviceState.MICROG_ACTIVE_BACKED_UP,
    )

    /** States representing an installation in progress, not a failed rollback. */
    private val COMMIT_CANDIDATES = setOf(
        TransactionState.PREFLIGHT_OK,
        TransactionState.BACKUP_STARTED,
        TransactionState.BACKUP_COMPLETE,
        TransactionState.GMS_MASKED,
        TransactionState.GSF_MASKED,
        TransactionState.STORE_MASKED,
        TransactionState.PACKAGE_CACHE_INVALIDATED,
        TransactionState.REBOOT_REQUESTED,
        TransactionState.POST_BOOT_VALIDATING,
    )

    fun shouldMarkStockRestored(
        journal: TransactionJournal?,
        state: DeviceState,
        backendConfirmed: Boolean,
        backendOperationId: String?,
    ): Boolean {
        if (state != DeviceState.STOCK) return false

        val rollbackPending = journal?.state in setOf(
            TransactionState.ROLLBACK_RUNNING,
            TransactionState.ROLLBACK_REQUIRED,
            TransactionState.REBOOT_REQUESTED,
            TransactionState.PACKAGE_CACHE_INVALIDATED,
            TransactionState.GMS_UNMOUNTED,
            TransactionState.GSF_UNMOUNTED,
            TransactionState.STORE_UNMOUNTED,
            TransactionState.REINDEX_PENDING,
            TransactionState.FAILED,
        )
        val confirmationId = backendOperationId?.takeIf { it.isNotBlank() && it != "unknown" }
        val backendRestoreUnrecorded = backendConfirmed &&
            journal?.state != TransactionState.RESTORED &&
            (journal == null || confirmationId == null || journal.operationId == confirmationId)
        return rollbackPending || backendRestoreUnrecorded
    }

    fun shouldCommitActiveState(
        journal: TransactionJournal?,
        state: DeviceState,
    ): Boolean = journal != null &&
        journal.state in COMMIT_CANDIDATES &&
        when (state) {
            DeviceState.DEGOOGLE_ACTIVE -> journal.operationMode == OperationMode.PURE
            DeviceState.MICROG_ACTIVE, DeviceState.MICROG_ACTIVE_BACKED_UP ->
                journal.operationMode != OperationMode.PURE
            else -> false
        }

    /**
     * A Rescue Party reboot drops root and all bind mounts, so microG is healthy only
     * if the environment was rebuilt and validated afterward. When the only recorded
     * reason for a post-boot failure is Rescue Party, and the derived state confirms
     * the installation (GMS priv-app, GSF missing, finalize complete), the UI shows
     * a dismissible warning instead of a blocking error.
     */
    fun isRescuePartyOnlyFailure(
        journal: TransactionJournal?,
        state: DeviceState,
        gmsPrivileged: Boolean,
        gsfAbsent: Boolean,
        finalizeDone: Boolean,
    ): Boolean = journal != null &&
        state in ACTIVE_STATES &&
        journal.detail.isRescuePartyReason() &&
        gmsPrivileged &&
        gsfAbsent &&
        finalizeDone

    private fun String.isRescuePartyReason(): Boolean =
        contains("rescueparty", ignoreCase = true) ||
            contains("rescue_party", ignoreCase = true) ||
            contains("rescue party", ignoreCase = true)
}
