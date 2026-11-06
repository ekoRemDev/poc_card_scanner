package com.example.card_scanner

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Renders a synthetic card-number line with a 5x7 bitmap font (scaled, with
 * noise) and simulates ML Kit's observed failures on embossed digits:
 * dropped characters and digits read as ')' / 'B'.
 */
internal class EmbossedNumberReaderTest {

    private val font = mapOf(
        '0' to listOf("01110", "10001", "10011", "10101", "11001", "10001", "01110"),
        '1' to listOf("00100", "01100", "00100", "00100", "00100", "00100", "01110"),
        '2' to listOf("01110", "10001", "00001", "00010", "00100", "01000", "11111"),
        '3' to listOf("11110", "00001", "00001", "01110", "00001", "00001", "11110"),
        '4' to listOf("00010", "00110", "01010", "10010", "11111", "00010", "00010"),
        '5' to listOf("11111", "10000", "11110", "00001", "00001", "10001", "01110"),
        '6' to listOf("00110", "01000", "10000", "11110", "10001", "10001", "01110"),
        '7' to listOf("11111", "00001", "00010", "00100", "01000", "01000", "01000"),
        '8' to listOf("01110", "10001", "10001", "01110", "10001", "10001", "01110"),
        '9' to listOf("01110", "10001", "10001", "01111", "00001", "00010", "01100"),
    )

    private val scale = 6
    private val glyphW = 5 * scale
    private val glyphH = 7 * scale
    private val pitch = 36
    private val left = 20
    private val top = 30

    private fun render(number: String, seed: Int): LumaEnhancer.GrayImage {
        val w = left * 2 + 19 * pitch
        val h = top * 2 + glyphH
        val rnd = Random(seed)
        // Low contrast like embossing: background 120, strokes 150, noise ±6.
        val px = IntArray(w * h) { 120 + rnd.nextInt(-6, 7) }
        number.forEachIndexed { k, ch ->
            val x0 = left + EmbossedNumberReader.PATTERN[k] * pitch + (pitch - glyphW) / 2
            font.getValue(ch).forEachIndexed { gy, row ->
                row.forEachIndexed { gx, bit ->
                    if (bit == '1') {
                        for (dy in 0 until scale) for (dx in 0 until scale) {
                            val i = (top + gy * scale + dy) * w + x0 + gx * scale + dx
                            px[i] = (px[i] + 30).coerceAtMost(255)
                        }
                    }
                }
            }
        }
        return LumaEnhancer.GrayImage(w, h, px)
    }

    private fun symbol(k: Int, ch: Char, conf: Float = 0.95f): EmbossedNumberReader.Symbol {
        val x0 = left + EmbossedNumberReader.PATTERN[k] * pitch + (pitch - glyphW) / 2
        return EmbossedNumberReader.Symbol(ch, x0, top, x0 + glyphW, top + glyphH, conf)
    }

    @Test
    fun recoversDigitsOcrDroppedOrMisread() {
        val number = "4539578763621486" // Luhn-valid Visa
        // OCR sees each digit class confidently once (templates); the other
        // positions are misread as ')' or dropped entirely.
        val seen = mutableSetOf<Char>()
        val ocr = number.mapIndexedNotNull { k, ch ->
            when {
                seen.add(ch) -> symbol(k, ch)
                k % 3 == 0 -> null // dropped
                else -> symbol(k, ')', 0.3f)
            }
        }
        val reader = EmbossedNumberReader()
        var result: EmbossedNumberReader.Result? = null
        for (frame in 0 until 3) result = reader.update(listOf(ocr), render(number, seed = frame))
        assertEquals(number, result!!.number, result.diagnostics)
        assertTrue(!result.diagnostics.contains(number), "diagnostics must not contain digits")
    }

    @Test
    fun luhnFillsTheOnlyUnreadableDigit() {
        val number = "4539578763621486"
        // Position 15 ('6') is never read and '6' is used for no template
        // except position 10... drop both '6's so there's no '6' template;
        // then position 10 and 15 are unknown → two unknowns, no result.
        // Keep position 10 readable so exactly one digit (15) is unknown.
        val ocr = number.mapIndexedNotNull { k, ch -> if (k == 15) null else symbol(k, ch) }
            .filterNot { it.ch == '6' && it.left > left + 15 * pitch }
        val reader = EmbossedNumberReader()
        var result: EmbossedNumberReader.Result? = null
        for (frame in 0 until 2) result = reader.update(listOf(ocr), render(number, seed = 10 + frame))
        assertEquals(number, result!!.number, result.diagnostics)
    }

    @Test
    fun noNumberFromUnrelatedText() {
        val reader = EmbossedNumberReader()
        val image = render("4539578763621486", seed = 1)
        val text = "VALID".mapIndexed { i, ch -> symbol(i, ch) }
        assertNull(reader.update(listOf(text), image).number)
    }

    @Test
    fun gridIsFoundWhenFirstGroupIsMissing() {
        // OCR misses the whole first group; the grid must still align.
        // First-group digits (4,3,6,3) all occur later, so shapes exist (Luhn-valid).
        val number = "4363578763621487"
        val ocr = number.mapIndexedNotNull { k, ch -> if (k < 4) null else symbol(k, ch) }
        val reader = EmbossedNumberReader()
        var result: EmbossedNumberReader.Result? = null
        // Shape-only digits need ≥3 consistent votes, i.e. ≥3 frames.
        for (frame in 0 until 3) result = reader.update(listOf(ocr), render(number, seed = 20 + frame))
        assertEquals(number, result!!.number, result.diagnostics)
    }

    @Test
    fun neverReturnsAWrongNumberWhenADigitHasNoShape() {
        // '9' occurs only in the unread first group: no '9' shape exists, so
        // that cell gets the closest *wrong* digit. Luhn must stop it.
        val number = "4539578763621486"
        val ocr = number.mapIndexedNotNull { k, ch -> if (k < 4) null else symbol(k, ch) }
        val reader = EmbossedNumberReader()
        repeat(6) { frame ->
            val result = reader.update(listOf(ocr), render(number, seed = 30 + frame))
            assertTrue(result.number == null || result.number == number, "wrong number returned")
        }
    }

    @Test
    fun calibratedShapesReadACardWhereOcrGetsNoDigitRight() {
        // Calibration card A: OCR reads most digits correctly (alignment proof).
        val cardA = "4539578763621486"
        val calibrationOcr = cardA.mapIndexed { k, ch -> symbol(k, if (k % 4 == 1) ')' else ch) }
        val calibrator = EmbossedNumberReader()
        repeat(4) { frame ->
            calibrator.calibrate(listOf(calibrationOcr), render(cardA, seed = 40 + frame), cardA)
        }
        val json = EmbossedNumberReader.templatesToJson(calibrator.calibratedTemplates(), calibrator.calibrationFrames)
        assertTrue(!json.contains(cardA), "export must not contain the card number")
        val builtIn = EmbossedNumberReader.templatesFromJson(json)

        // Card B: a different, Luhn-valid Mastercard. Card A has no '0', so B
        // only uses digits that were calibrated (plus a Luhn check digit).
        val body = "512345678912345"
        val cardB = (0..9).map { body + it }.first { CardParser.isPlausibleCardNumber(it) }
        // OCR finds the boxes but reads *no* digit correctly (all ')' or 'B').
        val garbled = cardB.mapIndexed { k, _ -> symbol(k, if (k % 2 == 0) ')' else 'J', 0.2f) }
        val reader = EmbossedNumberReader(builtIn)
        var result: EmbossedNumberReader.Result? = null
        repeat(3) { frame -> result = reader.update(listOf(garbled), render(cardB, seed = 60 + frame)) }
        assertEquals(cardB, result!!.number, result!!.diagnostics)
    }

    @Test
    fun calibrationRejectsMisalignedFrames() {
        val card = "4539578763621486"
        // OCR "reads" digits that don't match the known number → reject.
        val wrong = card.mapIndexed { k, _ -> symbol(k, '0') }
        val calibrator = EmbossedNumberReader()
        val diag = calibrator.calibrate(listOf(wrong), render(card, seed = 1), card)
        assertTrue(diag.startsWith("calibrate rejected"), diag)
        assertEquals(0, calibrator.calibrationFrames)
    }
}
