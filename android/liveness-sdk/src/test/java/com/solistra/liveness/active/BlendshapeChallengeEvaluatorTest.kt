package com.solistra.liveness.active

import com.solistra.liveness.core.LivenessChallenge
import com.solistra.liveness.core.LivenessConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BlendshapeChallengeEvaluatorTest {

    private lateinit var config: LivenessConfig
    private lateinit var evaluator: BlendshapeChallengeEvaluator

    @Before
    fun setUp() {
        config = LivenessConfig.default()
        evaluator = BlendshapeChallengeEvaluator(config)
    }

    // ── SMILE TESTS ──────────────────────────────────────────────────────────

    @Test
    fun `smile succeeds with symmetric smile held for 150ms`() {
        evaluator.startChallenge(LivenessChallenge.SMILE, timestampMs = 1000L)
        val dummyPose = HeadPose(0f, 0f, 0f)

        val smileBlendshapes = mapOf(
            "mouthSmileLeft" to 0.45f,
            "mouthSmileRight" to 0.45f,
            "cheekSquintLeft" to 0.20f,
            "cheekSquintRight" to 0.20f
        )

        // Feed frames across 200ms (7 frames at 30ms intervals)
        var completed = false
        for (i in 0..7) {
            val ts = 1000L + (i * 30L)
            val (isDone, progress) = evaluator.evaluateFrame(smileBlendshapes, dummyPose, ts)
            if (isDone) {
                completed = true
                assertEquals(1.0f, progress, 0.001f)
                break
            }
        }
        assertTrue("Smile challenge should succeed after being held for > 150ms", completed)
    }

    @Test
    fun `smile succeeds with asymmetric smile due to dominant side`() {
        evaluator.startChallenge(LivenessChallenge.SMILE, timestampMs = 1000L)
        val dummyPose = HeadPose(0f, 0f, 0f)

        // Asymmetric smile: right side smiles strongly (0.45), left side is weak (0.10)
        val asymmetricSmile = mapOf(
            "mouthSmileLeft" to 0.10f,
            "mouthSmileRight" to 0.45f,
            "cheekSquintLeft" to 0.05f,
            "cheekSquintRight" to 0.15f
        )

        var completed = false
        for (i in 0..7) {
            val ts = 1000L + (i * 30L)
            val (isDone, _) = evaluator.evaluateFrame(asymmetricSmile, dummyPose, ts)
            if (isDone) {
                completed = true
                break
            }
        }
        assertTrue("Asymmetric smile must succeed with dominant mouth corner", completed)
    }

    @Test
    fun `smile does not complete if held for less than 150ms`() {
        evaluator.startChallenge(LivenessChallenge.SMILE, timestampMs = 1000L)
        val dummyPose = HeadPose(0f, 0f, 0f)

        val smileBlendshapes = mapOf(
            "mouthSmileLeft" to 0.50f,
            "mouthSmileRight" to 0.50f
        )

        // Feed frames for only 90ms (3 frames)
        var completed = false
        for (i in 0..3) {
            val ts = 1000L + (i * 30L)
            val (isDone, _) = evaluator.evaluateFrame(smileBlendshapes, dummyPose, ts)
            if (isDone) completed = true
        }
        assertFalse("Smile should not complete under 150ms hold duration", completed)
    }

    // ── HEAD NOD TESTS ───────────────────────────────────────────────────────

    @Test
    fun `head nod succeeds relative to natural phone holding baseline pitch`() {
        // Natural phone holding position: neutral pitch is +14° (user looking slightly down at phone)
        evaluator.setBaseline(pitch = 14f, yaw = 0f)
        evaluator.startChallenge(LivenessChallenge.NOD_HEAD, timestampMs = 1000L)
        val emptyBlendshapes = emptyMap<String, Float>()

        // Frame 1: at baseline (+14°, delta = 0°)
        val (done1, _) = evaluator.evaluateFrame(emptyBlendshapes, HeadPose(pitch = 14f, yaw = 0f, roll = 0f), 1030L)
        assertFalse(done1)

        // Frame 2: nods down to +25° (delta = +11° >= 8.5° threshold)
        val (done2, prog2) = evaluator.evaluateFrame(emptyBlendshapes, HeadPose(pitch = 25f, yaw = 0f, roll = 0f), 1060L)
        assertFalse(done2)
        assertTrue("Progress should jump to waiting for return", prog2 >= 0.65f)

        // Frame 3: returns head back toward baseline (+16°, delta = +2° <= 5.5° return threshold)
        val (done3, prog3) = evaluator.evaluateFrame(emptyBlendshapes, HeadPose(pitch = 16f, yaw = 0f, roll = 0f), 1120L)
        assertTrue("Head nod must complete upon returning within 5.5° of baseline", done3)
        assertEquals(1.0f, prog3, 0.001f)
    }

    @Test
    fun `head nod succeeds when nodding up first then returning to center`() {
        evaluator.setBaseline(pitch = 5f, yaw = 0f)
        evaluator.startChallenge(LivenessChallenge.NOD_HEAD, timestampMs = 1000L)
        val emptyBlendshapes = emptyMap<String, Float>()

        // Frame 1: at resting pitch (+5°)
        evaluator.evaluateFrame(emptyBlendshapes, HeadPose(pitch = 5f, yaw = 0f, roll = 0f), 1030L)

        // Frame 2: tilts UP to -5° (upExcursion = 10° >= 8.5° threshold)
        val (done2, prog2) = evaluator.evaluateFrame(emptyBlendshapes, HeadPose(pitch = -5f, yaw = 0f, roll = 0f), 1060L)
        assertFalse(done2)
        assertTrue("Progress should indicate return phase", prog2 >= 0.65f)

        // Frame 3: returns DOWN to +3° (within 5.5° of reference)
        val (done3, prog3) = evaluator.evaluateFrame(emptyBlendshapes, HeadPose(pitch = 3f, yaw = 0f, roll = 0f), 1120L)
        assertTrue("Upward nod must complete when returning to center", done3)
        assertEquals(1.0f, prog3, 0.001f)
    }

    @Test
    fun `head nod succeeds after posture shift from preceding challenge`() {
        // Suppose baseline was 0°, but after a smile or turn, resting pitch shifted to +4°
        evaluator.setBaseline(pitch = 0f, yaw = 0f)
        evaluator.startChallenge(LivenessChallenge.NOD_HEAD, timestampMs = 1000L)
        val emptyBlendshapes = emptyMap<String, Float>()

        // Frame 1: start at +4° resting pitch
        evaluator.evaluateFrame(emptyBlendshapes, HeadPose(pitch = 4f, yaw = 0f, roll = 0f), 1030L)

        // Frame 2: nods down to +14° (delta = +10° relative to +4° start)
        val (done2, _) = evaluator.evaluateFrame(emptyBlendshapes, HeadPose(pitch = 14f, yaw = 0f, roll = 0f), 1060L)
        assertFalse(done2)

        // Frame 3: returns to +5° (within return tolerance of +4° start)
        val (done3, prog3) = evaluator.evaluateFrame(emptyBlendshapes, HeadPose(pitch = 5f, yaw = 0f, roll = 0f), 1120L)
        assertTrue("Head nod must adapt to resting start pitch", done3)
        assertEquals(1.0f, prog3, 0.001f)
    }

    @Test
    fun `head nod does not complete without returning to baseline`() {
        evaluator.setBaseline(pitch = 10f, yaw = 0f)
        evaluator.startChallenge(LivenessChallenge.NOD_HEAD, timestampMs = 1000L)
        val emptyBlendshapes = emptyMap<String, Float>()

        // User nods down past baseline (+22°, delta = 12°) and holds head down
        evaluator.evaluateFrame(emptyBlendshapes, HeadPose(pitch = 22f, yaw = 0f, roll = 0f), 1050L)
        val (done, prog) = evaluator.evaluateFrame(emptyBlendshapes, HeadPose(pitch = 22f, yaw = 0f, roll = 0f), 1100L)

        assertFalse("Head nod should not complete if head stays down", done)
        assertTrue(prog in 0.65f..0.99f)
    }

    // ── BLINK TESTS ──────────────────────────────────────────────────────────

    @Test
    fun `blink succeeds on rapid eye closure and reopen`() {
        evaluator.startChallenge(LivenessChallenge.BLINK, timestampMs = 1000L)
        val dummyPose = HeadPose(0f, 0f, 0f)

        // Frame 1: Eyes open (1000ms)
        val eyesOpen = mapOf("eyeBlinkLeft" to 0.05f, "eyeBlinkRight" to 0.05f)
        evaluator.evaluateFrame(eyesOpen, dummyPose, 1000L)

        // Frame 2-4: Eyes closed (blink) for ~120ms (1030ms -> 1150ms)
        val eyesClosed = mapOf("eyeBlinkLeft" to 0.80f, "eyeBlinkRight" to 0.80f)
        for (i in 1..4) {
            val (done, _) = evaluator.evaluateFrame(eyesClosed, dummyPose, 1000L + i * 30L)
            assertFalse("Should not complete while eyes are closed", done)
        }

        // Frame 5: Eyes reopened at 1160ms (duration ~130ms)
        val (done, prog) = evaluator.evaluateFrame(eyesOpen, dummyPose, 1160L)
        assertTrue("Blink should complete when eyes reopen within valid duration", done)
        assertEquals(1.0f, prog, 0.001f)
    }

    // ── HEAD TURN TESTS ──────────────────────────────────────────────────────

    @Test
    fun `turn left succeeds relative to baseline yaw after 100ms hold`() {
        evaluator.setBaseline(pitch = 0f, yaw = 2f)
        evaluator.startChallenge(LivenessChallenge.TURN_LEFT, timestampMs = 1000L)
        val emptyBlendshapes = emptyMap<String, Float>()

        // Turn left to yaw = 15° (delta = 13° >= 12° threshold)
        evaluator.evaluateFrame(emptyBlendshapes, HeadPose(pitch = 0f, yaw = 15f, roll = 0f), 1000L)

        // Hold at 15° for 120ms
        evaluator.evaluateFrame(emptyBlendshapes, HeadPose(pitch = 0f, yaw = 15f, roll = 0f), 1060L)
        val (done, prog) = evaluator.evaluateFrame(emptyBlendshapes, HeadPose(pitch = 0f, yaw = 15f, roll = 0f), 1120L)

        assertTrue("Turn left should complete after 100ms hold based on frame timestamps", done)
        assertEquals(1.0f, prog, 0.001f)
    }

    @Test
    fun `turn right succeeds relative to baseline yaw after 100ms hold`() {
        evaluator.setBaseline(pitch = 0f, yaw = 0f)
        evaluator.startChallenge(LivenessChallenge.TURN_RIGHT, timestampMs = 1000L)
        val emptyBlendshapes = emptyMap<String, Float>()

        // Turn right to yaw = -14° (turnExcursion = +14° >= 12° threshold)
        evaluator.evaluateFrame(emptyBlendshapes, HeadPose(pitch = 0f, yaw = -14f, roll = 0f), 1000L)

        evaluator.evaluateFrame(emptyBlendshapes, HeadPose(pitch = 0f, yaw = -14f, roll = 0f), 1060L)
        val (done, prog) = evaluator.evaluateFrame(emptyBlendshapes, HeadPose(pitch = 0f, yaw = -14f, roll = 0f), 1120L)

        assertTrue("Turn right should complete after 100ms hold", done)
        assertEquals(1.0f, prog, 0.001f)
    }

    // ── OPEN MOUTH TESTS ─────────────────────────────────────────────────────

    @Test
    fun `open mouth succeeds after 150ms hold`() {
        evaluator.startChallenge(LivenessChallenge.OPEN_MOUTH, timestampMs = 1000L)
        val dummyPose = HeadPose(0f, 0f, 0f)

        val mouthOpen = mapOf("jawOpen" to 0.45f)
        var completed = false
        for (i in 0..6) {
            val ts = 1000L + (i * 30L)
            val (isDone, _) = evaluator.evaluateFrame(mouthOpen, dummyPose, ts)
            if (isDone) {
                completed = true
                break
            }
        }
        assertTrue("Open mouth challenge should succeed after 150ms hold", completed)
    }

    // ── HEAD POSE CALCULATOR MATRIX TEST ──────────────────────────────────────

    @Test
    fun `head pose calculator extracts Euler angles correctly from column-major matrix`() {
        // Identity matrix in column-major: 0 yaw, 0 pitch, 0 roll
        val identityMatrix = floatArrayOf(
            1f, 0f, 0f, 0f,  // col 0
            0f, 1f, 0f, 0f,  // col 1
            0f, 0f, 1f, 0f,  // col 2
            0f, 0f, -50f, 1f // col 3 (translation)
        )

        val pose = HeadPoseCalculator.fromTransformationMatrix(identityMatrix)
        assertEquals(0f, pose.pitch, 0.01f)
        assertEquals(0f, pose.yaw, 0.01f)
        assertEquals(0f, pose.roll, 0.01f)
    }
}
