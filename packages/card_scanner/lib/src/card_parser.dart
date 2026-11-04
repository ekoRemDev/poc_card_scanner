/// Pure-Dart parsing of OCR text into payment-card fields.
///
/// Reference implementation. The Swift (iOS) and Kotlin (Android) parsers are
/// ports of this file and must pass the same `test_vectors/*.json`.
library;

enum CardBrand {
  visa('Visa'),
  mastercard('Mastercard'),
  amex('American Express'),
  troy('Troy'),
  discover('Discover'),
  jcb('JCB'),
  dinersClub('Diners Club'),
  unionPay('UnionPay'),
  maestro('Maestro'),
  unknown('Unknown');

  const CardBrand(this.label);
  final String label;
}

class CardExpiry {
  const CardExpiry(this.month, this.year);

  final int month;

  /// Four-digit year.
  final int year;

  String get formatted =>
      '${month.toString().padLeft(2, '0')}/${(year % 100).toString().padLeft(2, '0')}';

  @override
  bool operator ==(Object other) =>
      other is CardExpiry && other.month == month && other.year == year;

  @override
  int get hashCode => Object.hash(month, year);

  @override
  String toString() => formatted;
}

/// Fields found in a single OCR frame. Any of them may be null.
class FrameParseResult {
  const FrameParseResult({this.number, this.expiry, this.holderName});

  final String? number;
  final CardExpiry? expiry;
  final String? holderName;

  bool get isEmpty => number == null && expiry == null && holderName == null;
}

class CardParser {
  CardParser({DateTime Function()? clock}) : _clock = clock ?? DateTime.now;

  final DateTime Function() _clock;

  // Characters OCR commonly confuses with digits on embossed/printed cards.
  static const Map<String, String> _digitConfusions = {
    'O': '0',
    'o': '0',
    'D': '0',
    'Q': '0',
    'U': '0',
    'I': '1',
    'l': '1',
    'i': '1',
    '|': '1',
    'L': '1',
    'T': '1',
    'Z': '2',
    'z': '2',
    'S': '5',
    's': '5',
    'G': '6',
    'b': '6',
    'B': '8',
    'g': '9',
    'q': '9',
  };

  static final RegExp _expiryRegex = RegExp(
    r'(?<!\d)(0[1-9]|1[0-2])\s?[/\\\-]\s?(\d{4}|\d{2})(?!\d)',
  );

  static final RegExp _nameRegex = RegExp(r"^[A-ZÇĞİÖŞÜ][A-ZÇĞİÖŞÜ.'\- ]+$");

  /// Words that appear on cards but are never part of the holder's name.
  static const Set<String> _nameStopWords = {
    'VISA', 'MASTERCARD', 'MASTER', 'CARD', 'AMEX', 'AMERICAN', 'EXPRESS',
    'TROY', 'MAESTRO', 'DISCOVER', 'JCB', 'UNIONPAY', 'DINERS', 'CLUB',
    'DEBIT', 'CREDIT', 'PREPAID', 'BUSINESS', 'CORPORATE', 'COMMERCIAL',
    'PLATINUM', 'GOLD', 'CLASSIC', 'STANDARD', 'WORLD', 'ELITE', 'SIGNATURE',
    'INFINITE', 'ELECTRON', 'PREMIER', 'PREMIUM', 'BLACK', 'TITANIUM',
    'VALID', 'THRU', 'FROM', 'UNTIL', 'GOOD', 'EXPIRES', 'EXPIRY', 'END',
    'MONTH', 'YEAR', 'MEMBER', 'SINCE', 'BANK', 'BANKASI', 'INTERNATIONAL',
    'CONTACTLESS', 'ELECTRONIC', 'USE', 'ONLY', 'AUTHORIZED',
    // Common Turkish card programme names.
    'KART', 'KARTI', 'BONUS', 'AXESS', 'MAXIMUM', 'PARAF', 'CARDFINANS',
    'ADVANTAGE', 'MILES', 'SMILES', 'BANKKART', 'WINGS', 'SAGLAM',
    'CUSTOMER', 'SERVICE', 'MUSTERI', 'HIZMETLERI', 'HIZMETLER',
  };

  FrameParseResult parse(List<String> lines) {
    return FrameParseResult(
      number: findCardNumber(lines),
      expiry: findExpiry(lines),
      holderName: findHolderName(lines),
    );
  }

  // ---------------------------------------------------------------------------
  // Card number
  // ---------------------------------------------------------------------------

  String? findCardNumber(List<String> lines) {
    // Try each line alone, then adjacent line pairs (OCR sometimes splits
    // the number groups across two lines).
    final candidates = <String>[
      ...lines,
      for (var i = 0; i < lines.length - 1; i++) '${lines[i]} ${lines[i + 1]}',
    ];
    for (final line in candidates) {
      final number = _extractNumber(line);
      if (number != null) return number;
    }
    return null;
  }

  /// Joins consecutive digit groups as printed ("4111 1111 1111 1111",
  /// "3782 822463 10005" or one unspaced run) and returns the longest
  /// plausible card number. Groups are never split, so random digit strings
  /// can't yield a number by slicing.
  String? _extractNumber(String line) {
    final groups = line
        .split(RegExp(r'[\s\-]+'))
        .where((t) => t.isNotEmpty)
        .map(_normalizeGroup)
        .toList();
    String? best;
    for (var i = 0; i < groups.length; i++) {
      var joined = '';
      for (var j = i; j < groups.length; j++) {
        final group = groups[j];
        if (group == null) break;
        joined += group;
        if (joined.length > 19) break;
        if (isPlausibleCardNumber(joined) &&
            (best == null || joined.length > best.length)) {
          best = joined;
        }
      }
    }
    return best;
  }

  /// Fixes OCR letter/digit confusion inside a token that already contains
  /// a digit ("S555" → "5555"). Returns null if it isn't a digit group.
  static String? _normalizeGroup(String token) {
    if (!RegExp(r'\d').hasMatch(token)) return null;
    final fixed = token.split('').map((c) => _digitConfusions[c] ?? c).join();
    return RegExp(r'^\d+$').hasMatch(fixed) ? fixed : null;
  }

  static bool isPlausibleCardNumber(String digits) {
    if (digits.length < 13 || digits.length > 19) return false;
    final brand = detectBrand(digits);
    if (brand == CardBrand.unknown) return false;
    if (!_validLengthFor(brand, digits.length)) return false;
    return luhnValid(digits);
  }

  static bool luhnValid(String digits) {
    var sum = 0;
    var doubleIt = false;
    for (var i = digits.length - 1; i >= 0; i--) {
      var d = digits.codeUnitAt(i) - 0x30;
      if (d < 0 || d > 9) return false;
      if (doubleIt) {
        d *= 2;
        if (d > 9) d -= 9;
      }
      sum += d;
      doubleIt = !doubleIt;
    }
    return sum % 10 == 0;
  }

  static CardBrand detectBrand(String digits) {
    int prefix(int n) =>
        digits.length >= n ? int.parse(digits.substring(0, n)) : -1;

    if (digits.startsWith('9792')) return CardBrand.troy;
    if (digits.startsWith('4')) return CardBrand.visa;
    final p2 = prefix(2);
    final p3 = prefix(3);
    final p4 = prefix(4);
    final p6 = prefix(6);
    if (p2 == 34 || p2 == 37) return CardBrand.amex;
    if ((p2 >= 51 && p2 <= 55) || (p6 >= 222100 && p6 <= 272099)) {
      return CardBrand.mastercard;
    }
    if (p4 == 6011 || p2 == 65 || (p3 >= 644 && p3 <= 649)) {
      return CardBrand.discover;
    }
    if (p4 >= 3528 && p4 <= 3589) return CardBrand.jcb;
    if (p2 == 36 || p2 == 38 || p2 == 39 || (p3 >= 300 && p3 <= 305)) {
      return CardBrand.dinersClub;
    }
    if (p2 == 62) return CardBrand.unionPay;
    if (p2 == 50 || (p2 >= 56 && p2 <= 69)) return CardBrand.maestro;
    return CardBrand.unknown;
  }

  static bool _validLengthFor(CardBrand brand, int length) => switch (brand) {
    CardBrand.amex => length == 15,
    CardBrand.dinersClub => length >= 14 && length <= 19,
    CardBrand.visa => length == 13 || length == 16 || length == 19,
    CardBrand.mastercard || CardBrand.troy => length == 16,
    CardBrand.maestro => length >= 12 && length <= 19,
    _ => length >= 16 && length <= 19,
  };

  // ---------------------------------------------------------------------------
  // Expiry
  // ---------------------------------------------------------------------------

  /// Returns the latest plausible MM/YY date. Cards often print both
  /// "VALID FROM" and "VALID THRU"; the expiry is the later one.
  CardExpiry? findExpiry(List<String> lines) {
    final now = _clock();
    CardExpiry? best;
    for (final line in lines) {
      final normalized = line.replaceAll(RegExp(r'[Oo]'), '0');
      for (final m in _expiryRegex.allMatches(normalized)) {
        final month = int.parse(m.group(1)!);
        var year = int.parse(m.group(2)!);
        if (year < 100) year += 2000;
        // Accept dates from 1 year ago (just-expired cards still scan) to
        // 20 years ahead.
        if (year < now.year - 1 || year > now.year + 20) continue;
        final candidate = CardExpiry(month, year);
        if (best == null ||
            candidate.year > best.year ||
            (candidate.year == best.year && candidate.month > best.month)) {
          best = candidate;
        }
      }
    }
    return best;
  }

  // ---------------------------------------------------------------------------
  // Holder name (heuristic, lowest confidence field)
  // ---------------------------------------------------------------------------

  String? findHolderName(List<String> lines) {
    for (final raw in lines) {
      final line = raw.trim().replaceAll(RegExp(r'\s+'), ' ');
      if (line.length < 5 || line.length > 26) continue;
      if (!_nameRegex.hasMatch(line)) continue;
      final words = line.split(' ');
      if (words.length < 2 || words.length > 4) continue;
      if (words.any((w) => w.length < 2)) continue;
      if (words.any((w) => _nameStopWords.contains(w.replaceAll('.', '')))) {
        continue;
      }
      return line;
    }
    return null;
  }
}
