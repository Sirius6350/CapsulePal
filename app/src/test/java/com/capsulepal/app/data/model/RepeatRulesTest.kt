package com.capsulepal.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class RepeatRulesTest {

    private fun draft(repeat: RepeatRule, repeatCount: Int): TaskDraft = TaskDraft(
        title = "测试",
        date = LocalDate.of(2026, 1, 1),
        startTime = LocalTime.of(9, 0),
        repeat = repeat,
        repeatCount = repeatCount
    )

    @Test
    fun `连续天数保留设置值`() {
        val task = draft(RepeatRule.CONSECUTIVE, 7).toTask()
        assertEquals(7, task.repeatCount)
    }

    @Test
    fun `连续天数超界时收敛到允许范围`() {
        assertEquals(
            MAX_CONSECUTIVE_DAYS,
            draft(RepeatRule.CONSECUTIVE, MAX_CONSECUTIVE_DAYS + 1).toTask().repeatCount
        )
        assertEquals(MIN_CONSECUTIVE_DAYS, draft(RepeatRule.CONSECUTIVE, 0).toTask().repeatCount)
    }

    @Test
    fun `其他重复规则不保留连续天数`() {
        assertEquals(0, draft(RepeatRule.DAILY, 7).toTask().repeatCount)
        assertEquals(0, draft(RepeatRule.WEEKLY, 7).toTask().repeatCount)
        assertEquals(0, draft(RepeatRule.ONCE, 7).toTask().repeatCount)
    }

    @Test
    fun `连续天数按日历日展开并忽略节假日跳过`() {
        val task = draft(RepeatRule.CONSECUTIVE, 7).copy(skipHolidays = true).toTask()
        assertFalse(task.skipHolidays)
    }
}
