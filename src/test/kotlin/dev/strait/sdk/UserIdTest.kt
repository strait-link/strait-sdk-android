package dev.strait.sdk

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract B27 (shared-spec ADR 0009, engine ADR 0110): setUserId(id) after the app's own sign-in; later events
 * carry it on the app.event webhook. Emails and phone numbers are hashed on the device. Port of
 * sdk-react-native/test/user-id.test.ts.
 */
class UserIdTest {
    private val t = 1_800_000_000_000L
    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** null status = offline. */
    private class Engine(var status: Int? = 202) : HttpTransport {
        val events = mutableListOf<JSONObject>()
        override fun request(url: String, method: String, jsonBody: String?): HttpResponse {
            if (URI(url).path == "/v1/event") events += JSONObject(jsonBody!!)
            return HttpResponse(status ?: throw java.io.IOException("offline"), "{}")
        }
    }

    private fun client(e: Engine, s: KeyValueStore = MemoryStore().apply { data["strait.deferredChecked"] = "1" }) =
        StraitClient(StraitConfig(
            publishableKey = "st_pub_test_x", endpoint = "https://links.test", storage = s,
            device = { DeviceFields(411, 2.625, "en", "Asia/Kolkata") }, transport = e, clock = { t },
        ))
    private fun queue(s: KeyValueStore) = JSONArray(s.get("strait.pendingOpens") ?: "[]").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }

    @Test
    fun fieldRule() {
        assertEquals(UserIdField("user_4821", false), userIdField("  user_4821 "))
        assertEquals(UserIdField(sha("priya@example.com"), true), userIdField(" Priya@Example.COM "))
        assertEquals(UserIdField(sha("josé@example.com"), true), userIdField("JOSÉ@Example.com"))
        assertEquals(UserIdField(sha("+919876543210"), true), userIdField("+91 98765-43210"))
        // v15 (shared-spec ADR 0010): formats v14 sent raw; 00 = +; the hashed form keeps the +.
        for (p in listOf("(+91) 98765 43210", "0091 98765 43210", "+91\u00A098765\u00A043210", "\u202A+91 98765 43210\u202C",
                "+91\u201398765\u201343210", "tel:+91-98765-43210", "\uFF0B\uFF19\uFF11 \uFF19\uFF18\uFF17\uFF16\uFF15 \uFF14\uFF13\uFF12\uFF11\uFF10"))
            assertEquals(UserIdField(sha("+919876543210"), true), userIdField(p), p)
        assertEquals(UserIdField(sha("9876543210"), true), userIdField("\u096F\u096E\u096D\u096C\u096B\u096A\u0969\u0968\u0967\u0966"))
        assertEquals(UserIdField(sha("9876543210"), true), userIdField("98765/43210"))
        assertEquals(UserIdField(sha("priya\uFF20example.com"), true), userIdField("Priya\uFF20Example.com"))
        for (id in listOf("98765+43210", "+91 98765 43210 ext 12", "TEL:1234567", "98765_43210")) assertEquals(UserIdField(id, false), userIdField(id), id)
        for (bad in listOf(null, "", "   ", "x".repeat(USER_ID_MAX + 1), "a\nb", "a\u007fb")) assertNull(userIdField(bad), "$bad")
        assertEquals(UserIdField("x".repeat(USER_ID_MAX), false), userIdField("x".repeat(USER_ID_MAX)))
    }

    @Test
    fun trimIsJavaScriptsWhitespaceSet() {
        // Stripped like String.prototype.trim: BOM, NBSP, line/paragraph separators, ideographic space...
        for (ws in listOf("\uFEFF", "\u00A0", "\u1680", "\u2000", "\u200A", "\u2028", "\u2029", "\u202F", "\u205F", "\u3000", "\t\n\u000B\u000C\r "))
            assertEquals(UserIdField("user_4821", false), userIdField("${ws}user_4821$ws"), "trim ${ws.map { it.code }}")
        // Kept (JS doesn't strip them): U+001C-U+001F are then refused as control characters; U+0085 / U+200B stay in the id.
        for (c in '\u001C'..'\u001F') assertNull(userIdField("user_4821$c"))
        assertEquals(UserIdField("user_4821\u0085", false), userIdField("user_4821\u0085"))
        assertEquals(UserIdField("\u200Buser_4821", false), userIdField("\u200Buser_4821"))
    }

    @Test
    fun toStringNeverPrintsTheId() {
        assertFalse(userIdField("user_4821").toString().contains("4821"))
        assertFalse(userIdField("Priya@Example.com").toString().contains(sha("priya@example.com")))
    }

    @Test
    fun theIdIsReadWhenTrackEventIsCalled() {
        // A setUserId on another thread while trackEvent runs (here: during its storage read) never changes that event.
        val e = Engine()
        lateinit var c: StraitClient
        val s = object : KeyValueStore {
            val data = mutableMapOf("strait.deferredChecked" to "1")
            override fun get(key: String): String? { if (key == "strait.lastTap") c.setUserId("other"); return data[key] }
            override fun set(key: String, value: String) { data[key] = value }
        }
        c = client(e, s)
        c.setUserId("user_1")
        c.trackEvent("purchase")
        assertEquals("user_1", e.events.single().getString("userId"))
    }

    @Test
    fun eventsAfterSetUserIdCarryItAndNullStopsIt() {
        val e = Engine()
        val c = client(e)
        c.trackEvent("open_cart")
        c.setUserId("user_4821")
        c.trackEvent("purchase")
        c.setUserId(null)
        c.trackEvent("after_logout")
        assertFalse(e.events[0].has("userId") || e.events[0].has("userIdHashed"))
        assertEquals("user_4821", e.events[1].getString("userId"))
        assertEquals(false, e.events[1].getBoolean("userIdHashed"))
        assertFalse(e.events[2].has("userId") || e.events[2].has("userIdHashed"))
    }

    @Test
    fun anEmailNeverLeavesTheDeviceOnlyItsHashIsSentAndQueued() {
        val e = Engine(status = null)
        val s = MemoryStore().apply { data["strait.deferredChecked"] = "1" }
        val c = client(e, s)
        c.setUserId("Priya@Example.com")
        assertFalse(c.trackEvent("purchase"))
        assertEquals(sha("priya@example.com"), e.events[0].getString("userId"))
        assertTrue(e.events[0].getBoolean("userIdHashed"))
        assertFalse(e.events[0].toString().contains("priya", ignoreCase = true))
        val stored = s.get("strait.pendingOpens")!!
        assertTrue(stored.contains(sha("priya@example.com")))
        assertFalse(s.data.values.any { it.contains("priya", ignoreCase = true) }, "raw email in storage")
    }

    @Test
    fun theIdAtTheTimeOfTheEventIsSentEvenWhenTheEventIsSentLater() {
        val e = Engine(status = null)
        val s = MemoryStore().apply { data["strait.deferredChecked"] = "1" }
        val c = client(e, s)
        c.setUserId("user_1")
        c.trackEvent("purchase", eventId = "ord_1")
        c.setUserId("user_2")
        assertEquals("user_1", queue(s)[0].getString("userId"))
        e.status = 202
        c.flushOpenReports()
        assertEquals("user_1", e.events.last().getString("userId"))
    }

    @Test
    fun optingOutClearsItAndSetUserIdWhileOffIsIgnored() {
        val e = Engine()
        val c = client(e)
        c.setUserId("user_4821")
        c.setEnabled(false)
        c.setUserId("user_9")
        c.setEnabled(true)
        c.trackEvent("purchase")
        assertEquals(1, e.events.size)
        assertFalse(e.events[0].has("userId"))
    }

    @Test
    fun aStoredOptOutWinsOverAnIdSetBeforeTheSwitchWasRead() {
        val e = Engine()
        val s = MemoryStore().apply { data["strait.deferredChecked"] = "1"; data["strait.enabled"] = "0" }
        val c = client(e, s)
        c.setUserId("user_4821")
        c.setEnabled(true)
        c.trackEvent("purchase")
        assertFalse(e.events.single().has("userId"))
    }

    @Test
    fun aBadValueClearsThePreviousId() {
        val e = Engine()
        val c = client(e)
        c.setUserId("user_1")
        c.setUserId("x".repeat(USER_ID_MAX + 1))
        c.trackEvent("purchase")
        assertFalse(e.events.single().has("userId"))
    }
}
