package com.capsulepal.app.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.capsulepal.app.monitor.RuntimeMetrics
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 极客版独立指标悬浮窗：温度、电量、帧率、内存。
 *
 * 与保活悬浮点完全独立：开关、透明度、大小、横竖排都单独设置；
 * 窗口可按住拖动、双击关闭，位置由设置层持久化。
 */
class MetricOverlayController(
    context: Context,
    private val onRequestClose: () -> Unit = {},
    private val onPositionChanged: (Int, Int) -> Unit = { _, _ -> }
) {

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val windowManager = appContext.getSystemService(WindowManager::class.java)
    private val density = appContext.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(appContext).scaledTouchSlop

    private var window: View? = null
    private var line1: TextView? = null
    private var line2: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var enabled = false
    private var alphaPercent = 80
    private var scalePercent = 100
    private var showTemp = true
    private var showBattery = true
    private var showFps = true
    private var showMemory = true
    private var horizontal = false
    private var temperatureC: Float? = null
    private var levelPct: Int? = null

    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragStartX = 0
    private var dragStartY = 0
    private var dragging = false
    private var lastTapUpAt = 0L

    private val tick = object : Runnable {
        override fun run() {
            if (!enabled) return
            updateText()
            handler.postDelayed(this, 1_000L)
        }
    }

    /** 由前台服务在设置或电量变化时调用。 */
    fun update(
        enabled: Boolean,
        alphaPercent: Int,
        scalePercent: Int,
        showTemp: Boolean,
        showBattery: Boolean,
        showFps: Boolean,
        showMemory: Boolean,
        temperatureC: Float?,
        levelPct: Int?,
        horizontal: Boolean = false,
        offsetX: Int = -1,
        offsetY: Int = -1
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post {
                update(
                    enabled, alphaPercent, scalePercent,
                    showTemp, showBattery, showFps, showMemory,
                    temperatureC, levelPct, horizontal, offsetX, offsetY
                )
            }
            return
        }
        val structureChanged = enabled != this.enabled ||
            scalePercent != this.scalePercent ||
            showTemp != this.showTemp ||
            showBattery != this.showBattery ||
            showFps != this.showFps ||
            showMemory != this.showMemory ||
            horizontal != this.horizontal
        this.enabled = enabled
        this.alphaPercent = alphaPercent.coerceIn(10, 100)
        this.scalePercent = scalePercent.coerceIn(80, 140)
        this.showTemp = showTemp
        this.showBattery = showBattery
        this.showFps = showFps
        this.showMemory = showMemory
        this.horizontal = horizontal
        this.temperatureC = temperatureC
        this.levelPct = levelPct

        if (!enabled) {
            hide()
            return
        }
        if (structureChanged || window == null) {
            rebuild(offsetX, offsetY)
        } else {
            applyAlpha()
            updateText()
        }
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 1_000L)
    }

    fun hide() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { hide() }
            return
        }
        enabled = false
        handler.removeCallbacks(tick)
        removeWindow()
    }

    private fun rebuild(offsetX: Int, offsetY: Int) {
        removeWindow()
        if (!Settings.canDrawOverlays(appContext)) return
        val manager = windowManager ?: return
        val view = buildView()
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = if (offsetX >= 0) offsetX else (12 * density).roundToInt()
            y = if (offsetY >= 0) offsetY else (56 * density).roundToInt()
            alpha = this@MetricOverlayController.alphaPercent / 100f
        }
        val added = runCatching {
            manager.addView(view, layoutParams)
            true
        }.getOrDefault(false)
        if (!added) return
        window = view
        params = layoutParams
        installTouch(view)
        updateText()
    }

    private fun buildView(): LinearLayout {
        val scale = scalePercent / 100f
        val horizontalPadding = (12 * density * scale).roundToInt()
        val verticalPadding = (8 * density * scale).roundToInt()
        val panel = LinearLayout(appContext).apply {
            orientation = if (horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
            background = GradientDrawable().apply {
                cornerRadius = 10 * density * scale
                setColor(Color.argb(150, 16, 20, 28))
                setStroke((1 * density).roundToInt(), Color.argb(60, 255, 255, 255))
            }
        }
        val first = metricText(scale)
        val second = metricText(scale)
        panel.addView(first)
        if (!horizontal) panel.addView(second)
        line1 = first
        line2 = if (horizontal) null else second
        return panel
    }

    private fun metricText(scale: Float): TextView = TextView(appContext).apply {
        setTextColor(Color.WHITE)
        textSize = 11f * scale
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        includeFontPadding = false
        setLineSpacing(2f * density, 1f)
        // 等宽数字，实时数值变化时不会左右抖。
        fontFeatureSettings = "tnum"
    }

    private fun installTouch(view: View) {
        view.setOnTouchListener { _, event ->
            val layoutParams = params ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val now = System.currentTimeMillis()
                    if (now - lastTapUpAt <= 320L) {
                        lastTapUpAt = 0L
                        dragging = false
                        onRequestClose()
                        hide()
                        return@setOnTouchListener true
                    }
                    dragging = false
                    dragStartRawX = event.rawX
                    dragStartRawY = event.rawY
                    dragStartX = layoutParams.x
                    dragStartY = layoutParams.y
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - dragStartRawX
                    val dy = event.rawY - dragStartRawY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                    }
                    if (dragging) {
                        val metrics = appContext.resources.displayMetrics
                        val maxX = (metrics.widthPixels - view.width).coerceAtLeast(0)
                        val maxY = (metrics.heightPixels - view.height).coerceAtLeast(0)
                        // gravity 为 TOP|END：x 越大越靠左。
                        layoutParams.x = (dragStartX - dx.roundToInt()).coerceIn(0, maxX)
                        layoutParams.y = (dragStartY + dy.roundToInt()).coerceIn(0, maxY)
                        runCatching { windowManager?.updateViewLayout(view, layoutParams) }
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        onPositionChanged(layoutParams.x, layoutParams.y)
                    } else {
                        lastTapUpAt = System.currentTimeMillis()
                    }
                    dragging = false
                    true
                }

                else -> false
            }
        }
    }

    private fun updateText() {
        val first = line1 ?: return
        val lines = mutableListOf<String>()
        if (showTemp) lines.add(RuntimeMetrics.temperatureText(temperatureC))
        if (showBattery) lines.add(RuntimeMetrics.batteryText(levelPct))
        if (showFps) lines.add("${RuntimeMetrics.fpsText()} FPS")
        if (showMemory) lines.add(RuntimeMetrics.memoryText())
        if (horizontal) {
            first.text = lines.joinToString(" · ")
            first.visibility = if (lines.isEmpty()) View.GONE else View.VISIBLE
            line2?.visibility = View.GONE
        } else {
            first.text = lines.getOrNull(0) ?: ""
            val second = line2
            second?.text = lines.drop(1).joinToString("\n")
            first.visibility = if (lines.isEmpty()) View.GONE else View.VISIBLE
            second?.visibility = if (lines.size <= 1) View.GONE else View.VISIBLE
        }
    }

    private fun applyAlpha() {
        params?.alpha = alphaPercent / 100f
        val view = window ?: return
        val layoutParams = params ?: return
        runCatching { windowManager?.updateViewLayout(view, layoutParams) }
    }

    private fun removeWindow() {
        val view = window
        window = null
        line1 = null
        line2 = null
        params = null
        if (view != null) runCatching { windowManager?.removeViewImmediate(view) }
    }
}
