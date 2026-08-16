package dev.degoogle.app.boot

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.degoogle.app.MainActivity
import dev.degoogle.app.R
import dev.degoogle.app.data.Prefs
import dev.degoogle.app.domain.DeviceState
import dev.degoogle.app.domain.SystemFacts
import dev.degoogle.app.domain.TransactionJournalStore
import dev.degoogle.app.domain.TransactionState
import dev.degoogle.app.recovery.AutoRecoveryCoordinator
import dev.degoogle.app.recovery.AutoRecoveryStatus
import dev.degoogle.app.root.BackendInstaller
import dev.degoogle.app.root.BackendRunner
import dev.degoogle.app.root.SuRootExecutor
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Recupera automaticamente um rollback interrompido por reboot completo.
 *
 * O receiver não assume que a operação de microG sobreviveu: primeiro roda o
 * mesmo probe usado pela Activity. Só estados RESTORE_PREPARED ou máscaras
 * legadas conhecidas podem disparar restore-stock; mounts externos continuam
 * bloqueados pelo backend. Se o estado for MICROG_BOOTED, a notificação antiga
 * de conclusão permanece o caminho correto.
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
        val coordinator = AutoRecoveryCoordinator(backend)

        // KernelSU pode voltar alguns segundos depois do BOOT_COMPLETED em
        // aparelhos com root volátil. Repetimos somente quando o probe ainda
        // não vê root; uma falha operacional não é repetida silenciosamente.
        var result = coordinator.runIfNeeded(wipeData = true)
        var attempt = 1
        while (attempt < ROOT_RETRY_COUNT && !result.succeeded && !result.facts.rootOk) {
            delay(ROOT_RETRY_DELAY_MS)
            result = coordinator.runIfNeeded(wipeData = true)
            attempt++
        }

        when (result.status) {
            AutoRecoveryStatus.REBOOT_REQUESTED -> {
                Log.i(TAG, "Automatic recovery requested a userspace reboot: ${result.message}")
            }
            AutoRecoveryStatus.CLEANED -> {
                Log.i(TAG, "Temporary residues cleaned automatically")
                confirmStock(journal, result.facts)
                runCatching { prefs.clearPendingOperation() }
            }
            AutoRecoveryStatus.NOT_NEEDED -> {
                if (result.assessment.state == DeviceState.STOCK) {
                    confirmStock(journal, result.facts)
                    runCatching { prefs.clearPendingOperation() }
                } else if (pending) {
                    notifyPending(context)
                }
            }
            AutoRecoveryStatus.FAILED -> {
                Log.e(TAG, "Automatic recovery failed: ${result.message}")
                if (pending || result.facts.rootOk) notifyRecoveryFailed(context)
            }
        }
    }

    private fun confirmStock(journal: TransactionJournalStore, facts: SystemFacts) {
        val current = journal.read() ?: return
        if (current.state in setOf(
                TransactionState.RESTORED,
                TransactionState.COMMITTED,
                TransactionState.IDLE,
            )
        ) return
        journal.update(
            operationId = current.operationId,
            fingerprint = facts.fingerprint,
            state = TransactionState.RESTORED,
            detail = "estado stock confirmado automaticamente após boot",
        )
    }

    private fun notifyPending(context: Context) {
        notify(
            context = context,
            title = context.getString(R.string.notif_pending_title),
            text = context.getString(R.string.notif_pending_text),
        )
    }

    private fun notifyRecoveryFailed(context: Context) {
        notify(
            context = context,
            title = context.getString(R.string.notif_recovery_failed_title),
            text = context.getString(R.string.notif_recovery_failed_text),
        )
    }

    private fun notify(context: Context, title: String, text: String) {
        val notificationsOn = runCatching {
            kotlinx.coroutines.runBlocking { Prefs(context).notificationsEnabled.first() }
        }.getOrDefault(true)
        if (!notificationsOn) return

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
        private const val ROOT_RETRY_COUNT = 3
        private const val ROOT_RETRY_DELAY_MS = 5_000L
    }
}
