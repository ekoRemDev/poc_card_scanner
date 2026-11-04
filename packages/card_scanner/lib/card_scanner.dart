/// On-device payment card scanner.
///
/// * iOS: Apple Vision text recognition + native camera UI.
/// * Android: CameraX + ML Kit (default) or Google Pay Card Recognition.
library;

export 'src/card_parser.dart'
    show CardBrand, CardExpiry, CardParser, FrameParseResult;
export 'src/card_scanner_api.dart';
