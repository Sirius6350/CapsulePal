package com.capsulepal.app.notifier

import androidx.annotation.DrawableRes
import com.capsulepal.app.R
import com.capsulepal.app.data.model.ActiveTask
import com.capsulepal.app.data.model.TaskCategory
import com.capsulepal.app.data.model.TaskKind
import com.capsulepal.app.data.model.TaskPhase
import com.capsulepal.app.data.model.TimelineState
import com.capsulepal.app.data.model.iconRes
import com.capsulepal.app.domain.TimeUtils
import com.capsulepal.app.monitor.BatteryCalc
import com.capsulepal.app.monitor.MonitorState

enum class NotificationPhase { PREPARE, IN_PROGRESS }

enum class IslandKind { TASK, CHARGING }

data class TaskNotificationContent(
    val phase: NotificationPhase,
    @DrawableRes val iconRes: Int,
    val title: String,
    val text: String,
    val bigText: String,
    val progress: Int,
    val max: Int,
    val taskId: Long?,
    val taskTitle: String,
    val note: String = "",
    val test: Boolean = false,
    val kind: IslandKind = IslandKind.TASK,
    /** 胶囊右侧的短文本（≤7 字符），充电时显示功率。 */
    val shortText: String = "",
    /** 充电会话 id：点“收到”只收起本次充电。 */
    val sessionId: Long = 0L
)

object NotificationContentFactory {

    fun fromTimeline(
        state: TimelineState,
        nowMillis: Long,
        charging: MonitorState? = null
    ): TaskNotificationContent? {
        // 上岛只由“已进入准备窗口 / 已开始”驱动。
        // 未到用户设置的准备时间时，即使离开始很近也不提前上岛。
        state.active?.let { return fromActive(it, nowMillis, charging) }
        return null
    }

    fun fromActive(
        active: ActiveTask,
        nowMillis: Long,
        charging: MonitorState? = null
    ): TaskNotificationContent {
        val remaining = active.remainingMillis(nowMillis)
        val total = (active.windowEndMillis - active.windowStartMillis).coerceAtLeast(1L)
        val progress = (nowMillis - active.windowStartMillis).coerceIn(0L, total)

        // 充电时胶囊左侧仍是待办名，右侧换成功率；卡片第三行插入充电摘要。
        val liveCharging = charging?.takeIf { it.reading.charging }
        val chargingLine = liveCharging?.let { chargingSummary(it, nowMillis) }
        val powerText = liveCharging?.chargePowerW
            ?.let { BatteryCalc.formatPowerCompact(it) }
            .orEmpty()
        val note = chargingLine ?: active.task.note
        val chargingSuffix = chargingLine?.let { "\n$it" }.orEmpty()

        // 统一文案：待办名 + 开始时间 + 剩余 / 已进行分钟。
        val clock = TimeUtils.formatClock(active.task.startTime)
        val marker = when {
            active.task.planId != 0L -> "计划 · "
            active.task.kind == TaskKind.SCHEDULE -> "日程 · "
            else -> ""
        }
        return when (active.phase) {
            TaskPhase.PREPARE -> TaskNotificationContent(
                phase = NotificationPhase.PREPARE,
                iconRes = active.task.category.iconRes(),
                title = marker + active.task.shortTitle.ifBlank { active.task.title },
                text = "$clock 开始 · 还有 ${TimeUtils.formatMinutesRounded(remaining)}",
                bigText = ("「${active.task.title}」$clock 开始，还有 ${
                    TimeUtils.formatMinutesRounded(remaining)
                }。不着急，慢慢来。").withNote(active.task.note) + chargingSuffix,
                progress = progress.toInt().coerceIn(0, total.toInt()),
                max = total.toInt(),
                taskId = active.task.id,
                taskTitle = active.task.title,
                note = note,
                shortText = powerText
            )
            TaskPhase.IN_PROGRESS -> {
                val elapsed = (nowMillis - active.windowStartMillis).coerceIn(0L, total)
                TaskNotificationContent(
                    phase = NotificationPhase.IN_PROGRESS,
                    iconRes = active.task.category.iconRes(),
                    title = marker + active.task.shortTitle.ifBlank { active.task.title },
                    text = "进行中 · 已进行 ${
                        TimeUtils.formatMinutesRounded(elapsed)
                    } · 还剩 ${TimeUtils.formatMinutesRounded(remaining)}",
                    bigText = ("正在陪「${active.task.title}」，$clock 开始，已经进行 ${
                        TimeUtils.formatMinutesRounded(elapsed)
                    }，还剩 ${TimeUtils.formatMinutesRounded(remaining)}。" +
                        " 结束后我会轻轻收好，再衔接下一项。")
                        .withNote(active.task.note) + chargingSuffix,
                    progress = progress.toInt().coerceIn(0, total.toInt()),
                    max = total.toInt(),
                    taskId = active.task.id,
                    taskTitle = active.task.title,
                    note = note,
                    shortText = powerText
                )
            }
        }
    }

    /** 待办岛与充电同时出现时的卡片第三行：正在充电 · 已充 xx 分 · xx%/分 · xx℃。 */
    private fun chargingSummary(monitor: MonitorState, nowMillis: Long): String {
        val session = monitor.currentCharge
        val minutes = session?.durationMinutes(nowMillis) ?: 0
        val parts = mutableListOf("正在充电")
        parts += if (minutes <= 0) "刚插上电源" else "已充 ${BatteryCalc.formatDuration(minutes)}"
        session?.speedPctPerMin(nowMillis)?.let { parts += BatteryCalc.formatSpeed(it) }
        monitor.reading.tempC?.let { parts += BatteryCalc.formatTemp(it) }
        return parts.joinToString(" · ")
    }

    /**
     * 上岛测试：一条只存在于通知里的虚拟待办。
     * 从当前时刻开始进入 1 分钟准备窗口，随后进行 1 分钟，全程不写入数据库。
     */
    fun testContent(elapsedMillis: Long): TaskNotificationContent {
        val elapsed = elapsedMillis.coerceIn(0L, TEST_TOTAL_MILLIS)
        val progress = elapsed.toInt()
        return if (elapsed < TEST_PREPARE_MILLIS) {
            val remaining = TEST_PREPARE_MILLIS - elapsed
            TaskNotificationContent(
                phase = NotificationPhase.PREPARE,
                iconRes = TaskCategory.OTHER.iconRes(),
                title = TEST_TITLE,
                text = "距开始 ${TimeUtils.formatMinutesRounded(remaining)}",
                bigText = "「$TEST_TITLE」距开始还有 ${TimeUtils.formatMinutesRounded(remaining)}。" +
                    " 这是上岛测试，持续 1 分钟，点完成、跳过、收到都只会收起通知。",
                progress = progress,
                max = TEST_TOTAL_MILLIS.toInt(),
                taskId = null,
                taskTitle = TEST_TITLE,
                test = true
            )
        } else {
            val inProgress = elapsed - TEST_PREPARE_MILLIS
            TaskNotificationContent(
                phase = NotificationPhase.IN_PROGRESS,
                iconRes = TaskCategory.OTHER.iconRes(),
                title = TEST_TITLE,
                text = "进行中 · 已进行 ${TimeUtils.formatMinutesRounded(inProgress)}",
                bigText = "「$TEST_TITLE」正在进行，已经进行 ${TimeUtils.formatMinutesRounded(inProgress)}。" +
                    " 这是上岛测试，点完成、跳过、收到都只会收起通知。",
                progress = progress,
                max = TEST_TOTAL_MILLIS.toInt(),
                taskId = null,
                taskTitle = TEST_TITLE,
                test = true
            )
        }
    }

    const val TEST_TITLE = "测试"
    const val TEST_PREPARE_MILLIS = 60_000L
    const val TEST_DURATION_MILLIS = 60_000L
    const val TEST_TOTAL_MILLIS = TEST_PREPARE_MILLIS + TEST_DURATION_MILLIS

    /**
     * 充电上岛：胶囊左侧电池图标、右侧功率；卡片第一行充电状态，
     * 第二行本次时长与速度，第三行电池温度。
     */
    fun chargingContent(state: MonitorState, nowMillis: Long): TaskNotificationContent {
        val session = state.currentCharge
        val minutes = session?.durationMinutes(nowMillis) ?: 0
        val speed = session?.speedPctPerMin(nowMillis)
        val durationText = if (minutes <= 0) "刚插上电源" else "已充 ${BatteryCalc.formatDuration(minutes)}"
        val secondLine = if (speed != null) {
            "$durationText · ${BatteryCalc.formatSpeed(speed)}"
        } else {
            durationText
        }
        val tempText = state.reading.tempC?.let { BatteryCalc.formatTemp(it) }
        // 部分机型到达充电上限后会暂停充电但仍插着电，此时不假装“正在充电”。
        val title = if (state.reading.charging) "正在充电" else "已接电源"
        return TaskNotificationContent(
            phase = NotificationPhase.IN_PROGRESS,
            iconRes = R.drawable.ic_battery_charging,
            title = title,
            text = secondLine,
            bigText = secondLine + (tempText?.let { "\n电池温度 $it" } ?: ""),
            progress = (state.reading.levelPct ?: 0).coerceIn(0, 100),
            max = 100,
            taskId = null,
            taskTitle = title,
            note = tempText?.let { "电池温度 $it" } ?: "",
            kind = IslandKind.CHARGING,
            shortText = state.chargePowerW?.let { BatteryCalc.formatPowerCompact(it) } ?: "",
            sessionId = session?.id ?: 0L
        )
    }
}

private fun String.withNote(note: String): String =
    if (note.isBlank()) this else "$this\n备注：$note"
