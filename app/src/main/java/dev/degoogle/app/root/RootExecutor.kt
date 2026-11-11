package dev.degoogle.app.root

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit

/**
 * Runs commands with root.
 *
 * Security: never concatenate arbitrary strings into a shell command. All
 * arguments pass through [ShellQuote.quote], and commands are built
 * with explicit argv ([ProcessBuilder]).
 */
interface RootExecutor {
    suspend fun execute(command: List<String>): RootResult

    /** Executes with extra environment variables (merged with the inherited environment). */
    suspend fun execute(command: List<String>, env: Map<String, String>): RootResult

    /**
     * Executes a command, forwarding each stderr line as soon as the process emits it.
     *
     * The default implementation preserves compatibility with test executors
     * and alternate backends; the real executor overrides this method to stream
     * output.
     */
    suspend fun executeStreaming(
        command: List<String>,
        env: Map<String, String>,
        onStderrLine: (String) -> Unit,
    ): RootResult {
        val result = execute(command, env)
        result.stderr.lineSequence()
            .filter(String::isNotBlank)
            .forEach { line -> runCatching { onStderrLine(line) } }
        return result
    }

    suspend fun isRootAvailable(): Boolean
}

object ShellQuote {
    /** POSIX quoting for a single argument. */
    fun quote(arg: String): String = "'" + arg.replace("'", "'\\''") + "'"
}

class SuRootExecutor(
    private val suPath: String = "su",
    private val timeoutSeconds: Long = 60,
) : RootExecutor {

    override suspend fun isRootAvailable(): Boolean {
        val r = execute(listOf("id", "-u"))
        return r is RootResult.Ok && r.stdout.trim() == "0"
    }

    /**
     * Executes [command] via `su -c`. [command] is the argv for the command to run;
     * it is serialized safely (POSIX-quoting each argument), and the resulting shell
     * is the only concatenation point; user data is never concatenated.
     */
    override suspend fun execute(command: List<String>): RootResult =
        execute(command, env = emptyMap())

    override suspend fun execute(command: List<String>, env: Map<String, String>): RootResult =
        executeStreaming(command, env) {}

    override suspend fun executeStreaming(
        command: List<String>,
        env: Map<String, String>,
        onStderrLine: (String) -> Unit,
    ): RootResult =
        withContext(Dispatchers.IO) {
            val script = command.joinToString(" ") { ShellQuote.quote(it) }
            withTimeout(TimeUnit.SECONDS.toMillis(timeoutSeconds)) {
                runCatching {
                    val pb = ProcessBuilder(suPath, "-c", script)
                    pb.environment().putAll(env)
                    pb.redirectErrorStream(false)
                    val proc = pb.start()
                    try {
                        coroutineScope {
                            val stdout = async(Dispatchers.IO) {
                                proc.inputStream.bufferedReader().use { it.readText() }
                            }
                            val stderr = async(Dispatchers.IO) {
                                buildString {
                                    proc.errorStream.bufferedReader().useLines { lines ->
                                        lines.forEach { line ->
                                            append(line).append('\n')
                                            if (line.isNotBlank()) {
                                                runCatching { onStderrLine(line) }
                                            }
                                        }
                                    }
                                }
                            }
                            val code = proc.waitFor()
                            val out = stdout.await()
                            val err = stderr.await()
                            RootResult.Ok(
                                exitCode = code,
                                stdout = out,
                                stderr = err,
                            )
                        }
                    } finally {
                        if (proc.isAlive) proc.destroyForcibly()
                    }
                }.getOrElse { e ->
                    RootResult.Error(e.message ?: "su execution failed", stderr = "")
                }
            }
        }

}

/** RootExecutor that runs without root (for unit tests and diagnostics). */
class LocalRootExecutor(
    private val delegate: (List<String>) -> RootResult = { args ->
        runCatching {
            val pb = ProcessBuilder(args)
            pb.redirectErrorStream(false)
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader().readText()
            val err = proc.errorStream.bufferedReader().readText()
            val code = proc.waitFor()
            RootResult.Ok(code, out, err)
        }.getOrElse { RootResult.Error(it.message ?: "local error") }
    },
) : RootExecutor {
    override suspend fun execute(command: List<String>): RootResult = delegate(command)
    override suspend fun execute(command: List<String>, env: Map<String, String>): RootResult =
        delegate(command) // local tests do not depend on env
    override suspend fun isRootAvailable(): Boolean = execute(listOf("id", "-u")).stdout.trim() == "0"
}
