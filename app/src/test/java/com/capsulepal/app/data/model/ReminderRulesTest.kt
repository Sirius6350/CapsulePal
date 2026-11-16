package com.capsulepal.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek

class ReminderRulesTest {

    @Test
    fun `codec round trips every editable field`() {
        val rule = ReminderRule(
            id = 42,
            name = "到家",
            type = ReminderTriggerType.WIFI,
            enabled = false,
            action = ReminderActionType.CUSTOM,
            message = "到家啦，准备 {task}",
            wifiSsid = "Home",
            timeWindowEnabled = true,
            startMinuteOfDay = 18 * 60,
            endMinuteOfDay = 23 * 60,
            daysMask = ReminderRule.WEEKDAYS,
            dailyLimit = 0,
            highPriority = true
        )
        val decoded = ReminderRuleCodec.decode(ReminderRuleCodec.encode(listOf(rule)))
        assertNotNull(decoded)
        assertEquals(listOf(rule), decoded)
    }

    @Test
    fun `codec round trips multi app selection and message segments`() {
        val rule = ReminderRule(
            id = 7,
            type = ReminderTriggerType.APP_LAUNCH,
            appPackages = listOf("com.game", "com.video"),
            appLabels = listOf("游戏", "视频"),
            message = "打开太久了",
            messageTask = "写周报",
            messageMinutes = "10",
            messageTime = "19:30",
            triggerMinuteOfDay = 8 * 60 + 45
        )
        assertEquals(
            listOf(rule),
            ReminderRuleCodec.decode(ReminderRuleCodec.encode(listOf(rule)))
        )
    }

    @Test
    fun `codec round trips message slot mask and bluetooth address`() {
        val rule = ReminderRule(
            id = 9,
            type = ReminderTriggerType.BLUETOOTH,
            bluetoothName = "车机",
            bluetoothAddress = "AA:BB:CC:DD:EE:FF",
            message = "上车前先 10 分钟 19:30 写周报",
            messageSlotsMask = ReminderRule.SLOT_TASK or ReminderRule.SLOT_MINUTES
        )
        assertEquals(
            listOf(rule),
            ReminderRuleCodec.decode(ReminderRuleCodec.encode(listOf(rule)))
        )
    }

    @Test
    fun `daily limit is clamped to twenty`() {
        val rule = ReminderRule(
            id = 1,
            type = ReminderTriggerType.HEADSET,
            dailyLimit = 99
        )
        assertEquals(20, rule.effectiveDailyLimit)
    }

    @Test
    fun `empty list is preserved instead of falling back to defaults`() {
        assertEquals(emptyList<ReminderRule>(), ReminderRuleCodec.decode("[]"))
    }

    @Test
    fun `zero daily limit means unlimited`() {
        val rule = ReminderRule(
            id = 1,
            type = ReminderTriggerType.HEADSET,
            dailyLimit = 0
        )
        assertEquals(0, rule.effectiveDailyLimit)
    }

    @Test
    fun `days mask controls weekdays`() {
        val weekdays = ReminderRule(
            id = 1,
            type = ReminderTriggerType.HEADSET,
            daysMask = ReminderRule.WEEKDAYS
        )
        assertTrue(weekdays.matchesDay(DayOfWeek.MONDAY))
        assertTrue(!weekdays.matchesDay(DayOfWeek.SUNDAY))
    }

    @Test
    fun `removed trigger types are dropped when decoding`() {
        val json = """
            [
              {"id":1,"type":"TIME","enabled":true},
              {"id":2,"type":"STILL","enabled":true},
              {"id":3,"type":"SCREEN_ON","enabled":true},
              {"id":4,"type":"WIFI","enabled":true}
            ]
        """.trimIndent()
        assertEquals(
            listOf(ReminderTriggerType.WIFI),
            ReminderRuleCodec.decode(json).orEmpty().map { it.type }
        )
    }

    @Test
    fun `defaults keep app trigger disabled`() {
        val defaults = ReminderRules.defaults().associateBy { it.type }
        assertEquals(false, defaults.getValue(ReminderTriggerType.APP_LAUNCH).enabled)
        assertEquals(true, defaults.getValue(ReminderTriggerType.WIFI).enabled)
        assertEquals(2, defaults.getValue(ReminderTriggerType.APP_LAUNCH).effectiveDailyLimit)
    }
}
