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
