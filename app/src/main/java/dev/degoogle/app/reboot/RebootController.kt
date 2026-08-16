package dev.degoogle.app.reboot

import dev.degoogle.app.root.BackendRunner
import dev.degoogle.app.root.RebootSource

/**
 * Controlador de reboot.
 *
 * Público-alvo: aparelhos com root obtido via EXPLOIT — o kernel reboot
 * destrói o root e o microG. Por isso o ÚNICO reboot suportado é o soft
 * reboot (userspace): `sys.powerctl=reboot,userspace`, que preserva o
 * kernel (exploit residente), os mounts e o root.
 *
 * O app NUNCA assume que o reboot aconteceu: após qualquer abertura o estado
 * é re-derivado do sistema.
 */
interface RebootController {
    /** Soft reboot (userspace), with a reason when no reboot was started. */
    suspend fun softReboot(): SoftRebootResult
}

enum class SoftRebootFailure {
    COOLDOWN,
    UNSUPPORTED,
    FAILED,
}

data class SoftRebootResult(
    val succeeded: Boolean,
    val exitCode: Int,
    val failure: SoftRebootFailure? = null,
    val detail: String = "",
)

class BackendRebootController(
    private val backend: BackendRunner,
) : RebootController {

    override suspend fun softReboot(): SoftRebootResult {
        val result = backend.softReboot(RebootSource.MANUAL)
        return SoftRebootResult(
            succeeded = result.succeeded,
            exitCode = result.exitCode,
            failure = if (result.succeeded) {
                null
            } else {
                when (result.exitCode) {
                    BackendRunner.ExitCodes.SOFT_REBOOT_COOLDOWN -> SoftRebootFailure.COOLDOWN
                    BackendRunner.ExitCodes.UNSUPPORTED -> SoftRebootFailure.UNSUPPORTED
                    else -> SoftRebootFailure.FAILED
                }
            },
            detail = result.stderr,
        )
    }
}
