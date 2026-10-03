import UIKit
import AVFoundation
import SolistraLivenessCore
import SolistraLivenessEngine

public final class LivenessViewController: UIViewController, LivenessDelegate, CameraManagerDelegate {

    public var config: LivenessConfig = .default
    public weak var completionDelegate: LivenessDelegate?

    private var engine: LivenessEngine?
    private let cameraManager = CameraManager()
    private var previewLayer: AVCaptureVideoPreviewLayer?

    private let overlayView = OvalOverlayView()
    private let promptCardView = UIView()
    private let stepLabel = UILabel()
    private let promptLabel = UILabel()
    private let closeButton = UIButton(type: .system)
    private let timeoutProgressView = UIProgressView(progressViewStyle: .default)

    private let feedbackGenerator = UINotificationFeedbackGenerator()

    public override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black

        setupCameraPreview()
        setupUI()
        setupScreenCaptureObserver()

        engine = LivenessEngine(config: config)
        engine?.delegate = self
        cameraManager.delegate = self

        cameraManager.configureSession { [weak self] success in
            guard let self = self, success else { return }
            self.cameraManager.start()
            self.engine?.startSession()
        }
    }

    private func setupCameraPreview() {
        let preview = AVCaptureVideoPreviewLayer(session: cameraManager.captureSession)
        preview.videoGravity = .resizeAspectFill
        preview.frame = view.bounds
        view.layer.addSublayer(preview)
        self.previewLayer = preview
    }

    public override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer?.frame = view.bounds
        overlayView.frame = view.bounds
    }

    private func setupUI() {
        view.addSubview(overlayView)

        // Close Button
        closeButton.setImage(UIImage(systemName: "xmark"), for: .normal)
        closeButton.tintColor = .white
        closeButton.translatesAutoresizingMaskIntoConstraints = false
        closeButton.addTarget(self, action: #selector(didTapClose), for: .touchUpInside)
        view.addSubview(closeButton)

        // Prompt Card Container
        promptCardView.backgroundColor = UIColor(white: 0.1, alpha: 0.75)
        promptCardView.layer.cornerRadius = 16
        promptCardView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(promptCardView)

        stepLabel.font = .systemFont(ofSize: 12, weight: .bold)
        stepLabel.textColor = UIColor(white: 0.7, alpha: 1.0)
        stepLabel.textAlignment = .center
        stepLabel.translatesAutoresizingMaskIntoConstraints = false
        promptCardView.addSubview(stepLabel)

        promptLabel.font = .systemFont(ofSize: 18, weight: .semibold)
        promptLabel.textColor = .white
        promptLabel.textAlignment = .center
        promptLabel.numberOfLines = 2
        promptLabel.text = "Center your face in the oval"
        promptLabel.translatesAutoresizingMaskIntoConstraints = false
        promptCardView.addSubview(promptLabel)

        // Timeout Progress View
        timeoutProgressView.progressTintColor = UIColor(red: 0.0, green: 0.9, blue: 1.0, alpha: 1.0)
        timeoutProgressView.trackTintColor = UIColor.white.withAlphaComponent(0.2)
        timeoutProgressView.translatesAutoresizingMaskIntoConstraints = false
        timeoutProgressView.isHidden = true
        view.addSubview(timeoutProgressView)

        NSLayoutConstraint.activate([
            closeButton.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 16),
            closeButton.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -20),
            closeButton.widthAnchor.constraint(equalToConstant: 40),
            closeButton.heightAnchor.constraint(equalToConstant: 40),

            promptCardView.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 50),
            promptCardView.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 32),
            promptCardView.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -32),

            stepLabel.topAnchor.constraint(equalTo: promptCardView.topAnchor, constant: 12),
            stepLabel.centerXAnchor.constraint(equalTo: promptCardView.centerXAnchor),

            promptLabel.topAnchor.constraint(equalTo: stepLabel.bottomAnchor, constant: 4),
            promptLabel.leadingAnchor.constraint(equalTo: promptCardView.leadingAnchor, constant: 16),
            promptLabel.trailingAnchor.constraint(equalTo: promptCardView.trailingAnchor, constant: -16),
            promptLabel.bottomAnchor.constraint(equalTo: promptCardView.bottomAnchor, constant: -14),

            timeoutProgressView.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -36),
            timeoutProgressView.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 48),
            timeoutProgressView.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -48),
            timeoutProgressView.heightAnchor.constraint(equalToConstant: 4)
        ])
    }

    private func setupScreenCaptureObserver() {
        guard config.enableScreenSecurity else { return }

        NotificationCenter.default.addObserver(
            forName: UIScreen.capturedDidChangeNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            if UIScreen.main.isCaptured {
                self?.engine?.stopSession()
                self?.livenessSession(didFailWithError: .screenCaptureDetected)
            }
        }
    }

    @objc private func didTapClose() {
        engine?.stopSession()
        cameraManager.stop()
        completionDelegate?.livenessSession(didFailWithError: .userCancelled)
        dismiss(animated: true)
    }

    // MARK: - CameraManagerDelegate

    public func cameraManager(didOutput pixelBuffer: CVPixelBuffer, timestamp: TimeInterval) {
        engine?.processVideoFrame(pixelBuffer: pixelBuffer, timestamp: timestamp)
    }

    public func cameraManager(didFailWithError error: Error) {
        livenessSession(didFailWithError: .cameraInitializationFailed(error.localizedDescription))
    }

    // MARK: - LivenessDelegate

    public func livenessSession(didUpdateState state: LivenessState) {
        completionDelegate?.livenessSession(didUpdateState: state)

        switch state {
        case .faceAlignment(let message, _, let isCentered, let isAppropriateDist):
            stepLabel.isHidden = true
            timeoutProgressView.isHidden = true
            promptLabel.text = message
            let color = (isCentered && isAppropriateDist) ? UIColor.cyan : UIColor.white
            overlayView.setBorderColor(color)
            overlayView.setProgress(0)

        case .performingChallenge(let challenge, let idx, let total, let progress, let remaining):
            stepLabel.isHidden = false
            stepLabel.text = "CHALLENGE \(idx + 1) OF \(total)"
            promptLabel.text = challenge.promptText
            timeoutProgressView.isHidden = false
            timeoutProgressView.progress = remaining / config.challengeTimeoutSeconds
            overlayView.setBorderColor(UIColor(red: 0.0, green: 0.9, blue: 1.0, alpha: 1.0))
            overlayView.setProgress(progress)

        case .evaluatingPassive:
            stepLabel.isHidden = true
            promptLabel.text = "Analyzing security markers..."
            timeoutProgressView.isHidden = true

        case .success(let result):
            promptLabel.text = "Verification Complete"
            overlayView.setBorderColor(UIColor(red: 0.0, green: 0.9, blue: 0.4, alpha: 1.0))
            overlayView.setProgress(1.0)
            feedbackGenerator.notificationOccurred(.success)
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) { [weak self] in
                self?.completionDelegate?.livenessSession(didCompleteWithResult: result)
                self?.dismiss(animated: true)
            }

        case .failed(let error):
            promptLabel.text = error.localizedDescription
            overlayView.setBorderColor(UIColor(red: 1.0, green: 0.1, blue: 0.2, alpha: 1.0))
            feedbackGenerator.notificationOccurred(.error)
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.8) { [weak self] in
                self?.completionDelegate?.livenessSession(didFailWithError: error)
                self?.dismiss(animated: true)
            }

        default:
            break
        }
    }

    public func livenessSession(didUpdateProgress progress: Float, forChallenge challenge: LivenessChallenge) {
        overlayView.setProgress(progress)
    }

    public func livenessSession(didCompleteWithResult result: LivenessResult) {
        cameraManager.stop()
        completionDelegate?.livenessSession(didCompleteWithResult: result)
    }

    public func livenessSession(didFailWithError error: LivenessError) {
        cameraManager.stop()
        completionDelegate?.livenessSession(didFailWithError: error)
    }

    public override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        cameraManager.stop()
        engine?.stopSession()
    }

    deinit {
        cameraManager.stop()
        engine?.destroy()
    }
}
