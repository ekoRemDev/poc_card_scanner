package com.example.card_scanner

import org.json.JSONObject
import java.io.File
import java.util.Calendar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Runs the shared `test_vectors/card_parser_vectors.json` (also used by Dart and Swift). */
internal class CardParserVectorsTest {
    private fun vectorsFile(): File {
        // Gradle runs tests from the plugin's android/ dir or from the example app.
        val candidates = listOf("../test_vectors", "../../test_vectors", "../../../test_vectors")
        return candidates.map { File(it, "card_parser_vectors.json") }.first { it.exists() }
    }

    @Test
    fun sharedVectors() {
        val json = JSONObject(vectorsFile().readText())
        val (y, m, d) = json.getString("referenceDate").split("-").map(String::toInt)
        val ref = Calendar.getInstance().apply { set(y, m - 1, d) }
        val parser = CardParser { ref }

        val vectors = json.getJSONArray("vectors")
        val failures = mutableListOf<String>()
        for (i in 0 until vectors.length()) {
            val v = vectors.getJSONObject(i)
            val lines = v.getJSONArray("lines").let { a -> List(a.length()) { a.getString(it) } }
            val expected = v.getJSONObject("expected")
            val result = parser.parse(lines)
            val expiry = result.expiry?.let { "%02d/%d".format(it.month, it.year) }
            fun exp(key: String) = if (expected.isNull(key)) null else expected.getString(key)
            if (result.number != exp("number") || expiry != exp("expiry") ||
                result.holderName != exp("holderName")
            ) {
                failures += "${v.getString("description")}: got $result / $expiry"
            }
        }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun aggregatorLocksAfterAgreeingFrames() {
        var now = 0L
        val agg = ScanAggregator(clock = { now })
        val good = FrameParseResult("4111111111111111", CardExpiry(3, 2029), "AYSE YILMAZ")
        assertNull(agg.add(good))
        assertNull(agg.add(FrameParseResult(number = "4111111111111129")))
        assertNull(agg.add(good))
        val result = agg.add(good)!!
        assertEquals("4111111111111111", result.number)
        assertEquals(CardExpiry(3, 2029), result.expiry)
        assertEquals("AYSE YILMAZ", result.holderName)
    }

    @Test
    fun aggregatorFinishesAfterGraceWithoutName() {
        var now = 0L
        val agg = ScanAggregator(clock = { now })
        val frame = FrameParseResult(number = "4111111111111111")
        repeat(3) { assertNull(agg.add(frame)) }
        now += 2000
        assertEquals("4111111111111111", agg.add(frame)?.number)
    }
}
