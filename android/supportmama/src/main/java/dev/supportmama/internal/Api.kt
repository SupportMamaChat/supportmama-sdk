package dev.supportmama.internal

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * The calls the server has (docs/protocol.md), blocking: call off the
 * main thread. Hand-rolled on HttpURLConnection so the library adds no HTTP
 * client, and no keep rules, to the host app.
 */
internal class Api(private val endpoint: String, private val auth: String, private val appKey: String? = null) {

    class SlowDown : IOException("slow_down")
    /** PUT /v1/push before the first message: there's no conversation to attach the token to yet. */
    class NoConversation : IOException("no_conversation")

    /** One GET: new messages, and whether the server pushes replies (null from servers before push). */
    data class Page(val messages: List<Message>, val push: Boolean?)

    /** [quick]: short timeouts, for a push's fetch, which has only seconds before Android may freeze the process. */
    fun fetch(after: Long, quick: Boolean = false): Page {
        val res = request("GET", "/v1/messages?after=$after", null, if (quick) QUICK else NORMAL, null)
        val arr = res.getJSONArray("messages")
        return Page(List(arr.length()) { parse(arr.getJSONObject(it)) }, if (res.has("push")) res.optBoolean("push") else null)
    }

    /** Registers this install's FCM token; true when the server will push replies (docs/protocol.md, "PUT /v1/push"). */
    fun registerPush(token: String): Boolean {
        val bytes = JSONObject().put("kind", "fcm").put("token", token).toString().toByteArray()
        return request("PUT", "/v1/push", "application/json; charset=utf-8") { it.write(bytes) }.optBoolean("push")
    }

    fun forgetPush() {
        request("DELETE", "/v1/push", null, write = null)
    }

    fun send(text: String, contextLine: String, name: String?, image: File?): Message {
        val body = JSONObject().put("text", text).put("context", contextLine)
        if (!name.isNullOrBlank()) body.put("name", name)
        val res = if (image != null) upload(body, image) else {
            val bytes = body.toString().toByteArray()
            request("POST", "/v1/messages", "application/json; charset=utf-8") { it.write(bytes) }
        }
        return parse(res.getJSONObject("message"))
    }

    /** As multipart/form-data: [fields] as text parts, then [image] as the `image` part. */
    private fun upload(fields: JSONObject, image: File): JSONObject {
        val boundary = "supportmama-" + UUID.randomUUID()
        return request("POST", "/v1/messages", "multipart/form-data; boundary=$boundary") { out ->
            fun line(s: String = "") = out.write((s + "\r\n").toByteArray())
            for (key in fields.keys()) {
                line("--$boundary")
                line("Content-Disposition: form-data; name=\"$key\"")
                line()
                line(fields.getString(key))
            }
            line("--$boundary")
            line("Content-Disposition: form-data; name=\"image\"; filename=\"screenshot.jpg\"")
            line("Content-Type: image/jpeg")
            line()
            image.inputStream().use { it.copyTo(out) }
            line()
            line("--$boundary--")
        }
    }

    private class Timeouts(val connect: Int, val read: Int)

    private fun request(
        method: String, path: String, contentType: String?, timeouts: Timeouts = NORMAL, write: ((OutputStream) -> Unit)?,
    ): JSONObject {
        val conn = URL(endpoint.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = timeouts.connect
            conn.readTimeout = timeouts.read
            conn.setRequestProperty("Authorization", "Bearer $auth")
            if (appKey != null) conn.setRequestProperty("X-App-Key", appKey)
            if (write != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", contentType)
                conn.outputStream.buffered().use(write)
            }
            val code = conn.responseCode
            if (code == 429) throw SlowDown()
            if (code == 404 && path == "/v1/push") throw NoConversation()
            if (code !in 200..299) throw IOException("supportmama $method $path: HTTP $code")
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private val NORMAL = Timeouts(10_000, 20_000)
        private val QUICK = Timeouts(3_000, 4_000)

        fun parse(o: JSONObject) = Message(
            o.getLong("id"), o.getString("from") == "team", o.getString("text"), o.getLong("at"),
            hasImage = o.optBoolean("image"),
        )
    }
}
