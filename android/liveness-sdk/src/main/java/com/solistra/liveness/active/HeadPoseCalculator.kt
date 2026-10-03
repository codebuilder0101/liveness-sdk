package com.solistra.liveness.active

import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Calculates head pose angles (Yaw, Pitch, Roll) in degrees from 3D transformation matrices or landmarks.
 */
data class HeadPose(
    val pitch: Float, // Up/Down (+ looking down, - looking up)
    val yaw: Float,   // Left/Right (+ looking left, - looking right)
    val roll: Float   // Tilt (+ tilted right, - tilted left)
)

object HeadPoseCalculator {

    /**
     * Extracts Euler angles (in degrees) from a 4x4 or 3x3 column-major / row-major rotation matrix.
     * MediaPipe provides facial transformation matrix in 4x4 format.
     */
    fun fromTransformationMatrix(matrix: FloatArray): HeadPose {
        if (matrix.size < 16) {
            return HeadPose(0f, 0f, 0f)
        }

        // Extract rotation components from 4x4 matrix
        // m00, m01, m02, m03
        // m10, m11, m12, m13
        // m20, m21, m22, m23
        // m30, m31, m32, m33
        val m00 = matrix[0]
        val m10 = matrix[4]
        val m11 = matrix[5]
        val m12 = matrix[6]
        val m20 = matrix[8]
        val m21 = matrix[9]
        val m22 = matrix[10]

        val sy = sqrt((m00 * m00 + m10 * m10).toDouble()).toFloat()
        val isSingular = sy < 1e-6

        val pitch: Float
        val yaw: Float
        val roll: Float

        if (!isSingular) {
            pitch = Math.toDegrees(atan2(m21.toDouble(), m22.toDouble())).toFloat()
            yaw = Math.toDegrees(atan2(-m20.toDouble(), sy.toDouble())).toFloat()
            roll = Math.toDegrees(atan2(m10.toDouble(), m00.toDouble())).toFloat()
        } else {
            pitch = Math.toDegrees(atan2(-m12.toDouble(), m11.toDouble())).toFloat()
            yaw = Math.toDegrees(atan2(-m20.toDouble(), sy.toDouble())).toFloat()
            roll = 0f
        }

        return HeadPose(pitch = pitch, yaw = yaw, roll = roll)
    }
}
