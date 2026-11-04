import 'package:card_scanner/card_scanner.dart';
import 'package:card_scanner/src/messages.g.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

class _FakeApi implements CardScannerHostApi {
  ScanOptionsMessage? lastOptions;
  ScanResultMessage? result;
  PlatformException? error;

  @override
  Future<ScanResultMessage?> scan(ScanOptionsMessage options) async {
    lastOptions = options;
    if (error != null) throw error!;
    return result;
  }

  @override
  Future<AvailabilityMessage> getAvailability() async =>
      AvailabilityMessage(native: true, googlePay: false);

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

void main() {
  test('maps result and options', () async {
    final api = _FakeApi()
      ..result = ScanResultMessage(
        number: '4111111111111111',
        engine: ScanEngineMessage.googlePay,
        elapsedMs: 900,
        framesProcessed: 0,
        expiryMonth: 3,
        expiryYear: 2029,
        holderName: 'IGNORED NAME',
      );
    final result = await CardScanner(hostApi: api).scan(
      const CardScanOptions(
        androidEngine: AndroidScanEngine.googlePay,
        scanHolderName: false,
        timeout: Duration(seconds: 20),
      ),
    );
    expect(api.lastOptions!.engine, ScanEngineMessage.googlePay);
    expect(api.lastOptions!.timeoutMs, 20000);
    expect(result!.brand, CardBrand.visa);
    expect(result.engine, ScanEngine.googlePay);
    expect(result.expiry, const CardExpiry(3, 2029));
    expect(result.holderName, isNull);
    expect(result.framesProcessed, isNull);
    expect(result.maskedNumber, '4111 •••• •••• 1111');
  });

  test('cancel returns null', () async {
    expect(await CardScanner(hostApi: _FakeApi()).scan(), isNull);
  });

  test('maps platform errors', () async {
    final api = _FakeApi()
      ..error = PlatformException(code: 'permission_denied');
    expect(
      () => CardScanner(hostApi: api).scan(),
      throwsA(
        isA<CardScanException>().having(
          (e) => e.code,
          'code',
          CardScanErrorCode.permissionDenied,
        ),
      ),
    );
  });

  test('formats Amex as 4-6-5', () {
    expect(CardScanResult.formatPan('378282246310005'), '3782 822463 10005');
  });
}
