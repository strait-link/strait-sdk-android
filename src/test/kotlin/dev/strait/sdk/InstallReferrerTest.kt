package dev.strait.sdk

import org.json.JSONObject
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * B26 (engine ADR 0106, shared-spec ADR 0008): the once-per-install check sends the raw Play install referrer as
 * `installReferrer` on the calls that record the install (/v1/referrer and its /v1/match fallback), so the install
 * keeps its campaign even when the tap never went through a Strait link. 1..4096 characters, else left out; never on
 * the record:false re-check, never off Android, nothing while switched off.
 */
class InstallReferrerTest {
    private val ads = "utm_source=google&utm_medium=cpc&utm_campaign=strait-qa&gclid=Cj0KCQ"
    private val strait = "strait_link=lnk_1&strait_click=3f2a9c1e-7b4d-4e8a-9c0f-1a2b3c4d5e6f&utm_source=google-play"

    private class Engine(val reply: (String) -> HttpResponse = { HttpResponse(200, """{"matched":false}""") }) : HttpTransport {
        val calls = mutableListOf<Pair<String, JSONObject>>()
        override fun request(url: String, method: String, jsonBody: String?): HttpResponse {
            val path = URI(url).path
            calls += path to JSONObject(jsonBody ?: "{}")
            return reply(path)
        }
        fun body(path: String) = calls.single { it.first == path }.second
        val paths get() = calls.map { it.first }
    }

    private fun client(e: Engine, referrer: String?, platform: String = "android", s: KeyValueStore = MemoryStore()) =
        StraitClient(StraitConfig(
            publishableKey = "st_pub_test_x", endpoint = "https://acme.strait.link", storage = s, platform = platform,
            installReferrer = { referrer }, device = { DeviceFields(411, 2.625, "en", "Asia/Kolkata") },
            transport = e, clock = { 1_800_000_000_000L },
        ))

    @Test
    fun fieldRule() {
        assertEquals(4096, INSTALL_REFERRER_MAX)
        assertEquals(ads, installReferrerField(ads))
        assertEquals("a".repeat(4096), installReferrerField("a".repeat(4096)))
        assertNull(installReferrerField("a".repeat(4097)))
        assertNull(installReferrerField(""))
        assertNull(installReferrerField(null))
    }

    @Test
    fun googleAdsInstallSendsTheReferrerOnMatch() {
        val e = Engine()
        client(e, ads).start(null)
        assertEquals(listOf("/v1/match"), e.paths)
        assertEquals(ads, e.body("/v1/match").optString("installReferrer"))
        assertTrue(e.body("/v1/match").has("openId"))
    }

    @Test
    fun straitLinkInstallSendsItOnReferrerAndOnTheMatchFallback() {
        val matched = Engine { if (it == "/v1/referrer") HttpResponse(200, """{"matched":true,"longUrl":"https://shop.example/p/1","linkId":"lnk_1"}""") else HttpResponse(200, "{}") }
        client(matched, strait).start(null)
        assertEquals(strait, matched.body("/v1/referrer").optString("installReferrer"))
        val unmatched = Engine()
        client(unmatched, strait).start(null)
        assertEquals(listOf("/v1/referrer", "/v1/match"), unmatched.paths)
        assertEquals(strait, unmatched.body("/v1/match").optString("installReferrer"))
    }

    @Test
    fun tooLongEmptyOrNoneIsLeftOut() {
        for (r in listOf("$ads&x=" + "a".repeat(4096), "", null)) {
            val e = Engine()
            client(e, r).start(null)
            assertFalse(e.body("/v1/match").has("installReferrer"), "referrer length ${r?.length}")
        }
    }

    @Test
    fun notOffAndroid() {
        val e = Engine()
        client(e, ads, platform = "ios").start(null)
        assertFalse(e.body("/v1/match").has("installReferrer"))
    }

    @Test
    fun recheckSendsNoReferrer() {
        val e = Engine()
        client(e, ads, s = MemoryStore().apply { data["strait.deferredChecked"] = "1" }).checkDeferred()
        assertFalse(e.body("/v1/match").optBoolean("record", true))
        assertFalse(e.body("/v1/match").has("installReferrer"))
    }

    @Test
    fun switchedOffSendsNothing() {
        val e = Engine()
        val c = client(e, ads)
        c.setEnabled(false)
        c.start(null)
        assertEquals(emptyList(), e.paths)
    }
}
