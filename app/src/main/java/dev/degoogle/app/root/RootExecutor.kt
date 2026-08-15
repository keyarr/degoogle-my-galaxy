package dev.degoogle.app.root

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit

/**
 * Executa comandos com root.
 *
 * Segurança: nunca concatenamos strings arbitrárias em shell. Todos os
 * argumentos passam por [ShellQuote.quote] e os comandos são construídos
 * com argv explícito ([ProcessBuilder]).
 */
interface RootExecutor {
    suspend fun execute(command: List<String>): RootResult
    suspend fun isRootAvailable(): Boolean
}

object ShellQuote {
    /** Quote POSIX de um único argumento. */
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
     * Executa [command] via `su -c`. [command] é o argv do comando a rodar;
     * ele é serializado de forma segura (quote POSIX por argumento) e o shell
     * resultante é o único ponto de concatenação — nunca dados do usuário.
     */
    override suspend fun execute(command: List<String>): RootResult = withContext(Dispatchers.IO) {
        val script = command.joinToString(" ") { ShellQuote.quote(it) }
        withTimeout(TimeUnit.SECONDS.toMillis(timeoutSeconds)) {
            runCatching {
                val pb = ProcessBuilder(suPath, "-c", script)
                pb.redirectErrorStream(false)
                val proc = pb.start()
                val out = proc.inputStream.bufferedReader().readText()
                val err = proc.errorStream.bufferedReader().readText()
                val code = proc.waitFor()
                RootResult.Ok(exitCode = code, stdout = out, stderr = err)
            }.getOrElse { e ->
                RootResult.Error(e.message ?: "falha ao executar su", stderr = "")
            }
        }
    }

}

/** RootExecutor que roda sem root (para testes unitários e diagnóstico). */
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
        }.getOrElse { RootResult.Error(it.message ?: "erro local") }
    },
) : RootExecutor {
    override suspend fun execute(command: List<String>): RootResult = delegate(command)
    override suspend fun isRootAvailable(): Boolean = execute(listOf("id", "-u")).stdout.trim() == "0"
}
