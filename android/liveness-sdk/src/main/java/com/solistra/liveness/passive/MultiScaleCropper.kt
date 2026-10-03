package com.solistra.liveness.passive

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF

/**
 * Multi-Scale Contextual Cropper for MiniFASNet Silent-Face-Anti-Spoofing.
 * Generates expanded bounding boxes (Scale 2.7x and Scale 4.0x) around the detected face.
 */
object MultiScaleCropper {

    /**
     * Crops and scales the face region with a given expansion factor.
     * @param sourceBitmap Full camera frame.
     * @param faceBbox Bounding box of the detected face.
     * @param scaleFactor Contextual expansion ratio (typically 2.7 or 4.0).
     * @param targetSize Target square resolution (typically 80 for MiniFASNet).
     */
    fun cropFaceWithScale(
        sourceBitmap: Bitmap,
        faceBbox: RectF,
        scaleFactor: Float = 2.7f,
        targetSize: Int = 80
    ): Bitmap {
        val faceWidth = faceBbox.width()
        val faceHeight = faceBbox.height()
        val centerX = faceBbox.centerX()
        val centerY = faceBbox.centerY()

        // Calculate expanded square bounding box
        val maxDim = maxOf(faceWidth, faceHeight)
        val expandedSize = maxDim * scaleFactor

        val cropLeft = (centerX - expandedSize / 2f).toInt()
        val cropTop = (centerY - expandedSize / 2f).toInt()
        val cropRight = (centerX + expandedSize / 2f).toInt()
        val cropBottom = (centerY + expandedSize / 2f).toInt()

        val outputBitmap = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outputBitmap)
        canvas.drawColor(Color.BLACK) // Black padding for regions outside original frame bounds

        // Source intersection rect
        val srcIntersect = Rect(
            cropLeft.coerceAtLeast(0),
            cropTop.coerceAtLeast(0),
            cropRight.coerceAtMost(sourceBitmap.width),
            cropBottom.coerceAtMost(sourceBitmap.height)
        )

        if (srcIntersect.width() <= 0 || srcIntersect.height() <= 0) {
            return outputBitmap
        }

        // Destination mapping rect inside targetSize x targetSize
        val normLeft = (srcIntersect.left - cropLeft).toFloat() / expandedSize * targetSize
        val normTop = (srcIntersect.top - cropTop).toFloat() / expandedSize * targetSize
        val normRight = (srcIntersect.right - cropLeft).toFloat() / expandedSize * targetSize
        val normBottom = (srcIntersect.bottom - cropTop).toFloat() / expandedSize * targetSize

        val dstRect = RectF(normLeft, normTop, normRight, normBottom)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        canvas.drawBitmap(sourceBitmap, srcIntersect, dstRect, paint)
        return outputBitmap
    }
}
