import 'package:pigeon/pigeon.dart';

@ConfigurePigeon(
  PigeonOptions(
    dartOut: 'lib/src/messages.g.dart',
    kotlinOut:
        'android/src/main/kotlin/com/example/card_scanner/Messages.g.kt',
    kotlinOptions: KotlinOptions(package: 'com.example.card_scanner'),
    swiftOut: 'ios/card_scanner/Sources/card_scanner/Messages.g.swift',
  ),
)
/// Which recognition engine performs the scan.
enum ScanEngineMessage {
  /// Platform default: Apple Vision on iOS, CameraX + ML Kit on Android.
  native,

  /// Android only: Google Pay Payment Card Recognition (Google Wallet UI).
  googlePay,

  /// Android only, evaluation: Doubango ultimateCreditCard (card-specific
  /// deep learning OCR). Only present when built with cardScannerDoubango=true.
  doubango,
}

class ScanOptionsMessage {
  ScanOptionsMessage({
    required this.engine,
    required this.fallbackToNative,
    required this.scanExpiry,
    required this.scanHolderName,
    required this.timeoutMs,
    required this.debugShowRecognizedText,
  });

  ScanEngineMessage engine;

  /// If [engine] is unavailable, use [ScanEngineMessage.native] instead.
  bool fallbackToNative;
  bool scanExpiry;
  bool scanHolderName;

  /// 0 disables the timeout.
  int timeoutMs;

  /// Development only: overlay the raw OCR text on the scanner screen.
  bool debugShowRecognizedText;
  String? title;
  String? subtitle;
}

class ScanResultMessage {
  ScanResultMessage({
    required this.number,
    required this.engine,
    required this.elapsedMs,
    required this.framesProcessed,
  });

  String number;
  int? expiryMonth;
  int? expiryYear;
  String? holderName;
  ScanEngineMessage engine;
  int elapsedMs;

  /// 0 when the engine does not expose it (Google Pay).
  int framesProcessed;
}

class AvailabilityMessage {
  AvailabilityMessage({required this.native, required this.googlePay});

  bool native;
  bool googlePay;

  /// Why Google Pay card recognition is unavailable (as reported by Google).
  String? googlePayUnavailableReason;
}

class CalibrationResultMessage {
  CalibrationResultMessage({required this.framesUsed, required this.digitsCovered});

  int framesUsed;
  int digitsCovered;

  /// File with the averaged digit shapes (no card number), if saved.
  String? filePath;
}

/// Errors are reported as PlatformException with these codes:
/// `permission_denied`, `unavailable`, `timeout`, `camera_error`, `busy`.
/// A user cancel completes with `null`.
@HostApi()
abstract class CardScannerHostApi {
  @async
  AvailabilityMessage getAvailability();

  @async
  ScanResultMessage? scan(ScanOptionsMessage options);

  /// Development only (Android): learn embossed digit shapes from a card
  /// whose number is known. Returns null if cancelled.
  @async
  CalibrationResultMessage? calibrateEmbossed(ScanOptionsMessage options, String knownNumber);
}
