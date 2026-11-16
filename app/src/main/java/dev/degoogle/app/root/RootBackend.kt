package dev.degoogle.app.root

import dev.degoogle.app.domain.CapabilityResult
import dev.degoogle.app.domain.RootBackendType

/** Result of an operation that may change state. */
data class OperationResult(
    val succeeded: Boolean,
    val message: String,
    val exitCode: Int = if (succeeded) 0 else 1,
)

/** Abstracts the root mechanism used by the compatibility flow. */
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
        if (!isAvailable()) return CapabilityResult.fail("root backend not available")
        val identityCheck = """
            self=${'$'}(readlink /proc/self/ns/mnt 2>/dev/null) || exit 1
            pid1=${'$'}(readlink /proc/1/ns/mnt 2>/dev/null) || exit 1
            global=${'$'}(nsenter --mount=/proc/1/ns/mnt -- readlink /proc/self/ns/mnt 2>/dev/null) || exit 1
            [ -n "${'$'}self" ] && [ -n "${'$'}pid1" ] && [ "${'$'}global" = "${'$'}pid1" ]
        """.trimIndent()
        val result = executor.execute(listOf("sh", "-c", identityCheck))
        return if (result.succeeded) {
            CapabilityResult.pass("self/PID 1/global mount namespace verified")
        } else {
            CapabilityResult.fail(
                "could not enter PID 1 namespace",
                "exit=${result.exitCode} ${result.stderr.trim()}".trim(),
            )
        }
    }

    override suspend fun canPerformBindMount(): CapabilityResult {
        if (!isAvailable()) return CapabilityResult.fail("root backend not available")
        // Constant script: does not include user data. The test is isolated,
        // reversible, and never touches GMS/GSF/Store directories.
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
            value=${'$'}(nsenter --mount=/proc/1/ns/mnt -- cat "${'$'}base/dst/value") || exit 1;
            [ "${'$'}value" = capability ] || exit 1;
            nsenter --mount=/proc/1/ns/mnt -- umount "${'$'}base/dst" || exit 1;
            test ! -e "${'$'}base/dst/value" || exit 1
        """.trimIndent()
        val result = executor.execute(listOf("sh", "-c", testScript))
        return if (result.succeeded) {
            CapabilityResult.pass("global temporary bind mount created, read and unmounted")
        } else {
            CapabilityResult.fail(
                "reversible global bind mount failed",
                "exit=${result.exitCode} ${result.stderr.trim()}".trim(),
            )
        }
    }

    override suspend fun supportsSoftReboot(): CapabilityResult {
        if (!isAvailable()) return CapabilityResult.fail("root backend not available")
        val result = executor.execute(
            listOf(
                "sh",
                "-c",
                "if [ \"$(getprop init.userspace_reboot.is_supported 2>/dev/null)\" = \"1\" ] && " +
                    "command -v setprop >/dev/null 2>&1; then printf SYS_POWERCTL_USERSPACE; " +
                    "elif [ -x /data/adb/ksud ]; then " +
                    "help=\$(/data/adb/ksud --help 2>&1; /data/adb/ksud reboot --help 2>&1); " +
                    "case \"\$help\" in *soft-reboot*|*soft_reboot*|*userspace*) printf KSUD_SOFT_REBOOT ;; " +
                    "*) printf KSUD_UNVERIFIED ;; esac; " +
                    "elif command -v setprop >/dev/null 2>&1 || " +
                    "{ command -v stop >/dev/null 2>&1 && command -v start >/dev/null 2>&1; }; " +
                    "then printf ZYGOTE_RESTART; fi",
            ),
        )
        return when (result.stdout.trim()) {
            "SYS_POWERCTL_USERSPACE" -> CapabilityResult.pass("init.userspace_reboot.is_supported=1; AOSP userspace restart selected")
            "KSUD_SOFT_REBOOT" -> CapabilityResult.pass("KernelSU advertises a userspace soft-reboot command")
            "KSUD_UNVERIFIED" -> CapabilityResult.warn("KernelSU soft reboot is present but unverified", "/data/adb/ksud")
            "ZYGOTE_RESTART" -> CapabilityResult.warn("Zygote restart requires explicit user opt-in", "kernel reboot is not used")
            else -> CapabilityResult.fail(
                "no userspace or framework restart mechanism is available",
                "exit=${result.exitCode} ${result.stderr.trim()}".trim(),
            )
        }
    }

    override suspend fun softReboot(): OperationResult {
        val support = supportsSoftReboot()
        if (support.status == dev.degoogle.app.domain.CapabilityStatus.FAIL ||
            support.status == dev.degoogle.app.domain.CapabilityStatus.UNKNOWN ||
            (support.status == dev.degoogle.app.domain.CapabilityStatus.WARN && support.evidence != "kernel reboot is not used")
        ) {
            return OperationResult(
                succeeded = false,
                message = support.reason,
                exitCode = 5,
            )
        }
        val result = executor.execute(
            listOf(
                "sh",
                "-c",
                "if [ \"$(getprop init.userspace_reboot.is_supported 2>/dev/null)\" = 1 ] && command -v setprop >/dev/null 2>&1; then " +
                    "setprop sys.powerctl reboot,userspace; " +
                    "elif [ -x /data/adb/ksud ]; then /data/adb/ksud reboot; " +
                    "elif command -v setprop >/dev/null 2>&1; then setprop ctl.restart zygote; " +
                    "else stop && start; fi",
            ),
        )
        return OperationResult(
            succeeded = result.succeeded,
            message = if (result.succeeded) "soft reboot requested" else result.stderr.ifBlank { "soft reboot failed" },
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
