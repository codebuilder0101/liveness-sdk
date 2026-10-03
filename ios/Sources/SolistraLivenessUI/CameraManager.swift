import Foundation
import AVFoundation
import UIKit

public protocol CameraManagerDelegate: AnyObject {
    func cameraManager(didOutput pixelBuffer: CVPixelBuffer, timestamp: TimeInterval)
    func cameraManager(didFailWithError error: Error)
}

public final class CameraManager: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate {

    public weak var delegate: CameraManagerDelegate?
    public let captureSession = AVCaptureSession()
    private let sessionQueue = DispatchQueue(label: "com.solistra.liveness.cameraQueue")

    public override init() {
        super.init()
    }

    public func configureSession(completion: @escaping (Bool) -> Void) {
        sessionQueue.async { [weak self] in
            guard let self = self else { return }

            self.captureSession.beginConfiguration()
            self.captureSession.sessionPreset = .hd1280x720

            guard let frontCamera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .front),
                  let videoInput = try? AVCaptureDeviceInput(device: frontCamera),
                  self.captureSession.canAddInput(videoInput) else {
                self.captureSession.commitConfiguration()
                DispatchQueue.main.async { completion(false) }
                return
            }

            self.captureSession.addInput(videoInput)

            let videoOutput = AVCaptureVideoDataOutput()
            videoOutput.videoSettings = [
                kCVPixelBufferPixelFormatTypeKey as String: Int(kCVPixelFormatType_32BGRA)
            ]
            videoOutput.alwaysDiscardsLateVideoFrames = true
            videoOutput.setSampleBufferDelegate(self, queue: self.sessionQueue)

            if self.captureSession.canAddOutput(videoOutput) {
                self.captureSession.addOutput(videoOutput)
            }

            // Ensure video orientation is portrait and mirrored for front camera
            if let connection = videoOutput.connection(with: .video) {
                connection.videoOrientation = .portrait
                connection.isVideoMirrored = true
            }

            self.captureSession.commitConfiguration()
            DispatchQueue.main.async { completion(true) }
        }
    }

    public func start() {
        sessionQueue.async { [weak self] in
            if self?.captureSession.isRunning == false {
                self?.captureSession.startRunning()
            }
        }
    }

    public func stop() {
        sessionQueue.async { [weak self] in
            if self?.captureSession.isRunning == true {
                self?.captureSession.stopRunning()
            }
        }
    }

    // MARK: - AVCaptureVideoDataOutputSampleBufferDelegate

    public func captureOutput(
        _ output: AVCaptureOutput,
        didOutput sampleBuffer: CMSampleBuffer,
        from connection: AVCaptureConnection
    ) {
        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        let timestamp = CMSampleBufferGetPresentationTimeStamp(sampleBuffer).seconds
        delegate?.cameraManager(didOutput: pixelBuffer, timestamp: timestamp)
    }
}
