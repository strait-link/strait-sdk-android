package dev.strait.sdk

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Contract B23 (shared-spec ADR 0002): every row of the findings table. */
class DoctorTest {
    private val pub = "st_pub_live_placeholder"
    private val ep = "https://acme.strait.link"
    private val fp = "FA:C6:17:45:DC:09:03:78:6F:B9:ED:E6:2A:96:2B:39:9F:73:48:F0:BB:6F:89:9B:83:32:66:75:91:03:3B:9C"
    private val fp2 = "6B:A3:E4:40:DF:12:16:87:B1:C6:98:7F:E1:9A:4E:28:CF:96:A6:61:38:40:28:00:03:90:56:EA:42:6F:7C:18"
    private fun entry(pkg: String, vararg certs: String) =
        """{"relation":["delegate_permission/common.handle_all_urls"],"target":{"namespace":"android_app","package_name":"$pkg","sha256_cert_fingerprints":[${certs.joinToString(",") { "\"$it\"" }}]}}"""
    private val file = DoctorFile(200, "[${entry("com.acme.app", fp)},${entry("com.acme.beta", fp2)}]")

    private fun run(
        key: String = pub, endpoint: String = ep, appId: String? = "com.acme.app",
        certs: List<String> = emptyList(), f: DoctorFile? = file, platform: String = "android",
    ) = doctorFindings(key, endpoint, platform, appId, certs, f)

    private fun ids(fs: List<DoctorFinding>) = fs.map { it.id }

    @Test fun keyOk() {
        val k = run().first()
        assertEquals(DoctorFinding("key.ok", "ok", "Publishable key is set", ""), k)
    }

    @Test fun secretKeys() {
        for (key in listOf("st_live_x", "st_test_x")) {
            assertEquals(
                DoctorFinding(
                    "key.secret", "error", "This is a secret key, not the publishable key",
                    "Use the publishable key (st_pub_…) from Dashboard → Get started, and revoke this secret key in Dashboard → Settings → Secret keys: it has been inside your app.",
                ),
                run(key = key).first(),
            )
        }
    }

    @Test fun invalidKey() {
        for (key in listOf("", "pk_live_x", "ST_PUB_x")) {
            assertEquals(
                DoctorFinding("key.invalid", "error", "Not a publishable key", "Copy the publishable key (st_pub_…) from Dashboard → Get started."),
                run(key = key).first(),
            )
        }
    }

    @Test fun invalidEndpointStopsBeforeHostChecks() {
        for (e in listOf("", "http://acme.strait.link", "acme.strait.link", "https://", "ftp://acme.strait.link")) {
            val fs = run(endpoint = e, f = null)
            assertEquals(listOf("key.ok", "endpoint.invalid"), ids(fs), e)
            assertEquals(
                DoctorFinding(
                    "endpoint.invalid", "error", "The endpoint isn't an https link domain",
                    "Set endpoint to your link domain from Dashboard → Get started, e.g. https://<handle>.strait.link.",
                ),
                fs[1],
            )
        }
    }

    @Test fun unreachable() {
        val fs = run(f = DoctorFile(null, null))
        assertEquals(listOf("key.ok", "host.unreachable"), ids(fs))
        assertEquals(
            DoctorFinding("host.unreachable", "error", "Can't reach acme.strait.link", "Check that endpoint is your link domain (Dashboard → Get started) and that this device is online."),
            fs[1],
        )
    }

    @Test fun fileMissing() {
        for (f in listOf(DoctorFile(404, "[]"), DoctorFile(200, "<html>"), DoctorFile(200, "{\"a\":1}"), DoctorFile(200, null))) {
            val fs = run(f = f)
            assertEquals(listOf("key.ok", "host.file_missing"), ids(fs), f.toString())
            assertEquals(
                DoctorFinding(
                    "host.file_missing", "error", "acme.strait.link doesn't serve /.well-known/assetlinks.json",
                    "Use your Strait link domain as endpoint (Dashboard → Get started); Strait serves this file there.",
                ),
                fs[1],
            )
        }
    }

    @Test fun noApp() {
        val fs = run(f = DoctorFile(200, "[]"))
        assertEquals(listOf("key.ok", "app.none"), ids(fs))
        assertEquals(DoctorFinding("app.none", "error", "No Android app is set up for acme.strait.link", "Add your app in Dashboard → Apps & stores."), fs[1])
    }

    @Test fun appMissingListsWhatIsThere() {
        val fs = run(appId = "com.other.app", certs = listOf(fp))
        assertEquals(listOf("key.ok", "app.missing"), ids(fs))
        assertEquals(
            DoctorFinding(
                "app.missing", "error", "acme.strait.link doesn't list com.other.app",
                "Add com.other.app in Dashboard → Apps & stores (listed now: com.acme.app, com.acme.beta).",
            ),
            fs[1],
        )
    }

    @Test fun appListedWithoutCertificate() {
        val fs = run()
        assertEquals(listOf("key.ok", "app.listed"), ids(fs))
        assertEquals(DoctorFinding("app.listed", "ok", "acme.strait.link lists com.acme.app", ""), fs[1])
    }

    @Test fun appUnknown() {
        for (a in listOf(null, "")) {
            val fs = run(appId = a, certs = listOf(fp))
            assertEquals(listOf("key.ok", "app.unknown"), ids(fs))
            assertEquals(DoctorFinding("app.unknown", "warn", "acme.strait.link lists com.acme.app, com.acme.beta", "Make sure your app is one of these."), fs[1])
        }
    }

    @Test fun certListedAfterNormalising() {
        val lowerNoColons = fp.replace(":", "").lowercase()
        for (c in listOf(fp, fp.lowercase(), lowerNoColons)) {
            val fs = run(certs = listOf(c))
            assertEquals(listOf("key.ok", "app.listed", "cert.listed"), ids(fs), c)
            assertEquals(DoctorFinding("cert.listed", "ok", "This build's signing certificate is listed", ""), fs[2])
        }
        // the file's fingerprints are normalised too
        val f = DoctorFile(200, "[${entry("com.acme.app", lowerNoColons)}]")
        assertEquals("cert.listed", run(certs = listOf(fp), f = f).last().id)
    }

    @Test fun certMissingNamesTheBuildsFingerprint() {
        // fp2 is listed, but for another package: still missing for com.acme.app
        val fs = run(certs = listOf(fp2.replace(":", "").lowercase()))
        assertEquals(listOf("key.ok", "app.listed", "cert.missing"), ids(fs))
        assertEquals(
            DoctorFinding(
                "cert.missing", "error", "This build's signing certificate isn't listed for com.acme.app",
                "Add $fp2 in Dashboard → Apps & stores. Debug, release and Play App Signing keys each have their own fingerprint.",
            ),
            fs[2],
        )
    }

    @Test fun anyOfSeveralSignersIsEnough() {
        assertEquals("cert.listed", run(certs = listOf(fp2, fp)).last().id)
    }

    @Test fun okMeansNoError() {
        assertTrue(DoctorReport.of(run(certs = listOf(fp))).ok)
        assertFalse(DoctorReport.of(run(key = "st_live_x")).ok)
        assertTrue(DoctorReport.of(run(appId = null)).ok) // a warn is not an error
    }

    @Test fun keyAndEndpointBothWrongReportBoth() {
        assertEquals(listOf("key.secret", "endpoint.invalid"), ids(run(key = "st_live_x", endpoint = "http://x", f = null)))
    }

    @Test fun iosFileMatchesBundleSuffix() {
        val aasa = DoctorFile(200, """{"applinks":{"apps":[],"details":[{"appID":"ABCDE12345.com.acme.app","paths":["*"]},{"appIDs":["ABCDE12345.com.acme.beta"]}]}}""")
        val ok = run(platform = "ios", f = aasa)
        assertEquals(DoctorFinding("app.listed", "ok", "acme.strait.link lists ABCDE12345.com.acme.app", ""), ok[1])
        val miss = run(platform = "ios", appId = "com.other", f = aasa)
        assertEquals("Add com.other in Dashboard → Apps & stores (listed now: ABCDE12345.com.acme.app, ABCDE12345.com.acme.beta).", miss[1].fix)
        assertEquals("acme.strait.link doesn't serve /.well-known/apple-app-site-association", run(platform = "ios", f = file)[1].title)
        assertEquals("No iOS app is set up for acme.strait.link", run(platform = "ios", f = DoctorFile(200, """{"applinks":{"details":[]}}"""))[1].title)
    }

    // --- StraitClient.doctor(): one GET through the configured transport, never throws

    private fun client(endpoint: String, transport: HttpTransport) = StraitClient(
        StraitConfig(publishableKey = pub, endpoint = endpoint, device = { DeviceFields(390, 3.0, "en-IN", "Asia/Kolkata") }, transport = transport, deviceClass = null),
    )

    @Test fun doctorFetchesAssetLinksOnce() {
        val calls = mutableListOf<String>()
        val r = client("$ep/", { url, method, body -> calls += "$method $url ${body == null}"; HttpResponse(200, file.body) })
            .doctor("com.acme.app", listOf(fp))
        assertEquals(listOf("GET $ep/.well-known/assetlinks.json true"), calls)
        assertEquals(listOf("key.ok", "app.listed", "cert.listed"), ids(r.findings))
        assertTrue(r.ok)
    }

    @Test fun doctorNetworkFailureIsAFindingNotAThrow() {
        val r = client(ep, { _, _, _ -> throw IOException("offline") }).doctor("com.acme.app")
        assertEquals(listOf("key.ok", "host.unreachable"), ids(r.findings))
        assertFalse(r.ok)
    }

    @Test fun doctorInvalidEndpointFetchesNothing() {
        var called = false
        val r = client("http://acme.strait.link", { _, _, _ -> called = true; HttpResponse(200, "[]") }).doctor()
        assertFalse(called)
        assertEquals(listOf("key.ok", "endpoint.invalid"), ids(r.findings))
    }
}
