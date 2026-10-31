package dev.degoogle.app.recovery

import dev.degoogle.app.domain.DeviceState
import dev.degoogle.app.domain.SystemFacts
import dev.degoogle.app.domain.TransactionJournal
import dev.degoogle.app.domain.TransactionJournalStore
import dev.degoogle.app.domain.TransactionReconciliation
import dev.degoogle.app.domain.TransactionState
import dev.degoogle.app.root.BackendCommandResult
import dev.degoogle.app.root.BackendRunner
import dev.degoogle.app.root.RootExecutor
import dev.degoogle.app.root.RootResult
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PostBootFailurePropagationTest {

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
    fun `post-boot-validate falho propaga motivo para o journal e bloqueia reconciliacao ativa`() = runTest {
        val dir = Files.createTempDirectory("degoogle-post-boot-test").toFile()
        try {
            val store = TransactionJournalStore(dir.resolve("journal.json"))
            val operationId = "op-initial-123"
            val fingerprint = "samsung/sm-s928b/user"

            // 1. Simula estado prévio pós soft reboot: journal em REBOOT_REQUESTED
            store.update(
                operationId = operationId,
                fingerprint = fingerprint,
                state = TransactionState.REBOOT_REQUESTED,
                detail = "soft reboot requested",
            )
            val preRebootJournal = store.read()!!
            assertEquals(TransactionState.REBOOT_REQUESTED, preRebootJournal.state)
            assertTrue(
                TransactionReconciliation.shouldCommitActiveState(
                    preRebootJournal,
                    DeviceState.MICROG_ACTIVE_BACKED_UP,
                ),
            )

            // 2. Simula backend postBootValidate retornando código 4 por rescue party
            val stdout = """
                DEGOOGLE_ROOT_OK=1
                DEGOOGLE_STATE=MICROG_ACTIVE_BACKED_UP
                DEGOOGLE_POST_BOOT_STATUS=FAIL
                DEGOOGLE_POST_BOOT_REASON=boot reason indica Rescue Party: reboot,rescueparty
            """.trimIndent()
            val stderr = "ERRO: boot reason indica Rescue Party: reboot,rescueparty\n"
            val fake = FakeExecutor(ArrayDeque(listOf(RootResult.Ok(4, stdout, stderr))))
            val runner = BackendRunner(fake, "/data/local/tmp/degoogle.sh")

            val postBootResult: BackendCommandResult = runner.postBootValidate(streamProgress = false)
            assertFalse(postBootResult.succeeded)
            assertEquals(4, postBootResult.exitCode)
            assertEquals("boot reason indica Rescue Party: reboot,rescueparty", postBootResult.facts.postBootReason)

            // 3. Simula lógica de tratamento de falha no AppViewModel:
            // preserva operationId, atualiza para ROLLBACK_REQUIRED com o motivo concreto
            val failureDetail = postBootResult.facts.postBootReason?.takeIf { it.isNotBlank() }
                ?: postBootResult.stderr.lineSequence().map(String::trim).lastOrNull(String::isNotEmpty)
                ?: "post-boot validation failed"

            assertTrue(
                store.update(
                    operationId = preRebootJournal.operationId,
                    fingerprint = fingerprint,
                    state = TransactionState.ROLLBACK_REQUIRED,
                    detail = failureDetail,
                ),
            )

            // 4. Verifica coerência do journal Kotlin
            val failedJournal = store.read()!!
            assertEquals(operationId, failedJournal.operationId)
            assertEquals(fingerprint, failedJournal.deviceFingerprint)
            assertEquals(TransactionState.ROLLBACK_REQUIRED, failedJournal.state)
            assertEquals("boot reason indica Rescue Party: reboot,rescueparty", failedJournal.detail)
            assertTrue(store.requiresRecovery())

            // 5. Verifica que shouldCommitActiveState agora é estritamente false
            assertFalse(
                TransactionReconciliation.shouldCommitActiveState(
                    failedJournal,
                    DeviceState.MICROG_ACTIVE_BACKED_UP,
                ),
            )
            assertFalse(
                TransactionReconciliation.shouldCommitActiveState(
                    failedJournal,
                    DeviceState.MICROG_ACTIVE,
                ),
            )

            // 6. Simula recuperação real para stock: confirmação de STOCK atualiza para RESTORED
            assertTrue(
                store.update(
                    operationId = failedJournal.operationId,
                    fingerprint = fingerprint,
                    state = TransactionState.RESTORED,
                    detail = "stock state confirmed after rollback",
                ),
            )
            val restoredJournal = store.read()!!
            assertEquals(TransactionState.RESTORED, restoredJournal.state)
            assertFalse(store.requiresRecovery())
        } finally {
            dir.deleteRecursively()
        }
    }
}
