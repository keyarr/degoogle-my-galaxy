package dev.degoogle.app.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.degoogle.app.data.Prefs
import dev.degoogle.app.domain.DeviceState
import dev.degoogle.app.domain.StateDetector
import dev.degoogle.app.domain.SystemFacts
import dev.degoogle.app.domain.TransactionJournalStore
import dev.degoogle.app.domain.TransactionState
import dev.degoogle.app.root.BackendInstaller
import dev.degoogle.app.root.BackendRunner
import dev.degoogle.app.root.SuRootExecutor
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Reconciles state after a full reboot without changing installed packages.
 *
 * Restoring stock is deliberately restricted to the explicit action in the
 * UI. This receiver may confirm an already restored stock state; it must
 * never start rollback itself.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // LOCKED_BOOT_COMPLETED pode ocorrer antes de o DataStore estar
        // disponível. O BOOT_COMPLETED cobre a recuperação após o unlock e
        // evita executar o rollback duas vezes.
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                recover(context)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun recover(context: Context) {
        val prefs = Prefs(context)
        val backendPath = BackendInstaller(context).ensureInstalled() ?: return

        val journal = TransactionJournalStore.forAppFiles(context.filesDir)
        val backend = BackendRunner(
            executor = SuRootExecutor(),
            backendPath = backendPath.absolutePath,
            transactionBase = File(context.filesDir, "transaction").absolutePath,
            operationId = { journal.read()?.operationId },
            onProgress = { line -> Log.i(TAG, line) },
        )
        val probe = backend.probe()
        if (!probe.raw.succeeded) return

        val state = StateDetector.detect(probe.facts, profile = null)
        if (state == DeviceState.STOCK && confirmStock(journal, probe.facts)) {
            runCatching { prefs.clearPendingOperation() }
        }
    }

    private fun confirmStock(journal: TransactionJournalStore, facts: SystemFacts): Boolean {
        val current = journal.read() ?: return false
        if (current.state == TransactionState.RESTORED) return true
        if (current.state !in setOf(
                TransactionState.ROLLBACK_RUNNING,
                TransactionState.ROLLBACK_REQUIRED,
                TransactionState.REBOOT_REQUESTED,
                TransactionState.PACKAGE_CACHE_INVALIDATED,
                TransactionState.GMS_UNMOUNTED,
                TransactionState.GSF_UNMOUNTED,
                TransactionState.STORE_UNMOUNTED,
                TransactionState.REINDEX_PENDING,
                TransactionState.FAILED,
            )
        ) return false
        return journal.update(
            operationId = current.operationId,
            fingerprint = facts.fingerprint,
            state = TransactionState.RESTORED,
            detail = "stock state confirmed after rollback",
        )
    }

    companion object {
        private const val TAG = "DeGoogle.BootRecovery"
    }
}
