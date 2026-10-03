package com.solistra.liveness.core

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Supported active challenges evaluated via MediaPipe blendshapes and head pose.
 */
@Parcelize
enum class LivenessChallenge(val promptText: String) : Parcelable {
    BLINK("Blink your eyes"),
    SMILE("Smile naturally"),
    TURN_LEFT("Turn your head slowly to the left"),
    TURN_RIGHT("Turn your head slowly to the right"),
    NOD_HEAD("Nod your head up and down"),
    OPEN_MOUTH("Open your mouth slightly")
}
