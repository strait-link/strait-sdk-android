package dev.strait.sdk

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * doctor() against a real link host (network). Off by default; run with
 * STRAIT_DOCTOR_LIVE=https://<handle>.strait.link ./gradlew test --tests '*DoctorLiveTest' -i
 */
@EnabledIfEnvironmentVariable(named = "STRAIT_DOCTOR_LIVE", matches = "https://.+")
class DoctorLiveTest {
    private val client = StraitClient(
        StraitConfig(
            publishableKey = "st_pub_live_placeholder",
            endpoint = System.getenv("STRAIT_DOCTOR_LIVE"),
            device = { DeviceFields(390, 3.0, "en-IN", "Asia/Kolkata") },
        ),
    )
    private val cert = "FA:C6:17:45:DC:09:03:78:6F:B9:ED:E6:2A:96:2B:39:9F:73:48:F0:BB:6F:89:9B:83:32:66:75:91:03:3B:9C"

    private fun show(label: String, r: DoctorReport): List<String> {
        println("== $label: ok=${r.ok}")
        r.findings.forEach { println("  [${it.severity}] ${it.id}: ${it.title}" + if (it.fix.isEmpty()) "" else "\n      fix: ${it.fix}") }
        return r.findings.map { it.id }
    }

    @Test fun listedApp() =
        assertEquals(listOf("key.ok", "app.listed", "cert.listed"), show("doctor(\"com.straitlink.app\", [$cert])", client.doctor("com.straitlink.app", listOf(cert))))

    @Test fun unlistedApp() =
        assertEquals(listOf("key.ok", "app.missing"), show("doctor(\"com.example.notlisted\", [$cert])", client.doctor("com.example.notlisted", listOf(cert))))
}
