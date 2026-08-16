package dev.degoogle.app.recovery

import dev.degoogle.app.domain.DeviceProfiles
import dev.degoogle.app.domain.DeviceState
import dev.degoogle.app.domain.SystemFacts
import dev.degoogle.app.root.BackendRunner
import dev.degoogle.app.root.RootExecutor
import dev.degoogle.app.root.RootResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoRecoveryCoordinatorTest {

    private class FakeExecutor(
        private val responses: ArrayDeque<RootResult>,
        val commands: MutableList<List<String>> = mutableListOf(),
    ) : RootExecutor {
        override suspend fun execute(command: List<String>): RootResult {
            commands += command
            return responses.removeFirst()
        }

        override suspend fun execute(command: List<String>, env: Map<String, String>): RootResult =
            execute(command)

        override suspend fun isRootAvailable(): Boolean = true
    }

    private val profile = DeviceProfiles.SUPPORTED.first()

    private fun stockFacts(): SystemFacts = SystemFacts.EMPTY.copy(
        rootOk = true,
        profileMatch = true,
        manufacturer = profile.manufacturer,
        model = profile.models.first(),
        androidSdk = "36",
        gmsPath = "${profile.gmsSystemDir}/GmsCore/GmsCore.apk",
        gsfPath = "${profile.gsfSystemDir}/GoogleServicesFramework.apk",
        storePath = "${profile.storeSystemDir}/Phonesky.apk",
    )

    @Test
    fun `rollback interrompido sem mounts solicita restore stock`() {
        val facts = stockFacts().copy(gsfPath = null, storePath = null)

        val assessment = RecoveryPolicy.assess(facts, profile)

        assertEquals(DeviceState.RESTORE_PREPARED, assessment.state)
        assertEquals(AutoRecoveryAction.RESTORE_STOCK, assessment.action)
    }

    @Test
    fun `mount legado conhecido solicita restore stock`() {
        val facts = stockFacts().copy(
            mountGms = true,
            mountGsf = true,
            mountStore = true,
            mountGmsIsLegacy = true,
            mountGsfIsLegacy = true,
            mountStoreIsLegacy = true,
        )

        val assessment = RecoveryPolicy.assess(facts, profile)

        assertEquals(DeviceState.ERROR, assessment.state)
        assertEquals(AutoRecoveryAction.RESTORE_STOCK, assessment.action)
    }

    @Test
    fun `stock com resíduos conhecidos solicita apenas limpeza`() {
        val assessment = RecoveryPolicy.assess(
            stockFacts().copy(maskResiduePresent = true),
            profile,
        )

        assertEquals(DeviceState.STOCK, assessment.state)
        assertEquals(AutoRecoveryAction.CLEAN_RESIDUE, assessment.action)
    }

    @Test
    fun `stock limpo não dispara operação`() {
        val assessment = RecoveryPolicy.assess(stockFacts(), profile)

        assertEquals(DeviceState.STOCK, assessment.state)
        assertEquals(AutoRecoveryAction.NONE, assessment.action)
    }

    @Test
    fun `mount externo não é assumido como recuperável`() {
        val facts = stockFacts().copy(
            mountGms = true,
            mountGsf = true,
            mountStore = true,
        )

        val assessment = RecoveryPolicy.assess(facts, profile)

        assertEquals(DeviceState.ERROR, assessment.state)
        assertEquals(AutoRecoveryAction.NONE, assessment.action)
    }

    @Test
    fun `coordenador executa restore e soft reboot sem intervenção`() = runTest {
        val probe = """
            DEGOOGLE_ROOT_OK=1
            DEGOOGLE_PROFILE_MATCH=1
            DEGOOGLE_MANUFACTURER=samsung
            DEGOOGLE_MODEL=SM-S928B
            DEGOOGLE_ANDROID_SDK=36
            DEGOOGLE_GMS_PATH=/product/priv-app/GmsCore/GmsCore.apk
            DEGOOGLE_GSF_PATH=
            DEGOOGLE_STORE_PATH=
        """.trimIndent()
        val fake = FakeExecutor(
            ArrayDeque(
                listOf(
                    RootResult.Ok(0, probe, ""),
                    RootResult.Ok(0, "DEGOOGLE_STATE=RESTORE_PREPARED\n", ""),
                    RootResult.Ok(0, "DEGOOGLE_STATE=RESTORE_PREPARED\n", ""),
                ),
            ),
        )
        val backend = BackendRunner(fake, "/data/local/tmp/degoogle.sh")

        val result = AutoRecoveryCoordinator(backend).runIfNeeded()

        assertTrue(result.succeeded)
        assertEquals(AutoRecoveryStatus.REBOOT_REQUESTED, result.status)
        assertEquals(
            listOf(
                listOf("sh", "/data/local/tmp/degoogle.sh", "probe"),
                listOf("sh", "/data/local/tmp/degoogle.sh", "restore-stock", "--wipe-data"),
                listOf("sh", "/data/local/tmp/degoogle.sh", "soft-reboot"),
            ),
            fake.commands,
        )
    }
}
