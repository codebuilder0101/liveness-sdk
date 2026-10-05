package com.solistra.liveness.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import com.solistra.liveness.active.BlendshapeChallengeEvaluator
import com.solistra.liveness.active.HeadPose
import com.solistra.liveness.active.MediaPipeLandmarkerHelper
import com.solistra.liveness.core.LivenessCallback
import com.solistra.liveness.core.LivenessChallenge
import com.solistra.liveness.core.LivenessConfig
import com.solistra.liveness.core.LivenessException
import com.solistra.liveness.core.LivenessResult
import com.solistra.liveness.core.LivenessState
import com.solistra.liveness.passive.MiniFASNetClassifier
import com.solistra.liveness.passive.MultiScaleCropper
import com.solistra.liveness.passive.SpoofScoreSmoother
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Main Liveness Engine coordinating Active & Passive verification pipelines.
 */
class LivenessEngine(
    private val context: Context,
    private val config: LivenessConfig = LivenessConfig.default(),
    private val callback: LivenessCallback
) : MediaPipeLandmarkerHelper.LandmarkerListener {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val passiveExecutor = Executors.newSingleThreadExecutor()
    private val isPassiveInferencing = AtomicBoolean(false)

    private var landmarkerHelper: MediaPipeLandmarkerHelper? = null
    private var miniFASNetClassifier: MiniFASNetClassifier? = null

    private val challengeEvaluator = BlendshapeChallengeEvaluator(config)
    private val spoofScoreSmoother = SpoofScoreSmoother(
        windowSize = config.passiveFrameSampleSize,
        passThreshold = config.passiveSpoofThreshold
    )

    private val stateLock = Any()

    private var sessionStartTimeMs = 0L
    private var challengeStartTimeMs = 0L
    private var currentChallengeIndex = 0
    private var activeChallenges = listOf<LivenessChallenge>()
    private val completedChallenges = mutableListOf<LivenessChallenge>()

    @Volatile
    private var currentState: LivenessState = LivenessState.Idle
    @Volatile
    private var isSessionActive = false
    private var bestFaceBitmap: Bitmap? = null

    // Oval Guide boundaries (normalized 0.0 to 1.0)
    private var guideRect = RectF(0.15f, 0.15f, 0.85f, 0.85f)
    private var stableAlignmentFrames = 0

    init {
        initializeEngines()
    }

    private fun initializeEngines() {
        updateState(LivenessState.Initializing)
        try {
            landmarkerHelper = MediaPipeLandmarkerHelper(
                context = context,
                useGpu = config.useGpuDelegate,
                listener = this
            )
            miniFASNetClassifier = MiniFASNetClassifier(
                context = context,
                useGpu = config.useGpuDelegate
            )
            updateState(LivenessState.Idle)
        } catch (e: Exception) {
            updateState(LivenessState.Failed(LivenessException.ModelLoadFailed(e.message ?: "Unknown model load error")))
        }
    }

    fun setGuideRect(rect: RectF) {
        synchronized(stateLock) {
            this.guideRect = rect
        }
    }

    fun getCurrentState(): LivenessState = currentState

    fun startSession() {
        synchronized(stateLock) {
            if (isSessionActive) return

            sessionStartTimeMs = SystemClock.uptimeMillis()
            currentChallengeIndex = 0
            completedChallenges.clear()
            spoofScoreSmoother.reset()
            bestFaceBitmap = null
            stableAlignmentFrames = 0
            isSessionActive = true

            // Generate challenge sequence
            activeChallenges = if (config.challenges.isNotEmpty()) {
                config.challenges
            } else {
                val pool = LivenessChallenge.values().toMutableList()
                pool.shuffle()
                pool.take(config.randomChallengeCount)
            }

            updateState(
                LivenessState.FaceAlignment(
                    message = "Center your face in the oval",
                    isFaceDetected = false,
                    isCentered = false,
                    isAppropriateDistance = false
                )
            )
        }
    }

    fun processFrame(bitmap: Bitmap) {
        if (!isSessionActive) return
        landmarkerHelper?.detectAsync(bitmap)
    }

    override fun onLandmarksDetected(
        result: FaceLandmarkerResult,
        inputBitmap: Bitmap,
        faceBoundingBox: RectF?,
        blendshapes: Map<String, Float>,
        headPose: HeadPose,
        timestampMs: Long
    ) {
        synchronized(stateLock) {
            if (!isSessionActive) return

            // 1. Check for multiple faces
            if (result.faceLandmarks().size > 1) {
                finishWithError(LivenessException.MultipleFacesDetected())
                return
            }

            // 2. Check if face is detected
            if (faceBoundingBox == null || result.faceLandmarks().isEmpty()) {
                if (currentState is LivenessState.PerformingChallenge) {
                    updateState(
                        LivenessState.FaceAlignment(
                            message = "Face lost. Please stay in the oval",
                            isFaceDetected = false,
                            isCentered = false,
                            isAppropriateDistance = false
                        )
                    )
                }
                return
            }

            // 3. Evaluate Lighting / Luminance
            val avgLuminance = calculateLuminance(inputBitmap, faceBoundingBox)
            if (avgLuminance < config.minLuminanceThreshold) {
                if (currentState is LivenessState.FaceAlignment) {
                    updateState(
                        LivenessState.FaceAlignment(
                            message = "Lighting too dark. Move to a brighter area",
                            isFaceDetected = true,
                            isCentered = false,
                            isAppropriateDistance = false
                        )
                    )
                }
                return
            }

            // 4. Evaluate Face Alignment & Positioning
            val isAligned = verifyAlignment(faceBoundingBox, inputBitmap.width, inputBitmap.height, headPose)

            // 5. Concurrently run Multi-Scale Passive Anti-Spoofing on a background thread
            dispatchPassiveInference(inputBitmap, faceBoundingBox, headPose)

            // 6. State Machine progression
            when (val state = currentState) {
                is LivenessState.FaceAlignment -> {
                    if (isAligned) {
                        stableAlignmentFrames++
                        if (stableAlignmentFrames > 6) { // Require stable position for ~180ms
                            startNextChallenge(timestampMs)
                        }
                    } else {
                        stableAlignmentFrames = 0
                    }
                }

                is LivenessState.PerformingChallenge -> {
                    val elapsedSeconds = (timestampMs - challengeStartTimeMs) / 1000f
                    val remainingSeconds = (config.challengeTimeoutSeconds - elapsedSeconds).coerceAtLeast(0f)

                    if (elapsedSeconds >= config.challengeTimeoutSeconds) {
                        finishWithError(LivenessException.ChallengeTimeout(state.challenge))
                        return
                    }

                    val (isCompleted, progress) = challengeEvaluator.evaluateFrame(blendshapes, headPose, timestampMs)

                    mainHandler.post {
                        callback.onChallengeProgress(state.challenge, progress)
                    }

                    if (isCompleted) {
                        completedChallenges.add(state.challenge)
                        currentChallengeIndex++

                        if (currentChallengeIndex < activeChallenges.size) {
                            startNextChallenge(timestampMs)
                        } else {
                            // All active challenges completed, verify passive anti-spoof
                            evaluateFinalLiveness()
                        }
                    } else {
                        updateState(
                            LivenessState.PerformingChallenge(
                                challenge = state.challenge,
                                challengeIndex = currentChallengeIndex,
                                totalChallenges = activeChallenges.size,
                                progress = progress,
                                remainingSeconds = remainingSeconds
                            )
                        )
                    }
                }

                else -> {}
            }
        }
    }

    private fun verifyAlignment(faceBbox: RectF, frameWidth: Int, frameHeight: Int, headPose: HeadPose): Boolean {
        val normLeft = faceBbox.left / frameWidth
        val normTop = faceBbox.top / frameHeight
        val normRight = faceBbox.right / frameWidth
        val normBottom = faceBbox.bottom / frameHeight
        val normWidth = normRight - normLeft

        // Additive tolerance for symmetric margin
        val tolerance = 0.06f
        val isInsideOval = normLeft >= (guideRect.left - tolerance) &&
                normRight <= (guideRect.right + tolerance) &&
                normTop >= (guideRect.top - tolerance) &&
                normBottom <= (guideRect.bottom + tolerance)

        val isAppropriateDistance = normWidth in 0.28f..0.65f
        // Allow ±18° yaw/pitch tolerance so users don't get stuck in alignment
        // for mild head tilt/turn (natural when preparing to smile or nod)
        val isFacingForward = abs(headPose.yaw) < 18f && abs(headPose.pitch) < 18f

        val isCentered = isInsideOval && isFacingForward

        if (currentState is LivenessState.FaceAlignment) {
            val message = when {
                !isAppropriateDistance && normWidth < 0.28f -> "Move closer to the camera"
                !isAppropriateDistance && normWidth > 0.65f -> "Move slightly back"
                !isInsideOval -> "Align your face inside the oval"
                !isFacingForward -> "Look straight at the camera"
                else -> "Hold still..."
            }
            updateState(
                LivenessState.FaceAlignment(
                    message = message,
                    isFaceDetected = true,
                    isCentered = isCentered,
                    isAppropriateDistance = isAppropriateDistance
                )
            )
        }

        return isCentered && isAppropriateDistance
    }

    private fun calculateLuminance(bitmap: Bitmap, faceBbox: RectF): Float {
        val left = faceBbox.left.toInt().coerceIn(0, bitmap.width - 1)
        val top = faceBbox.top.toInt().coerceIn(0, bitmap.height - 1)
        val width = faceBbox.width().toInt().coerceIn(1, bitmap.width - left)
        val height = faceBbox.height().toInt().coerceIn(1, bitmap.height - top)

        val sampleStepX = maxOf(1, width / 20)
        val sampleStepY = maxOf(1, height / 20)
        var totalLum = 0.0
        var samples = 0

        for (y in top until (top + height) step sampleStepY) {
            for (x in left until (left + width) step sampleStepX) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                totalLum += (0.299 * r + 0.587 * g + 0.114 * b)
                samples++
            }
        }
        return if (samples > 0) (totalLum / samples).toFloat() else 128f
    }

    private fun dispatchPassiveInference(fullBitmap: Bitmap, faceBbox: RectF, headPose: HeadPose) {
        val classifier = miniFASNetClassifier ?: return

        // Drop frame if previous passive inference is still executing to avoid pipeline lag
        if (!isPassiveInferencing.compareAndSet(false, true)) {
            return
        }

        // Deep copy bitmap snapshot for thread-safe asynchronous processing
        val frameCopy = try {
            fullBitmap.copy(Bitmap.Config.ARGB_8888, false)
        } catch (e: Exception) {
            isPassiveInferencing.set(false)
            return
        }

        passiveExecutor.submit {
            try {
                // Scale 2.7x: Canonical context crop matching 2.7_80x80_MiniFASNetV2 weights
                val crop27 = MultiScaleCropper.cropFaceWithScale(frameCopy, faceBbox, scaleFactor = 2.7f, targetSize = 80)
                val result = classifier.classify(crop27)

                spoofScoreSmoother.addSample(result.realConfidence)

                synchronized(stateLock) {
                    if (abs(headPose.yaw) < 8f && abs(headPose.pitch) < 8f && result.realConfidence > 0.7f) {
                        bestFaceBitmap = frameCopy.copy(Bitmap.Config.ARGB_8888, false)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isPassiveInferencing.set(false)
            }
        }
    }

    private fun startNextChallenge(timestampMs: Long) {
        val nextChallenge = activeChallenges[currentChallengeIndex]
        challengeStartTimeMs = timestampMs
        challengeEvaluator.startChallenge(nextChallenge, timestampMs)

        updateState(
            LivenessState.PerformingChallenge(
                challenge = nextChallenge,
                challengeIndex = currentChallengeIndex,
                totalChallenges = activeChallenges.size,
                progress = 0f,
                remainingSeconds = config.challengeTimeoutSeconds
            )
        )
    }

    private fun evaluateFinalLiveness() {
        updateState(LivenessState.EvaluatingPassive(0.9f))

        passiveExecutor.submit {
            val avgPassiveScore = spoofScoreSmoother.getAverageScore()
            val isLive = spoofScoreSmoother.isReliablyReal()

            synchronized(stateLock) {
                if (isLive) {
                    val sessionDuration = SystemClock.uptimeMillis() - sessionStartTimeMs
                    val rawBestImage = bestFaceBitmap
                    val thumbnail = createScaledThumbnail(rawBestImage, maxDimension = 400)
                    
                    // Keep full resolution bitmap in memory singleton
                    com.solistra.liveness.ui.LivenessSDKResultHolder.fullResolutionBitmap = rawBestImage

                    val result = LivenessResult(
                        isLive = true,
                        passiveScore = avgPassiveScore,
                        completedChallenges = ArrayList(completedChallenges),
                        bestFaceImage = thumbnail,
                        sessionDurationMs = sessionDuration
                    )
                    isSessionActive = false
                    updateState(LivenessState.Success(result))
                    mainHandler.post { callback.onSuccess(result) }
                } else {
                    finishWithError(LivenessException.PassiveAntiSpoofFailed(avgPassiveScore))
                }
            }
        }
    }

    private fun createScaledThumbnail(bitmap: Bitmap?, maxDimension: Int = 400): Bitmap? {
        if (bitmap == null) return null
        val width = bitmap.width
        val height = bitmap.height
        if (width <= maxDimension && height <= maxDimension) return bitmap

        val ratio = minOf(maxDimension.toFloat() / width, maxDimension.toFloat() / height)
        val dstW = (width * ratio).toInt().coerceAtLeast(1)
        val dstH = (height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, dstW, dstH, true)
    }

    private fun finishWithError(error: LivenessException) {
        isSessionActive = false
        updateState(LivenessState.Failed(error))
        mainHandler.post { callback.onError(error) }
    }

    private fun updateState(newState: LivenessState) {
        currentState = newState
        mainHandler.post { callback.onStateChanged(newState) }
    }

    override fun onError(error: Exception) {
        finishWithError(LivenessException.ModelLoadFailed(error.message ?: "Inference error"))
    }

    fun stopSession() {
        synchronized(stateLock) {
            isSessionActive = false
            updateState(LivenessState.Idle)
        }
    }

    fun destroy() {
        stopSession()
        try {
            passiveExecutor.shutdown()
            if (!passiveExecutor.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                passiveExecutor.shutdownNow()
            }
        } catch (e: Exception) {
            passiveExecutor.shutdownNow()
        }
        landmarkerHelper?.close()
        miniFASNetClassifier?.close()
    }
}
