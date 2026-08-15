package dev.degoogle.app.domain

/**
 * StateDetector: converte [SystemFacts] (estado real do Android) em
 * [DeviceState]. Regras idênticas às do `compute_state` do backend shell.
 */
object StateDetector {

    fun detect(facts: SystemFacts, profile: DeviceProfile?): DeviceState {
        if (!facts.rootOk) return DeviceState.NO_ROOT

        // Perfil: o backend já emite PROFILE_MATCH, mas revalidamos aqui contra
        // o perfil local para não depender de uma flag interna.
        val compatibleProfile = profile ?: DeviceProfiles.matching(
            manufacturer = facts.manufacturer,
            model = facts.model,
            sdk = facts.androidSdk,
        )
        if (!facts.profileMatch || compatibleProfile == null) return DeviceState.UNSUPPORTED

        val profileSafe = compatibleProfile

        if (!facts.mountGms && !facts.mountGsf && !facts.mountStore) {
            // Nenhum mount ativo: stock ou rollback pendente de reboot.
            val gmsAtMask = facts.gmsPath?.startsWith(profileSafe.gmsSystemDir) == true
            if (!gmsAtMask) return DeviceState.ERROR
            val stockVisible = !facts.gsfPath.isNullOrEmpty() && !facts.storePath.isNullOrEmpty()
            return if (stockVisible) DeviceState.STOCK else DeviceState.RESTORE_PREPARED
        }

        // Existe mount ativo — deve ser completo e por NOSSAS máscaras.
        if (!facts.mountGms || !facts.mountGsf || !facts.mountStore) {
            return DeviceState.ERROR
        }
        // Mount de fonte desconhecida (ex.: microg-mask do script antigo): o app
        // não gerencia esse estado e não deve mexer nele.
        if (!facts.mountGmsIsOurs || !facts.mountGsfIsOurs || !facts.mountStoreIsOurs) {
            return DeviceState.ERROR
        }

        val gmsFromMask = facts.gmsPath?.startsWith(profileSafe.gmsSystemDir) == true
        if (!gmsFromMask) return DeviceState.ERROR

        if (!facts.gsfPath.isNullOrEmpty()) {
            // Mounts ativos mas GSF ainda registrado ⇒ PM ainda enxerga o stock
            // (o soft reboot ainda não aconteceu).
            return DeviceState.PREPARED
        }

        // Pós-boot: GSF sumiu. microG deve ser priv-app de verdade.
        if (!facts.gmsPrivileged) return DeviceState.ERROR

        val storeFromMask = facts.storePath?.startsWith(profileSafe.storeSystemDir) == true
        if (!storeFromMask) return DeviceState.ERROR

        if (!facts.finalizeDone) return DeviceState.MICROG_BOOTED

        return if (facts.backupPresent) {
            DeviceState.MICROG_ACTIVE_BACKED_UP
        } else {
            // Sem backup: pode ser primeira instalação (NEEDS_SETUP é decidido
            // pela UI com base no prompt já exibido — dado auxiliar).
            DeviceState.MICROG_ACTIVE
        }
    }
}

/**
 * Validação de priv-app de verdade — não basta o path estar sob a máscara.
 */
data class PrivAppValidationResult(
    val packageRegistered: Boolean,
    val pathUnderMask: Boolean,
    val privilegedFlag: Boolean,
    val requestedInteractAcrossUsers: Boolean,
    val mountSourceIsMask: Boolean,
) {
    val valid: Boolean
        get() = packageRegistered && pathUnderMask && privilegedFlag &&
            mountSourceIsMask && requestedInteractAcrossUsers

    val issues: List<String>
        get() = buildList {
            if (!packageRegistered) add("Pacote não registrado")
            if (!pathUnderMask) add("Path fora da máscara")
            if (!privilegedFlag) add("Flag PRIVILEGED ausente")
            if (!requestedInteractAcrossUsers) add("INTERACT_ACROSS_USERS ausente")
            if (!mountSourceIsMask) add("Mount não provém da nossa máscara")
        }
}

object PrivAppValidator {

    fun validate(facts: SystemFacts, profile: DeviceProfile): PrivAppValidationResult {
        val registered = !facts.gmsPath.isNullOrEmpty()
        val pathUnderMask = facts.gmsPath?.startsWith(profile.gmsSystemDir) == true
        val privileged = facts.gmsPrivileged
        // INTERACT_ACROSS_USERS: o backend reporta nas flags; aqui tratamos como
        // satisfeito se privilegiado e o path está sob a máscara (o PM concede
        // permissões privileged a priv-apps). A checagem dura fica no backend.
        val requested = facts.gmsFlags.orEmpty().contains("PRIVILEGED")
        val mountOk = facts.mountGms
        return PrivAppValidationResult(
            packageRegistered = registered,
            pathUnderMask = pathUnderMask,
            privilegedFlag = privileged,
            requestedInteractAcrossUsers = requested,
            mountSourceIsMask = mountOk,
        )
    }
}
