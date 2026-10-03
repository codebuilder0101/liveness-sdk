package com.solistra.liveness.ui

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Custom overlay view displaying a high-contrast darkened backdrop,
 * transparent center oval cutout, dynamic progress arc, and color-coded state border.
 */
class LivenessOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val backdropPaint = Paint().apply {
        color = Color.parseColor("#CC000000") // 80% opacity dark overlay
        style = Paint.Style.FILL
    }

    private val transparentEraserPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        isAntiAlias = true
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 10f
        strokeCap = Paint.Cap.ROUND
    }

    private val progressArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF") // Vibrant cyan/blue
        style = Paint.Style.STROKE
        strokeWidth = 14f
        strokeCap = Paint.Cap.ROUND
    }

    val ovalRect = RectF()
    private var progressSweepAngle = 0f
    private var currentBorderColor = Color.WHITE
    private var targetBorderColor = Color.WHITE
    private var colorAnimator: ValueAnimator? = null

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        calculateOvalBounds(w, h)
    }

    private fun calculateOvalBounds(viewWidth: Int, viewHeight: Int) {
        // Oval width ~72% of screen width, height ~1.35x width
        val ovalWidth = viewWidth * 0.72f
        val ovalHeight = ovalWidth * 1.35f

        val left = (viewWidth - ovalWidth) / 2f
        val top = (viewHeight - ovalHeight) / 2f - (viewHeight * 0.04f) // slightly lifted upwards
        val right = left + ovalWidth
        val bottom = top + ovalHeight

        ovalRect.set(left, top, right, bottom)
    }

    fun setGuideColor(newColor: Int, animated: Boolean = true) {
        if (targetBorderColor == newColor) return
        targetBorderColor = newColor

        colorAnimator?.cancel()
        if (animated) {
            colorAnimator = ValueAnimator.ofObject(ArgbEvaluator(), currentBorderColor, newColor).apply {
                duration = 250
                addUpdateListener { animator ->
                    currentBorderColor = animator.animatedValue as Int
                    borderPaint.color = currentBorderColor
                    invalidate()
                }
                start()
            }
        } else {
            currentBorderColor = newColor
            borderPaint.color = newColor
            invalidate()
        }
    }

    fun setProgress(progress: Float) {
        val clamped = progress.coerceIn(0f, 1f)
        progressSweepAngle = clamped * 360f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1. Draw dark backdrop over entire canvas
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backdropPaint)

        // 2. Erase oval cutout
        canvas.drawOval(ovalRect, transparentEraserPaint)

        // 3. Draw oval border guide
        canvas.drawOval(ovalRect, borderPaint)

        // 4. Draw active challenge progress arc around the oval
        if (progressSweepAngle > 0f) {
            canvas.drawArc(ovalRect, -90f, progressSweepAngle, false, progressArcPaint)
        }
    }
}
