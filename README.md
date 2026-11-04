# poc_card_scanner

Card scanning for Flutter, similar to Apple/Google Wallet's "add card".

- `packages/card_scanner/` is the **SDK** (Flutter plugin). iOS uses Apple Vision. Android uses CameraX + ML Kit, with Google Pay card recognition as an option.
- The root app is the **spike harness**. It's an "add card" form that can scan with each engine, including the original Flutter-only POC scanner (`lib/baseline/`) as a baseline, and export a CSV without card numbers.

Docs: [docs/PLAN.md](docs/PLAN.md) (original POC analysis), [docs/SDK_PLAN.md](docs/SDK_PLAN.md) (SDK design, status, spike steps).

```sh
flutter run --release   # needs a physical device with a camera
```
