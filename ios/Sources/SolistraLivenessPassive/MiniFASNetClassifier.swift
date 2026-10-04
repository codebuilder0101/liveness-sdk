import Foundation
import UIKit
import CoreML
import CoreVideo
import Vision

public struct AntiSpoofPrediction {
    public let isReal: Bool
    public let realConfidence: Float
    public let spoof2dConfidence: Float
    public let spoof3dConfidence: Float

    public init(isReal: Bool, realConfidence: Float, spoof2dConfidence: Float, spoof3dConfidence: Float) {
        self.isReal = isReal
        self.realConfidence = realConfidence
        self.spoof2dConfidence = spoof2dConfidence
        self.spoof3dConfidence = spoof3dConfidence
    }
}

public final class MiniFASNetClassifier {
    private var model: MLModel?
    private let inputWidth = 80
    private let inputHeight = 80

    public init(modelURL: URL? = nil) {
        if let url = modelURL {
            self.model = try? MLModel(contentsOf: url)
        } else if let bundleURL = Bundle.main.url(forResource: "MiniFASNetV1SE", withExtension: "mlmodelc") ??
                    Bundle(for: MiniFASNetClassifier.self).url(forResource: "MiniFASNetV1SE", withExtension: "mlmodelc") {
            self.model = try? MLModel(contentsOf: bundleURL)
        }
    }

    public func setModel(_ mlModel: MLModel) {
        self.model = mlModel
    }

    /**
     * Runs CoreML inference on an 80x80 face crop.
     */
    public func classify(croppedBitmap: UIImage) -> AntiSpoofPrediction {
        guard let model = model else {
            // Model not loaded — do NOT silently pass everyone through.
            // Return a failed prediction so the caller can surface this as an error.
            print("[MiniFASNetClassifier] WARNING: CoreML model not loaded. Returning spoof result.")
            return AntiSpoofPrediction(isReal: false, realConfidence: 0, spoof2dConfidence: 1, spoof3dConfidence: 0)
        }

        guard let pixelBuffer = croppedBitmap.toCVPixelBuffer(width: inputWidth, height: inputHeight) else {
            return AntiSpoofPrediction(isReal: false, realConfidence: 0, spoof2dConfidence: 1, spoof3dConfidence: 0)
        }

        do {
            let inputName = model.modelDescription.inputDescriptionsByName.keys.first ?? "input"
            let featureValue = MLFeatureValue(pixelBuffer: pixelBuffer)
            let inputProvider = try MLDictionaryFeatureProvider(dictionary: [inputName: featureValue])

            let output = try model.prediction(from: inputProvider)
            let outputName = model.modelDescription.outputDescriptionsByName.keys.first ?? "output"

            if let multiArray = output.featureValue(for: outputName)?.multiArrayValue, multiArray.count >= 3 {
                let raw0 = multiArray[0].floatValue
                let raw1 = multiArray[1].floatValue
                let raw2 = multiArray[2].floatValue

                // Softmax normalizer
                let maxVal = max(raw0, max(raw1, raw2))
                let exp0 = exp(raw0 - maxVal)
                let exp1 = exp(raw1 - maxVal)
                let exp2 = exp(raw2 - maxVal)
                let sumExp = exp0 + exp1 + exp2

                let spoof2d = sumExp > 0 ? exp0 / sumExp : 0.33
                let real = sumExp > 0 ? exp1 / sumExp : 0.33
                let spoof3d = sumExp > 0 ? exp2 / sumExp : 0.33

                return AntiSpoofPrediction(
                    isReal: real > 0.5 && real > (spoof2d + spoof3d) * 0.5,
                    realConfidence: real,
                    spoof2dConfidence: spoof2d,
                    spoof3dConfidence: spoof3d
                )
            }
        } catch {
            print("[MiniFASNetClassifier] CoreML inference error: \(error.localizedDescription)")
        }

        return AntiSpoofPrediction(isReal: false, realConfidence: 0, spoof2dConfidence: 1, spoof3dConfidence: 0)
    }
}

// MARK: - Pixel Buffer Helper
private extension UIImage {
    func toCVPixelBuffer(width: Int, height: Int) -> CVPixelBuffer? {
        var pixelBuffer: CVPixelBuffer?
        let attrs = [
            kCVPixelBufferCGImageCompatibilityKey: kCFBooleanTrue,
            kCVPixelBufferCGBitmapContextCompatibilityKey: kCFBooleanTrue
        ] as CFDictionary

        let status = CVPixelBufferCreate(
            kCFAllocatorDefault,
            width,
            height,
            kCVPixelFormatType_32ARGB,
            attrs,
            &pixelBuffer
        )

        guard status == kCVReturnSuccess, let buffer = pixelBuffer else {
            return nil
        }

        CVPixelBufferLockBaseAddress(buffer, [])
        defer { CVPixelBufferUnlockBaseAddress(buffer, []) }

        let context = CGContext(
            data: CVPixelBufferGetBaseAddress(buffer),
            width: width,
            height: height,
            bitsPerComponent: 8,
            bytesPerRow: CVPixelBufferGetBytesPerRow(buffer),
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.noneSkipFirst.rawValue
        )

        guard let cgImage = self.cgImage, let ctx = context else {
            return nil
        }

        ctx.draw(cgImage, in: CGRect(x: 0, y: 0, width: width, height: height))
        return buffer
    }
}
