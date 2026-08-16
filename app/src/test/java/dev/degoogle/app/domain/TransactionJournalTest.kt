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
            assertTrue(store.update("op-1", "fp", TransactionState.COMMITTED))
            assertFalse(store.requiresRecovery())
            assertEquals(1234L, store.read()?.timestamp)
        } finally {
            dir.deleteRecursively()
        }
    }
}
