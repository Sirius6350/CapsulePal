package com.capsulepal.app.domain

import com.capsulepal.app.data.model.Task

/** 待办的闹钟配额分档：今天剩下的、未来一周内即将开始的。 */
data class ReminderBuckets(
    val active: List<Task>,
    val preactive: List<Task>
)

object ReminderWindows {

    /** 预活跃档向前看的天数；更远的待办进入沉睡，等每日唤醒再重新纳入。 */
    const val PREACTIVE_HORIZON_DAYS = 7L

    /**
     * 按开始时间把开放待办分成三档：
     * - 活跃：今天结束前开始，排完整的准备 / 开始 / 结束闹钟；
     * - 预活跃：未来一周内开始，只排开始闹钟；
     * - 沉睡：更远的待办不返回，由每日唤醒重新评估。
     */
    fun classify(
        tasks: List<Task>,
        nowMillis: Long,
        endOfTodayMillis: Long,
        horizonMillis: Long
    ): ReminderBuckets {
        val horizonEnd = nowMillis + horizonMillis
        val candidates = tasks.asSequence()
            .filter { it.isOpen && it.endMillis > nowMillis && it.startMillis <= horizonEnd }
            .sortedBy { it.startMillis }
            .toList()
        val (active, preactive) = candidates.partition { it.startMillis < endOfTodayMillis }
        return ReminderBuckets(active = active, preactive = preactive)
    }
}
