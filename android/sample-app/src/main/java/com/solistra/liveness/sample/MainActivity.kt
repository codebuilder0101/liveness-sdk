package com.solistra.liveness.sample

import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.view.Window
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.solistra.liveness.core.LivenessChallenge
import com.solistra.liveness.core.LivenessConfig
import com.solistra.liveness.core.LivenessResult
import com.solistra.liveness.sample.databinding.ActivityMainBinding
import com.solistra.liveness.ui.LivenessActivity
import com.solistra.liveness.ui.LivenessSDKResultHolder

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val historyAdapter = HistoryAdapter()
    private var lastCapturedFace: Bitmap? = null

    // Register contract for Liveness Verification
    private val livenessLauncher = registerForActivityResult(
        LivenessActivity.Contract()
    ) { result: LivenessResult? ->
        handleVerificationResult(result)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        setupHistoryRecyclerView()
        applyPreset(Preset.STANDARD)
    }

    private fun setupUI() {
        // Preset selection toggle
        binding.toggleGroupPreset.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                when (checkedId) {
                    R.id.btnPresetStandard -> applyPreset(Preset.STANDARD)
                    R.id.btnPresetHighSec -> applyPreset(Preset.HIGH_SECURITY)
                    R.id.btnPresetCustom -> applyPreset(Preset.CUSTOM)
                }
            }
        }

        // Sliders value listeners
        binding.sliderTimeout.addOnChangeListener { _, value, _ ->
            binding.tvTimeoutVal.text = String.format("%.1fs", value)
        }

        binding.sliderSpoofThreshold.addOnChangeListener { _, value, _ ->
            binding.tvSpoofThresholdVal.text = "${value.toInt()}%"
        }

        binding.sliderSampleSize.addOnChangeListener { _, value, _ ->
            binding.tvSampleSizeVal.text = "${value.toInt()} frames"
        }

        // Launch button
        binding.btnStartVerification.setOnClickListener {
            LivenessSDKResultHolder.lastResult = null
            LivenessSDKResultHolder.lastError = null
            LivenessSDKResultHolder.fullResolutionBitmap = null
            val config = buildLivenessConfig()
            livenessLauncher.launch(config)
        }

        // Click on face thumbnail to inspect
        binding.ivVerifiedFace.setOnClickListener {
            lastCapturedFace?.let { showImageInspectionDialog(it) }
        }

        // Clear history
        binding.btnClearHistory.setOnClickListener {
            historyAdapter.clear()
            updateHistoryVisibility()
        }
    }

    private fun setupHistoryRecyclerView() {
        binding.rvHistory.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = historyAdapter
        }
        updateHistoryVisibility()
    }

    private fun updateHistoryVisibility() {
        if (historyAdapter.isEmpty()) {
            binding.tvEmptyHistory.visibility = View.VISIBLE
            binding.rvHistory.visibility = View.GONE
        } else {
            binding.tvEmptyHistory.visibility = View.GONE
            binding.rvHistory.visibility = View.VISIBLE
        }
    }

    private fun buildLivenessConfig(): LivenessConfig {
        // Gather selected challenges
        val selectedChallenges = mutableListOf<LivenessChallenge>()
        if (binding.chipBlink.isChecked) selectedChallenges.add(LivenessChallenge.BLINK)
        if (binding.chipSmile.isChecked) selectedChallenges.add(LivenessChallenge.SMILE)
        if (binding.chipTurnLeft.isChecked) selectedChallenges.add(LivenessChallenge.TURN_LEFT)
        if (binding.chipTurnRight.isChecked) selectedChallenges.add(LivenessChallenge.TURN_RIGHT)
        if (binding.chipNodHead.isChecked) selectedChallenges.add(LivenessChallenge.NOD_HEAD)
        if (binding.chipOpenMouth.isChecked) selectedChallenges.add(LivenessChallenge.OPEN_MOUTH)

        val timeoutSeconds = binding.sliderTimeout.value
        val spoofThreshold = binding.sliderSpoofThreshold.value / 100f
        val sampleSize = binding.sliderSampleSize.value.toInt()
        val screenSecurity = binding.switchScreenSecurity.isChecked
        val useGpu = binding.switchGpu.isChecked
        val debugOverlay = binding.switchDebugOverlay.isChecked

        return LivenessConfig(
            challenges = if (selectedChallenges.isNotEmpty()) selectedChallenges else listOf(LivenessChallenge.BLINK),
            challengeTimeoutSeconds = timeoutSeconds,
            passiveSpoofThreshold = spoofThreshold,
            passiveFrameSampleSize = sampleSize,
            enableScreenSecurity = screenSecurity,
            useGpuDelegate = useGpu,
            enableDebugOverlay = debugOverlay,
            enableDebugLogging = true
        )
    }

    private fun handleVerificationResult(result: LivenessResult?) {
        val fallbackResult = result ?: LivenessSDKResultHolder.lastResult
        val error = LivenessSDKResultHolder.lastError

        if (fallbackResult != null && fallbackResult.isLive) {
            // Success Outcome
            binding.tvStatusBadge.text = "PASSED"
            binding.tvStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_success))
            binding.tvStatusBadge.setBackgroundResource(R.drawable.bg_badge_passed)

            val passivePercent = (fallbackResult.passiveScore * 100).toInt()
            binding.tvPassiveScoreVal.text = "$passivePercent%"
            binding.tvPassiveScoreVal.setTextColor(ContextCompat.getColor(this, R.color.status_success))
            binding.indicatorPassiveScore.progress = passivePercent
            binding.indicatorPassiveScore.setIndicatorColor(ContextCompat.getColor(this, R.color.status_success))

            binding.tvDurationVal.text = "Session Duration: ${fallbackResult.sessionDurationMs} ms"
            val challengeNames = fallbackResult.completedChallenges.joinToString(", ") { it.name }
            binding.tvChallengesCompleted.text = "Challenges: $challengeNames"

            val faceBitmap = fallbackResult.bestFaceImage ?: LivenessSDKResultHolder.fullResolutionBitmap
            faceBitmap?.let {
                lastCapturedFace = it
                binding.ivVerifiedFace.setImageBitmap(it)
            }

            binding.tvErrorBanner.visibility = View.GONE

            historyAdapter.addItem(
                LivenessHistoryItem(
                    isLive = true,
                    passiveScore = fallbackResult.passiveScore,
                    sessionDurationMs = fallbackResult.sessionDurationMs,
                    completedChallenges = fallbackResult.completedChallenges
                )
            )
        } else {
            // Failed Outcome
            binding.tvStatusBadge.text = "FAILED"
            binding.tvStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_error))
            binding.tvStatusBadge.setBackgroundResource(R.drawable.bg_badge_failed)

            val spoofScore = fallbackResult?.passiveScore ?: 0f
            val passivePercent = (spoofScore * 100).toInt()
            binding.tvPassiveScoreVal.text = if (spoofScore > 0f) "$passivePercent%" else "0%"
            binding.tvPassiveScoreVal.setTextColor(ContextCompat.getColor(this, R.color.status_error))
            binding.indicatorPassiveScore.progress = passivePercent
            binding.indicatorPassiveScore.setIndicatorColor(ContextCompat.getColor(this, R.color.status_error))

            binding.tvDurationVal.text = "Session Duration: ${fallbackResult?.sessionDurationMs ?: 0} ms"
            binding.tvChallengesCompleted.text = "Challenges: None"

            val errorMsg = error?.message ?: "Verification failed or cancelled by user"
            binding.tvErrorBanner.text = "Error: $errorMsg"
            binding.tvErrorBanner.visibility = View.VISIBLE

            historyAdapter.addItem(
                LivenessHistoryItem(
                    isLive = false,
                    passiveScore = spoofScore,
                    sessionDurationMs = fallbackResult?.sessionDurationMs ?: 0,
                    completedChallenges = emptyList(),
                    errorMessage = errorMsg
                )
            )
        }

        updateHistoryVisibility()
    }

    private fun showImageInspectionDialog(bitmap: Bitmap) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val imageView = ImageView(this).apply {
            setImageBitmap(bitmap)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(24, 24, 24, 24)
        }

        dialog.setContentView(imageView)
        dialog.show()
    }

    private enum class Preset {
        STANDARD,
        HIGH_SECURITY,
        CUSTOM
    }

    private fun applyPreset(preset: Preset) {
        when (preset) {
            Preset.STANDARD -> {
                binding.chipBlink.isChecked = true
                binding.chipSmile.isChecked = true
                binding.chipTurnLeft.isChecked = false
                binding.chipTurnRight.isChecked = false
                binding.chipNodHead.isChecked = false
                binding.chipOpenMouth.isChecked = false

                binding.sliderTimeout.value = 8.0f
                binding.sliderSpoofThreshold.value = 55f
                binding.sliderSampleSize.value = 8f
                binding.switchScreenSecurity.isChecked = true
                binding.switchGpu.isChecked = true
                binding.switchDebugOverlay.isChecked = true
            }

            Preset.HIGH_SECURITY -> {
                binding.chipBlink.isChecked = true
                binding.chipSmile.isChecked = true
                binding.chipTurnLeft.isChecked = true
                binding.chipTurnRight.isChecked = false
                binding.chipNodHead.isChecked = false
                binding.chipOpenMouth.isChecked = true

                binding.sliderTimeout.value = 5.0f
                binding.sliderSpoofThreshold.value = 70f
                binding.sliderSampleSize.value = 12f
                binding.switchScreenSecurity.isChecked = true
                binding.switchGpu.isChecked = true
                binding.switchDebugOverlay.isChecked = true
            }

            Preset.CUSTOM -> {
                // Keep user's current manual selections
            }
        }
    }
}
