package com.capsulepal.app.domain

import com.capsulepal.app.data.model.Plan
import com.capsulepal.app.data.model.RepeatRule
import com.capsulepal.app.data.model.Task
import com.capsulepal.app.data.model.TaskCategory
import com.capsulepal.app.data.model.TaskStatus
import com.capsulepal.app.data.model.displayCategory
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt

/** 计划进度：跳过计入分母，进度不会因为跳过而虚高。 */
data class PlanProgress(
    val completed: Int = 0,
    val skipped: Int = 0,
    val open: Int = 0,
    /** 周期计划的完整分母：已铺出的实例 + 未来到结束日应生成的实例。 */
    val total: Int = completed + skipped + open
) {
    val fraction: Float get() = if (total == 0) 0f else completed.toFloat() / total
    val percent: Int get() = (fraction * 100).roundToInt()
}

/**
 * [plan] 为周期计划时，分母按整个计划区间推算，不依赖重复实例是否已经铺出来，
 * 这样百分比不会随着滚动补齐未来实例而往回掉。
 */
fun planProgress(tasks: List<Task>, plan: Plan? = null): PlanProgress {
    val completed = tasks.count { it.status == TaskStatus.COMPLETED }
    val skipped = tasks.count { it.status == TaskStatus.SKIPPED }
    val open = tasks.count { it.isOpen }
    val end = plan?.takeIf { it.isPeriod }?.targetDate
    val total = if (end != null) {
        expectedPeriodTotal(tasks, end)
    } else {
        completed + skipped + open
    }
    return PlanProgress(
        completed = completed,
        skipped = skipped,
        open = open,
        total = maxOf(total, completed + skipped + open)
    )
}

/**
 * 周期计划的应做条数：按系列统计「已铺出的实例 + 从最后一条到结束日还应生成的实例」。
 * 单独待办每条算一个；连续 n 天在创建时已一次铺满，不再向后推算。
 */
private fun expectedPeriodTotal(tasks: List<Task>, planEnd: LocalDate): Int {
    if (tasks.isEmpty()) return 0
    var total = tasks.count { it.seriesId == 0L }
    tasks.filter { it.seriesId != 0L }
        .groupBy { it.seriesId }
        .values
        .forEach { rows ->
            total += rows.size
            val head = rows.minByOrNull { it.date } ?: return@forEach
            if (head.repeat == RepeatRule.ONCE || head.repeat == RepeatRule.CONSECUTIVE) {
                return@forEach
            }
            var cursor = rows.maxOf { it.date }.plusDays(1)
            var guard = 0
            while (!cursor.isAfter(planEnd) && guard < MAX_PROJECTED_DAYS) {
                val matches = when (head.repeat) {
                    RepeatRule.DAILY -> true
                    RepeatRule.WEEKLY -> cursor.dayOfWeek in head.repeatDays
                    else -> false
                }
                val rest = head.skipHolidays && HolidayProvider.isRestDay(cursor)
                if (matches && !rest) total++
                cursor = cursor.plusDays(1)
                guard++
            }
        }
    return total
}

/** 推算未来实例的安全上限，仅防御异常的超长计划。 */
private const val MAX_PROJECTED_DAYS = 3_660

/** 重复计划的进度：只记录完成次数，不算百分比。 */
data class RepeatPlanStats(
    val completed: Int = 0,
    val thisWeek: Int = 0,
    val streak: Int = 0
)

fun repeatPlanStats(tasks: List<Task>, today: LocalDate = LocalDate.now()): RepeatPlanStats {
    val done = tasks.filter { it.status == TaskStatus.COMPLETED }
    if (done.isEmpty()) return RepeatPlanStats()
    val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val thisWeek = done.count { !it.date.isBefore(weekStart) && !it.date.isAfter(today) }
    val doneDays = done.map { it.date }.toSet()
    // 连续天数从今天（或昨天）往回数；中间断一天就归零。
    var cursor = when {
        today in doneDays -> today
        today.minusDays(1) in doneDays -> today.minusDays(1)
        else -> return RepeatPlanStats(completed = done.size, thisWeek = thisWeek)
    }
    var streak = 0
    while (cursor in doneDays) {
        streak++
        cursor = cursor.minusDays(1)
    }
    return RepeatPlanStats(completed = done.size, thisWeek = thisWeek, streak = streak)
}

enum class SummaryRange(val label: String) { DAY("日"), WEEK("周"), MONTH("月"), YEAR("年") }

data class SummaryCategory(
    val category: TaskCategory,
    val label: String,
    val completed: Int,
    val skipped: Int,
    val open: Int
) {
    val total: Int get() = completed + skipped + open
    val fraction: Float get() = if (total == 0) 0f else completed.toFloat() / total
}

/** 趋势图上的一根柱子：一个时间段内的完成数与总数。 */
data class SummaryTrendPoint(
    val label: String,
    val completed: Int,
    val total: Int
)

data class SummaryStats(
    val range: SummaryRange,
    val anchor: LocalDate,
    val start: LocalDate,
    val end: LocalDate,
    val completed: Int = 0,
    val skipped: Int = 0,
    val open: Int = 0,
    val categories: List<SummaryCategory> = emptyList(),
    val trend: List<SummaryTrendPoint> = emptyList()
) {
    val total: Int get() = completed + skipped + open
    /** 完成率 = 完成 / 全部待办（含未完成与跳过）。 */
    val completionRate: Float get() = if (total == 0) 0f else completed.toFloat() / total
    val percent: Int get() = (completionRate * 100).roundToInt()
    val hasData: Boolean get() = total > 0
}

object SummaryCalculator {

    fun bounds(range: SummaryRange, anchor: LocalDate): Pair<LocalDate, LocalDate> = when (range) {
        SummaryRange.DAY -> anchor to anchor
        SummaryRange.WEEK -> {
            val start = anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            start to start.plusDays(6)
        }
        SummaryRange.MONTH -> {
            val month = YearMonth.from(anchor)
            month.atDay(1) to month.atEndOfMonth()
        }
        SummaryRange.YEAR -> {
            anchor.withDayOfYear(1) to anchor.withDayOfYear(anchor.lengthOfYear())
        }
    }

    fun shift(range: SummaryRange, anchor: LocalDate, delta: Long): LocalDate = when (range) {
        SummaryRange.DAY -> anchor.plusDays(delta)
        SummaryRange.WEEK -> anchor.plusWeeks(delta)
        SummaryRange.MONTH -> anchor.plusMonths(delta)
        SummaryRange.YEAR -> anchor.plusYears(delta)
    }

    fun summarize(tasks: List<Task>, range: SummaryRange, anchor: LocalDate): SummaryStats {
        val (start, end) = bounds(range, anchor)
        val inRange = tasks.filter { !it.date.isBefore(start) && !it.date.isAfter(end) }
        val categories = inRange
            .groupBy { it.category to it.displayCategory() }
            .map { (key, group) ->
                SummaryCategory(
                    category = key.first,
                    label = key.second,
                    completed = group.count { it.status == TaskStatus.COMPLETED },
                    skipped = group.count { it.status == TaskStatus.SKIPPED },
                    open = group.count { it.isOpen }
                )
            }
            .sortedWith(compareByDescending<SummaryCategory> { it.total }.thenBy { it.label })
        return SummaryStats(
            range = range,
            anchor = anchor,
            start = start,
            end = end,
            completed = inRange.count { it.status == TaskStatus.COMPLETED },
            skipped = inRange.count { it.status == TaskStatus.SKIPPED },
            open = inRange.count { it.isOpen },
            categories = categories,
            trend = trendFor(tasks, range, start, end)
        )
    }

    /**
     * 趋势分桶：日看最近 7 天，周看 7 天，月按 7 天一格，年按月。
     * 标签只保留能在一屏内看清的最短信息。
     */
    private fun trendFor(
        tasks: List<Task>,
        range: SummaryRange,
        start: LocalDate,
        end: LocalDate
    ): List<SummaryTrendPoint> = when (range) {
        SummaryRange.DAY -> (6 downTo 0).map { back ->
            val day = end.minusDays(back.toLong())
            trendPoint(tasks, day, day, "${day.monthValue}/${day.dayOfMonth}")
        }
        SummaryRange.WEEK -> (0..6).map { offset ->
            val day = start.plusDays(offset.toLong())
            trendPoint(tasks, day, day, weekdayLabel(day.dayOfWeek))
        }
        SummaryRange.MONTH -> buildList {
            var cursor = start
            while (!cursor.isAfter(end)) {
                val segmentEnd = minOf(cursor.plusDays(6), end)
                val label = if (cursor == segmentEnd) {
                    "${cursor.dayOfMonth}"
                } else {
                    "${cursor.dayOfMonth}-${segmentEnd.dayOfMonth}"
                }
                add(trendPoint(tasks, cursor, segmentEnd, label))
                cursor = segmentEnd.plusDays(1)
            }
        }
        SummaryRange.YEAR -> (1..12).map { month ->
            val monthStart = start.withMonth(month).withDayOfMonth(1)
            val monthEnd = YearMonth.from(monthStart).atEndOfMonth()
            trendPoint(tasks, monthStart, monthEnd, "$month")
        }
    }

    private fun trendPoint(
        tasks: List<Task>,
        from: LocalDate,
        to: LocalDate,
        label: String
    ): SummaryTrendPoint {
        val bucket = tasks.filter { !it.date.isBefore(from) && !it.date.isAfter(to) }
        return SummaryTrendPoint(
            label = label,
            completed = bucket.count { it.status == TaskStatus.COMPLETED },
            total = bucket.size
        )
    }

    private fun weekdayLabel(dayOfWeek: DayOfWeek): String = when (dayOfWeek) {
        DayOfWeek.MONDAY -> "一"
        DayOfWeek.TUESDAY -> "二"
        DayOfWeek.WEDNESDAY -> "三"
        DayOfWeek.THURSDAY -> "四"
        DayOfWeek.FRIDAY -> "五"
        DayOfWeek.SATURDAY -> "六"
        DayOfWeek.SUNDAY -> "日"
    }
}
