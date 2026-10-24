package dev.strait.sdk

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * doctor-vectors.json (contract B23, ADR 0002): the same inputs give the same ids, severities, titles
 * and fixes as the React Native reference (0.9.3) and the Flutter SDK, which carry the same file.
 */
class DoctorVectorsTest {
    @Test
    fun everyVector() {
        val text = this::class.java.getResourceAsStream("/doctor-vectors.json")!!.bufferedReader().readText()
        val vectors = JSONObject(text).getJSONArray("vectors")
        assertTrue(vectors.length() >= 40)
        val wrong = ArrayList<String>()
        for (i in 0 until vectors.length()) {
            val v = vectors.getJSONObject(i)
            val input = v.getJSONObject("input")
            val certs = input.getJSONArray("certSha256").let { a -> (0 until a.length()).map { a.getString(it) } }
            val file = if (input.isNull("file")) null else input.getJSONObject("file").let {
                DoctorFile(if (it.isNull("status")) null else it.getInt("status"), if (it.isNull("body")) null else it.getString("body"))
            }
            val report = DoctorReport.of(
                doctorFindings(
                    input.getString("publishableKey"), input.getString("endpoint"), input.getString("platform"),
                    if (input.isNull("appId")) null else input.getString("appId"), certs, file,
                ),
            )
            val want = v.getJSONArray("findings").let { a ->
                (0 until a.length()).map { a.getJSONObject(it).let { f -> DoctorFinding(f.getString("id"), f.getString("severity"), f.getString("title"), f.getString("fix")) } }
            }
            if (want != report.findings || v.getBoolean("ok") != report.ok) {
                wrong.add("${v.getString("name")}:\n  want ${v.getBoolean("ok")} $want\n  got  ${report.ok} ${report.findings}")
            }
        }
        assertEquals("", wrong.joinToString("\n"))
    }
}
