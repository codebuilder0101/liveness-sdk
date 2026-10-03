package com.solistra.liveness.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.VibratorManager
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.solistra.liveness.core.LivenessCallback
import com.solistra.liveness.core.LivenessChallenge
import com.solistra.liveness.core.LivenessConfig
import com.solistra.liveness.core.LivenessException
import com.solistra.liveness.core.LivenessResult
import com.solistra.liveness.core.LivenessState
import com.solistra.liveness.databinding.ActivityLivenessBinding
import com.solistra.liveness.engine.LivenessEngine

class LivenessActivity : AppCompatActivity(), LivenessCallback {

    private lateinit var binding: ActivityLivenessBinding
    private var engine: LivenessEngine? = null
    private var cameraManager: CameraPreviewManager? = null
    private var config: LivenessConfig = LivenessConfig.default()

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            initializeCameraAndEngine()
        } else {
            Toast.makeText(this, "Camera permission is required for liveness verification", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        config = intent?.let {
            IntentCompat.getParcelableExtra(it, EXTRA_CONFIG, LivenessConfig::class.java)
        } ?: LivenessConfig.default()

        if (config.enableScreenSecurity) {
            // Anti-tamper: Block screenshots, screen recording, and screen casting
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        }

        binding = ActivityLivenessBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnClose.setOnClickListener {
            finish()
        }

        checkCameraPermission()
    }

    private fun checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            initializeCameraAndEngine()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun initializeCameraAndEngine() {
        engine = LivenessEngine(
            context = applicationContext,
            config = config,
            callback = this
        )

        cameraManager = CameraPreviewManager(
            context = this,
            lifecycleOwner = this,
            previewView = binding.previewView,
            onFrameAvailable = { frameBitmap ->
                engine?.processFrame(frameBitmap)
            }
        )

        cameraManager?.startCamera(
            onSuccess = {
                engine?.startSession()
            },
            onError = { exc ->
                Toast.makeText(this, "Camera initialization error: ${exc.message}", Toast.LENGTH_SHORT).show()
            }
        )
    }

    override fun onStateChanged(state: LivenessState) {
        when (state) {
            is LivenessState.FaceAlignment -> {
                binding.tvStepIndicator.visibility = View.GONE
                binding.timeoutProgressBar.visibility = View.INVISIBLE
                binding.tvPrompt.text = state.message
                val color = if (state.isCentered && state.isAppropriateDistance) Color.parseColor("#00E5FF") else Color.WHITE
                binding.overlayView.setGuideColor(color)
                binding.overlayView.setProgress(0f)
            }

            is LivenessState.PerformingChallenge -> {
                binding.tvStepIndicator.visibility = View.VISIBLE
                binding.tvStepIndicator.text = "CHALLENGE ${state.challengeIndex + 1} OF ${state.totalChallenges}"
                binding.tvPrompt.text = state.challenge.promptText
                binding.timeoutProgressBar.visibility = View.VISIBLE

                val progressPercent = ((state.remainingSeconds / config.challengeTimeoutSeconds) * 100).toInt()
                binding.timeoutProgressBar.progress = progressPercent

                binding.overlayView.setGuideColor(Color.parseColor("#00E5FF")) // Active Cyan
                binding.overlayView.setProgress(state.progress)
            }

            is LivenessState.EvaluatingPassive -> {
                binding.tvStepIndicator.visibility = View.GONE
                binding.tvPrompt.text = "Analyzing security markers..."
                binding.timeoutProgressBar.visibility = View.INVISIBLE
            }

            is LivenessState.Success -> {
                binding.tvPrompt.text = "Verification Complete"
                binding.overlayView.setGuideColor(Color.parseColor("#00E676")) // Success Green
                binding.overlayView.setProgress(1f)
                triggerHapticFeedback(isSuccess = true)
            }

            is LivenessState.Failed -> {
                binding.tvPrompt.text = state.error.message ?: "Verification Failed"
                binding.overlayView.setGuideColor(Color.parseColor("#FF1744")) // Error Red
                triggerHapticFeedback(isSuccess = false)
            }

            else -> {}
        }
    }

    override fun onChallengeProgress(challenge: LivenessChallenge, progress: Float) {
        binding.overlayView.setProgress(progress)
    }

    override fun onSuccess(result: LivenessResult) {
        LivenessSDKResultHolder.lastResult = result
        val dataIntent = Intent().apply {
            putExtra(EXTRA_RESULT, result)
        }
        setResult(RESULT_OK, dataIntent)
        binding.root.postDelayed({ finish() }, 1000)
    }

    override fun onError(error: LivenessException) {
        LivenessSDKResultHolder.lastError = error
        val dataIntent = Intent().apply {
            putExtra(EXTRA_ERROR_MESSAGE, error.message)
            putExtra(EXTRA_ERROR_CODE, error.errorCode)
        }
        setResult(RESULT_CANCELED, dataIntent)
        binding.root.postDelayed({ finish() }, 1800)
    }

    private fun triggerHapticFeedback(isSuccess: Boolean) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                val vibrator = vibratorManager.defaultVibrator
                val effect = if (isSuccess) {
                    VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                } else {
                    VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
                }
                vibrator.vibrate(effect)
            }
        } catch (e: Exception) {
            // Ignored if device has no vibrator
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraManager?.shutdown()
        engine?.destroy()
    }

    companion object {
        const val EXTRA_CONFIG = "extra_liveness_config"
        const val EXTRA_RESULT = "extra_liveness_result"
        const val EXTRA_ERROR_MESSAGE = "extra_liveness_error_message"
        const val EXTRA_ERROR_CODE = "extra_liveness_error_code"

        fun createIntent(context: Context, config: LivenessConfig = LivenessConfig.default()): Intent {
            return Intent(context, LivenessActivity::class.java).apply {
                putExtra(EXTRA_CONFIG, config)
            }
        }
    }

    /**
     * Recommended modern ActivityResultContract for launching LivenessActivity.
     */
    class Contract : ActivityResultContract<LivenessConfig, LivenessResult?>() {
        override fun createIntent(context: Context, input: LivenessConfig): Intent {
            return LivenessActivity.createIntent(context, input)
        }

        override fun parseResult(resultCode: Int, intent: Intent?): LivenessResult? {
            if (resultCode != Activity.RESULT_OK || intent == null) {
                return null
            }
            return IntentCompat.getParcelableExtra(intent, EXTRA_RESULT, LivenessResult::class.java)
        }
    }
}

object LivenessSDKResultHolder {
    @Volatile
    var lastResult: LivenessResult? = null
    @Volatile
    var lastError: LivenessException? = null
}
