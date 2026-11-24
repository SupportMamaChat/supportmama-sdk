package dev.supportmama.internal

import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

class PushTest {
    @Test fun onlySupportMamaRepliesAreOurs() {
        assertTrue(Push.isOurs(mapOf("supportmama" to "reply", "id" to "42", "text" to "hi")))
        assertFalse(Push.isOurs(mapOf("type" to "promo")))
        assertFalse(Push.isOurs(mapOf("supportmama" to "something-else")))
    }

    @Test fun aTokenIsSentWhenNewChangedOrAWeekOld() {
        val now = 1_000_000_000_000L
        assertFalse(Push.registrationDue(null, null, now))
        assertFalse(Push.registrationDue("  ", null, now))
        assertTrue(Push.registrationDue("t1", null, now))
        assertTrue(Push.registrationDue("t2", "t1" to now, now))
        assertFalse(Push.registrationDue("t1", "t1" to now - 3_600_000, now))
        assertTrue(Push.registrationDue("t1", "t1" to now - Push.REREGISTER_MS, now))
    }
}

/** The push calls against a local HTTP server, as the protocol describes them. */
class PushApiTest {
    private lateinit var server: HttpServer
    private val requests = mutableListOf<Triple<String, String, String>>()
    private var status = 200
    private var reply = """{"push":true}"""

    @Before fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val body = ex.requestBody.readBytes().decodeToString()
            requests += Triple(ex.requestMethod, ex.requestURI.toString(), body)
            requests += Triple("auth", ex.requestHeaders.getFirst("Authorization") ?: "", ex.requestHeaders.getFirst("X-App-Key") ?: "")
            val bytes = reply.toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @After fun stop() = server.stop(0)

    private fun api() = Api("http://127.0.0.1:${server.address.port}", "id.secret", "smk_test")

    @Test fun registerSendsTheFcmTokenAndReadsTheAnswer() {
        assertTrue(api().registerPush("fcm-token-123"))
        val (method, path, body) = requests[0]
        assertEquals("PUT", method)
        assertEquals("/v1/push", path)
        assertEquals(JSONObject("""{"kind":"fcm","token":"fcm-token-123"}""").toString(), JSONObject(body).toString())
        assertEquals(Triple("auth", "Bearer id.secret", "smk_test"), requests[1])

        reply = """{"push":false,"reason":"not_configured"}"""
        assertFalse(api().registerPush("fcm-token-123"))
    }

    @Test(expected = Api.NoConversation::class)
    fun registeringBeforeTheFirstMessageSaysSo() {
        status = 404
        reply = """{"error":"no_conversation"}"""
        api().registerPush("fcm-token-123")
    }

    @Test fun forgetDeletes() {
        reply = """{"push":false}"""
        api().forgetPush()
        assertEquals("DELETE" to "/v1/push", requests[0].first to requests[0].second)
    }

    @Test fun aFetchCarriesThePushFlagWhenTheServerHasIt() {
        reply = """{"messages":[{"id":3,"from":"team","text":"hi","at":1}],"tag":"ab12","push":true}"""
        val page = api().fetch(2)
        assertEquals(listOf(3L), page.messages.map { it.id })
        assertEquals(true, page.push)
        assertEquals("/v1/messages?after=2", requests[0].second)

        reply = """{"messages":[],"tag":"ab12"}"""
        assertNull(api().fetch(3).push)
    }
}
