package dev.degoogle.app.domain

/**
 * StateDetector: converte [SystemFacts] (estado real do Android) em
 * [DeviceState]. Regras idênticas às do `compute_state` do backend shell.
 */
object StateDetector {

    fun detect(facts: SystemFacts, profile: DeviceProfile?): DeviceState {
        if (!facts.rootOk) return DeviceState.NO_ROOT

        // PROFILE_MATCH é telemetria legada do backend. A fonte de verdade da
        // UI é o perfil local, que também valida fabricante, modelo e SDK. Em
        // um estado pós-procedimento o PackageManager pode ter sido reindexado
        // por uma versão diferente do backend; não transforme essa flag stale
        // em UNSUPPORTED quando o perfil local e as provas operacionais batem.
        val localProfile = DeviceProfiles.matching(
            manufacturer = facts.manufacturer,
            model = facts.model,
            sdk = facts.androidSdk,
        )
        val compatibleProfile = profile?.takeIf { it.id == localProfile?.id } ?: localProfile
        if (compatibleProfile == null) return DeviceState.UNSUPPORTED

        val profileSafe = compatibleProfile

        if (!facts.mountGms && !facts.mountGsf && !facts.mountStore) {
            // Nenhum mount ativo: stock ou rollback pendente de reboot.
            //
            // Uma atualização legítima de um app de sistema aparece em
            // /data/app. Ela só é aceita quando o Package Locator também
            // confirmou hasDataUpdate; um path /data/app isolado continua
            // sendo tratado como estado inesperado.
            val gmsIsStock = isStockPackagePath(
                facts.gmsPath,
                profileSafe.gmsSystemDir,
                facts.gmsPackage,
            )
            if (!gmsIsStock) return DeviceState.ERROR

            val gsfPresent = !facts.gsfPath.isNullOrBlank()
            val storePresent = !facts.storePath.isNullOrBlank()
            if (!gsfPresent || !storePresent) return DeviceState.RESTORE_PREPARED

            val gsfIsStock = isStockPackagePath(
                facts.gsfPath,
                profileSafe.gsfSystemDir,
                facts.gsfPackage,
            )
            val storeIsStock = isStockPackagePath(
                facts.storePath,
                profileSafe.storeSystemDir,
                facts.storePackage,
            )
            return if (gsfIsStock && storeIsStock) DeviceState.STOCK else DeviceState.ERROR
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

    /**
     * Aceita o APK stock no diretório homologado ou uma atualização registrada
     * pelo Package Locator em /data/app. O segundo caso nunca é inferido só
     * pelo texto do path.
     */
    private fun isStockPackagePath(
        path: String?,
        systemDir: String,
        packageInfo: SystemPackageInfo?,
    ): Boolean {
        val actual = path?.takeIf { it.isNotBlank() } ?: return false
        val expectedRoot = systemDir.trimEnd('/')
        if (actual.startsWith("$expectedRoot/")) return true
        return actual.startsWith("/data/app/") && packageInfo?.hasDataUpdate == true
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
