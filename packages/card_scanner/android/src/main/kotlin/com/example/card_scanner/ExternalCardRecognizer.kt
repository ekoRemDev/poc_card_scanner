package com.example.card_scanner

import android.content.Context
import java.nio.ByteBuffer

/**
 * A card-specific recognizer that is compiled in only when enabled by a
 * Gradle flag (e.g. Doubango, see `cardScannerDoubango`). Loaded by
 * reflection so the SDK builds without it.
 *
 * All calls happen on the scanner's single analysis thread.
 */
interface ExternalCardRecognizer {
    /** Returns null on success, otherwise an error message. */
    fun init(context: Context): String?

    /** One YUV_420_888 camera frame. */
    fun process(
        y: ByteBuffer, u: ByteBuffer, v: ByteBuffer,
        width: Int, height: Int,
        yRowStride: Int, uRowStride: Int, vRowStride: Int, uvPixelStride: Int,
        rotationDegrees: Int,
    ): Read

    fun close()

    class Read(
        /** Digits only, or null. */
        val number: String?,
        val expiryText: String?,
        val holder: String?,
        /** Field names the engine returned (for debug logs; never values). */
        val fieldNames: List<String>,
        val cardsDetected: Int,
        val error: String? = null,
        /** Raw number value with digits replaced by '#' (debug; no digits). */
        val numberShape: String? = null,
    )

    companion object {
        const val DOUBANGO = "com.example.card_scanner.doubango.DoubangoRecognizer"

        fun load(className: String): ExternalCardRecognizer? = try {
            Class.forName(className).getDeclaredConstructor().newInstance() as ExternalCardRecognizer
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: ClassCastException) {
            null
        } catch (_: LinkageError) {
            null // e.g. native library missing for this ABI
        }
    }
}
