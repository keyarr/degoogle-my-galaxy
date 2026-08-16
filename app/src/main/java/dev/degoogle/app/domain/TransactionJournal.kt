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
)

/** Journal atômico no espaço privado do app; escrita idempotente por estado. */
class TransactionJournalStore(
    private val file: File,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    companion object {
        /**
         * Mantém o journal Kotlin fora de `files/transaction`, pois esse
         * diretório é manipulado pelo backend root e pode terminar root:root.
         */
        fun forAppFiles(filesDir: File): TransactionJournalStore =
            TransactionJournalStore(File(filesDir, "app-transaction/journal.json"))
    }

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    fun write(journal: TransactionJournal): Boolean = runCatching {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(TransactionJournal.serializer(), journal))
        if (!tmp.renameTo(file)) error("rename atômico do journal falhou")
    }.isSuccess

    fun update(
        operationId: String,
        fingerprint: String,
        state: TransactionState,
        detail: String = "",
    ): Boolean = write(
        TransactionJournal(
            operationId = operationId,
            timestamp = clock(),
            deviceFingerprint = fingerprint,
            state = state,
            detail = detail,
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
