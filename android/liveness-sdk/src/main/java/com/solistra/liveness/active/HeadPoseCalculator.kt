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

        // MediaPipe facialTransformationMatrixes produces a flat 16-float column-major 4x4 matrix:
        // R[row][col] = matrix[col * 4 + row]
        // Normalize out scale vectors since matrix is metric
        fun r(row: Int, col: Int) = matrix[col * 4 + row]

        val sx = sqrt((r(0, 0) * r(0, 0) + r(1, 0) * r(1, 0) + r(2, 0) * r(2, 0)).toDouble()).toFloat()
        val sy = sqrt((r(0, 1) * r(0, 1) + r(1, 1) * r(1, 1) + r(2, 1) * r(2, 1)).toDouble()).toFloat()
        val sz = sqrt((r(0, 2) * r(0, 2) + r(1, 2) * r(1, 2) + r(2, 2) * r(2, 2)).toDouble()).toFloat()

        if (sx < 1e-6f || sy < 1e-6f || sz < 1e-6f) {
            return HeadPose(0f, 0f, 0f)
        }

        val r00 = r(0, 0) / sx
        val r10 = r(1, 0) / sx
        val r20 = r(2, 0) / sx
        val r21 = r(2, 1) / sy
        val r22 = r(2, 2) / sz

        val pitch = Math.toDegrees(atan2(r21.toDouble(), r22.toDouble())).toFloat()
        val yaw = Math.toDegrees(atan2(-r20.toDouble(), sqrt((r00 * r00 + r10 * r10).toDouble()))).toFloat()
        val roll = Math.toDegrees(atan2(r10.toDouble(), r00.toDouble())).toFloat()

        return HeadPose(pitch = pitch, yaw = yaw, roll = roll)
    }
}
