package dev.supportmama.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreTest {
    private fun sent(id: Long, team: Boolean = false, text: String = "m$id") = Message(id, team, text, at = id * 1000)
    private fun pending(text: String) = Message(0, false, text, at = 0)

    @Test fun mergeAddsNewMessagesInOrderAndKeepsUnsentLast() {
        val unsent = pending("still sending")
        val merged = Store.merge(listOf(sent(1), unsent), listOf(sent(3, team = true), sent(2)))
        assertEquals(listOf(1L, 2L, 3L, 0L), merged.map { it.id })
        assertEquals("still sending", merged.last().text)
    }

    @Test fun mergeDropsRepeatsAndKeepsTheLocalCopy() {
        // The local copy has the screenshot path only this phone knows.
        val local = sent(5).copy(image = "/data/shot.jpg")
        val merged = Store.merge(listOf(local), listOf(sent(5), sent(6, team = true)))
        assertEquals(listOf(5L, 6L), merged.map { it.id })
        assertEquals("/data/shot.jpg", merged.first().image)
    }

    @Test fun cacheRoundTripsAndTurnsInFlightMessagesIntoFailedOnes() {
        val messages = listOf(sent(1).copy(image = "/x.jpg"), sent(2, team = true), pending("half sent"))
        val back = Store.decode(Store.encode(messages))
        assertEquals(messages.map { it.copy(failed = !it.sent) }, back)
        assertTrue(back.last().failed)
    }

    @Test fun serverMessagesParse() {
        val m = Api.parse(org.json.JSONObject("""{"id":7,"from":"team","text":"hi","at":1700000000000,"image":true}"""))
        assertEquals(Message(7, true, "hi", 1_700_000_000_000, localId = m.localId, hasImage = true), m)
    }
}
