package dev.degoogle.app.root

import dev.degoogle.app.domain.ProbeParser
import dev.degoogle.app.domain.SystemFacts

/**
 * Executa o backend `degoogle.sh` (instalado no filesDir) com argv seguro.
 * O stdout é tratado como protocolo de máquina; o stderr é preservado para a
 * seção técnica da UI.
 */
class BackendRunner(
    private val executor: RootExecutor,
    private val backendPath: String,
    private val experimentalOptIn: () -> Boolean = { false },
    private val transactionBase: String? = null,
    private val operationId: () -> String? = { null },
    private val onProgress: (String) -> Unit = {},
) {

    /** Exit codes do backend. */
    object ExitCodes {
        const val OK = 0
        const val GENERAL = 1
        const val USAGE = 2
        const val PRECONDITION = 3
        const val PARTIAL = 4
        const val UNSUPPORTED = 5
        const val SOFT_REBOOT_COOLDOWN = 6
    }

    /** Serializa `sh <backend> <args>` com argv explícito. */
    private fun script(vararg args: String): List<String> =
        listOf("sh", backendPath) + args.toList()

    suspend fun probe(): BackendProbeResult {
        val result = executor.execute(script("probe"), env = environment())
        val facts = ProbeParser.parse(result.stdout)
        return BackendProbeResult(
            facts = facts,
            raw = result,
            rootBackend = rootBackendFor(facts.rootManager),
        )
    }

    /** Seleciona a implementação sem acoplar o fluxo a KernelSU. */
    fun rootBackendFor(rootManager: String): RootBackend =
        RootBackendFactory.fromName(rootManager, executor)

    /** Probe explícito: não altera GMS, GSF, Store, cache ou dados. */
    suspend fun dryRun(): BackendCommandResult = runCommand(listOf("dry-run"), streamProgress = false)

    /** Revalida o ambiente imediatamente antes de uma operação destrutiva. */
    suspend fun preflight(): BackendCommandResult = runCommand(listOf("preflight"))

    suspend fun prepare(gmsApk: String, companionApk: String): BackendCommandResult =
        runCommand(listOf("prepare", gmsApk, companionApk))

    suspend fun finalize(): BackendCommandResult = runCommand(listOf("finalize"))

    /** Remove updates de /data/app (GMS/vending) e desabilita a Play Store. */
    suspend fun cleanup(): BackendCommandResult = runCommand(listOf("cleanup"))

    /** Remove apenas payloads de máscaras conhecidas, sem tocar no backup. */
    suspend fun cleanupResidue(): BackendCommandResult = runCommand(listOf("cleanup-residue"))

    suspend fun backup(): BackendCommandResult = runCommand(listOf("backup"))

    suspend fun restoreBackup(): BackendCommandResult = runCommand(listOf("restore-backup"))

    suspend fun restoreStock(wipeData: Boolean = true): BackendCommandResult =
        runCommand(if (wipeData) listOf("restore-stock", "--wipe-data") else listOf("restore-stock"))

    suspend fun softReboot(source: RebootSource = RebootSource.MANUAL): BackendCommandResult =
        runCommand(
            args = listOf("soft-reboot"),
            extraEnvironment = mapOf("DEGOOGLE_REBOOT_SOURCE" to source.value),
        )

    suspend fun postBootValidate(streamProgress: Boolean = true): BackendCommandResult =
        runCommand(listOf("post-boot-validate"), streamProgress = streamProgress)

    suspend fun rollback(wipeData: Boolean = false): BackendCommandResult =
        runCommand(if (wipeData) listOf("rollback", "--wipe-data") else listOf("rollback"))

    suspend fun status(): BackendCommandResult = runCommand(listOf("status"), streamProgress = false)

    private suspend fun runCommand(
        args: List<String>,
        streamProgress: Boolean = true,
        extraEnvironment: Map<String, String> = emptyMap(),
    ): BackendCommandResult {
        val command = listOf("sh", backendPath) + args
        val result = if (streamProgress) {
            executor.executeStreaming(
                command,
                env = environment(extraEnvironment),
                onStderrLine = onProgress,
            )
        } else {
            executor.execute(
                command,
                env = environment(extraEnvironment),
            )
        }
        val facts = ProbeParser.parse(result.stdout)
        return BackendCommandResult(
            succeeded = result.succeeded,
            exitCode = result.exitCode,
            facts = facts,
            stderr = result.stderr,
        )
    }

    private fun environment(extra: Map<String, String> = emptyMap()): Map<String, String> = buildMap {
        if (experimentalOptIn()) put("DEGOOGLE_EXPERIMENTAL", "1")
        transactionBase?.takeIf { it.isNotBlank() }?.let {
            put("DEGOOGLE_TRANSACTION_BASE", it)
        }
        operationId()?.takeIf { it.isNotBlank() }?.let {
            put("DEGOOGLE_OPERATION_ID", it)
        }
        putAll(extra)
    }

}

enum class RebootSource(val value: String) {
    MANUAL("manual"),
    AUTOMATIC("automatic"),
}

data class BackendProbeResult(
    val facts: SystemFacts,
    val raw: RootResult,
    val rootBackend: RootBackend,
)

data class BackendCommandResult(
    val succeeded: Boolean,
    val exitCode: Int,
    val facts: SystemFacts,
    val stderr: String,
)
