package dev.degoogle.app.reboot

import dev.degoogle.app.root.BackendRunner

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
    /** Soft reboot (userspace). false = aparelho não suporta; nunca reboot completo. */
    suspend fun softReboot(): Boolean
}

class BackendRebootController(
    private val backend: BackendRunner,
) : RebootController {

    override suspend fun softReboot(): Boolean = backend.softReboot().succeeded
}
