package com.example.card_scanner

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

internal class LumaEnhancerTest {
    // Buffer, 2 wide x 3 high, rowStride 3 (one padding byte per row):
    //   1 2
    //   3 4
    //   5 6
    private val buffer = byteArrayOf(1, 2, 0, 3, 4, 0, 5, 6, 0)

    private fun upright(rotation: Int, uprightW: Int, uprightH: Int) =
        LumaEnhancer().cropUpright(buffer, 2, 3, 3, rotation, 0, 0, uprightW, uprightH).pixels.copyOf(6)

    @Test
    fun rotation0() = assertContentEquals(intArrayOf(1, 2, 3, 4, 5, 6), upright(0, 2, 3))

    @Test
    fun rotation90Clockwise() {
        // 5 3 1
        // 6 4 2
        assertContentEquals(intArrayOf(5, 3, 1, 6, 4, 2), upright(90, 3, 2))
    }

    @Test
    fun rotation180() = assertContentEquals(intArrayOf(6, 5, 4, 3, 2, 1), upright(180, 2, 3))

    @Test
    fun rotation270() {
        // 2 4 6
        // 1 3 5
        assertContentEquals(intArrayOf(2, 4, 6, 1, 3, 5), upright(270, 3, 2))
    }

    @Test
    fun cropOffsetsAreInUprightCoordinates() {
        // Upright (90°) image is 3x2: [5 3 1 / 6 4 2]; crop x 1..3, y 1..2 → [4 2]
        val crop = LumaEnhancer().cropUpright(buffer, 2, 3, 3, 90, 1, 1, 3, 2)
        assertContentEquals(intArrayOf(4, 2), crop.pixels.copyOf(2))
    }

    @Test
    fun localNormalizeTurnsFaintEdgeIntoStrongContrast() {
        // 40x20 image: left half 100, right half 106 — a faint embossing-like edge.
        val w = 40
        val h = 20
        val pixels = IntArray(w * h) { i -> if (i % w < w / 2) 100 else 106 }
        val enhancer = LumaEnhancer()
        // Run a larger image first so reused buffers hold stale data.
        enhancer.localNormalize(LumaEnhancer.GrayImage(60, 30, IntArray(1800) { 255 }), 4, false)
        val out = enhancer.localNormalize(LumaEnhancer.GrayImage(w, h, pixels), radius = 4, invert = false).copyOf(w * h)
        val row = 10 * w
        val edge = (w / 2 - 6 until w / 2 + 6).map { out[row + it] }
        val flat = out[row + 2]
        // A 6-level step becomes a ~6x larger swing (old tuning gave only ~23).
        assertTrue(edge.max() - edge.min() >= 35, "edge contrast amplified: $edge")
        assertTrue(flat in 120..136, "flat area stays mid-gray: $flat")

        val inverted = enhancer.localNormalize(LumaEnhancer.GrayImage(w, h, pixels), 4, invert = true)
        assertTrue(inverted[10 * w + w / 2] < inverted[10 * w + w / 2 - 1], "inverted flips polarity")
    }

    @Test
    fun uprightRectToBufferMatchesCropUpright() {
        // Buffer 5x3 where each byte encodes its own (bx, by).
        val w = 5
        val h = 3
        val buffer = ByteArray(w * h) { (it).toByte() } // value = by*w + bx
        for (rotation in listOf(0, 90, 180, 270)) {
            val uw = if (rotation % 180 == 0) w else h
            val uh = if (rotation % 180 == 0) h else w
            // A rect that's not symmetric, so mix-ups show.
            val l = 1; val t = 0; val r = uw; val b = uh - 1
            val crop = LumaEnhancer().cropUpright(buffer, w, h, w, rotation, l, t, r, b)
            val xs = (0 until crop.width * crop.height).map { crop.pixels[it] % w }
            val ys = (0 until crop.width * crop.height).map { crop.pixels[it] / w }
            val rect = LumaEnhancer.uprightRectToBuffer(l, t, r, b, w, h, rotation)
            assertContentEquals(
                intArrayOf(xs.min(), ys.min(), xs.max() + 1, ys.max() + 1), rect,
                "rotation $rotation",
            )
        }
    }

    @Test
    fun lumaFromArgb() {
        val out = IntArray(3)
        LumaEnhancer.lumaFromArgb(intArrayOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF808080.toInt()), 3, out)
        assertContentEquals(intArrayOf(255, 0, 128), out)
    }
}
