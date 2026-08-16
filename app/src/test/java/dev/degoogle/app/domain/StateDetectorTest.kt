package dev.degoogle.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Casos mínimos exigidos pelo spec, com o estado derivado de fatos reais
 * (o mock do RootExecutor acontece na camada acima; aqui testamos a
 * derivação pura).
 */
class StateDetectorTest {

    private val profile = DeviceProfiles.SUPPORTED.first()

    private fun facts(block: FactsBuilder.() -> Unit): SystemFacts =
        FactsBuilder(profile).apply(block).build()

    // 1. root ausente
    @Test
    fun `root ausente vira NO_ROOT`() {
        val f = facts { rootOk = false }
        assertEquals(DeviceState.NO_ROOT, StateDetector.detect(f, profile))
    }

    // 2. device incompatível
    @Test
    fun `device incompatível vira UNSUPPORTED`() {
        val f = facts {
            rootOk = true
            manufacturer = "xiaomi"
            model = "POCO X3"
            profileMatch = false
        }
        assertEquals(DeviceState.UNSUPPORTED, StateDetector.detect(f, profile))
    }

    // 3. stock correto
    @Test
    fun `stock correto vira STOCK`() {
        val f = facts {
            gmsPath = "${profile.gmsSystemDir}/GmsCore/GmsCore.apk"
            gsfPath = "${profile.gsfSystemDir}/GoogleServicesFramework/GoogleServicesFramework.apk"
            storePath = "${profile.storeSystemDir}/Phonesky.apk"
        }
        assertEquals(DeviceState.STOCK, StateDetector.detect(f, profile))
    }

    // 4. GMS em /data/app
    @Test
    fun `GMS em data app vira ERROR`() {
        val f = facts {
            gmsPath = "/data/app/com.google.android.gms-1/base.apk"
            gsfPath = "${profile.gsfSystemDir}/GoogleServicesFramework.apk"
            storePath = "${profile.storeSystemDir}/Phonesky.apk"
        }
        assertEquals(DeviceState.ERROR, StateDetector.detect(f, profile))
    }

    // 4b. atualização legítima em /data/app, confirmada pelo locator
    @Test
    fun `updates confirmados em data app mantêm estado STOCK`() {
        val gmsUpdate = "/data/app/~~gms/com.google.android.gms-1/base.apk"
        val storeUpdate = "/data/app/~~store/com.android.vending-1/base.apk"
        val f = facts {
            gmsPath = gmsUpdate
            gmsPackage = SystemPackageInfo(
                packageName = "com.google.android.gms",
                activeCodePath = gmsUpdate,
                hasDataUpdate = true,
            )
            gsfPath = "${profile.gsfSystemDir}/GoogleServicesFramework/GoogleServicesFramework.apk"
            storePath = storeUpdate
            storePackage = SystemPackageInfo(
                packageName = "com.android.vending",
                activeCodePath = storeUpdate,
                hasDataUpdate = true,
            )
        }
        assertEquals(DeviceState.STOCK, StateDetector.detect(f, profile))
    }

    @Test
    fun `path inesperado em pacote presente não vira STOCK`() {
        val f = facts {
            gmsPath = "${profile.gmsSystemDir}/GmsCore/GmsCore.apk"
            gsfPath = "/data/local/tmp/GoogleServicesFramework.apk"
            storePath = "${profile.storeSystemDir}/Phonesky.apk"
        }
        assertEquals(DeviceState.ERROR, StateDetector.detect(f, profile))
    }

    // 5. GMS mascarado + GSF ainda presente (pré-reboot)
    @Test
    fun `GMS mascarado com GSF presente vira PREPARED`() {
        val f = facts {
            mountedByUs()
            gmsPath = "${profile.gmsSystemDir}/GmsCore/GmsCore.apk"
            gsfPath = "${profile.gsfSystemDir}/GoogleServicesFramework.apk"
            storePath = "${profile.storeSystemDir}/Phonesky.apk"
        }
        assertEquals(DeviceState.PREPARED, StateDetector.detect(f, profile))
    }

    // 6. GMS mascarado + GSF ausente + finalize pendente
    @Test
    fun `GMS mascarado com GSF ausente e finalize pendente vira MICROG_BOOTED`() {
        val f = facts {
            mountedByUs()
            gmsPath = "${profile.gmsSystemDir}/GmsCore/GmsCore.apk"
            gmsPrivileged = true
            gsfPath = null
            storePath = "${profile.storeSystemDir}/Companion.apk"
            finalizeDone = false
        }
        assertEquals(DeviceState.MICROG_BOOTED, StateDetector.detect(f, profile))
    }

// 7. Companion ausente
    @Test
    fun `microG ativo mas Companion ausente vira ERROR`() {
        val f = facts {
            mountedByUs()
            gmsPath = "${profile.gmsSystemDir}/GmsCore/GmsCore.apk"
            gmsPrivileged = true
            gsfPath = null
            storePath = null
            finalizeDone = true
        }
        assertEquals(DeviceState.ERROR, StateDetector.detect(f, profile))
    }

    // 8. backup ausente
    @Test
    fun `microG ativo sem backup vira MICROG_ACTIVE`() {
        val f = facts {
            mountedByUs()
            gmsPath = "${profile.gmsSystemDir}/GmsCore/GmsCore.apk"
            gmsPrivileged = true
            gsfPath = null
            storePath = "${profile.storeSystemDir}/Companion.apk"
            finalizeDone = true
        }
        assertEquals(DeviceState.MICROG_ACTIVE, StateDetector.detect(f, profile))
    }

    // 9. backup presente
    @Test
    fun `microG ativo com backup presente vira MICROG_ACTIVE_BACKED_UP`() {
        val f = facts {
            mountedByUs()
            gmsPath = "${profile.gmsSystemDir}/GmsCore/GmsCore.apk"
            gmsPrivileged = true
            gsfPath = null
            storePath = "${profile.storeSystemDir}/Companion.apk"
            finalizeDone = true
            backupPresent = true
        }
        assertEquals(DeviceState.MICROG_ACTIVE_BACKED_UP, StateDetector.detect(f, profile))
    }

    // 10. mount parcial
    @Test
    fun `mount parcial vira ERROR`() {
        val f = facts {
            mountGms = true
            mountGsf = false
            mountStore = true
            gmsPath = "${profile.gmsSystemDir}/GmsCore/GmsCore.apk"
        }
        assertEquals(DeviceState.ERROR, StateDetector.detect(f, profile))
    }

    // 11b. mounts de fonte desconhecida (ex.: microg-mask do script antigo)
    @Test
    fun `mounts de fonte desconhecida vira ERROR`() {
        val f = facts {
            mountGms = true; mountGsf = true; mountStore = true
            mountGmsIsOurs = false
            mountGsfIsOurs = false
            mountStoreIsOurs = false
            gmsPath = "${profile.gmsSystemDir}/GmsCore/GmsCore.apk"
            gmsPrivileged = true
            gsfPath = null
            storePath = "${profile.storeSystemDir}/Companion.apk"
        }
        assertEquals(DeviceState.ERROR, StateDetector.detect(f, profile))
    }

    // 12. rollback parcial (sem mounts, GMS ainda da máscara)
    @Test
    fun `sem mounts com GMS ainda da máscara vira RESTORE_PREPARED`() {
        val f = facts {
            gmsPath = "${profile.gmsSystemDir}/GmsCore/GmsCore.apk"
            gsfPath = null
            storePath = null
        }
        assertEquals(DeviceState.RESTORE_PREPARED, StateDetector.detect(f, profile))
    }

    // 13. GMS da máscara mas não privileged
    @Test
    fun `GMS da máscara mas não privileged vira ERROR`() {
        val f = facts {
            mountedByUs()
            gmsPath = "${profile.gmsSystemDir}/GmsCore/GmsCore.apk"
            gmsPrivileged = false
            gsfPath = null
            storePath = "${profile.storeSystemDir}/Companion.apk"
        }
        assertEquals(DeviceState.ERROR, StateDetector.detect(f, profile))
    }
}

/** Factory de SystemFacts com defaults = stock saudável. */
private class FactsBuilder(private val profile: DeviceProfile) {
    var rootOk = true
    var rootManager = "KernelSU"
    var profileMatch = true
    var manufacturer = "samsung"
    var model = "SM-S928B"
    var device = "e3q"
    var product = "e3qxxx"
    var androidSdk = "36"
    var androidRelease = "16"
    var fingerprint = "samsung/e3qxxx/e3q:16/UP1A.231005.007:user/release-keys"
    var selinux = "Enforcing"
    var abi = "arm64-v8a"
    var gmsPath: String? = null
    var gmsPackage: SystemPackageInfo? = null
    var gmsVersion: String? = null
    var gmsVersionCode: String? = null
    var gmsUid: String? = null
    var gmsFlags: String? = null
    var gmsPrivileged = false
    var gsfPath: String? = null
    var gsfPackage: SystemPackageInfo? = null
    var storePath: String? = null
    var storePackage: SystemPackageInfo? = null
    var storeVersion: String? = null
    var mountGms = false
    var mountGmsIsOurs = false
    var mountGsf = false
    var mountGsfIsOurs = false
    var mountStore = false
    var mountStoreIsOurs = false
    var mountGmsSource: String? = null

    fun mountedByUs() {
        mountGms = true; mountGmsIsOurs = true
        mountGsf = true; mountGsfIsOurs = true
        mountStore = true; mountStoreIsOurs = true
    }
    var backupPresent = false
    var finalizeDone = false

    fun build(): SystemFacts = SystemFacts(
        rootOk = rootOk,
        rootManager = rootManager,
        profileId = profile.id,
        profileMatch = profileMatch,
        manufacturer = manufacturer,
        model = model,
        device = device,
        product = product,
        androidSdk = androidSdk,
        androidRelease = androidRelease,
        fingerprint = fingerprint,
        selinux = selinux,
        abi = abi,
        gmsPath = gmsPath,
        gmsPackage = gmsPackage,
        gmsVersion = gmsVersion,
        gmsVersionCode = gmsVersionCode,
        gmsUid = gmsUid,
        gmsFlags = gmsFlags,
        gmsPrivileged = gmsPrivileged,
        gsfPath = gsfPath,
        gsfPackage = gsfPackage,
        storePath = storePath,
        storePackage = storePackage,
        storeVersion = storeVersion,
        mountGms = mountGms,
        mountGmsIsOurs = mountGmsIsOurs,
        mountGsf = mountGsf,
        mountGsfIsOurs = mountGsfIsOurs,
        mountStore = mountStore,
        mountStoreIsOurs = mountStoreIsOurs,
        mountGmsSource = mountGmsSource,
        backupPresent = backupPresent,
        finalizeDone = finalizeDone,
        shellState = null,
    )
}
