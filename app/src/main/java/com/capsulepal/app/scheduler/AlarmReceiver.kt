package com.capsulepal.app.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import com.capsulepal.app.CapsuleApp
import com.capsulepal.app.data.model.AlarmPoint
import com.capsulepal.app.service.FloatingKeepAliveService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? CapsuleApp ?: return
        when (intent.action) {
            AlarmScheduler.ACTION_TASK_ALARM -> {
                val taskId = intent.getLongExtra(AlarmScheduler.EXTRA_TASK_ID, -1L)
                val point = runCatching {
                    AlarmPoint.valueOf(intent.getStringExtra(AlarmScheduler.EXTRA_POINT).orEmpty())
                }.getOrNull()
                if (taskId <= 0L || point == null) return

                // 这里同步取时间：它是系统真正投递闹钟的时刻；
                // 协程里再取会把“CPU 睡着导致的处理延迟”误记成“系统闹钟晚到”。
                val receivedAt = System.currentTimeMillis()
                runAsync(context, WAKE_LOCK_TIMEOUT_MILLIS) {
                    app.container.handleAlarm(taskId, point, receivedAt)
                }
            }
            AlarmScheduler.ACTION_DAILY_WAKE -> {
                runAsync(context, WAKE_LOCK_TIMEOUT_MILLIS) {
                    // rescheduleAll 会顺带排下一天 00:05 的唤醒。
                    app.container.rescheduleAll()
                }
            }
            AlarmScheduler.ACTION_OVERLAY_TICK -> {
                runAsync(context, WAKE_LOCK_TIMEOUT_MILLIS) {
                    val settings = app.container.settingsRepository.settings.first()
                    if (settings.keepAliveEnabled) {
                        FloatingKeepAliveService.tick(context)
                    } else {
                        app.container.scheduler.cancelOffsetTick()
                    }
                }
            }
            AlarmScheduler.ACTION_CALENDAR_TICK,
            AlarmScheduler.ACTION_CALENDAR_EVENT -> {
                runAsync(context, WAKE_LOCK_TIMEOUT_MILLIS) {
                    // checkCalendarSoon 内部会按最新规则重排下一次精确闹钟或 15 分钟心跳。
                    app.container.checkCalendarSoon()
                }
            }
        }
    }

    /**
     * goAsync 只保证进程不被回收，不保证 CPU 不休眠。
     * 闹钟响过之后系统会立刻释放唤醒锁，如果不自己持锁，
     * 协程可能等到下一次系统唤醒才真正执行，通知就晚了。
     */
    private fun runAsync(
        context: Context,
        timeoutMillis: Long,
        block: suspend () -> Unit
    ) {
        val lock = runCatching {
            context.getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                ?.apply {
                    setReferenceCounted(false)
                    acquire(timeoutMillis)
                }
        }.getOrNull()
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                block()
            } finally {
                runCatching { if (lock?.isHeld == true) lock.release() }
                pending.finish()
            }
        }
    }

    companion object {
        private const val WAKE_LOCK_TAG = "CapsulePal:alarm"
        private const val WAKE_LOCK_TIMEOUT_MILLIS = 60_000L
    }
}
