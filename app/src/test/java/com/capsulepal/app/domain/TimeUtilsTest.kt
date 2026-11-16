package com.capsulepal.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class TimeUtilsTest {

    @Test
    fun `countdown keeps two digits under an hour`() {
        assertEquals("00:00", TimeUtils.formatCountdown(0L))
        assertEquals("00:59", TimeUtils.formatCountdown(59_000L))
        assertEquals("01:00", TimeUtils.formatCountdown(60_000L))
        assertEquals("12:34", TimeUtils.formatCountdown(12 * 60_000L + 34_000L))
    }

    @Test
    fun `countdown adds hours when needed`() {
        assertEquals("1:00:00", TimeUtils.formatCountdown(3_600_000L))
        assertEquals("1:01:01", TimeUtils.formatCountdown(3_661_000L))
    }

    @Test
    fun `rounded minutes never drop to zero`() {
        assertEquals("1 分钟", TimeUtils.formatMinutesRounded(1L))
        assertEquals("1 分钟", TimeUtils.formatMinutesRounded(60_000L))
        assertEquals("2 分钟", TimeUtils.formatMinutesRounded(61_000L))
    }

    @Test
    fun `duration text switches by size`() {
        assertEquals("45 分钟", TimeUtils.formatDuration(45))
        assertEquals("1 小时", TimeUtils.formatDuration(60))
        assertEquals("1 小时 30 分", TimeUtils.formatDuration(90))
    }

    @Test
    fun `greeting changes across the day`() {
        assertEquals("早上好", TimeUtils.greeting(6))
        assertEquals("上午好", TimeUtils.greeting(10))
        assertEquals("中午好", TimeUtils.greeting(12))
        assertEquals("下午好", TimeUtils.greeting(16))
        assertEquals("晚上好", TimeUtils.greeting(20))
        assertEquals("夜深了", TimeUtils.greeting(2))
    }

    @Test
    fun `human date relative labels`() {
        val base = LocalDate.of(2026, 3, 2)
        assertEquals("今天", TimeUtils.humanDate(base, base))
        assertEquals("明天", TimeUtils.humanDate(base.plusDays(1), base))
        assertEquals("昨天", TimeUtils.humanDate(base.minusDays(1), base))
        assertEquals("周四", TimeUtils.humanDate(base.plusDays(3), base))
    }

    @Test
    fun `clock formatting`() {
        assertEquals("09:05", TimeUtils.formatClock(LocalTime.of(9, 5)))
    }
}
