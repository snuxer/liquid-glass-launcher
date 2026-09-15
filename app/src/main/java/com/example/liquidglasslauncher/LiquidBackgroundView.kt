package com.example.liquidglasslauncher

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.sin
import kotlin.random.Random

/**
 * Zeichnet einen animierten "Liquid Glass"-Hintergrund: mehrere weiche, farbige
 * Lichtblasen, die langsam umeinander schweben. Das ist unabhängig vom System-
 * Wallpaper (kein Berechtigungsproblem) und sieht dem echten Liquid-Glass-Material
 * sehr nahe: Glaskarten schimmern später darüber.
 */
class LiquidBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private data class Blob(
        val baseX: Float,
        val baseY: Float,
        val radius: Float,
        val color: Int,
        val phase: Float,
        val speed: Float,
        val orbit: Float
    )

    private var blobs: List<Blob> = emptyList()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SCREEN)
    }
    private var t = 0f

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 18000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            t = it.animatedValue as Float
            invalidate()
        }
    }

    private val palette = intArrayOf(
        Color.parseColor("#6C7BFF"),
        Color.parseColor("#FF7FCB"),
        Color.parseColor("#57DDFF"),
        Color.parseColor("#A96CFF"),
        Color.parseColor("#63F2B0")
    )

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return
        val rnd = Random(7)
        blobs = palette.mapIndexed { i, color ->
            Blob(
                baseX = w * (0.15f + 0.7f * rnd.nextFloat()),
                baseY = h * (0.1f + 0.8f * rnd.nextFloat()),
                radius = w * (0.4f + 0.2f * rnd.nextFloat()),
                color = color,
                phase = i * 1.3f,
                speed = 0.5f + rnd.nextFloat() * 0.5f,
                orbit = w * (0.28f + 0.18f * rnd.nextFloat())
            )
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        paint.shader = null
        paint.color = Color.parseColor("#181A32")
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        for (blob in blobs) {
            val angle = (t * 360f * blob.speed + blob.phase * 60f) * (Math.PI / 180f)
            val cx = blob.baseX + (blob.orbit * sin(angle)).toFloat()
            val cy = blob.baseY + (blob.orbit * sin(angle * 0.7 + blob.phase)).toFloat()

            blobPaint.shader = RadialGradient(
                cx, cy, blob.radius,
                intArrayOf(withAlpha(blob.color, 255), withAlpha(blob.color, 0)),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, blob.radius, blobPaint)
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
}
