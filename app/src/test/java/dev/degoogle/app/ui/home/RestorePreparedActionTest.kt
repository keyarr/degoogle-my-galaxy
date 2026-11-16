package dev.degoogle.app.ui.home

import dev.degoogle.app.domain.SystemFacts
import org.junit.Assert.assertEquals
import org.junit.Test

class RestorePreparedActionTest {

    @Test
    fun `post-reboot without restore-stock requires restore stock before soft reboot`() {
        val facts = SystemFacts.EMPTY.copy(
            stockReindexPending = false,
            stockReindexReady = false,
        )
        assertEquals(RestorePreparedAction.RESTORE_STOCK, resolveRestorePreparedAction(facts))
    }

    @Test
    fun `prepared rollback waiting for restart offers soft reboot`() {
        val facts = SystemFacts.EMPTY.copy(
            stockReindexPending = true,
            stockReindexReady = false,
        )
        assertEquals(RestorePreparedAction.SOFT_REBOOT, resolveRestorePreparedAction(facts))
    }

    @Test
    fun `verified soft reboot after restore-stock offers reindex stock`() {
        val facts = SystemFacts.EMPTY.copy(
            stockReindexPending = true,
            stockReindexReady = true,
        )
        assertEquals(RestorePreparedAction.REINDEX_STOCK, resolveRestorePreparedAction(facts))
    }

    @Test
    fun `stockReindexReady without stockReindexPending still requires restore stock`() {
        val facts = SystemFacts.EMPTY.copy(
            stockReindexPending = false,
            stockReindexReady = true,
        )
        assertEquals(RestorePreparedAction.RESTORE_STOCK, resolveRestorePreparedAction(facts))
    }
}
