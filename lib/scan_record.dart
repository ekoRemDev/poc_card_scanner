import 'package:card_scanner/card_scanner.dart';

/// One scan in the spike session, from any engine (SDK or Flutter baseline).
class ScanRecord {
  ScanRecord({
    required this.engine,
    required this.number,
    required this.elapsed,
    this.expiry,
    this.holderName,
    this.framesProcessed,
  }) : at = DateTime.now();

  factory ScanRecord.fromSdk(CardScanResult r) => ScanRecord(
    engine: 'SDK: ${r.engine.label}',
    number: r.number,
    expiry: r.expiry,
    holderName: r.holderName,
    elapsed: r.elapsed,
    framesProcessed: r.framesProcessed,
  );

  final String engine;

  /// Kept in memory only for filling the form; never exported or logged.
  final String number;
  final CardExpiry? expiry;
  final String? holderName;
  final Duration elapsed;
  final int? framesProcessed;
  final DateTime at;

  CardBrand get brand => CardParser.detectBrand(number);
  String get maskedNumber => CardScanResult.maskPan(number);

  static const csvHeader =
      'time,engine,brand,masked_number,expiry,name_found,elapsed_ms,frames';

  /// PAN-free row for the spike report.
  String toCsvRow() => [
    at.toIso8601String(),
    engine,
    brand.label,
    maskedNumber,
    expiry?.formatted ?? '',
    holderName != null ? 'yes' : 'no',
    elapsed.inMilliseconds,
    framesProcessed ?? '',
  ].join(',');
}
