package dev.strait.sdk

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/*
 * doctor(): a setup check the developer calls in debug builds (contract B23, shared-spec ADR 0002).
 * Ids, severities and wording are the same in every Strait SDK.
 */

/** One setup check. [severity] is "ok", "warn" or "error"; [fix] is empty for "ok". */
data class DoctorFinding(val id: String, val severity: String, val title: String, val fix: String)

/** The findings in order; [ok] = no "error" finding. */
data class DoctorReport(val ok: Boolean, val findings: List<DoctorFinding>) {
    companion object {
        fun of(findings: List<DoctorFinding>) = DoctorReport(findings.none { it.severity == "error" }, findings)
    }
}

/** The fetched verification file: [status] null = the request failed (network). */
data class DoctorFile(val status: Int?, val body: String?)

/** The endpoint's host when it is an https URL with a host, else null. */
internal fun doctorHost(endpoint: String): String? = try {
    URI(endpoint).takeIf { it.scheme == "https" }?.host?.takeIf { it.isNotEmpty() }
} catch (_: Exception) {
    null
}

/** Path of the file `doctor()` fetches from the link host for [platform] ("android" or "ios"). */
internal fun doctorFilePath(platform: String) =
    if (platform == "ios") "/.well-known/apple-app-site-association" else "/.well-known/assetlinks.json"

/** Upper-case, colon-separated SHA-256 (accepts any case, with or without colons). */
internal fun normalizeFingerprint(s: String): String =
    s.filter { it.isLetterOrDigit() }.uppercase().chunked(2).joinToString(":")

/**
 * The B23 findings, pure. [appId] = Android package / iOS bundle ID, null when unknown.
 * [certSha256] = this build's signing certificate(s), any format (Android). [file] = what was fetched
 * from `{endpoint}` + [doctorFilePath], null when the endpoint was invalid (nothing fetched).
 */
fun doctorFindings(
    publishableKey: String,
    endpoint: String,
    platform: String,
    appId: String?,
    certSha256: List<String>,
    file: DoctorFile?,
): List<DoctorFinding> {
    val out = ArrayList<DoctorFinding>()
    fun ok(id: String, title: String) = out.add(DoctorFinding(id, "ok", title, ""))
    fun err(id: String, title: String, fix: String) = out.add(DoctorFinding(id, "error", title, fix))

    when {
        publishableKey.startsWith("st_pub_") -> ok("key.ok", "Publishable key is set")
        publishableKey.startsWith("st_live_") || publishableKey.startsWith("st_test_") -> err(
            "key.secret", "This is a secret key, not the publishable key",
            "Use the publishable key (st_pub_…) from Dashboard → Get started, and revoke this secret key in Dashboard → Settings → Secret keys: it has been inside your app.",
        )
        else -> err("key.invalid", "Not a publishable key", "Copy the publishable key (st_pub_…) from Dashboard → Get started.")
    }

    val host = doctorHost(endpoint)
    if (host == null) {
        err(
            "endpoint.invalid", "The endpoint isn't an https link domain",
            "Set endpoint to your link domain from Dashboard → Get started, e.g. https://<handle>.strait.link.",
        )
        return out
    }
    if (file?.status == null) {
        err("host.unreachable", "Can't reach $host", "Check that endpoint is your link domain (Dashboard → Get started) and that this device is online.")
        return out
    }
    val ios = platform == "ios"
    // listed app id (Android package / iOS appID) -> its fingerprints (Android only)
    val apps = (if (file.status == 200) file.body?.let { if (ios) iosApps(it) else androidApps(it) } else null) ?: run {
        err(
            "host.file_missing", "$host doesn't serve ${doctorFilePath(platform).substringAfterLast('/')}",
            "Use your Strait link domain as endpoint (Dashboard → Get started); Strait serves this file there.",
        )
        return out
    }
    if (apps.isEmpty()) {
        err("app.none", "No ${if (ios) "iOS" else "Android"} app is set up for $host", "Add your app in Dashboard → Apps & stores.")
        return out
    }
    val listed = apps.keys.joinToString(", ")
    val appId = appId?.trim()
    if (appId.isNullOrEmpty()) {
        out.add(DoctorFinding("app.unknown", "warn", "$host lists $listed", "Make sure your app is one of these."))
        return out
    }
    val match = apps.keys.firstOrNull { if (ios) it.endsWith(".$appId") else it == appId }
    if (match == null) {
        err("app.missing", "$host doesn't list $appId", "Add $appId in Dashboard → Apps & stores (listed now: $listed).")
        return out
    }
    ok("app.listed", "$host lists $match")
    val mine = if (ios) emptyList() else certSha256.map(::normalizeFingerprint).filter { it.isNotEmpty() }
    if (mine.isNotEmpty()) {
        if (mine.any { it in apps.getValue(match) }) {
            ok("cert.listed", "This build's signing certificate is listed")
        } else {
            err(
                "cert.missing", "This build's signing certificate isn't listed for $appId",
                "Add ${mine.joinToString(", ")} in Dashboard → Apps & stores. Debug, release and Play App Signing keys each have their own fingerprint.",
            )
        }
    }
    return out
}

/** assetlinks.json: package -> normalised fingerprints, in file order; null if it isn't a JSON array. */
private fun androidApps(body: String): Map<String, Set<String>>? {
    val arr = try { JSONArray(body) } catch (_: Exception) { return null }
    val apps = LinkedHashMap<String, MutableSet<String>>()
    for (i in 0 until arr.length()) {
        val t = arr.optJSONObject(i)?.optJSONObject("target") ?: continue
        val pkg = t.optString("package_name", "")
        if (t.optString("namespace") != "android_app" || pkg.isEmpty()) continue
        val certs = apps.getOrPut(pkg) { LinkedHashSet() }
        val fps = t.optJSONArray("sha256_cert_fingerprints") ?: continue
        for (j in 0 until fps.length()) certs.add(normalizeFingerprint(fps.optString(j, "")))
    }
    return apps
}

/** apple-app-site-association: appIDs (TEAM.bundle) in file order (`appIDs`, then the legacy `appID`), once each; null without `applinks.details`. */
private fun iosApps(body: String): Map<String, Set<String>>? {
    val details = try { JSONObject(body).optJSONObject("applinks")?.optJSONArray("details") } catch (_: Exception) { null } ?: return null
    val apps = LinkedHashMap<String, Set<String>>()
    for (i in 0 until details.length()) {
        val d = details.optJSONObject(i) ?: continue
        d.optJSONArray("appIDs")?.let { ids ->
            for (j in 0 until ids.length()) ids.optString(j, "").takeIf { it.isNotEmpty() }?.let { apps.putIfAbsent(it, emptySet()) }
        }
        d.optString("appID", "").takeIf { it.isNotEmpty() }?.let { apps.putIfAbsent(it, emptySet()) }
    }
    return apps
}
