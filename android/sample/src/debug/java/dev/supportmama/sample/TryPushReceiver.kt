package dev.supportmama.sample

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.supportmama.SupportMama
import kotlin.concurrent.thread

/**
 * Debug builds only: walks the push path without a Firebase project, against
 * a local server whose FCM is a fake.
 *
 *   adb shell am broadcast -a dev.supportmama.sample.TRY_PUSH -p dev.supportmama.sample --es token fake-token-…
 *       hands SupportMama a made-up FCM token, as onNewToken would
 *   adb shell am broadcast -a dev.supportmama.sample.TRY_PUSH -p dev.supportmama.sample --es supportmama reply --es text "hi"
 *       delivers a push's data, as onMessageReceived would
 */
class TryPushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val extras = intent.extras ?: return
        extras.getString("token")?.let {
            SupportMama.setPushToken(context, it)
            return
        }
        val data = extras.keySet().mapNotNull { k -> extras.getString(k)?.let { k to it } }.toMap()
        val pending = goAsync()
        // handlePush blocks until the notification is up, as it may in onMessageReceived.
        thread {
            try {
                SupportMama.handlePush(context.applicationContext, data)
            } finally {
                pending.finish()
            }
        }
    }
}
