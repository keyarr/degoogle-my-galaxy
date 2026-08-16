package dev.degoogle.app.root

import dev.degoogle.app.domain.CapabilityResult
import dev.degoogle.app.domain.RootBackendType

/** Resultado de uma operação que pode alterar estado. */
data class OperationResult(
    val succeeded: Boolean,
    val message: String,
    val exitCode: Int = if (succeeded) 0 else 1,
)

/** Abstrai o mecanismo de root do fluxo de compatibilidade. */
interface RootBackend {
    val type: RootBackendType

    suspend fun isAvailable(): Boolean
    suspend fun execute(command: List<String>): RootResult
    suspend fun canEnterGlobalMountNamespace(): CapabilityResult
    suspend fun canPerformBindMount(): CapabilityResult
    suspend fun supportsSoftReboot(): CapabilityResult
    suspend fun softReboot(): OperationResult
}

abstract class CommandRootBackend(
    protected val executor: RootExecutor,
) : RootBackend {
    protected abstract val markerPaths: List<String>

    override suspend fun isAvailable(): Boolean {
        if (!executor.isRootAvailable()) return false
        if (markerPaths.isEmpty()) return true
        return markerPaths.any { executor.execute(listOf("test", "-e", it)).succeeded }
    }

    override suspend fun execute(command: List<String>): RootResult = executor.execute(command)

    override suspend fun canEnterGlobalMountNamespace(): CapabilityResult {
        if (!isAvailable()) return CapabilityResult.fail("backend root não está disponível")
        val identityCheck = """
            self=${'$'}(readlink /proc/self/ns/mnt 2>/dev/null) || exit 1
            pid1=${'$'}(readlink /proc/1/ns/mnt 2>/dev/null) || exit 1
            global=${'$'}(nsenter --mount=/proc/1/ns/mnt -- readlink /proc/self/ns/mnt 2>/dev/null) || exit 1
            [ -n "${'$'}self" ] && [ -n "${'$'}pid1" ] && [ "${'$'}global" = "${'$'}pid1" ]
        """.trimIndent()
        val result = executor.execute(listOf("sh", "-c", identityCheck))
        return if (result.succeeded) {
            CapabilityResult.pass("self/PID 1/global mount namespace conferidos")
        } else {
            CapabilityResult.fail(
                "não foi possível entrar no namespace do PID 1",
                "exit=${result.exitCode} ${result.stderr.trim()}".trim(),
            )
        }
    }

    override suspend fun canPerformBindMount(): CapabilityResult {
        if (!isAvailable()) return CapabilityResult.fail("backend root não está disponível")
        // Script constante: não incorpora dados do usuário. O teste é isolado,
        // reversível e nunca toca nos diretórios de GMS/GSF/Store.
        val testScript = """
            base=/data/local/tmp/degoogle-capability-test.${'$'}${'$'};
            cleanup() {
                nsenter --mount=/proc/1/ns/mnt -- umount "${'$'}base/dst" >/dev/null 2>&1 ||
                    nsenter --mount=/proc/1/ns/mnt -- umount -l "${'$'}base/dst" >/dev/null 2>&1 || true;
                rm -rf "${'$'}base";
            }
            trap cleanup EXIT INT TERM;
            rm -rf "${'$'}base" || exit 1;
            mkdir -p "${'$'}base/src" "${'$'}base/dst" || exit 1;
            printf capability > "${'$'}base/src/value" || exit 1;
            nsenter --mount=/proc/1/ns/mnt -- mount --bind "${'$'}base/src" "${'$'}base/dst" || exit 1;
            nsenter --mount=/proc/1/ns/mnt -- test "${'$'}base/dst/value" || exit 1;
            nsenter --mount=/proc/1/ns/mnt -- umount "${'$'}base/dst" || exit 1;
            test ! -e "${'$'}base/dst/value" || exit 1
        """.trimIndent()
        val result = executor.execute(listOf("sh", "-c", testScript))
        return if (result.succeeded) {
            CapabilityResult.pass("bind mount temporário global criado, lido e desmontado")
        } else {
            CapabilityResult.fail(
                "bind mount global reversível falhou",
                "exit=${result.exitCode} ${result.stderr.trim()}".trim(),
            )
        }
    }

    override suspend fun supportsSoftReboot(): CapabilityResult {
        if (!isAvailable()) return CapabilityResult.fail("backend root não está disponível")
        val method = when (type) {
            RootBackendType.KERNELSU -> "/data/adb/ksud"
            else -> "sys.powerctl userspace"
        }
        return CapabilityResult.warn(
            reason = "estratégia existe, mas segurança depende de homologação do firmware",
            evidence = method,
        )
    }

    override suspend fun softReboot(): OperationResult {
        val result = when (type) {
            RootBackendType.KERNELSU -> executor.execute(listOf("/data/adb/ksud", "soft-reboot"))
            else -> executor.execute(listOf("setprop", "sys.powerctl", "reboot,userspace"))
        }
        return OperationResult(
            succeeded = result.succeeded,
            message = if (result.succeeded) "soft reboot solicitado" else result.stderr.ifBlank { "soft reboot falhou" },
            exitCode = result.exitCode,
        )
    }
}

class KernelSuBackend(executor: RootExecutor) : CommandRootBackend(executor) {
    override val type = RootBackendType.KERNELSU
    override val markerPaths = listOf("/data/adb/ksud", "/data/adb/ksu")
}

class MagiskBackend(executor: RootExecutor) : CommandRootBackend(executor) {
    override val type = RootBackendType.MAGISK
    override val markerPaths = listOf("/data/adb/magisk")
}

class APatchBackend(executor: RootExecutor) : CommandRootBackend(executor) {
    override val type = RootBackendType.APATCH
    override val markerPaths = listOf("/data/adb/apd", "/data/adb/ap")
}

class UnknownRootBackend(executor: RootExecutor) : CommandRootBackend(executor) {
    override val type = RootBackendType.UNKNOWN
    override val markerPaths: List<String> = emptyList()
}

object RootBackendFactory {
    fun fromType(type: RootBackendType, executor: RootExecutor): RootBackend = when (type) {
        RootBackendType.KERNELSU -> KernelSuBackend(executor)
        RootBackendType.MAGISK -> MagiskBackend(executor)
        RootBackendType.APATCH -> APatchBackend(executor)
        RootBackendType.UNKNOWN -> UnknownRootBackend(executor)
    }

    fun fromName(name: String, executor: RootExecutor): RootBackend =
        fromType(
            when (name.trim().lowercase()) {
                "kernelsu", "kernel su", "ksu" -> RootBackendType.KERNELSU
                "magisk" -> RootBackendType.MAGISK
                "apatch" -> RootBackendType.APATCH
                else -> RootBackendType.UNKNOWN
            },
            executor,
        )
}
