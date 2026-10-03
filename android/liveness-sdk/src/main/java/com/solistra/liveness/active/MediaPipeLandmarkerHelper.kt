package com.solistra.liveness.active

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.SystemClock
import com.google.mediapipe.framework.image.BitmapExtractor
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult

/**
 * Encapsulated MediaPipe Face Landmarker Helper for Android.
 * Runs in LIVE_STREAM mode with GPU/CPU acceleration.
 */
class MediaPipeLandmarkerHelper(
    private val context: Context,
    private val useGpu: Boolean = true,
    private val modelAssetName: String = "face_landmarker.task",
    private val listener: LandmarkerListener
) {

    interface LandmarkerListener {
        fun onLandmarksDetected(
            result: FaceLandmarkerResult,
            inputBitmap: Bitmap,
            faceBoundingBox: RectF?,
            blendshapes: Map<String, Float>,
            headPose: HeadPose,
            timestampMs: Long
        )
        fun onError(error: Exception)
    }

    private var faceLandmarker: FaceLandmarker? = null
    private var lastTimestampMs: Long = -1L

    init {
        setupFaceLandmarker()
    }

    private fun setupFaceLandmarker() {
        try {
            faceLandmarker = createLandmarker(gpuEnabled = useGpu)
        } catch (e: Exception) {
            if (useGpu) {
                try {
                    // Fallback to CPU delegate if GPU delegate fails on current hardware
                    faceLandmarker = createLandmarker(gpuEnabled = false)
                } catch (cpuError: Exception) {
                    listener.onError(cpuError)
                }
            } else {
                listener.onError(e)
            }
        }
    }

    private fun createLandmarker(gpuEnabled: Boolean): FaceLandmarker {
        val baseOptionsBuilder = BaseOptions.builder()
            .setModelAssetPath(modelAssetName)

        if (gpuEnabled) {
            baseOptionsBuilder.setDelegate(Delegate.GPU)
        } else {
            baseOptionsBuilder.setDelegate(Delegate.CPU)
        }

        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(baseOptionsBuilder.build())
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumFaces(2) // Detect up to 2 faces to identify multi-face spoof attempts
            .setOutputFaceBlendshapes(true)
            .setOutputFacialTransformationMatrixes(true)
            .setResultListener { result, mpImage ->
                processResult(result, mpImage)
            }
            .setErrorListener { error ->
                listener.onError(Exception(error.message))
            }
            .build()

        return FaceLandmarker.createFromOptions(context, options)
    }

    /**
     * Feeds camera frames to MediaPipe asynchronously with monotonic timestamp guarantee.
     */
    @Synchronized
    fun detectAsync(bitmap: Bitmap, timestampMs: Long = SystemClock.uptimeMillis()) {
        val landmarker = faceLandmarker ?: return
        val currentTimestamp = if (timestampMs <= lastTimestampMs) {
            lastTimestampMs + 1
        } else {
            timestampMs
        }
        lastTimestampMs = currentTimestamp

        val mpImage = BitmapImageBuilder(bitmap).build()
        landmarker.detectAsync(mpImage, currentTimestamp)
    }

    private fun processResult(result: FaceLandmarkerResult, mpImage: MPImage) {
        val bitmap = try {
            BitmapExtractor.extract(mpImage)
        } catch (e: Exception) {
            Bitmap.createBitmap(mpImage.width, mpImage.height, Bitmap.Config.ARGB_8888)
        }

        val faceLandmarksList = result.faceLandmarks()
        if (faceLandmarksList.isEmpty()) {
            listener.onLandmarksDetected(
                result = result,
                inputBitmap = bitmap,
                faceBoundingBox = null,
                blendshapes = emptyMap(),
                headPose = HeadPose(0f, 0f, 0f),
                timestampMs = result.timestampMs()
            )
            return
        }

        // Primary face landmarks (0th)
        val primaryFaceLandmarks = faceLandmarksList[0]

        // Calculate normalized bounding box
        var minX = 1.0f
        var minY = 1.0f
        var maxX = 0.0f
        var maxY = 0.0f

        for (landmark in primaryFaceLandmarks) {
            minX = minX.coerceAtMost(landmark.x())
            minY = minY.coerceAtMost(landmark.y())
            maxX = maxX.coerceAtLeast(landmark.x())
            maxY = maxY.coerceAtLeast(landmark.y())
        }

        val boundingBox = RectF(
            minX * bitmap.width,
            minY * bitmap.height,
            maxX * bitmap.width,
            maxY * bitmap.height
        )

        // Extract blendshapes
        val blendshapesMap = mutableMapOf<String, Float>()
        val blendshapeCategories = result.faceBlendshapes()
        if (blendshapeCategories.isPresent && blendshapeCategories.get().isNotEmpty()) {
            for (category in blendshapeCategories.get()[0]) {
                blendshapesMap[category.categoryName()] = category.score()
            }
        }

        // Extract head pose from transformation matrix
        var headPose = HeadPose(0f, 0f, 0f)
        val matrixes = result.facialTransformationMatrixes()
        if (matrixes.isPresent && matrixes.get().isNotEmpty()) {
            val matrixData = matrixes.get()[0]
            headPose = HeadPoseCalculator.fromTransformationMatrix(matrixData)
        }

        listener.onLandmarksDetected(
            result = result,
            inputBitmap = bitmap,
            faceBoundingBox = boundingBox,
            blendshapes = blendshapesMap,
            headPose = headPose,
            timestampMs = result.timestampMs()
        )
    }

    fun close() {
        faceLandmarker?.close()
        faceLandmarker = null
    }
}
