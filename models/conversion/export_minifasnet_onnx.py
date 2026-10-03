"""
MiniFASNet Model Exporter: PyTorch -> ONNX -> TFLite / CoreML
Converts Silent-Face-Anti-Spoofing models for mobile edge deployment.
"""

import os
import sys
import torch
import torch.nn as nn
import torch.nn.functional as F

# ----------------------------------------------------------------------
# MiniFASNet PyTorch Model Definition (Silent-Face-Anti-Spoofing)
# ----------------------------------------------------------------------

class Conv_block(nn.Module):
    def __init__(self, in_c, out_c, kernel=(1, 1), stride=(1, 1), padding=(0, 0), groups=1):
        super(Conv_block, self).__init__()
        self.conv = nn.Conv2d(in_c, out_c, kernel_size=kernel, stride=stride, padding=padding, groups=groups, bias=False)
        self.bn = nn.BatchNorm2d(out_c)
        self.prelu = nn.PReLU(out_c)

    def forward(self, x):
        return self.prelu(self.bn(self.conv(x)))


class Linear_block(nn.Module):
    def __init__(self, in_c, out_c, kernel=(1, 1), stride=(1, 1), padding=(0, 0), groups=1):
        super(Linear_block, self).__init__()
        self.conv = nn.Conv2d(in_c, out_c, kernel_size=kernel, stride=stride, padding=padding, groups=groups, bias=False)
        self.bn = nn.BatchNorm2d(out_c)

    def forward(self, x):
        return self.bn(self.conv(x))


class Depth_Wise(nn.Module):
    def __init__(self, in_c, out_c, residual=False, kernel=(3, 3), stride=(2, 2), padding=(1, 1), groups=1):
        super(Depth_Wise, self).__init__()
        self.residual = residual
        self.conv = Conv_block(in_c, out_c=groups, kernel=(1, 1), padding=(0, 0), stride=(1, 1))
        self.conv_dw = Conv_block(groups, groups, groups=groups, kernel=kernel, padding=padding, stride=stride)
        self.project = Linear_block(groups, out_c, kernel=(1, 1), padding=(0, 0), stride=(1, 1))

    def forward(self, x):
        if self.residual:
            short_cut = x
            x = self.conv(x)
            x = self.conv_dw(x)
            x = self.project(x)
            return short_cut + x
        else:
            x = self.conv(x)
            x = self.conv_dw(x)
            x = self.project(x)
            return x


class Residual(nn.Module):
    def __init__(self, c, num_block, groups, kernel=(3, 3), stride=(1, 1), padding=(1, 1)):
        super(Residual, self).__init__()
        modules = []
        for _ in range(num_block):
            modules.append(Depth_Wise(c, c, residual=True, kernel=kernel, padding=padding, stride=stride, groups=groups))
        self.model = nn.Sequential(*modules)

    def forward(self, x):
        return self.model(x)


class MiniFASNet(nn.Module):
    def __init__(self, keep, embedding_size, conv68_kernel=(5, 5), drop_p=0.2, num_classes=3, img_channel=3):
        super(MiniFASNet, self).__init__()
        self.conv1 = Conv_block(img_channel, keep[0], kernel=(3, 3), stride=(2, 2), padding=(1, 1))
        self.conv2_dw = Conv_block(keep[0], keep[0], kernel=(3, 3), stride=(1, 1), padding=(1, 1), groups=keep[0])

        c1 = [(keep[0], keep[1], 2, 1), (keep[1], keep[2], 2, 2), (keep[2], keep[3], 2, 2)]
        c2 = [(keep[1], keep[2], 2, 2), (keep[2], keep[3], 2, 2)]

        modules = []
        for in_c, out_c, num_block, stride in c1:
            modules.append(Depth_Wise(in_c, out_c, residual=False, kernel=(3, 3), stride=(stride, stride), padding=(1, 1), groups=in_c * 2))
            modules.append(Residual(out_c, num_block=num_block, groups=out_c * 2, kernel=(3, 3), stride=(1, 1), padding=(1, 1)))
        self.body = nn.Sequential(*modules)

        self.output_layer = nn.Sequential(
            Conv_block(keep[3], embedding_size, kernel=(1, 1), stride=(1, 1), padding=(0, 0)),
            Linear_block(embedding_size, embedding_size, groups=embedding_size, kernel=conv68_kernel, stride=(1, 1), padding=(0, 0)),
            nn.Flatten()
        )
        self.prob = nn.Linear(embedding_size, num_classes)

    def forward(self, x):
        x = self.conv1(x)
        x = self.conv2_dw(x)
        x = self.body(x)
        x = self.output_layer(x)
        x = self.prob(x)
        # Return softmax probabilities
        return F.softmax(x, dim=1)


def build_minifasnet_v1se(num_classes=3):
    return MiniFASNet(keep=[32, 32, 103, 103], embedding_size=128, conv68_kernel=(5, 5), num_classes=num_classes)


def build_minifasnet_v2(num_classes=3):
    return MiniFASNet(keep=[32, 32, 128, 128], embedding_size=128, conv68_kernel=(5, 5), num_classes=num_classes)


def export_to_onnx(model, onnx_output_path, input_size=(80, 80)):
    """Exports PyTorch MiniFASNet model to ONNX."""
    model.eval()
    dummy_input = torch.randn(1, 3, input_size[0], input_size[1], requires_grad=False)
    
    os.makedirs(os.path.dirname(onnx_output_path), exist_ok=True)
    
    torch.onnx.export(
        model,
        dummy_input,
        onnx_output_path,
        export_params=True,
        opset_version=13,
        do_constant_folding=True,
        input_names=['input'],
        output_names=['output'],
        dynamic_axes=None  # Fixed batch size 1 for optimal mobile inference
    )
    print(f"[✓] Successfully exported ONNX model to: {onnx_output_path}")


if __name__ == "__main__":
    print("Exporting MiniFASNet models to ONNX...")
    model_v1se = build_minifasnet_v1se(num_classes=3)
    model_v2 = build_minifasnet_v2(num_classes=3)
    
    export_to_onnx(model_v1se, "models/exported/minifasnet_v1se_80x80.onnx")
    export_to_onnx(model_v2, "models/exported/minifasnet_v2_80x80.onnx")
    print("Done.")
