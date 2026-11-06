import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'card_parser.dart';
import 'messages.g.dart';

/// Engine used on Android. iOS always uses Apple Vision.
enum AndroidScanEngine {
  /// Our own scanner: CameraX + ML Kit text recognition. Works on every
  /// device with a camera (no Google Play services needed).
  native,

  /// Google Pay Payment Card Recognition — the scanner Google Wallet uses.
  /// Needs Google Play services and isn't available in every region.
  /// Returns number + expiry only (never the holder name).
  googlePay,

  /// **Evaluation only.** Doubango ultimateCreditCard: a card-specific deep
  /// learning model (handles embossed digits). Non-commercial license; only
  /// available when the app is built with `cardScannerDoubango=true` in
  /// `android/gradle.properties`, otherwise throws
  /// [CardScanErrorCode.unavailable].
  doubango,
}

/// The engine that actually produced a result.
enum ScanEngine {
  appleVision('Apple Vision'),
  mlKit('CameraX + ML Kit'),
  googlePay('Google Pay Card Recognition'),
  doubango('Doubango ultimateCreditCard');

  const ScanEngine(this.label);
  final String label;
}

class CardScanOptions {
  const CardScanOptions({
    this.androidEngine = AndroidScanEngine.native,
    this.fallbackToNative = true,
    this.scanExpiry = true,
    this.scanHolderName = true,
    this.timeout,
    this.title,
    this.subtitle,
    this.debugShowRecognizedText = false,
  });

  final AndroidScanEngine androidEngine;

  /// When [androidEngine] is [AndroidScanEngine.googlePay] and it's
  /// unavailable, open the native scanner instead of throwing.
  final bool fallbackToNative;
  final bool scanExpiry;
  final bool scanHolderName;

  /// Closes the scanner with [CardScanErrorCode.timeout]. Null = no timeout.
  /// Not applied to the Google Pay UI.
  final Duration? timeout;

  /// Overrides the default English texts on the native scanner screen.
  final String? title;
  final String? subtitle;

  /// **Development only.** Shows the raw OCR text (which can include the full
  /// card number) on the scanner screen, to diagnose cards that won't scan.
  /// It's displayed only, never logged. Keep false in production builds.
  final bool debugShowRecognizedText;
}

class CardScanResult {
  const CardScanResult({
    required this.number,
    required this.brand,
    required this.engine,
    required this.elapsed,
    this.expiry,
    this.holderName,
    this.framesProcessed,
  });

  /// Digits only. Treat as sensitive: never log it, tokenize it ASAP.
  final String number;
  final CardExpiry? expiry;
  final String? holderName;
  final CardBrand brand;
  final ScanEngine engine;

  /// Time from opening the scanner to the result.
  final Duration elapsed;

  /// Null when the engine doesn't expose it (Google Pay).
  final int? framesProcessed;

  /// Safe to show or log, e.g. `4111 •••• •••• 1111`.
  String get maskedNumber => maskPan(number);

  /// Grouped as printed on the card (4-6-5 for Amex, 4-4-4-4 otherwise).
  String get formattedNumber => formatPan(number);

  static String maskPan(String pan) => pan.length < 8
      ? '••••'
      : '${pan.substring(0, 4)} •••• •••• ${pan.substring(pan.length - 4)}';

  static String formatPan(String pan) {
    final groups = pan.length == 15 ? [4, 6, 5] : [4, 4, 4, 4, 3];
    final out = <String>[];
    var i = 0;
    for (final g in groups) {
      if (i >= pan.length) break;
      final end = (i + g).clamp(0, pan.length);
      out.add(pan.substring(i, end));
      i = end;
    }
    return out.join(' ');
  }

  @override
  String toString() =>
      'CardScanResult($maskedNumber, ${expiry ?? '-'}, '
      '${engine.name})';
}

enum CardScanErrorCode {
  permissionDenied,

  /// The requested engine can't run on this device/region.
  unavailable,
  timeout,
  cameraError,

  /// Another scan is already open.
  busy,
  unknown,
}

class CardScanException implements Exception {
  const CardScanException(this.code, [this.message]);

  final CardScanErrorCode code;
  final String? message;

  @override
  String toString() => 'CardScanException(${code.name}: ${message ?? ''})';
}

class CalibrationResult {
  const CalibrationResult({
    required this.framesUsed,
    required this.digitsCovered,
    this.filePath,
  });

  final int framesUsed;

  /// How many of the digits 0–9 the calibration card contained.
  final int digitsCovered;
  final String? filePath;
}

class ScannerAvailability {
  const ScannerAvailability({
    required this.native,
    required this.googlePay,
    this.googlePayUnavailableReason,
  });

  /// A camera exists (and, on iOS, Vision is available).
  final bool native;

  /// Android only: Google Pay card recognition can be launched.
  final bool googlePay;

  /// Google's reason when [googlePay] is false, e.g. the app isn't
  /// registered for the card recognition API (production access needed).
  final String? googlePayUnavailableReason;
}

/// Entry point of the SDK.
///
/// ```dart
/// final result = await CardScanner().scan();
/// if (result != null) print(result.maskedNumber);
/// ```
class CardScanner {
  CardScanner({@visibleForTesting CardScannerHostApi? hostApi})
    : _api = hostApi ?? CardScannerHostApi();

  final CardScannerHostApi _api;

  Future<ScannerAvailability> availability() async {
    final m = await _guard(_api.getAvailability);
    return ScannerAvailability(
      native: m.native,
      googlePay: m.googlePay,
      googlePayUnavailableReason: m.googlePayUnavailableReason,
    );
  }

  /// Opens the scanner. Returns null if the user cancels.
  /// Throws [CardScanException].
  Future<CardScanResult?> scan([
    CardScanOptions options = const CardScanOptions(),
  ]) async {
    final message = await _guard(
      () => _api.scan(
        ScanOptionsMessage(
          engine: switch (options.androidEngine) {
            AndroidScanEngine.native => ScanEngineMessage.native,
            AndroidScanEngine.googlePay => ScanEngineMessage.googlePay,
            AndroidScanEngine.doubango => ScanEngineMessage.doubango,
          },
          fallbackToNative: options.fallbackToNative,
          scanExpiry: options.scanExpiry,
          scanHolderName: options.scanHolderName,
          timeoutMs: options.timeout?.inMilliseconds ?? 0,
          debugShowRecognizedText: options.debugShowRecognizedText,
          title: options.title,
          subtitle: options.subtitle,
        ),
      ),
    );
    if (message == null) return null;

    final month = message.expiryMonth;
    final year = message.expiryYear;
    return CardScanResult(
      number: message.number,
      brand: CardParser.detectBrand(message.number),
      engine: switch (message.engine) {
        ScanEngineMessage.googlePay => ScanEngine.googlePay,
        ScanEngineMessage.doubango => ScanEngine.doubango,
        ScanEngineMessage.native =>
          Platform.isIOS ? ScanEngine.appleVision : ScanEngine.mlKit,
      },
      expiry: options.scanExpiry && month != null && year != null
          ? CardExpiry(month, year)
          : null,
      holderName: options.scanHolderName ? message.holderName : null,
      elapsed: Duration(milliseconds: message.elapsedMs),
      framesProcessed: message.framesProcessed > 0
          ? message.framesProcessed
          : null,
    );
  }

  /// **Development only (Android).** Opens the scanner in calibration mode:
  /// scan an embossed card whose [knownNumber] you enter, and the SDK saves
  /// the average shape of each digit (no card number) to a file, to be
  /// merged into the SDK's built-in embossed digit shapes.
  /// Returns null if cancelled.
  Future<CalibrationResult?> calibrateEmbossed(String knownNumber) async {
    final digits = knownNumber.replaceAll(RegExp(r'\D'), '');
    if (digits.length != 16 || !CardParser.luhnValid(digits)) {
      throw ArgumentError('Enter a valid 16-digit card number');
    }
    final m = await _guard(
      () => _api.calibrateEmbossed(
        ScanOptionsMessage(
          engine: ScanEngineMessage.native,
          fallbackToNative: true,
          scanExpiry: false,
          scanHolderName: false,
          timeoutMs: 0,
          debugShowRecognizedText: false,
          title: 'Calibration: hold the embossed card steady',
          subtitle: 'Learning digit shapes. Nothing is uploaded.',
        ),
        digits,
      ),
    );
    if (m == null) return null;
    return CalibrationResult(
      framesUsed: m.framesUsed,
      digitsCovered: m.digitsCovered,
      filePath: m.filePath,
    );
  }

  static Future<T> _guard<T>(Future<T> Function() call) async {
    try {
      return await call();
    } on PlatformException catch (e) {
      throw CardScanException(_codeFrom(e.code), e.message);
    }
  }

  static CardScanErrorCode _codeFrom(String code) => switch (code) {
    'permission_denied' => CardScanErrorCode.permissionDenied,
    'unavailable' => CardScanErrorCode.unavailable,
    'timeout' => CardScanErrorCode.timeout,
    'camera_error' => CardScanErrorCode.cameraError,
    'busy' => CardScanErrorCode.busy,
    _ => CardScanErrorCode.unknown,
  };
}
