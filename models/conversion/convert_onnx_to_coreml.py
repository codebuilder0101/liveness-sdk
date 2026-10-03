"""
Converts MiniFASNet ONNX/PyTorch models to Apple CoreML (.mlpackage / .mlmodel).
"""

import os
import sys

def convert_onnx_to_coreml(onnx_path, coreml_output_path):
    try:
        import coremltools as ct
    except ImportError:
        print("[!] Please install coremltools: pip install coremltools")
        return

    print(f"Loading and converting ONNX to CoreML: {onnx_path}")
    
    # Define Image Preprocessing Normalization
    # MiniFASNet uses standard BGR/RGB mean and scale
    # input range [0, 255] normalized
    image_input = ct.ImageType(
        name="input",
        shape=(1, 3, 80, 80),
        scale=1.0 / 255.0,
        color_layout=ct.colorlayout.RGB
    )

    model = ct.convert(
        onnx_path,
        inputs=[image_input],
        minimum_deployment_target=ct.target.iOS15,
        compute_precision=ct.precision.FLOAT16
    )

    # Add metadata
    model.author = "Solistra Liveness SDK"
    model.license = "Apache-2.0"
    model.short_description = "MiniFASNet Passive Anti-Spoofing Model"
    model.version = "1.0"

    os.makedirs(os.path.dirname(coreml_output_path), exist_ok=True)
    model.save(coreml_output_path)
    print(f"[✓] Successfully saved CoreML model to: {coreml_output_path}")


if __name__ == "__main__":
    convert_onnx_to_coreml(
        "models/exported/minifasnet_v1se_80x80.onnx",
        "models/exported/MiniFASNetV1SE.mlpackage"
    )
