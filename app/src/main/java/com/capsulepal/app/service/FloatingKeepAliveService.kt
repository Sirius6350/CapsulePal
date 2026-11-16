package com.capsulepal.app.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.capsulepal.app.CapsuleApp
import com.capsulepal.app.MainActivity
import com.capsulepal.app.R
import com.capsulepal.app.data.model.AppSettings
import com.capsulepal.app.data.model.ReminderActionType
import com.capsulepal.app.data.model.needsBackgroundWatch
import com.capsulepal.app.notifier.NotificationActions
import com.capsulepal.app.notifier.NotificationChannels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 前台保活服务。
 *
 * 启动条件（任一满足）：
 * 1. 用户开启「后台保持陪伴」；
 * 2. 存在启用的 WiFi / 蓝牙 / 打开应用规则（这些依赖进程内监听）。
 *
 * 悬浮点窗口由 App 级的 [OverlayDotController] 负责：它自己收集状态、做健康检查、
 * 亮屏重挂、定时移动。服务只负责三件事：
 * - 撑住前台通知，降低进程被回收的概率；
 * - 把最新设置翻译成悬浮点的显示参数；
 * - 设置全关时退出。
 *
 * 这样即使服务被 ROM 重建，窗口也不会跟着消失；窗口被 ROM 收走时，
 * OverlayDotController 的 2 秒健康检查会把它重新挂上。
 */
class FloatingKeepAliveService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var dot: OverlayDotController
    private lateinit var scheduler: com.capsulepal.app.scheduler.AlarmScheduler
    private var lastNotificationMode: Int? = null
    private var lastOverlayHidden = false
    /** 上一次下发设置时「后台保持陪伴」是否开启，用于识别关→开并复位悬浮点。 */
    private var lastKeepAliveEnabled = false

    /** 供电量状态回调复用的最新设置，避免每秒重读 DataStore。 */
    @Volatile
    private var latestSettings: AppSettings? = null

    /** 服务此刻是否应该继续存在；销毁时据此决定要不要自愈重启。 */
    @Volatile
    private var shouldRun = false

    /** 已经进入关闭流程，不允许再复用这个实例。 */
    @Volatile
    private var stopping = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        NotificationChannels.ensure(this)
        val container = (application as CapsuleApp).container
        dot = container.overlayDot
        scheduler = container.scheduler

        // 悬浮点第一次真正显示时提示一次（只在开启「后台保持陪伴」后出现）。
        dot.onFirstShow = { showTip(lastOverlayHidden) }

        if (!startForegroundNow()) return

        scope.launch {
            container.settingsRepository.settings
                .distinctUntilChanged()
                .collect { settings ->
                    runCatching { applySettings(settings) }
                }
        }

        // 极客版指标悬浮窗需要跟随每秒电量读数刷新温度与电量。
        scope.launch {
            container.batteryMonitor.state.collect { monitor ->
                val settings = latestSettings ?: return@collect
                if (!(settings.version.showFloatingMetrics && settings.metricOverlayEnabled)) {
                    return@collect
                }
                runCatching {
                    container.metricOverlay.update(
                        enabled = true,
                        alphaPercent = settings.metricOverlayAlphaSafe,
                        scalePercent = settings.metricOverlayScaleSafe,
                        showTemp = settings.metricOverlayShowTemp,
                        showBattery = settings.metricOverlayShowBattery,
                        showFps = settings.metricOverlayShowFps,
                        showMemory = settings.metricOverlayShowMemory,
                        temperatureC = monitor?.reading?.tempC,
                        levelPct = monitor?.reading?.levelPct,
                        horizontal = settings.metricOverlayHorizontal,
                        offsetX = settings.metricOverlayOffsetX,
                        offsetY = settings.metricOverlayOffsetY
                    )
                }
            }
        }
    }

    private fun startForegroundNow(): Boolean {
        val started = runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(MODE_KEEP_ALIVE),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    0
                }
            )
            true
        }.getOrDefault(false)
        if (!started) {
            stopping = true
            if (instance === this) instance = null
            stopSelf()
        }
        return started
    }

    private suspend fun applySettings(settings: AppSettings) {
        val container = (application as CapsuleApp).container
        latestSettings = settings
        // 极客体验没有待办模块，待办类规则不触发，也就不需要后台监听。
        fun needsWatch(s: AppSettings): Boolean = s.version.showReminders &&
            s.reminderRules.any {
                it.enabled && it.type.needsBackgroundWatch &&
                    (s.version.showTodoPage || it.action == ReminderActionType.CUSTOM)
            }
        // 极客体验或非极客版开启「为极客采样」时都需要常驻做 1 秒采样。
        fun needsGeek(s: AppSettings): Boolean = s.version.recordsBatteryTimeline ||
            (s.geekSamplingInOtherVersions && s.batteryHighFrequencyAlways)
        val needsMetrics = settings.version.showFloatingMetrics && settings.metricOverlayEnabled
        shouldRun = settings.keepAliveEnabled || needsWatch(settings) || needsMetrics ||
            needsGeek(settings)
        if (!shouldRun) {
            // 设置流偶尔会推来一个短暂过渡值，先重新确认真实状态再停止，避免悬浮点闪一下就没了。
            val latest = runCatching {
                container.settingsRepository.settings.first()
            }.getOrNull()
            val stillOff = latest != null &&
                !latest.keepAliveEnabled &&
                !needsWatch(latest) &&
                !(latest.version.showFloatingMetrics && latest.metricOverlayEnabled) &&
                !needsGeek(latest)
            if (latest == null || stillOff) {
                shutdown()
                return
            }
            applySettings(latest)
            return
        }

        updateNotificationMode(
            when {
                settings.keepAliveEnabled -> MODE_KEEP_ALIVE
                settings.version.recordsBatteryTimeline -> MODE_GEEK
                else -> MODE_WATCH
            }
        )
        lastOverlayHidden = settings.overlayHidden
        // 移动已由 OverlayDotController 自己驱动，不再排 TICK 闹钟。
        scheduler.cancelOffsetTick()
        dot.update(
            enabled = settings.keepAliveEnabled,
            hidden = settings.overlayHidden,
            alphaPercent = settings.overlayAlphaSafe,
            moveMinutes = settings.overlayMoveMinutesSafe
        )
        // 极客版指标悬浮窗：独立开关与外观，不跟随悬浮点。
        val monitor = container.batteryMonitor.state.value
        runCatching {
            container.metricOverlay.update(
                enabled = needsMetrics,
                alphaPercent = settings.metricOverlayAlphaSafe,
                scalePercent = settings.metricOverlayScaleSafe,
                showTemp = settings.metricOverlayShowTemp,
                showBattery = settings.metricOverlayShowBattery,
                showFps = settings.metricOverlayShowFps,
                showMemory = settings.metricOverlayShowMemory,
                temperatureC = monitor?.reading?.tempC,
                levelPct = monitor?.reading?.levelPct,
                horizontal = settings.metricOverlayHorizontal,
                offsetX = settings.metricOverlayOffsetX,
                offsetY = settings.metricOverlayOffsetY
            )
        }
        // 关→开时无论服务是否重建过，都明确复位一次左上角。
        if (settings.keepAliveEnabled && !lastKeepAliveEnabled) {
            dot.resetToTopLeft()
        }
        lastKeepAliveEnabled = settings.keepAliveEnabled
    }

    private fun updateNotificationMode(mode: Int) {
        if (lastNotificationMode == mode) return
        lastNotificationMode = mode
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.notify(NOTIFICATION_ID, buildNotification(mode)) }
    }

    private fun showTip(visible: Boolean) {
        val text = if (visible) {
            "悬浮点已重置到屏幕左上角"
        } else {
            "后台保持陪伴已开启，悬浮点当前隐藏"
        }
        runCatching {
            Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show()
        }
    }

    private fun shutdown() {
        shouldRun = false
        stopping = true
        if (instance === this) instance = null
        dot.onFirstShow = null
        dot.hide()
        (application as? CapsuleApp)?.container?.metricOverlay?.hide()
        scheduler.cancelOffsetTick()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (stopping) {
            // 这个实例已经在关闭流程里，等它销毁后重新拉起一个新实例。
            Handler(Looper.getMainLooper()).postDelayed({
                start(applicationContext)
            }, 1_000L)
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_START, ACTION_TICK -> {
                scope.launch {
                    runCatching {
                        applySettings(
                            (application as CapsuleApp).container.settingsRepository.settings.first()
                        )
                    }
                }
            }
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        val restart = shouldRun && !stopping
        if (instance === this) instance = null
        dot.onFirstShow = null
        // 注意：这里不隐藏悬浮点。服务被系统回收时窗口应继续存在，
        // 新拉起的服务实例会复用同一个 OverlayDotController。
        scheduler.cancelOffsetTick()
        scope.cancel()
        super.onDestroy()
        if (restart) {
            Handler(Looper.getMainLooper()).postDelayed({
                runCatching {
                    ContextCompat.startForegroundService(
                        applicationContext,
                        Intent(applicationContext, FloatingKeepAliveService::class.java).apply {
                            action = ACTION_START
                        }
                    )
                }
            }, 1_200L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(mode: Int): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 3001, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = when (mode) {
            MODE_KEEP_ALIVE -> "小胶囊在后台安静待着"
            MODE_GEEK -> "极客采样进行中"
            else -> "主动提醒监听中"
        }
        val text = when (mode) {
            MODE_KEEP_ALIVE -> "只做保活，不打扰；隐藏悬浮点后仍在。"
            MODE_GEEK -> "充放电每 1 秒采样；悬浮点显示由你控制。"
            else -> "打开应用、蓝牙、WiFi 提醒正在后台待命。"
        }
        return NotificationCompat.Builder(this, NotificationChannels.KEEP_ALIVE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(pendingIntent)
            .build()
    }

    companion object {
        const val NOTIFICATION_ID = NotificationActions.KEEP_ALIVE_NOTIFICATION_ID
        const val ACTION_START = "com.capsulepal.app.action.START_KEEP_ALIVE"
        const val ACTION_STOP = "com.capsulepal.app.action.STOP_KEEP_ALIVE"
        const val ACTION_TICK = "com.capsulepal.app.action.KEEP_ALIVE_TICK"
        private const val MODE_WATCH = 0
        private const val MODE_KEEP_ALIVE = 1
        private const val MODE_GEEK = 2

        /** 服务已存在时直接复用，避免 startForegroundService 在后台被系统拒绝而静默失败。 */
        @Volatile
        private var instance: FloatingKeepAliveService? = null

        /** 供提醒诊断页判断后台监听是否还活着。 */
        val isRunning: Boolean
            get() = instance?.stopping == false

        fun start(context: Context) {
            val appContext = context.applicationContext
            val running = instance
            if (running != null && !running.stopping) {
                running.scope.launch {
                    runCatching {
                        val container = (running.application as CapsuleApp).container
                        running.applySettings(container.settingsRepository.settings.first())
                    }
                }
                return
            }
            if (running != null) {
                // 旧实例还在关闭流程里，等它销毁后再拉起，不能复用一个即将销毁的实例。
                Handler(Looper.getMainLooper()).postDelayed({ start(appContext) }, 1_000L)
                return
            }
            instance = null
            val intent = Intent(appContext, FloatingKeepAliveService::class.java).apply {
                action = ACTION_START
            }
            runCatching { ContextCompat.startForegroundService(appContext, intent) }
                .onFailure { runCatching { appContext.startService(intent) } }
        }

        /** 重新显示悬浮点：保留上次位置，不重置也不提示。 */
        fun showDot(context: Context) = start(context)

        fun stop(context: Context) {
            val appContext = context.applicationContext
            val running = instance
            if (running != null) {
                running.stopping = true
                instance = null
                // 先隐藏窗口（幂等），即使服务正在销毁也不会留下一个孤儿悬浮点。
                (appContext as? CapsuleApp)?.container?.overlayDot?.hide()
                running.scope.launch { running.shutdown() }
                return
            }
            runCatching {
                appContext.stopService(Intent(appContext, FloatingKeepAliveService::class.java))
            }
            // 服务已经不在时，窗口可能还挂在应用容器上，一并收掉。
            (appContext as? CapsuleApp)?.container?.overlayDot?.hide()
        }

        fun tick(context: Context) = start(context)
    }
}
