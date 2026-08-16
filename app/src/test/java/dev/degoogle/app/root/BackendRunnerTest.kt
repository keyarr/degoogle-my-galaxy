package dev.degoogle.app.root

import dev.degoogle.app.domain.DeviceState
import dev.degoogle.app.domain.RootBackendType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mock do RootExecutor: confirma que o BackendRunner monta o argv correto e
 * que o stdout do backend é interpretado como protocolo de máquina.
 */
class BackendRunnerTest {

    private class FakeExecutor(
        var responses: ArrayDeque<RootResult> = ArrayDeque(),
        val recorded: MutableList<List<String>> = mutableListOf(),
    ) : RootExecutor {
        override suspend fun execute(command: List<String>): RootResult {
            recorded.add(command)
            return responses.removeFirstOrNull() ?: RootResult.Ok(0, "", "")
        }

        override suspend fun execute(command: List<String>, env: Map<String, String>): RootResult =
            execute(command)

        override suspend fun isRootAvailable(): Boolean = true
    }

    @Test
    fun `probe parseia fatos e estado`() = runTest {
        val out = """
            DEGOOGLE_ROOT_OK=1
            DEGOOGLE_ROOT_MANAGER=KernelSU
            DEGOOGLE_MODEL=SM-S928B
            DEGOOGLE_GMS_PATH=/product/priv-app/GmsCore/GmsCore.apk
            DEGOOGLE_GSF_PATH=
            DEGOOGLE_MOUNT_GMS=1
            DEGOOGLE_MOUNT_GSF=1
            DEGOOGLE_MOUNT_STORE=1
            DEGOOGLE_STATE=MICROG_BOOTED
        """.trimIndent()
        val fake = FakeExecutor(ArrayDeque(listOf(RootResult.Ok(0, out, ""))))
        val runner = BackendRunner(fake, "/data/local/tmp/degoogle.sh")

        val probe = runner.probe()
        assertTrue(probe.raw.succeeded)
        assertEquals(DeviceState.MICROG_BOOTED, probe.facts.shellState)
        assertTrue(probe.facts.mountGms)
        assertEquals("/product/priv-app/GmsCore/GmsCore.apk", probe.facts.gmsPath)
        assertEquals(RootBackendType.KERNELSU, probe.rootBackend.type)
        assertEquals(listOf("sh", "/data/local/tmp/degoogle.sh", "probe"), fake.recorded.single())
    }

    @Test
    fun `prepare passa argv correto com os APKs`() = runTest {
        val fake = FakeExecutor(
            ArrayDeque(
                listOf(RootResult.Ok(0, "DEGOOGLE_STATE=PREPARED\n", "")),
            ),
        )
        val runner = BackendRunner(fake, "/data/local/tmp/degoogle.sh")

        val r = runner.prepare("/data/user/0/dev.degoogle/files/downloads/gms.apk", "/path/com panion.apk")
        assertTrue(r.succeeded)
        assertEquals(
            listOf("sh", "/data/local/tmp/degoogle.sh", "prepare", "/data/user/0/dev.degoogle/files/downloads/gms.apk", "/path/com panion.apk"),
            fake.recorded.single(),
        )
    }

    @Test
    fun `exit code 3 vira falha com pré-condição`() = runTest {
        val fake = FakeExecutor(
            ArrayDeque(
                listOf(RootResult.Ok(3, "", "ERRO: GMS não está em /product/priv-app/GmsCore")),
            ),
        )
        val runner = BackendRunner(fake, "/data/local/tmp/degoogle.sh")
        val r = runner.prepare("/x.apk", "/y.apk")
        assertEquals(false, r.succeeded)
        assertEquals(3, r.exitCode)
        assertTrue(r.stderr.contains("GMS"))
    }

    @Test
    fun `progresso do stderr e encaminhado ao callback`() = runTest {
        val progress = mutableListOf<String>()
        val fake = FakeExecutor(
            ArrayDeque(
                listOf(
                    RootResult.Ok(
                        0,
                        "DEGOOGLE_STATE=PREPARED\n",
                        "===== PREPARE =====\n  mascarado: GMS\n",
                    ),
                ),
            ),
        )
        val runner = BackendRunner(
            executor = fake,
            backendPath = "/data/local/tmp/degoogle.sh",
            onProgress = progress::add,
        )

        runner.prepare("/x.apk", "/y.apk")

        assertEquals(listOf("===== PREPARE =====", "  mascarado: GMS"), progress)
    }
}
