package dev.supportmama.internal

/** The plain rules behind push, kept apart from Android so they can be unit-tested. */
internal object Push {
    /** Re-registering an unchanged token once a week tells the server the install is still around. */
    const val REREGISTER_MS = 7 * 86_400_000L

    /** A push SupportMama sent carries `supportmama: "reply"` in its data (docs/protocol.md). */
    fun isOurs(data: Map<String, String>): Boolean = data["supportmama"] == "reply"

    /** Whether [token] should be sent to the server now, given what it last accepted. */
    fun registrationDue(token: String?, registered: Pair<String, Long>?, now: Long): Boolean {
        if (token.isNullOrBlank()) return false
        if (registered == null || registered.first != token) return true
        return now - registered.second >= REREGISTER_MS
    }
}
