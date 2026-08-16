package dev.degoogle.app.domain

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionJournalTest {
    @Test
    fun `journal é atômico e estados parciais exigem recuperação`() {
        val dir = Files.createTempDirectory("degoogle-journal").toFile()
        try {
            val store = TransactionJournalStore(dir.resolve("journal"), clock = { 1234L })
            assertTrue(store.update("op-1", "fp", TransactionState.GMS_MASKED))
            assertTrue(store.requiresRecovery())
            assertEquals(TransactionState.GMS_MASKED, store.read()?.state)
            assertTrue(store.update("op-1", "fp", TransactionState.REINDEX_PENDING))
            assertTrue(store.requiresRecovery())
            assertEquals(TransactionState.REINDEX_PENDING, store.read()?.state)
            assertTrue(store.update("op-1", "fp", TransactionState.STORE_MASKED))
            assertFalse(store.requiresRecovery())
            assertEquals(TransactionState.STORE_MASKED, store.read()?.state)
            assertTrue(store.update("op-1", "fp", TransactionState.COMMITTED))
            assertFalse(store.requiresRecovery())
            assertEquals(1234L, store.read()?.timestamp)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `journal do app usa diretório separado do backend root`() {
        val filesDir = Files.createTempDirectory("degoogle-app-files").toFile()
        try {
            val store = TransactionJournalStore.forAppFiles(filesDir)

            assertTrue(store.update("op-app", "fp", TransactionState.ROLLBACK_REQUIRED))
            assertTrue(filesDir.resolve("app-transaction/journal.json").isFile)
            assertEquals(TransactionState.ROLLBACK_REQUIRED, store.read()?.state)
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun `transação pós reboot ativa pode ser reconciliada`() {
        val journal = TransactionJournal(
            operationId = "op-active",
            timestamp = 1234L,
            deviceFingerprint = "fp",
            state = TransactionState.REBOOT_REQUESTED,
        )

        assertTrue(
            TransactionReconciliation.shouldCommitActiveState(
                journal,
                DeviceState.MICROG_ACTIVE_BACKED_UP,
            ),
        )
    }

    @Test
    fun `reconciliação não oculta rollback falho nem boot incompleto`() {
        val rollback = TransactionJournal(
            operationId = "op-rollback",
            timestamp = 1234L,
            deviceFingerprint = "fp",
            state = TransactionState.ROLLBACK_REQUIRED,
        )
        val booted = rollback.copy(state = TransactionState.REBOOT_REQUESTED)

        assertFalse(
            TransactionReconciliation.shouldCommitActiveState(
                rollback,
                DeviceState.MICROG_ACTIVE,
            ),
        )
        assertFalse(
            TransactionReconciliation.shouldCommitActiveState(
                booted,
                DeviceState.MICROG_BOOTED,
            ),
        )
    }
}
