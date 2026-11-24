package dev.supportmama.internal

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import dev.supportmama.R
import dev.supportmama.ReplyCheckJob
import dev.supportmama.SupportMama
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * The one conversation this install has, process-wide: what the screen shows,
 * what the badge reads, and what the background check updates.
 */
internal class Chat private constructor(private val app: Context) {

    companion object {
        @Volatile private var instance: Chat? = null

        /** How long a push waits for the reply before showing the push's own text; FCM allows about 10 s. */
        private const val PUSH_WAIT_MS = 7_000L

        fun get(context: Context): Chat =
            instance ?: synchronized(this) { instance ?: Chat(context.applicationContext).also { instance = it } }
    }

    private val store = Store(app)
    /** Nothing in here may take the host app down: a failure is logged and dropped. */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> Log.w("SupportMama", "background work failed", e) }
    )
    private val fetchLock = Mutex()
    private val pushLock = Mutex()
    private val main = Handler(Looper.getMainLooper())

    /** Null in a process where the host never called init (a background check can still run). */
    @Volatile var config: SupportMama.Config? = null
        private set

    private val _messages = MutableStateFlow(store.cached())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    /** The last refresh failed; the screen says so instead of looking empty. */
    val offline = MutableStateFlow(false)
    val unread = MutableStateFlow(hasUnread(_messages.value))
    val name = MutableStateFlow(store.name)
    /** A screenshot picked for the next message, staged in the cache. */
    val staged = MutableStateFlow<String?>(null)

    /** The chat is on screen: replies are read as they arrive, and no notification is posted. */
    @Volatile var visible = false

    fun configure(config: SupportMama.Config) {
        this.config = config
        store.endpoint = config.endpoint
        store.appKey = config.appKey
    }

    fun requireConfig(): SupportMama.Config =
        config ?: error("Call SupportMama.init(context, Config(endpoint = …)) before using the chat")

    /** From the config, or (in a background check's fresh process) as last configured. */
    private fun api(): Api? = (config?.endpoint ?: store.endpoint)?.let {
        Api(it, store.credentials(), config?.appKey ?: store.appKey)
    }

    fun setName(value: String) {
        val trimmed = value.take(40)
        name.value = trimmed
        store.name = trimmed
    }

    var askedNotifications by store::askedNotifications

    // ── Sending ──────────────────────────────────────────────────────────────

    fun attach(uri: Uri) {
        scope.launch {
            val file = Images.stage(app, uri)
            if (file == null) {
                toast(R.string.supportmama_image_unreadable)
                return@launch
            }
            staged.value?.let(Images::discard)
            staged.value = file.absolutePath
        }
    }

    fun removeAttachment() {
        staged.value?.let(Images::discard)
        staged.value = null
    }

    fun send(text: String) {
        val trimmed = text.trim()
        val picked = staged.value
        if (trimmed.isEmpty() && picked == null) return
        staged.value = null
        scope.launch {
            val image = picked?.let { runCatching { store.keepImage(File(it)).absolutePath }.getOrNull() }
            val pending = Message(0, fromTeam = false, text = trimmed, at = System.currentTimeMillis(), image = image)
            change { it + pending }
            deliver(pending)
        }
    }

    fun retry(localId: String) {
        val message = _messages.value.firstOrNull { it.localId == localId && it.failed } ?: return
        val pending = message.copy(failed = false)
        change { list -> list.map { if (it.localId == localId) pending else it } }
        scope.launch { deliver(pending) }
    }

    private suspend fun deliver(pending: Message) {
        val api = api() ?: return
        val line = contextLine()
        val image = pending.image?.let(::File)?.takeIf { it.exists() }
        val result = runCatching { api.send(pending.text, line, name.value, image) }
        result.onSuccess { sent ->
            change { list ->
                // A poll may have brought this message back already, without
                // the copy of its screenshot that only this side has.
                if (list.any { it.id == sent.id }) {
                    list.filter { it.localId != pending.localId }
                        .map { if (it.id == sent.id) it.copy(image = pending.image) else it }
                } else {
                    list.map { if (it.localId == pending.localId) sent.copy(localId = pending.localId, image = pending.image) else it }
                }
            }
            offline.value = false
            // The first send makes the conversation a push token attaches to.
            syncPush()
            scheduleReplyCheck()
        }.onFailure { e ->
            change { list -> list.map { if (it.localId == pending.localId) pending.copy(failed = true) else it } }
            if (e is Api.SlowDown) toast(R.string.supportmama_slow_down)
        }
    }

    // ── Receiving ────────────────────────────────────────────────────────────

    fun refreshInBackground() {
        scope.launch { refresh() }
    }

    /** Pulls what's new; returns the team replies this install didn't have before. [quick]: for a push (short timeouts). */
    suspend fun refresh(quick: Boolean = false): List<Message> = withContext(Dispatchers.IO) {
        // Nobody who never wrote has anything to fetch.
        if (_messages.value.isEmpty()) {
            offline.value = false
            return@withContext emptyList()
        }
        val api = api() ?: return@withContext emptyList()
        fetchLock.withLock {
            val page = runCatching { api.fetch(store.readCursor, quick) }.getOrElse {
                offline.value = true
                return@withLock emptyList()
            }
            offline.value = false
            // A token taken back but not yet forgotten by the server may still read
            // as `push: true`: that mustn't switch the background check off.
            page.push?.let { setPushActive(it && pushWanted() != null) }
            if (store.pushForgetPending) scope.launch { syncPush() }
            val incoming = page.messages
            if (incoming.isEmpty()) return@withLock emptyList()
            val known = _messages.value.filter { it.sent }.map { it.id }.toHashSet()
            change { Store.merge(it, incoming) }
            incoming.maxOfOrNull { it.id }?.let { if (it > store.readCursor) store.readCursor = it }
            if (visible) markSeen() else unread.value = hasUnread(_messages.value)
            incoming.filter { it.fromTeam && it.id !in known }
        }
    }

    /** The user has the conversation on screen: everything in it is read. */
    fun markSeen() {
        val newest = _messages.value.lastOrNull { it.fromTeam }?.id
        if (newest != null && newest > store.lastSeen) store.lastSeen = newest
        unread.value = false
        Notifier.cancel(app)
    }

    private fun hasUnread(messages: List<Message>) =
        (messages.lastOrNull { it.fromTeam }?.id ?: 0L) > store.lastSeen

    private fun change(transform: (List<Message>) -> List<Message>) {
        _messages.update(transform)
        store.save(_messages.value)
    }

    // ── While the app is closed ──────────────────────────────────────────────
    //
    // By default a periodic job asks the server. It runs only while a
    // conversation is live and unschedules itself [Config.keepCheckingDays]
    // after the last message. When the app hands over an FCM token and the
    // server says it pushes replies, the job is off; it comes back whenever
    // the server says push stopped working (docs/protocol.md, "PUT /v1/push").

    fun scheduleReplyCheck() {
        val config = config ?: return
        if (!config.notifications || store.pushActive) return
        val scheduler = app.getSystemService(JobScheduler::class.java) ?: return
        if (scheduler.getPendingJob(config.jobId) != null) return
        val period = config.backgroundCheckMinutes.coerceAtLeast(15) * 60_000L
        // Without it the user still sees replies whenever they open the app.
        runCatching {
            scheduler.schedule(
                JobInfo.Builder(config.jobId, ComponentName(app, ReplyCheckJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(period, period / 3)
                    .build()
            )
        }.onFailure { Log.w("SupportMama", "couldn't schedule the reply check", it) }
    }

    /** Runs on a background thread from [ReplyCheckJob]; true while there's reason to check again. */
    suspend fun checkInBackground(): Boolean {
        val config = config
        if (config != null && !config.notifications) return false
        if (store.pushActive) return false
        val newest = _messages.value.maxOfOrNull { it.at } ?: return false
        val days = config?.keepCheckingDays ?: 7
        if (System.currentTimeMillis() - newest > days * 86_400_000L) return false
        val replies = refresh()
        val reply = replies.lastOrNull()
        if (reply != null && !visible && unread.value) Notifier.notify(app, config, reply.text)
        return true
    }

    private fun cancelReplyCheck() {
        val jobId = config?.jobId ?: return
        runCatching { app.getSystemService(JobScheduler::class.java)?.cancel(jobId) }
    }

    // ── Push ─────────────────────────────────────────────────────────────────

    fun setPushToken(token: String) {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) return
        store.pushToken = trimmed
        scope.launch { syncPush() }
    }

    fun clearPushToken() {
        if (store.pushToken != null || store.pushRegistered != null) store.pushForgetPending = true
        store.pushToken = null
        store.pushRegistered = null
        setPushActive(false)
        scope.launch { syncPush() }
    }

    /** After init: a weekly re-register, or a DELETE that didn't get through. */
    fun registerPushInBackground() {
        scope.launch { syncPush() }
    }

    /** The token the server should have: none when the app took it back or turned notifications off. */
    private fun pushWanted(): String? = store.pushToken?.takeIf { config?.notifications != false }

    /**
     * Brings the server in line with this install, one call at a time: sends
     * the token when it's new, changed, or a week old (once there's a
     * conversation to attach it to), or asks the server to forget it when
     * the app took it back or turned notifications off. Whatever the server
     * answers decides whether the background check runs.
     */
    private suspend fun syncPush() = withContext(Dispatchers.IO) {
        pushLock.withLock {
            val api = api() ?: return@withLock
            val token = pushWanted()
            if (token == null) {
                if (config?.notifications == false && store.pushRegistered != null) store.pushForgetPending = true
                if (!store.pushForgetPending) return@withLock
                runCatching { api.forgetPush() }
                    .onSuccess {
                        store.pushForgetPending = false
                        store.pushRegistered = null
                        setPushActive(false)
                    }
                    .onFailure { Log.i("SupportMama", "push unregistration failed; trying again later", it) }
                return@withLock
            }
            if (_messages.value.none { it.sent }) return@withLock
            if (!Push.registrationDue(token, store.pushRegistered, System.currentTimeMillis())) return@withLock
            runCatching { api.registerPush(token) }
                .onSuccess { push ->
                    // Taken back or replaced while the PUT was out: the run queued
                    // behind this one sends the DELETE or the new token.
                    if (pushWanted() != token) return@onSuccess
                    // The PUT replaced whatever the server held, so nothing is left to forget.
                    store.pushForgetPending = false
                    store.pushRegistered = token to System.currentTimeMillis()
                    setPushActive(push)
                }
                .onFailure { if (it !is Api.NoConversation) Log.i("SupportMama", "push registration failed; trying again later", it) }
        }
    }

    private fun setPushActive(active: Boolean) {
        if (store.pushActive == active) return
        store.pushActive = active
        if (active) cancelReplyCheck() else scheduleReplyCheck()
    }

    /**
     * A SupportMama push arrived. The push only says "there's a reply": fetch
     * it from the server (so the notification shows the real text even when
     * the app maker keeps previews off), then notify unless the chat is on
     * screen. Offline, the push's own text has to do. FCM calls
     * onMessageReceived on a background thread and allows a few seconds, so
     * this blocks there until the notification is up.
     */
    fun onPush(data: Map<String, String>) {
        val work: suspend () -> Unit = {
            // The fetch runs as its own job so the wait really ends at the limit:
            // a timeout can't interrupt a blocking HTTP call, only stop waiting
            // for it. A fetch that finishes later still merges the reply.
            val fetch = scope.async { refresh(quick = true) }
            val fetched = withTimeoutOrNull(PUSH_WAIT_MS) { runCatching { fetch.await() }.getOrNull() }
            if (!visible && config?.notifications != false) {
                val text = if (fetched == null || offline.value) data["text"]
                    else _messages.value.lastOrNull { it.fromTeam }?.takeIf { unread.value }?.text
                if (text != null) Notifier.notify(app, config, text)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) scope.launch { work() }
        else runCatching { runBlocking { work() } }.onFailure { Log.w("SupportMama", "push handling failed", it) }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * The facts your team asks first, posted into the thread once per change:
     * `MyApp 2.3.1 (41) · Android 14 · Google Pixel 8 · en-IN · plan: pro`.
     */
    private fun contextLine(): String {
        val pm = app.packageManager
        val label = runCatching { app.applicationInfo.loadLabel(pm).toString() }.getOrDefault(app.packageName)
        val info = runCatching { pm.getPackageInfo(app.packageName, 0) }.getOrNull()
        val version = info?.versionName ?: "?"
        @Suppress("DEPRECATION")
        val code = info?.let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong() }
        val locale = app.resources.configuration.locales[0].toLanguageTag()
        val device = if (Build.MODEL.startsWith(Build.MANUFACTURER, ignoreCase = true)) Build.MODEL
            else Build.MANUFACTURER.replaceFirstChar { it.uppercase() } + " " + Build.MODEL
        val extra = runCatching { config?.attributes?.invoke().orEmpty() }.getOrDefault(emptyMap())
            .filter { (k, v) -> k.isNotBlank() && v.isNotBlank() }
            .map { (k, v) -> "$k: $v" }
        return (listOf("$label $version" + (code?.let { " ($it)" } ?: ""), "Android ${Build.VERSION.RELEASE}", device, locale) + extra)
            .joinToString(" · ")
    }

    private fun toast(res: Int) = main.post { Toast.makeText(app, res, Toast.LENGTH_SHORT).show() }
}
