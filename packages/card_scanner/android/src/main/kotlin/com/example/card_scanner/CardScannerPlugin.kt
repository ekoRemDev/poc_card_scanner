package com.example.card_scanner

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.wallet.PaymentCardRecognitionIntentRequest
import com.google.android.gms.wallet.PaymentCardRecognitionResult
import com.google.android.gms.wallet.PaymentsClient
import com.google.android.gms.wallet.Wallet
import com.google.android.gms.wallet.WalletConstants
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.PluginRegistry
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class CardScannerPlugin :
    FlutterPlugin, ActivityAware, CardScannerHostApi, PluginRegistry.ActivityResultListener {

    private companion object {
        const val REQUEST_NATIVE_SCAN = 0xCA01
        const val REQUEST_GOOGLE_PAY_SCAN = 0xCA02
    }

    private var binding: ActivityPluginBinding? = null
    private var pending: CancellableContinuation<ScanResultMessage?>? = null
    private var googlePayStartedAt = 0L
    /** Engine running inside ScannerActivity for the pending scan. */
    private var activityEngine = ScanEngineMessage.NATIVE

    // ----------------------------------------------------------- lifecycle

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        CardScannerHostApi.setUp(flutterPluginBinding.binaryMessenger, this)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        CardScannerHostApi.setUp(binding.binaryMessenger, null)
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        this.binding = binding
        binding.addActivityResultListener(this)
    }

    override fun onDetachedFromActivity() {
        binding?.removeActivityResultListener(this)
        binding = null
    }

    override fun onDetachedFromActivityForConfigChanges() = onDetachedFromActivity()

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) =
        onAttachedToActivity(binding)

    private fun activity(): Activity =
        binding?.activity ?: throw FlutterError("camera_error", "Plugin is not attached to an activity")

    // ----------------------------------------------------------- host API

    override suspend fun getAvailability(): AvailabilityMessage {
        val activity = activity()
        val hasCamera = activity.packageManager
            .hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_ANY)
        return AvailabilityMessage(native = hasCamera, googlePay = googlePayIntentOrNull(activity) != null)
    }

    override suspend fun scan(options: ScanOptionsMessage): ScanResultMessage? {
        if (pending != null) throw FlutterError("busy", "A scan is already in progress")
        val activity = activity()

        if (options.engine == ScanEngineMessage.GOOGLE_PAY) {
            val pendingIntent = googlePayIntentOrNull(activity)
            if (pendingIntent != null) {
                return awaitResult {
                    googlePayStartedAt = SystemClock.elapsedRealtime()
                    activity.startIntentSenderForResult(
                        pendingIntent.intentSender, REQUEST_GOOGLE_PAY_SCAN, null, 0, 0, 0,
                    )
                }
            }
            if (!options.fallbackToNative) {
                throw FlutterError("unavailable", "Google Pay card recognition is unavailable on this device")
            }
        }

        activityEngine =
            if (options.engine == ScanEngineMessage.DOUBANGO) ScanEngineMessage.DOUBANGO else ScanEngineMessage.NATIVE
        return awaitResult {
            activity.startActivityForResult(ScannerActivity.intent(activity, options), REQUEST_NATIVE_SCAN)
        }
    }

    private suspend fun awaitResult(launch: () -> Unit): ScanResultMessage? =
        suspendCancellableCoroutine { continuation ->
            pending = continuation
            continuation.invokeOnCancellation { pending = null }
            try {
                launch()
            } catch (e: Exception) {
                pending = null
                continuation.resumeWithException(FlutterError("camera_error", e.message))
            }
        }

    // ----------------------------------------------------------- results

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        val result = when (requestCode) {
            REQUEST_NATIVE_SCAN -> runCatching { nativeResult(resultCode, data) }
            REQUEST_GOOGLE_PAY_SCAN -> runCatching { googlePayResult(resultCode, data) }
            else -> return false
        }
        val continuation = pending ?: return true
        pending = null
        result.fold(continuation::resume, continuation::resumeWithException)
        return true
    }

    private fun nativeResult(resultCode: Int, data: Intent?): ScanResultMessage? = when (resultCode) {
        Activity.RESULT_OK -> data?.let {
            val month = it.getIntExtra(ScannerActivity.RESULT_EXPIRY_MONTH, 0)
            val year = it.getIntExtra(ScannerActivity.RESULT_EXPIRY_YEAR, 0)
            ScanResultMessage(
                number = it.getStringExtra(ScannerActivity.RESULT_NUMBER)!!,
                expiryMonth = month.takeIf { m -> m > 0 }?.toLong(),
                expiryYear = year.takeIf { y -> y > 0 }?.toLong(),
                holderName = it.getStringExtra(ScannerActivity.RESULT_HOLDER_NAME),
                engine = activityEngine,
                elapsedMs = it.getLongExtra(ScannerActivity.RESULT_ELAPSED_MS, 0),
                framesProcessed = it.getIntExtra(ScannerActivity.RESULT_FRAMES, 0).toLong(),
            )
        }
        ScannerActivity.RESULT_ERROR -> throw FlutterError(
            data?.getStringExtra(ScannerActivity.RESULT_ERROR_CODE) ?: "camera_error",
            data?.getStringExtra(ScannerActivity.RESULT_ERROR_MESSAGE),
        )
        else -> null // cancelled
    }

    private fun googlePayResult(resultCode: Int, data: Intent?): ScanResultMessage? {
        if (resultCode != Activity.RESULT_OK || data == null) return null
        val card = PaymentCardRecognitionResult.getFromIntent(data) ?: return null
        val expiry = card.creditCardExpirationDate
        return ScanResultMessage(
            number = card.pan,
            expiryMonth = expiry?.month?.toLong(),
            expiryYear = expiry?.year?.toLong(),
            holderName = null, // not provided by the API
            engine = ScanEngineMessage.GOOGLE_PAY,
            elapsedMs = SystemClock.elapsedRealtime() - googlePayStartedAt,
            framesProcessed = 0,
        )
    }

    // ----------------------------------------------------------- Google Pay

    private var paymentsClient: PaymentsClient? = null

    /**
     * The only reliable availability check is asking for the intent: it fails
     * on unsupported regions/devices even when Play services are present.
     */
    private suspend fun googlePayIntentOrNull(activity: Activity) = try {
        val playServices = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(activity)
        if (playServices != ConnectionResult.SUCCESS) {
            android.util.Log.w("CardScanner", "Google Play services unavailable: status $playServices")
            null
        } else {
            val client = paymentsClient ?: Wallet.getPaymentsClient(
                activity,
                Wallet.WalletOptions.Builder()
                    // Must be PRODUCTION: in ENVIRONMENT_TEST, card recognition
                    // returns a dummy test card (4111 1111 1111 1111) instead of
                    // the scanned card.
                    .setEnvironment(WalletConstants.ENVIRONMENT_PRODUCTION)
                    .build(),
            ).also { paymentsClient = it }
            client.getPaymentCardRecognitionIntent(
                PaymentCardRecognitionIntentRequest.getDefaultInstance(),
            ).await().paymentCardRecognitionPendingIntent
        }
    } catch (e: Exception) {
        // No card data here; the reason is what developers need to see
        // (e.g. app not enabled for Google Pay production, region, device).
        android.util.Log.w("CardScanner", "Google Pay card recognition unavailable: $e")
        null
    }
}
