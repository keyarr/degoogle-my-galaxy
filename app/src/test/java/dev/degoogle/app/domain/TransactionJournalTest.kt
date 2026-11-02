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

    @Test
    fun `transição para ROLLBACK_REQUIRED preserva operationId e detalhe do erro`() {
        val dir = Files.createTempDirectory("degoogle-journal-rollback").toFile()
        try {
            val store = TransactionJournalStore(dir.resolve("journal"), clock = { 5678L })
            assertTrue(
                store.update(
                    operationId = "op-42",
                    fingerprint = "fp-galaxy",
                    state = TransactionState.REBOOT_REQUESTED,
                    detail = "soft reboot requested",
                ),
            )
            val current = store.read()
            assertEquals("op-42", current?.operationId)
            assertEquals(TransactionState.REBOOT_REQUESTED, current?.state)

            // Falha na validação pós-boot (ex.: rescue party)
            val failureDetail = "boot reason indica Rescue Party: reboot,rescueparty"
            assertTrue(
                store.update(
                    operationId = current!!.operationId,
                    fingerprint = current.deviceFingerprint,
                    state = TransactionState.ROLLBACK_REQUIRED,
                    detail = failureDetail,
                ),
            )

            val updated = store.read()
            assertEquals("op-42", updated?.operationId)
            assertEquals("fp-galaxy", updated?.deviceFingerprint)
            assertEquals(TransactionState.ROLLBACK_REQUIRED, updated?.state)
            assertEquals(failureDetail, updated?.detail)
            assertTrue(store.requiresRecovery())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `ROLLBACK_REQUIRED impede shouldCommitActiveState mesmo com MICROG_ACTIVE_BACKED_UP`() {
        val journal = TransactionJournal(
            operationId = "op-rescue",
            timestamp = 1234L,
            deviceFingerprint = "fp",
            state = TransactionState.ROLLBACK_REQUIRED,
            detail = "boot reason indica Rescue Party: reboot,rescueparty",
        )

        assertFalse(
            TransactionReconciliation.shouldCommitActiveState(
                journal,
                DeviceState.MICROG_ACTIVE_BACKED_UP,
            ),
        )
        assertFalse(
            TransactionReconciliation.shouldCommitActiveState(
                journal,
                DeviceState.MICROG_ACTIVE,
            ),
        )
    }

    @Test
    fun `recuperação real para stock permite transicionar de ROLLBACK_REQUIRED para RESTORED`() {
        val dir = Files.createTempDirectory("degoogle-journal-restore").toFile()
        try {
            val store = TransactionJournalStore(dir.resolve("journal"), clock = { 9999L })
            store.update(
                operationId = "op-recover",
                fingerprint = "fp-galaxy",
                state = TransactionState.ROLLBACK_REQUIRED,
                detail = "boot reason indica Rescue Party: reboot,rescueparty",
            )
            assertTrue(store.requiresRecovery())

            // Simula confirmação de stock após rollback
            store.update(
                operationId = "op-recover",
                fingerprint = "fp-galaxy",
                state = TransactionState.RESTORED,
                detail = "stock state confirmed after rollback",
            )

            val restored = store.read()
            assertEquals(TransactionState.RESTORED, restored?.state)
            assertEquals("stock state confirmed after rollback", restored?.detail)
            assertFalse(store.requiresRecovery())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `rescue party com microG validado vira aviso e não erro`() {
        val journal = TransactionJournal(
            operationId = "op-rescue-ok",
            timestamp = 1234L,
            deviceFingerprint = "fp",
            state = TransactionState.ROLLBACK_REQUIRED,
            detail = "boot reason indica Rescue Party: reboot,rescueparty",
        )

        assertTrue(
            TransactionReconciliation.isRescuePartyOnlyFailure(
                journal = journal,
                state = DeviceState.MICROG_ACTIVE_BACKED_UP,
                gmsPrivileged = true,
                gsfAbsent = true,
                finalizeDone = true,
            ),
        )
        // Sem prova de que o microG voltou saudável, continua sendo erro.
        assertFalse(
            TransactionReconciliation.isRescuePartyOnlyFailure(
                journal = journal,
                state = DeviceState.MICROG_ACTIVE,
                gmsPrivileged = false,
                gsfAbsent = true,
                finalizeDone = true,
            ),
        )
        // Falha que não é do boot (ex.: reboot recusado) nunca vira aviso.
        assertFalse(
            TransactionReconciliation.isRescuePartyOnlyFailure(
                journal = journal.copy(detail = "soft reboot refused"),
                state = DeviceState.MICROG_ACTIVE,
                gmsPrivileged = true,
                gsfAbsent = true,
                finalizeDone = true,
            ),
        )
    }
}
