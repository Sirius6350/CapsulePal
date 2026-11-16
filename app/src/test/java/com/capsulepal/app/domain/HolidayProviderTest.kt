package com.capsulepal.app.domain

import com.capsulepal.app.data.model.TemplateType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class HolidayProviderTest {

    @Test
    fun `new year 2026 is a holiday`() {
        val date = LocalDate.of(2026, 1, 1)
        assertEquals("元旦", HolidayProvider.holidayName(date))
        assertTrue(HolidayProvider.isRestDay(date))
        assertEquals(TemplateType.WEEKEND, HolidayProvider.templateType(date))
    }

    @Test
    fun `spring festival 2026 covers the official range`() {
        assertEquals("春节", HolidayProvider.holidayName(LocalDate.of(2026, 2, 15)))
        assertEquals("春节", HolidayProvider.holidayName(LocalDate.of(2026, 2, 23)))
        assertNull(HolidayProvider.holidayName(LocalDate.of(2026, 2, 24)))
    }

    @Test
    fun `make up workday is not a rest day`() {
        val sunday = LocalDate.of(2026, 1, 4)
        assertTrue(sunday.dayOfWeek == java.time.DayOfWeek.SUNDAY)
        assertTrue(HolidayProvider.isMakeUpWorkday(sunday))
        assertFalse(HolidayProvider.isRestDay(sunday))
        assertEquals(TemplateType.WEEKDAY, HolidayProvider.templateType(sunday))
    }

    @Test
    fun `make up saturday in may is a workday`() {
        val saturday = LocalDate.of(2026, 5, 9)
        assertFalse(HolidayProvider.isRestDay(saturday))
    }

    @Test
    fun `plain weekend falls back to rest day`() {
        val saturday = LocalDate.of(2026, 6, 6)
        assertTrue(HolidayProvider.isRestDay(saturday))
        assertNull(HolidayProvider.holidayName(saturday))
    }

    @Test
    fun `next holiday skips the current one`() {
        val next = HolidayProvider.nextHoliday(LocalDate.of(2026, 3, 1))
        assertEquals("清明", next?.first)
        assertEquals(LocalDate.of(2026, 4, 4), next?.second)
    }

    @Test
    fun `upcoming holidays are sorted`() {
        val list = HolidayProvider.upcomingHolidays(LocalDate.of(2026, 8, 1), 2)
        assertEquals(listOf("中秋", "国庆"), list.map { it.first })
    }

    @Test
    fun `table version is bumped for the 2027 official data`() {
        assertEquals("2036.2", HolidayProvider.TABLE_VERSION)
    }

    @Test
    fun `spring festival 2027 matches the official nine day range`() {
        assertEquals("除夕", HolidayProvider.holidayName(LocalDate.of(2027, 2, 5)))
        assertEquals("春节", HolidayProvider.holidayName(LocalDate.of(2027, 2, 6)))
        assertEquals("春节", HolidayProvider.holidayName(LocalDate.of(2027, 2, 13)))
        assertNull(HolidayProvider.holidayName(LocalDate.of(2027, 2, 14)))
        assertFalse(HolidayProvider.isEstimated(LocalDate.of(2027, 2, 6)))
        assertFalse(HolidayProvider.isRestDay(LocalDate.of(2027, 2, 14)))
    }

    @Test
    fun `2027 holidays match the official schedule`() {
        assertEquals("元旦", HolidayProvider.holidayName(LocalDate.of(2027, 1, 3)))
        assertNull(HolidayProvider.holidayName(LocalDate.of(2027, 1, 4)))
        assertEquals("劳动节", HolidayProvider.holidayName(LocalDate.of(2027, 5, 5)))
        assertNull(HolidayProvider.holidayName(LocalDate.of(2027, 5, 6)))
        assertEquals("端午", HolidayProvider.holidayName(LocalDate.of(2027, 6, 9)))
        assertEquals("中秋", HolidayProvider.holidayName(LocalDate.of(2027, 9, 15)))
        assertEquals("国庆", HolidayProvider.holidayName(LocalDate.of(2027, 10, 7)))
        assertNull(HolidayProvider.holidayName(LocalDate.of(2027, 10, 8)))
    }

    @Test
    fun `2027 make up workdays are workdays`() {
        listOf(
            LocalDate.of(2027, 1, 31),
            LocalDate.of(2027, 2, 14),
            LocalDate.of(2027, 5, 8),
            LocalDate.of(2027, 9, 26),
            LocalDate.of(2027, 10, 9)
        ).forEach { date ->
            assertTrue(HolidayProvider.isMakeUpWorkday(date))
            assertFalse(HolidayProvider.isRestDay(date))
        }
    }

    @Test
    fun `estimated spring festival 2036 starts at the lunar new year`() {
        assertEquals("除夕", HolidayProvider.holidayName(LocalDate.of(2036, 1, 27)))
        assertEquals("春节", HolidayProvider.holidayName(LocalDate.of(2036, 1, 28)))
    }

    @Test
    fun `estimated new year 2028 merges the adjacent weekend`() {
        // 2028-01-01 是周六，连休到周日。
        assertEquals("元旦", HolidayProvider.holidayName(LocalDate.of(2028, 1, 1)))
        assertEquals("元旦", HolidayProvider.holidayName(LocalDate.of(2028, 1, 2)))
        assertNull(HolidayProvider.holidayName(LocalDate.of(2028, 1, 3)))
    }

    @Test
    fun `qingming 2027 matches the official range`() {
        assertEquals("清明", HolidayProvider.holidayName(LocalDate.of(2027, 4, 3)))
        assertEquals("清明", HolidayProvider.holidayName(LocalDate.of(2027, 4, 5)))
        assertNull(HolidayProvider.holidayName(LocalDate.of(2027, 4, 6)))
        assertFalse(HolidayProvider.isEstimated(LocalDate.of(2027, 4, 5)))
    }

    @Test
    fun `estimated holidays handle lunar dates across years`() {
        assertEquals("端午", HolidayProvider.holidayName(LocalDate.of(2032, 6, 12)))
        assertEquals("中秋", HolidayProvider.holidayName(LocalDate.of(2034, 9, 27)))
        assertNull(HolidayProvider.holidayName(LocalDate.of(2034, 9, 28)))
    }

    @Test
    fun `estimated mid autumn and national day merge when overlapping`() {
        // 2031 中秋落在 10 月 1 日，与国庆连成一段。
        val name = HolidayProvider.holidayName(LocalDate.of(2031, 10, 1)).orEmpty()
        assertTrue(name.contains("中秋"))
        assertTrue(name.contains("国庆"))
        // 2036 中秋落在 10 月 4 日，与国庆连休。
        val later = HolidayProvider.holidayName(LocalDate.of(2036, 10, 4)).orEmpty()
        assertTrue(later.contains("中秋"))
        assertTrue(later.contains("国庆"))
    }

    @Test
    fun `official years are not marked as estimated`() {
        assertFalse(HolidayProvider.isEstimated(LocalDate.of(2026, 1, 1)))
        assertFalse(HolidayProvider.isEstimated(LocalDate.of(2027, 10, 1)))
        assertTrue(HolidayProvider.isEstimated(LocalDate.of(2030, 1, 1)))
    }

    @Test
    fun `every estimated year has the main statutory holidays`() {
        for (year in 2028..2036) {
            assertEquals("元旦", HolidayProvider.holidayName(LocalDate.of(year, 1, 1)))
            assertTrue(HolidayProvider.holidayName(LocalDate.of(year, 5, 1)) != null)
            assertTrue(HolidayProvider.holidayName(LocalDate.of(year, 10, 1)) != null)
            assertTrue(HolidayProvider.isEstimated(LocalDate.of(year, 1, 1)))
        }
    }
}
