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
import com.solistra.liveness.network.MobfacilAuthConfig
import com.solistra.liveness.network.MobfacilAuthResult
import com.solistra.liveness.network.MobfacilToken
import com.solistra.liveness.network.MobfacilTokenManager
import com.solistra.liveness.network.MobfacilUploadResult
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
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
        setupMobfacilUI()
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
        val enableMobfacil = binding.switchMobfacilUpload.isChecked
        val customEndpoint = binding.etCollectorEndpoint.text?.toString()?.trim().orEmpty()

        val authConfig = if (enableMobfacil) {
            val staging = MobfacilAuthConfig.staging()
            if (customEndpoint.isNotEmpty()) {
                if (customEndpoint.startsWith("http://") || customEndpoint.startsWith("https://")) {
                    val uri = java.net.URI(customEndpoint)
                    val base = "${uri.scheme}://${uri.authority}"
                    val path = uri.path
                    staging.copy(baseUrl = base, dataEndpoint = path)
                } else {
                    staging.copy(dataEndpoint = customEndpoint)
                }
            } else {
                staging
            }
        } else null

        return LivenessConfig(
            challenges = if (selectedChallenges.isNotEmpty()) selectedChallenges else listOf(LivenessChallenge.BLINK),
            challengeTimeoutSeconds = timeoutSeconds,
            passiveSpoofThreshold = spoofThreshold,
            passiveFrameSampleSize = sampleSize,
            enableScreenSecurity = screenSecurity,
            useGpuDelegate = useGpu,
            enableDebugOverlay = debugOverlay,
            enableDebugLogging = true,
            mobfacilAuthConfig = authConfig,
            autoUploadToMobfacil = enableMobfacil
        )
    }

    private fun setupMobfacilUI() {
        val tokenManager = MobfacilTokenManager.getInstance(MobfacilAuthConfig.staging(), this)

        fun updateTokenDisplay(token: MobfacilToken) {
            binding.tvMobfacilTokenBadge.text = "VALID (200 OK)"
            binding.tvMobfacilTokenBadge.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_success))
            binding.tvMobfacilTokenBadge.setBackgroundResource(R.drawable.bg_badge_passed)
            binding.tvMobfacilTokenStatus.text = "Token: Bearer ${token.accessToken.take(24)}...\nStatus: Active JWT | Expires in: ${token.remainingSeconds()}s\nClient: ${tokenManager.config.clientId}"
            binding.tvMobfacilTokenStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.brand_cyan))
        }

        // Auto-authenticate on launch with official client credentials
        val cached = tokenManager.getCachedToken()
        if (cached != null && !cached.isExpired()) {
            updateTokenDisplay(cached)
        } else {
            binding.tvMobfacilTokenBadge.text = "CONNECTING..."
            binding.tvMobfacilTokenBadge.setTextColor(ContextCompat.getColor(this, R.color.brand_cyan))
            lifecycleScope.launch {
                val result = tokenManager.authenticate(forceRefresh = false)
                when (result) {
                    is MobfacilAuthResult.Success -> updateTokenDisplay(result.token)
                    is MobfacilAuthResult.Failure -> {
                        binding.tvMobfacilTokenBadge.text = "AUTH FAILED"
                        binding.tvMobfacilTokenBadge.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_error))
                        binding.tvMobfacilTokenBadge.setBackgroundResource(R.drawable.bg_badge_failed)
                        binding.tvMobfacilTokenStatus.text = "Auth Error [${result.errorCode}]: ${result.message}"
                    }
                }
            }
        }

        binding.btnTestMobfacilAuth.setOnClickListener {
            binding.tvMobfacilTokenBadge.text = "CONNECTING..."
            binding.tvMobfacilTokenBadge.setTextColor(ContextCompat.getColor(this, R.color.brand_cyan))
            lifecycleScope.launch {
                val result = tokenManager.authenticate(forceRefresh = true)
                when (result) {
                    is MobfacilAuthResult.Success -> {
                        updateTokenDisplay(result.token)
                    }
                    is MobfacilAuthResult.Failure -> {
                        binding.tvMobfacilTokenBadge.text = "AUTH FAILED"
                        binding.tvMobfacilTokenBadge.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_error))
                        binding.tvMobfacilTokenBadge.setBackgroundResource(R.drawable.bg_badge_failed)
                        binding.tvMobfacilTokenStatus.text = "Error [${result.errorCode}]: ${result.message}"
                    }
                }
            }
        }

        binding.btnTestDelinquency.setOnClickListener {
            binding.tvMobfacilTokenBadge.text = "TESTING..."
            lifecycleScope.launch {
                val badConfig = MobfacilAuthConfig(
                    clientId = "disabled_client_QA",
                    clientSecret = "invalid_secret"
                )
                val testManager = MobfacilTokenManager(badConfig)
                val result = testManager.authenticate(forceRefresh = true)
                if (result is MobfacilAuthResult.Failure.LicenseBlocked) {
                    binding.tvMobfacilTokenBadge.text = "BLOCKED (400)"
                    binding.tvMobfacilTokenBadge.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_error))
                    binding.tvMobfacilTokenBadge.setBackgroundResource(R.drawable.bg_badge_failed)
                    binding.tvMobfacilTokenStatus.text = "Flow 4 Inadimplência Confirmed (Simulation):\n${result.description}"
                } else {
                    binding.tvMobfacilTokenStatus.text = "Result: $result"
                }
            }
        }

        binding.btnTestRotatedSecret.setOnClickListener {
            binding.tvMobfacilTokenBadge.text = "TESTING..."
            lifecycleScope.launch {
                val badSecretConfig = MobfacilAuthConfig(
                    clientId = "sdk_ken_QA",
                    clientSecret = "COMPLETELY_WRONG_ROTATED_SECRET"
                )
                val testManager = MobfacilTokenManager(badSecretConfig)
                val result = testManager.authenticate(forceRefresh = true)
                if (result is MobfacilAuthResult.Failure.InvalidCredentials) {
                    binding.tvMobfacilTokenBadge.text = "ROTATED (401)"
                    binding.tvMobfacilTokenBadge.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_error))
                    binding.tvMobfacilTokenBadge.setBackgroundResource(R.drawable.bg_badge_failed)
                    binding.tvMobfacilTokenStatus.text = "Flow 5 Secret Rotation Confirmed (Simulation):\n${result.description}"
                } else {
                    binding.tvMobfacilTokenStatus.text = "Result: $result"
                }
            }
        }

        binding.btnTestUploadDirect.setOnClickListener {
            binding.tvMobfacilUploadStatus.text = "Uploading test payload to collector..."
            binding.tvMobfacilUploadStatus.setTextColor(ContextCompat.getColor(this, R.color.brand_cyan))

            val endpointText = binding.etCollectorEndpoint.text?.toString()?.trim().orEmpty()
            val currentAuthConfig = if (endpointText.isNotEmpty()) {
                if (endpointText.startsWith("http://") || endpointText.startsWith("https://")) {
                    val uri = java.net.URI(endpointText)
                    val base = "${uri.scheme}://${uri.authority}"
                    val path = uri.path
                    MobfacilAuthConfig.staging().copy(baseUrl = base, dataEndpoint = path)
                } else {
                    MobfacilAuthConfig.staging().copy(dataEndpoint = endpointText)
                }
            } else {
                MobfacilAuthConfig.staging()
            }

            lifecycleScope.launch {
                val testManager = MobfacilTokenManager.getInstance(currentAuthConfig, this@MainActivity)
                val collector = com.solistra.liveness.network.MobfacilCollectorService(testManager, currentAuthConfig)
                val dummyResult = LivenessResult(
                    isLive = true,
                    passiveScore = 0.92f,
                    completedChallenges = listOf(LivenessChallenge.BLINK, LivenessChallenge.SMILE),
                    bestFaceImage = lastCapturedFace ?: LivenessSDKResultHolder.fullResolutionBitmap,
                    sessionDurationMs = 2800L
                )
                val outcome = collector.uploadLivenessData(dummyResult)
                when (outcome) {
                    is MobfacilUploadResult.Success -> {
                        binding.tvMobfacilUploadStatus.text = "Collector Upload: ${outcome.statusCode} OK (Data Transmitted)\n${outcome.responseBody.take(120)}"
                        binding.tvMobfacilUploadStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_success))
                    }
                    is MobfacilUploadResult.Failure -> {
                        if (outcome.statusCode == 404) {
                            binding.tvMobfacilUploadStatus.text = "Bearer Auth: 200 OK (Valid JWT Token)\nCollector Route: HTTP 404 (Gateway route not yet active on staging)"
                            binding.tvMobfacilUploadStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.brand_cyan))
                        } else {
                            binding.tvMobfacilUploadStatus.text = "Collector Upload [${outcome.errorCode}]: ${outcome.message}\nTarget: ${currentAuthConfig.getFullDataUrl()}"
                            binding.tvMobfacilUploadStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_error))
                        }
                    }
                }
            }
        }

        binding.btnClearMobfacilToken.setOnClickListener {
            tokenManager.invalidateCache()
            binding.tvMobfacilTokenBadge.text = "NO TOKEN"
            binding.tvMobfacilTokenBadge.setTextColor(ContextCompat.getColor(this, R.color.status_neutral))
            binding.tvMobfacilTokenBadge.setBackgroundResource(R.drawable.bg_badge_neutral)
            binding.tvMobfacilTokenStatus.text = "Token: Cache cleared"
        }
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

            // MobFácil Upload Status update
            val uploadResult = fallbackResult.uploadResult
            if (uploadResult != null) {
                when (uploadResult) {
                    is MobfacilUploadResult.Success -> {
                        binding.tvMobfacilUploadStatus.text = "Collector Upload: 200 OK (Transmitted with Bearer Token)"
                        binding.tvMobfacilUploadStatus.setTextColor(ContextCompat.getColor(this, R.color.status_success))
                    }
                    is MobfacilUploadResult.Failure -> {
                        if (uploadResult.statusCode == 404) {
                            binding.tvMobfacilUploadStatus.text = "Bearer Auth: 200 OK (Authenticated)\nCollector: HTTP 404 (Gateway route not yet deployed on staging)"
                            binding.tvMobfacilUploadStatus.setTextColor(ContextCompat.getColor(this, R.color.brand_cyan))
                        } else {
                            binding.tvMobfacilUploadStatus.text = "Collector Upload [${uploadResult.errorCode}]: ${uploadResult.message}"
                            binding.tvMobfacilUploadStatus.setTextColor(ContextCompat.getColor(this, R.color.status_error))
                        }
                    }
                }
            } else if (binding.switchMobfacilUpload.isChecked) {
                binding.tvMobfacilUploadStatus.text = "Collector Upload: Verification complete, awaiting collector sync"
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

            val spoofScore = fallbackResult?.passiveScore
                ?: (if (error is com.solistra.liveness.core.LivenessException.PassiveAntiSpoofFailed) error.score else 0f)
            val passivePercent = (spoofScore * 100).toInt()
            binding.tvPassiveScoreVal.text = "$passivePercent%"
            binding.tvPassiveScoreVal.setTextColor(ContextCompat.getColor(this, R.color.status_error))
            binding.indicatorPassiveScore.progress = passivePercent
            binding.indicatorPassiveScore.setIndicatorColor(ContextCompat.getColor(this, R.color.status_error))

            binding.tvDurationVal.text = "Session Duration: ${fallbackResult?.sessionDurationMs ?: 0} ms"
            val completedList = fallbackResult?.completedChallenges
                ?: (if (error is com.solistra.liveness.core.LivenessException.ChallengeTimeout) listOf() else emptyList())
            binding.tvChallengesCompleted.text = if (completedList.isNotEmpty()) {
                "Challenges: ${completedList.joinToString { it.name.lowercase().replace('_', ' ').replaceFirstChar(Char::titlecase) }}"
            } else {
                "Challenges: None"
            }

            val errorMsg = error?.message ?: "Verification failed or cancelled by user"
            binding.tvErrorBanner.text = "Error: $errorMsg"
            binding.tvErrorBanner.visibility = View.VISIBLE

            historyAdapter.addItem(
                LivenessHistoryItem(
                    isLive = false,
                    passiveScore = spoofScore,
                    sessionDurationMs = fallbackResult?.sessionDurationMs ?: 0,
                    completedChallenges = completedList,
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
