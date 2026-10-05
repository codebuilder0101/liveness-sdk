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
    fun `smile succeeds with symmetric smile held for 250ms`() {
        evaluator.startChallenge(LivenessChallenge.SMILE, timestampMs = 1000L)
        val dummyPose = HeadPose(0f, 0f, 0f)

        val smileBlendshapes = mapOf(
            "mouthSmileLeft" to 0.45f,
            "mouthSmileRight" to 0.45f,
            "cheekSquintLeft" to 0.30f,
            "cheekSquintRight" to 0.30f
        )

        // Feed frames across 300ms (10 frames at 30ms intervals)
        var completed = false
        for (i in 0..10) {
            val ts = 1000L + (i * 30L)
            val (isDone, progress) = evaluator.evaluateFrame(smileBlendshapes, dummyPose, ts)
            if (isDone) {
                completed = true
                assertEquals(1.0f, progress, 0.001f)
                break
            }
        }
        assertTrue("Smile challenge should succeed after being held for > 250ms", completed)
    }

    @Test
    fun `smile succeeds with asymmetric smile due to stronger side and cheek activation`() {
        evaluator.startChallenge(LivenessChallenge.SMILE, timestampMs = 1000L)
        val dummyPose = HeadPose(0f, 0f, 0f)

        // Asymmetric smile: right side smiles strongly (0.50), left side is weak (0.15)
        // Cheek squints active (0.35)
        val asymmetricSmile = mapOf(
            "mouthSmileLeft" to 0.15f,
            "mouthSmileRight" to 0.50f,
            "cheekSquintLeft" to 0.20f,
            "cheekSquintRight" to 0.40f
        )

        var completed = false
        for (i in 0..10) {
            val ts = 1000L + (i * 30L)
            val (isDone, _) = evaluator.evaluateFrame(asymmetricSmile, dummyPose, ts)
            if (isDone) {
                completed = true
                break
            }
        }
        assertTrue("Asymmetric smile must succeed and not fail", completed)
    }

    @Test
    fun `smile does not complete if held for less than 250ms`() {
        evaluator.startChallenge(LivenessChallenge.SMILE, timestampMs = 1000L)
        val dummyPose = HeadPose(0f, 0f, 0f)

        val smileBlendshapes = mapOf(
            "mouthSmileLeft" to 0.50f,
            "mouthSmileRight" to 0.50f
        )

        // Feed frames for only 150ms
        var completed = false
        for (i in 0..4) {
            val ts = 1000L + (i * 30L)
            val (isDone, _) = evaluator.evaluateFrame(smileBlendshapes, dummyPose, ts)
            if (isDone) completed = true
        }
        assertFalse("Smile should not complete under 250ms", completed)
    }

    // ── HEAD NOD TESTS ───────────────────────────────────────────────────────

    @Test
    fun `head nod succeeds when pitching down then returning to neutral`() {
        evaluator.startChallenge(LivenessChallenge.NOD_HEAD, timestampMs = 1000L)
        val emptyBlendshapes = emptyMap<String, Float>()

        // Phase 1: User nods down past 15°
        val (done1, _) = evaluator.evaluateFrame(emptyBlendshapes, HeadPose(pitch = 5f, yaw = 0f, roll = 0f), 1030L)
        assertFalse(done1)

        val (done2, prog2) = evaluator.evaluateFrame(emptyBlendshapes, HeadPose(pitch = 16f, yaw = 0f, roll = 0f), 1060L)
        assertFalse(done2) // Transitioned to WAITING_FOR_RETURN
        assertTrue(prog2 >= 0.65f)

        // Phase 2: User returns head back to neutral (pitch = 8° < 12°)
        val (done3, prog3) = evaluator.evaluateFrame(emptyBlendshapes, HeadPose(pitch = 8f, yaw = 0f, roll = 0f), 1100L)
        assertTrue("Head nod must complete upon returning to neutral", done3)
        assertEquals(1.0f, prog3, 0.001f)
    }

    @Test
    fun `head nod does not complete without returning to neutral`() {
        evaluator.startChallenge(LivenessChallenge.NOD_HEAD, timestampMs = 1000L)
        val emptyBlendshapes = emptyMap<String, Float>()

        // User nods down past 15° and stays looking down
        evaluator.evaluateFrame(emptyBlendshapes, HeadPose(pitch = 18f, yaw = 0f, roll = 0f), 1050L)
        val (done, prog) = evaluator.evaluateFrame(emptyBlendshapes, HeadPose(pitch = 17f, yaw = 0f, roll = 0f), 1100L)

        assertFalse("Head nod should not complete if head is still down", done)
        assertTrue(prog in 0.65f..0.99f)
    }

    // ── BLINK TESTS ──────────────────────────────────────────────────────────

    @Test
    fun `blink succeeds on eyes closed and then opened`() {
        evaluator.startChallenge(LivenessChallenge.BLINK, timestampMs = 1000L)
        val dummyPose = HeadPose(0f, 0f, 0f)

        // Frame 1-3: Eyes open
        val eyesOpen = mapOf("eyeBlinkLeft" to 0.05f, "eyeBlinkRight" to 0.05f)
        evaluator.evaluateFrame(eyesOpen, dummyPose, 1000L)
        evaluator.evaluateFrame(eyesOpen, dummyPose, 1030L)

        // Frame 4-7: Eyes closed (blink) for ~120ms
        val eyesClosed = mapOf("eyeBlinkLeft" to 0.85f, "eyeBlinkRight" to 0.85f)
        for (i in 0..3) {
            val (done, _) = evaluator.evaluateFrame(eyesClosed, dummyPose, 1060L + i * 30L)
            assertFalse("Should not complete while eyes are still closed", done)
        }

        // Frame 8: Eyes reopened (at 1200ms)
        val (done, prog) = evaluator.evaluateFrame(eyesOpen, dummyPose, 1200L)
        assertTrue("Blink should complete when eyes reopen within valid duration", done)
        assertEquals(1.0f, prog, 0.001f)
    }

    // ── HEAD TURN TESTS ──────────────────────────────────────────────────────

    @Test
    fun `turn left succeeds after yaw threshold and peak hold`() {
        evaluator.startChallenge(LivenessChallenge.TURN_LEFT, timestampMs = 1000L)
        val emptyBlendshapes = emptyMap<String, Float>()

        // Turn left to yaw = 16° (>= 15° threshold)
        evaluator.evaluateFrame(emptyBlendshapes, HeadPose(pitch = 0f, yaw = 16f, roll = 0f), 1000L)
        // First frame sets peak reached
        Thread.sleep(160) // wait for hold duration
        val (done2, prog2) = evaluator.evaluateFrame(emptyBlendshapes, HeadPose(pitch = 0f, yaw = 16f, roll = 0f), 1200L)
        assertTrue("Turn left should complete after 150ms hold", done2)
        assertEquals(1.0f, prog2, 0.001f)
    }

    @Test
    fun `turn right succeeds after negative yaw threshold and peak hold`() {
        evaluator.startChallenge(LivenessChallenge.TURN_RIGHT, timestampMs = 1000L)
        val emptyBlendshapes = emptyMap<String, Float>()

        // Turn right to yaw = -16° (negated to 16° >= 15° threshold)
        evaluator.evaluateFrame(emptyBlendshapes, HeadPose(pitch = 0f, yaw = -16f, roll = 0f), 1000L)
        Thread.sleep(160)
        val (done, prog) = evaluator.evaluateFrame(emptyBlendshapes, HeadPose(pitch = 0f, yaw = -16f, roll = 0f), 1200L)
        assertTrue("Turn right should complete after 150ms hold", done)
        assertEquals(1.0f, prog, 0.001f)
    }

    // ── OPEN MOUTH TESTS ─────────────────────────────────────────────────────

    @Test
    fun `open mouth succeeds after hold duration`() {
        evaluator.startChallenge(LivenessChallenge.OPEN_MOUTH, timestampMs = 1000L)
        val dummyPose = HeadPose(0f, 0f, 0f)

        val mouthOpen = mapOf("jawOpen" to 0.70f)
        var completed = false
        for (i in 0..11) {
            val ts = 1000L + (i * 30L)
            val (isDone, _) = evaluator.evaluateFrame(mouthOpen, dummyPose, ts)
            if (isDone) {
                completed = true
                break
            }
        }
        assertTrue("Open mouth challenge should succeed after 300ms hold", completed)
    }
}
