package dev.degoogle.app.boot

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import dev.degoogle.app.data.Prefs

/**
 * Receiver de BOOT_COMPLETED.
 *
 * Na primeira versão ele NÃO executa nenhuma operação root em silêncio:
 * apenas detecta que existe uma operação pendente (registrada antes do soft
 * reboot) e gera uma notificação pedindo para abrir o app.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            return
        }
        val prefs = Prefs(context)
        val pending = runBlocking { prefs.hasPendingOperation() }
        if (!pending) return

        // Notificações desativadas: operação pendente é concluída apenas
        // abrindo o app — nada é notificado.
        val notificationsOn = runBlocking { prefs.notificationsEnabled.first() }
        if (!notificationsOn) return

        createChannel(context)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(context.getString(dev.degoogle.app.R.string.notif_pending_title))
            .setContentText(context.getString(dev.degoogle.app.R.string.notif_pending_text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(
                android.app.PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, dev.degoogle.app.MainActivity::class.java),
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
        private const val CHANNEL_ID = "degoogle_pending"
        private const val NOTIF_ID = 1
    }
}
