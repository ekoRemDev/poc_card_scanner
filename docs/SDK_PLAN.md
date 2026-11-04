# Native Card Scanner SDK: Analysis & Plan

> Target market: **Georgia** (Georgian bank cards, e.g. Liberty, TBC, Bank of Georgia). Earlier drafts wrongly assumed Turkey; Turkish-specific bits (Troy brand, Turkish programme names) are harmless but not needed.

**Proposal:** a reusable card-scanning SDK.
- **iOS:** Apple Vision text recognition and a native camera screen.
- **Android:** Google Pay Payment Card Recognition, the scanner Google Wallet uses.
- **Flutter:** a thin plugin over both. The native SDKs could also be used directly by native iOS/Android apps.

Status: **SDK implemented (see section 9). Phase 0 spike is ready to run on real devices.**

Decisions taken (2026-11-04):
- Flutter apps only. One plugin repo: `packages/card_scanner`.
- Android fallback: (a) native CameraX + ML Kit.
- Android default: our own scanner. Google Pay is opt-in (`AndroidScanEngine.googlePay`) and falls back to native.
- Spike first: the root app is now the spike harness.

---

## 1. How it compares with the current POC

| | Current POC (Flutter + ML Kit) | Native SDK (Vision + Google Pay) |
|---|---|---|
| iOS OCR engine | Google ML Kit (bundled) | Apple Vision (built into iOS, free) |
| iOS app size impact | ML Kit binaries (~10–20 MB, to be measured) | ~0 MB |
| Frame path | Every camera frame copied native → Dart → native | Frames stay native; no channel traffic per frame |
| iOS accuracy | Good | Expected similar or better on iOS (Vision `.accurate` is strong on embossed digits); **must be measured** |
| Android UX | Our own screen | Google's own screen, identical to Google Wallet |
| Android fields | Number, expiry, name | **Number + expiry only** (API returns no name) |
| Swift Package Manager | ML Kit plugins don't support SPM; Flutter warned this "will become an error" | Vision is a system framework; SPM-ready |
| Reusable from native apps | No (Flutter only) | Yes, if built as native libraries |
| Code to maintain | 1 codebase (Dart) | Swift + Kotlin + Dart wrapper |

## 2. iOS: Apple Vision

**APIs**
- `VNRecognizeTextRequest` (iOS 13+), or the Swift `RecognizeTextRequest` (iOS 18+).
- Camera via `AVCaptureSession` + `AVCaptureVideoDataOutput` (BGRA/420f).
- Useful knobs:
  - `recognitionLevel`: `.fast` for the live loop, `.accurate` for confirmation frames.
  - `usesLanguageCorrection = false`, otherwise it "corrects" digit groups into words.
  - `regionOfInterest` = the card frame. This is ROI cropping for free, which the POC lacks.
  - `minimumTextHeight` to ignore small print.
  - `recognitionLanguages = ["en-US"]`. Names with Turkish characters still come through as Latin.

**Not usable**
- Apple Wallet's scanner: private.
- VisionKit `DataScannerViewController` (iOS 16+, A12+): nice live highlighting, but it has no card-number content type. You still need your own parser, and you lose control over how frames are processed. Not recommended as the core; could be a later experiment.

**Pipeline**
1. AVCapture at 1280×720, ~10–15 fps.
2. Vision runs on the card-frame region (`.fast`).
3. Card parser in Swift: number checks (brand, length, Luhn), expiry, name.
4. Voting across frames (same rules as the POC).
5. One `.accurate` pass on the best frame to confirm.
6. Haptic, then return the result.

Extras:
- Torch, tap-to-focus.
- On iPhone 13 Pro and later, a macro/`.near` focus restriction for close range.
- Blur the screen when the app goes inactive, so the card doesn't show in the app switcher.

**Minimum iOS:** 13 with the VN API. Proposed: **15.0** to match the app.

## 3. Android: Google Pay Payment Card Recognition

**API:** `play-services-wallet`.
- `PaymentsClient.getPaymentCardRecognitionIntent()` gives a `PendingIntent`.
- `PaymentCardRecognitionResult.getFromIntent()` returns `pan` + `creditCardExpirationDate`.
- Already working in the POC (`MainActivity.kt`), on `ENVIRONMENT_TEST`.

**Hard limitations (decide with these in mind)**
1. **No cardholder name.** Only number + expiry.
2. **UI isn't customisable:** Google's screen, branding and language.
3. **Needs Google Play services.** It won't work on Huawei/HMS devices or on de-Googled or some enterprise devices.
4. **Availability varies by region and device.** The intent request can fail with "unavailable". ⚠️ **Must verify for Georgia (GE) on real devices before committing.** Google doesn't publish a clear country list. (Update: the actual blocker turned out to be app registration, not region; see below.)
5. **Production environment:** check whether `ENVIRONMENT_PRODUCTION` needs a Google Pay business-console registration/approval for your app ID. It's unclear from docs; verify in the spike.
6. No metrics or callbacks while it runs. You only get the final result or a cancel.

**So a fallback on Android is mandatory.** Options:
- (a) Native CameraX + ML Kit (or unbundled Play-services ML Kit, ~0 MB) with the same Kotlin parser. Consistent native SDK, more code.
- (b) Reuse the current Flutter ML Kit scanner as the fallback. Cheapest, but Flutter-only.
- (c) No fallback: show manual entry. Simplest; some users can't scan.

## 4. Proposed SDK architecture

```
┌─────────────────────────── Flutter plugin: card_scanner ───────────────────────────┐
│ Dart API:  CardScanner.isAvailable() / CardScanner.scan(CardScanOptions)            │
│            → CardScanResult | throws CardScanException(cancelled, permissionDenied, │
│                                                         unavailable, timeout)       │
│ Pigeon-generated type-safe channel (no hand-written MethodChannel maps)             │
└───────────────┬───────────────────────────────────────────────┬─────────────────────┘
                │                                               │
   iOS: CardScannerKit (Swift Package)          Android: card-scanner (AAR / Gradle module)
   ├─ ScannerViewController (AVCapture UI)      ├─ GooglePayEngine (primary)
   ├─ VisionTextEngine                          ├─ FallbackEngine (CameraX + ML Kit) [opt (a)]
   ├─ CardParser.swift                          ├─ CardParser.kt
   └─ ScanAggregator.swift                      └─ ScanAggregator.kt
                │                                               │
                └──────── shared test vectors (JSON): OCR lines → expected fields ──┘
                         run by XCTest, JUnit and Dart tests to keep parsers identical
```

**Dart API sketch**
```dart
final result = await CardScanner.scan(const CardScanOptions(
  scanExpiry: true,
  scanHolderName: true,              // ignored by the Google Pay engine
  androidEngine: AndroidEngine.auto, // auto = Google Pay → fallback
  timeout: Duration(seconds: 30),
  strings: ScannerStrings(title: 'Kartınızı çerçeveye hizalayın'),
));
// result: number, expiry, holderName?, brand, engine (vision | googlePay | mlKit)
```

**Design rules**
- The PAN crosses the platform channel exactly once (the final result) and is never logged.
- Card brand and Luhn validation live in the parser on both platforms.
- The SDK holds no state and has no network access. Analytics go through a callback, with the PAN removed.

## 5. Distribution

| Consumer | Package |
|---|---|
| Flutter apps | Plugin via private git dependency or private pub server (OnePub / Cloudsmith) |
| Native iOS apps (optional) | Swift Package (+ CocoaPods podspec while Flutter still uses CocoaPods) |
| Native Android apps (optional) | AAR on a private Maven repo (GitHub Packages / Artifactory) |

If only Flutter apps will use it: **one Flutter plugin repo** with the native code inside. Splitting it out later is cheap.

## 6. Plan & estimates (1 developer who knows Swift + Kotlin + Flutter)

| Phase | Work | Estimate |
|---|---|---|
| **0. Spike (do first)** | (1) Is Google Pay recognition available on target devices in Georgia? (2) Vision vs ML Kit accuracy and speed on the same 20+ cards. (3) Production-environment requirements | 2–3 days |
| 1. iOS SDK | Camera UI, Vision engine, Swift parser + aggregator, ROI, torch/focus, XCTest | 6–8 days |
| 2. Android SDK | Google Pay engine hardened, fallback per decision (a: +4–5 days, b: +1, c: 0), JUnit | 3–8 days |
| 3. Flutter plugin | Pigeon API, error mapping, example app, Dart tests, shared test vectors | 3–4 days |
| 4. Hardening | Localisation (TR/EN), accessibility (VoiceOver/TalkBack), security review (PCI), docs, versioning/CI publish | 3–5 days |
| **Total** | | **~3.5–5.5 weeks** |

## 7. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| Google Pay recognition unavailable in Georgia / on many devices | Android path mostly falls back | Spike first; fallback (a) or (b) |
| Behaviour differs per platform (Android: no name, Google UI) | Product/UX inconsistency | Accept it, or use our own scanner on Android too (fallback engine as primary) |
| Two parsers drift apart | Different results per OS | Shared JSON test vectors in CI |
| Vision on older iPhones is slow at `.accurate` | Slower lock | `.fast` loop + one `.accurate` confirmation |
| Newer card designs: number on the back, vertical | Lower success rate | Orientation retry in the iOS/fallback engines; the Google Pay engine handles its own |

## 8. Decisions needed

1. **Who uses the SDK?** Flutter apps only, or native iOS/Android apps too?
2. **Android when Google Pay isn't available:** (a) native ML Kit fallback, (b) reuse the Flutter POC scanner, or (c) manual entry only?
3. **UX consistency:** is Google's own screen (no name, Google look) acceptable on Android? Or should Android use our own scanner first, with Google Pay as an option?
4. **Next step:** run the Phase 0 spike first (recommended), or go straight to building?

## 9. Implementation status

| Piece | Where | Verified |
|---|---|---|
| Dart API (`CardScanner`, options, result, errors) | `packages/card_scanner/lib/` | Unit tests with fake host API |
| Pigeon channel | `pigeons/messages.dart` → generated `Messages.g.*` | Compiles on all 3 sides |
| Reference parser (Dart) | `lib/src/card_parser.dart` | 21 shared vectors |
| iOS: Vision + AVFoundation scanner | `ios/.../ScannerViewController.swift` | Builds (SPM); XCTest on simulator: vectors, aggregator, ROI math |
| iOS parser port | `ios/.../CardParser.swift` | Same 21 vectors (XCTest) |
| Android: CameraX + ML Kit scanner | `android/.../ScannerActivity.kt` | Builds; JUnit: vectors + aggregator |
| Android: Google Pay engine + fallback | `android/.../CardScannerPlugin.kt` | Builds; **needs device test** |
| Spike harness | root app (`lib/main.dart`) | Builds on iOS + Android |

**Not verified yet (needs a real device and real cards):** camera behaviour, OCR accuracy, ROI alignment on screen, focus/zoom, Google Pay availability in Georgia.

### Deviations from the plan
- **iOS uses Vision `.accurate` on every frame** (restricted to the card frame), not `.fast` + one `.accurate` confirmation. It's simpler, and voting needs good frames. Revisit only if lock time on older iPhones is too slow.
- **Parser number extraction changed:** it now joins whole digit groups instead of slicing digit runs. A shared vector exposed that slicing could pull a valid-looking number out of a random 16-digit string.
- **AndroidX pinned** (`core-ktx` 1.17.0, `activity-ktx` 1.11.0), because newer versions force compileSdk 37 on every app that uses the plugin.
- **Android ML Kit is bundled**, not the Play-services model. The native engine is also the path for devices without Play services.

### Measured
- **iOS app size:** the Runner binary is 0.36 MB with Vision (SDK example) and 49 MB with ML Kit (harness). Uncompressed release builds; download size is smaller after App Store thinning. Dropping ML Kit on iOS is a big saving.

### Running the spike
1. `flutter run --release` on an iPhone and on 1–2 Android phones (one Samsung, one Pixel if possible).
2. On Android, the harness shows whether **Google Pay card recognition is available** on that device.
3. Scan each test card with every engine button. "Copy CSV" exports the session; it contains masked numbers only.
4. Compare against the targets in `PLAN.md` §6, then decide (a) whether to remove the Flutter baseline and (b) whether to keep Google Pay as an option.

## 10. Field feedback (2026-11-04)

- **iOS (Vision):** works as expected on a real device. The `startRunning()` crash is fixed.
- **Android (ML Kit):** couldn't read **raised (embossed) or engraved digits**. Changes:
  - Analysis resolution raised to 1080p.
  - Frames rotate through three versions: raw, local-contrast-boosted card crop, and the same crop inverted (`LumaEnhancer.kt`). Votes from all three are pooled.
  - New `debugShowRecognizedText` option (harness toggle) shows the raw OCR lines and which version was read, to diagnose cards that still fail.
  - **Not verified on a device yet.**
- Android release build: added `-dontwarn` rules for the Flutter baseline's ML Kit plugin. The SDK on its own builds in release with R8 and needs no extra rules.

### Google Pay card recognition: production needs registration (verified 2026-11-04, Pixel 8 Pro, Georgia)
- In `ENVIRONMENT_TEST` the API works, but it **always returns the dummy card 4111 1111 1111 1111**, whatever card is scanned. It's useless for real cards.
- In `ENVIRONMENT_PRODUCTION` the request fails with `10: The Application invoking the current API hasn't registered for Google card recognition API.`
- So production use needs the app (package name + release signing certificate) **registered and approved by Google**. Presumably that's done through the Google Pay & Wallet Console, but that step hasn't been checked yet. It's a business/console step that can't be done from code, and approval isn't guaranteed.
- The SDK now uses PRODUCTION and logs the rejection reason (`CardScanner` tag, no card data).

### Android ML Kit vs raised (embossed) digits: measured (2026-11-04, Pixel 8 Pro, Visa embossed)
- 60 s capture, 401 frames (134 raw, 134 contrast-boosted, 133 inverted): **card number found in 0 frames**. Expiry was found in 107.
- ML Kit *does* locate the number line, but every read is wrong. It substitutes `)`, `B`, `R`, `J` and `*` for digits and **drops 1–2 digits** (14–15 characters instead of 16), e.g. `#### ##)B# B####`.
- Contrast enhancement didn't change that. Parser letter→digit mappings can't recover dropped digits (the checksum fails), so this can't be fixed in the parser.
- Conclusion: ML Kit's general Latin model (`line_recognition_legacy_mobile`) isn't reliable on raised digits. Android needs a card-specific recognizer (e.g. a commercial SDK such as BlinkCard), or Google Pay with Google registration.
- Debug logging fix: lines with <3 digits now mask letters too (the first version leaked the cardholder name into logcat).

### Still-photo experiment (in-memory) — measured 2026-11-04
- CameraX `ImageCapture` straight into RAM (`OnImageCapturedCallback`), never written to disk. Refocus on the card, crop to the card frame, OCR raw / contrast / inverted, recycle all bitmaps.
- 15 photos at 4032×2268 (45 OCR passes): **card number found in 0**. Expiry was found in 14. Live frames in the same session: 0 of 434.
- Photos still drop digits and turn raised digits into letters (`#### ###### #####`, `#### #### I#LATH`). **Resolution isn't the bottleneck; ML Kit's model is.**
- Found and fixed along the way: R8 removed ML Kit's registrar constructors (crash on scanner open in release builds). `consumer-rules.pro` now ships with the plugin.

### Doubango ultimateCreditCard (evaluation engine): measured 2026-11-04, Pixel 8 Pro, embossed Visa
- Integrated as `AndroidScanEngine.doubango`, compiled in only with `cardScannerDoubango=true` (set in the harness `android/gradle.properties`). Files in `packages/card_scanner/android/third_party/doubango/` (non-commercial license). Frame-to-disk debug writing is forced off.
- Accuracy on embossed digits is **good**: in 60 s, 189 frames, card detected in 117, and **number read and self-validated in 93**. Expiry (`valid_thru`) and `holder_name` are read too. About 290 ms per 1080p frame.
- **But the free GitHub build masks the number**: every digit **8** is returned as `*` (the test card's shape was `###*####***####`). The model's charset contains `8`, and Doubango still reports these numbers `valid=true`, so this is a deliberate evaluation limitation, not a recognition error. The user confirmed every other digit was correct. This isn't documented in the README. We do **not** undo the masking in code (that would circumvent the license protection); our SDK therefore accepted 0 numbers.
- Pricing (doubango.org/pricing.html): **€9,700 per app (unlimited devices, 1 year support)** or €19/device (min 50).
- Next step: ask Doubango to confirm a commercial or evaluation license returns the **unmasked** number, ideally with a time-limited key to verify on our cards before buying.
