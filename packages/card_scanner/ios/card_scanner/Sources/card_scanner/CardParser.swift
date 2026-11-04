import Foundation

/// Swift port of `lib/src/card_parser.dart` (the reference implementation).
/// Must pass `test_vectors/card_parser_vectors.json`.
struct CardExpiry: Hashable {
  let month: Int
  /// Four-digit year.
  let year: Int
}

struct FrameParseResult {
  var number: String?
  var expiry: CardExpiry?
  var holderName: String?
}

struct CardParser {
  var now: () -> Date = Date.init

  private static let digitConfusions: [Character: Character] = [
    "O": "0", "o": "0", "D": "0", "Q": "0", "U": "0",
    "I": "1", "l": "1", "i": "1", "|": "1", "L": "1", "T": "1",
    "Z": "2", "z": "2",
    "S": "5", "s": "5",
    "G": "6", "b": "6",
    "B": "8",
    "g": "9", "q": "9",
  ]

  private static let expiryRegex = try! NSRegularExpression(
    pattern: #"(?<!\d)(0[1-9]|1[0-2])\s?[/\\\-]\s?(\d{4}|\d{2})(?!\d)"#)
  private static let nameRegex = try! NSRegularExpression(
    pattern: #"^[A-ZÇĞİÖŞÜ][A-ZÇĞİÖŞÜ.'\- ]+$"#)
  private static let separatorRegex = try! NSRegularExpression(pattern: #"[\s\-]+"#)

  private static let nameStopWords: Set<String> = [
    "VISA", "MASTERCARD", "MASTER", "CARD", "AMEX", "AMERICAN", "EXPRESS",
    "TROY", "MAESTRO", "DISCOVER", "JCB", "UNIONPAY", "DINERS", "CLUB",
    "DEBIT", "CREDIT", "PREPAID", "BUSINESS", "CORPORATE", "COMMERCIAL",
    "PLATINUM", "GOLD", "CLASSIC", "STANDARD", "WORLD", "ELITE", "SIGNATURE",
    "INFINITE", "ELECTRON", "PREMIER", "PREMIUM", "BLACK", "TITANIUM",
    "VALID", "THRU", "FROM", "UNTIL", "GOOD", "EXPIRES", "EXPIRY", "END",
    "MONTH", "YEAR", "MEMBER", "SINCE", "BANK", "BANKASI", "INTERNATIONAL",
    "CONTACTLESS", "ELECTRONIC", "USE", "ONLY", "AUTHORIZED",
    "KART", "KARTI", "BONUS", "AXESS", "MAXIMUM", "PARAF", "CARDFINANS",
    "ADVANTAGE", "MILES", "SMILES", "BANKKART", "WINGS", "SAGLAM",
    "CUSTOMER", "SERVICE", "MUSTERI", "HIZMETLERI", "HIZMETLER",
  ]

  func parse(_ lines: [String]) -> FrameParseResult {
    FrameParseResult(
      number: findCardNumber(lines),
      expiry: findExpiry(lines),
      holderName: findHolderName(lines))
  }

  // MARK: - Card number

  func findCardNumber(_ lines: [String]) -> String? {
    var candidates = lines
    if lines.count > 1 {
      for i in 0..<(lines.count - 1) { candidates.append(lines[i] + " " + lines[i + 1]) }
    }
    for line in candidates {
      if let number = extractNumber(line) { return number }
    }
    return nil
  }

  private func extractNumber(_ line: String) -> String? {
    let ns = line as NSString
    let marked = Self.separatorRegex.stringByReplacingMatches(
      in: line, range: NSRange(location: 0, length: ns.length), withTemplate: " ")
    let groups = marked.split(separator: " ").map { Self.normalizeGroup(String($0)) }

    var best: String?
    for i in groups.indices {
      var joined = ""
      for j in i..<groups.count {
        guard let group = groups[j] else { break }
        joined += group
        if joined.count > 19 { break }
        if Self.isPlausibleCardNumber(joined), joined.count > (best?.count ?? 0) {
          best = joined
        }
      }
    }
    return best
  }

  private static func normalizeGroup(_ token: String) -> String? {
    guard token.contains(where: { $0.isASCII && $0.isNumber }) else { return nil }
    let fixed = String(token.map { digitConfusions[$0] ?? $0 })
    return fixed.allSatisfy { $0.isASCII && $0.isNumber } ? fixed : nil
  }

  static func isPlausibleCardNumber(_ digits: String) -> Bool {
    guard (13...19).contains(digits.count) else { return false }
    let brand = CardBrand.detect(digits)
    guard brand != .unknown, brand.isValidLength(digits.count) else { return false }
    return luhnValid(digits)
  }

  static func luhnValid(_ digits: String) -> Bool {
    var sum = 0
    var doubleIt = false
    for c in digits.reversed() {
      guard var d = c.wholeNumberValue, c.isASCII else { return false }
      if doubleIt {
        d *= 2
        if d > 9 { d -= 9 }
      }
      sum += d
      doubleIt.toggle()
    }
    return sum % 10 == 0
  }

  // MARK: - Expiry

  func findExpiry(_ lines: [String]) -> CardExpiry? {
    let currentYear = Calendar(identifier: .gregorian).component(.year, from: now())
    var best: CardExpiry?
    for line in lines {
      let normalized = line.replacingOccurrences(of: "O", with: "0")
        .replacingOccurrences(of: "o", with: "0")
      let ns = normalized as NSString
      let matches = Self.expiryRegex.matches(
        in: normalized, range: NSRange(location: 0, length: ns.length))
      for m in matches {
        guard let month = Int(ns.substring(with: m.range(at: 1))),
          var year = Int(ns.substring(with: m.range(at: 2)))
        else { continue }
        if year < 100 { year += 2000 }
        if year < currentYear - 1 || year > currentYear + 20 { continue }
        let candidate = CardExpiry(month: month, year: year)
        if let b = best,
          candidate.year < b.year || (candidate.year == b.year && candidate.month <= b.month)
        {
          continue
        }
        best = candidate
      }
    }
    return best
  }

  // MARK: - Holder name

  func findHolderName(_ lines: [String]) -> String? {
    for raw in lines {
      let line = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        .split(whereSeparator: { $0.isWhitespace }).joined(separator: " ")
      guard (5...26).contains(line.count) else { continue }
      let ns = line as NSString
      guard Self.nameRegex.firstMatch(
        in: line, range: NSRange(location: 0, length: ns.length)) != nil
      else { continue }
      let words = line.split(separator: " ").map(String.init)
      guard (2...4).contains(words.count) else { continue }
      if words.contains(where: { $0.count < 2 }) { continue }
      if words.contains(where: {
        Self.nameStopWords.contains($0.replacingOccurrences(of: ".", with: ""))
      }) { continue }
      return line
    }
    return nil
  }
}

enum CardBrand {
  case visa, mastercard, amex, troy, discover, jcb, dinersClub, unionPay, maestro, unknown

  static func detect(_ digits: String) -> CardBrand {
    func prefix(_ n: Int) -> Int { digits.count >= n ? Int(digits.prefix(n)) ?? -1 : -1 }
    if digits.hasPrefix("9792") { return .troy }
    if digits.hasPrefix("4") { return .visa }
    let p2 = prefix(2), p3 = prefix(3), p4 = prefix(4), p6 = prefix(6)
    if p2 == 34 || p2 == 37 { return .amex }
    if (51...55).contains(p2) || (222100...272099).contains(p6) { return .mastercard }
    if p4 == 6011 || p2 == 65 || (644...649).contains(p3) { return .discover }
    if (3528...3589).contains(p4) { return .jcb }
    if [36, 38, 39].contains(p2) || (300...305).contains(p3) { return .dinersClub }
    if p2 == 62 { return .unionPay }
    if p2 == 50 || (56...69).contains(p2) { return .maestro }
    return .unknown
  }

  func isValidLength(_ length: Int) -> Bool {
    switch self {
    case .amex: return length == 15
    case .dinersClub: return (14...19).contains(length)
    case .visa: return [13, 16, 19].contains(length)
    case .mastercard, .troy: return length == 16
    case .maestro: return (12...19).contains(length)
    default: return (16...19).contains(length)
    }
  }
}

/// Port of `ScanAggregator` from the Flutter POC: votes across frames and
/// decides when the scan is locked.
final class ScanAggregator {
  var numberVotesRequired = 3
  var expiryVotesRequired = 2
  var nameVotesRequired = 3
  var extraFieldsGrace: TimeInterval = 1.5
  var wantsExpiry = true
  var wantsName = true

  private var numberVotes: [String: Int] = [:]
  private var expiryVotes: [CardExpiry: Int] = [:]
  private var nameVotes: [String: Int] = [:]
  private var numberLockedAt: Date?
  private let now: () -> Date
  private(set) var framesProcessed = 0

  init(now: @escaping () -> Date = Date.init) { self.now = now }

  var leadingNumber: String? { Self.leader(numberVotes) }
  var lockedNumber: String? { Self.winner(numberVotes, numberVotesRequired) }

  struct Result {
    let number: String
    let expiry: CardExpiry?
    let holderName: String?
    let framesProcessed: Int
  }

  /// Returns a result once the scan is complete.
  func add(_ frame: FrameParseResult) -> Result? {
    framesProcessed += 1
    if let n = frame.number { numberVotes[n, default: 0] += 1 }
    if wantsExpiry, let e = frame.expiry { expiryVotes[e, default: 0] += 1 }
    if wantsName, let h = frame.holderName { nameVotes[h, default: 0] += 1 }

    guard let number = lockedNumber else { return nil }
    if numberLockedAt == nil { numberLockedAt = now() }

    let expiry = Self.winner(expiryVotes, expiryVotesRequired)
    let name = Self.winner(nameVotes, nameVotesRequired)
    let allLocked = (!wantsExpiry || expiry != nil) && (!wantsName || name != nil)
    let graceOver = now().timeIntervalSince(numberLockedAt!) >= extraFieldsGrace
    guard allLocked || graceOver else { return nil }

    return Result(
      number: number,
      expiry: expiry ?? Self.leader(expiryVotes),
      holderName: name,
      framesProcessed: framesProcessed)
  }

  private static func leader<T>(_ votes: [T: Int]) -> T? {
    votes.max(by: { $0.value < $1.value })?.key
  }

  private static func winner<T>(_ votes: [T: Int], _ required: Int) -> T? {
    guard let l = leader(votes), votes[l]! >= required else { return nil }
    return l
  }
}
