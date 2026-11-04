package com.example.card_scanner

import kotlin.math.sqrt

/**
 * Image pre-processing for raised (embossed) / engraved card digits.
 *
 * Raised digits are usually the same colour as the card, so they're only
 * visible through faint shading at their edges. ML Kit tends to miss them
 * on the raw frame. Local contrast normalisation — each pixel compared with
 * the mean and spread of its own neighbourhood — turns that shading into
 * strong strokes, independent of overall lighting or card colour.
 *
 * Buffers are reused between frames (one instance per scanner, used from a
 * single thread) to avoid ~20 MB of garbage per frame. Returned arrays are
 * only valid until the next call. Pure Kotlin so it can be unit tested.
 */
class LumaEnhancer {

    class GrayImage(val width: Int, val height: Int, val pixels: IntArray)

    private var crop = IntArray(0)
    private var blurred = IntArray(0)
    private var sum = IntArray(0)
    private var sumSq = LongArray(0)
    private var normalized = IntArray(0)
    private var argb = IntArray(0)

    /**
     * Copies the region [left, top, right, bottom) — given in *upright*
     * image coordinates — out of a camera luminance (Y) plane, rotating it
     * upright. [rotationDegrees] is CameraX's `imageInfo.rotationDegrees`
     * (clockwise rotation needed to make the buffer upright).
     */
    fun cropUpright(
        y: ByteArray,
        bufferWidth: Int,
        bufferHeight: Int,
        rowStride: Int,
        rotationDegrees: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ): GrayImage {
        val w = right - left
        val h = bottom - top
        if (crop.size < w * h) crop = IntArray(w * h)
        val out = crop
        var i = 0
        for (uy in top until bottom) {
            for (ux in left until right) {
                val bx: Int
                val by: Int
                when (rotationDegrees) {
                    90 -> { bx = uy; by = bufferHeight - 1 - ux }
                    180 -> { bx = bufferWidth - 1 - ux; by = bufferHeight - 1 - uy }
                    270 -> { bx = bufferWidth - 1 - uy; by = ux }
                    else -> { bx = ux; by = uy }
                }
                out[i++] = y[by * rowStride + bx].toInt() and 0xFF
            }
        }
        return GrayImage(w, h, out)
    }

    /**
     * Local contrast normalisation: `128 + gain * (p - mean) / (std + floor)`
     * over a (2·radius+1)² window, computed in O(n) with integral images.
     * A 3×3 pre-blur cuts sensor noise ~3× first, so faint edges can be
     * amplified strongly without turning flat areas into grain.
     */
    fun localNormalize(
        img: GrayImage,
        radius: Int,
        invert: Boolean,
        gain: Double = 64.0,
        noiseFloor: Double = 4.0,
    ): IntArray {
        val w = img.width
        val h = img.height
        val src = boxBlur3(img)
        val stride = w + 1
        val integralSize = stride * (h + 1)
        if (sum.size < integralSize) {
            sum = IntArray(integralSize)
            sumSq = LongArray(integralSize)
        }
        val sum = sum
        val sumSq = sumSq
        // Row 0 / column 0 must be zero (reused arrays may hold old data).
        for (x in 0 until stride) { sum[x] = 0; sumSq[x] = 0 }
        for (y in 0 until h) {
            sum[(y + 1) * stride] = 0
            sumSq[(y + 1) * stride] = 0
            var rowSum = 0
            var rowSq = 0L
            for (x in 0 until w) {
                val p = src[y * w + x]
                rowSum += p
                rowSq += (p * p).toLong()
                sum[(y + 1) * stride + x + 1] = sum[y * stride + x + 1] + rowSum
                sumSq[(y + 1) * stride + x + 1] = sumSq[y * stride + x + 1] + rowSq
            }
        }

        if (normalized.size < w * h) normalized = IntArray(w * h)
        val out = normalized
        for (y in 0 until h) {
            val y0 = maxOf(0, y - radius)
            val y1 = minOf(h, y + radius + 1)
            for (x in 0 until w) {
                val x0 = maxOf(0, x - radius)
                val x1 = minOf(w, x + radius + 1)
                val n = ((y1 - y0) * (x1 - x0)).toDouble()
                val s = sum[y1 * stride + x1] - sum[y0 * stride + x1] - sum[y1 * stride + x0] + sum[y0 * stride + x0]
                val sq = sumSq[y1 * stride + x1] - sumSq[y0 * stride + x1] - sumSq[y1 * stride + x0] + sumSq[y0 * stride + x0]
                val mean = s / n
                val variance = (sq / n - mean * mean).coerceAtLeast(0.0)
                val v = 128 + gain * (src[y * w + x] - mean) / (sqrt(variance) + noiseFloor)
                val c = v.toInt().coerceIn(0, 255)
                out[y * w + x] = if (invert) 255 - c else c
            }
        }
        return out
    }

    private fun boxBlur3(img: GrayImage): IntArray {
        val w = img.width
        val h = img.height
        val src = img.pixels
        if (blurred.size < w * h) blurred = IntArray(w * h)
        val out = blurred
        for (y in 0 until h) {
            for (x in 0 until w) {
                var s = 0
                var n = 0
                for (dy in -1..1) {
                    val yy = y + dy
                    if (yy < 0 || yy >= h) continue
                    for (dx in -1..1) {
                        val xx = x + dx
                        if (xx < 0 || xx >= w) continue
                        s += src[yy * w + xx]
                        n++
                    }
                }
                out[y * w + x] = s / n
            }
        }
        return out
    }

    companion object {
        /**
         * Maps a rect given in *upright* coordinates to the unrotated buffer
         * it came from (same convention as [cropUpright]). Returns
         * [left, top, right, bottom] in buffer pixels, so a big photo can be
         * cropped *before* rotating it.
         */
        fun uprightRectToBuffer(
            left: Int, top: Int, right: Int, bottom: Int,
            bufferWidth: Int, bufferHeight: Int, rotationDegrees: Int,
        ): IntArray = when (rotationDegrees) {
            90 -> intArrayOf(top, bufferHeight - right, bottom, bufferHeight - left)
            180 -> intArrayOf(bufferWidth - right, bufferHeight - bottom, bufferWidth - left, bufferHeight - top)
            270 -> intArrayOf(bufferWidth - bottom, left, bufferWidth - top, right)
            else -> intArrayOf(left, top, right, bottom)
        }

        /** ARGB → luminance (BT.601 integer approximation), in place into [out]. */
        fun lumaFromArgb(argb: IntArray, count: Int, out: IntArray) {
            for (i in 0 until count) {
                val c = argb[i]
                out[i] = (((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8
            }
        }
    }

    /** Gray → opaque ARGB pixels (first [count] entries) for a Bitmap. */
    fun toArgb(gray: IntArray, count: Int): IntArray {
        if (argb.size < count) argb = IntArray(count)
        for (i in 0 until count) {
            val g = gray[i]
            argb[i] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
        }
        return argb
    }
}
