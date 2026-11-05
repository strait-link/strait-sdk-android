package dev.strait.sdk

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Opt-out (td2-27, ADR 0002): setEnabled(false) = no reports, no events, no install matching, kept across
 * launches; a link that carries its own destination still reaches onLink. Port of
 * sdk-react-native/test/opt-out.test.ts.
 */
class OptOutTest {
    private val t = 1_800_000_000_000L

    private class Engine : HttpTransport {
        val calls = mutableListOf<String>()
        override fun request(url: String, method: String, jsonBody: String?): HttpResponse {
            calls += URI(url).path
            return HttpResponse(202, """{"matched":false}""")
        }
    }

    private fun client(e: Engine, s: KeyValueStore) = StraitClient(StraitConfig(
        publishableKey = "st_pub_test_x", endpoint = "https://acme.strait.link", storage = s,
        device = { DeviceFields(411, 2.625, "en", "Asia/Kolkata") }, transport = e, clock = { t },
    ))

    @Test
    fun disabledSendsNothingAndLinksStillRoute() {
        val e = Engine()
        // Empty storage = a first launch (the deferred check is due), plus one report saved earlier.
        val s = MemoryStore().apply {
            data["strait.pendingOpens"] = """[{"openId":"o_1","kind":"direct","route":"app_link","appState":"closed","platform":"android","matched":true,"firstLaunch":false,"at":$t}]"""
        }
        val c = client(e, s)
        val links = mutableListOf<LinkEvent>()
        c.onLink { links += it }
        c.setEnabled(false)
        c.start(null)
        c.handleUrl("acme://product/42")
        c.handleUrl("https://acme.strait.link/sale") // a short link needs a lookup, which counts the tap
        assertFalse(c.trackEvent("purchase", value = 5.0, eventId = "ord_1"))
        c.flushOpenReports()
        assertEquals("disabled", c.checkDeferred().reason)
        assertEquals(emptyList(), e.calls)
        assertTrue(links.any { it.matched && it.rawUrl == "acme://product/42" })
        assertTrue(links.any { !it.matched && it.reason == "disabled" && it.rawUrl == "https://acme.strait.link/sale" })
        assertEquals(0, c.pendingOpenReports()) // reports saved before the opt-out are deleted
        assertEquals("", s.get("strait.deferredChecked") ?: "") // the check is still due
    }

    @Test
    fun persistsAcrossLaunches() {
        val s = MemoryStore()
        client(Engine(), s).setEnabled(false)
        val e = Engine()
        val c = client(e, s) // next launch, same storage
        assertFalse(c.isEnabled())
        c.start(null)
        c.trackEvent("signup")
        assertEquals(emptyList(), e.calls)
    }

    @Test
    fun reEnablingResumesRunsTheSkippedFirstLaunchCheckAndSendsEvents() {
        val e = Engine()
        val s = MemoryStore()
        val c = client(e, s)
        c.setEnabled(false)
        c.start(null)
        assertEquals(emptyList(), e.calls)
        c.setEnabled(true)
        assertTrue(c.isEnabled())
        assertTrue("/v1/match" in e.calls)
        assertEquals("1", s.get("strait.deferredChecked"))
        assertTrue(c.trackEvent("purchase", eventId = "ord_2"))
        assertTrue("/v1/event" in e.calls)
    }
}
