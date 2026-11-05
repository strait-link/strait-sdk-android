package dev.strait.sdk

import org.json.JSONObject
import java.io.File
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * td2-23: every request that records an open or install carries `appVersion` (the host app's versionName,
 * from [StraitConfig.appVersion]) and `sdkVersion` ([SDK_VERSION]), so support can ask "which app version?".
 * Never on /v1/lookup (opted out) or /v1/event; nothing at all while switched off.
 */
class AppVersionTest {
    private val t = 1_800_000_000_000L
    private val click = "3f2a9c1e-7b4d-4e8a-9c0f-1a2b3c4d5e6f"

    private class Engine(val reply: (String) -> HttpResponse = { HttpResponse(200, """{"matched":false,"recorded":true}""") }) : HttpTransport {
        val calls = mutableListOf<Pair<String, JSONObject?>>()
        override fun request(url: String, method: String, jsonBody: String?): HttpResponse {
            val path = URI(url).path
            calls += path to jsonBody?.let { JSONObject(it) }
            return reply(path)
        }
        fun bodies(path: String) = calls.filter { it.first == path }.map { it.second!! }
    }

    private fun client(
        e: Engine,
        s: KeyValueStore = MemoryStore().apply { data["strait.deferredChecked"] = "1" },
        referrer: String? = null,
        appVersion: () -> String? = { "4.2.1" },
    ) = StraitClient(StraitConfig(
        publishableKey = "st_pub_test_x", endpoint = "https://acme.strait.link", storage = s,
        installReferrer = { referrer }, device = { DeviceFields(411, 2.625, "en", "Asia/Kolkata") },
        transport = e, clock = { t }, appVersion = appVersion,
    ))

    private fun assertVersions(body: JSONObject, app: String? = "4.2.1") {
        if (app == null) assertFalse(body.has("appVersion"), body.toString()) else assertEquals(app, body.optString("appVersion"))
        assertEquals(SDK_VERSION, body.optString("sdkVersion"))
    }

    private fun assertNoVersions(body: JSONObject) {
        assertFalse(body.has("appVersion"), body.toString())
        assertFalse(body.has("sdkVersion"), body.toString())
    }

    @Test
    fun resolveCarriesBothVersions() {
        val e = Engine()
        client(e).handleUrl("https://acme.strait.link/sale")
        assertVersions(e.bodies("/v1/resolve").single())
    }

    @Test
    fun openReportCarriesBothVersions() {
        val e = Engine { HttpResponse(202, "{}") }
        client(e).handleUrl("acme://product/42?strait_click=$click")
        assertVersions(e.bodies("/v1/open").single())
    }

    @Test
    fun queuedOpenRetryCarriesBothVersions() {
        var online = false
        val e = Engine { if (online) HttpResponse(202, "{}") else throw java.io.IOException("offline") }
        val c = client(e)
        c.handleUrl("acme://product/42")
        assertEquals(1, c.pendingOpenReports())
        online = true
        c.flushOpenReports()
        assertEquals(0, c.pendingOpenReports())
        assertVersions(e.bodies("/v1/open").last())
    }

    @Test
    fun referrerCarriesBothVersions() {
        val e = Engine { HttpResponse(200, """{"matched":true,"longUrl":"https://shop.example/p/1","linkId":"lnk_1"}""") }
        client(e, MemoryStore(), referrer = "strait_link=lnk_1&strait_click=$click").start(null)
        assertVersions(e.bodies("/v1/referrer").single())
    }

    @Test
    fun matchCarriesBothVersions() {
        val e = Engine()
        client(e, MemoryStore()).start(null)
        assertVersions(e.bodies("/v1/match").single())
    }

    @Test
    fun eventCarriesNeither() {
        val e = Engine { HttpResponse(202, "{}") }
        assertTrue(client(e).trackEvent("purchase", value = 5.0, eventId = "ord_1"))
        assertNoVersions(e.bodies("/v1/event").single())
    }

    @Test
    fun disabledSendsNeitherAndLookupCarriesNeither() {
        val e = Engine { path ->
            if (path == "/v1/lookup") HttpResponse(200, """{"matched":true,"longUrl":"https://shop.example/p/42"}""")
            else HttpResponse(202, "{}")
        }
        val c = client(e, MemoryStore())
        c.setEnabled(false)
        c.start(null)
        c.handleUrl("https://acme.strait.link/sale")
        c.handleUrl("acme://product/42")
        c.flushOpenReports()
        assertEquals(listOf("/v1/lookup"), e.calls.map { it.first })
        assertNoVersions(e.bodies("/v1/lookup").single())
    }

    @Test
    fun appVersionIsReadOnceTrimmedAndCapped() {
        var reads = 0
        val e = Engine { HttpResponse(202, "{}") }
        val c = client(e, appVersion = { reads++; "  " + "9".repeat(80) + "  " })
        c.handleUrl("acme://a/1")
        c.handleUrl("acme://a/2")
        assertEquals(1, reads)
        e.bodies("/v1/open").forEach { assertVersions(it, "9".repeat(64)) }
    }

    @Test
    fun badOrMissingAppVersionIsLeftOutButSdkVersionStillSent() {
        for (bad in listOf<() -> String?>({ null }, { "   " }, { "1.0-é" }, { "1.0\n2" }, { throw IllegalStateException("no PackageManager") })) {
            val e = Engine { HttpResponse(202, "{}") }
            client(e, appVersion = bad).handleUrl("acme://a/1")
            assertVersions(e.bodies("/v1/open").single(), app = null)
        }
    }

    @Test
    fun wireVersionRule() {
        assertEquals("1.2.3", wireVersion(" 1.2.3 "))
        assertEquals("a".repeat(64), wireVersion("a".repeat(65)))
        assertEquals("1.0 (beta)", wireVersion("1.0 (beta)"))
        assertNull(wireVersion(null))
        assertNull(wireVersion(""))
        assertNull(wireVersion("\t"))
        assertNull(wireVersion("1.0\u0000"))
        assertNull(wireVersion("版本1"))
    }

    @Test
    fun sdkVersionMatchesTheGradleVersion() {
        val gradle = Regex("""(?m)^version = "([^"]+)"""").find(File("build.gradle.kts").readText())!!.groupValues[1]
        assertEquals(gradle, SDK_VERSION)
    }
}
