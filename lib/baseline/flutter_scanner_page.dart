import 'dart:io';

import 'package:camera/camera.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:google_mlkit_text_recognition/google_mlkit_text_recognition.dart';

import 'package:card_scanner/card_scanner.dart';

import '../scan_record.dart';
import 'input_image_converter.dart';
import 'scan_aggregator.dart';
import 'secure_screen.dart';

/// The original Flutter-only POC scanner, kept as the baseline to compare
/// the native SDK against. Pops with a [ScanRecord] or null.
class FlutterScannerPage extends StatefulWidget {
  const FlutterScannerPage({super.key});

  static Future<ScanRecord?> open(BuildContext context) =>
      Navigator.of(context).push<ScanRecord>(
        MaterialPageRoute(
          fullscreenDialog: true,
          builder: (_) => const FlutterScannerPage(),
        ),
      );

  @override
  State<FlutterScannerPage> createState() => _FlutterScannerPageState();
}

class _FlutterScannerPageState extends State<FlutterScannerPage>
    with WidgetsBindingObserver {
  final _recognizer = TextRecognizer(script: TextRecognitionScript.latin);
  final _parser = CardParser();
  ScanAggregator _aggregator = ScanAggregator();

  CameraController? _controller;
  bool _busy = false;
  bool _done = false;
  bool _torchOn = false;
  String? _error;
  String? _liveNumber; // masked, for feedback only

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    SystemChrome.setPreferredOrientations([DeviceOrientation.portraitUp]);
    SecureScreen.enable();
    _initCamera();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    SystemChrome.setPreferredOrientations([]);
    SecureScreen.disable();
    _disposeCamera();
    _recognizer.close();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.inactive ||
        state == AppLifecycleState.paused) {
      _disposeCamera();
    } else if (state == AppLifecycleState.resumed && _controller == null) {
      _initCamera();
    }
  }

  Future<void> _initCamera() async {
    try {
      final cameras = await availableCameras();
      final back = cameras.firstWhere(
        (c) => c.lensDirection == CameraLensDirection.back,
        orElse: () => cameras.first,
      );
      final controller = CameraController(
        back,
        // 720p is enough for card text and keeps OCR latency low.
        ResolutionPreset.high,
        enableAudio: false,
        imageFormatGroup: Platform.isAndroid
            ? ImageFormatGroup.nv21
            : ImageFormatGroup.bgra8888,
      );
      _controller = controller;
      await controller.initialize();
      await controller.lockCaptureOrientation(DeviceOrientation.portraitUp);
      try {
        await controller.setFocusMode(FocusMode.auto);
      } catch (_) {
        /* not supported on every device */
      }
      await controller.startImageStream((img) => _onFrame(img, back));
      if (mounted) setState(() {});
    } on CameraException catch (e) {
      setState(
        () => _error = e.code == 'CameraAccessDenied'
            ? 'Camera permission denied. Enable it in Settings.'
            : 'Camera error: ${e.description ?? e.code}',
      );
    }
  }

  Future<void> _disposeCamera() async {
    final c = _controller;
    _controller = null;
    _torchOn = false;
    if (c == null) return;
    try {
      if (c.value.isStreamingImages) await c.stopImageStream();
    } catch (_) {}
    await c.dispose();
  }

  /// Drops frames while the previous one is still in OCR, which naturally
  /// throttles to whatever the device can handle (~5-15 fps).
  Future<void> _onFrame(CameraImage image, CameraDescription camera) async {
    if (_busy || _done) return;
    _busy = true;
    try {
      final input = toInputImage(image, camera);
      if (input == null) return;
      final text = await _recognizer.processImage(input);
      final lines = [
        for (final block in text.blocks)
          for (final line in block.lines) line.text,
      ];
      final frame = _parser.parse(lines);
      final result = _aggregator.add(frame);

      final leading = _aggregator.leadingNumber;
      final masked = leading == null ? null : CardScanResult.maskPan(leading);
      if (mounted && masked != _liveNumber) {
        setState(() => _liveNumber = masked);
      }
      if (result != null) _finish(result);
    } catch (e) {
      debugPrint('OCR frame failed: $e');
    } finally {
      _busy = false;
    }
  }

  void _finish(ScanRecord result) {
    if (_done) return;
    _done = true;
    HapticFeedback.mediumImpact();
    if (mounted) Navigator.of(context).pop(result);
  }

  Future<void> _toggleTorch() async {
    final c = _controller;
    if (c == null || !c.value.isInitialized) return;
    try {
      await c.setFlashMode(_torchOn ? FlashMode.off : FlashMode.torch);
      setState(() => _torchOn = !_torchOn);
    } catch (_) {}
  }

  void _restart() => setState(() {
    _aggregator = ScanAggregator();
    _liveNumber = null;
  });

  @override
  Widget build(BuildContext context) {
    final controller = _controller;
    final locked = _aggregator.lockedNumber != null;

    return Scaffold(
      backgroundColor: Colors.black,
      body: Stack(
        fit: StackFit.expand,
        children: [
          if (_error != null)
            Center(
              child: Padding(
                padding: const EdgeInsets.all(32),
                child: Text(
                  _error!,
                  textAlign: TextAlign.center,
                  style: const TextStyle(color: Colors.white),
                ),
              ),
            )
          else if (controller != null && controller.value.isInitialized)
            _CoverPreview(controller: controller)
          else
            const Center(child: CircularProgressIndicator()),
          _CardFrameOverlay(highlight: locked || _liveNumber != null),
          SafeArea(
            child: Column(
              children: [
                Row(
                  children: [
                    IconButton(
                      icon: const Icon(Icons.close, color: Colors.white),
                      onPressed: () => Navigator.of(context).pop(),
                    ),
                    const Spacer(),
                    IconButton(
                      icon: Icon(
                        _torchOn ? Icons.flash_on : Icons.flash_off,
                        color: Colors.white,
                      ),
                      onPressed: _toggleTorch,
                    ),
                  ],
                ),
                const SizedBox(height: 24),
                const Text(
                  'Position your card in the frame',
                  style: TextStyle(
                    color: Colors.white,
                    fontSize: 18,
                    fontWeight: FontWeight.w600,
                  ),
                ),
                const SizedBox(height: 6),
                const Text(
                  'Scanning happens on this device. Nothing is uploaded.',
                  style: TextStyle(color: Colors.white70, fontSize: 13),
                ),
                const Spacer(),
                AnimatedOpacity(
                  opacity: _liveNumber == null ? 0 : 1,
                  duration: const Duration(milliseconds: 200),
                  child: Text(
                    _liveNumber ?? '',
                    style: const TextStyle(
                      color: Colors.white,
                      fontSize: 20,
                      letterSpacing: 2,
                      fontFeatures: [FontFeature.tabularFigures()],
                    ),
                  ),
                ),
                TextButton(
                  onPressed: _restart,
                  child: const Text(
                    'Restart',
                    style: TextStyle(color: Colors.white70),
                  ),
                ),
                const SizedBox(height: 24),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

/// Camera preview scaled to cover the screen (like the native scanners).
class _CoverPreview extends StatelessWidget {
  const _CoverPreview({required this.controller});

  final CameraController controller;

  @override
  Widget build(BuildContext context) {
    final size = controller.value.previewSize;
    if (size == null) return CameraPreview(controller);
    // previewSize is reported in landscape; swap for portrait.
    return ClipRect(
      child: FittedBox(
        fit: BoxFit.cover,
        child: SizedBox(
          width: size.height,
          height: size.width,
          child: CameraPreview(controller),
        ),
      ),
    );
  }
}

/// Dimmed overlay with an ISO/IEC 7810 ID-1 shaped cutout (85.60 × 53.98 mm).
class _CardFrameOverlay extends StatelessWidget {
  const _CardFrameOverlay({required this.highlight});

  final bool highlight;
  static const cardAspectRatio = 85.60 / 53.98;

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) {
        final width = constraints.maxWidth * 0.88;
        final height = width / cardAspectRatio;
        final rect = Rect.fromCenter(
          center: constraints.biggest.center(Offset.zero),
          width: width,
          height: height,
        );
        return CustomPaint(
          painter: _OverlayPainter(
            rect,
            highlight ? Colors.greenAccent : Colors.white,
          ),
        );
      },
    );
  }
}

class _OverlayPainter extends CustomPainter {
  _OverlayPainter(this.cardRect, this.borderColor);

  final Rect cardRect;
  final Color borderColor;

  @override
  void paint(Canvas canvas, Size size) {
    final rrect = RRect.fromRectAndRadius(cardRect, const Radius.circular(16));
    final background = Path()..addRect(Offset.zero & size);
    final hole = Path()..addRRect(rrect);
    canvas.drawPath(
      Path.combine(PathOperation.difference, background, hole),
      Paint()..color = Colors.black.withValues(alpha: 0.55),
    );
    canvas.drawRRect(
      rrect,
      Paint()
        ..style = PaintingStyle.stroke
        ..strokeWidth = 3
        ..color = borderColor,
    );
  }

  @override
  bool shouldRepaint(_OverlayPainter old) =>
      old.cardRect != cardRect || old.borderColor != borderColor;
}
