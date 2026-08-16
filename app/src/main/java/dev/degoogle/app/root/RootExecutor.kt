package dev.degoogle.app.root

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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

    /** Executa com variáveis de ambiente extras (merge sobre o ambiente herdado). */
    suspend fun execute(command: List<String>, env: Map<String, String>): RootResult

    /**
     * Executa encaminhando cada linha de stderr assim que o processo a emite.
     *
     * A implementação padrão preserva compatibilidade com executores de teste
     * e backends alternativos; o executor real sobrescreve para fazer streaming
     * de verdade.
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
    override suspend fun execute(command: List<String>, env: Map<String, String>): RootResult =
        delegate(command) // testes locais não dependem de env
    override suspend fun isRootAvailable(): Boolean = execute(listOf("id", "-u")).stdout.trim() == "0"
}
