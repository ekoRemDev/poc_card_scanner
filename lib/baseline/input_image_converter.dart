import 'dart:io';
import 'dart:ui' show Size;

import 'package:camera/camera.dart';
import 'package:google_mlkit_text_recognition/google_mlkit_text_recognition.dart';

/// Converts a streamed [CameraImage] into an ML Kit [InputImage].
///
/// Requires the camera to be opened with:
///  * Android: [ImageFormatGroup.nv21]   (single plane)
///  * iOS:     [ImageFormatGroup.bgra8888] (single plane)
/// The app is locked to portrait, so rotation only depends on the sensor.
InputImage? toInputImage(CameraImage image, CameraDescription camera) {
  if (image.planes.length != 1) return null;

  final InputImageRotation rotation;
  final InputImageFormat format;
  if (Platform.isAndroid) {
    rotation =
        InputImageRotationValue.fromRawValue(camera.sensorOrientation) ??
        InputImageRotation.rotation90deg;
    format = InputImageFormat.nv21;
  } else {
    rotation = InputImageRotation.rotation0deg; // ignored on iOS
    format = InputImageFormat.bgra8888;
  }

  final plane = image.planes.first;
  return InputImage.fromBytes(
    bytes: plane.bytes,
    metadata: InputImageMetadata(
      size: Size(image.width.toDouble(), image.height.toDouble()),
      rotation: rotation,
      format: format,
      bytesPerRow: plane.bytesPerRow,
    ),
  );
}
