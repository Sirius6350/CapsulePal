package com.capsulepal.app.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.capsulepal.app.CapsuleApp
import com.capsulepal.app.data.model.AlarmPoint
import com.capsulepal.app.service.FloatingKeepAliveService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalTime

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

                val pending = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        app.container.handleAlarm(taskId, point)
                    } finally {
                        pending.finish()
                    }
                }
            }
            AlarmScheduler.ACTION_DAILY_WAKE -> {
                val pending = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        // rescheduleAll 会顺带排下一天 00:05 的唤醒。
                        app.container.rescheduleAll()
                    } finally {
                        pending.finish()
                    }
                }
            }
            AlarmScheduler.ACTION_OVERLAY_TICK -> {
                val pending = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        val settings = app.container.settingsRepository.settings.first()
                        if (settings.keepAliveEnabled) {
                            FloatingKeepAliveService.tick(context)
                        } else {
                            app.container.scheduler.cancelOffsetTick()
                        }
                    } finally {
                        pending.finish()
                    }
                }
            }
            AlarmScheduler.ACTION_CALENDAR_TICK,
            AlarmScheduler.ACTION_CALENDAR_EVENT -> {
                val pending = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        // checkCalendarSoon 内部会按最新规则重排下一次精确闹钟或 15 分钟心跳。
                        app.container.checkCalendarSoon()
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }
}
