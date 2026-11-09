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
        assertEquals(UserIdField(sha("919876543210"), true), userIdField("+91 98765-43210"))
        for (bad in listOf(null, "", "   ", "x".repeat(USER_ID_MAX + 1), "a\nb", "a\u007fb")) assertNull(userIdField(bad), "$bad")
        assertEquals(UserIdField("x".repeat(USER_ID_MAX), false), userIdField("x".repeat(USER_ID_MAX)))
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
