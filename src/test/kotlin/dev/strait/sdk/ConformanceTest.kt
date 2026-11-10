package dev.strait.sdk

import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** shared-spec/conformance-vectors.json: every case, every function (B2, B4, B5, B7, B12, B14). */
class ConformanceTest {
    private val v: JSONObject by lazy {
        JSONObject(this::class.java.getResourceAsStream("/conformance-vectors.json")!!.bufferedReader().readText())
    }

    private fun params(o: JSONObject): Map<String, String> =
        o.keySet().associateWith { o.getString(it) }

    private fun objects(name: String): List<JSONObject> {
        val arr = v.getJSONArray(name)
        assertTrue(arr.length() > 0, "no $name vectors")
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    @Test
    fun constants() {
        val c = v.getJSONObject("constants")
        assertEquals(c.getLong("RESUME_WINDOW_MS"), RESUME_WINDOW_MS)
        assertEquals(c.getLong("TRANSIENT_PAUSE_MS"), TRANSIENT_PAUSE_MS)
        assertEquals(c.getInt("OPEN_QUEUE_MAX"), OPEN_QUEUE_MAX)
        assertEquals(c.getLong("OPEN_QUEUE_MAX_AGE_MS"), OPEN_QUEUE_MAX_AGE_MS)
        assertEquals(c.getLong("ATTRIBUTION_WINDOW_MS"), ATTRIBUTION_WINDOW_MS)
        assertEquals(c.getInt("INSTALL_REFERRER_MAX"), INSTALL_REFERRER_MAX)
        assertEquals(c.getInt("USER_ID_MAX"), USER_ID_MAX)
        val days = c.getJSONArray("RETURN_DAYS")
        assertEquals((0 until days.length()).map { days.getInt(it) }, RETURN_DAYS)
        assertEquals(c.getLong("DAY_MS"), DAY_MS)
        assertEquals(9, c.length(), "unexpected constants: $c")
        assertEquals(17, v.getInt("version"), "vectors v17")
    }

    @Test
    fun returnNoteVectors() {
        for (c in objects("returnNote")) {
            val stored = if (c.isNull("stored")) null else c.getString("stored")
            val want = if (c.isNull("expected")) null else c.getJSONObject("expected").let { ReturnNote(it.getInt("day"), it.getString("clickId"), it.getString("openId")) }
            assertEquals(want, returnNote(stored, c.getDouble("now")), c.getString("name"))
        }
    }

    @Test
    fun userIdVectors() {
        for (c in objects("userId")) {
            val input = if (c.isNull("input")) null else c.getString("input")
            val want = if (c.isNull("expected")) null else c.getJSONObject("expected").let { UserIdField(it.getString("userId"), it.getBoolean("userIdHashed")) }
            assertEquals(want, userIdField(input), "userId: ${c.getString("name")}")
        }
    }

    @Test
    fun installReferrerVectors() {
        for ((i, c) in objects("installReferrer").withIndex()) {
            val input = if (c.isNull("input")) null else c.getString("input")
            val want = if (c.isNull("expected")) null else c.getString("expected")
            assertEquals(want, installReferrerField(input), "installReferrer #$i")
        }
    }

    @Test
    fun eventClickIdVectors() {
        for (c in objects("eventClickId")) {
            val stored = if (c.isNull("stored")) null else c.getString("stored")
            val explicit = if (c.isNull("explicit")) null else c.getString("explicit")
            val expected = if (c.isNull("expected")) null else c.getString("expected")
            assertEquals(expected, eventClickId(stored, c.getLong("now"), explicit), c.getString("name"))
        }
    }

    @Test
    fun firstLaunchStateVectors() {
        for (c in objects("firstLaunchState")) {
            val flag = if (c.isNull("flag")) null else c.getString("flag")
            val stored = if (c.isNull("stored")) null else c.getString("stored")
            val installTime = if (c.isNull("installTime")) null else c.getDouble("installTime")
            val e = c.getJSONObject("expected")
            val want = FirstLaunchState(e.getBoolean("firstLaunch"), e.getBoolean("clearFlag"), if (e.isNull("storeInstallTime")) null else e.getString("storeInstallTime"))
            assertEquals(want, firstLaunchState(flag, stored, installTime), c.getString("name"))
        }
    }

    @Test
    fun replyClickIdVectors() {
        for (c in objects("replyClickId")) {
            val reply = if (c.isNull("reply")) null else c.get("reply")
            val fallback = if (c.isNull("fallback")) null else c.getString("fallback")
            val expected = if (c.isNull("expected")) null else c.getString("expected")
            assertEquals(expected, replyClickId(reply, fallback), c.getString("name"))
        }
    }

    @Test
    fun reportUrlVectors() {
        for (c in objects("reportUrl")) assertEquals(c.getString("expected"), reportUrl(c.getString("input")), c.getString("input"))
    }

    @Test
    fun staleTapVectors() {
        for (c in objects("staleTap")) {
            val stored = if (c.isNull("stored")) null else c.getString("stored")
            assertEquals(c.getBoolean("expected"), staleTap(stored, c.getLong("now")), c.getString("name"))
        }
    }

    @Test
    fun screenWidth() {
        for (c in objects("screenWidth")) {
            assertEquals(c.getInt("expected"), browserScreenWidth(c.getDouble("logical")), "logical=${c.get("logical")}")
        }
    }

    @Test
    fun deviceClassVectors() {
        for (c in objects("deviceClass")) {
            val expected = if (c.isNull("expected")) null else c.getString("expected")
            assertEquals(expected, deviceClassForSize(c.getDouble("shortestSide")), "shortestSide=${c.get("shortestSide")}")
        }
    }

    @Test
    fun portraitScreenWidthVectors() {
        for (c in objects("portraitScreenWidth")) {
            assertEquals(c.getInt("expected"), portraitScreenWidth(c.getDouble("width"), c.getDouble("height")),
                "${c.get("width")}x${c.get("height")}")
        }
    }

    @Test
    fun splitUrlVectors() {
        for (c in objects("splitUrl")) {
            val input = c.getString("input")
            val got = splitUrl(input)
            if (c.isNull("expected")) {
                assertNull(got, input); continue
            }
            val e = c.getJSONObject("expected")
            assertEquals(
                SplitUrl(e.getString("scheme"), e.getString("host"), e.getString("path"), params(e.getJSONObject("params"))),
                got, input,
            )
        }
    }

    @Test
    fun referrerVectors() {
        for (c in objects("referrer")) {
            val input = if (c.isNull("input")) null else c.getString("input")
            val expected = if (c.isNull("expected")) null else c.getString("expected")
            assertEquals(expected, parseStraitLink(input), "referrer=$input")
        }
    }

    @Test
    fun referrerClickVectors() {
        for (c in objects("referrerClick")) {
            val input = if (c.isNull("input")) null else c.getString("input")
            val expected = if (c.isNull("expected")) null else c.getString("expected")
            assertEquals(expected, parseStraitClick(input), "referrer=$input")
        }
    }

    @Test
    fun takeClickIdVectors() {
        for (c in objects("takeClickId")) {
            val input = c.getString("input")
            val e = c.getJSONObject("expected")
            val expected = TakenClickId(e.getString("url"), if (e.isNull("clickId")) null else e.getString("clickId"))
            assertEquals(expected, takeClickId(input), input)
        }
    }

    @Test
    fun openQueueVectors() {
        for (c in objects("openQueue")) {
            val q = c.getJSONArray("queue").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
            val got = pruneOpenQueue(q, c.getLong("now")) { it.getLong("at") }.map { it.getString("openId") }
            val expected = c.getJSONArray("expected").let { a -> (0 until a.length()).map { a.getString(it) } }
            assertEquals(expected, got, c.getString("name"))
        }
    }

    @Test
    fun retryVectors() {
        for (c in objects("retry")) {
            val status = if (c.isNull("status")) null else c.getInt("status")
            assertEquals(c.getBoolean("expected"), shouldRetryReport(status), "status=$status")
        }
    }

    @Test
    fun retryDelayVectors() {
        for (c in objects("retryDelay")) {
            val retryAfter = if (c.isNull("retryAfter")) null else c.getString("retryAfter")
            assertEquals(c.getLong("expected"), retryDelayMs(c.getInt("failures"), retryAfter, c.getDouble("random")), c.getString("name"))
        }
    }

    @Test
    fun newOpenIdShape() {
        val id = newOpenId(1_800_000_000_000)
        assertTrue(Regex("^o_[a-z0-9]+_[a-z0-9]{12}$").matches(id), id)
        assertTrue(id.startsWith("o_${1_800_000_000_000L.toString(36)}_"), id)
        assertEquals("o_${1_800_000_000_000L.toString(36)}_aaaaaaaaaaaa", newOpenId(1_800_000_000_000) { 0.0 })
        assertEquals("o_${1_800_000_000_000L.toString(36)}_999999999999", newOpenId(1_800_000_000_000) { 0.9999 })
    }

    @Test
    fun linkHostVectors() {
        for (c in objects("linkHosts")) {
            val extra = c.getJSONArray("linkHosts").let { a -> List(a.length()) { a.getString(it) } }
            val expected = c.getJSONArray("expected").let { a -> List(a.length()) { a.getString(it) } }
            assertEquals(expected, normalizeLinkHosts(c.getString("endpoint"), extra), "linkHosts=$extra")
        }
    }

    @Test
    fun classifyVectors() {
        for (c in objects("classify")) {
            val raw = c.getString("raw")
            val hosts = c.getJSONArray("linkHosts").let { a -> (0 until a.length()).map { a.getString(it) } }
            val got = classifyUrl(raw, hosts)
            if (c.isNull("expected")) {
                assertNull(got, raw); continue
            }
            val e = c.getJSONObject("expected")
            val expected = if (e.getBoolean("needsResolve")) {
                assertFalse(e.has("clickId"), raw)
                ClassifiedUrl(e.getString("route"), true)
            } else {
                assertTrue(e.has("clickId"), "clickId present (maybe null) on $raw")
                ClassifiedUrl(e.getString("route"), false, e.getString("url"), e.getString("path"), params(e.getJSONObject("params")),
                    if (e.isNull("clickId")) null else e.getString("clickId"))
            }
            assertEquals(expected, got, raw)
        }
    }

    @Test
    fun appStateVectors() {
        for (c in objects("appState")) {
            val t = AppStateTracker()
            val got = mutableListOf<String>()
            val steps: JSONArray = c.getJSONArray("steps")
            for (i in 0 until steps.length()) {
                val s = steps.getJSONArray(i)
                when (s.getString(0)) {
                    "state" -> t.onState(AppLifecycleState.fromWire(s.getString(1))!!, s.getLong(2))
                    "url" -> got += t.classify(s.getLong(1))
                    else -> error("unknown step ${s.getString(0)}")
                }
            }
            val exp = c.getJSONArray("expected").let { a -> (0 until a.length()).map { a.getString(it) } }
            assertEquals(exp, got, c.getString("name"))
        }
    }

    @Test
    fun decodeFallsBackLikeJs() {
        // decodeURIComponent throws on these; splitUrl keeps the raw text.
        assertEquals("100%", decode("100%"))
        assertEquals("%zz", decode("%zz"))
        assertEquals("%C3", decode("%C3"))
        assertEquals("a b", decode("a+b"))
    }
}
