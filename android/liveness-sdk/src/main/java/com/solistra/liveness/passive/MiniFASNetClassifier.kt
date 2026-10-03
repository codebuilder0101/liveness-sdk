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
     * Expected input tensor format: [1, 3, 80, 80] NCHW, BGR, normalized x/255.0f.
     */
    @Synchronized
    fun classify(croppedFaceBitmap: Bitmap): AntiSpoofResult {
        val interp = interpreter ?: return AntiSpoofResult(false, 0f, 1f, 0f)

        val inputBuffer = ByteBuffer.allocateDirect(1 * inputChannels * inputSize * inputSize * 4) // Float32: [1, 3, 80, 80]
        inputBuffer.order(ByteOrder.nativeOrder())
        inputBuffer.rewind()

        val intValues = IntArray(inputSize * inputSize)
        val resized = if (croppedFaceBitmap.width == inputSize && croppedFaceBitmap.height == inputSize) {
            croppedFaceBitmap
        } else {
            Bitmap.createScaledBitmap(croppedFaceBitmap, inputSize, inputSize, true)
        }

        resized.getPixels(intValues, 0, inputSize, 0, 0, inputSize, inputSize)
        val numPixels = inputSize * inputSize

        // Populate tensor in NCHW format [1, 3, 80, 80] with BGR channel order and x / 255.0f scaling
        // Channel 0: Blue [80 x 80]
        for (i in 0 until numPixels) {
            val b = (intValues[i] and 0xFF).toFloat() / 255.0f
            inputBuffer.putFloat(b)
        }

        // Channel 1: Green [80 x 80]
        for (i in 0 until numPixels) {
            val g = ((intValues[i] shr 8) and 0xFF).toFloat() / 255.0f
            inputBuffer.putFloat(g)
        }

        // Channel 2: Red [80 x 80]
        for (i in 0 until numPixels) {
            val r = ((intValues[i] shr 16) and 0xFF).toFloat() / 255.0f
            inputBuffer.putFloat(r)
        }

        // Output tensor shape: [1, 3] -> [Spoof2D, Real/Live, Spoof3D] with Softmax already applied by model
        val outputBuffer = Array(1) { FloatArray(numClasses) }

        interp.run(inputBuffer, outputBuffer)

        val probabilities = outputBuffer[0]
        val spoof2d = probabilities[0]
        val realScore = probabilities[1]
        val spoof3d = probabilities[2]

        return AntiSpoofResult(
            isReal = realScore >= 0.60f && realScore > spoof2d && realScore > spoof3d,
            realConfidence = realScore,
            spoof2dConfidence = spoof2d,
            spoof3dConfidence = spoof3d
        )
    }

    fun close() {
        interpreter?.close()
        interpreter = null
        gpuDelegate?.close()
        gpuDelegate = null
    }
}
