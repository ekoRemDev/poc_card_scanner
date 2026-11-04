# Credit Card Scanning in Flutter: Analysis & POC Plan

## 1. Goal

Let users add a payment card by pointing the camera at it, with the same experience as
Apple Wallet / Google Wallet: live camera, card-shaped frame, automatic lock-on, no shutter
button, fields auto-filled (number, expiry, optionally name). CVV is never scanned.

## 2. How the native wallets do it (and what we can reuse)

| Platform | What Wallet uses | Public API for 3rd-party apps? |
|---|---|---|
| **Android** – Google Wallet | Google Pay **Payment Card Recognition** (Play services) | **Yes.** `PaymentsClient.getPaymentCardRecognitionIntent()` in `play-services-wallet`. Returns PAN + expiry. Needs Play services; availability varies by region/device. |
| **iOS** – Apple Wallet | Private card-scanner in PassKit/Wallet | **No.** Only building blocks: Vision `VNRecognizeTextRequest`, VisionKit `DataScannerViewController` (iOS 16+), and Live Text "Scan Text" on text fields. |
| Safari / AutoFill | iOS AutoFill "Scan Credit Card" | Only in Safari web forms. For native fields the POC sets `autofillHints` so saved cards can be suggested; whether a native field offers *scan* has to be checked on a device. |

**Conclusion:** You can't call one OS API on both platforms. To get the same UX everywhere,
build the scanner yourself on top of on-device OCR. On Android you can also offer
Google's native scanner where it's available.

## 3. Options evaluated

| Option | Pros | Cons | Verdict |
|---|---|---|---|
| **A. `camera` + Google ML Kit Text Recognition + custom parser** | Same code on iOS/Android, on-device, free, full UX control | Must write card-specific parsing and frame voting; ML Kit adds ~4–10 MB per platform | **Primary path** |
| B. Google Pay Card Recognition (Android) | Identical to Google Wallet, Google-maintained accuracy | Android only, needs Play services, not available in every region | **Optional add-on on Android** |
| C. Apple Vision / VisionKit via platform channel (iOS) | Native, no extra SDK size | iOS only, still needs our own parser, more native code | Phase 2 if ML Kit accuracy on iOS is not enough |
| D. Stripe CardScan / card.io / 3rd-party SDKs | Ready-made UI | card.io is dead; Stripe CardScan is tied to Stripe and is being deprecated; vendor lock-in / licensing | Rejected |
| E. Paid SDKs (Microblink BlinkCard, Anyline, …) | Best accuracy, handles flat-printed and vertical cards, front+back | Licence cost | Fallback if A doesn't meet accuracy targets |

## 4. POC architecture (implemented)

```
CameraController (720p stream, NV21 on Android / BGRA on iOS)
        │  frame dropped if previous still in OCR (natural throttling)
        ▼
input_image_converter.dart ──► ML Kit TextRecognizer (latin, on-device)
        │  text lines
        ▼
card_parser.dart        (pure Dart, unit tested)
  • PAN: 13–19 digits, OCR confusion fix (O→0, S→5, l→1 …) only on digit-heavy lines,
         line-pair joining, BIN/brand + length rules, Luhn
  • Expiry: MM/YY, MM-YYYY…, picks the latest date (VALID FROM vs THRU), plausibility window
  • Name: uppercase 2–4 words, stop-word filter (brand/tier/bank words incl. Turkish ones)
        │  FrameParseResult
        ▼
scan_aggregator.dart    (multi-frame voting)
  • PAN locks after 3 agreeing frames
  • then up to 1.5 s more to lock expiry (2 votes) / name (3 votes)
        │
        ▼
CardScanResult ──► form autofill + POC metrics (time to lock, frames processed)

Android only:  MainActivity.kt ── MethodChannel "poc_card_scanner/native"
  • gpayScanCard → Google Pay Payment Card Recognition UI → PAN + expiry
  • setSecure    → FLAG_SECURE while scanner is open (no screenshots/recents)
```

Brands detected: Visa, Mastercard (incl. 2-series), Amex, **Troy (9792)**, Discover, JCB,
Diners, UnionPay, Maestro.

## 5. Security / compliance notes (PCI DSS)

- All processing is on-device; frames are never saved or uploaded.
- Never log the full PAN. Use `CardScanResult.maskedNumber`.
- CVV is never scanned (and can't legally be stored).
- Android: FLAG_SECURE on the scanner screen. iOS (production): hide sensitive content on
  `AppLifecycleState.inactive` so it doesn't appear in the app-switcher snapshot.
- Production: once you have the PAN, tokenize it with your PSP right away rather than
  keeping it in app state longer than needed.

## 6. How to evaluate the POC

Test set: at least 20 real cards across these groups:
- embossed vs flat-printed, light vs dark backgrounds, glossy/metal
- **number on the back / vertical cards** (common on new Visa/Mastercard designs)
- Troy, Amex, Mastercard 2-series

Metrics, read from the "Last scan" panel:

| Metric | Target |
|---|---|
| PAN correct on first lock | ≥ 95% |
| Median time to lock | ≤ 2 s |
| Expiry found | ≥ 85% |
| Name found | best effort (≥ 60%) |
| False PAN (wrong number accepted) | 0 in the test set |

Run the same cards through the Google Pay native scanner on Android to compare.

## 7. Known limitations / next steps

1. **ROI cropping**: OCR currently runs on the whole frame. Cropping to the on-screen card
   frame should cut latency and false positives.
2. **Vertical cards**: the app is locked to portrait and ML Kit handles mild rotation, but
   fully vertical cards may need a second pass with the image rotated 90°.
3. **Name extraction** is heuristic. You could match it against the user's profile name instead.
4. **Focus**: some Android devices need tap-to-focus or a manual macro focus distance.
5. **iOS native Vision** (option C) as an A/B test against ML Kit on iOS.
6. **App size**: measure the ML Kit impact (Android: consider the unbundled model from Play
   services `play-services-mlkit-text-recognition` to save ~4 MB).
7. Production hardening: package the scanner as an internal Flutter package with a
   `Future<CardScanResult?> scanCard()` API, plus analytics on time-to-lock and
   abandonment rate (without PAN).
