package com.voicerecorder.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

class WaveformView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE53935.toInt()
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
    }

    private val bars = 20
    private var amplitudes = FloatArray(bars) { 0.1f }
    private var animating = false
    private var phase = 0f
    private val runnable = object : Runnable {
        override fun run() {
            if (animating) {
                phase += 0.15f
                for (i in 0 until bars) {
                    val base = abs(sin(phase + i * 0.4f)).toFloat()
                    amplitudes[i] = 0.1f + base * 0.9f * Random.nextFloat().coerceAtLeast(0.3f)
                }
                invalidate()
                postDelayed(this, 80)
            }
        }
    }

    fun startAnimation() {
        animating = true
        post(runnable)
    }

    fun stopAnimation() {
        animating = false
        amplitudes = FloatArray(bars) { 0.1f }
        invalidate()
    }

    fun updateAmplitude(maxAmp: Int) {
        val norm = (maxAmp / 32767f).coerceIn(0f, 1f)
        for (i in 0 until bars) {
            val base = abs(sin(phase + i * 0.4f)).toFloat()
            amplitudes[i] = 0.05f + norm * base
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val barWidth = w / bars
        val cx = w / 2
        paint.color = 0xFFE53935.toInt()

        for (i in 0 until bars) {
            val x = barWidth * i + barWidth / 2
            val barH = (amplitudes[i] * h * 0.85f).coerceAtLeast(6f)
            val distFromCenter = abs(x - cx) / cx
            paint.alpha = (255 * (1f - distFromCenter * 0.4f)).toInt()
            canvas.drawLine(x, h / 2 - barH / 2, x, h / 2 + barH / 2, paint)
        }
    }
}
