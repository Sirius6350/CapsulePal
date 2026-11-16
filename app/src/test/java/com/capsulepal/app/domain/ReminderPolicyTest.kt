package com.capsulepal.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderPolicyTest {

    @Test
    fun `quiet hours support crossing midnight`() {
        val start = 23 * 60
        val end = 7 * 60
        assertTrue(ReminderPolicy.isQuietMinute(23 * 60, start, end))
        assertTrue(ReminderPolicy.isQuietMinute(6 * 60 + 59, start, end))
        assertFalse(ReminderPolicy.isQuietMinute(7 * 60, start, end))
        assertFalse(ReminderPolicy.isQuietMinute(12 * 60, start, end))
    }

    @Test
    fun `quiet hours inside the same day`() {
        val start = 12 * 60
        val end = 14 * 60
        assertFalse(ReminderPolicy.isQuietMinute(11 * 60 + 59, start, end))
        assertTrue(ReminderPolicy.isQuietMinute(12 * 60, start, end))
        assertTrue(ReminderPolicy.isQuietMinute(13 * 60 + 59, start, end))
        assertFalse(ReminderPolicy.isQuietMinute(14 * 60, start, end))
    }

    @Test
    fun `same start and end means quiet hours are off`() {
        assertFalse(ReminderPolicy.isQuietMinute(0, 0, 0))
        assertFalse(ReminderPolicy.isQuietMinute(12 * 60, 12 * 60, 12 * 60))
    }

    @Test
    fun `cooldown key prefers calendar app and task identity`() {
        assertEquals(
            "calendar:1000",
            ReminderPolicy.cooldownKey(
                eventStartMillis = 1000L,
                appLaunchPackage = "com.video",
                taskId = 7L
            )
        )
        assertEquals(
            "app:com.video",
            ReminderPolicy.cooldownKey(
                eventStartMillis = 0L,
                appLaunchPackage = "com.video",
                taskId = 7L
            )
        )
        assertEquals(
            "task:7",
            ReminderPolicy.cooldownKey(
                eventStartMillis = 0L,
                appLaunchPackage = null,
                taskId = 7L
            )
        )
    }

    @Test
    fun `event-driven triggers do not share an event key`() {
        // 充电 / WiFi / 蓝牙 / 耳机每次事件独立，靠全局冷却兜底，
        // 避免不同触发源在冷却期内互相压制。
        assertNull(ReminderPolicy.cooldownKey(0L, null, null))
    }
}
