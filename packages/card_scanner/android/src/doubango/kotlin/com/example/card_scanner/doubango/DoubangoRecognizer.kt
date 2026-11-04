package com.example.card_scanner.doubango

import android.content.Context
import com.example.card_scanner.ExternalCardRecognizer
import org.doubango.ultimateCreditCard.Sdk.ULTCCARD_SDK_IMAGE_TYPE
import org.doubango.ultimateCreditCard.Sdk.UltCreditCardSdkEngine
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer

/**
 * EVALUATION ONLY. Doubango ultimateCreditCard SDK, licensed for
 * non-commercial use (see third_party/doubango/LICENSE). Compiled in only
 * when the app sets `cardScannerDoubango=true`.
 */
class DoubangoRecognizer : ExternalCardRecognizer {

    override fun init(context: Context): String? {
        // https://www.doubango.org/SDKs/credit-card-ocr/docs/Configuration_options.html
        val config = JSONObject().apply {
            put("debug_level", "warn")
            // PRIVACY: never let the SDK write camera frames to storage.
            put("debug_write_input_image_enabled", false)
            put("debug_internal_data_path", context.cacheDir.absolutePath)
            put("num_threads", -1)
            put("gpgpu_enabled", true)
            put("ielcd_enabled", false)
            put("detect_minscore", 0.5)
            put("detect_roi", JSONArray(listOf(0, 0, 0, 0)))
            put("recogn_minscore", 0.2)
            put("recogn_score_type", "min")
            put("recogn_rectify_enabled", false)
        }
        val init = UltCreditCardSdkEngine.init(context.assets, config.toString())
        if (!init.isOK) return "Doubango init failed ${init.code()}: ${init.phrase()}"
        UltCreditCardSdkEngine.warmUp(ULTCCARD_SDK_IMAGE_TYPE.ULTCCARD_SDK_IMAGE_TYPE_YUV420P)
        return null
    }

    override fun process(
        y: ByteBuffer, u: ByteBuffer, v: ByteBuffer,
        width: Int, height: Int,
        yRowStride: Int, uRowStride: Int, vRowStride: Int, uvPixelStride: Int,
        rotationDegrees: Int,
    ): ExternalCardRecognizer.Read {
        val result = UltCreditCardSdkEngine.process(
            ULTCCARD_SDK_IMAGE_TYPE.ULTCCARD_SDK_IMAGE_TYPE_YUV420P,
            y, u, v,
            width.toLong(), height.toLong(),
            yRowStride.toLong(), uRowStride.toLong(), vRowStride.toLong(), uvPixelStride.toLong(),
            exifOrientation(rotationDegrees),
        )
        try {
            if (!result.isOK) {
                return ExternalCardRecognizer.Read(null, null, null, emptyList(), 0, "${result.code()}: ${result.phrase()}")
            }
            return parse(result.json())
        } finally {
            result.delete()
        }
    }

    override fun close() {
        UltCreditCardSdkEngine.deInit()
    }

    private fun parse(json: String?): ExternalCardRecognizer.Read {
        if (json.isNullOrEmpty()) return ExternalCardRecognizer.Read(null, null, null, emptyList(), 0)
        val root = JSONObject(json)
        val cards = root.optJSONArray("cards") ?: return ExternalCardRecognizer.Read(null, null, null, emptyList(), 0)

        var number: String? = null
        var expiry: String? = null
        var holder: String? = null
        var numberShape: String? = null
        val names = mutableListOf<String>()
        for (c in 0 until cards.length()) {
            val fields = cards.getJSONObject(c).optJSONArray("fields") ?: continue
            for (f in 0 until fields.length()) {
                val field = fields.getJSONObject(f)
                val name = field.optString("name")
                val value = field.optString("value")
                val valid = if (field.has("valid")) field.optBoolean("valid") else null
                names += if (valid == null) name else "$name(valid=$valid)"
                val key = name.lowercase()
                when {
                    key == "number" || key.contains("pan") -> {
                        if (numberShape == null) numberShape = value.map { if (it.isDigit()) '#' else it }.joinToString("")
                        if (valid != false) number = number ?: value.filter(Char::isDigit)
                    }
                    key.contains("valid") || key.contains("expir") || key.contains("date") ->
                        expiry = expiry ?: value
                    key.contains("holder") || key.contains("name") ->
                        holder = holder ?: value.trim().uppercase()
                }
            }
        }
        return ExternalCardRecognizer.Read(number, expiry, holder, names, cards.length(), numberShape = numberShape)
    }

    /** android.media.ExifInterface orientation constants. */
    private fun exifOrientation(rotationDegrees: Int) = when (rotationDegrees) {
        90 -> 6 // ORIENTATION_ROTATE_90
        180 -> 3 // ORIENTATION_ROTATE_180
        270 -> 8 // ORIENTATION_ROTATE_270
        else -> 1 // ORIENTATION_NORMAL
    }
}
