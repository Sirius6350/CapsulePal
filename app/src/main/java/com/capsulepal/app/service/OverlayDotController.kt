package com.capsulepal.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * 悬浮点窗口管理（参照 Syscan 的 FloatingWindowManager 重写）。
 *
 * 与旧实现的关键差别：
 * 1. 实例挂在 Application 上（AppContainer 持有），不随前台服务重建而丢失窗口；
 * 2. 尺寸 / 可见性变化一律整窗重建（removeViewImmediate + addView），
 *    不用 updateViewLayout 改尺寸——部分 ROM 对 overlay 缩小尺寸不生效；
 * 3. 只保留最小窗口 flag（NOT_FOCUSABLE + NOT_TOUCHABLE），
 *    不用 LAYOUT_NO_LIMITS，避免窗口被摆到屏幕外；
 * 4. 2 秒一次健康检查 + 亮屏/解锁广播，窗口被系统收走会立刻重建；
 * 5. 移动由本类内的 Handler 循环驱动，只要进程活着就一定会动。
 *
 * 显示悬浮点时按 1dp 纯黑绘制；隐藏时缩成 1px 并固定 10% 透明度。
 */
class OverlayDotController(context: Context) {

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val windowManager = appContext.getSystemService(WindowManager::class.java)
    private val random = Random(System.currentTimeMillis())

    /** 当前挂在系统上的窗口；null 表示没有窗口。 */
    private var window: View? = null
    private var params: WindowManager.LayoutParams? = null

    /** 以 addView 成功为准，避免 View 引用还在但窗口已经被系统摘掉。 */
    private var shown = false

    private var enabled = false
    private var hiddenMode = false
    private var alpha = DEFAULT_ALPHA
    private var moveMinutes = DEFAULT_MOVE_MINUTES

    private var x = 0
    private var y = 0
    private var hasPosition = false

    /** 本次开启后还没有成功显示过；用于只提示一次。 */
    private var firstShowPending = true
    var onFirstShow: (() -> Unit)? = null

    private var receiverRegistered = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!enabled) return
            // 亮屏 / 解锁后部分 ROM 会把 overlay 临时收走，这里立即确认窗口还在。
            handler.post { ensureWindow() }
        }
    }

    /** 窗口被系统收走时最多 2 秒就会自愈；窗口正常时只是一次很轻的检查。 */
    private val healthTick = object : Runnable {
        override fun run() {
            if (!enabled) return
            ensureWindow()
            handler.postDelayed(this, HEALTH_INTERVAL_MILLIS)
        }
    }

    /** 按用户设定间隔移动。 */
    private val moveTick = object : Runnable {
        override fun run() {
            if (!enabled) return
            if (ensureWindow()) drift()
            handler.postDelayed(this, moveMinutes * 60_000L)
        }
    }

    /**
     * 由前台服务在每次设置变化时调用（主线程；从其他线程调用会自动转到主线程）。
     */
    fun update(enabled: Boolean, hidden: Boolean, alphaPercent: Int, moveMinutes: Int) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { update(enabled, hidden, alphaPercent, moveMinutes) }
            return
        }
        val wasEnabled = this.enabled
        this.enabled = enabled
        if (!enabled) {
            firstShowPending = true
            detachWindow()
            return
        }

        val nextHidden = hidden
        val nextAlpha = if (hidden) HIDDEN_ALPHA else alphaPercent.coerceIn(10, 80) / 100f
        val nextMoveMinutes = moveMinutes.coerceIn(1, 30)
        val structureChanged = nextHidden != hiddenMode

        hiddenMode = nextHidden
        alpha = nextAlpha
        window?.alpha = nextAlpha
        params?.alpha = 1f

        registerScreenReceiver()

        if (!wasEnabled) {
            // 总开关重新打开：回到左上角，并允许再提示一次。
            hasPosition = false
            firstShowPending = true
            this.moveMinutes = nextMoveMinutes
            ensureWindow(forceRebuild = true)
            restartMoveLoop()
        } else {
            ensureWindow(forceRebuild = structureChanged)
            if (nextMoveMinutes != this.moveMinutes) {
                this.moveMinutes = nextMoveMinutes
                restartMoveLoop()
            }
        }
        startHealthLoop()
    }

    /** 关闭悬浮点（保留实例，方便下次直接复用）。 */
    fun hide() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { hide() }
            return
        }
        enabled = false
        firstShowPending = true
        detachWindow()
    }

    /**
     * 强制回到屏幕左上角。
     *
     * 不依赖 enabled 的关→开过渡：总开关一打开就直接改写窗口坐标，
     * 因此即使服务一直在为规则监听而存活、或窗口被系统重建过，也会回到左上角。
     * 改坐标用 updateViewLayout（各 ROM 都支持），不做整窗重建，避免闪一下。
     */
    fun resetToTopLeft() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { resetToTopLeft() }
            return
        }
        hasPosition = false
        ensurePlacement(dotSize())
        firstShowPending = true
        if (!enabled) return

        val view = window
        val layoutParams = params
        if (view != null && layoutParams != null && view.isAttachedToWindow) {
            layoutParams.x = x
            layoutParams.y = y
            runCatching { windowManager?.updateViewLayout(view, layoutParams) }
        } else {
            ensureWindow()
        }
    }

    private fun detachWindow() {
        handler.removeCallbacks(healthTick)
        handler.removeCallbacks(moveTick)
        removeWindow()
    }

    private fun startHealthLoop() {
        handler.removeCallbacks(healthTick)
        handler.postDelayed(healthTick, HEALTH_INTERVAL_MILLIS)
    }

    private fun restartMoveLoop() {
        handler.removeCallbacks(moveTick)
        handler.postDelayed(moveTick, moveMinutes * 60_000L)
    }

    private fun registerScreenReceiver() {
        if (receiverRegistered) return
        receiverRegistered = true
        runCatching {
            ContextCompat.registerReceiver(
                appContext,
                screenReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_USER_PRESENT)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
    }

    /**
     * 确认窗口真的挂在屏幕上；不正常就整窗重建。
     * 息屏时系统本就不绘制 overlay，这里不做特殊处理，重建也不会造成可见闪烁。
     */
    private fun ensureWindow(forceRebuild: Boolean = false): Boolean {
        if (!enabled) return false
        if (!canDrawOverlay()) {
            if (shown) removeWindow()
            return false
        }
        if (forceRebuild) return rebuild()
        val view = window
        if (shown && view != null && view.isAttachedToWindow && view.isShown) {
            if (abs(view.alpha - alpha) > 0.01f) view.alpha = alpha
            return true
        }
        return rebuild()
    }

    private fun rebuild(): Boolean {
        removeWindow()
        return addWindow()
    }

    private fun addWindow(): Boolean {
        if (!enabled || !canDrawOverlay()) return false
        val manager = windowManager ?: return false
        val size = dotSize()
        ensurePlacement(size)

        val layoutParams = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = this@OverlayDotController.x
            y = this@OverlayDotController.y
            // 透明度只由 View.alpha 控制，窗口 alpha 必须保持 1，否则会相乘。
            alpha = 1f
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
        }

        val view = View(appContext).apply {
            // 纯黑底，不加描边。
            setBackgroundColor(0xFF000000.toInt())
            alpha = this@OverlayDotController.alpha
        }
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit

            override fun onViewDetachedFromWindow(v: View) {
                // removeWindow() 会先把 window 置空，因此这里只会被系统摘窗口触发。
                if (window !== v) return
                window = null
                params = null
                shown = false
                handler.postDelayed({ if (enabled) ensureWindow() }, 300L)
            }
        })
        val added = runCatching {
            manager.addView(view, layoutParams)
            true
        }.getOrDefault(false)
        if (!added) return false

        window = view
        params = layoutParams
        shown = true
        if (firstShowPending) {
            firstShowPending = false
            onFirstShow?.invoke()
        }
        return true
    }

    private fun removeWindow() {
        val view = window
        window = null
        params = null
        shown = false
        if (view != null) runCatching { windowManager?.removeViewImmediate(view) }
    }

    /**
     * 按设定间隔随机移动：每跳随机方向、跨越一段距离，碰边按镜面回弹，
     * 因此不会贴边反复点亮，也不会长期停在某个角落。
     */
    private fun drift() {
        val view = window ?: return
        val layoutParams = params ?: return
        val manager = windowManager ?: return
        val bounds = screenBounds()
        val size = dotSize()
        val maxX = max(1, bounds.width() - size)
        val maxY = max(1, bounds.height() - size)

        val angle = random.nextDouble(0.0, Math.PI * 2)
        var dirX = kotlin.math.cos(angle).toFloat()
        var dirY = kotlin.math.sin(angle).toFloat()
        val span = min(maxX, maxY) * (0.35f + random.nextFloat() * 0.55f)
        var targetX = x + dirX * span
        var targetY = y + dirY * span

        repeat(4) {
            if (targetX < 0f) {
                targetX = -targetX
                dirX = -dirX
            }
            if (targetX > maxX) {
                targetX = 2f * maxX - targetX
                dirX = -dirX
            }
            if (targetY < 0f) {
                targetY = -targetY
                dirY = -dirY
            }
            if (targetY > maxY) {
                targetY = 2f * maxY - targetY
                dirY = -dirY
            }
        }

        val endX = targetX.coerceIn(0f, maxX.toFloat()).roundToInt()
        val endY = targetY.coerceIn(0f, maxY.toFloat()).roundToInt()

        // 瞬移：直接改写窗口坐标，不做位移动画。
        x = endX
        y = endY
        layoutParams.x = endX
        layoutParams.y = endY
        runCatching { manager.updateViewLayout(view, layoutParams) }
    }

    private fun canDrawOverlay(): Boolean = Settings.canDrawOverlays(appContext)

    private fun dotSize(): Int = if (hiddenMode) {
        1
    } else {
        (appContext.resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
    }

    private fun ensurePlacement(size: Int) {
        if (!hasPosition) {
            val margin = (16 * appContext.resources.displayMetrics.density).roundToInt()
            x = margin
            y = statusBarHeight() + margin
            hasPosition = true
        }
        val bounds = screenBounds()
        val maxX = max(0, bounds.width() - size)
        val maxY = max(0, bounds.height() - size)
        x = x.coerceIn(0, maxX)
        y = y.coerceIn(0, maxY)
    }

    private fun statusBarHeight(): Int {
        val id = appContext.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) appContext.resources.getDimensionPixelSize(id) else 0
    }

    private fun screenBounds(): Rect {
        val manager = windowManager
        return if (manager != null) {
            manager.currentWindowMetrics.bounds
        } else {
            val display = appContext.resources.displayMetrics
            Rect(0, 0, display.widthPixels, display.heightPixels)
        }
    }

    private companion object {
        const val HIDDEN_ALPHA = 0.1f
        const val DEFAULT_ALPHA = 0.8f
        const val DEFAULT_MOVE_MINUTES = 3
        const val HEALTH_INTERVAL_MILLIS = 2_000L
    }
}
