package dev.strait.sdk

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Port of sdk-react-native/test/install-state.test.ts (B6a, shared-spec ADR 0003) and
 * test/events-v2.test.ts (B13a, ADR 0004).
 */
class InstallStateEventsTest {
    private val pk = "st_pub_test_x"
    private val tOld = 1_780_000_000_000L
    private val tNew = 1_790_000_000_000L
    private val eventIdRe = Regex("^e_[a-z0-9]+_[a-z0-9]{12}$")

    private data class Call(val path: String, val body: JSONObject?)

    /** status per path; null = no answer (offline). */
    private class Engine : HttpTransport {
        val routes = mutableMapOf<String, Int?>("/v1/match" to 200, "/v1/open" to 202, "/v1/event" to 202)
        val calls = mutableListOf<Call>()
        fun of(path: String) = calls.filter { it.path == path }
        override fun request(url: String, method: String, jsonBody: String?): HttpResponse {
            val path = URI(url).path
            calls += Call(path, jsonBody?.let { JSONObject(it) })
            val status = if (routes.containsKey(path)) routes[path] ?: throw java.io.IOException("offline") else 404
            val body = if (path == "/v1/match") """{"matched":false}""" else "{}"
            return HttpResponse(status, body)
        }
    }

    private var t = 1_800_000_000_000L
    private fun client(engine: Engine, storage: KeyValueStore, installTime: () -> Long? = { null }, platform: String = "ios") =
        StraitClient(StraitConfig(
            publishableKey = pk, endpoint = "https://links.test", storage = storage, platform = platform,
            device = { DeviceFields(390, 3.0, "en", "Asia/Kolkata") }, transport = engine, clock = { t },
            installTime = installTime,
        ))
    private fun store(vararg kv: Pair<String, String>) = MemoryStore().apply { data.putAll(kv) }

    // --- B6a ---------------------------------------------------------------

    @Test
    fun firstLaunchStateCases() {
        assertEquals(FirstLaunchState(false, false, null), firstLaunchState("1", "$tNew", tNew.toDouble()))
        assertEquals(FirstLaunchState(false, false, "$tNew"), firstLaunchState("1", null, tNew.toDouble()))
        assertEquals(FirstLaunchState(true, true, "$tNew"), firstLaunchState("1", "$tOld", tNew.toDouble()))
        assertEquals(FirstLaunchState(true, false, "$tNew"), firstLaunchState(null, null, tNew.toDouble()))
        assertEquals(FirstLaunchState(false, false, null), firstLaunchState("1", "$tOld", null))
        assertEquals(FirstLaunchState(false, false, null), firstLaunchState("1", "$tOld", Double.NaN))
    }

    @Test
    fun restoredStorageRunsTheDeferredCheckOnceAndCountsTheInstall() {
        val s = store("strait.deferredChecked" to "1", "strait.installTime" to "$tOld")
        val e = Engine()
        val events = mutableListOf<LinkEvent>()
        client(e, s, { tNew }).apply { onLink { events += it } }.start(null)
        assertEquals(1, e.of("/v1/match").size)
        assertTrue(e.of("/v1/match")[0].body!!.getString("openId").startsWith("o_"))
        assertEquals(listOf("deferred"), events.map { it.kind })
        assertEquals("1", s.get("strait.deferredChecked"))
        assertEquals("$tNew", s.get("strait.installTime"))
        client(e, s, { tNew }).start(null) // next launch: a normal one
        assertEquals(1, e.of("/v1/match").size)
    }

    @Test
    fun sdkUpgradeAdoptsTheInstallTimeWithoutANewInstall() {
        val s = store("strait.deferredChecked" to "1")
        val e = Engine()
        client(e, s, { tNew }).start(null)
        assertEquals(0, e.of("/v1/match").size)
        assertEquals("$tNew", s.get("strait.installTime"))
    }

    @Test
    fun restoredStorageThenOfflineFirstLaunchRetriesNextLaunch() {
        val s = store("strait.deferredChecked" to "1", "strait.installTime" to "$tOld")
        val e = Engine().apply { routes["/v1/match"] = null }
        client(e, s, { tNew }).start(null)
        assertEquals("", s.get("strait.deferredChecked"))
        e.routes["/v1/match"] = 200
        client(e, s, { tNew }).start(null)
        assertEquals(2, e.of("/v1/match").size)
        assertEquals("1", s.get("strait.deferredChecked"))
    }

    @Test
    fun unknownOrThrowingInstallTimeKeepsTheOldBehaviour() {
        for (it in listOf<() -> Long?>({ null }, { throw IllegalStateException("no package info") }, { 0L }, { -5L })) {
            val s = store("strait.deferredChecked" to "1", "strait.installTime" to "$tOld")
            val e = Engine()
            client(e, s, it).start(null)
            assertEquals(0, e.of("/v1/match").size)
            assertEquals("$tOld", s.get("strait.installTime"))
        }
    }

    @Test
    fun freshInstallStoresItsInstallTime() {
        val s = store()
        val e = Engine()
        client(e, s, { tNew }).start(null)
        assertEquals(1, e.of("/v1/match").size)
        assertEquals("$tNew", s.get("strait.installTime"))
        assertEquals("1", s.get("strait.deferredChecked"))
    }

    // --- B13a --------------------------------------------------------------

    private fun ready(e: Engine = Engine(), s: MemoryStore = store("strait.deferredChecked" to "1")) =
        Triple(client(e, s, platform = "android").also { it.start(null) }, e, s)
    private fun queue(s: MemoryStore): List<JSONObject> = JSONArray(s.get("strait.pendingOpens") ?: "[]").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }

    @Test
    fun newEventIdShape() {
        val a = newEventId(t)
        assertTrue(eventIdRe.matches(a), a)
        assertTrue(Regex("^[A-Za-z0-9_.:-]{1,64}$").matches(a))
        assertTrue(a != newEventId(t))
    }

    @Test
    fun sendsTheAppsEventIdOrMakesOne() {
        val (c, e) = ready()
        assertTrue(c.trackEvent("purchase", value = 499.0, currency = "INR", eventId = "ord_1001"))
        val b = e.of("/v1/event")[0].body!!
        assertEquals("ord_1001", b.getString("eventId"))
        assertEquals(pk, b.getString("publishableKey"))
        c.trackEvent("signup")
        assertTrue(eventIdRe.matches(e.of("/v1/event")[1].body!!.getString("eventId")))
    }

    @Test
    fun offlineEventIsQueuedWithoutTheKeyAndSentLaterWithTheSameEventId() {
        val (c, e, s) = ready()
        e.routes["/v1/event"] = null
        assertFalse(c.trackEvent("purchase", value = 5.0, currency = "USD", eventId = "ord_7"))
        val saved = queue(s)
        assertEquals(1, saved.size)
        val q = saved[0]
        assertEquals(listOf("event", "$t", "purchase", "android", "ord_7", "5.0", "USD"),
            listOf(q.getString("type"), q.get("at").toString(), q.getString("event"), q.getString("platform"), q.getString("eventId"), q.getDouble("value").toString(), q.getString("currency")))
        assertEquals(7, saved[0].length())
        assertFalse(saved[0].toString().contains("st_pub_"))
        assertEquals(1, c.pendingOpenReports())
        e.routes["/v1/event"] = 202
        c.onAppState(AppLifecycleState.ACTIVE)
        val sent = e.of("/v1/event").last().body!!
        assertEquals("ord_7", sent.getString("eventId"))
        assertEquals(pk, sent.getString("publishableKey"))
        assertFalse(sent.has("type") || sent.has("at"))
        assertEquals(0, e.of("/v1/open").size)
        assertEquals(0, queue(s).size)
    }

    @Test
    fun fiveHundredAnd429AreKeptOther4xxIsNot() {
        val (c, e, s) = ready()
        e.routes["/v1/event"] = 400
        assertFalse(c.trackEvent("c", eventId = "bad id!"))
        e.routes["/v1/event"] = 503
        c.trackEvent("a")
        e.routes["/v1/event"] = 429
        c.trackEvent("b")
        assertEquals(listOf("a", "b"), queue(s).map { it.getString("event") })
    }

    @Test
    fun opensAndEventsShareTheQueue() {
        val (c, e, s) = ready()
        e.routes["/v1/event"] = null
        c.trackEvent("purchase", eventId = "ord_9")
        val open = JSONObject().put("openId", "o_1_aaaaaaaaaaaa").put("kind", "direct").put("route", "app_link").put("appState", "closed")
            .put("platform", "android").put("matched", true).put("firstLaunch", false).put("at", t)
        s.set("strait.pendingOpens", JSONArray().put(open).put(queue(s)[0]).toString())
        e.routes["/v1/event"] = 202
        c.flushOpenReports()
        assertEquals(listOf("o_1_aaaaaaaaaaaa"), e.of("/v1/open").map { it.body!!.getString("openId") })
        assertEquals(2, e.of("/v1/event").count { it.body!!.getString("eventId") == "ord_9" })
        assertEquals(0, queue(s).size)
    }

    @Test
    fun theTapIdIsChosenWhenTheAppCalls() {
        val (c, e, s) = ready()
        val tap = "3f2a9c1e-7b4d-4e8a-9c0f-1a2b3c4d5e6f"
        s.set("strait.lastTap", rememberTap(tap, t))
        e.routes["/v1/event"] = null
        c.trackEvent("purchase", eventId = "ord_10")
        assertEquals(tap, queue(s)[0].getString("clickId"))
    }

    @Test
    fun anAcceptedEventSendsTheSavedQueueOnTheExecutorNotOnTheCallersThread() {
        // Review 5 Nov: trackEvent blocks its caller for one request; the backlog it triggers
        // (up to 100 saved reports) must run on the SDK's executor, like report().
        val e = Engine()
        val s = store("strait.deferredChecked" to "1")
        val later = ArrayDeque<Runnable>()
        val c = StraitClient(StraitConfig(
            publishableKey = pk, endpoint = "https://links.test", storage = s, platform = "android",
            device = { DeviceFields(390, 3.0, "en", "Asia/Kolkata") }, transport = e, clock = { t },
            executor = { later.addLast(it) },
        ))
        c.start(null)
        while (later.isNotEmpty()) later.removeFirst().run()
        e.routes["/v1/event"] = null
        c.trackEvent("purchase", eventId = "ord_11") // offline: saved
        e.routes["/v1/event"] = 202
        assertTrue(c.trackEvent("purchase", eventId = "ord_12"))
        assertEquals(listOf("ord_11", "ord_12"), e.of("/v1/event").map { it.body!!.getString("eventId") }.distinct())
        assertEquals(1, e.of("/v1/event").count { it.body!!.getString("eventId") == "ord_11" }) // not re-sent yet
        while (later.isNotEmpty()) later.removeFirst().run()
        assertEquals(2, e.of("/v1/event").count { it.body!!.getString("eventId") == "ord_11" })
        assertEquals(0, queue(s).size)
    }
}
