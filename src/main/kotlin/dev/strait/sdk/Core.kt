package dev.strait.sdk

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import kotlin.math.ceil

/*
 * Pure, platform-free link logic, ported 1:1 from sdk-react-native/src/core.ts.
 * shared-spec/conformance-vectors.json is the cross-language contract
 * (see shared-spec/SDK-CONTRACT.md). No android.net.Uri / java.net.URI here:
 * behaviour must equal splitUrl exactly (B12).
 */

/**
 * Screen width as a browser reports it (`screen.width`). Chrome rounds
 * fractional logical widths UP (1080 px at 2.625 = 411.43 -> 412). Matching
 * needs the app and the browser at the tap to agree (B2).
 */
internal fun browserScreenWidth(logicalWidth: Double): Int = ceil(logicalWidth - 0.001).toInt()

/**
 * The `screenWidth` device field (B17): the screen's SHORTER side, as a browser
 * reports it. Safari's `screen.width` at the tap is the portrait width whatever
 * the orientation, so an app first launched in landscape (844x390) still reports 390.
 * On Android: `portraitScreenWidth(m.widthPixels / m.density, m.heightPixels / m.density)`.
 */
fun portraitScreenWidth(logicalWidth: Double, logicalHeight: Double): Int =
    browserScreenWidth(minOf(logicalWidth, logicalHeight))

/**
 * The `deviceClass` sent with open reports and the store sheet (contract B14,
 * engine ADR 0039), from the screen's shorter side in dp: "tablet" from 600
 * (Android's sw600dp rule), else "phone"; null when unknown (0, negative, NaN).
 * On Android: `deviceClassForSize(resources.configuration.smallestScreenWidthDp.toDouble())`.
 */
fun deviceClassForSize(shortestSide: Double): String? = when {
    !shortestSide.isFinite() || shortestSide <= 0.0 -> null
    shortestSide >= 600.0 -> "tablet"
    else -> "phone"
}

internal data class SplitUrl(
    val scheme: String,
    val host: String,
    val path: String,
    val params: Map<String, String>,
)

private val URL_RE = Regex("^([a-z][a-z0-9+.-]*)://([^/?#]*)([^?#]*)(?:\\?([^#]*))?", RegexOption.IGNORE_CASE)
private val SCHEME_RE = Regex("^[a-z][a-z0-9+.-]*://", RegexOption.IGNORE_CASE)

/**
 * Split a URL without platform URL classes. Scheme and host are lower-cased;
 * '+' and %-escapes in the query are decoded; the fragment is dropped.
 */
internal fun splitUrl(u: String): SplitUrl? {
    val m = URL_RE.find(u.trim()) ?: return null
    val params = LinkedHashMap<String, String>()
    for (pair in m.groupValues[4].split('&')) {
        if (pair.isEmpty()) continue
        val i = pair.indexOf('=')
        val k = if (i < 0) pair else pair.substring(0, i)
        val v = if (i < 0) "" else pair.substring(i + 1)
        params[decode(k)] = decode(v)
    }
    val path = m.groupValues[3].ifEmpty { "/" }
    return SplitUrl(m.groupValues[1].lowercase(), m.groupValues[2].lowercase(), path, params)
}

/**
 * JS `decodeURIComponent(s.replace(/\+/g, ' '))`, returning `s` unchanged when
 * the escapes are malformed or not valid UTF-8 (where JS would throw).
 */
internal fun decode(s: String): String {
    val plus = s.replace('+', ' ')
    if ('%' !in plus) return plus
    val out = StringBuilder(plus.length)
    var i = 0
    while (i < plus.length) {
        val c = plus[i]
        if (c != '%') {
            out.append(c); i++; continue
        }
        val bytes = ByteArrayOutputStream()
        while (i < plus.length && plus[i] == '%') {
            if (i + 2 >= plus.length) return s
            val b = plus.substring(i + 1, i + 3).toIntOrNull(16) ?: return s
            if (plus[i + 1] == '+' || plus[i + 1] == '-') return s
            bytes.write(b)
            i += 3
        }
        try {
            val dec = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            out.append(dec.decode(ByteBuffer.wrap(bytes.toByteArray())))
        } catch (_: CharacterCodingException) {
            return s
        }
    }
    return out.toString()
}

/**
 * The hosts that serve this app's short links: the endpoint's host plus any
 * configured link domains, given as URLs ("https://go.brand.com") or bare
 * hosts ("go.brand.com"). Lower-cased, de-duplicated, order kept; anything
 * else (blank, paths, spaces) is ignored.
 */
internal fun normalizeLinkHosts(endpoint: String, linkHosts: List<String> = emptyList()): List<String> {
    val bare = Regex("^[a-z0-9.-]+(:\\d+)?$", RegexOption.IGNORE_CASE)
    val out = mutableListOf<String>()
    for (h in listOf(endpoint) + linkHosts) {
        val t = h.trim()
        val host = splitUrl(h)?.host ?: (if (bare.matches(t)) t.lowercase() else null)
        if (host != null && host !in out) out += host
    }
    return out
}

/** The strait_link id inside a Play Install Referrer string, or null. */
internal fun parseStraitLink(referrer: String?): String? = referrerParam(referrer, "strait_link")

/**
 * The tap id (strait_click) inside a Play Install Referrer string, or null.
 * Joins the install to the exact tap that sent the user to the store.
 */
internal fun parseStraitClick(referrer: String?): String? =
    referrerParam(referrer, "strait_click")?.takeIf { CLICK_ID.matches(it) }

private fun referrerParam(referrer: String?, key: String): String? {
    if (referrer.isNullOrEmpty()) return null
    for (pair in referrer.split('&')) {
        val i = pair.indexOf('=')
        if (i < 0 || pair.substring(0, i) != key) continue
        return decode(pair.substring(i + 1)).ifEmpty { null }
    }
    return null
}

/** A tap id as Strait issues it (uuid); anything else is ignored. */
private val CLICK_ID =
    Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", RegexOption.IGNORE_CASE)

/** Result of [takeClickId]: the URL without its tap id, and the tap id (or null). */
internal data class TakenClickId(val url: String, val clickId: String?)

/**
 * Remove every `strait_click` parameter from a URL's query, keeping the rest
 * of the URL byte-for-byte (fragment included). Returns the cleaned URL and
 * the tap id (null when absent or malformed). The app never sees the tap id.
 */
internal fun takeClickId(raw: String): TakenClickId {
    val s = raw.trim()
    val hash = s.indexOf('#')
    val beforeHash = if (hash < 0) s else s.substring(0, hash)
    val frag = if (hash < 0) "" else s.substring(hash)
    val q = beforeHash.indexOf('?')
    if (q < 0) return TakenClickId(s, null)
    var clickId: String? = null
    val kept = beforeHash.substring(q + 1).split('&').filter { pair ->
        val i = pair.indexOf('=')
        if (decode(if (i < 0) pair else pair.substring(0, i)) != "strait_click") return@filter true
        val v = decode(if (i < 0) "" else pair.substring(i + 1))
        if (CLICK_ID.matches(v)) clickId = v.lowercase()
        false
    }
    val query = kept.joinToString("&")
    return TakenClickId(beforeHash.substring(0, q) + (if (query.isNotEmpty()) "?$query" else "") + frag, clickId)
}

/** How the app received a link (`route` on [LinkEvent]). */
object LinkRoute {
    const val APP_LINK = "app_link"
    const val CUSTOM_SCHEME = "custom_scheme"
    const val INSTALL_REFERRER = "install_referrer"
    const val FINGERPRINT = "fingerprint"
}

/**
 * Result of [classifyUrl]. When [needsResolve] is true the URL is a Strait
 * short link and [url]/[path]/[params]/[clickId] are null; ask /v1/resolve.
 */
internal data class ClassifiedUrl(
    val route: String,
    val needsResolve: Boolean,
    val url: String? = null,
    val path: String? = null,
    val params: Map<String, String>? = null,
    /** Tap id from a Strait hand-off (removed from url/params), else null. */
    val clickId: String? = null,
)

/**
 * The deep link inside an old Firebase Dynamic Links long link (contract B22):
 * `https://<x>.page.link/?link=<url>&apn=...` -> `<url>`. Only on a `*.page.link`
 * host, only at the root path, only when `link` is an absolute http(s) URL with
 * a host. Anything else -> null (a page.link short link is resolved by the engine).
 */
internal fun pageLinkLongLink(p: SplitUrl): String? {
    if (!p.host.endsWith(".page.link") || (p.path != "/" && p.path != "")) return null
    val link = p.params["link"]
    if (link.isNullOrEmpty()) return null
    val inner = splitUrl(link) ?: return null
    if ((inner.scheme != "https" && inner.scheme != "http") || inner.host.isEmpty()) return null
    return link.trim()
}

/**
 * What a URL handed to the app means (B4):
 * - https on a Strait link host -> a short link; ask /v1/resolve for the destination.
 *   Except an FDL long link on a `*.page.link` link host (B22): its `link=` value
 *   IS the destination, read on the device with no network call.
 * - other https (a verified link on the customer's own site) -> it IS the destination.
 * - yourapp://host/path (browser hand-off) -> destination https://host/path.
 * A `strait_click` tap id is removed from the destination and returned apart.
 * Returns null for anything that isn't a URL.
 */
internal fun classifyUrl(raw: String, linkHosts: List<String>): ClassifiedUrl? {
    val p0 = splitUrl(raw) ?: return null
    val isWeb = p0.scheme == "https" || p0.scheme == "http"
    if (isWeb && linkHosts.any { it.lowercase() == p0.host }) {
        val long = pageLinkLongLink(p0)
        if (long != null) {
            val inner = classifyUrl(long, emptyList())
            if (inner != null && !inner.needsResolve) return inner.copy(route = LinkRoute.APP_LINK)
        }
        return ClassifiedUrl(LinkRoute.APP_LINK, needsResolve = true)
    }
    val (clean, clickId) = takeClickId(raw)
    val p = splitUrl(clean)!!
    val url = if (isWeb) clean else SCHEME_RE.replaceFirst(clean, "https://")
    return ClassifiedUrl(
        route = if (isWeb) LinkRoute.APP_LINK else LinkRoute.CUSTOM_SCHEME,
        needsResolve = false,
        url = url,
        path = p.path,
        params = p.params,
        clickId = clickId,
    )
}

/** Open reports waiting to be sent are kept at most this long... */
internal const val OPEN_QUEUE_MAX_AGE_MS: Long = 7L * 24 * 60 * 60 * 1000

/** ...and at most this many (oldest dropped first). */
internal const val OPEN_QUEUE_MAX: Int = 100

/**
 * Prune a pending-report queue: drop reports older than [OPEN_QUEUE_MAX_AGE_MS]
 * (by their `at`), then keep the newest [OPEN_QUEUE_MAX]. Order is kept.
 */
internal fun <T> pruneOpenQueue(queue: List<T>, now: Long, at: (T) -> Long): List<T> =
    queue.filter { now - at(it) <= OPEN_QUEUE_MAX_AGE_MS }.takeLast(OPEN_QUEUE_MAX)

/**
 * Conversion events carry the tap id of the most recent attributed link open
 * for this long (contract B15).
 */
internal const val ATTRIBUTION_WINDOW_MS: Long = 7L * 24 * 60 * 60 * 1000

/** Storage value for the remembered tap (key `strait.lastTap`): `{"clickId":...,"at":<epoch ms>}`. */
internal fun rememberTap(clickId: String, at: Long): String =
    org.json.JSONObject().put("clickId", clickId.lowercase()).put("at", at).toString()

/**
 * The `clickId` a conversion event sends (contract B15): a non-empty [explicit]
 * wins; otherwise the remembered tap ([stored], see [rememberTap]) when it is a
 * valid tap id opened at most [ATTRIBUTION_WINDOW_MS] before [now] (and not
 * after it). Anything unreadable means no tap.
 */
internal fun eventClickId(stored: String?, now: Long, explicit: String? = null): String? {
    if (!explicit.isNullOrEmpty()) return explicit
    if (stored.isNullOrEmpty()) return null
    val tap = try { org.json.JSONObject(stored) } catch (_: Exception) { return null }
    val clickId = tap.opt("clickId") as? String ?: return null
    if (!CLICK_ID.matches(clickId)) return null
    val at = (tap.opt("at") as? Number)?.toDouble()?.takeIf { it.isFinite() } ?: return null
    val age = now - at
    return if (age >= 0 && age <= ATTRIBUTION_WINDOW_MS) clickId.lowercase() else null
}

/**
 * True when the remembered tap ([stored], see [rememberTap]) is set but can no
 * longer be used: unreadable, malformed, or opened more than
 * [ATTRIBUTION_WINDOW_MS] before [now] (or after it). The SDK then deletes it
 * instead of keeping it on the device (contract B18).
 */
internal fun staleTap(stored: String?, now: Long): Boolean = !stored.isNullOrEmpty() && eventClickId(stored, now) == null

/**
 * The URL the SDK reports to the engine (`/v1/open`, `/v1/resolve`) or saves in
 * the open queue (contract B18): the query string and fragment are removed,
 * except the first `utm_source` pair, kept byte for byte, because the engine
 * reads it for channel attribution. The engine keeps nothing else from the
 * query: it stores host + path only. The query is what sits before any '#',
 * between the first and second '?'.
 */
internal fun reportUrl(url: String): String {
    val noFragment = url.substringBefore('#')
    val q = noFragment.indexOf('?')
    if (q < 0) return noFragment
    val base = noFragment.substring(0, q)
    for (pair in noFragment.substring(q + 1).substringBefore('?').split('&')) {
        if (pair.substringBefore('=') == "utm_source") return "$base?$pair"
    }
    return base
}

/**
 * The tap id to remember after an attributed open the engine answered
 * (contract B16): the reply's `clickId` when it is a valid tap id
 * (lower-cased); else [fallback] when valid (a tap id the SDK already knew,
 * e.g. the Play referrer's — so an older engine that returns none keeps B15);
 * else null, which forgets the remembered tap (the newer touch wins).
 */
internal fun replyClickId(reply: Any?, fallback: String? = null): String? {
    if (reply is String && CLICK_ID.matches(reply)) return reply.lowercase()
    if (fallback != null && CLICK_ID.matches(fallback)) return fallback.lowercase()
    return null
}

private val REFERRAL_CODE = Regex("^[A-Za-z0-9_-]{1,64}$")

/**
 * The referral code in a matched deferred reply (`/v1/referrer`, `/v1/match`),
 * or null (contract B21, proposal). Only a valid code (1-64 letters, digits,
 * - or _) counts, kept exactly as sent; anything else, or an engine that
 * sends no `referralCode`, gives null.
 */
internal fun replyReferralCode(reply: Any?): String? =
    if (reply is String && REFERRAL_CODE.matches(reply)) reply else null

/** The `match` of a matched deferred reply, as sent, when it has a string method and confidence; else null (engine ADR 0081). */
internal fun replyMatch(reply: Any?): MatchInfo? {
    val m = reply as? org.json.JSONObject ?: return null
    val method = m.opt("method") as? String ?: return null
    val confidence = m.opt("confidence") as? String ?: return null
    return MatchInfo(method, confidence)
}

/** Whether a failed report should be kept for retry: no answer (null), 429 or 5xx. */
internal fun shouldRetryReport(status: Int?): Boolean = status == null || status == 429 || status >= 500

/** C1 backoff: the first hold after a 429/5xx is 15-30 s; each one in a row doubles it, up to RETRY_MAX_MS. */
internal const val RETRY_BASE_MS = 30_000L
/** C1 backoff: never hold the queue longer than 1 hour (Retry-After included). */
internal const val RETRY_MAX_MS = 3_600_000L
private val RETRY_AFTER_SECONDS = Regex("[0-9]{1,9}")

/**
 * How long to hold the open/event queue after the [failures]-th 429/5xx answer in a row (contract B14, C1).
 * Exponential with equal jitter: step = min(RETRY_MAX_MS, RETRY_BASE_MS x 2^(failures-1)), wait
 * floor(step/2 + random x step/2), so phones that failed together spread out. A Retry-After in whole
 * seconds (1-9 ASCII digits, spaces trimmed; an HTTP date or anything else is ignored) is a floor. The
 * result never exceeds RETRY_MAX_MS. [random] is in [0, 1] (the platform's random number).
 */
internal fun retryDelayMs(failures: Int, retryAfter: String?, random: Double): Long {
    val n = failures.coerceIn(1, 17)
    val half = minOf(RETRY_MAX_MS, RETRY_BASE_MS shl (n - 1)) / 2.0
    val r = if (random >= 0.0) minOf(random, 1.0) else 0.0 // NaN counts as 0, like the reference
    val backoff = kotlin.math.floor(half + r * half).toLong()
    val s = retryAfter?.trim()?.takeIf { RETRY_AFTER_SECONDS.matches(it) }?.toLong()
    val server = if (s != null) minOf(s, RETRY_MAX_MS / 1000) * 1000 else 0L
    return minOf(RETRY_MAX_MS, maxOf(backoff, server))
}

/** A unique id for one link open (the engine de-duplicates retries by it). */
internal fun newOpenId(now: Long, random: () -> Double = Math::random): String {
    val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
    val r = StringBuilder(12)
    repeat(12) { r.append(alphabet[kotlin.math.floor(random() * 36).toInt()]) }
    return "o_${now.toString(36)}_$r"
}

/**
 * A unique id for one conversion event when the app gives none (contract B13a): the engine
 * stores an eventId once, so a retried send is never counted twice. Same shape as [newOpenId].
 */
internal fun newEventId(now: Long, random: () -> Double = Math::random): String = "e" + newOpenId(now, random).substring(1)

/** This SDK's version, sent as `sdkVersion` (td2-23). Equals `version` in build.gradle.kts (a test pins it). */
internal const val SDK_VERSION = "0.10.1"

/**
 * A version string as it goes on the wire (`appVersion`, `sdkVersion`): trimmed, cut to 64 characters,
 * and only if what's left is 1-64 printable ASCII characters (the engine's rule); anything else is null (not sent).
 */
internal fun wireVersion(v: String?): String? =
    v?.trim()?.take(64)?.takeIf { s -> s.isNotEmpty() && s.all { it in ' '..'~' } }

/** What start() does with the once-per-install flag (contract B6a, shared-spec ADR 0003). */
data class FirstLaunchState(
    /** Run the once-per-install deferred check / count this launch as the install's first. */
    val firstLaunch: Boolean,
    /** Set `strait.deferredChecked` to "" first: the storage came back from another install. */
    val clearFlag: Boolean,
    /** Then write this to `strait.installTime` (null = leave it). */
    val storeInstallTime: String?,
)

/**
 * B6a: storage restored from a backup of another install (Android Auto Backup) brings back
 * `strait.deferredChecked`. The app's install time, stored next to it, tells: a different
 * stored time = a new install, so the check runs again. No stored time (an app updated from an
 * older SDK) = adopt it, never a new install. Unknown install time (null, < 1, not finite) = B6 alone.
 */
internal fun firstLaunchState(flag: String?, storedInstallTime: String?, installTime: Double?): FirstLaunchState {
    val t = installTime?.takeIf { it.isFinite() && it >= 1 }?.let { kotlin.math.floor(it).toLong().toString() }
    val stored = storedInstallTime?.takeIf { it.isNotEmpty() }
    if (flag != "1") return FirstLaunchState(true, false, if (t != null && t != stored) t else null)
    if (t == null || t == stored) return FirstLaunchState(false, false, null)
    if (stored == null) return FirstLaunchState(false, false, t)
    return FirstLaunchState(true, true, t)
}

/** A link arriving this soon after the app came back to the front came "from background". */
internal const val RESUME_WINDOW_MS: Long = 2000

/** Pauses shorter than this are Android delivering the link, not the user leaving. */
internal const val TRANSIENT_PAUSE_MS: Long = 1000

/** App lifecycle state, as reported by ProcessLifecycleOwner / Activity callbacks. */
enum class AppLifecycleState(val wire: String) {
    ACTIVE("active"), BACKGROUND("background"), INACTIVE("inactive");

    companion object {
        fun fromWire(s: String): AppLifecycleState? = entries.firstOrNull { it.wire == s }
    }
}

/** What the app was doing when a link arrived (`appState` on [LinkEvent]). */
object AppStateAtLink {
    const val CLOSED = "closed"
    const val BACKGROUND = "background"
    const val FOREGROUND = "foreground"
}

/**
 * Tracks app lifecycle to label a link delivered while the app is running (B5).
 * Android wraps link delivery in a brief pause/resume, and the link can arrive
 * before or after the resume: a pause under [TRANSIENT_PAUSE_MS] is that
 * delivery (app was on screen); a longer one means the user had left.
 */
internal class AppStateTracker {
    private var state = AppLifecycleState.ACTIVE
    private var backgroundAt = Double.NEGATIVE_INFINITY
    private var resumeAt = Double.NEGATIVE_INFINITY
    private var backgroundFor = 0.0

    @Synchronized
    fun onState(s: AppLifecycleState, now: Long) {
        if (s != AppLifecycleState.ACTIVE && state == AppLifecycleState.ACTIVE) backgroundAt = now.toDouble()
        if (s == AppLifecycleState.ACTIVE && state != AppLifecycleState.ACTIVE) {
            resumeAt = now.toDouble()
            backgroundFor = now - backgroundAt
        }
        state = s
    }

    /** Label ("background" / "foreground") for a link delivered (while running) at [now]. */
    @Synchronized
    fun classify(now: Long): String {
        var away: Double? = null
        if (state != AppLifecycleState.ACTIVE) away = now - backgroundAt
        else if (now - resumeAt <= RESUME_WINDOW_MS) away = backgroundFor
        return if (away != null && away >= TRANSIENT_PAUSE_MS) AppStateAtLink.BACKGROUND else AppStateAtLink.FOREGROUND
    }
}
