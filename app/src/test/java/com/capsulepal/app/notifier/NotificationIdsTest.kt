package com.capsulepal.app.notifier

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationIdsTest {

    /**
     * 提醒曾与保活前台服务共用 1002，导致提醒通知无法被 cancel() 收起，
     * 表现为按钮点了没反应。这里固定住各通知位的唯一性。
     */
    @Test
    fun `notification slots are unique`() {
        val ids = listOf(
            NotificationActions.TASK_NOTIFICATION_ID,
            NotificationActions.KEEP_ALIVE_NOTIFICATION_ID,
            NotificationActions.DUAL_REMINDER_NOTIFICATION_ID,
            NotificationActions.REMINDER_NOTIFICATION_ID
        )
        assertEquals(ids.size, ids.toSet().size)
    }
}
