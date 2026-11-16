package com.capsulepal.app.partner

import com.capsulepal.app.data.model.ActiveTask
import com.capsulepal.app.data.model.Task
import com.capsulepal.app.data.model.TaskPhase
import com.capsulepal.app.data.model.TaskStatus
import com.capsulepal.app.data.model.UpcomingTask
import com.capsulepal.app.domain.HolidayProvider
import com.capsulepal.app.domain.TimeUtils
import java.time.DayOfWeek
import java.time.LocalDate

enum class PartnerMood(val label: String) {
    IDLE("待机"),
    FOCUS("专注"),
    ALERT("提醒"),
    HAPPY("完成"),
    SLEEPY("打盹"),
    CHARGING("充电");

    companion object {
        fun forHour(hour: Int): PartnerMood = if (hour >= 23 || hour < 6) SLEEPY else IDLE
    }
}

data class PartnerState(
    val mood: PartnerMood,
    val headline: String,
    val message: String,
    val islandText: String
)

/** 用户刚刚跳过的待办，用于今日页气泡的短暂回执。 */
data class RecentSkip(
    val taskId: Long,
    val skippedAtMillis: Long
)

object PartnerCopy {

    fun greeting(name: String, hour: Int): String = when (hour) {
        in 5..8 -> "$name 已经醒了，今天也慢慢来。"
        in 9..11 -> "上午好，$name 在陪你。"
        in 12..13 -> "先好好吃饭，$name 等你回来。"
        in 14..17 -> "下午好，$name 把节奏放慢了。"
        in 18..22 -> "晚上好，$name 陪你收个尾。"
        else -> "夜深了，$name 有点困，也想让你早点休息。"
    }

    /**
     * 今日页顶部伙伴气泡。优先级：
     * 进行中 > 准备中 > 刚跳过 > 今天全部收尾 > 下一件 > 今天没安排（节假日 > 周末 > 普通）。
     */
    fun dayState(
        date: LocalDate,
        tasks: List<Task>,
        active: ActiveTask?,
        next: UpcomingTask?,
        nowMillis: Long,
        holidayName: String?,
        recentSkip: RecentSkip?
    ): PartnerState {
        if (active?.phase == TaskPhase.IN_PROGRESS) {
            return inProgress(active, nowMillis)
        }
        if (active?.phase == TaskPhase.PREPARE) {
            return preparing(active, nowMillis)
        }

        recentSkip?.let { skip ->
            val skipped = tasks.firstOrNull {
                it.id == skip.taskId && it.status == TaskStatus.SKIPPED
            }
            if (skipped != null) {
                val following = tasks
                    .filter { it.isOpen && it.startMillis > skipped.startMillis }
                    .minByOrNull { it.startMillis }
                // 有下一件时保留到它的准备窗口开始，没有就只停留一分钟。
                val windowEnd = following?.prepareStartMillis
                    ?: (skip.skippedAtMillis + 60_000L)
                if (nowMillis < windowEnd) {
                    return justSkipped(following)
                }
            }
        }

        if (tasks.isNotEmpty() && tasks.none { it.isOpen }) {
            val skippedCount = tasks.count { it.status == TaskStatus.SKIPPED }
            return if (skippedCount > 0) {
                PartnerState(
                    mood = PartnerMood.HAPPY,
                    headline = "今天收工",
                    message = "今天能完成的都完成了。跳过的 $skippedCount 件事，可以放到以后的安排中，" +
                        "由我帮你记着。",
                    islandText = "今天已经收尾"
                )
            } else {
                PartnerState(
                    mood = PartnerMood.HAPPY,
                    headline = "今天收工",
                    message = "今天任务圆满完成，放松一下吧。",
                    islandText = "今天任务圆满完成"
                )
            }
        }

        if (next != null) {
            return upcoming(next)
        }

        if (tasks.isEmpty()) {
            return emptyDay(date, holidayName)
        }

        // 有安排，但眼下既没有进行中的事，也没有可等待的下一件（例如旧任务还没结算）。
        return PartnerState(
            mood = PartnerMood.IDLE,
            headline = "空档",
            message = "现在没有正在进行的事，想提前准备下一件也可以。",
            islandText = "现在没有进行中的事"
        )
    }

    fun holidayBlessing(name: String): String = when (name) {
        "元旦" -> "新年的第一缕阳光归你"
        "除夕" -> "今晚不赶时间，只赶团圆"
        "春节" -> "愿新的一年顺顺当当，团圆常在"
        "清明" -> "适合想念，也适合好好照顾自己"
        "劳动节" -> "辛苦了，今天先好好休息"
        "端午" -> "记得吃个粽子，愿安康常伴"
        "中秋" -> "月亮和团圆都别错过"
        "国庆" -> "山河辽阔，好好享受这几天"
        "国庆·中秋" -> "家国同庆，月饼与假期都不要辜负"
        else -> "好好享受这一天"
    }

    fun upcomingHoliday(name: String, days: Long): String =
        if (days <= 1) "$name 就在明天" else "距$name 还有 $days 天"

    private fun inProgress(active: ActiveTask, nowMillis: Long): PartnerState {
        val remaining = active.remainingMillis(nowMillis)
        val countdown = TimeUtils.formatMinutesRounded(remaining)
        val message = if (active.task.durationMinutes >= 5 && remaining <= 2 * 60_000L) {
            "再坚持一会儿，还有 ${countdown}就结束了。"
        } else {
            "「${active.task.title}」进行中，还剩 $countdown，保持这个节奏。"
        }
        return PartnerState(
            mood = PartnerMood.FOCUS,
            headline = "正在进行",
            message = message,
            islandText = "${active.task.title} · 还剩 $countdown"
        )
    }

    private fun preparing(active: ActiveTask, nowMillis: Long): PartnerState {
        val countdown = TimeUtils.formatMinutesRounded(active.remainingMillis(nowMillis))
        return PartnerState(
            mood = PartnerMood.ALERT,
            headline = "准备窗口",
            message = "还有 ${countdown}就要开始「${active.task.title}」啦，记得提前做好准备。",
            islandText = "距「${active.task.title}」还有 $countdown"
        )
    }

    private fun justSkipped(following: Task?): PartnerState = if (following != null) {
        PartnerState(
            mood = PartnerMood.IDLE,
            headline = "先放一放",
            message = "这件先放一放，${TimeUtils.formatClock(following.startMillis)} 的" +
                "「${following.title}」见。",
            islandText = "已跳过这一件"
        )
    } else {
        PartnerState(
            mood = PartnerMood.IDLE,
            headline = "先放一放",
            message = "这件先放一放，今天先到这儿。",
            islandText = "已跳过这一件"
        )
    }

    private fun upcoming(next: UpcomingTask): PartnerState = PartnerState(
        mood = PartnerMood.IDLE,
        headline = "下一件事",
        message = "接下来的「${next.task.title}」，${TimeUtils.formatClock(next.task.startMillis)} 开始，" +
            "可以先做准备，也可以歇会儿先。",
        islandText = "距「${next.task.title}」还有 " +
            TimeUtils.formatMinutesRounded(next.untilStartMillis)
    )

    private fun emptyDay(date: LocalDate, holidayName: String?): PartnerState {
        if (holidayName != null) {
            return PartnerState(
                mood = PartnerMood.IDLE,
                headline = "假期",
                message = "今天是$holidayName，${holidayBlessing(holidayName)}，我先放个假，有事再叫我。",
                islandText = "今天是$holidayName"
            )
        }
        val isWeekend = date.dayOfWeek == DayOfWeek.SATURDAY ||
            date.dayOfWeek == DayOfWeek.SUNDAY
        if (isWeekend && !HolidayProvider.isMakeUpWorkday(date)) {
            val label = if (date.dayOfWeek == DayOfWeek.SATURDAY) "周六" else "周日"
            return PartnerState(
                mood = PartnerMood.IDLE,
                headline = "周末",
                message = "今天是$label，难得的美好时光。",
                islandText = "今天是$label"
            )
        }
        return PartnerState(
            mood = PartnerMood.IDLE,
            headline = "今天很空",
            message = "今天没安排，按自己的节奏来。",
            islandText = "今天还没有安排"
        )
    }
}
