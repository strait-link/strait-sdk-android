package dev.strait.sdk

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * F1 (privacy): the default transport names only the SDK, never Android's default
 * `Dalvik/2.1.0 (Linux; U; Android 14; <model> Build/...)`, which carries the phone model into
 * Cloudflare/Render logs (also on the opt-out /v1/lookup).
 */
class UserAgentTest {
    @Test
    fun defaultTransportSendsANeutralSdkUserAgent() {
        var ua: String? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { x ->
                ua = x.requestHeaders.getFirst("User-Agent")
                x.sendResponseHeaders(200, 2)
                x.responseBody.use { it.write("{}".toByteArray()) }
            }
            start()
        }
        try {
            UrlConnectionTransport().request("http://127.0.0.1:${server.address.port}/v1/lookup", "POST", "{}")
        } finally {
            server.stop(0)
        }
        assertEquals("strait-android/$SDK_VERSION", ua)
    }

    @Test
    fun sdkVersionMatchesTheBuildVersion() {
        val gradle = File("build.gradle.kts").readText()
        assertEquals(Regex("""(?m)^version = "([^"]+)"""").find(gradle)!!.groupValues[1], SDK_VERSION)
    }
}
