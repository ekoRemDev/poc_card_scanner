import 'dart:io';

import 'package:flutter/services.dart';

const nativeChannel = MethodChannel('poc_card_scanner/native');

/// Blocks screenshots / screen recording / recents thumbnails while a card
/// is on screen (Android FLAG_SECURE). No-op on iOS, where the equivalent
/// is hiding content on `inactive` lifecycle.
class SecureScreen {
  static Future<void> enable() => _set(true);
  static Future<void> disable() => _set(false);

  static Future<void> _set(bool secure) async {
    if (!Platform.isAndroid) return;
    try {
      await nativeChannel.invokeMethod('setSecure', secure);
    } on PlatformException catch (_) {}
  }
}
