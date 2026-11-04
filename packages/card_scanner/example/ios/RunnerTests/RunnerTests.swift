import XCTest

@testable import card_scanner

/// Runs the shared `test_vectors/card_parser_vectors.json` (also used by
/// the Dart and Kotlin parsers) plus iOS-specific geometry tests.
class RunnerTests: XCTestCase {

  private func loadVectors() throws -> [String: Any] {
    let url = URL(fileURLWithPath: #filePath)
      .deletingLastPathComponent()  // RunnerTests
      .appendingPathComponent("../../../test_vectors/card_parser_vectors.json")
      .standardized
    let data = try Data(contentsOf: url)
    return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
  }

  func testSharedParserVectors() throws {
    let json = try loadVectors()
    let formatter = DateFormatter()
    formatter.dateFormat = "yyyy-MM-dd"
    let reference = try XCTUnwrap(formatter.date(from: json["referenceDate"] as! String))
    let parser = CardParser(now: { reference })

    let vectors = try XCTUnwrap(json["vectors"] as? [[String: Any]])
    XCTAssertGreaterThan(vectors.count, 0)
    for v in vectors {
      let name = v["description"] as! String
      let expected = v["expected"] as! [String: Any]
      let result = parser.parse(v["lines"] as! [String])
      let expiry = result.expiry.map { String(format: "%02d/%d", $0.month, $0.year) }
      XCTAssertEqual(result.number, expected["number"] as? String, "\(name): number")
      XCTAssertEqual(expiry, expected["expiry"] as? String, "\(name): expiry")
      XCTAssertEqual(result.holderName, expected["holderName"] as? String, "\(name): name")
    }
  }

  func testAggregatorLocksAfterAgreeingFrames() {
    let aggregator = ScanAggregator(now: { Date(timeIntervalSince1970: 0) })
    let good = FrameParseResult(
      number: "4111111111111111", expiry: CardExpiry(month: 3, year: 2029),
      holderName: "AYSE YILMAZ")
    XCTAssertNil(aggregator.add(good))
    XCTAssertNil(aggregator.add(FrameParseResult(number: "4111111111111129")))
    XCTAssertNil(aggregator.add(good))
    let result = aggregator.add(good)
    XCTAssertEqual(result?.number, "4111111111111111")
    XCTAssertEqual(result?.expiry, CardExpiry(month: 3, year: 2029))
    XCTAssertEqual(result?.holderName, "AYSE YILMAZ")
  }

  func testAggregatorDoesNotWaitForUnrequestedFields() {
    let aggregator = ScanAggregator(now: { Date(timeIntervalSince1970: 0) })
    aggregator.wantsExpiry = false
    aggregator.wantsName = false
    let frame = FrameParseResult(number: "4111111111111111")
    XCTAssertNil(aggregator.add(frame))
    XCTAssertNil(aggregator.add(frame))
    XCTAssertEqual(aggregator.add(frame)?.number, "4111111111111111")
  }

  func testRegionOfInterestMatchesCenteredCardFrame() {
    // 390x844 pt screen, 720x1280 portrait buffer, aspect fill.
    let view = CGSize(width: 390, height: 844)
    let width = 390 * 0.88
    let height = width / (85.60 / 53.98)
    let card = CGRect(
      x: (390 - width) / 2, y: (844 - height) / 2, width: width, height: height)
    let roi = ScannerViewController.regionOfInterest(
      cardRect: card, viewSize: view, imageSize: CGSize(width: 720, height: 1280), margin: 0)

    // Buffer is wider than the visible area (sides cropped), so the card
    // covers less than 88% of the image width; it's vertically centered.
    XCTAssertEqual(roi.midX, 0.5, accuracy: 0.001)
    XCTAssertEqual(roi.midY, 0.5, accuracy: 0.001)
    let scale = 844.0 / 1280.0  // height-limited aspect fill
    XCTAssertEqual(roi.width, width / (720 * scale), accuracy: 0.001)
    XCTAssertEqual(roi.height, height / 844, accuracy: 0.001)
  }
}
