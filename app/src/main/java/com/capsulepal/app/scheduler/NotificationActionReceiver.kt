package com.capsulepal.app.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.capsulepal.app.CapsuleApp
import com.capsulepal.app.data.model.TaskCategory
import com.capsulepal.app.data.model.TaskDraft
import com.capsulepal.app.data.model.TaskPhase
import com.capsulepal.app.data.model.TaskStatus
import com.capsulepal.app.notifier.NotificationActions
import com.capsulepal.app.service.LiveUpdateService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? CapsuleApp ?: return

        // 上岛测试卡片的完成/跳过/收到：只收起通知，不碰待办与成长。
        if (intent.action == NotificationActions.ACTION_TEST) {
            app.container.notifications.cancelTest()
            context.stopService(Intent(context, LiveUpdateService::class.java))
            app.container.notifications.cancel()
            return
        }

        // 充电岛“收到”：本次充电不再上岛，下次插电重新出现。
        if (intent.action == NotificationActions.ACTION_CHARGE_ACK) {
            val sessionId = intent.getLongExtra(NotificationActions.EXTRA_SESSION_ID, 0L)
            app.container.notifications.acknowledgeCharging(sessionId)
            context.stopService(Intent(context, LiveUpdateService::class.java))
            app.container.notifications.cancel()
            return
        }

        // 主动提醒：知道了只收起这次提醒，不影响待办后续正常上岛。
        if (intent.action == NotificationActions.ACTION_REMINDER_DISMISS) {
            app.container.notifications.cancelReminder()
            app.container.dismissSuggestion()
            return
        }

        // 用户划掉了双重提醒的普通通知：同一阶段不再补发。
        if (intent.action == NotificationActions.ACTION_DUAL_DISMISS) {
            app.container.notifications.markDualReminderDismissed()
            return
        }

        // 即时待办“完成”：清掉内存态并撤回独立通知。
        if (intent.action == NotificationActions.ACTION_COMPLETE_INSTANT) {
            app.container.completeInstantTodo()
            return
        }

        // 主动提醒：开始准备 / 加入日程
        if (intent.action == NotificationActions.ACTION_REMINDER_ACCEPT) {
            val pending = goAsync()
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    val taskId = intent.getLongExtra(NotificationActions.EXTRA_TASK_ID, -1L)
                    if (taskId > 0L) {
                        app.container.taskRepository.task(taskId)?.let { task ->
                            if (task.status == TaskStatus.IDLE) {
                                app.container.startTaskNow(task, TaskPhase.PREPARE)
                            }
                        }
                    } else {
                        val title = intent.getStringExtra(NotificationActions.EXTRA_REMINDER_TITLE).orEmpty()
                        val startMillis = intent.getLongExtra(NotificationActions.EXTRA_REMINDER_START, 0L)
                        val duration = intent.getIntExtra(NotificationActions.EXTRA_REMINDER_DURATION, 30)
                        if (title.isNotBlank() && startMillis > 0L) {
                            val dateTime = Instant.ofEpochMilli(startMillis)
                                .atZone(ZoneId.systemDefault())
                            app.container.saveTask(
                                TaskDraft(
                                    title = title,
                                    date = dateTime.toLocalDate(),
                                    startTime = dateTime.toLocalTime().withSecond(0).withNano(0),
                                    durationMinutes = duration.coerceIn(15, 180),
                                    prepareMinutes = 10,
                                    category = TaskCategory.MEETING,
                                    note = "来自系统日历"
                                )
                            )
                        }
                    }
                    app.container.dismissSuggestion()
                    app.container.notifications.cancelReminder()
                } finally {
                    pending.finish()
                }
            }
            return
        }

        val taskId = intent.getLongExtra(NotificationActions.EXTRA_TASK_ID, -1L)
        if (taskId <= 0L) return

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (intent.action == NotificationActions.ACTION_ACK) {
                    app.container.acknowledgeTask(taskId)
                    context.stopService(Intent(context, LiveUpdateService::class.java))
                    app.container.notifications.cancel()
                    return@launch
                }
                val task = app.container.taskRepository.task(taskId) ?: return@launch
                when (intent.action) {
                    NotificationActions.ACTION_COMPLETE -> app.container.completeTask(task)
                    NotificationActions.ACTION_SKIP -> app.container.skipTask(task)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
