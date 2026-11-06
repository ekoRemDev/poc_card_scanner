package com.example.card_scanner

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Full-screen card scanner: CameraX + ML Kit text recognition.
 * Returns the result through [Intent] extras (see companion constants).
 */
class ScannerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_SUBTITLE = "subtitle"
        const val EXTRA_TIMEOUT_MS = "timeoutMs"
        const val EXTRA_WANTS_EXPIRY = "wantsExpiry"
        const val EXTRA_WANTS_NAME = "wantsName"
        const val EXTRA_DEBUG_TEXT = "debugShowRecognizedText"
        const val EXTRA_ENGINE = "engine"
        const val ENGINE_DOUBANGO = "doubango"
        const val EXTRA_CALIBRATION_NUMBER = "calibrationNumber"
        const val RESULT_CAL_FRAMES = "calFrames"
        const val RESULT_CAL_DIGITS = "calDigits"
        const val RESULT_CAL_PATH = "calPath"

        /** Built-in embossed digit shapes shipped with the SDK (optional). */
        const val EMBOSSED_TEMPLATES_ASSET = "card_scanner_embossed_templates.json"
        private const val CALIBRATION_TARGET_FRAMES = 40
        private const val LOG_TAG = "CardScanner"

        /** Live scanning gets this long before still photos start. */
        private const val PHOTO_FIRST_DELAY_MS = 2500L
        private const val PHOTO_INTERVAL_MS = 1500L

        const val RESULT_NUMBER = "number"
        const val RESULT_EXPIRY_MONTH = "expiryMonth"
        const val RESULT_EXPIRY_YEAR = "expiryYear"
        const val RESULT_HOLDER_NAME = "holderName"
        const val RESULT_ELAPSED_MS = "elapsedMs"
        const val RESULT_FRAMES = "frames"
        const val RESULT_ERROR_CODE = "errorCode"
        const val RESULT_ERROR_MESSAGE = "errorMessage"

        /** resultCode used for errors (RESULT_OK = success, RESULT_CANCELED = user cancel). */
        const val RESULT_ERROR = RESULT_FIRST_USER

        fun intent(context: Context, options: ScanOptionsMessage) =
            Intent(context, ScannerActivity::class.java).apply {
                putExtra(EXTRA_TITLE, options.title)
                putExtra(EXTRA_SUBTITLE, options.subtitle)
                putExtra(EXTRA_TIMEOUT_MS, options.timeoutMs)
                putExtra(EXTRA_WANTS_EXPIRY, options.scanExpiry)
                putExtra(EXTRA_WANTS_NAME, options.scanHolderName)
                putExtra(EXTRA_DEBUG_TEXT, options.debugShowRecognizedText)
                if (options.engine == ScanEngineMessage.DOUBANGO) putExtra(EXTRA_ENGINE, ENGINE_DOUBANGO)
            }
    }

    private val startedAt = SystemClock.elapsedRealtime()
    private val parser = CardParser()
    private lateinit var aggregator: ScanAggregator
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private lateinit var analysisExecutor: ExecutorService
    private lateinit var photoExecutor: ExecutorService
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var previewView: PreviewView
    private lateinit var overlay: CardFrameOverlay
    private lateinit var liveNumber: TextView
    private var debugText: TextView? = null
    private val debug by lazy { intent.getBooleanExtra(EXTRA_DEBUG_TEXT, false) }
    private lateinit var torchButton: ImageButton
    private var camera: Camera? = null
    private var torchOn = false
    private var finished = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else finishWithError("permission_denied", "Camera permission denied")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No screenshots, screen recording or recents thumbnail of the card.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        aggregator = ScanAggregator(
            wantsExpiry = intent.getBooleanExtra(EXTRA_WANTS_EXPIRY, true),
            wantsName = intent.getBooleanExtra(EXTRA_WANTS_NAME, true),
        )
        analysisExecutor = Executors.newSingleThreadExecutor()
        photoExecutor = Executors.newSingleThreadExecutor()
        setContentView(buildLayout())

        if (intent.getStringExtra(EXTRA_ENGINE) == ENGINE_DOUBANGO) {
            val engine = ExternalCardRecognizer.load(ExternalCardRecognizer.DOUBANGO)
            if (engine == null) {
                finishWithError(
                    "unavailable",
                    "Doubango engine is not built in. Set cardScannerDoubango=true in android/gradle.properties.",
                )
                return
            }
            external = engine
            // Same single thread as frame analysis, so init finishes first.
            analysisExecutor.execute {
                val error = try { engine.init(applicationContext) } catch (e: Throwable) { e.toString() }
                if (error == null) {
                    externalReady = true
                } else {
                    Log.w(LOG_TAG, error)
                    runOnUiThread { finishWithError("unavailable", error) }
                }
            }
        }

        val timeoutMs = intent.getLongExtra(EXTRA_TIMEOUT_MS, 0)
        if (timeoutMs > 0) {
            mainHandler.postDelayed(
                { finishWithError("timeout", "No card recognised in time") }, timeoutMs,
            )
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        external?.let { engine ->
            analysisExecutor.execute { if (externalReady) runCatching { engine.close() } }
        }
        analysisExecutor.shutdown()
        photoExecutor.shutdown()
        recognizer.close()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ UI

    private fun dp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    @SuppressLint("ClickableViewAccessibility")
    private fun buildLayout(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        previewView = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        root.addView(previewView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        overlay = CardFrameOverlay(this)
        root.addView(overlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val top = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bar = FrameLayout(this)
        val closeButton = iconButton(R.drawable.card_scanner_ic_close, "Close") {
            if (calibrationNumber != null) {
                finishCalibration() // keep whatever was learned so far
            } else {
                setResult(RESULT_CANCELED)
                finish()
            }
        }
        torchButton = iconButton(R.drawable.card_scanner_ic_flash_off, "Torch") { toggleTorch() }
        bar.addView(closeButton, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.START))
        bar.addView(torchButton, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.END))
        top.addView(bar)
        top.addView(label(intent.getStringExtra(EXTRA_TITLE) ?: "Position your card in the frame", 18f, bold = true))
        top.addView(
            label(
                intent.getStringExtra(EXTRA_SUBTITLE)
                    ?: "Scanning happens on this device. Nothing is uploaded.",
                13f, alpha = 0.7f,
            ),
        )
        root.addView(top, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))

        liveNumber = label("", 20f).apply { visibility = View.INVISIBLE }
        root.addView(
            liveNumber,
            FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER).apply {
                topMargin = dp(150f).toInt()
            },
        )

        if (intent.getBooleanExtra(EXTRA_DEBUG_TEXT, false)) {
            debugText = TextView(this).apply {
                setTextColor(Color.YELLOW)
                setBackgroundColor(Color.argb(160, 0, 0, 0))
                typeface = android.graphics.Typeface.MONOSPACE
                textSize = 11f
                val pad = dp(8f).toInt()
                setPadding(pad, pad, pad, pad)
            }
            root.addView(debugText, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            top.setPadding(dp(8f).toInt(), bars.top, dp(8f).toInt(), 0)
            debugText?.let { it.setPadding(it.paddingLeft, it.paddingTop, it.paddingRight, bars.bottom + dp(8f).toInt()) }
            insets
        }

        previewView.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                val point = previewView.meteringPointFactory.createPoint(event.x, event.y)
                camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
                v.performClick()
            }
            true
        }
        return root
    }

    private fun iconButton(icon: Int, description: String, onClick: () -> Unit) =
        ImageButton(this).apply {
            setImageResource(icon)
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = description
            val pad = dp(12f).toInt()
            setPadding(pad, pad, pad, pad)
            setOnClickListener { onClick() }
        }

    private fun label(text: String, sizeSp: Float, bold: Boolean = false, alpha: Float = 1f) =
        TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            this.alpha = alpha
            textSize = sizeSp
            gravity = Gravity.CENTER
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            val pad = dp(24f).toInt()
            setPadding(pad, dp(4f).toInt(), pad, dp(4f).toInt())
        }

    private fun showLive(number: String?) {
        val masked = number?.let { if (it.length < 8) "••••" else "${it.take(4)} •••• •••• ${it.takeLast(4)}" }
        liveNumber.text = masked ?: ""
        liveNumber.visibility = if (masked == null) View.INVISIBLE else View.VISIBLE
        overlay.highlight = masked != null
    }

    private fun toggleTorch() {
        val cam = camera ?: return
        if (!cam.cameraInfo.hasFlashUnit()) return
        torchOn = !torchOn
        cam.cameraControl.enableTorch(torchOn)
        torchButton.setImageResource(
            if (torchOn) R.drawable.card_scanner_ic_flash_on else R.drawable.card_scanner_ic_flash_off,
        )
    }

    // -------------------------------------------------------------- camera

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                // Same 16:9 aspect for preview and analysis so the on-screen
                // card frame maps 1:1 onto analysis coordinates.
                val aspect = AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
                val preview = Preview.Builder()
                    .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(aspect).build())
                    .build()
                    .also { it.surfaceProvider = previewView.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(aspect)
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    // 1080p: raised digits need the extra pixels.
                                    Size(1920, 1080),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                ),
                            )
                            .build(),
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(analysisExecutor, ::analyze) }

                // Full-resolution stills, delivered to RAM only (see capturePhoto).
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(aspect)
                            .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                            .build(),
                    )
                    .build()

                provider.unbindAll()
                camera = try {
                    provider.bindToLifecycle(
                        this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis, capture,
                    ).also {
                        imageCapture = capture
                        if (external == null && calibrationNumber == null) {
                            mainHandler.postDelayed(photoLoop, PHOTO_FIRST_DELAY_MS)
                        }
                    }
                } catch (e: IllegalArgumentException) {
                    // Device can't run 3 streams together: live scanning only.
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis,
                    )
                }
            } catch (e: Exception) {
                finishWithError("camera_error", e.message ?: "Camera failed to start")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Runs on [analysisExecutor]. KEEP_ONLY_LATEST + closing the proxy only
     * after OCR completes means frames are dropped while ML Kit is busy.
     */
    @OptIn(ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        if (media == null || finished) {
            proxy.close()
            return
        }
        val rotation = proxy.imageInfo.rotationDegrees
        external?.let { engine ->
            analyzeExternal(engine, proxy, rotation)
            return
        }
        val uprightWidth = if (rotation % 180 == 0) proxy.width else proxy.height
        val uprightHeight = if (rotation % 180 == 0) proxy.height else proxy.width
        val roi = overlay.regionOfInterest(uprightWidth, uprightHeight)

        // Rotate through frame variants; votes from all of them are pooled.
        // Raw frames keep flat-printed cards fast; the enhanced crops are
        // what make raised/engraved digits readable.
        val variant = LIVE_VARIANTS[frameCounter++ % LIVE_VARIANTS.size]
        val input: InputImage
        val filterRoi: Rect?
        // Raw luminance of the card frame, upright: used by the enhanced
        // variants and by the embossed-number reader (cell templates).
        val cardLuma: LumaEnhancer.GrayImage?
        // Offset from OCR coordinates to cardLuma coordinates.
        val ocrOffsetX: Int
        val ocrOffsetY: Int
        try {
            val usableRoi = roi?.takeIf { it.width() >= 32 && it.height() >= 32 }
            cardLuma = usableRoi?.let { cardLuma(proxy, rotation, it) }
            if (variant == FrameVariant.RAW || usableRoi == null || cardLuma == null) {
                input = InputImage.fromMediaImage(media, rotation)
                filterRoi = roi
                ocrOffsetX = usableRoi?.left ?: 0
                ocrOffsetY = usableRoi?.top ?: 0
            } else {
                input = enhancedInput(cardLuma, invert = variant == FrameVariant.CONTRAST_INVERTED)
                filterRoi = null // the crop *is* the card frame
                ocrOffsetX = 0
                ocrOffsetY = 0
            }
        } catch (e: Exception) {
            proxy.close()
            return
        }

        val startedOcr = SystemClock.elapsedRealtime()
        recognizer.process(input)
            .addOnSuccessListener { text ->
                val all = text.textBlocks.flatMap { it.lines }
                // Only text inside the card frame, top-to-bottom.
                val lines = all
                    .filter { line ->
                        val box = line.boundingBox ?: return@filter filterRoi == null
                        filterRoi == null || filterRoi.contains(box.centerX(), box.centerY())
                    }
                    .sortedBy { it.boundingBox?.top ?: 0 }
                    .map { it.text }
                var frame = parser.parse(lines)
                if (debug) {
                    logDiagnostics(
                        variant, SystemClock.elapsedRealtime() - startedOcr, roi,
                        uprightWidth, uprightHeight, all.size, lines, frame,
                    )
                }
                // Embossed digits: ML Kit locates the line, our reader reads it.
                val calibrating = calibrationNumber
                if (cardLuma != null && calibrating != null) {
                    val symbolLines = all.map { line ->
                        line.elements.flatMap { it.symbols }.mapNotNull { sym ->
                            val box = sym.boundingBox ?: return@mapNotNull null
                            val ch = sym.text.singleOrNull() ?: return@mapNotNull null
                            EmbossedNumberReader.Symbol(
                                ch,
                                box.left - ocrOffsetX, box.top - ocrOffsetY,
                                box.right - ocrOffsetX, box.bottom - ocrOffsetY,
                                sym.confidence,
                            )
                        }
                    }
                    val diag = embossedReader.calibrate(symbolLines, cardLuma, calibrating)
                    if (debug) Log.d(LOG_TAG, "${variant.name.lowercase()} $diag")
                    val frames = embossedReader.calibrationFrames
                    liveNumber.text = "Calibrating… $frames/$CALIBRATION_TARGET_FRAMES"
                    liveNumber.visibility = View.VISIBLE
                    if (frames >= CALIBRATION_TARGET_FRAMES) finishCalibration()
                    return@addOnSuccessListener
                }
                if (cardLuma != null) {
                    val symbolLines = all.map { line ->
                        line.elements.flatMap { it.symbols }.mapNotNull { sym ->
                            val box = sym.boundingBox ?: return@mapNotNull null
                            val ch = sym.text.singleOrNull() ?: return@mapNotNull null
                            EmbossedNumberReader.Symbol(
                                ch,
                                box.left - ocrOffsetX, box.top - ocrOffsetY,
                                box.right - ocrOffsetX, box.bottom - ocrOffsetY,
                                sym.confidence,
                            )
                        }
                    }
                    val embossed = embossedReader.update(symbolLines, cardLuma)
                    if (debug) Log.d(LOG_TAG, "${variant.name.lowercase()} ${embossed.diagnostics}")
                    if (frame.number == null && embossed.number != null) {
                        frame = frame.copy(number = embossed.number)
                    }
                }
                onFrame(frame, variant, lines)
            }
            .addOnCompleteListener { proxy.close() }
    }

    private enum class FrameVariant {
        RAW, CONTRAST, CONTRAST_INVERTED,
        PHOTO_RAW, PHOTO_CONTRAST, PHOTO_CONTRAST_INVERTED,
        DOUBANGO,
    }

    private val LIVE_VARIANTS = listOf(FrameVariant.RAW, FrameVariant.CONTRAST, FrameVariant.CONTRAST_INVERTED)

    private var frameCounter = 0
    private var lumaBuffer = ByteArray(0)
    private val enhancer = LumaEnhancer()
    private var enhancedBitmap: Bitmap? = null

    private val embossedReader by lazy { EmbossedNumberReader(loadBuiltInTemplates()) }

    /** Development calibration mode: the known card number (memory only). */
    private val calibrationNumber: String? by lazy { intent.getStringExtra(EXTRA_CALIBRATION_NUMBER) }

    private fun loadBuiltInTemplates(): Array<FloatArray?> = try {
        assets.open(EMBOSSED_TEMPLATES_ASSET).bufferedReader().use {
            EmbossedNumberReader.templatesFromJson(it.readText())
        }.also { t -> if (debug) Log.d(LOG_TAG, "built-in embossed templates: ${t.count { it != null }}/10 digits") }
    } catch (_: java.io.FileNotFoundException) {
        arrayOfNulls(10) // none shipped yet
    } catch (e: Exception) {
        Log.w(LOG_TAG, "embossed templates unreadable: ${e.javaClass.simpleName}")
        arrayOfNulls(10)
    }

    /**
     * Saves the averaged digit shapes (never the card number) to the app's
     * external files dir, so a developer can `adb pull` and merge them.
     */
    private fun finishCalibration() {
        if (finished) return
        finished = true
        val templates = embossedReader.calibratedTemplates()
        val frames = embossedReader.calibrationFrames
        var path: String? = null
        if (frames > 0) {
            try {
                val dir = getExternalFilesDir(null) ?: filesDir
                val file = java.io.File(dir, "embossed_calibration_${System.currentTimeMillis()}.json")
                file.writeText(EmbossedNumberReader.templatesToJson(templates, frames))
                path = file.absolutePath
            } catch (e: Exception) {
                Log.w(LOG_TAG, "calibration save failed: ${e.javaClass.simpleName}")
            }
        }
        setResult(RESULT_OK, Intent().apply {
            putExtra(RESULT_CAL_FRAMES, frames)
            putExtra(RESULT_CAL_DIGITS, templates.count { it != null })
            putExtra(RESULT_CAL_PATH, path)
        })
        finish()
    }

    /**
     * Upright luminance of the card frame (reused buffers). With
     * KEEP_ONLY_LATEST the next frame only arrives after this one's proxy is
     * closed (OCR complete), so the buffers are stable while OCR runs.
     */
    private fun cardLuma(proxy: ImageProxy, rotation: Int, roi: Rect): LumaEnhancer.GrayImage {
        val plane = proxy.planes[0] // Y plane of YUV_420_888, pixelStride == 1
        val buffer = plane.buffer.duplicate().apply { rewind() }
        val size = buffer.remaining()
        if (lumaBuffer.size < size) lumaBuffer = ByteArray(size)
        buffer.get(lumaBuffer, 0, size)
        return enhancer.cropUpright(
            lumaBuffer, proxy.width, proxy.height, plane.rowStride, rotation,
            roi.left, roi.top, roi.right, roi.bottom,
        )
    }

    /** Local-contrast boosted bitmap of [crop] for ML Kit. */
    private fun enhancedInput(crop: LumaEnhancer.GrayImage, invert: Boolean): InputImage {
        // Window ≈ digit height (~1/16 of the card-frame crop).
        val radius = maxOf(6, crop.height / 20)
        val gray = enhancer.localNormalize(crop, radius, invert)
        val count = crop.width * crop.height
        val pixels = enhancer.toArgb(gray, count)

        var bitmap = enhancedBitmap
        if (bitmap == null || bitmap.width != crop.width || bitmap.height != crop.height) {
            bitmap = Bitmap.createBitmap(crop.width, crop.height, Bitmap.Config.ARGB_8888)
            enhancedBitmap = bitmap
        }
        bitmap.setPixels(pixels, 0, crop.width, 0, 0, crop.width, crop.height)
        return InputImage.fromBitmap(bitmap, 0)
    }

    /** Main thread (ML Kit listeners run there). */
    private fun onFrame(frame: FrameParseResult, variant: FrameVariant, lines: List<String>) {
        if (finished) return
        debugText?.text = "[${variant.name.lowercase()}]\n" + lines.joinToString("\n")
        val result = aggregator.add(frame)
        showLive(aggregator.leadingNumber)
        if (result != null) finishWithResult(result)
    }

    /**
     * Digits → '#'. Letters are kept only on digit-heavy lines (where they are
     * misread digits, e.g. "#S##"); on other lines they become 'a' so names
     * never reach logcat.
     */
    private fun lineShape(line: String): String {
        val digitHeavy = line.count { it.isDigit() } >= 3
        return line.map {
            when {
                it.isDigit() -> '#'
                it.isLetter() && !digitHeavy -> 'a'
                else -> it
            }
        }.joinToString("")
    }

    /**
     * Debug-only. Logs the *shape* of each OCR line (see [lineShape]) so
     * misreads ("#S##") and missing digits are visible without card digits
     * or names ever reaching logcat.
     */
    private fun logDiagnostics(
        variant: FrameVariant,
        ocrMs: Long,
        roi: Rect?,
        imageWidth: Int,
        imageHeight: Int,
        totalLines: Int,
        lines: List<String>,
        frame: FrameParseResult,
    ) {
        val shapes = lines.joinToString(" | ", transform = ::lineShape)
        Log.d(
            LOG_TAG,
            "${variant.name.lowercase()} ${ocrMs}ms img=${imageWidth}x$imageHeight roi=${roi?.toShortString()} " +
                "lines=${lines.size}/$totalLines number=${frame.number != null}" +
                (frame.number?.let { "(fp=${EmbossedNumberReader.fingerprint(it)})" } ?: "") +
                " expiry=${frame.expiry != null} " +
                "name=${frame.holderName != null} :: $shapes",
        )
    }

    // -------------------------------------------------------------- external engine

    private var external: ExternalCardRecognizer? = null
    @Volatile private var externalReady = false

    /** [analysisExecutor]. Card-specific engine (e.g. Doubango) on the full frame. */
    private fun analyzeExternal(engine: ExternalCardRecognizer, proxy: ImageProxy, rotation: Int) {
        try {
            if (!externalReady) return
            val planes = proxy.planes
            val started = SystemClock.elapsedRealtime()
            val read = engine.process(
                planes[0].buffer, planes[1].buffer, planes[2].buffer,
                proxy.width, proxy.height,
                planes[0].rowStride, planes[1].rowStride, planes[2].rowStride, planes[1].pixelStride,
                rotation,
            )
            val ms = SystemClock.elapsedRealtime() - started
            // Same validation as every other engine: brand, length, Luhn.
            val number = read.number?.takeIf { CardParser.isPlausibleCardNumber(it) }
            val frame = FrameParseResult(
                number = number,
                expiry = read.expiryText?.let { parser.findExpiry(listOf(it)) },
                holderName = read.holder?.takeIf { it.isNotBlank() },
            )
            if (debug) {
                Log.d(
                    LOG_TAG,
                    "doubango ${ms}ms img=${proxy.width}x${proxy.height} rot=$rotation cards=${read.cardsDetected} " +
                        "rawNumber=${read.number?.let { "${it.length}digits" }} number=${frame.number != null} " +
                        "expiry=${frame.expiry != null} name=${frame.holderName != null} " +
                        "fields=${read.fieldNames} shape=${read.numberShape} ${read.error?.let { "error=$it" } ?: ""}",
                )
            }
            // On-screen debug overlay only (never logged).
            val overlay = listOfNotNull(
                read.number?.let { "number: $it" },
                read.expiryText?.let { "expiry: $it" },
                read.holder?.let { "holder: $it" },
                read.error?.let { "error: $it" },
            )
            runOnUiThread { onFrame(frame, FrameVariant.DOUBANGO, overlay) }
        } catch (e: Throwable) {
            if (debug) Log.w(LOG_TAG, "doubango frame failed: ${e.javaClass.simpleName}")
        } finally {
            proxy.close()
        }
    }

    // -------------------------------------------------------------- photos

    /*
     * Still-photo pass. A full-resolution photo gives the digits ~3x more
     * pixels than the live 1080p stream and has focus/exposure settled.
     *
     * PRIVACY: photos are delivered by CameraX straight into RAM
     * (OnImageCapturedCallback). They are never written to a file, gallery
     * or cache, so there is nothing to delete; every bitmap is recycled as
     * soon as OCR finishes.
     */
    private var imageCapture: ImageCapture? = null
    private var photoInFlight = false
    private val photoEnhancer = LumaEnhancer() // used only on photoExecutor
    private val photoLoop = Runnable { capturePhoto() }

    /** Main thread. */
    private fun capturePhoto() {
        val capture = imageCapture ?: return
        if (finished || photoInFlight || isDestroyed) return
        photoInFlight = true

        val shoot = Runnable {
            if (finished || isDestroyed) return@Runnable
            capture.takePicture(photoExecutor, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) = processPhoto(image)

                override fun onError(exception: ImageCaptureException) {
                    if (debug) Log.w(LOG_TAG, "photo capture failed: ${exception.imageCaptureError}")
                    photoDone()
                }
            })
        }
        // Refocus on the card (screen centre) right before the shot.
        val point = previewView.meteringPointFactory.createPoint(previewView.width / 2f, previewView.height / 2f)
        val focus = camera?.cameraControl?.startFocusAndMetering(
            FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF).build(),
        )
        if (focus == null) shoot.run() else focus.addListener(shoot, ContextCompat.getMainExecutor(this))
    }

    /** [photoExecutor]. Crops the card, OCRs three variants, frees everything. */
    private fun processPhoto(image: ImageProxy) {
        val bitmaps = mutableListOf<Bitmap>()
        try {
            val rotation = image.imageInfo.rotationDegrees
            val full = image.toBitmap() // decoded JPEG, unrotated
            image.close()
            bitmaps += full

            val uprightW = if (rotation % 180 == 0) full.width else full.height
            val uprightH = if (rotation % 180 == 0) full.height else full.width
            val roi = overlay.regionOfInterest(uprightW, uprightH) ?: return
            val r = LumaEnhancer.uprightRectToBuffer(
                roi.left, roi.top, roi.right, roi.bottom, full.width, full.height, rotation,
            )
            // Crop first (small), then rotate upright.
            val crop = Bitmap.createBitmap(
                full, r[0], r[1], r[2] - r[0], r[3] - r[1],
                Matrix().apply { postRotate(rotation.toFloat()) }, true,
            )
            bitmaps += crop
            full.recycle()

            val w = crop.width
            val h = crop.height
            val argb = IntArray(w * h)
            crop.getPixels(argb, 0, w, 0, 0, w, h)
            val luma = IntArray(w * h)
            LumaEnhancer.lumaFromArgb(argb, w * h, luma)
            val gray = LumaEnhancer.GrayImage(w, h, luma)
            val radius = maxOf(6, h / 20)

            fun enhanced(invert: Boolean): Bitmap {
                val out = photoEnhancer.localNormalize(gray, radius, invert)
                return Bitmap.createBitmap(photoEnhancer.toArgb(out, w * h), w, h, Bitmap.Config.ARGB_8888)
                    .also { bitmaps += it }
            }

            val inputs = listOf(
                FrameVariant.PHOTO_RAW to crop,
                FrameVariant.PHOTO_CONTRAST to enhanced(false),
                FrameVariant.PHOTO_CONTRAST_INVERTED to enhanced(true),
            )
            for ((variant, bitmap) in inputs) {
                if (finished) break
                val started = SystemClock.elapsedRealtime()
                val text = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
                val all = text.textBlocks.flatMap { it.lines }
                val lines = all.sortedBy { it.boundingBox?.top ?: 0 }.map { it.text }
                val frame = parser.parse(lines)
                if (debug) {
                    logDiagnostics(variant, SystemClock.elapsedRealtime() - started, roi, uprightW, uprightH, all.size, lines, frame)
                }
                runOnUiThread { onFrame(frame, variant, lines) }
            }
        } catch (e: Exception) {
            if (debug) Log.w(LOG_TAG, "photo OCR failed: ${e.javaClass.simpleName}")
        } finally {
            image.close() // no-op if already closed
            bitmaps.forEach { if (!it.isRecycled) it.recycle() }
            photoDone()
        }
    }

    private fun photoDone() = runOnUiThread {
        photoInFlight = false
        if (!finished && !isDestroyed) mainHandler.postDelayed(photoLoop, PHOTO_INTERVAL_MS)
    }

    // -------------------------------------------------------------- result

    private fun finishWithResult(result: ScanAggregator.Result) {
        if (finished) return
        finished = true
        val feedback = if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM
        else HapticFeedbackConstants.LONG_PRESS
        window.decorView.performHapticFeedback(feedback)
        setResult(RESULT_OK, Intent().apply {
            putExtra(RESULT_NUMBER, result.number)
            result.expiry?.let {
                putExtra(RESULT_EXPIRY_MONTH, it.month)
                putExtra(RESULT_EXPIRY_YEAR, it.year)
            }
            putExtra(RESULT_HOLDER_NAME, result.holderName)
            putExtra(RESULT_ELAPSED_MS, SystemClock.elapsedRealtime() - startedAt)
            putExtra(RESULT_FRAMES, result.framesProcessed)
        })
        finish()
    }

    private fun finishWithError(code: String, message: String) {
        if (finished) return
        finished = true
        setResult(RESULT_ERROR, Intent().apply {
            putExtra(RESULT_ERROR_CODE, code)
            putExtra(RESULT_ERROR_MESSAGE, message)
        })
        finish()
    }
}

/** Dimmed overlay with an ID-1 (85.60 × 53.98 mm) card cutout. */
private class CardFrameOverlay(context: Context) : View(context) {
    var highlight = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val density = resources.displayMetrics.density
    private val dimPaint = Paint().apply { color = Color.argb(140, 0, 0, 0) }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * density
    }

    @Volatile private var cardRect = RectF()
    @Volatile private var viewWidth = 0
    @Volatile private var viewHeight = 0

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val width = w * 0.88f
        val height = width / (85.60f / 53.98f)
        cardRect = RectF((w - width) / 2, (h - height) / 2, (w + width) / 2, (h + height) / 2)
        viewWidth = w
        viewHeight = h
    }

    override fun onDraw(canvas: Canvas) {
        val radius = 16 * density
        val path = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
            addRoundRect(cardRect, radius, radius, Path.Direction.CW)
        }
        canvas.drawPath(path, dimPaint)
        borderPaint.color = if (highlight) Color.rgb(105, 240, 174) else Color.WHITE
        canvas.drawRoundRect(cardRect, radius, radius, borderPaint)
    }

    /**
     * The card frame mapped into upright analysis-image pixels, assuming the
     * preview is FILL_CENTER (aspect fill) with the same aspect ratio as the
     * analysis stream. Enlarged by [margin] for imperfect alignment.
     */
    fun regionOfInterest(imageWidth: Int, imageHeight: Int, margin: Float = 0.08f): Rect? {
        val vw = viewWidth
        val vh = viewHeight
        val card = cardRect
        if (vw == 0 || vh == 0 || card.isEmpty) return null
        val scale = maxOf(vw.toFloat() / imageWidth, vh.toFloat() / imageHeight)
        val offsetX = (vw - imageWidth * scale) / 2
        val offsetY = (vh - imageHeight * scale) / 2
        val mx = card.width() * margin
        val my = card.height() * margin
        return Rect(
            ((card.left - mx - offsetX) / scale).toInt().coerceAtLeast(0),
            ((card.top - my - offsetY) / scale).toInt().coerceAtLeast(0),
            ((card.right + mx - offsetX) / scale).toInt().coerceAtMost(imageWidth),
            ((card.bottom + my - offsetY) / scale).toInt().coerceAtMost(imageHeight),
        )
    }
}
