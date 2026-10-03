import Foundation
import CoreMedia
import UIKit
import SolistraLivenessCore

public protocol MediaPipeLandmarkerDelegate: AnyObject {
    func landmarkerHelper(
        didDetectFaceLandmarks landmarks: [[CGPoint]],
        blendshapes: [String: Float],
        headPose: HeadPoseAngles,
        faceBoundingBox: CGRect?,
        inputImage: UIImage?,
        timestamp: TimeInterval
    )
    func landmarkerHelper(didFailWithError error: Error)
}

public final class MediaPipeLandmarkerHelper: NSObject {
    public weak var delegate: MediaPipeLandmarkerDelegate?

    public override init() {
        super.init()
    }

    public func processFrame(pixelBuffer: CVPixelBuffer, timestamp: TimeInterval) {
        // In full MediaPipe iOS integration:
        // let mpImage = MPImage(pixelBuffer: pixelBuffer)
        // faceLandmarker.detectAsync(image: mpImage, timestampInMilliseconds: Int(timestamp * 1000))
        
        // Extract basic frame geometry
        let width = CVPixelBufferGetWidth(pixelBuffer)
        let height = CVPixelBufferGetHeight(pixelBuffer)
        
        let sampleBBox = CGRect(
            x: CGFloat(width) * 0.2,
            y: CGFloat(height) * 0.2,
            width: CGFloat(width) * 0.6,
            height: CGFloat(height) * 0.6
        )

        // Delegate forwarding for pipeline
        let ciImage = CIImage(cvPixelBuffer: pixelBuffer)
        let context = CIContext()
        if let cgImage = context.createCGImage(ciImage, from: ciImage.extent) {
            let image = UIImage(cgImage: cgImage)
            delegate?.landmarkerHelper(
                didDetectFaceLandmarks: [],
                blendshapes: [:],
                headPose: HeadPoseAngles(pitch: 0, yaw: 0, roll: 0),
                faceBoundingBox: sampleBBox,
                inputImage: image,
                timestamp: timestamp
            )
        }
    }
}
