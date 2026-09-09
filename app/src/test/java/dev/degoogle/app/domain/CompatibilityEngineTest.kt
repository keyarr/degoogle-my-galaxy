package dev.degoogle.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.Json

class CompatibilityEngineTest {

    private fun facts(
        fingerprint: String = "samsung/e3qxxx/e3q:16/UP1A.231005.007/S928BXXU1AXK1:user/release-keys",
        capabilities: Map<Capability, CapabilityResult> = allPassCapabilities(),
    ) = SystemFacts(
        rootOk = true,
        rootManager = "KernelSU",
        profileId = "samsung_sm-s928b",
        profileMatch = true,
        manufacturer = "samsung",
        model = "SM-S928B",
        device = "e3q",
        product = "e3qxxx",
        androidSdk = "36",
        androidRelease = "16",
        fingerprint = fingerprint,
        selinux = "Enforcing",
        abi = "arm64-v8a",
        gmsPath = "/product/priv-app/GmsCore/GmsCore.apk",
        gmsVersion = "stock",
        gmsVersionCode = "1",
        gmsUid = "10123",
        gmsFlags = "PRIVILEGED SYSTEM",
        gmsPrivileged = true,
        gsfPath = "/system_ext/priv-app/GoogleServicesFramework/GoogleServicesFramework.apk",
        storePath = "/product/priv-app/Phonesky/Phonesky.apk",
        storeVersion = "stock",
        mountGms = false,
        mountGmsIsOurs = false,
        mountGsf = false,
        mountGsfIsOurs = false,
        mountStore = false,
        mountStoreIsOurs = false,
        mountGmsSource = null,
        backupPresent = false,
        finalizeDone = false,
        shellState = DeviceState.STOCK,
        buildId = "UP1A.231005.007",
        oneUiVersion = "8.5",
        capabilityResults = capabilities,
    )

    @Test
    fun `match exato mais capabilities pass gera SUPPORTED`() {
        val decision = CompatibilityEngine.evaluate(facts())
        assertEquals(DeviceCompatibility.SUPPORTED, decision.compatibility)
        assertTrue(decision.canExecuteNormally)
        assertFalse(decision.canExecuteExperimental)
    }

    @Test
    fun `updates em data app não transformam match exato em UNSUPPORTED`() {
        val gmsUpdate = "/data/app/~~gms/com.google.android.gms-1/base.apk"
        val storeUpdate = "/data/app/~~store/com.android.vending-1/base.apk"
        val decision = CompatibilityEngine.evaluate(
            facts().copy(
                gmsPath = gmsUpdate,
                gmsPackage = SystemPackageInfo(
                    packageName = "com.google.android.gms",
                    activeCodePath = gmsUpdate,
                    hasDataUpdate = true,
                ),
                storePath = storeUpdate,
                storePackage = SystemPackageInfo(
                    packageName = "com.android.vending",
                    activeCodePath = storeUpdate,
                    hasDataUpdate = true,
                ),
            ),
        )
        assertTrue(decision.knownGood.level == KnownGoodMatch.EXACT_MATCH)
        assertTrue(decision.compatibility != DeviceCompatibility.UNSUPPORTED)
    }

    @Test
    fun `GSF ausente sob máscara conhecida não invalida estado ativo`() {
        val decision = CompatibilityEngine.evaluate(
            facts().copy(
                gmsPath = "/product/priv-app/GmsCore/GmsCore.apk",
                gsfPath = null,
                storePath = "/product/priv-app/Phonesky/Companion.apk",
                mountGms = true,
                mountGmsIsOurs = true,
                mountGsf = true,
                mountGsfIsOurs = true,
                mountStore = true,
                mountStoreIsOurs = true,
            ),
        )
        assertEquals(DeviceCompatibility.SUPPORTED, decision.compatibility)
        assertTrue(decision.reasons.none { it.startsWith("gsf: expected=") })
    }

    @Test
    fun `maskable com update ativo é WARN e não UNKNOWN`() {
        val gmsUpdate = "/data/app/~~gms/com.google.android.gms-1/base.apk"
        val matrix = CapabilityEngine.evaluate(
            facts(capabilities = emptyMap()).copy(
                gmsPath = gmsUpdate,
                gmsPackage = SystemPackageInfo(
                    packageName = "com.google.android.gms",
                    activeCodePath = gmsUpdate,
                    hasDataUpdate = true,
                ),
            ),
        )
        assertEquals(CapabilityStatus.WARN, matrix[Capability.GMS_MASKABLE].status)
    }

    @Test
    fun `firmware não homologado não vira supported por semelhança`() {
        val decision = CompatibilityEngine.evaluate(
            facts(fingerprint = "samsung/e3qxxx/e3q:16/NEW_BUILD:user/release-keys"),
        )
        assertEquals(DeviceCompatibility.PROBABLY_SUPPORTED, decision.compatibility)
        assertFalse(decision.canExecuteNormally)
        assertTrue(decision.canExecuteExperimental)
    }

    @Test
    fun `unknown em capability crítica exige perfil`() {
        val capabilities = allPassCapabilities().toMutableMap()
        capabilities[Capability.SIGNATURE_SPOOFING] = CapabilityResult.unknown("fixture sem prova")
        val decision = CompatibilityEngine.evaluate(facts(capabilities = capabilities))
        assertEquals(DeviceCompatibility.REQUIRES_PROFILE, decision.compatibility)
        assertFalse(decision.canExecuteNormally)
        assertFalse(decision.canExecuteExperimental)
    }

    @Test
    fun `falha de restore torna ambiente unsafe`() {
        val capabilities = allPassCapabilities().toMutableMap()
        capabilities[Capability.SAFE_RESTORE] = CapabilityResult.fail("fixture sem rollback")
        val decision = CompatibilityEngine.evaluate(facts(capabilities = capabilities))
        assertEquals(DeviceCompatibility.UNSAFE, decision.compatibility)
        assertFalse(decision.canExecuteExperimental)
    }

    @Test
    fun `path fora das raízes permitido não passa no locator`() {
        val info = PackageLocator.parse(
            packageName = "com.google.android.gms",
            pmPathOutput = "package:/data/local/tmp/foo/base.apk\n",
        )
        assertFalse(info.safeTarget)
        val capabilities = allPassCapabilities().toMutableMap()
        capabilities[Capability.GMS_MASKABLE] = CapabilityResult.fail("target fora das raízes")
        val decision = CompatibilityEngine.evaluate(facts(capabilities = capabilities))
        assertEquals(DeviceCompatibility.UNSUPPORTED, decision.compatibility)
    }

    @Test
    fun `sem root continua bloqueado`() {
        val decision = CompatibilityEngine.evaluate(
            facts(capabilities = emptyMap()).copy(
                rootOk = false,
                manufacturer = "",
                model = "",
                fingerprint = "",
            ),
        )
        assertEquals(DeviceCompatibility.UNSUPPORTED, decision.compatibility)
        assertFalse(decision.canExecuteNormally)
        assertFalse(decision.canExecuteExperimental)
    }

    @Test
    fun `S24 com reboot WARN não libera experimental`() {
        val capabilities = allPassCapabilities().toMutableMap()
        capabilities[Capability.SAFE_SOFT_REBOOT] = CapabilityResult.warn(
            "estrategia existe, mas firmware não foi homologado",
            "KSUD_SOFT_REBOOT",
        )
        val decision = CompatibilityEngine.evaluate(
            facts(
                fingerprint = "samsung/e3qxxx/e3q:16/BP4A.251205.006/S928BXXS6DZG1:user/release-keys",
                capabilities = capabilities,
            ).copy(
                rebootStrategy = RebootStrategy(RootBackendType.KERNELSU, "KSUD_SOFT_REBOOT", Confidence.LOW, false),
            ),
        )
        assertEquals(KnownGoodMatch.FIRMWARE_FAMILY_MATCH, decision.knownGood.level)
        assertEquals(DeviceCompatibility.PROBABLY_SUPPORTED, decision.compatibility)
        assertFalse(decision.canExecuteNormally)
        assertFalse(decision.canExecuteExperimental)
    }

    @Test
    fun `S25 com update não cai em UNKNOWN quando shell diz UNKNOWN`() {
        val gmsUpdate = "/data/app/~~x/com.google.android.gms-1/base.apk"
        val storeUpdate = "/data/app/~~y/com.android.vending-1/base.apk"
        val capabilities = allPassCapabilities().toMutableMap()
        capabilities[Capability.GMS_MASKABLE] = CapabilityResult.unknown("GMS: caminho original de sistema ambíguo")
        capabilities[Capability.STORE_MASKABLE] = CapabilityResult.unknown("STORE: caminho original de sistema ambíguo")
        capabilities[Capability.SAFE_SOFT_REBOOT] = CapabilityResult.warn(
            "estrategia existe, mas firmware não foi homologado",
            "KSUD_SOFT_REBOOT",
        )
        val decision = CompatibilityEngine.evaluate(
            facts(
                fingerprint = "samsung/pa3qxxx/pa3q:16/BP4A.251205.006/S938BXXSBCZG3_OXMBCZG3:user/release-keys",
                capabilities = capabilities,
            ).copy(
                model = "SM-S938B",
                device = "pa3q",
                product = "pa3qxxx",
                gmsPath = gmsUpdate,
                gmsPackage = SystemPackageInfo(
                    packageName = "com.google.android.gms",
                    activeCodePath = gmsUpdate,
                    originalSystemPath = null,
                    targetDirectory = "/data/app/~~x/com.google.android.gms-1",
                    hasDataUpdate = true,
                ),
                gsfPath = "/system_ext/priv-app/GoogleServicesFramework/GoogleServicesFramework.apk",
                gsfPackage = SystemPackageInfo(
                    packageName = "com.google.android.gsf",
                    activeCodePath = "/system_ext/priv-app/GoogleServicesFramework/GoogleServicesFramework.apk",
                    originalSystemPath = "/system_ext/priv-app/GoogleServicesFramework/GoogleServicesFramework.apk",
                    targetDirectory = "/system_ext/priv-app/GoogleServicesFramework",
                ),
                storePath = storeUpdate,
                storePackage = SystemPackageInfo(
                    packageName = "com.android.vending",
                    activeCodePath = storeUpdate,
                    originalSystemPath = null,
                    targetDirectory = "/data/app/~~y/com.android.vending-1",
                    hasDataUpdate = true,
                ),
            ),
        )
        assertEquals(KnownGoodMatch.NO_MATCH, decision.knownGood.level)
        assertEquals(CapabilityStatus.WARN, decision.matrix[Capability.GMS_MASKABLE].status)
        assertEquals(CapabilityStatus.WARN, decision.matrix[Capability.STORE_MASKABLE].status)
        assertEquals(DeviceCompatibility.PROBABLY_SUPPORTED, decision.compatibility)
        assertFalse(decision.canExecuteNormally)
        assertFalse(decision.canExecuteExperimental)
    }

    @Test
    fun `target fora da allowlist sem update continua bloqueando`() {
        val badPath = "/data/local/tmp/foo/base.apk"
        val matrix = CapabilityEngine.evaluate(
            facts(capabilities = emptyMap()).copy(
                gmsPath = badPath,
                gmsPackage = SystemPackageInfo(
                    packageName = "com.google.android.gms",
                    activeCodePath = badPath,
                    originalSystemPath = null,
                    targetDirectory = "/data/local/tmp/foo",
                    hasDataUpdate = false,
                ),
            ),
        )
        assertEquals(CapabilityStatus.FAIL, matrix[Capability.GMS_MASKABLE].status)
    }

    @Test
    fun `compatibility report é serializável para diagnóstico`() {
        val facts = facts()
        val decision = CompatibilityEngine.evaluate(facts)
        val json = Json { encodeDefaults = true }.encodeToString(
            CompatibilityReport.serializer(),
            decision.report(facts),
        )

        assertTrue(json.contains("\"schemaVersion\""))
        assertTrue(json.contains("\"capabilities\""))
        assertTrue(json.contains("SUPPORTED"))
    }

    private fun allPassCapabilities(): Map<Capability, CapabilityResult> =
        CapabilityMatrix.CRITICAL_CAPABILITIES.associateWith { CapabilityResult.pass("fixture") }
}
