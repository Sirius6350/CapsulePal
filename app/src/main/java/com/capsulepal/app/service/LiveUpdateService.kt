package com.capsulepal.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.capsulepal.app.CapsuleApp
import com.capsulepal.app.notifier.NotificationActions
import com.capsulepal.app.notifier.NotificationChannels
import com.capsulepal.app.notifier.NotificationPlan
import com.capsulepal.app.widget.CapsuleWidgets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class LiveUpdateService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var refreshJob: Job? = null
    private var foregroundStarted = false
    private var foregroundNotificationId = 0

    override fun onCreate() {
        super.onCreate()
        instance = this
        NotificationChannels.ensure(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            val container = (application as CapsuleApp).container
            container.notifications.cancel()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val container = (application as CapsuleApp).container
        if (!foregroundStarted) {
            val started = startForegroundSafely(
                container.notifications.placeholder(),
                NotificationActions.TASK_NOTIFICATION_ID
            )
            if (!started) {
                // 后台启动前台服务被系统拦截：直接投递当前上岛通知，避免完全静默。
                container.refreshIslandDirect()
                stopSelf()
                return START_NOT_STICKY
            }
            foregroundStarted = true
        }

        launchRefreshLoop()
        return START_STICKY
    }

    /**
     * 服务已经在跑时直接唤醒刷新循环。
     * 后台调用 startForegroundService 可能被系统拦截，但服务本身还活着，
     * 这样插电时能立刻切到 1 秒刷新，而不是等上一轮 60 秒的延时走完。
     */
    private fun poke() {
        launchRefreshLoop()
    }

    private fun launchRefreshLoop() {
        val container = (application as CapsuleApp).container
        refreshJob?.cancel()
        refreshJob = scope.launch {
            while (true) {
                var nextDelay = REFRESH_INTERVAL_MILLIS
                val keepRunning = when (val plan = container.notifications.buildPlan()) {
                    is NotificationPlan.Live -> {
                        nextDelay = plan.refreshIntervalMillis
                        val id = NotificationActions.islandNotificationId(plan.content)
                        if (startForegroundSafely(plan.notification, id)) {
                            foregroundStarted = true
                        } else {
                            // 无法作为前台服务展示时直接投递，至少让上岛通知出现。
                            container.notifications.postLive(plan.notification, id)
                        }
                        true
                    }
                    is NotificationPlan.Fallback -> {
                        nextDelay = plan.refreshIntervalMillis
                        val id = NotificationActions.islandNotificationId(plan.content)
                        if (container.notifications.testActive()) {
                            // 测试降级时也保持前台刷新，确保准备/进行中切换与 2 分钟后自动收起。
                            if (startForegroundSafely(plan.notification, id)) {
                                foregroundStarted = true
                            } else {
                                container.notifications.postFallback(plan.notification, id)
                            }
                            true
                        } else {
                            ServiceCompat.stopForeground(
                                this@LiveUpdateService,
                                ServiceCompat.STOP_FOREGROUND_REMOVE
                            )
                            foregroundStarted = false
                            foregroundNotificationId = 0
                            // 先移除同 ID 的前台通知，再投递降级通知，否则会被一起清掉。
                            container.notifications.postFallback(plan.notification, id)
                            stopSelf()
                            false
                        }
                    }
                    NotificationPlan.None -> {
                        container.notifications.cancel()
                        ServiceCompat.stopForeground(
                            this@LiveUpdateService,
                            ServiceCompat.STOP_FOREGROUND_REMOVE
                        )
                        foregroundStarted = false
                        foregroundNotificationId = 0
                        stopSelf()
                        false
                    }
                }
                // 普通待办通知刷新后，最后再投递即时待办，避免它被普通通知挤下岛。
                container.refreshInstantTodoNotification()
                // 顺带刷新桌面组件；CapsuleWidgets 内部按 20 秒节流，1 秒轮询不会重复重建。
                CapsuleWidgets.requestUpdate(applicationContext)
                if (!keepRunning) break
                // 进度条按分钟推进，文字也按分钟刷新；秒数由系统 chronometer 自行走动。
                // 上岛测试只有 2 分钟，缩短刷新间隔以便准时切换准备/进行中并自动收起。
                delay(
                    if (container.notifications.testActive()) {
                        TEST_REFRESH_INTERVAL_MILLIS
                    } else {
                        nextDelay
                    }
                )
            }
        }
    }

    private fun startForegroundSafely(
        notification: android.app.Notification,
        id: Int
    ): Boolean {
        // 待办岛与充电岛样式不同，切换时必须彻底换一条通知：
        // 直接在同一 ID 上更新会被系统丢弃，表现为充电功率岛消失。
        if (foregroundStarted && id != foregroundNotificationId) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
            if (foregroundNotificationId != 0) {
                NotificationManagerCompat.from(this).cancel(foregroundNotificationId)
            }
        }
        return runCatching {
            ServiceCompat.startForeground(
                this,
                id,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    0
                }
            )
            foregroundNotificationId = id
            true
        }.getOrDefault(false)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_REFRESH = "com.capsulepal.app.action.REFRESH_LIVE"
        const val ACTION_CANCEL = "com.capsulepal.app.action.CANCEL_LIVE"
        private const val REFRESH_INTERVAL_MILLIS = 60_000L
        private const val TEST_REFRESH_INTERVAL_MILLIS = 15_000L

        /** 当前存活的服务实例；非空表示刷新循环正在运行。 */
        @Volatile
        private var instance: LiveUpdateService? = null

        fun isRunning(): Boolean = instance != null

        fun refresh(context: Context) {
            // 服务已经在跑：直接唤醒循环，避免后台 startForegroundService 被拦截后
            // 只能投递一次、白白等满上一轮的延时。
            instance?.let {
                it.poke()
                return
            }
            val intent = Intent(context, LiveUpdateService::class.java).apply {
                action = ACTION_REFRESH
            }
            val started = runCatching { context.startForegroundService(intent) }.isSuccess
            if (!started) {
                // 后台启动前台服务受限时，直接投递一次当前上岛通知兜底。
                (context.applicationContext as? CapsuleApp)?.container?.refreshIslandDirect()
            }
        }

        fun cancel(context: Context) {
            val intent = Intent(context, LiveUpdateService::class.java).apply {
                action = ACTION_CANCEL
            }
            val started = runCatching { context.startService(intent) }.isSuccess
            if (!started) {
                (context.applicationContext as? CapsuleApp)?.container?.notifications?.cancel()
            }
        }
    }
}
