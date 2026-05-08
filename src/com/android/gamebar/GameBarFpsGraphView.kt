/*
 * SPDX-FileCopyrightText: 2026 kenway214
 * SPDX-FileCopyrightText: 2026 putrazxyo13
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.gamebar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

class GameBarFpsGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val buffer = FloatArray(MAX_SAMPLES)
    private var head  = 0
    private var count = 0

    private var trackedMin = Float.MAX_VALUE
    private var trackedMax = -Float.MAX_VALUE
    private var needsFullScan = false

    private var fpsFloor   = 0f
    private var fpsCeiling = DEFAULT_FPS_CEILING

    private val linePath = Path()
    private val fillPath = Path()

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style      = Paint.Style.STROKE
        strokeWidth = 2.5f
        strokeCap  = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style       = Paint.Style.STROKE
        strokeWidth = 0.5f
        color       = Color.argb(30, 255, 255, 255)
    }

    fun pushFps(fps: Float) {
        if (fps < 0f) return

        if (count == MAX_SAMPLES) {
            val evicted = buffer[head]
            if (evicted <= trackedMin || evicted >= trackedMax) {
                needsFullScan = true
            }
        }

        buffer[head] = fps
        head = (head + 1) % MAX_SAMPLES
        if (count < MAX_SAMPLES) count++

        if (needsFullScan) {
            fullScanBounds()
            needsFullScan = false
        } else {
            if (fps < trackedMin) trackedMin = fps
            if (fps > trackedMax) trackedMax = fps
        }

        recalcBounds()
        invalidate()
    }

    fun reset() {
        count        = 0
        head         = 0
        trackedMin   = Float.MAX_VALUE
        trackedMax   = -Float.MAX_VALUE   
        needsFullScan = false
        fpsFloor     = 0f
        fpsCeiling   = DEFAULT_FPS_CEILING
        buffer.fill(0f)
        invalidate()
    }

    private fun fullScanBounds() {
        if (count == 0) return
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE   
        for (i in 0 until count) {
            val v = sampleAt(i)
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        trackedMin = lo
        trackedMax = hi
    }

    private fun recalcBounds() {
        if (count == 0) return
        val margin = ((trackedMax - trackedMin) * 0.15f).coerceAtLeast(5f)
        fpsFloor   = (trackedMin - margin).coerceAtLeast(0f)
        fpsCeiling = trackedMax + margin
    }

    private fun sampleAt(index: Int): Float {
        val pos = (head - count + index + MAX_SAMPLES) % MAX_SAMPLES
        return buffer[pos]
    }

    private fun fpsToColor(fps: Float): Int = when {
        fps >= 50f -> COLOR_HIGH
        fps >= 28f -> blendColor(COLOR_MID, COLOR_HIGH, (fps - 28f) / 22f)
        else       -> blendColor(COLOR_LOW, COLOR_MID,  (fps / 28f).coerceIn(0f, 1f))
    }

    private fun blendColor(from: Int, to: Int, ratio: Float): Int {
        val inv = 1f - ratio
        return Color.argb(
            (Color.alpha(from) * inv + Color.alpha(to) * ratio).toInt(),
            (Color.red(from)   * inv + Color.red(to)   * ratio).toInt(),
            (Color.green(from) * inv + Color.green(to) * ratio).toInt(),
            (Color.blue(from)  * inv + Color.blue(to)  * ratio).toInt()
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (width == 0 || height == 0) return
        if (count < 2) return

        val w = width.toFloat()
        val h = height.toFloat()

        val padTop    = 2f
        val padBottom = 2f
        val drawH     = h - padTop - padBottom
        val range     = (fpsCeiling - fpsFloor).coerceAtLeast(1f)

        for (i in 1..2) {
            val gy = padTop + drawH * i / 3f
            canvas.drawLine(0f, gy, w, gy, gridPaint)
        }

        val stepX       = w / (MAX_SAMPLES - 1).toFloat()
        val startOffset = MAX_SAMPLES - count

        linePath.reset()
        fillPath.reset()

        var prevX  = 0f
        var prevY  = 0f
        var avgFps = 0f

        for (i in 0 until count) {
            val fps        = sampleAt(i)
            avgFps        += fps
            val x          = (startOffset + i) * stepX
            val normalized = ((fps - fpsFloor) / range).coerceIn(0f, 1f)
            val y          = padTop + drawH * (1f - normalized)

            if (i == 0) {
                linePath.moveTo(x, y)
                fillPath.moveTo(x, h)
                fillPath.lineTo(x, y)
            } else {
                val cx = (prevX + x) / 2f
                linePath.cubicTo(cx, prevY, cx, y, x, y)
                fillPath.cubicTo(cx, prevY, cx, y, x, y)
            }
            prevX = x
            prevY = y
        }

        avgFps /= count

        fillPath.lineTo(prevX, h)
        fillPath.close()

        val lineColor = fpsToColor(avgFps)
        val fillTop   = Color.argb(60, Color.red(lineColor), Color.green(lineColor), Color.blue(lineColor))
        val fillBot   = Color.argb(0,  Color.red(lineColor), Color.green(lineColor), Color.blue(lineColor))

        fillPaint.shader = LinearGradient(
            0f, padTop, 0f, h,
            fillTop, fillBot,
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(fillPath, fillPaint)

        linePaint.color = lineColor
        canvas.drawPath(linePath, linePaint)
    }

    companion object {
        const val MAX_SAMPLES = 60
        private const val DEFAULT_FPS_CEILING = 65f
        private const val COLOR_HIGH = 0xFF4CAF50.toInt()
        private const val COLOR_MID  = 0xFFFFC107.toInt()
        private const val COLOR_LOW  = 0xFFF44336.toInt()
    }
}