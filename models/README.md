# Model Assets & Conversion Guide

This directory contains the tools and instructions for preparing the two core machine learning models required by the **Solistra Liveness Detection SDK**:

## 1. Google MediaPipe Face Landmarker (Active Liveness)
* **Model Task File:** `face_landmarker.task`
* **Source:** Official MediaPipe Vision Task Bundle
* **Features:**
  * 478 3D facial landmarks
  * 52 real-time facial blendshapes (`eyeBlinkLeft`, `mouthSmileRight`, `jawOpen`, etc.)
  * Facial transformation matrix (metric head pose: pitch, yaw, roll)
* **Download Instructions:**
  Download the latest bundle from Google MediaPipe:
  ```bash
  curl -O https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/latest/face_landmarker.task
  ```
  Place `face_landmarker.task` in:
  - **Android:** `android/liveness-sdk/src/main/assets/face_landmarker.task`
  - **iOS:** `ios/Sources/SolistraLivenessActive/Resources/face_landmarker.task`

---

## 2. Silent-Face-Anti-Spoofing (Passive Liveness)
* **Model Architecture:** MiniFASNetV1SE / MiniFASNetV2
* **Input Resolution:** `80x80x3` (RGB/BGR normalized)
* **Multi-Scale Contextual Crops:**
  1. **Scale 2.7x:** Immediate face & skin texture inspection (detects paper grain, matte masks, screen pixel arrays).
  2. **Scale 4.0x:** Contextual environment inspection (detects phone bezels, holding hands, screen glare, display borders).
* **Outputs:** 3-class softmax probabilities: `[2D Spoof, Real Face, 3D Spoof]`

### Exporting and Converting
1. Install dependencies:
   ```bash
   pip install -r conversion/requirements.txt
   ```
2. Run ONNX export:
   ```bash
   python conversion/export_minifasnet_onnx.py
   ```
3. Convert to TFLite (Android):
   ```bash
   python conversion/convert_onnx_to_tflite.py
   ```
   Output: `models/exported/minifasnet_v1se_80x80_fp16.tflite`
4. Convert to CoreML (iOS):
   ```bash
   python conversion/convert_onnx_to_coreml.py
   ```
   Output: `models/exported/MiniFASNetV1SE.mlpackage`
