package dev.degoogle.app.boot

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.degoogle.app.MainActivity
import dev.degoogle.app.R
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Reconciles state after a full reboot without changing installed packages.
 *
 * Restoring stock is deliberately restricted to the explicit action in the
 * UI. This receiver may confirm an already restored stock state or notify the
 * user that an operation is pending; it must never start rollback itself.
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
        val pending = runCatching { prefs.hasPendingOperation() }.getOrDefault(false)
        val backendPath = BackendInstaller(context).ensureInstalled()
        if (backendPath == null) {
            if (pending) notifyPending(context)
            return
        }

        val journal = TransactionJournalStore.forAppFiles(context.filesDir)
        val backend = BackendRunner(
            executor = SuRootExecutor(),
            backendPath = backendPath.absolutePath,
            transactionBase = File(context.filesDir, "transaction").absolutePath,
            operationId = { journal.read()?.operationId },
            onProgress = { line -> Log.i(TAG, line) },
        )
        val probe = backend.probe()
        if (!probe.raw.succeeded) {
            if (pending) notifyPending(context)
            return
        }

        val state = StateDetector.detect(probe.facts, profile = null)
        if (state == DeviceState.STOCK) {
            val stockConfirmed = confirmStock(journal, probe.facts)
            if (stockConfirmed) {
                runCatching { prefs.clearPendingOperation() }
            } else if (pending) {
                notifyPending(context)
            }
        } else if (pending) {
            notifyPending(context)
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
            )
        ) return false
        return journal.update(
            operationId = current.operationId,
            fingerprint = facts.fingerprint,
            state = TransactionState.RESTORED,
            detail = "stock state confirmed after rollback",
        )
    }

    private fun notifyPending(context: Context) {
        notify(
            context = context,
            title = context.getString(R.string.notif_pending_title),
            text = context.getString(R.string.notif_pending_text),
        )
    }

    private fun notify(context: Context, title: String, text: String) {
        val notificationsOn = runCatching {
            kotlinx.coroutines.runBlocking { Prefs(context).notificationsEnabled.first() }
        }.getOrDefault(true)
        if (!notificationsOn) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        createChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(
                android.app.PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java),
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                        android.app.PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

        runCatching { NotificationManagerCompat.from(context).notify(NOTIF_ID, notification) }
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "DeGoogle",
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "DeGoogle.BootRecovery"
        private const val CHANNEL_ID = "degoogle_pending"
        private const val NOTIF_ID = 1
    }
}
