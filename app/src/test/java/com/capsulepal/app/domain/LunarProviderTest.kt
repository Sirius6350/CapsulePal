package com.capsulepal.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LunarProviderTest {

    @Test
    fun `spring festival 2026 maps to lunar new year`() {
        val date = LocalDate.of(2026, 2, 17)
        assertEquals("正月", LunarProvider.lunarDayLabel(date))
        val full = LunarProvider.fullLabel(date)
        assertTrue(full.contains("正月"))
        assertTrue(full.contains("初一"))
        assertTrue(full.contains("马"))
    }

    @Test
    fun `lunar label prefers festivals`() {
        val label = LunarProvider.label(LocalDate.of(2026, 2, 17))
        assertTrue(label.contains("春节"))
    }

    @Test
    fun `mid autumn 2026 is recognised`() {
        val label = LunarProvider.label(LocalDate.of(2026, 9, 25))
        assertTrue(label.contains("中秋"))
    }

    @Test
    fun `solar term is available`() {
        val term = LunarProvider.solarTerm(LocalDate.of(2026, 12, 22))
        assertEquals("冬至", term)
    }

    @Test
    fun `year label carries zodiac`() {
        val year = LunarProvider.yearLabel(LocalDate.of(2026, 6, 1))
        assertTrue(year.contains("丙午"))
        assertTrue(year.contains("马"))
    }
}
