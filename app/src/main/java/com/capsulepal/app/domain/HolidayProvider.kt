package com.capsulepal.app.domain

import com.capsulepal.app.data.model.TemplateType
import com.nlf.calendar.Lunar
import com.nlf.calendar.Solar
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * 内置节假日表：随 App 版本更新，不联网。
 * 2025-2027 数据来自国务院办公厅放假安排通知；
 * 2028-2036 用农历与节气推算法定节日，并自动并入相邻周末形成连休。
 * 未来年份的调休上班日无法预知，因此不做虚构，界面上标记为“预估”。
 */
object HolidayProvider {

    const val TABLE_VERSION = "2036.2"

    private data class Holiday(
        val name: String,
        val start: LocalDate,
        val endInclusive: LocalDate,
        val estimated: Boolean = false
    )

    private val officialHolidays: List<Holiday> = listOf(
        // 2025
        Holiday("元旦", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 1)),
        Holiday("除夕", LocalDate.of(2025, 1, 28), LocalDate.of(2025, 1, 28)),
        Holiday("春节", LocalDate.of(2025, 1, 28), LocalDate.of(2025, 2, 4)),
        Holiday("清明", LocalDate.of(2025, 4, 4), LocalDate.of(2025, 4, 6)),
        Holiday("劳动节", LocalDate.of(2025, 5, 1), LocalDate.of(2025, 5, 5)),
        Holiday("端午", LocalDate.of(2025, 5, 31), LocalDate.of(2025, 6, 2)),
        Holiday("国庆·中秋", LocalDate.of(2025, 10, 1), LocalDate.of(2025, 10, 8)),
        // 2026（国办发明电〔2025〕7号）
        Holiday("元旦", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 3)),
        Holiday("除夕", LocalDate.of(2026, 2, 16), LocalDate.of(2026, 2, 16)),
        Holiday("春节", LocalDate.of(2026, 2, 15), LocalDate.of(2026, 2, 23)),
        Holiday("清明", LocalDate.of(2026, 4, 4), LocalDate.of(2026, 4, 6)),
        Holiday("劳动节", LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 5)),
        Holiday("端午", LocalDate.of(2026, 6, 19), LocalDate.of(2026, 6, 21)),
        Holiday("中秋", LocalDate.of(2026, 9, 25), LocalDate.of(2026, 9, 27)),
        Holiday("国庆", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 7)),
        // 2027（国办发明电〔2026〕10号）
        Holiday("元旦", LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 3)),
        Holiday("除夕", LocalDate.of(2027, 2, 5), LocalDate.of(2027, 2, 5)),
        Holiday("春节", LocalDate.of(2027, 2, 5), LocalDate.of(2027, 2, 13)),
        Holiday("清明", LocalDate.of(2027, 4, 3), LocalDate.of(2027, 4, 5)),
        Holiday("劳动节", LocalDate.of(2027, 5, 1), LocalDate.of(2027, 5, 5)),
        Holiday("端午", LocalDate.of(2027, 6, 9), LocalDate.of(2027, 6, 9)),
        Holiday("中秋", LocalDate.of(2027, 9, 15), LocalDate.of(2027, 9, 15)),
        Holiday("国庆", LocalDate.of(2027, 10, 1), LocalDate.of(2027, 10, 7))
    )

    private val officialMakeUpWorkdays: Set<LocalDate> = setOf(
        // 2025
        LocalDate.of(2025, 1, 26),
        LocalDate.of(2025, 2, 8),
        LocalDate.of(2025, 4, 27),
        LocalDate.of(2025, 9, 28),
        LocalDate.of(2025, 10, 11),
        // 2026
        LocalDate.of(2026, 1, 4),
        LocalDate.of(2026, 2, 14),
        LocalDate.of(2026, 2, 28),
        LocalDate.of(2026, 5, 9),
        LocalDate.of(2026, 9, 20),
        LocalDate.of(2026, 10, 10),
        // 2027
        LocalDate.of(2027, 1, 31),
        LocalDate.of(2027, 2, 14),
        LocalDate.of(2027, 5, 8),
        LocalDate.of(2027, 9, 26),
        LocalDate.of(2027, 10, 9)
    )

    private val estimatedHolidays: List<Holiday> = buildEstimatedHolidays()

    private val holidays: List<Holiday> = officialHolidays + estimatedHolidays

    private val makeUpWorkdays: Set<LocalDate> = officialMakeUpWorkdays

    fun holidayName(date: LocalDate): String? =
        holidays.firstOrNull { date >= it.start && date <= it.endInclusive }?.name

    fun isHoliday(date: LocalDate): Boolean = holidayName(date) != null

    /** 该日期是否来自 2028 及以后的推算数据。 */
    fun isEstimated(date: LocalDate): Boolean =
        holidays.firstOrNull { date >= it.start && date <= it.endInclusive }?.estimated == true

    fun isMakeUpWorkday(date: LocalDate): Boolean = date in makeUpWorkdays

    fun isRestDay(date: LocalDate): Boolean {
        if (isHoliday(date)) return true
        if (isMakeUpWorkday(date)) return false
        return date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY
    }

    fun isWorkday(date: LocalDate): Boolean = !isRestDay(date)

    fun templateType(date: LocalDate): TemplateType =
        if (isRestDay(date)) TemplateType.WEEKEND else TemplateType.WEEKDAY

    fun nextHoliday(from: LocalDate): Pair<String, LocalDate>? {
        val next = holidays
            .filter { it.start > from }
            .minByOrNull { it.start }
            ?: return null
        return next.name to next.start
    }

    fun upcomingHolidays(from: LocalDate, limit: Int = 3): List<Pair<String, LocalDate>> =
        holidays
            .filter { it.start > from }
            .sortedBy { it.start }
            .take(limit)
            .map { it.name to it.start }

    // ---------------------------------------------------------------------
    // 2028-2036 推算
    // ---------------------------------------------------------------------

    private fun buildEstimatedHolidays(): List<Holiday> {
        val eveMarkers = mutableListOf<Holiday>()
        val ranges = mutableListOf<Holiday>()

        for (year in 2028..2036) {
            ranges += range("元旦", LocalDate.of(year, 1, 1), LocalDate.of(year, 1, 1))

            val springFestival = lunarDate(year, 1, 1)
            val newYearsEve = springFestival.minusDays(1)
            eveMarkers += Holiday("除夕", newYearsEve, newYearsEve, estimated = true)
            // 法定假期：除夕至正月初三。
            ranges += range("春节", newYearsEve, lunarDate(year, 1, 3))

            val qingming = qingmingDate(year)
            ranges += range("清明", qingming, qingming)

            ranges += range("劳动节", LocalDate.of(year, 5, 1), LocalDate.of(year, 5, 2))

            val dragonBoat = lunarDate(year, 5, 5)
            ranges += range("端午", dragonBoat, dragonBoat)

            val midAutumn = lunarDate(year, 8, 15)
            ranges += range("中秋", midAutumn, midAutumn)

            ranges += range("国庆", LocalDate.of(year, 10, 1), LocalDate.of(year, 10, 3))
        }

        return eveMarkers + mergeOverlapping(ranges)
    }

    /** 法定日 + 相邻周末自动连休；只向相邻的周六周日扩展，不虚构调休。 */
    private fun range(name: String, coreStart: LocalDate, coreEnd: LocalDate): Holiday {
        var start = coreStart
        var end = coreEnd
        when (start.dayOfWeek) {
            DayOfWeek.MONDAY -> start = start.minusDays(2)
            DayOfWeek.SUNDAY -> start = start.minusDays(1)
            else -> Unit
        }
        when (end.dayOfWeek) {
            DayOfWeek.FRIDAY -> end = end.plusDays(2)
            DayOfWeek.SATURDAY -> end = end.plusDays(1)
            else -> Unit
        }
        return Holiday(name, start, end, estimated = true)
    }

    /** 中秋与国庆等相邻假期合并成一段连续休息日。 */
    private fun mergeOverlapping(ranges: List<Holiday>): List<Holiday> {
        val merged = mutableListOf<Holiday>()
        ranges.sortedBy { it.start }.forEach { holiday ->
            val last = merged.lastOrNull()
            if (last != null && holiday.start <= last.endInclusive.plusDays(1)) {
                val names = (last.name.split("·") + holiday.name.split("·"))
                    .distinct()
                    .joinToString("·")
                merged[merged.lastIndex] = Holiday(
                    name = names,
                    start = minOf(last.start, holiday.start),
                    endInclusive = maxOf(last.endInclusive, holiday.endInclusive),
                    estimated = true
                )
            } else {
                merged += holiday
            }
        }
        return merged
    }

    private fun lunarDate(year: Int, month: Int, day: Int): LocalDate {
        val solar = Lunar.fromYmd(year, month, day).solar
        return LocalDate.of(solar.year, solar.month, solar.day)
    }

    private fun qingmingDate(year: Int): LocalDate {
        for (day in 1..10) {
            val lunar = Solar.fromYmd(year, 4, day).lunar
            if (lunar.jieQi == "清明") {
                return LocalDate.of(year, 4, day)
            }
        }
        return LocalDate.of(year, 4, 5)
    }
}
