package com.capsulepal.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GrowthRulesTest {

    @Test
    fun `first completions give full xp`() {
        assertEquals(10, GrowthRules.xpForCompletion(0, 4))
        assertEquals(10, GrowthRules.xpForCompletion(3, 4))
    }

    @Test
    fun `later completions decrease but never reach zero`() {
        assertEquals(8, GrowthRules.xpForCompletion(4, 4))
        assertEquals(6, GrowthRules.xpForCompletion(5, 4))
        assertEquals(4, GrowthRules.xpForCompletion(6, 4))
        assertEquals(2, GrowthRules.xpForCompletion(7, 4))
        assertEquals(1, GrowthRules.xpForCompletion(8, 4))
        assertEquals(1, GrowthRules.xpForCompletion(20, 4))
    }

    @Test
    fun `full xp count is clamped between three and five`() {
        assertEquals(10, GrowthRules.xpForCompletion(4, 9))
        assertEquals(5, GrowthRules.fullXpRemaining(0, 99))
    }

    @Test
    fun `level thresholds grow`() {
        assertEquals(0, GrowthRules.thresholdForLevel(1))
        assertEquals(60, GrowthRules.thresholdForLevel(2))
        assertEquals(150, GrowthRules.thresholdForLevel(3))
        assertEquals(270, GrowthRules.thresholdForLevel(4))
        assertEquals(420, GrowthRules.thresholdForLevel(5))
    }

    @Test
    fun `level is derived from total xp`() {
        assertEquals(1, GrowthRules.levelForXp(0))
        assertEquals(1, GrowthRules.levelForXp(59))
        assertEquals(2, GrowthRules.levelForXp(60))
        assertEquals(2, GrowthRules.levelForXp(149))
        assertEquals(3, GrowthRules.levelForXp(150))
    }

    @Test
    fun `level info reports progress inside the level`() {
        val info = GrowthRules.levelInfo(150)
        assertEquals(3, info.level)
        assertEquals("默契", info.title)
        assertEquals(0, info.xpIntoLevel)
        assertEquals(120, info.xpForNextLevel)
        assertEquals(0f, info.progress, 0.001f)
    }

    @Test
    fun `max level has no next threshold`() {
        val info = GrowthRules.levelInfo(Int.MAX_VALUE)
        assertEquals(GrowthRules.MAX_LEVEL, info.level)
        assertTrue(info.isMax)
        assertEquals(1f, info.progress, 0.001f)
    }

    @Test
    fun `negative xp is treated as zero`() {
        val info = GrowthRules.levelInfo(-50)
        assertEquals(1, info.level)
        assertEquals(0, info.xpIntoLevel)
        assertFalse(info.isMax)
    }

    @Test
    fun `remaining full xp never goes negative`() {
        assertEquals(4, GrowthRules.fullXpRemaining(0, 4))
        assertEquals(0, GrowthRules.fullXpRemaining(9, 4))
    }
}
