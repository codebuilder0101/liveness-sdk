package com.solistra.liveness.sample

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.solistra.liveness.core.LivenessChallenge
import com.solistra.liveness.core.LivenessConfig
import com.solistra.liveness.sample.databinding.ActivityMainBinding
import com.solistra.liveness.ui.LivenessActivity
import com.solistra.liveness.ui.LivenessSDKResultHolder

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val livenessLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val livenessResult = LivenessSDKResultHolder.lastResult
            if (livenessResult != null && livenessResult.isLive) {
                binding.tvStatus.text = "Status: PASSED (Verified Live Face)"
                binding.tvStatus.setTextColor(Color.parseColor("#00E676"))
                binding.tvPassiveScore.text = "Passive Confidence: ${(livenessResult.passiveScore * 100).toInt()}%"
                binding.ivResultFace.setImageBitmap(livenessResult.bestFaceImage)
            }
        } else {
            val error = LivenessSDKResultHolder.lastError
            binding.tvStatus.text = "Status: FAILED (${error?.message ?: "Verification failed"})"
            binding.tvStatus.setTextColor(Color.parseColor("#FF1744"))
            binding.tvPassiveScore.text = "Passive Anti-Spoof Score: N/A"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnStartLiveness.setOnClickListener {
            val config = LivenessConfig(
                challenges = listOf(LivenessChallenge.BLINK, LivenessChallenge.SMILE),
                challengeTimeoutSeconds = 4.0f,
                passiveSpoofThreshold = 0.85f,
                useGpuDelegate = true,
                enableScreenSecurity = true
            )
            val intent = LivenessActivity.createIntent(this, config)
            livenessLauncher.launch(intent)
        }
    }
}
