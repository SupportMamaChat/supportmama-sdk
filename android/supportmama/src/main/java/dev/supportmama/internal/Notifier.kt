package dev.supportmama.internal

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import dev.supportmama.R
import dev.supportmama.SupportChatActivity
import dev.supportmama.SupportMama

/** The "the team replied" notification: one at a time, opens the chat. */
internal object Notifier {
    private const val CHANNEL_ID = "supportmama_replies"
    private const val NOTIFICATION_ID = 0x53_4D_02

    fun notify(context: Context, config: SupportMama.Config?, text: String) {
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT >= 33 &&
            app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = app.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, app.getString(R.string.supportmama_channel_name), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = app.getString(R.string.supportmama_channel_description)
                }
            )
        }
        val open = PendingIntent.getActivity(
            app, NOTIFICATION_ID,
            Intent(app, SupportChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = app.getString(R.string.supportmama_notification_title, config?.teamLabel ?: app.getString(R.string.supportmama_team))
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(app, CHANNEL_ID) else Notification.Builder(app)
        val notification = builder
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(config?.notificationIcon ?: R.drawable.supportmama_ic_notification)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }
}
