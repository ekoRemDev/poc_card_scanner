import AVFoundation
import Flutter
import UIKit

public class CardScannerPlugin: NSObject, FlutterPlugin, CardScannerHostApi {
  private weak var activeScanner: ScannerViewController?

  public static func register(with registrar: FlutterPluginRegistrar) {
    CardScannerHostApiSetup.setUp(
      binaryMessenger: registrar.messenger(), api: CardScannerPlugin())
  }

  func getAvailability() async throws -> AvailabilityMessage {
    AvailabilityMessage(
      native: AVCaptureDevice.default(for: .video) != nil,
      googlePay: false)
  }

  func scan(options: ScanOptionsMessage) async throws -> ScanResultMessage? {
    if options.engine != .native && !options.fallbackToNative {
      throw PigeonError(
        code: "unavailable", message: "This engine is Android only",
        details: nil)
    }
    guard await Self.cameraPermissionGranted() else {
      throw PigeonError(code: "permission_denied", message: "Camera access denied", details: nil)
    }

    var config = ScannerViewController.Config()
    if let title = options.title { config.title = title }
    if let subtitle = options.subtitle { config.subtitle = subtitle }
    if options.timeoutMs > 0 { config.timeout = TimeInterval(options.timeoutMs) / 1000 }
    config.wantsExpiry = options.scanExpiry
    config.wantsName = options.scanHolderName
    config.debugShowText = options.debugShowRecognizedText

    let outcome = try await present(config)
    switch outcome {
    case .cancelled:
      return nil
    case .failure(let code, let message):
      throw PigeonError(code: code, message: message, details: nil)
    case .success(let s):
      return ScanResultMessage(
        number: s.number,
        expiryMonth: s.expiry.map { Int64($0.month) },
        expiryYear: s.expiry.map { Int64($0.year) },
        holderName: s.holderName,
        engine: .native,
        elapsedMs: Int64(s.elapsed * 1000),
        framesProcessed: Int64(s.framesProcessed))
    }
  }

  @MainActor
  private func present(_ config: ScannerViewController.Config) async throws
    -> ScannerViewController.Outcome
  {
    guard activeScanner == nil else {
      throw PigeonError(code: "busy", message: "A scan is already in progress", details: nil)
    }
    guard let presenter = Self.topViewController() else {
      throw PigeonError(code: "camera_error", message: "No view controller to present from", details: nil)
    }
    return await withCheckedContinuation { continuation in
      let scanner = ScannerViewController(config: config)
      scanner.completion = { continuation.resume(returning: $0) }
      activeScanner = scanner
      presenter.present(scanner, animated: true)
    }
  }

  private static func cameraPermissionGranted() async -> Bool {
    switch AVCaptureDevice.authorizationStatus(for: .video) {
    case .authorized: return true
    case .notDetermined: return await AVCaptureDevice.requestAccess(for: .video)
    default: return false
    }
  }

  @MainActor
  private static func topViewController() -> UIViewController? {
    let window = UIApplication.shared.connectedScenes
      .compactMap { $0 as? UIWindowScene }
      .flatMap(\.windows)
      .first(where: \.isKeyWindow)
    var top = window?.rootViewController
    while let presented = top?.presentedViewController { top = presented }
    return top
  }
}
