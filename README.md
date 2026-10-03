# Solistra On-Device Face Liveness Detection SDK (Android & iOS)

A 100% on-device, high-security Face Liveness Detection SDK combining **Active Challenge-Response** and **Passive Anti-Spoofing** with zero server latency.

## 🚀 Key Features
* **Active Liveness (Google MediaPipe Tasks Vision):**
  * Real-time 52 facial blendshapes (`eyeBlinkLeft`, `mouthSmileRight`, `jawOpen`, etc.)
  * 3D Head Pose tracking (Yaw, Pitch, Roll matrix transformations)
  * Challenges: Blink, Smile, Turn Left, Turn Right, Head Nod, Open Mouth
* **Passive Anti-Spoofing (Silent-Face-Anti-Spoofing MiniFASNet):**
  * On-device TFLite (Android) and CoreML (iOS)
  * Multi-scale contextual face crops ($2.7\times$ skin texture and $4.0\times$ screen bezel/environment)
  * Blocks printed paper photos, 4K screen replays, and 3D silicone masks
* **100% Offline & Private:** Zero video or biometric frames leave the device.
* **Anti-Tampering:** Dynamic randomized challenge sequence, stall timeout, screenshot & screen-recording prevention (`FLAG_SECURE` / `UIScreen.isCaptured`).

---

## 📦 Project Structure

```
liveness-sdk/
├── models/                       # Model conversion scripts & definitions
│   ├── conversion/
│   │   ├── export_minifasnet_onnx.py
│   │   ├── convert_onnx_to_tflite.py
│   │   ├── convert_onnx_to_coreml.py
│   │   └── requirements.txt
│   └── README.md
├── android/                      # Android SDK & Sample App (Kotlin)
│   ├── liveness-sdk/             # Android AAR Library
│   │   └── src/main/java/com/solistra/liveness/
│   │       ├── core/             # Data models, config, states, results
│   │       ├── active/           # MediaPipe Face Landmarker & Blendshapes
│   │       ├── passive/          # MiniFASNet TFLite classifier & cropper
│   │       ├── engine/           # State machine coordinator
│   │       └── ui/               # CameraX & dynamic oval overlay
│   └── sample-app/               # Sample Android Application
└── ios/                          # iOS SDK (Swift Package Manager)
    ├── Package.swift
    └── Sources/
        ├── SolistraLivenessCore/
        ├── SolistraLivenessActive/
        ├── SolistraLivenessPassive/
        ├── SolistraLivenessEngine/
        └── SolistraLivenessUI/
```

---

## 📱 Android Quickstart

### 1. Launching Liveness Verification with ActivityResultContract
```kotlin
import com.solistra.liveness.core.LivenessChallenge
import com.solistra.liveness.core.LivenessConfig
import com.solistra.liveness.core.LivenessResult
import com.solistra.liveness.ui.LivenessActivity

class MyActivity : AppCompatActivity() {

    // Register modern ActivityResultContract
    private val livenessLauncher = registerForActivityResult(
        LivenessActivity.Contract()
    ) { result: LivenessResult? ->
        if (result != null && result.isLive) {
            val verifiedFaceBitmap = result.bestFaceImage
            val confidence = result.passiveScore
            val duration = result.sessionDurationMs
            val challengesPassed = result.completedChallenges
            // Liveness verified successfully
        } else {
            // Liveness verification failed or user cancelled
        }
    }

    private fun startLivenessCheck() {
        val config = LivenessConfig(
            challenges = listOf(LivenessChallenge.BLINK, LivenessChallenge.SMILE),
            challengeTimeoutSeconds = 4.0f,
            passiveSpoofThreshold = 0.85f,
            enableScreenSecurity = true,
            useGpuDelegate = true
        )
        livenessLauncher.launch(config)
    }
}
```

### 2. Testing via the Demo App
The `:sample-app` module contains a full test suite with:
- **Preset modes**: Standard (Blink + Smile), High Security (3 randomized challenges), and Custom.
- **Challenge sequence builder**: Select any combination of Blink, Smile, Turn Left, Turn Right, Nod Head, Open Mouth.
- **Real-time sliders**: Challenge timeout (2.0s - 8.0s), Passive spoof threshold (50% - 98%), and Frame sample size.
- **Verification audit log**: History of recent verification attempts, duration, confidence scores, and face snapshot inspection dialog.

---

## 🍎 iOS Quickstart

### 1. Launching Liveness Verification
```swift
import UIKit
import SolistraLivenessCore
import SolistraLivenessUI

class ViewController: UIViewController, LivenessDelegate {

    func startLiveness() {
        let config = LivenessConfig(
            challenges: [.blink, .smile],
            challengeTimeoutSeconds: 4.0,
            passiveSpoofThreshold: 0.85,
            enableScreenSecurity: true
        )

        let livenessVC = LivenessViewController()
        livenessVC.config = config
        livenessVC.completionDelegate = self
        livenessVC.modalPresentationStyle = .fullScreen
        present(livenessVC, animated: true)
    }

    // MARK: - LivenessDelegate
    func livenessSession(didCompleteWithResult result: LivenessResult) {
        print("Liveness Passed! Score: \(result.passiveScore)")
        let faceImage = result.bestFaceImage
    }

    func livenessSession(didFailWithError error: LivenessError) {
        print("Liveness Failed: \(error.localizedDescription)")
    }

    func livenessSession(didUpdateState state: LivenessState) {}
    func livenessSession(didUpdateProgress progress: Float, forChallenge challenge: LivenessChallenge) {}
}
```

---

## ⚙️ Model Conversion Setup

To generate the lightweight TFLite and CoreML models from Silent-Face-Anti-Spoofing:

```bash
cd models
pip install -r conversion/requirements.txt
python conversion/export_minifasnet_onnx.py
python conversion/convert_onnx_to_tflite.py
python conversion/convert_onnx_to_coreml.py
```
