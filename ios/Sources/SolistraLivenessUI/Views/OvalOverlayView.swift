import UIKit

public final class OvalOverlayView: UIView {

    private let maskLayer = CAShapeLayer()
    private let borderLayer = CAShapeLayer()
    private let progressLayer = CAShapeLayer()

    public private(set) var ovalRect: CGRect = .zero

    public override init(frame: CGRect) {
        super.init(frame: frame)
        setupLayers()
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        setupLayers()
    }

    private func setupLayers() {
        backgroundColor = UIColor.black.withAlphaComponent(0.75)

        maskLayer.fillRule = .evenOdd
        layer.mask = maskLayer

        borderLayer.fillColor = UIColor.clear.cgColor
        borderLayer.strokeColor = UIColor.white.cgColor
        borderLayer.lineWidth = 4.0
        layer.addSublayer(borderLayer)

        progressLayer.fillColor = UIColor.clear.cgColor
        progressLayer.strokeColor = UIColor(red: 0.0, green: 0.9, blue: 1.0, alpha: 1.0).cgColor
        progressLayer.lineWidth = 6.0
        progressLayer.lineCap = .round
        progressLayer.strokeEnd = 0.0
        layer.addSublayer(progressLayer)
    }

    public override func layoutSubviews() {
        super.layoutSubviews()
        updatePaths()
    }

    private func updatePaths() {
        let width = bounds.width * 0.74
        let height = width * 1.35
        let originX = (bounds.width - width) / 2.0
        let originY = (bounds.height - height) / 2.0 - (bounds.height * 0.04)

        ovalRect = CGRect(x: originX, y: originY, width: width, height: height)

        // Mask path (Screen bounds inverted with oval cutout)
        let path = UIBezierPath(rect: bounds)
        let ovalPath = UIBezierPath(ovalIn: ovalRect)
        path.append(ovalPath)
        maskLayer.path = path.cgPath

        borderLayer.path = ovalPath.cgPath
        progressLayer.path = ovalPath.cgPath
    }

    public func setBorderColor(_ color: UIColor, animated: Bool = true) {
        if animated {
            let anim = CABasicAnimation(keyPath: "strokeColor")
            anim.fromValue = borderLayer.strokeColor
            anim.toValue = color.cgColor
            anim.duration = 0.25
            borderLayer.add(anim, forKey: "strokeColor")
        }
        borderLayer.strokeColor = color.cgColor
    }

    public func setProgress(_ progress: Float, animated: Bool = true) {
        let clamped = CGFloat(min(1.0, max(0.0, progress)))
        progressLayer.strokeEnd = clamped
    }
}
