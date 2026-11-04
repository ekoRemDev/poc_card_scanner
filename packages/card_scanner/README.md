# card_scanner

On-device payment card scanner for Flutter.

| Platform | Engine | Returns |
|---|---|---|
| iOS 15+ | Apple Vision + AVFoundation (native screen) | number, expiry, name |
| Android (default) | CameraX + ML Kit (native screen) | number, expiry, name |
| Android (opt-in) | Google Pay Payment Card Recognition | number, expiry |

```dart
final scanner = CardScanner();
try {
  final result = await scanner.scan(const CardScanOptions(
    androidEngine: AndroidScanEngine.native, // or .googlePay (falls back to native)
    timeout: Duration(seconds: 60),
  ));
  if (result != null) {
    print(result.maskedNumber); // never log result.number
  }
} on CardScanException catch (e) {
  // e.code: permissionDenied, unavailable, timeout, cameraError, busy
}
```

## Setup in the consuming app
- **iOS:** add `NSCameraUsageDescription` to `Info.plist`.
- **Android:** nothing. The plugin declares `CAMERA` and its scanner activity.

## Security
- Processing happens only on the device. The full card number crosses the platform channel once, in the final result.
- Android uses FLAG_SECURE on the scanner screen. iOS blurs the screen when the app becomes inactive.
- The CVV is never scanned.

## Development
- API changes: edit `pigeons/messages.dart`, then run `dart run pigeon --input pigeons/messages.dart`.
- The parser exists three times (Dart reference, Swift, Kotlin). All three must pass `test_vectors/card_parser_vectors.json`:
  - Dart: `flutter test`
  - Kotlin: `cd example/android && ./gradlew :card_scanner:testDebugUnitTest`
  - Swift: `cd example && xcodebuild test -workspace ios/Runner.xcworkspace -scheme Runner -destination 'platform=iOS Simulator,name=<iPhone>' -only-testing:RunnerTests`
