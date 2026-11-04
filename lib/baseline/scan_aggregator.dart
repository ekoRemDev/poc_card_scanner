import 'package:card_scanner/card_scanner.dart';

import '../scan_record.dart';

/// Accumulates per-frame OCR results and decides when a scan is "locked".
///
/// A single OCR frame is noisy (a 6 read as 8 can still pass Luhn ~10% of
/// the time), so each field is voted on across frames. This is the same idea
/// the native Wallet scanners use: keep scanning until the reading is stable.
class ScanAggregator {
  ScanAggregator({
    this.numberVotesRequired = 3,
    this.expiryVotesRequired = 2,
    this.nameVotesRequired = 3,
    this.extraFieldsGrace = const Duration(milliseconds: 1500),
    DateTime Function()? clock,
  }) : _clock = clock ?? DateTime.now;

  final int numberVotesRequired;
  final int expiryVotesRequired;
  final int nameVotesRequired;

  /// After the number locks, keep scanning this long for expiry/name.
  final Duration extraFieldsGrace;

  final DateTime Function() _clock;

  final Map<String, int> _numberVotes = {};
  final Map<CardExpiry, int> _expiryVotes = {};
  final Map<String, int> _nameVotes = {};

  late final DateTime _startedAt = _clock();
  DateTime? _numberLockedAt;
  int framesProcessed = 0;

  String? get lockedNumber => _winner(_numberVotes, numberVotesRequired);
  CardExpiry? get lockedExpiry => _winner(_expiryVotes, expiryVotesRequired);
  String? get lockedName => _winner(_nameVotes, nameVotesRequired);

  /// Best guess so far, even if not locked (for live UI feedback).
  String? get leadingNumber => _leader(_numberVotes);

  /// Feeds one frame. Returns a final result once the scan is complete.
  ScanRecord? add(FrameParseResult frame) {
    framesProcessed++;
    if (frame.number != null) _vote(_numberVotes, frame.number!);
    if (frame.expiry != null) _vote(_expiryVotes, frame.expiry!);
    if (frame.holderName != null) _vote(_nameVotes, frame.holderName!);

    final number = lockedNumber;
    if (number == null) return null;
    _numberLockedAt ??= _clock();

    final allFieldsLocked = lockedExpiry != null && lockedName != null;
    final graceOver = _clock().difference(_numberLockedAt!) >= extraFieldsGrace;
    if (!allFieldsLocked && !graceOver) return null;

    return ScanRecord(
      engine: 'Flutter baseline: camera + ML Kit plugin',
      number: number,
      expiry: lockedExpiry ?? _leader(_expiryVotes),
      holderName: lockedName,
      elapsed: _clock().difference(_startedAt),
      framesProcessed: framesProcessed,
    );
  }

  static void _vote<T>(Map<T, int> votes, T value) =>
      votes[value] = (votes[value] ?? 0) + 1;

  static T? _leader<T>(Map<T, int> votes) {
    if (votes.isEmpty) return null;
    return votes.entries.reduce((a, b) => b.value > a.value ? b : a).key;
  }

  static T? _winner<T>(Map<T, int> votes, int required) {
    final leader = _leader(votes);
    if (leader == null || votes[leader]! < required) return null;
    return leader;
  }
}
