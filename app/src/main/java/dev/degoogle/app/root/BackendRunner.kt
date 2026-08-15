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
) {

    /** Exit codes do backend. */
    object ExitCodes {
        const val OK = 0
        const val GENERAL = 1
        const val USAGE = 2
        const val PRECONDITION = 3
        const val PARTIAL = 4
        const val UNSUPPORTED = 5
    }

    /** Serializa `sh <backend> <args>` com argv explícito. */
    private fun script(vararg args: String): List<String> =
        listOf("sh", backendPath) + args.toList()

    suspend fun probe(): BackendProbeResult {
        val result = executor.execute(script("probe"))
        val facts = ProbeParser.parse(result.stdout)
        return BackendProbeResult(facts = facts, raw = result)
    }

    suspend fun prepare(gmsApk: String, companionApk: String): BackendCommandResult =
        runCommand(listOf("prepare", gmsApk, companionApk))

    suspend fun finalize(): BackendCommandResult = runCommand(listOf("finalize"))

    /** Remove updates de /data/app (GMS/vending) e desabilita a Play Store. */
    suspend fun cleanup(): BackendCommandResult = runCommand(listOf("cleanup"))

    suspend fun backup(): BackendCommandResult = runCommand(listOf("backup"))

    suspend fun restoreBackup(): BackendCommandResult = runCommand(listOf("restore-backup"))

    suspend fun restoreStock(wipeData: Boolean = true): BackendCommandResult =
        runCommand(if (wipeData) listOf("restore-stock", "--wipe-data") else listOf("restore-stock"))

    suspend fun softReboot(): BackendCommandResult = runCommand(listOf("soft-reboot"))

    suspend fun status(): BackendCommandResult = runCommand(listOf("status"))

    private suspend fun runCommand(args: List<String>): BackendCommandResult {
        val result = executor.execute(listOf("sh", backendPath) + args)
        val facts = ProbeParser.parse(result.stdout)
        return BackendCommandResult(
            succeeded = result.succeeded,
            exitCode = result.exitCode,
            facts = facts,
            stderr = result.stderr,
        )
    }
}

data class BackendProbeResult(
    val facts: SystemFacts,
    val raw: RootResult,
)

data class BackendCommandResult(
    val succeeded: Boolean,
    val exitCode: Int,
    val facts: SystemFacts,
    val stderr: String,
)
