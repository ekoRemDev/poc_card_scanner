import 'package:card_scanner/card_scanner.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:poc_card_scanner/baseline/scan_aggregator.dart';
import 'package:poc_card_scanner/scan_record.dart';

/// Parser tests live in packages/card_scanner (shared vectors). These cover
/// the baseline aggregator and the PAN-free spike report.
void main() {
  test('baseline aggregator locks after agreeing frames', () {
    var now = DateTime(2026);
    final agg = ScanAggregator(clock: () => now);
    const good = FrameParseResult(
      number: '4111111111111111',
      expiry: CardExpiry(3, 2029),
      holderName: 'AYSE YILMAZ',
    );
    expect(agg.add(good), isNull);
    expect(agg.add(good), isNull);
    final record = agg.add(good);
    expect(record?.number, '4111111111111111');
    expect(record?.holderName, 'AYSE YILMAZ');
  });

  test('CSV report never contains the full card number', () {
    final row = ScanRecord(
      engine: 'test',
      number: '4111111111111111',
      expiry: const CardExpiry(3, 2029),
      elapsed: const Duration(milliseconds: 1234),
    ).toCsvRow();
    expect(row, isNot(contains('4111111111111111')));
    expect(row, contains('4111 •••• •••• 1111'));
    expect(row, contains('1234'));
  });
}
