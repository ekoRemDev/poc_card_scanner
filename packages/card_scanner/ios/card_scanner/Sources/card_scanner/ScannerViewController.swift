import AVFoundation
import UIKit
import Vision

/// Full-screen card scanner: AVFoundation camera + Apple Vision OCR.
///
/// Frames are processed on a background queue; while Vision is busy the
/// capture output drops late frames, which throttles naturally.
final class ScannerViewController: UIViewController {
  struct Config {
    var title = "Position your card in the frame"
    var subtitle = "Scanning happens on this device. Nothing is uploaded."
    var timeout: TimeInterval?
    var wantsExpiry = true
    var wantsName = true
    /// Development only: show raw OCR lines on screen.
    var debugShowText = false
  }

  struct ScanOutput {
    let number: String
    let expiry: CardExpiry?
    let holderName: String?
    let elapsed: TimeInterval
    let framesProcessed: Int
  }

  enum Outcome {
    case success(ScanOutput)
    case cancelled
    case failure(code: String, message: String)
  }

  /// Called exactly once, on the main thread, after dismissal starts.
  var completion: ((Outcome) -> Void)?

  private let config: Config
  private let startedAt = Date()
  private let session = AVCaptureSession()
  private let sessionQueue = DispatchQueue(label: "card_scanner.session")
  private let videoQueue = DispatchQueue(label: "card_scanner.video", qos: .userInitiated)
  private var device: AVCaptureDevice?

  private let parser = CardParser()
  private let aggregator = ScanAggregator()
  private let textRequest: VNRecognizeTextRequest = {
    let request = VNRecognizeTextRequest()
    request.recognitionLevel = .accurate
    // Language correction "fixes" digit groups into words; we want raw text.
    request.usesLanguageCorrection = false
    request.recognitionLanguages = ["en-US"]
    return request
  }()

  // Geometry shared between main thread (layout) and video queue (ROI).
  private let geometryLock = NSLock()
  private var cardRectInView: CGRect = .zero
  private var viewSize: CGSize = .zero

  private var finished = false
  private var torchOn = false
  private var timeoutWork: DispatchWorkItem?

  // UI
  private let previewLayer = AVCaptureVideoPreviewLayer()
  private let dimLayer = CAShapeLayer()
  private let borderLayer = CAShapeLayer()
  private let titleLabel = UILabel()
  private let subtitleLabel = UILabel()
  private let liveNumberLabel = UILabel()
  private let debugLabel = UILabel()
  private let closeButton = UIButton(type: .system)
  private let torchButton = UIButton(type: .system)
  private var privacyCover: UIView?

  private static let cardAspectRatio: CGFloat = 85.60 / 53.98

  init(config: Config) {
    self.config = config
    super.init(nibName: nil, bundle: nil)
    modalPresentationStyle = .fullScreen
    aggregator.wantsExpiry = config.wantsExpiry
    aggregator.wantsName = config.wantsName
  }

  @available(*, unavailable)
  required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

  override var supportedInterfaceOrientations: UIInterfaceOrientationMask { .portrait }
  override var preferredStatusBarStyle: UIStatusBarStyle { .lightContent }

  // MARK: - Lifecycle

  override func viewDidLoad() {
    super.viewDidLoad()
    view.backgroundColor = .black
    buildUI()
    sessionQueue.async { [weak self] in self?.startSession() }

    let nc = NotificationCenter.default
    nc.addObserver(
      self, selector: #selector(appWillResignActive),
      name: UIApplication.willResignActiveNotification, object: nil)
    nc.addObserver(
      self, selector: #selector(appDidBecomeActive),
      name: UIApplication.didBecomeActiveNotification, object: nil)

    if let timeout = config.timeout {
      let work = DispatchWorkItem { [weak self] in
        self?.finish(.failure(code: "timeout", message: "No card recognised in time"))
      }
      timeoutWork = work
      DispatchQueue.main.asyncAfter(deadline: .now() + timeout, execute: work)
    }
  }

  override func viewDidLayoutSubviews() {
    super.viewDidLayoutSubviews()
    previewLayer.frame = view.bounds

    let width = view.bounds.width * 0.88
    let card = CGRect(
      x: (view.bounds.width - width) / 2,
      y: (view.bounds.height - width / Self.cardAspectRatio) / 2,
      width: width,
      height: width / Self.cardAspectRatio)

    let rounded = UIBezierPath(roundedRect: card, cornerRadius: 16)
    let dim = UIBezierPath(rect: view.bounds)
    dim.append(rounded)
    dimLayer.frame = view.bounds
    dimLayer.path = dim.cgPath
    borderLayer.path = rounded.cgPath

    let safeTop = view.safeAreaInsets.top
    closeButton.frame = CGRect(x: 8, y: safeTop + 4, width: 44, height: 44)
    torchButton.frame = CGRect(x: view.bounds.width - 52, y: safeTop + 4, width: 44, height: 44)
    let labelWidth = view.bounds.width - 48
    titleLabel.frame = CGRect(x: 24, y: safeTop + 64, width: labelWidth, height: 26)
    subtitleLabel.frame = CGRect(x: 24, y: safeTop + 94, width: labelWidth, height: 40)
    liveNumberLabel.frame = CGRect(x: 24, y: card.maxY + 24, width: labelWidth, height: 30)
    let debugHeight: CGFloat = 140
    debugLabel.frame = CGRect(
      x: 0, y: view.bounds.height - view.safeAreaInsets.bottom - debugHeight,
      width: view.bounds.width, height: debugHeight)

    geometryLock.lock()
    cardRectInView = card
    viewSize = view.bounds.size
    geometryLock.unlock()
  }

  override func viewWillDisappear(_ animated: Bool) {
    super.viewWillDisappear(animated)
    // Swipe-to-dismiss or any other dismissal counts as cancel.
    finish(.cancelled, dismiss: false)
  }

  deinit {
    NotificationCenter.default.removeObserver(self)
  }

  // MARK: - UI

  private func buildUI() {
    previewLayer.session = session
    previewLayer.videoGravity = .resizeAspectFill
    view.layer.addSublayer(previewLayer)

    dimLayer.fillRule = .evenOdd
    dimLayer.fillColor = UIColor.black.withAlphaComponent(0.55).cgColor
    view.layer.addSublayer(dimLayer)

    borderLayer.fillColor = UIColor.clear.cgColor
    borderLayer.strokeColor = UIColor.white.cgColor
    borderLayer.lineWidth = 3
    view.layer.addSublayer(borderLayer)

    titleLabel.text = config.title
    titleLabel.font = .systemFont(ofSize: 18, weight: .semibold)
    subtitleLabel.text = config.subtitle
    subtitleLabel.font = .systemFont(ofSize: 13)
    subtitleLabel.numberOfLines = 2
    liveNumberLabel.font = .monospacedDigitSystemFont(ofSize: 20, weight: .regular)
    liveNumberLabel.alpha = 0
    for (label, alpha) in [(titleLabel, 1.0), (subtitleLabel, 0.7), (liveNumberLabel, 1.0)] {
      label.textColor = UIColor.white.withAlphaComponent(alpha)
      label.textAlignment = .center
      view.addSubview(label)
    }

    closeButton.setImage(UIImage(systemName: "xmark"), for: .normal)
    closeButton.accessibilityLabel = "Close"
    closeButton.addTarget(self, action: #selector(closeTapped), for: .touchUpInside)
    torchButton.setImage(UIImage(systemName: "bolt.slash.fill"), for: .normal)
    torchButton.accessibilityLabel = "Torch"
    torchButton.addTarget(self, action: #selector(torchTapped), for: .touchUpInside)
    for button in [closeButton, torchButton] {
      button.tintColor = .white
      view.addSubview(button)
    }

    if config.debugShowText {
      debugLabel.numberOfLines = 0
      debugLabel.font = .monospacedSystemFont(ofSize: 11, weight: .regular)
      debugLabel.textColor = .yellow
      debugLabel.backgroundColor = UIColor.black.withAlphaComponent(0.6)
      view.addSubview(debugLabel)
    }

    view.addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(tapToFocus)))
  }

  private func showLive(number: String?) {
    let text = number.map(Self.mask)
    guard liveNumberLabel.text != text else { return }
    liveNumberLabel.text = text
    borderLayer.strokeColor = (text == nil ? UIColor.white : UIColor.systemGreen).cgColor
    UIView.animate(withDuration: 0.2) { self.liveNumberLabel.alpha = text == nil ? 0 : 1 }
  }

  private static func mask(_ pan: String) -> String {
    pan.count < 8 ? "••••" : "\(pan.prefix(4)) •••• •••• \(pan.suffix(4))"
  }

  // MARK: - Camera

  /// Runs on [sessionQueue]. `startRunning()` must only be called after
  /// `commitConfiguration()`; calling it inside the configuration block
  /// throws an Objective-C exception and crashes the app.
  private func startSession() {
    if let error = configureSession() {
      DispatchQueue.main.async {
        self.finish(.failure(code: "camera_error", message: error))
      }
      return
    }
    session.startRunning()
  }

  /// Returns an error message, or nil on success.
  private func configureSession() -> String? {
    session.beginConfiguration()
    defer { session.commitConfiguration() }

    if session.canSetSessionPreset(.hd1280x720) { session.sessionPreset = .hd1280x720 }

    // Virtual multi-camera devices switch to the ultra-wide (macro) lens at
    // close range on Pro iPhones, which helps with small card text.
    let discovery = AVCaptureDevice.DiscoverySession(
      deviceTypes: [.builtInTripleCamera, .builtInDualWideCamera, .builtInWideAngleCamera],
      mediaType: .video, position: .back)
    guard let device = discovery.devices.first,
      let input = try? AVCaptureDeviceInput(device: device),
      session.canAddInput(input)
    else {
      return "No back camera available"
    }
    self.device = device
    session.addInput(input)

    let output = AVCaptureVideoDataOutput()
    output.alwaysDiscardsLateVideoFrames = true
    output.videoSettings = [
      kCVPixelBufferPixelFormatTypeKey as String:
        kCVPixelFormatType_420YpCbCr8BiPlanarFullRange
    ]
    output.setSampleBufferDelegate(self, queue: videoQueue)
    guard session.canAddOutput(output) else { return "Camera output not supported" }
    session.addOutput(output)

    // Deliver upright portrait buffers so ROI math needs no rotation.
    if let connection = output.connection(with: .video) {
      Self.setPortrait(connection)
    }
    DispatchQueue.main.async {
      if let connection = self.previewLayer.connection { Self.setPortrait(connection) }
    }

    configureFocus(device)
    return nil
  }

  private static func setPortrait(_ connection: AVCaptureConnection) {
    if #available(iOS 17.0, *) {
      if connection.isVideoRotationAngleSupported(90) { connection.videoRotationAngle = 90 }
    } else if connection.isVideoOrientationSupported {
      connection.videoOrientation = .portrait
    }
  }

  /// Continuous AF biased to near range, plus Apple's recommended zoom so a
  /// card that fills the frame is still beyond the lens' minimum focus
  /// distance (see Apple's "AVCamBarcode" sample).
  private func configureFocus(_ device: AVCaptureDevice) {
    guard (try? device.lockForConfiguration()) != nil else { return }
    defer { device.unlockForConfiguration() }

    if device.isFocusModeSupported(.continuousAutoFocus) {
      device.focusMode = .continuousAutoFocus
    }
    if device.isAutoFocusRangeRestrictionSupported {
      device.autoFocusRangeRestriction = .near
    }

    let minimumFocusDistance = Float(device.minimumFocusDistance)  // mm, -1 if unknown
    guard minimumFocusDistance > 0 else { return }
    let dims = CMVideoFormatDescriptionGetDimensions(device.activeFormat.formatDescription)
    // The card spans 88% of the portrait width = the sensor's short side.
    let fill = 0.88 * Float(dims.height) / Float(dims.width)
    let halfFov = device.activeFormat.videoFieldOfView / 2 * .pi / 180
    let cardWidthMm: Float = 85.6
    let subjectDistance = (cardWidthMm / fill) / tan(halfFov)
    if subjectDistance < minimumFocusDistance {
      let zoom = CGFloat(minimumFocusDistance / subjectDistance)
      device.videoZoomFactor = min(zoom, 2.0, device.activeFormat.videoMaxZoomFactor)
    }
  }

  private func stopSession() {
    sessionQueue.async { [session] in
      if session.isRunning { session.stopRunning() }
    }
  }

  // MARK: - Actions

  @objc private func closeTapped() { finish(.cancelled) }

  @objc private func torchTapped() {
    guard let device, device.hasTorch, (try? device.lockForConfiguration()) != nil else { return }
    torchOn.toggle()
    device.torchMode = torchOn ? .on : .off
    device.unlockForConfiguration()
    torchButton.setImage(
      UIImage(systemName: torchOn ? "bolt.fill" : "bolt.slash.fill"), for: .normal)
  }

  @objc private func tapToFocus(_ gesture: UITapGestureRecognizer) {
    guard let device, device.isFocusPointOfInterestSupported else { return }
    let point = previewLayer.captureDevicePointConverted(
      fromLayerPoint: gesture.location(in: view))
    guard (try? device.lockForConfiguration()) != nil else { return }
    device.focusPointOfInterest = point
    device.focusMode = .autoFocus
    device.unlockForConfiguration()
  }

  /// Hide the camera feed from the app switcher snapshot.
  @objc private func appWillResignActive() {
    guard privacyCover == nil else { return }
    let cover = UIVisualEffectView(effect: UIBlurEffect(style: .dark))
    cover.frame = view.bounds
    view.addSubview(cover)
    privacyCover = cover
  }

  @objc private func appDidBecomeActive() {
    privacyCover?.removeFromSuperview()
    privacyCover = nil
  }

  private func finish(_ outcome: Outcome, dismiss: Bool = true) {
    guard !finished else { return }
    finished = true
    timeoutWork?.cancel()
    stopSession()
    if case .success = outcome {
      UINotificationFeedbackGenerator().notificationOccurred(.success)
    }
    if dismiss {
      self.dismiss(animated: true)
    }
    completion?(outcome)
    completion = nil
  }

  // MARK: - Region of interest

  /// Maps the on-screen card frame to Vision's normalized, bottom-left-origin
  /// image coordinates, for an aspect-fill preview. Slightly enlarged so a
  /// card that isn't perfectly aligned is still read.
  static func regionOfInterest(
    cardRect: CGRect, viewSize: CGSize, imageSize: CGSize, margin: CGFloat = 0.08
  ) -> CGRect {
    guard viewSize.width > 0, imageSize.width > 0, cardRect.width > 0 else {
      return CGRect(x: 0, y: 0, width: 1, height: 1)
    }
    let scale = max(viewSize.width / imageSize.width, viewSize.height / imageSize.height)
    let shown = CGSize(width: imageSize.width * scale, height: imageSize.height * scale)
    let offset = CGPoint(
      x: (viewSize.width - shown.width) / 2, y: (viewSize.height - shown.height) / 2)
    let r = cardRect.insetBy(dx: -cardRect.width * margin, dy: -cardRect.height * margin)
    let x = (r.minX - offset.x) / shown.width
    let y = (r.minY - offset.y) / shown.height
    let w = r.width / shown.width
    let h = r.height / shown.height
    let roi = CGRect(x: x, y: 1 - y - h, width: w, height: h)
    return roi.intersection(CGRect(x: 0, y: 0, width: 1, height: 1))
  }
}

// MARK: - Frame processing

extension ScannerViewController: AVCaptureVideoDataOutputSampleBufferDelegate {
  func captureOutput(
    _ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer,
    from connection: AVCaptureConnection
  ) {
    guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
    let imageSize = CGSize(
      width: CVPixelBufferGetWidth(pixelBuffer), height: CVPixelBufferGetHeight(pixelBuffer))

    geometryLock.lock()
    let card = cardRectInView
    let size = viewSize
    geometryLock.unlock()

    textRequest.regionOfInterest = Self.regionOfInterest(
      cardRect: card, viewSize: size, imageSize: imageSize)

    let handler = VNImageRequestHandler(cvPixelBuffer: pixelBuffer, orientation: .up)
    guard (try? handler.perform([textRequest])) != nil else { return }

    // Top-to-bottom order so the "adjacent lines" join in the parser works.
    let lines = (textRequest.results ?? [])
      .sorted { $0.boundingBox.midY > $1.boundingBox.midY }
      .compactMap { $0.topCandidates(1).first?.string }

    let frame = parser.parse(lines)
    let result = aggregator.add(frame)
    let leading = aggregator.leadingNumber

    DispatchQueue.main.async { [weak self] in
      guard let self, !self.finished else { return }
      self.showLive(number: leading)
      if self.config.debugShowText { self.debugLabel.text = lines.joined(separator: "\n") }
      if let result {
        self.finish(
          .success(
            ScanOutput(
              number: result.number,
              expiry: result.expiry,
              holderName: result.holderName,
              elapsed: Date().timeIntervalSince(self.startedAt),
              framesProcessed: result.framesProcessed)))
      }
    }
  }
}
