package dev.supportmama.internal

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.util.UUID

internal data class Message(
    /** The server's id; 0 until it has been sent. */
    val id: Long,
    val fromTeam: Boolean,
    val text: String,
    val at: Long,
    val failed: Boolean = false,
    /** Tells unsent messages apart; the server id does that once there is one. */
    val localId: String = UUID.randomUUID().toString(),
    /** The attached screenshot as kept on this phone, if there is one. */
    val image: String? = null,
    /** The server says one was attached, even where [image] is gone. */
    val hasImage: Boolean = image != null,
) {
    val sent get() = id > 0
}

/**
 * Everything kept on the phone: the identity, the conversation (so the screen
 * opens on it even offline), and the read markers. All under names starting
 * with `supportmama` so nothing collides with the host app's files.
 */
internal class Store(private val context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("supportmama", Context.MODE_PRIVATE)
    private val dir get() = File(context.filesDir, "supportmama").apply { mkdirs() }
    private val cacheFile get() = File(dir, "messages.json")
    val imageDir get() = File(dir, "images").apply { mkdirs() }

    /**
     * `<id>.<secret>`, made on first use and kept for the life of the install.
     * The server stores only a hash of the secret, so the id alone can't read
     * a conversation. Clearing the app's data starts a new one.
     */
    fun credentials(): String = synchronized(this) {
        prefs.getString("auth", null) ?: run {
            val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val auth = UUID.randomUUID().toString() + "." +
                Base64.encodeToString(secret, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            prefs.edit().putString("auth", auth).commit()
            auth
        }
    }

    /** Remembered so a background check in a fresh process knows where to ask. */
    var endpoint: String?
        get() = prefs.getString("endpoint", null)
        set(value) = prefs.edit().putString("endpoint", value).apply()

    var appKey: String?
        get() = prefs.getString("appKey", null)
        set(value) = prefs.edit().putString("appKey", value).apply()

    var name: String
        get() = prefs.getString("name", "").orEmpty()
        set(value) = prefs.edit().putString("name", value.trim()).apply()

    /** The newest id a fetch brought back; only reads move it, never sends. */
    var readCursor: Long
        get() = prefs.getLong("read_after", 0L)
        set(value) = prefs.edit().putLong("read_after", value).apply()

    /** Newest team reply the user has had on screen. */
    var lastSeen: Long
        get() = prefs.getLong("seen", 0L)
        set(value) = prefs.edit().putLong("seen", value).apply()

    var askedNotifications: Boolean
        get() = prefs.getBoolean("asked_notifications", false)
        set(value) = prefs.edit().putBoolean("asked_notifications", value).apply()

    // ── Push (docs/protocol.md, "PUT /v1/push") ──────────────────────────────────────────────────

    /** The FCM token the host app handed over, if any. */
    var pushToken: String?
        get() = prefs.getString("push_token", null)
        set(value) = prefs.edit().putString("push_token", value).apply()

    /** The token the server last accepted, and when: re-registered when it changes, or weekly. */
    var pushRegistered: Pair<String, Long>?
        get() = prefs.getString("push_registered", null)?.let { t -> t to prefs.getLong("push_registered_at", 0L) }
        set(value) = prefs.edit().putString("push_registered", value?.first).putLong("push_registered_at", value?.second ?: 0L).apply()

    /**
     * The token was taken back (sign-out, or notifications turned off) and the
     * server hasn't confirmed forgetting it yet: DELETE /v1/push is retried
     * until it has.
     */
    var pushForgetPending: Boolean
        get() = prefs.getBoolean("push_forget_pending", false)
        set(value) = prefs.edit().putBoolean("push_forget_pending", value).apply()

    /** The server said it pushes replies to this install, so the background check is off. */
    var pushActive: Boolean
        get() = prefs.getBoolean("push_active", false)
        set(value) = prefs.edit().putBoolean("push_active", value).apply()

    fun cached(): List<Message> = runCatching { decode(cacheFile.readText()) }.getOrDefault(emptyList())

    @Synchronized
    fun save(messages: List<Message>) {
        runCatching {
            val tmp = File(cacheFile.path + ".tmp")
            tmp.writeText(encode(messages))
            tmp.renameTo(cacheFile)
        }
    }

    /** Moves a staged screenshot to where sent ones are kept. */
    fun keepImage(staged: File): File {
        val kept = File(imageDir, staged.name)
        if (!staged.renameTo(kept)) {
            staged.copyTo(kept, overwrite = true)
            staged.delete()
        }
        return kept
    }

    companion object {
        fun encode(messages: List<Message>): String {
            val arr = JSONArray()
            // A message still on its way is saved as failed: if the process dies
            // mid-send, it comes back with a retry rather than a spinner forever.
            messages.forEach {
                arr.put(
                    JSONObject().put("id", it.id).put("team", it.fromTeam).put("text", it.text)
                        .put("at", it.at).put("failed", it.failed || !it.sent).put("local", it.localId)
                        .put("image", it.image ?: "").put("hasImage", it.hasImage)
                )
            }
            return arr.toString()
        }

        fun decode(json: String): List<Message> {
            val arr = JSONArray(json)
            return List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                val image = o.optString("image").ifEmpty { null }
                Message(
                    o.getLong("id"), o.getBoolean("team"), o.getString("text"), o.getLong("at"),
                    o.optBoolean("failed"), o.getString("local"), image, o.optBoolean("hasImage", image != null),
                )
            }
        }

        /** Server messages and any unsent ones, in order, with [incoming] merged in. */
        fun merge(current: List<Message>, incoming: List<Message>): List<Message> {
            val known = current.filter { it.sent }.map { it.id }.toHashSet()
            val sent = (current.filter { it.sent } + incoming.filter { it.id !in known }).sortedBy { it.id }
            return sent + current.filter { !it.sent }
        }
    }
}
