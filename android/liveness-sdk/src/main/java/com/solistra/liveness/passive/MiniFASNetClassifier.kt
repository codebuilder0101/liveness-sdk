package com.solistra.liveness.passive

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * High-performance on-device TFLite inference classifier for MiniFASNet.
 */
class MiniFASNetClassifier(
    private val context: Context,
    private val modelAssetName: String = "minifasnet_v1se_80x80_fp16.tflite",
    private val useGpu: Boolean = true
) {

    data class AntiSpoofResult(
        val isReal: Boolean,
        val realConfidence: Float,   // 0.0 to 1.0
        val spoof2dConfidence: Float, // 2D Print / screen
        val spoof3dConfidence: Float  // 3D Mask / replay
    )

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null

    // MiniFASNet 80x80 input tensor dimensions
    private val inputSize = 80
    private val inputChannels = 3 // BGR or RGB
    private val numClasses = 3

    // MiniFASNet standard normalizations
    // Image scale normalization
    private val meanB = 104.0f
    private val meanG = 117.0f
    private val meanR = 123.0f

    init {
        setupInterpreter()
    }

    private fun setupInterpreter() {
        val modelBuffer = loadModelFile(context, modelAssetName)
        var interp: Interpreter? = null

        if (useGpu) {
            try {
                val compatList = CompatibilityList()
                if (compatList.isDelegateSupportedOnThisDevice) {
                    gpuDelegate = GpuDelegate()
                    val options = Interpreter.Options().apply {
                        addDelegate(gpuDelegate)
                    }
                    interp = Interpreter(modelBuffer, options)
                }
            } catch (e: Throwable) {
                gpuDelegate?.close()
                gpuDelegate = null
                interp = null
            }
        }

        if (interp == null) {
            val options = Interpreter.Options().apply {
                setNumThreads(4)
            }
            interp = Interpreter(modelBuffer, options)
        }

        interpreter = interp
    }

    private fun loadModelFile(context: Context, modelPath: String): ByteBuffer {
        val fileDescriptor = context.assets.openFd(modelPath)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    /**
     * Runs passive anti-spoof inference on an 80x80 cropped bitmap.
     */
    @Synchronized
    fun classify(croppedFaceBitmap: Bitmap): AntiSpoofResult {
        val interp = interpreter ?: return AntiSpoofResult(false, 0f, 1f, 0f)

        val inputBuffer = ByteBuffer.allocateDirect(1 * inputSize * inputSize * inputChannels * 4) // Float32
        inputBuffer.order(ByteOrder.nativeOrder())
        inputBuffer.rewind()

        val intValues = IntArray(inputSize * inputSize)
        val resized = if (croppedFaceBitmap.width == inputSize && croppedFaceBitmap.height == inputSize) {
            croppedFaceBitmap
        } else {
            Bitmap.createScaledBitmap(croppedFaceBitmap, inputSize, inputSize, true)
        }

        resized.getPixels(intValues, 0, inputSize, 0, 0, inputSize, inputSize)

        // Populate tensor in BGR format with mean subtraction
        for (i in 0 until inputSize * inputSize) {
            val pixel = intValues[i]
            val r = (pixel shr 16 and 0xFF).toFloat()
            val g = (pixel shr 8 and 0xFF).toFloat()
            val b = (pixel and 0xFF).toFloat()

            // MiniFASNet standard BGR order
            inputBuffer.putFloat(b - meanB)
            inputBuffer.putFloat(g - meanG)
            inputBuffer.putFloat(r - meanR)
        }

        // Output tensor shape: [1, 3] -> [Spoof2D, Real, Spoof3D]
        val outputBuffer = Array(1) { FloatArray(numClasses) }

        interp.run(inputBuffer, outputBuffer)

        val logits = outputBuffer[0]
        val probabilities = applySoftmax(logits)
        val spoof2d = probabilities[0]
        val realScore = probabilities[1]
        val spoof3d = probabilities[2]

        return AntiSpoofResult(
            isReal = realScore > 0.5f && realScore > (spoof2d + spoof3d) * 0.5f,
            realConfidence = realScore,
            spoof2dConfidence = spoof2d,
            spoof3dConfidence = spoof3d
        )
    }

    private fun applySoftmax(logits: FloatArray): FloatArray {
        var max = Float.NEGATIVE_INFINITY
        for (v in logits) {
            if (v > max) max = v
        }
        var sum = 0f
        val exps = FloatArray(logits.size)
        for (i in logits.indices) {
            exps[i] = kotlin.math.exp(logits[i] - max)
            sum += exps[i]
        }
        val probs = FloatArray(logits.size)
        for (i in logits.indices) {
            probs[i] = if (sum > 0f) exps[i] / sum else (1f / logits.size)
        }
        return probs
    }

    fun close() {
        interpreter?.close()
        interpreter = null
        gpuDelegate?.close()
        gpuDelegate = null
    }
}
