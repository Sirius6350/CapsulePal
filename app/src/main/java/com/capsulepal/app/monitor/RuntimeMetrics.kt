package com.capsulepal.app.monitor

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import java.util.ArrayDeque
import java.util.Locale

/** 一条运行时指标采样。 */
data class RuntimePoint(
    val ts: Long,
    val fps: Float?,
    val memoryUsedMb: Int?
)

/** 当前帧率与内存快照。 */
data class RuntimeSnapshot(
    val fps: Float?,
    val memoryUsedMb: Int?,
    val memoryTotalMb: Int?
)

/**
 * 帧率与内存的 1 小时环形采样（10 秒一个点）。
 *
 * 只保存在内存里：不写数据库、不进备份、杀进程即清空；
 * 极客版监测页与指标悬浮窗共用这一份数据。
 */
object RuntimeMetrics {

    private const val MAX_POINTS = 360
    private const val SAMPLE_INTERVAL_MILLIS = 10_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val points = ArrayDeque<RuntimePoint>()

    private var started = false
    private var frameCount = 0
    private var fpsWindowStart = 0L
    private var latestFps: Float? = null
    private var latestUsedMb: Int? = null
    private var totalMb: Int? = null
    private var lastPointTs = 0L
    private var activityManager: ActivityManager? = null

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!started) return
            val now = System.currentTimeMillis()
            if (fpsWindowStart == 0L) fpsWindowStart = now
            frameCount++
            val elapsed = now - fpsWindowStart
            if (elapsed >= 1_000L) {
                latestFps = frameCount * 1000f / elapsed
                frameCount = 0
                fpsWindowStart = now
            }
            if (now - lastPointTs >= SAMPLE_INTERVAL_MILLIS) {
                recordPoint(now)
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private val memoryTick = object : Runnable {
        override fun run() {
            if (!started) return
            refreshMemory()
            mainHandler.postDelayed(this, 5_000L)
        }
    }

    fun start(context: Context) {
        if (started) return
        started = true
        activityManager = context.applicationContext
            .getSystemService(ActivityManager::class.java)
        frameCount = 0
        fpsWindowStart = 0L
        lastPointTs = 0L
        mainHandler.post {
            Choreographer.getInstance().postFrameCallback(frameCallback)
            refreshMemory()
            mainHandler.postDelayed(memoryTick, 5_000L)
        }
    }

    fun stop() {
        started = false
        mainHandler.removeCallbacks(memoryTick)
        points.clear()
        latestFps = null
        latestUsedMb = null
    }

    fun started(): Boolean = started

    fun snapshot(): RuntimeSnapshot = RuntimeSnapshot(
        fps = latestFps,
        memoryUsedMb = latestUsedMb,
        memoryTotalMb = totalMb
    )

    /** 最近 1 小时的曲线，按时间升序。 */
    fun points(): List<RuntimePoint> = synchronized(points) { points.toList() }

    private fun recordPoint(now: Long) {
        lastPointTs = now
        synchronized(points) {
            points.addLast(RuntimePoint(now, latestFps, latestUsedMb))
            while (points.size > MAX_POINTS) points.removeFirst()
        }
    }

    private fun refreshMemory() {
        val manager = activityManager ?: return
        val info = ActivityManager.MemoryInfo()
        runCatching { manager.getMemoryInfo(info) }.onSuccess {
            totalMb = (info.totalMem / 1024 / 1024).toInt()
            latestUsedMb = ((info.totalMem - info.availMem) / 1024 / 1024)
                .toInt()
                .coerceAtLeast(0)
        }
    }

    /** 供悬浮窗格式化成一位小数。 */
    fun usedGb(): Float? = latestUsedMb?.let { it / 1024f }

    fun totalGb(): Float? = totalMb?.let { it / 1024f }

    /** 电量百分比取整，给悬浮窗用。 */
    fun batteryText(level: Int?): String = level?.let { "$it%" } ?: "--"

    /** 极客体验：采样值统一保留一位小数。 */
    fun fpsText(): String =
        latestFps?.let { String.format(Locale.CHINA, "%.1f", it) } ?: "--"

    fun temperatureText(tempC: Float?): String =
        tempC?.let { String.format(Locale.CHINA, "%.1f°C", it) } ?: "--"

    fun memoryText(): String {
        val used = usedGb() ?: return "--"
        val total = totalGb() ?: return String.format(Locale.CHINA, "%.1fGB", used)
        return String.format(Locale.CHINA, "%.1f/%.1fGB", used, total)
    }
}
