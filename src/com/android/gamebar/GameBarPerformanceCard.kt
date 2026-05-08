/*
 * SPDX-FileCopyrightText: 2026 kenway214
 * SPDX-FileCopyrightText: 2026 putrazxyo13
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.gamebar

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.os.Build
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlin.math.sqrt

object GameBarPerformanceCard {

    private const val W = 1080
    private const val H = 2000

    private val REGEX_HOURS = Regex("(\\d+)h")
    private val REGEX_MINS = Regex("(\\d+)m")
    private val REGEX_SECS = Regex("(\\d+)s")

    data class HistoryItem(
        val packageName: String,
        val appName: String,
        val durationStr: String,
        val durationMs: Long,
        val score: Int,
        val icon: Bitmap?,
        val isCurrent: Boolean
    )

    fun computeScore(analytics: LogAnalytics): Int {
        val fps = analytics.fpsTimeData.map { it.second.toFloat() }
        if (fps.size < 2) return 0

        val avg = fps.average().toFloat()
        if (avg <= 0f) return 0

        val stdDev = if (fps.isNotEmpty()) {
            sqrt(fps.sumOf { ((it - avg) * (it - avg)).toDouble() } / fps.size).toFloat()
        } else {
            0f
        }
        val stabilityRatio = 1f - (stdDev / avg).coerceIn(0f, 1f)
        val stabilityScore = stabilityRatio * 100f

        val low1 = analytics.fpsStats.fps1PercentLow.toFloat()
        val lowRatio = if (avg > 0f) (low1 / avg).coerceIn(0f, 1f) else 0f
        val lowScore = lowRatio * 100f

        val smoothScore = analytics.fpsStats.smoothnessPercentage.toFloat()

        val frameTime = analytics.frameTimeData.map { it.second.toFloat() }
        val pacingScore = if (frameTime.size >= 2) {
            val ftAvg = frameTime.average().toFloat()
            val ftStd = sqrt(frameTime.sumOf { ((it - ftAvg) * (it - ftAvg)).toDouble() } / frameTime.size).toFloat()
            val pacingRatio = 1f - (ftStd / ftAvg.coerceAtLeast(1f)).coerceIn(0f, 1f)
            pacingRatio * 100f
        } else {
            50f
        }

        val raw = (stabilityScore * 0.40f) +
                  (lowScore * 0.25f) +
                  (smoothScore * 0.20f) +
                  (pacingScore * 0.15f)

        return raw.toInt().coerceIn(0, 100)
    }

    private fun sessionMs(durationStr: String): Long {
        val parts = durationStr.lowercase(Locale.getDefault())
        val hours = REGEX_HOURS.find(parts)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val mins = REGEX_MINS.find(parts)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val secs = REGEX_SECS.find(parts)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        return (hours * 3600 + mins * 60 + secs) * 1000L
    }

    private fun getAppIcon(context: Context, packageName: String): Bitmap? {
        return try {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            drawable.toBitmap(width = 80, height = 80)
        } catch (e: Exception) {
            null
        }
    }

    private fun getAppNameSafe(context: Context, packageName: String): String {
        return try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }
    }

    private fun loadHistoryItems(context: Context, currentAnalytics: LogAnalytics, currentAppName: String): List<HistoryItem> {
        val historyItems = mutableListOf<HistoryItem>()
        val currentScore = computeScore(currentAnalytics)
        val currentPkg = currentAnalytics.appName
        
        historyItems.add(HistoryItem(
            packageName = currentPkg,
            appName = currentAppName,
            durationStr = currentAnalytics.sessionDuration,
            durationMs = sessionMs(currentAnalytics.sessionDuration),
            score = currentScore,
            icon = getAppIcon(context, currentPkg),
            isCurrent = true
        ))

        val filesMap = PerAppLogManager.getInstance().getAllPerAppLogFiles()
        val reader = PerAppLogReader()

        for ((pkgName, files) in filesMap) {
            if (pkgName == currentPkg) continue
            val latestFile = files.firstOrNull() ?: continue
            val otherAnalytics = reader.analyzeLogFile(latestFile.absolutePath) ?: continue
            val otherScore = computeScore(otherAnalytics)
            val otherAppName = getAppNameSafe(context, pkgName)
            historyItems.add(HistoryItem(
                packageName = pkgName,
                appName = otherAppName,
                durationStr = otherAnalytics.sessionDuration,
                durationMs = sessionMs(otherAnalytics.sessionDuration),
                score = otherScore,
                icon = getAppIcon(context, pkgName),
                isCurrent = false
            ))
        }

        historyItems.sortWith(compareByDescending<HistoryItem> { it.score }.thenByDescending { it.durationMs })
        return historyItems.take(5)
    }

    fun generateAndShare(context: Context, appName: String, analytics: LogAnalytics) {
        val bitmap = renderCard(context, appName, analytics)
        try {
            val file = File(
                context.externalCacheDir ?: context.cacheDir,
                "gamebar_card_${System.currentTimeMillis()}.png"
            )
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "GameBar Performance: $appName")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share Performance Card"))
        } catch (_: Exception) {
        } finally {
            bitmap.recycle()
        }
    }

    fun saveCard(context: Context, appName: String, analytics: LogAnalytics): Boolean {
        val bitmap = renderCard(context, appName, analytics)
        val safeName = appName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "GameBar_Card_${safeName}_$ts.png"

        return try {
            val resolver = context.contentResolver
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(android.provider.MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                    put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                }
            }
            val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return false
            val written = resolver.openOutputStream(uri)?.use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            } ?: false
            if (!written) return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val done = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                }
                resolver.update(uri, done, null, null)
            }
            true
        } catch (_: Exception) {
            false
        } finally {
            bitmap.recycle()
        }
    }

    private fun renderCard(context: Context, appName: String, analytics: LogAnalytics): Bitmap {
        val history = loadHistoryItems(context, analytics, appName)
        val score = computeScore(analytics)
        val bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)

        drawAmbientBackground(c)
        drawTelemetryPanel(c, appName, analytics, score)
        drawHistoryList(c, history)
        drawWatermark(c)

        return bitmap
    }

    private fun drawAmbientBackground(c: Canvas) {
        c.drawColor(0xFF000000.toInt())
        
        val glow1 = Paint().apply { 
            shader = RadialGradient(-200f, -200f, 1500f, 0x338B5CF6, 0x00000000, Shader.TileMode.CLAMP) 
        }
        c.drawCircle(-200f, -200f, 1500f, glow1)
        
        val glow2 = Paint().apply { 
            shader = RadialGradient(W + 200f, H - 200f, 1500f, 0x333B82F6, 0x00000000, Shader.TileMode.CLAMP) 
        }
        c.drawCircle(W + 200f, H - 200f, 1500f, glow2)
    }

    private fun drawTelemetryPanel(c: Canvas, appName: String, analytics: LogAnalytics, score: Int) {
        val padding = 80f
        var y = 140f
        
        val statusRingRadius = 10f
        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3B82F6.toInt() }
        val ringGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { 
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = 0x883B82F6.toInt() 
        }
        c.drawCircle(padding + statusRingRadius, y, statusRingRadius, ringPaint)
        c.drawCircle(padding + statusRingRadius, y, statusRingRadius + 6f, ringGlowPaint)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 54f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val appTitle = if (appName.length > 20) appName.substring(0, 18) + "..." else appName
        c.drawText(appTitle, padding + 40f, y + 18f, titlePaint)

        val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF9CA3AF.toInt()
            textSize = 34f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val badgeRect = RectF(W - padding - 220f, y - 35f, W - padding, y + 45f)
        val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1AFFFFFF }
        c.drawRoundRect(badgeRect, 40f, 40f, badgeBgPaint)
        
        c.drawText(analytics.sessionDuration, badgeRect.left + 30f, y + 16f, badgePaint)

        y += 100f
        
        val cardTop = y
        val cardBottom = y + 760f
        val cardRect = RectF(padding, cardTop, W - padding, cardBottom)
        
        val cardBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x05FFFFFF }
        val cardBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = 0x0FFFFFFF
        }
        c.drawRoundRect(cardRect, 48f, 48f, cardBgPaint)
        c.drawRoundRect(cardRect, 48f, 48f, cardBorderPaint)

        y += 80f
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF9CA3AF.toInt()
            textSize = 28f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.05f
        }
        c.drawText("PERFORMANCE INDEX", padding + 50f, y, labelPaint)
        
        y += 120f
        val scorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 180f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val maxScorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF9CA3AF.toInt()
            textSize = 60f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val scoreStr = score.toString()
        val scoreWidth = scorePaint.measureText(scoreStr)
        c.drawText(scoreStr, padding + 40f, y, scorePaint)
        c.drawText("/100", padding + 50f + scoreWidth, y, maxScorePaint)

        val subLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF9CA3AF.toInt()
            textSize = 28f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.05f
            textAlign = Paint.Align.RIGHT
        }
        val subValPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF3B82F6.toInt()
            textSize = 70f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
        }
        c.drawText("AVG FPS", W - padding - 50f, y - 80f, subLabelPaint)
        c.drawText(String.format(Locale.US, "%.1f", analytics.fpsStats.avgFps), W - padding - 50f, y, subValPaint)

        y += 60f
        val divPaint = Paint().apply { color = 0x1AFFFFFF }
        c.drawRect(padding + 40f, y, W - padding - 40f, y + 2f, divPaint)

        y += 60f
        val colW = (W - padding * 2 - 120f) / 2
        val leftColX = padding + 50f
        val rightColX = padding + 70f + colW

        val statLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF9CA3AF.toInt()
            textSize = 28f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.02f
        }
        val statValPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 38f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
        }

        val fpsVals = analytics.fpsTimeData.map { it.second.toFloat() }
        val avgFps = if (fpsVals.isNotEmpty()) fpsVals.average().toFloat() else 1f
        val stdDev = if (fpsVals.isNotEmpty()) {
            sqrt(fpsVals.sumOf { ((it - avgFps) * (it - avgFps)).toDouble() } / fpsVals.size).toFloat()
        } else {
            0f
        }
        
        drawStatItem(c, "1% LOW", String.format(Locale.US, "%.1f", analytics.fpsStats.fps1PercentLow), leftColX, y, colW, statLabelPaint, statValPaint, 
            0xFFF59E0B.toInt(), 0xFFEF4444.toInt(), (analytics.fpsStats.fps1PercentLow.toFloat() / 60f).coerceIn(0f, 1f))
            
        drawStatItem(c, "JITTER", String.format(Locale.US, "%.2f", stdDev), rightColX, y, colW, statLabelPaint, statValPaint, 
            0xFF10B981.toInt(), 0xFF3B82F6.toInt(), (1f - (stdDev / 10f)).coerceIn(0.1f, 1f))

        y += 130f
        
        val thermalStatusText = if (analytics.cpuStats.worstThermalStatus != "Normal" && analytics.cpuStats.worstThermalStatus != "Unknown") {
            "${analytics.cpuStats.maxTemp.toInt()}°C (${analytics.cpuStats.worstThermalStatus})"
        } else {
            "${analytics.cpuStats.maxTemp.toInt()}°C"
        }
        val thermalValPaint = Paint(statValPaint).apply {
            if (thermalStatusText.length > 6) textSize = 30f
        }
        drawStatItem(c, "THERMAL", thermalStatusText, leftColX, y, colW, statLabelPaint, thermalValPaint, 
            0xFFF59E0B.toInt(), 0xFFEF4444.toInt(), (analytics.cpuStats.maxTemp.toFloat() / 80f).coerceIn(0f, 1f))
            
        drawStatItem(c, "POWER", String.format(Locale.US, "%.1fW", analytics.powerStats.avgPower), rightColX, y, colW, statLabelPaint, statValPaint, 
            0xFF3B82F6.toInt(), 0xFF8B5CF6.toInt(), (analytics.powerStats.avgPower.toFloat() / 15f).coerceIn(0f, 1f))

        y += 100f

        c.drawText("FRAMETIME VARIANCE", padding + 50f, y + 40f, statLabelPaint)
        val livePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF3B82F6.toInt()
            textSize = 28f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
        }
        c.drawText("LIVE", W - padding - 50f, y + 40f, livePaint)

        y += 80f
        drawElegantChart(c, padding + 50f, y, W - padding * 2 - 100f, 120f, analytics.frameTimeData.map { it.second.toFloat() })
    }

    private fun drawStatItem(c: Canvas, label: String, value: String, x: Float, y: Float, width: Float, labelPaint: Paint, valPaint: Paint, color1: Int, color2: Int, fillRatio: Float) {
        c.drawText(label, x, y + 30f, labelPaint)
        c.drawText(value, x + width, y + 30f, valPaint)

        val trackRect = RectF(x, y + 50f, x + width, y + 62f)
        val trackBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x0DFFFFFF }
        c.drawRoundRect(trackRect, 6f, 6f, trackBg)

        val fillWidth = width * fillRatio
        val fillRect = RectF(x, y + 50f, x + fillWidth, y + 62f)
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(x, y + 50f, x + fillWidth, y + 50f, color1, color2, Shader.TileMode.CLAMP)
        }
        c.drawRoundRect(fillRect, 6f, 6f, fillPaint)
    }

    private fun drawElegantChart(c: Canvas, x: Float, y: Float, width: Float, height: Float, data: List<Float>) {
        if (data.isEmpty()) return
        val step = (data.size / 100).coerceAtLeast(1)
        val sampled = data.filterIndexed { index, _ -> index % step == 0 }
        
        val maxVal = (sampled.maxOrNull() ?: 33f).coerceAtLeast(16.6f) + 5f
        val minVal = (sampled.minOrNull() ?: 0f).coerceAtMost(maxVal - 5f).coerceAtLeast(0f)
        val range = (maxVal - minVal).coerceAtLeast(1f)
        
        val stepX = width / (sampled.size - 1).coerceAtLeast(1)
        val getY = { v: Float -> y + height - ((v - minVal) / range) * height }

        val path = Path()
        val fillPath = Path()
        
        sampled.forEachIndexed { i, v ->
            val px = x + i * stepX
            val py = getY(v)
            if (i == 0) {
                path.moveTo(px, py)
                fillPath.moveTo(px, y + height)
                fillPath.lineTo(px, py)
            } else {
                val prevX = x + (i - 1) * stepX
                val prevY = getY(sampled[i - 1])
                val cx = (prevX + px) / 2
                path.cubicTo(cx, prevY, cx, py, px, py)
                fillPath.cubicTo(cx, prevY, cx, py, px, py)
            }
        }
        fillPath.lineTo(x + width, y + height)
        fillPath.close()

        val fillGrad = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(x, y, x, y + height, 0x338B5CF6, 0x003B82F6, Shader.TileMode.CLAMP)
        }
        c.drawPath(fillPath, fillGrad)

        val lineGrad = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            shader = LinearGradient(x, y, x + width, y, 0xFF8B5CF6.toInt(), 0xFF3B82F6.toInt(), Shader.TileMode.CLAMP)
        }
        c.drawPath(path, lineGrad)
    }

    private fun drawHistoryList(c: Canvas, history: List<HistoryItem>) {
        var y = 1050f
        val padding = 80f
        
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF9CA3AF.toInt()
            textSize = 28f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.05f
        }
        c.drawText("TOP LOGS", padding, y + 30f, labelPaint)
        y += 60f

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        val initPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x0DFFFFFF
        }
        val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 34f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF9CA3AF.toInt()
            textSize = 24f
        }
        val scorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 48f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
        }
        val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val clipPath = Path()

        history.forEach { item ->
            val itemRect = RectF(padding, y, W - padding, y + 130f)
            
            if (item.isCurrent) {
                bgPaint.shader = LinearGradient(padding, y, padding + 400f, y, 0x1A8B5CF6, 0x00FFFFFF, Shader.TileMode.CLAMP)
                borderPaint.color = 0x338B5CF6
            } else {
                bgPaint.shader = null
                bgPaint.color = 0x04FFFFFF
                borderPaint.color = 0x00FFFFFF
            }
            
            c.drawRoundRect(itemRect, 28f, 28f, bgPaint)
            c.drawRoundRect(itemRect, 28f, 28f, borderPaint)
            
            val iconRect = RectF(padding + 25f, y + 25f, padding + 105f, y + 105f)
            if (item.icon != null) {
                clipPath.reset()
                clipPath.addRoundRect(iconRect, 16f, 16f, Path.Direction.CW)
                c.save()
                c.clipPath(clipPath)
                c.drawBitmap(item.icon, null, iconRect, iconPaint)
                c.restore()
            } else {
                c.drawRoundRect(iconRect, 16f, 16f, initPaint)
            }
            
            val displayAppName = if (item.appName.length > 20) item.appName.substring(0, 18) + "..." else item.appName
            c.drawText(displayAppName, padding + 130f, y + 60f, namePaint)
            c.drawText(item.durationStr, padding + 130f, y + 100f, timePaint)
            
            scorePaint.color = scoreToColor(item.score)
            c.drawText(item.score.toString(), W - padding - 30f, y + 85f, scorePaint)
            
            y += 150f
        }
    }

    private fun drawWatermark(c: Canvas) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x88FFFFFF.toInt()
            textSize = 30f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        c.drawText("GameBar", W / 2f, H - 60f, paint)

        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x44FFFFFF.toInt()
            textSize = 22f
            textAlign = Paint.Align.CENTER
        }
        c.drawText("OLED Telemetry", W / 2f, H - 30f, subPaint)
    }

    private fun scoreToColor(score: Int): Int = when {
        score >= 85 -> 0xFF6EE7B7.toInt()
        score >= 75 -> Color.WHITE
        else -> 0xFFFCA5A5.toInt()
    }
}

class GameBarLivePerformanceCardView(context: Context) : android.view.View(context) {

    private var currentFps: Float = 0f
    private var maxFps: Float = 60f
    private var currentCpuTemp: Float = 0f
    private var currentCpuUsage: Float = 0f
    private var currentGpuUsage: Float = 0f
    private var currentPower: Float = 0f
    private var jitterStdDev: Float = 0f
    private var thermalStatusText: String = "Normal"
    
    private val fpsHistory = FloatArray(120)
    private var historyHead = 0
    private var historySize = 0

    private val bgPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x80000000.toInt()
    }
    
    private val strokePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 2f
        color = 0x33FFFFFF.toInt()
    }
    
    private val glowPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x2210B981.toInt()
        maskFilter = android.graphics.BlurMaskFilter(20f, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }

    private val titlePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8B949E.toInt()
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
    }

    private val valuePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
    }

    private val highlightPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF10B981.toInt()
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
    }
    
    private val chartPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
    }
    
    private val chartFillPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.FILL
    }
    
    private val chartPath = android.graphics.Path()
    private val chartFillPath = android.graphics.Path()
    
    private val cornerRadius = 24f

    fun updateData(
        fps: Float,
        cpuTemp: Float,
        cpuUsage: Float,
        gpuUsage: Float,
        powerWatt: Float,
        thermalStatus: String,
        jitter: Float = 0f
    ) {
        if (fps >= 0) {
            fpsHistory[historyHead] = fps
            historyHead = (historyHead + 1) % fpsHistory.size
            if (historySize < fpsHistory.size) historySize++
        }
        
        this.currentFps = if (fps >= 0) fps else 0f
        this.currentCpuTemp = cpuTemp
        this.currentCpuUsage = cpuUsage
        this.currentGpuUsage = gpuUsage
        this.currentPower = powerWatt
        this.thermalStatusText = thermalStatus
        this.jitterStdDev = jitter
        
        if (currentFps > maxFps) maxFps = currentFps.coerceAtLeast(60f)
        
        invalidate()
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        
        val w = width.toFloat()
        val h = height.toFloat()
        
        canvas.drawRoundRect(0f, 0f, w, h, cornerRadius, cornerRadius, bgPaint)
        canvas.drawRoundRect(0f, 0f, w, h, cornerRadius, cornerRadius, strokePaint)
        
        glowPaint.color = when (thermalStatusText.lowercase()) {
            "normal" -> 0x1110B981
            "light", "moderate" -> 0x22F59E0B
            "severe", "critical", "emergency", "shutdown" -> 0x33EF4444
            else -> 0x1110B981
        }
        canvas.drawCircle(w / 2f, 0f, w * 0.8f, glowPaint)

        val padding = 20f
        var yPos = padding + 10f
        
        titlePaint.textSize = w * 0.08f
        titlePaint.textAlign = android.graphics.Paint.Align.LEFT
        canvas.drawText("LIVE PERFORMANCE", padding, yPos + titlePaint.textSize, titlePaint)
        yPos += titlePaint.textSize + 10f
        
        canvas.drawLine(padding, yPos, w - padding, yPos, strokePaint)
        yPos += 20f
        
        val chartH = h * 0.25f
        val chartW = w - (padding * 2)
        val chartTop = yPos
        val chartBottom = chartTop + chartH
        
        if (historySize > 1) {
            chartPath.reset()
            chartFillPath.reset()
            
            val stepX = chartW / (historySize - 1)
            var startIdx = (historyHead - historySize)
            if (startIdx < 0) startIdx += fpsHistory.size
            
            val firstVal = fpsHistory[startIdx]
            val firstY = chartBottom - ((firstVal / maxFps.coerceAtLeast(1f)) * chartH)
            
            chartPath.moveTo(padding, firstY)
            chartFillPath.moveTo(padding, chartBottom)
            chartFillPath.lineTo(padding, firstY)
            
            var prevX = padding
            var prevY = firstY
            
            for (i in 1 until historySize) {
                val idx = (startIdx + i) % fpsHistory.size
                val v = fpsHistory[idx]
                val cx = padding + (i * stepX)
                val cy = chartBottom - ((v / maxFps.coerceAtLeast(1f)) * chartH)
                
                val midX = (prevX + cx) / 2f
                chartPath.cubicTo(midX, prevY, midX, cy, cx, cy)
                chartFillPath.cubicTo(midX, prevY, midX, cy, cx, cy)
                
                prevX = cx
                prevY = cy
            }
            
            chartFillPath.lineTo(prevX, chartBottom)
            chartFillPath.close()
            
            chartFillPaint.shader = android.graphics.LinearGradient(
                0f, chartTop, 0f, chartBottom,
                0x8810B981.toInt(), 0x0010B981.toInt(), android.graphics.Shader.TileMode.CLAMP
            )
            canvas.drawPath(chartFillPath, chartFillPaint)
            
            chartPaint.shader = android.graphics.LinearGradient(
                0f, 0f, w, 0f,
                0xFF10B981.toInt(), 0xFF3B82F6.toInt(), android.graphics.Shader.TileMode.CLAMP
            )
            canvas.drawPath(chartPath, chartPaint)
            
            canvas.drawCircle(prevX, prevY, 6f, highlightPaint)
            
            titlePaint.textSize = w * 0.05f
            canvas.drawText("MAX ${maxFps.toInt()}", padding, chartTop + 15f, titlePaint)
        }
        yPos = chartBottom + 35f
        
        val col1X = padding
        val col2X = w / 2f + padding / 2f
        val valTextSize = w * 0.11f
        val lblTextSize = w * 0.06f
        
        valuePaint.textSize = valTextSize
        highlightPaint.textSize = valTextSize
        titlePaint.textSize = lblTextSize
        
        canvas.drawText("FPS", col1X, yPos, titlePaint)
        canvas.drawText("${currentFps.toInt()}", col1X, yPos + valTextSize, highlightPaint)
        
        canvas.drawText("JITTER", col2X, yPos, titlePaint)
        canvas.drawText(String.format("%.1f", jitterStdDev), col2X, yPos + valTextSize, valuePaint)
        yPos += valTextSize + 40f
        
        canvas.drawText("CPU / GPU", col1X, yPos, titlePaint)
        canvas.drawText("${currentCpuUsage.toInt()}% / ${currentGpuUsage.toInt()}%", col1X, yPos + valTextSize, valuePaint)
        yPos += valTextSize + 40f
        
        canvas.drawText("THERMAL", col1X, yPos, titlePaint)
        val thermalColor = if (thermalStatusText != "Normal" && thermalStatusText != "Unknown") 0xFFEF4444.toInt() else 0xFFF59E0B.toInt()
        valuePaint.color = thermalColor
        var thermalText = "${currentCpuTemp.toInt()}°C"
        if (thermalStatusText != "Normal" && thermalStatusText != "Unknown") {
            thermalText += " ($thermalStatusText)"
            if (thermalText.length > 8) valuePaint.textSize = valTextSize * 0.7f
        }
        canvas.drawText(thermalText, col1X, yPos + valuePaint.textSize, valuePaint)
        valuePaint.color = android.graphics.Color.WHITE
        valuePaint.textSize = valTextSize
        
        canvas.drawText("POWER", col2X, yPos, titlePaint)
        canvas.drawText(String.format("%.1fW", currentPower), col2X, yPos + valTextSize, valuePaint)
    }
}
