"""
Converts MiniFASNet ONNX models to TensorFlow Lite (.tflite) with FP16 and INT8 Quantization.
"""

import os
import sys

def convert_onnx_to_tflite(onnx_path, tflite_output_path, quantize_fp16=True):
    try:
        import onnx
        from onnx_tf.backend import prepare
        import tensorflow as tf
    except ImportError:
        print("[!] Please install: pip install onnx onnx-tf tensorflow")
        return

    print(f"Loading ONNX model: {onnx_path}")
    onnx_model = onnx.load(onnx_path)
    
    # Export intermediate SavedModel
    saved_model_dir = onnx_path.replace(".onnx", "_saved_model")
    print(f"Converting ONNX to TensorFlow SavedModel at: {saved_model_dir}")
    tf_rep = prepare(onnx_model)
    tf_rep.export_graph(saved_model_dir)

    # Convert SavedModel to TFLite
    print("Converting SavedModel to TFLite...")
    converter = tf.lite.TFLiteConverter.from_saved_model(saved_model_dir)
    
    if quantize_fp16:
        converter.optimizations = [tf.lite.Optimize.DEFAULT]
        converter.target_spec.supported_types = [tf.float16]
        print("Applied Float16 Quantization.")

    tflite_model = converter.convert()
    
    os.makedirs(os.path.dirname(tflite_output_path), exist_ok=True)
    with open(tflite_output_path, "wb") as f:
        f.write(tflite_model)

    print(f"[✓] Successfully saved TFLite model: {tflite_output_path} ({len(tflite_model) / 1024:.2f} KB)")


if __name__ == "__main__":
    convert_onnx_to_tflite(
        "models/exported/minifasnet_v1se_80x80.onnx",
        "models/exported/minifasnet_v1se_80x80_fp16.tflite"
    )
    convert_onnx_to_tflite(
        "models/exported/minifasnet_v2_80x80.onnx",
        "models/exported/minifasnet_v2_80x80_fp16.tflite"
    )
