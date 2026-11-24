package dev.supportmama

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.annotation.DrawableRes
import dev.supportmama.internal.Chat
import dev.supportmama.internal.Push
import kotlinx.coroutines.flow.StateFlow

/**
 * An in-app "Chat with us" that your team answers from Telegram or Slack.
 *
 * Every install that writes in gets its own thread on the team side (a topic
 * in a Telegram group, or a thread in a Slack channel); you answer by replying
 * there, and the reply shows up in the app. No accounts: the app makes an id
 * and a secret the first time the chat is used. Platform tokens stay on the
 * SupportMama server, never in the app.
 *
 * ```kotlin
 * class MyApp : Application() {
 *     override fun onCreate() {
 *         super.onCreate()
 *         SupportMama.init(this, SupportMama.Config(
 *             endpoint = "https://supportmama.you.workers.dev",
 *             appKey = "smk_…", // hosted SupportMama only
 *         ))
 *     }
 * }
 *
 * // From a "Help" button:
 * SupportMama.open(context)
 * ```
 *
 * Compose apps can embed the screen instead of opening the activity:
 * [SupportChat].
 */
object SupportMama {

    data class Config(
        /** Your SupportMama server, e.g. `https://supportmama.you.workers.dev`. */
        val endpoint: String,
        /** Hosted SupportMama: the app key from the dashboard. Self-hosted: leave null. */
        val appKey: String? = null,
        /** Top bar title. Default: "Chat with us". */
        val title: String? = null,
        /** Large first line of the empty screen. Default: "Talk to the team". */
        val headline: String? = null,
        /** The text under the headline. Default: a line about real people replying. */
        val intro: String? = null,
        /** Label over your replies. Default: "Team". */
        val teamLabel: String? = null,
        /** Offer a "What should we call you?" field; the name labels the thread for your team. */
        val askForName: Boolean = true,
        /** Let users attach one screenshot per message. */
        val screenshots: Boolean = true,
        /**
         * Post a notification when a reply arrives while the chat isn't on
         * screen. By default the app checks in the background, only while a
         * conversation is live: [keepCheckingDays] after the last message the
         * checks stop on their own. With [setPushToken], replies come by push
         * instead. False turns both off.
         */
        val notifications: Boolean = true,
        /** Small notification icon: a monochrome drawable. */
        @DrawableRes val notificationIcon: Int = R.drawable.supportmama_ic_notification,
        /** How often the background check runs. Android won't go under 15. */
        val backgroundCheckMinutes: Int = 30,
        val keepCheckingDays: Int = 7,
        /** JobScheduler id for the background check; change it if your app already uses this one. */
        val jobId: Int = 0x53_4D_01,
        /**
         * Extra facts for your team, posted into the thread with the app version,
         * Android version, device and language whenever any of them change. For
         * example `{ mapOf("plan" to billing.plan, "user" to account.id) }`.
         * Called on a background thread each time a message is sent.
         */
        val attributes: () -> Map<String, String> = { emptyMap() },
        /** Shows "Or email us" on the empty screen when set. */
        val email: String? = null,
    )

    /**
     * Call once, ideally from [Application.onCreate] so background reply checks
     * find the configuration too.
     */
    @JvmStatic
    fun init(context: Context, config: Config) {
        require(config.endpoint.startsWith("https://") || config.endpoint.startsWith("http://")) {
            "SupportMama endpoint must be an http(s) URL"
        }
        val chat = Chat.get(context)
        chat.configure(config)
        chat.registerPushInBackground()
        (context.applicationContext as? Application)?.let(::refreshOnResume)
    }

    /**
     * Push instead of background checks, for apps that use Firebase Cloud
     * Messaging and whose SupportMama server has FCM keys (dashboard → Push
     * notifications; self-hosted: `FCM_SERVICE_ACCOUNT`). Hand over the token
     * from `FirebaseMessagingService.onNewToken`,
     * and once at launch:
     *
     * ```kotlin
     * FirebaseMessaging.getInstance().token.addOnSuccessListener { SupportMama.setPushToken(this, it) }
     * ```
     *
     * It reaches the server once the user has written in (a conversation to
     * attach it to). Until the server confirms it will push, and whenever it
     * says pushes stopped working, the background check carries on as before.
     */
    @JvmStatic
    fun setPushToken(context: Context, token: String) = Chat.get(context).setPushToken(token)

    /**
     * Call first thing in `onMessageReceived`; true if the push was
     * SupportMama's, which it has then handled: it fetches the reply and posts
     * the notification (unless the chat is on screen) before returning.
     *
     * ```kotlin
     * override fun onMessageReceived(message: RemoteMessage) {
     *     if (SupportMama.handlePush(this, message.data)) return
     *     // your own pushes
     * }
     * ```
     */
    @JvmStatic
    fun handlePush(context: Context, data: Map<String, String>): Boolean {
        if (!Push.isOurs(data)) return false
        Chat.get(context).onPush(data)
        return true
    }

    /** Stops pushes to this install, say when the user signs out; background checks take over. */
    @JvmStatic
    fun clearPushToken(context: Context) = Chat.get(context).clearPushToken()

    /** Opens the chat screen, optionally with [draft] already typed in. */
    @JvmStatic
    @JvmOverloads
    fun open(context: Context, draft: String? = null) {
        Chat.get(context).requireConfig()
        val intent = Intent(context, SupportChatActivity::class.java)
        if (draft != null) intent.putExtra(SupportChatActivity.EXTRA_DRAFT, draft)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /**
     * What the user is called on your team's thread, if you already know
     * (say, from their account). They can still change it on the chat screen.
     */
    @JvmStatic
    fun setUserName(context: Context, name: String) = Chat.get(context).setName(name)

    /**
     * True while a reply from your team hasn't been seen: for a badge on your
     * "Help" button. Kept current by [refresh], the chat screen and the
     * background check.
     */
    @JvmStatic
    fun hasUnread(context: Context): StateFlow<Boolean> = Chat.get(context).unread

    /**
     * Fetches new replies now. Not usually needed: after [init] this happens on
     * its own when the app comes back to the foreground.
     */
    @JvmStatic
    fun refresh(context: Context) = Chat.get(context).refreshInBackground()

    /** Lets a user who just wrote in see replies without opening the chat. */
    private fun refreshOnResume(app: Application) {
        if (registered) return
        registered = true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            private var last = 0L
            override fun onActivityResumed(activity: Activity) {
                val now = System.currentTimeMillis()
                if (activity is SupportChatActivity || now - last < 60_000) return
                last = now
                Chat.get(app).refreshInBackground()
            }
            override fun onActivityCreated(activity: Activity, state: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    @Volatile private var registered = false
}
