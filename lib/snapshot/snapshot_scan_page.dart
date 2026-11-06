import 'dart:io';

import 'package:camera/camera.dart';
import 'package:card_scanner/card_scanner.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:google_mlkit_text_recognition/google_mlkit_text_recognition.dart';

/// Result of a snapshot scan, shown by the caller (e.g. in a SnackBar).
class SnapshotScanResult {
  const SnapshotScanResult({
    this.number,
    this.expiry,
    this.digitGroups = const [],
    required this.elapsed,
  });

  final String? number;
  final CardExpiry? expiry;

  /// Digit-bearing lines OCR found (when no valid card number was found).
  final List<String> digitGroups;
  final Duration elapsed;
}

/// Experiment: wait until the card fits the on-screen frame (its four edges
/// line up with the frame), take one full-resolution photo, OCR it, return.
///
/// PRIVACY: the camera plugin can only deliver photos as a temp file; it is
/// deleted immediately after OCR (also on errors).
class SnapshotScanPage extends StatefulWidget {
  const SnapshotScanPage({super.key});

  static Future<SnapshotScanResult?> open(BuildContext context) =>
      Navigator.of(context).push<SnapshotScanResult>(
        MaterialPageRoute(
          fullscreenDialog: true,
          builder: (_) => const SnapshotScanPage(),
        ),
      );

  @override
  State<SnapshotScanPage> createState() => _SnapshotScanPageState();
}

class _SnapshotScanPageState extends State<SnapshotScanPage> {
  static const _cardAspect = 85.60 / 53.98;
  static const _framesToCapture = 4; // aligned this many frames in a row

  final _recognizer = TextRecognizer(script: TextRecognitionScript.latin);
  final _parser = CardParser();
  final _startedAt = DateTime.now();

  CameraController? _controller;
  CameraDescription? _camera;
  Size _viewSize = Size.zero;
  bool _busy = false;
  bool _capturing = false;
  int _alignedFrames = 0;
  List<bool> _sides = const [
    false,
    false,
    false,
    false,
  ]; // top,right,bottom,left
  String _status = 'Align the card with the frame';
  String? _error;

  @override
  void initState() {
    super.initState();
    SystemChrome.setPreferredOrientations([DeviceOrientation.portraitUp]);
    _init();
  }

  @override
  void dispose() {
    SystemChrome.setPreferredOrientations([]);
    _controller?.dispose();
    _recognizer.close();
    super.dispose();
  }

  Future<void> _init() async {
    try {
      final cameras = await availableCameras();
      final back = cameras.firstWhere(
        (c) => c.lensDirection == CameraLensDirection.back,
        orElse: () => cameras.first,
      );
      final controller = CameraController(
        back,
        ResolutionPreset.veryHigh, // photos at this preset or higher
        enableAudio: false,
        imageFormatGroup: Platform.isAndroid
            ? ImageFormatGroup.nv21
            : ImageFormatGroup.bgra8888,
      );
      _camera = back;
      _controller = controller;
      await controller.initialize();
      await controller.lockCaptureOrientation(DeviceOrientation.portraitUp);
      try {
        await controller.setFocusMode(FocusMode.auto);
      } catch (_) {}
      await controller.startImageStream(_onFrame);
      if (mounted) setState(() {});
    } on CameraException catch (e) {
      setState(() => _error = 'Camera error: ${e.description ?? e.code}');
    }
  }

  // ------------------------------------------------------------ alignment

  void _onFrame(CameraImage image) {
    if (_busy || _capturing || _viewSize == Size.zero) return;
    _busy = true;
    try {
      final sides = _detectEdges(image);
      final aligned = sides.every((s) => s);
      _alignedFrames = aligned ? _alignedFrames + 1 : 0;
      if (mounted) {
        setState(() {
          _sides = sides;
          _status = aligned
              ? 'Hold still…'
              : 'Align the card with the frame (${sides.where((s) => s).length}/4 edges)';
        });
      }
      if (_alignedFrames >= _framesToCapture) _capture();
    } finally {
      _busy = false;
    }
  }

  /// For each side of the on-screen frame, looks for a strong brightness
  /// step (card edge vs background) near that side, in the camera frame.
  List<bool> _detectEdges(CameraImage image) {
    final camera = _camera;
    if (camera == null || image.planes.isEmpty) {
      return const [false, false, false, false];
    }

    final rotation = Platform.isAndroid ? camera.sensorOrientation : 0;
    final bw = image.width;
    final bh = image.height;
    final uprightW = rotation % 180 == 0 ? bw : bh;
    final uprightH = rotation % 180 == 0 ? bh : bw;
    final plane = image.planes.first;
    final bytes = plane.bytes;
    final stride = plane.bytesPerRow;
    final bgra = !Platform.isAndroid;

    int lum(int ux, int uy) {
      if (ux < 0 || uy < 0 || ux >= uprightW || uy >= uprightH) return -1;
      int bx, by;
      switch (rotation) {
        case 90:
          bx = uy;
          by = bh - 1 - ux;
        case 180:
          bx = bw - 1 - ux;
          by = bh - 1 - uy;
        case 270:
          bx = bw - 1 - uy;
          by = ux;
        default:
          bx = ux;
          by = uy;
      }
      if (bgra) {
        final i = by * stride + bx * 4;
        return (bytes[i + 2] * 77 + bytes[i + 1] * 150 + bytes[i] * 29) >> 8;
      }
      return bytes[by * stride + bx];
    }

    // On-screen frame → upright image coordinates (aspect-fill preview).
    final view = _viewSize;
    final cardW = view.width * 0.88;
    final cardH = cardW / _cardAspect;
    final scale = (view.width / uprightW) > (view.height / uprightH)
        ? view.width / uprightW
        : view.height / uprightH;
    final offX = (view.width - uprightW * scale) / 2;
    final offY = (view.height - uprightH * scale) / 2;
    final left = ((view.width - cardW) / 2 - offX) / scale;
    final right = ((view.width + cardW) / 2 - offX) / scale;
    final top = ((view.height - cardH) / 2 - offY) / scale;
    final bottom = ((view.height + cardH) / 2 - offY) / scale;
    final w = right - left;
    final h = bottom - top;

    // Search ±10% of the card size around each side; edge = luminance step.
    bool side(
      bool horizontal,
      double nominal,
      double from,
      double to,
      double band,
    ) {
      const samples = 32;
      const step = 3; // px either side of the candidate edge
      const threshold = 22;
      var hits = 0;
      for (var i = 0; i < samples; i++) {
        final along = from + (to - from) * (i + 0.5) / samples;
        var best = 0;
        for (var d = -band; d <= band; d += 2) {
          final p = nominal + d;
          final a = horizontal
              ? lum(along.round(), (p - step).round())
              : lum((p - step).round(), along.round());
          final b = horizontal
              ? lum(along.round(), (p + step).round())
              : lum((p + step).round(), along.round());
          if (a < 0 || b < 0) continue;
          final g = (a - b).abs();
          if (g > best) best = g;
        }
        if (best >= threshold) hits++;
      }
      return hits >= samples * 0.6;
    }

    final bandV = h * 0.10;
    final bandH = w * 0.10;
    return [
      side(true, top, left + w * 0.1, right - w * 0.1, bandV), // top
      side(false, right, top + h * 0.1, bottom - h * 0.1, bandH), // right
      side(true, bottom, left + w * 0.1, right - w * 0.1, bandV), // bottom
      side(false, left, top + h * 0.1, bottom - h * 0.1, bandH), // left
    ];
  }

  // ------------------------------------------------------------ capture

  Future<void> _capture() async {
    final controller = _controller;
    if (controller == null || _capturing) return;
    setState(() {
      _capturing = true;
      _status = 'Reading…';
    });
    HapticFeedback.mediumImpact();
    XFile? photo;
    try {
      if (controller.value.isStreamingImages) {
        await controller.stopImageStream();
      }
      photo = await controller.takePicture();
      final text = await _recognizer.processImage(
        InputImage.fromFilePath(photo.path),
      );
      final lines = [
        for (final block in text.blocks)
          for (final line in block.lines) line.text,
      ];
      final parsed = _parser.parse(lines);
      final groups = lines
          .where((l) => RegExp(r'\d').allMatches(l).length >= 4)
          .toList();
      if (mounted) {
        Navigator.of(context).pop(
          SnapshotScanResult(
            number: parsed.number,
            expiry: parsed.expiry,
            digitGroups: groups,
            elapsed: DateTime.now().difference(_startedAt),
          ),
        );
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          _status = 'Capture failed, try again';
          _capturing = false;
          _alignedFrames = 0;
        });
        try {
          await controller.startImageStream(_onFrame);
        } catch (_) {}
      }
    } finally {
      // Never keep a photo of the card around.
      if (photo != null) {
        try {
          await File(photo.path).delete();
        } catch (_) {}
      }
    }
  }

  // ------------------------------------------------------------ UI

  @override
  Widget build(BuildContext context) {
    final controller = _controller;
    return Scaffold(
      backgroundColor: Colors.black,
      body: LayoutBuilder(
        builder: (context, constraints) {
          _viewSize = constraints.biggest;
          return Stack(
            fit: StackFit.expand,
            children: [
              if (_error != null)
                Center(
                  child: Text(
                    _error!,
                    style: const TextStyle(color: Colors.white),
                  ),
                )
              else if (controller != null && controller.value.isInitialized)
                _CoverPreview(controller: controller)
              else
                const Center(child: CircularProgressIndicator()),
              CustomPaint(painter: _FramePainter(_sides, _cardAspect)),
              SafeArea(
                child: Column(
                  children: [
                    Align(
                      alignment: Alignment.topLeft,
                      child: IconButton(
                        icon: const Icon(Icons.close, color: Colors.white),
                        onPressed: () => Navigator.of(context).pop(),
                      ),
                    ),
                    const SizedBox(height: 16),
                    Text(
                      _status,
                      style: const TextStyle(
                        color: Colors.white,
                        fontSize: 18,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                    const Spacer(),
                    Padding(
                      padding: const EdgeInsets.only(bottom: 32),
                      child: FloatingActionButton(
                        onPressed: _capturing ? null : _capture,
                        backgroundColor: Colors.white,
                        child: _capturing
                            ? const CircularProgressIndicator()
                            : const Icon(Icons.camera_alt, color: Colors.black),
                      ),
                    ),
                  ],
                ),
              ),
            ],
          );
        },
      ),
    );
  }
}

class _CoverPreview extends StatelessWidget {
  const _CoverPreview({required this.controller});

  final CameraController controller;

  @override
  Widget build(BuildContext context) {
    final size = controller.value.previewSize;
    if (size == null) return CameraPreview(controller);
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

/// Dimmed overlay with the card frame; each side turns green when the card's
/// edge is detected there.
class _FramePainter extends CustomPainter {
  _FramePainter(this.sides, this.aspect);

  final List<bool> sides;
  final double aspect;

  @override
  void paint(Canvas canvas, Size size) {
    final w = size.width * 0.88;
    final h = w / aspect;
    final r = Rect.fromCenter(
      center: size.center(Offset.zero),
      width: w,
      height: h,
    );
    canvas.drawPath(
      Path.combine(
        PathOperation.difference,
        Path()..addRect(Offset.zero & size),
        Path()..addRRect(RRect.fromRectAndRadius(r, const Radius.circular(16))),
      ),
      Paint()..color = Colors.black.withValues(alpha: 0.5),
    );
    Paint p(bool ok) => Paint()
      ..strokeWidth = 4
      ..strokeCap = StrokeCap.round
      ..color = ok ? Colors.greenAccent : Colors.white;
    canvas.drawLine(r.topLeft, r.topRight, p(sides[0]));
    canvas.drawLine(r.topRight, r.bottomRight, p(sides[1]));
    canvas.drawLine(r.bottomLeft, r.bottomRight, p(sides[2]));
    canvas.drawLine(r.topLeft, r.bottomLeft, p(sides[3]));
  }

  @override
  bool shouldRepaint(_FramePainter old) => old.sides != sides;
}
