import Foundation
import UIKit
import CoreGraphics

public struct MultiScaleCropper {

    /**
     * Extracts an expanded square face region using the specified scale factor (e.g. 2.7x or 4.0x)
     * and resizes to target dimension (80x80) for MiniFASNet.
     */
    public static func cropFaceWithScale(
        image: UIImage,
        faceBbox: CGRect,
        scaleFactor: CGFloat = 2.7,
        targetSize: CGSize = CGSize(width: 80, height: 80)
    ) -> UIImage? {
        guard let cgImage = image.cgImage else { return nil }

        let centerX = faceBbox.midX
        let centerY = faceBbox.midY
        let maxDim = max(faceBbox.width, faceBbox.height)
        let expandedSize = maxDim * scaleFactor

        let cropRect = CGRect(
            x: centerX - expandedSize / 2.0,
            y: centerY - expandedSize / 2.0,
            width: expandedSize,
            height: expandedSize
        )

        let renderer = UIGraphicsImageRenderer(size: targetSize)
        return renderer.image { context in
            // Fill background with black for boundary margin padding
            UIColor.black.setFill()
            context.fill(CGRect(origin: .zero, size: targetSize))

            // Compute sub-region coordinates
            let imageWidth = CGFloat(cgImage.width)
            let imageHeight = CGFloat(cgImage.height)

            let srcIntersection = cropRect.intersection(CGRect(x: 0, y: 0, width: imageWidth, height: imageHeight))
            guard !srcIntersection.isNull && srcIntersection.width > 0 && srcIntersection.height > 0 else { return }

            if let subCgImage = cgImage.cropping(to: srcIntersection) {
                let normLeft = (srcIntersection.minX - cropRect.minX) / expandedSize * targetSize.width
                let normTop = (srcIntersection.minY - cropRect.minY) / expandedSize * targetSize.height
                let normWidth = srcIntersection.width / expandedSize * targetSize.width
                let normHeight = srcIntersection.height / expandedSize * targetSize.height

                let dstRect = CGRect(x: normLeft, y: normTop, width: normWidth, height: normHeight)
                UIImage(cgImage: subCgImage).draw(in: dstRect)
            }
        }
    }
}
