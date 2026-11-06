package com.example.card_scanner

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Reads raised (embossed) card numbers, where general-purpose OCR fails.
 *
 * Embossed numbers use one standard font, on one line, with fixed character
 * pitch and a fixed group pattern (4-4-4-4 with one blank position between
 * groups). So the problem reduces to:
 *  1. **Locate**: ML Kit still finds the number line and gives per-character
 *     boxes, even when it misreads or drops characters.
 *  2. **Grid**: estimate the pitch from those boxes and fit the 19-position
 *     grid (16 digits + 3 gaps), which also places the digits ML Kit dropped.
 *  3. **Classify**: compare each digit cell with templates harvested *in this
 *     session* from cells ML Kit did read confidently. All digits on a card
 *     share font and lighting, so no training data or font file is needed.
 *  4. **Vote** per position across frames, then brand + length + Luhn
 *     (Luhn can also fill exactly one missing digit).
 *
 * PRIVACY: templates and votes live only in this object (one scan session).
 * [Result.diagnostics] contains counts only, never digits.
 *
 * Pure Kotlin (no Android types) so it can be unit tested on the JVM.
 * Spike scope: 16-digit 4-4-4-4 numbers (Visa / Mastercard / most cards).
 */
class EmbossedNumberReader(
    /**
     * Built-in digit shapes (index = digit), e.g. from a calibration file
     * shipped with the SDK. Lets every cell be classified even when OCR
     * reads none of the digits correctly (Farrington 7B embossing font).
     */
    private val builtInTemplates: Array<FloatArray?> = arrayOfNulls(10),
) {

    /** One OCR character with its box, in the same pixel space as the image. */
    class Symbol(
        val ch: Char,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        /** 0..1, or NaN when the OCR engine doesn't report it. */
        val confidence: Float = Float.NaN,
    ) {
        val cx get() = (left + right) / 2.0
        val height get() = bottom - top
    }

    class Result(val number: String?, val diagnostics: String)

    private val votes = Array(DIGITS) { DoubleArray(10) }
    /** Positions that ever got a real digit from OCR (strong evidence). */
    private val ocrConfirmed = BooleanArray(DIGITS)
    /** Best template match score seen per position and digit. */
    private val templateScore = Array(DIGITS) { DoubleArray(10) }
    private val templates = Array(10) { ArrayList<FloatArray>() }
    private var framesUsed = 0

    /**
     * @param lines OCR lines (each a list of symbols) in [image] pixel space.
     * @param image Upright luminance of the region the coordinates refer to.
     * @return a plausible card number once enough evidence has accumulated.
     */
    fun update(lines: List<List<Symbol>>, image: LumaEnhancer.GrayImage): Result {
        val line = pickNumberLine(lines, image.width)
            ?: return Result(null, "embossed line=none")
        val grid = fitGrid(line, image)
            ?: return Result(null, "embossed line=${line.size}sym grid=none")
        framesUsed++

        // 1) ML Kit labels per cell (+ template harvesting).
        val cellVectors = arrayOfNulls<FloatArray>(DIGITS)
        val mlDigit = IntArray(DIGITS) { -1 }
        for (k in 0 until DIGITS) {
            val cx = grid.x0 + PATTERN[k] * grid.pitch
            val vector = cellVector(image, cx, grid) ?: continue
            cellVectors[k] = vector
            val sym = grid.symbolAt[k] ?: continue
            val digit = sym.ch.digitToIntOrNull()
            if (digit != null) {
                mlDigit[k] = digit
                votes[k][digit] += 1.0
                ocrConfirmed[k] = true
                val confident = sym.confidence.isNaN() || sym.confidence >= TEMPLATE_MIN_CONFIDENCE
                if (confident) addTemplate(digit, vector)
            } else {
                CONFUSIONS[sym.ch]?.let { votes[k][it] += 0.5 }
            }
        }

        // 2) Template classification for cells ML Kit didn't read as a digit.
        var templated = 0
        var bestSum = 0.0
        var marginSum = 0.0
        var attempts = 0
        for (k in 0 until DIGITS) {
            if (mlDigit[k] >= 0) continue
            val vector = cellVectors[k] ?: continue
            lastBest = 0.0; lastMargin = 0.0
            val classified = classify(vector)
            attempts++; bestSum += lastBest; marginSum += lastMargin
            val (digit, score) = classified ?: continue
            votes[k][digit] += 1.0
            templateScore[k][digit] = maxOf(templateScore[k][digit], score)
            templated++
        }

        // 3) Current best guess per position.
        val digits = IntArray(DIGITS) { k ->
            val d = decided(votes[k])
            // A shape-only decision (no OCR digit ever at this position) must
            // be *consistent across frames*: enough votes and a clear share.
            // Otherwise leave it to Luhn.
            if (d >= 0 && !ocrConfirmed[k] && votes[k][d] < SOFT_MIN_VOTES) -1 else d
        }
        val known = digits.count { it >= 0 }
        val soft = (0 until DIGITS).filter { !ocrConfirmed[it] }
        val number = assemble(digits, soft)
        val classes = templates.count { it.isNotEmpty() }
        val diag = "embossed line=${line.size}sym pitch=${"%.1f".format(grid.pitch)} " +
            "fit=${grid.matched}/${line.size} known=$known/$DIGITS templated=$templated " +
            (if (attempts > 0) "avgBest=${"%.2f".format(bestSum / attempts)} avgMargin=${"%.3f".format(marginSum / attempts)} " else "") +
            "tplClasses=$classes frames=$framesUsed number=${number != null}" +
            (number?.let { " fp=${fingerprint(it)}" } ?: "")
        return Result(number, diag)
    }

    // ------------------------------------------------------------ locate

    /** The line with the most digit-like characters, wide enough to be the PAN. */
    private fun pickNumberLine(lines: List<List<Symbol>>, imageWidth: Int): List<Symbol>? =
        lines
            .map { line -> line.filter { it.ch.isDigit() || it.ch in DIGIT_LIKE } }
            .filter { it.size >= MIN_LINE_SYMBOLS }
            .filter { (it.maxOf { s -> s.right } - it.minOf { s -> s.left }) >= imageWidth * 0.35 }
            .maxByOrNull { it.size }
            ?.sortedBy { it.cx }

    // ------------------------------------------------------------ grid

    private class Grid(
        val x0: Double,
        val pitch: Double,
        val top: Int,
        val bottom: Int,
        val matched: Int,
        val symbolAt: Array<Symbol?>,
    )

    /**
     * Fits x = x0 + slot * pitch, slot ∈ 0..18, digits on [PATTERN] slots.
     * Tries every (symbol, slot) anchor and keeps the best-scoring fit.
     */
    private fun fitGrid(symbols: List<Symbol>, image: LumaEnhancer.GrayImage): Grid? {
        val h = median(symbols.map { it.height.toDouble() })
        val gaps = symbols.zipWithNext { a, b -> b.cx - a.cx }.filter { it in 0.5 * h..1.4 * h }
        val pitch0 = if (gaps.size >= 3) median(gaps) else PITCH_PER_HEIGHT * h
        if (pitch0 <= 0) return null

        // Score every (anchor symbol, slot) hypothesis.
        val scored = ArrayList<Triple<Double, Double, Int>>() // x0, pitch, score
        for (pitch in listOf(pitch0 * 0.97, pitch0, pitch0 * 1.03)) {
            for (anchor in symbols) {
                for (slot in PATTERN) {
                    val x0 = anchor.cx - slot * pitch
                    var score = 0
                    for (s in symbols) {
                        val pos = (s.cx - x0) / pitch
                        val nearest = pos.roundToInt()
                        if (abs(pos - nearest) > 0.3) continue
                        score += if (nearest in PATTERN_SET) 2 else -3
                    }
                    scored += Triple(x0, pitch, score)
                }
            }
        }
        val bestScore = scored.maxOfOrNull { it.third } ?: return null
        if (bestScore <= 0) return null

        // Geometry can be ambiguous (e.g. a whole group missing fits several
        // offsets equally well). Among the best-scoring fits, prefer the one
        // whose 16 cells contain the most texture (embossed digits) — a wrong
        // offset puts cells on blank card or off the image.
        return scored
            .filter { it.third == bestScore }
            .mapNotNull { (x0, pitch, _) -> buildGrid(symbols, x0, pitch) }
            .distinctBy { (it.x0 / (it.pitch * 0.5)).roundToInt() }
            .maxByOrNull { textureScore(image, it) }
    }

    private fun buildGrid(symbols: List<Symbol>, x0: Double, pitch: Double): Grid? {
        val symbolAt = arrayOfNulls<Symbol>(DIGITS)
        var matched = 0
        for (s in symbols) {
            val pos = (s.cx - x0) / pitch
            val slot = pos.roundToInt()
            if (abs(pos - slot) > 0.3) continue
            val k = PATTERN.indexOf(slot)
            if (k >= 0 && symbolAt[k] == null) {
                symbolAt[k] = s
                matched++
            }
        }
        if (matched < MIN_MATCHED) return null
        val matchedSymbols = symbolAt.filterNotNull()

        // Least-squares refinement of x0 and pitch over all matched symbols
        // (cx = x0 + slot * pitch): a small pitch error otherwise accumulates
        // to a whole cell by the end of the line.
        var n = 0.0; var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
        for (k in 0 until DIGITS) {
            val s = symbolAt[k] ?: continue
            val slot = PATTERN[k].toDouble()
            n++; sx += slot; sy += s.cx; sxx += slot * slot; sxy += slot * s.cx
        }
        val denominator = n * sxx - sx * sx
        val fittedPitch = if (denominator > 1e-9) (n * sxy - sx * sy) / denominator else pitch
        val fittedX0 = (sy - fittedPitch * sx) / n
        val usable = fittedPitch in pitch * 0.85..pitch * 1.15
        return Grid(
            if (usable) fittedX0 else x0, if (usable) fittedPitch else pitch,
            median(matchedSymbols.map { it.top.toDouble() }).toInt(),
            median(matchedSymbols.map { it.bottom.toDouble() }).toInt(),
            matched, symbolAt,
        )
    }

    /** Sum of pixel standard deviations of the 16 cells (0 for cells off-image). */
    private fun textureScore(image: LumaEnhancer.GrayImage, grid: Grid): Double {
        var total = 0.0
        for (k in 0 until DIGITS) {
            val cx = grid.x0 + PATTERN[k] * grid.pitch
            val left = (cx - grid.pitch * 0.4).toInt()
            val right = (cx + grid.pitch * 0.4).toInt()
            if (left < 0 || right > image.width || grid.top < 0 || grid.bottom > image.height) continue
            var sum = 0.0
            var sq = 0.0
            var n = 0
            for (y in grid.top until grid.bottom) {
                for (x in left until right) {
                    val p = image.pixels[y * image.width + x].toDouble()
                    sum += p; sq += p * p; n++
                }
            }
            if (n > 0) total += sqrt((sq / n - (sum / n) * (sum / n)).coerceAtLeast(0.0))
        }
        return total
    }

    // ------------------------------------------------------------ cells

    /**
     * Feature vector of the cell around [cx]: edge strength (gradient
     * magnitude) on a CELL_W×CELL_H grid, zero-mean unit-norm.
     *
     * Edge strength instead of brightness because embossed digits are only
     * visible through shading whose light/dark side flips with the light
     * direction; the *edges* stay in the same place either way.
     */
    private fun cellVector(image: LumaEnhancer.GrayImage, cx: Double, grid: Grid): FloatArray? {
        val h = grid.bottom - grid.top
        val left = (cx - grid.pitch * 0.5).toInt()
        val right = (cx + grid.pitch * 0.5).toInt()
        val top = grid.top - (h * 0.12).toInt()
        val bottom = grid.bottom + (h * 0.12).toInt()
        if (left < 0 || top < 0 || right > image.width || bottom > image.height) return null
        if (right - left < 4 || bottom - top < 4) return null

        // 1) Area-resample brightness to 2x the feature grid.
        val gw = CELL_W * 2
        val gh = CELL_H * 2
        val gray = FloatArray(gw * gh)
        val sx = (right - left).toDouble() / gw
        val sy = (bottom - top).toDouble() / gh
        for (y in 0 until gh) {
            val y0 = top + (y * sy).toInt()
            val y1 = maxOf(y0 + 1, top + ((y + 1) * sy).toInt())
            for (x in 0 until gw) {
                val x0 = left + (x * sx).toInt()
                val x1 = maxOf(x0 + 1, left + ((x + 1) * sx).toInt())
                var sum = 0
                var n = 0
                for (yy in y0 until minOf(y1, image.height)) {
                    for (xx in x0 until minOf(x1, image.width)) {
                        sum += image.pixels[yy * image.width + xx]
                        n++
                    }
                }
                gray[y * gw + x] = if (n == 0) 0f else sum.toFloat() / n
            }
        }
        // 2) Gradient magnitude (central differences), pooled 2x2 → CELL grid.
        val out = FloatArray(CELL_W * CELL_H)
        for (y in 0 until gh) {
            for (x in 0 until gw) {
                val l = gray[y * gw + maxOf(0, x - 1)]
                val r = gray[y * gw + minOf(gw - 1, x + 1)]
                val u = gray[maxOf(0, y - 1) * gw + x]
                val d = gray[minOf(gh - 1, y + 1) * gw + x]
                out[(y / 2) * CELL_W + (x / 2)] += abs(r - l) + abs(d - u)
            }
        }
        return normalize(out)
    }

    private fun normalize(v: FloatArray): FloatArray? {
        val mean = v.average().toFloat()
        var norm = 0.0
        for (i in v.indices) {
            v[i] -= mean
            norm += v[i] * v[i]
        }
        if (norm < 1e-6) return null // flat cell: nothing printed / embossed
        val inv = (1.0 / sqrt(norm)).toFloat()
        for (i in v.indices) v[i] *= inv
        return v
    }

    private fun addTemplate(digit: Int, vector: FloatArray) {
        val list = templates[digit]
        if (list.size >= MAX_TEMPLATES_PER_DIGIT) list.removeAt(0)
        list += vector.copyOf()
    }

    /** Best digit and its score by normalised cross-correlation, if ahead. */
    private fun classify(vector: FloatArray): Pair<Int, Double>? {
        val scored = scoreDigits(vector) ?: return null
        lastBest = scored.second
        lastMargin = scored.third
        if (scored.second < MATCH_MIN || scored.third < MATCH_MARGIN) return null
        return scored.first to scored.second
    }

    private var lastBest = 0.0
    private var lastMargin = 0.0

    /** (best digit, best score, margin over the best *other* digit) or null. */
    private fun scoreDigits(vector: FloatArray): Triple<Int, Double, Double>? {
        val best = DoubleArray(10) { -1.0 }
        for (d in 0..9) {
            val candidates = templates[d] + listOfNotNull(builtInTemplates[d])
            for (t in candidates) {
                var dot = 0.0
                for (i in vector.indices) dot += vector[i] * t[i]
                if (dot > best[d]) best[d] = dot
            }
        }
        val order = (0..9).sortedByDescending { best[it] }
        val first = order[0]
        if (best[first] < 0) return null
        val second = order[1]
        val margin = if (best[second] >= 0) best[first] - best[second] else best[first]
        return Triple(first, best[first], margin)
    }

    // ------------------------------------------------------------ calibration

    private val calibrationSum = Array(10) { FloatArray(CELL_W * CELL_H) }
    private val calibrationSeen = BooleanArray(10)
    var calibrationFrames = 0
        private set

    /**
     * Development tool: accumulates the average shape of each digit from a
     * card whose number is known. A frame is used only if the grid is
     * provably aligned: OCR's digit reads must agree with [knownNumber] at
     * their positions (≥ [CALIBRATION_MIN_AGREE] of at least 3 reads).
     *
     * @return diagnostics (counts only).
     */
    fun calibrate(lines: List<List<Symbol>>, image: LumaEnhancer.GrayImage, knownNumber: String): String {
        require(knownNumber.length == DIGITS && knownNumber.all(Char::isDigit))
        val line = pickNumberLine(lines, image.width) ?: return "calibrate line=none"
        val grid = fitGrid(line, image) ?: return "calibrate grid=none"

        var reads = 0
        var agree = 0
        for (k in 0 until DIGITS) {
            val digit = grid.symbolAt[k]?.ch?.digitToIntOrNull() ?: continue
            reads++
            if (digit == knownNumber[k].digitToInt()) agree++
        }
        if (reads < 3 || agree < reads * CALIBRATION_MIN_AGREE) {
            return "calibrate rejected agree=$agree/$reads frames=$calibrationFrames"
        }
        var cells = 0
        // Self-test: how well do the *built-in* shapes (from other cards)
        // classify this card's digits? Truth is known here. Counts only.
        var tested = 0
        var correct = 0
        var truthRank1Margin = 0.0
        for (k in 0 until DIGITS) {
            val vector = cellVector(image, grid.x0 + PATTERN[k] * grid.pitch, grid) ?: continue
            val d = knownNumber[k].digitToInt()
            if (builtInTemplates[d] != null) {
                val scored = scoreDigitsBuiltInOnly(vector)
                if (scored != null) {
                    tested++
                    if (scored.first == d) { correct++; truthRank1Margin += scored.third }
                }
            }
            val sum = calibrationSum[d]
            for (i in sum.indices) sum[i] += vector[i]
            calibrationSeen[d] = true
            cells++
        }
        calibrationFrames++
        selfTestTested += tested
        selfTestCorrect += correct
        return "calibrate accepted agree=$agree/$reads cells=$cells frames=$calibrationFrames " +
            "digitsCovered=${calibrationSeen.count { it }}" +
            (if (tested > 0) " selftest=$correct/$tested total=$selfTestCorrect/$selfTestTested" +
                " avgMarginWhenRight=${"%.3f".format(if (correct > 0) truthRank1Margin / correct else 0.0)}" else "")
    }

    private var selfTestTested = 0
    private var selfTestCorrect = 0

    private fun scoreDigitsBuiltInOnly(vector: FloatArray): Triple<Int, Double, Double>? {
        val best = DoubleArray(10) { -1.0 }
        for (d in 0..9) {
            val t = builtInTemplates[d] ?: continue
            var dot = 0.0
            for (i in vector.indices) dot += vector[i] * t[i]
            best[d] = dot
        }
        val order = (0..9).sortedByDescending { best[it] }
        if (best[order[0]] < 0) return null
        val margin = if (best[order[1]] >= 0) best[order[0]] - best[order[1]] else best[order[0]]
        return Triple(order[0], best[order[0]], margin)
    }

    /** Averaged digit shapes (null = digit not on the calibration card). */
    fun calibratedTemplates(): Array<FloatArray?> = Array(10) { d ->
        if (!calibrationSeen[d]) null else normalize(calibrationSum[d].copyOf())
    }

    // ------------------------------------------------------------ decide

    private fun decided(v: DoubleArray): Int {
        val total = v.sum()
        if (total < MIN_VOTES) return -1
        val top = v.indices.maxBy { v[it] }
        return if (v[top] / total >= MIN_SHARE) top else -1
    }

    private fun assemble(digits: IntArray, soft: List<Int>): String? {
        val unknown = digits.indices.filter { digits[it] < 0 }
        return when (unknown.size) {
            0 -> digits.joinToString("").takeIf { CardParser.isPlausibleCardNumber(it) }
                // All filled but Luhn fails: only if exactly one position lacks
                // OCR confirmation may Luhn correct it (never guess wider).
                ?: soft.singleOrNull()?.let { assemble(digits.copyOf().also { d -> d[it] = -1 }, emptyList()) }
            // Exactly one unknown: Luhn determines it uniquely.
            1 -> (0..9).map { d ->
                digits.copyOf().also { it[unknown[0]] = d }.joinToString("")
            }.singleOrNull { CardParser.isPlausibleCardNumber(it) }
            else -> null
        }
    }

    companion object {
        const val DIGITS = 16

        /**
         * Debug-only consistency check: a 0..999 hash bucket. Equal numbers
         * give equal fingerprints; it can't be turned back into the number.
         */
        fun fingerprint(number: String) = (number.hashCode() and 0x7fffffff) % 1000

        /** Grid slots of the 16 digits in "dddd dddd dddd dddd". */
        val PATTERN = intArrayOf(0, 1, 2, 3, 5, 6, 7, 8, 10, 11, 12, 13, 15, 16, 17, 18)
        private val PATTERN_SET = PATTERN.toSet()

        /** Embossed pitch (3.63 mm, 7 cpi) relative to character height. */
        private const val PITCH_PER_HEIGHT = 0.84

        private const val MIN_LINE_SYMBOLS = 8
        private const val MIN_MATCHED = 8
        private const val CELL_W = 16
        private const val CELL_H = 24
        private const val MAX_TEMPLATES_PER_DIGIT = 8
        private const val TEMPLATE_MIN_CONFIDENCE = 0.8f
        private const val MATCH_MIN = 0.35
        private const val MATCH_MARGIN = 0.02
        private const val SOFT_MIN_VOTES = 3.0
        private const val CALIBRATION_MIN_AGREE = 0.7

        const val TEMPLATE_FORMAT = "gradmag-16x24-v1"

        /**
         * Serialises digit shapes. Deliberately no per-digit counts: those
         * would reveal how often each digit occurs on the calibration card.
         */
        fun templatesToJson(templates: Array<FloatArray?>, frames: Int): String {
            val sb = StringBuilder()
            sb.append("{\"format\":\"").append(TEMPLATE_FORMAT).append("\",\"frames\":").append(frames)
            sb.append(",\"digits\":{")
            var first = true
            for (d in 0..9) {
                val t = templates[d] ?: continue
                if (!first) sb.append(',')
                first = false
                sb.append('"').append(d).append("\":[")
                t.forEachIndexed { i, v -> if (i > 0) sb.append(','); sb.append("%.5f".format(java.util.Locale.ROOT, v)) }
                sb.append(']')
            }
            sb.append("}}")
            return sb.toString()
        }

        /** Parses [templatesToJson] output (or a merged file of the same format). */
        fun templatesFromJson(json: String): Array<FloatArray?> {
            val root = org.json.JSONObject(json)
            require(root.optString("format") == TEMPLATE_FORMAT) { "Unsupported template format" }
            val digits = root.getJSONObject("digits")
            return Array(10) { d ->
                val arr = digits.optJSONArray(d.toString()) ?: return@Array null
                FloatArray(arr.length()) { arr.getDouble(it).toFloat() }.takeIf { it.size == CELL_W * CELL_H }
            }
        }
        private const val MIN_VOTES = 1.5
        private const val MIN_SHARE = 0.6

        /** Characters OCR produces for embossed digits (observed: ")", "B", "R", "J", "*"). */
        private val DIGIT_LIKE = setOf(
            ')', '(', '*', '|', '/', 'B', 'R', 'J', 'O', 'D', 'Q', 'I', 'l', 'L',
            'S', 's', 'Z', 'z', 'G', 'b', 'g', 'q', 'T', 'U', 'A',
        )

        /** Weak (half-weight) votes for common letter/digit confusions. */
        private val CONFUSIONS = mapOf(
            'O' to 0, 'D' to 0, 'Q' to 0, 'I' to 1, 'l' to 1, '|' to 1,
            'Z' to 2, 'z' to 2, 'S' to 5, 's' to 5, 'G' to 6, 'b' to 6, 'B' to 8,
        )

        private fun median(values: List<Double>): Double {
            if (values.isEmpty()) return 0.0
            val sorted = values.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
        }
    }
}
