package dev.strait.sdk

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * B28 (shared-spec ADR 0012): at most three "came back" notes per install (day 1, 7, 30), carrying the
 * install's tap id. Port of sdk-react-native/test/return-note.test.ts.
 */
class ReturnNoteTest {
    private val click = "3f2a9c1e-7b4d-4e8a-9c0f-1a2b3c4d5e6f"
    private val t0 = 1_800_000_000_000L
    private var now = t0

    private class Engine(val reply: (String) -> HttpResponse) : HttpTransport {
        val sent = mutableListOf<Pair<String, JSONObject>>()
        override fun request(url: String, method: String, jsonBody: String?): HttpResponse {
            val path = URI(url).path
            sent += path to JSONObject(jsonBody ?: "{}")
            return reply(path)
        }
        fun notes() = sent.filter { it.second.optString("kind") == "return" }
    }

    private val matchReply: (String) -> HttpResponse = { path ->
        if (path == "/v1/match") HttpResponse(200, """{"matched":true,"clickId":"${click.uppercase()}","longUrl":"https://shop.example/p/1"}""")
        else HttpResponse(202, """{"ok":true}""")
    }

    private fun client(e: Engine, s: KeyValueStore) = StraitClient(StraitConfig(
        publishableKey = "st_pub_test_x", endpoint = "https://acme.strait.link", storage = s,
        device = { DeviceFields(411, 2.625, "en", "Asia/Kolkata") }, transport = e, clock = { now },
        deviceClass = "phone", appVersion = { "2.1.0" },
    ))

    private fun installTap(s: MemoryStore) = JSONObject(s.data["strait.installTap"]!!)

    @Test
    fun pureReturnNoteIsDueOnDay1_7_30OnceEach() {
        val s = installTapValue(click, t0)
        assertNull(returnNote(s, (t0 + DAY_MS - 1).toDouble()))
        assertEquals(ReturnNote(1, click, "r1-$click"), returnNote(s, (t0 + DAY_MS).toDouble()))
        assertNull(returnNote(s, (t0 + 2 * DAY_MS).toDouble()))
        assertEquals(7, returnNote(s, (t0 + 7 * DAY_MS + 5).toDouble())?.day)
        assertEquals(30, returnNote(s, (t0 + 30 * DAY_MS).toDouble())?.day)
        assertNull(returnNote(s, (t0 + 31 * DAY_MS).toDouble()))
        assertNull(returnNote(installTapValue(click, t0, listOf(1)), (t0 + DAY_MS).toDouble()))
    }

    @Test
    fun installMatchKeepsTheTapDay1SendsOneNoteThenNothing() {
        val e = Engine(matchReply)
        val s = MemoryStore()
        client(e, s).start(null)
        val tap = installTap(s)
        assertEquals(click, tap.getString("clickId"))
        assertEquals(t0, tap.getLong("at"))
        assertEquals(0, tap.getJSONArray("sent").length())
        assertEquals(emptyList(), e.notes()) // day 0: nothing

        now = t0 + DAY_MS + 60_000
        val again = client(e, s)
        again.start(null)
        again.onAppState(AppLifecycleState.ACTIVE)
        val (path, body) = e.notes().single()
        assertEquals("/v1/open", path)
        assertEquals(
            setOf("publishableKey", "openId", "kind", "day", "clickId", "platform", "at", "sdkVersion", "appVersion"),
            body.keySet(),
        ) // no url, route, appState, deviceClass
        assertEquals("r1-$click", body.getString("openId"))
        assertEquals(1, body.getInt("day"))
        assertEquals(click, body.getString("clickId"))
        assertEquals("android", body.getString("platform"))
        assertEquals(now, body.getLong("at"))
        assertEquals("2.1.0", body.getString("appVersion"))
        assertEquals("1", installTap(s).getJSONArray("sent").join(","))
    }

    @Test
    fun laterTapNeverReplacesInstallTapAndRestoredBackupIsCleared() {
        val other = "11111111-2222-4333-8444-555555555555"
        val e = Engine(matchReply)
        val s = MemoryStore()
        s.data["strait.installTap"] = installTapValue(other, t0 - 40 * DAY_MS) // a backup of another install
        val c = client(e, s)
        c.start(null)
        c.handleUrl("acme://p/2?strait_click=$other")
        assertEquals(click, installTap(s).getString("clickId"))
        assertEquals(other, JSONObject(s.data["strait.lastTap"]!!).getString("clickId"))
    }

    @Test
    fun firstLaunchOpenedByLinkWithTapIdKeepsThatTap() {
        val e = Engine { HttpResponse(202, """{"ok":true}""") }
        val s = MemoryStore()
        client(e, s).start("acme://p/2?strait_click=$click")
        assertEquals(click, installTap(s).getString("clickId"))
        assertEquals(t0, installTap(s).getLong("at"))
    }

    @Test
    fun failedMarkWriteSendsNothing() {
        val e = Engine { HttpResponse(202, """{"ok":true}""") }
        val s = object : KeyValueStore {
            val data = mutableMapOf("strait.deferredChecked" to "1", "strait.installTap" to installTapValue(click, t0))
            override fun get(key: String) = data[key]
            override fun set(key: String, value: String) {
                if (key == "strait.installTap") throw IllegalStateException("disk full")
                data[key] = value
            }
        }
        now = t0 + DAY_MS
        val c = client(e, s)
        c.start(null)
        c.onAppState(AppLifecycleState.ACTIVE)
        assertEquals(emptyList(), e.notes())
    }

    @Test
    fun offlineNoteIsQueuedThenFlushedOptedOutSendsNothingAndClears() {
        now = t0 + 7 * DAY_MS
        var down = true
        val e = Engine { if (down) HttpResponse(503, "{}") else HttpResponse(202, """{"ok":true}""") }
        val s = MemoryStore()
        s.data["strait.deferredChecked"] = "1"
        s.data["strait.installTap"] = installTapValue(click, t0, listOf(1))
        val c = client(e, s)
        c.random = { 0.0 }
        c.start(null)
        val queued = JSONArray(s.data["strait.pendingOpens"]).getJSONObject(0)
        assertEquals("return", queued.getString("type"))
        assertEquals("r7-$click", queued.getString("openId"))
        assertEquals(7, queued.getInt("day"))
        assertFalse(queued.has("publishableKey"))
        assertEquals("1,7", installTap(s).getJSONArray("sent").join(","))

        down = false
        now += 3_600_000 // past the C1 hold
        c.flushOpenReports()
        val (path, body) = e.sent.last()
        assertEquals("/v1/open", path)
        assertEquals("r7-$click", body.getString("openId"))
        assertEquals("st_pub_test_x", body.getString("publishableKey"))
        assertFalse(body.has("type"))
        assertFalse(body.has("deviceClass"))
        assertEquals(0, c.pendingOpenReports())

        c.setEnabled(false)
        assertEquals("", s.data["strait.installTap"])
        val before = e.sent.size
        now = t0 + 30 * DAY_MS
        c.onAppState(AppLifecycleState.ACTIVE)
        assertEquals(before, e.sent.size)
        assertTrue(e.notes().size == 2) // the 503 try + the flushed one, nothing more
    }
}
