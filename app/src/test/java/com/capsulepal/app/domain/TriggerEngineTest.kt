package com.capsulepal.app.domain

import com.capsulepal.app.data.model.ReminderActionType
import com.capsulepal.app.data.model.ReminderRule
import com.capsulepal.app.data.model.ReminderTriggerType
import com.capsulepal.app.data.model.Task
import com.capsulepal.app.data.model.TaskCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class TriggerEngineTest {

    private val engine = TriggerEngine()
    private val date: LocalDate = LocalDate.of(2026, 3, 2)
    private val start: LocalTime = LocalTime.of(10, 0)
    private val nowMillis: Long = date.atTime(9, 30)
        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun task(
        prepareMinutes: Int = 10,
        id: Long = 7L
    ) = Task(
        id = id,
        title = "写周报",
        date = date,
        startTime = start,
        durationMinutes = 30,
        prepareMinutes = prepareMinutes,
        category = TaskCategory.FOCUS
    )

    private fun rule(
        type: ReminderTriggerType,
        id: Long = 1L,
        action: ReminderActionType = ReminderActionType.AUTO_TASK,
        enabled: Boolean = true,
        message: String = "",
        messageTask: String = "",
        messageMinutes: String = "",
        messageTime: String = "",
        wifiSsid: String = "",
        bluetoothName: String = "",
        bluetoothAddress: String = "",
        appPackages: List<String> = emptyList(),
        leadMinutes: Int = 15,
        timeWindowEnabled: Boolean = false,
        startMinute: Int = 0,
        endMinute: Int = 24 * 60 - 1,
        daysMask: Int = ReminderRule.ALL_DAYS,
        linkedTaskId: Long = 0L,
        highPriority: Boolean = false
    ) = ReminderRule(
        id = id,
        type = type,
        enabled = enabled,
        action = action,
        message = message,
        messageTask = messageTask,
        messageMinutes = messageMinutes,
        messageTime = messageTime,
        wifiSsid = wifiSsid,
        bluetoothName = bluetoothName,
        bluetoothAddress = bluetoothAddress,
        appPackages = appPackages,
        calendarLeadMinutes = leadMinutes,
        timeWindowEnabled = timeWindowEnabled,
        startMinuteOfDay = startMinute,
        endMinuteOfDay = endMinute,
        daysMask = daysMask,
        linkedTaskId = linkedTaskId,
        highPriority = highPriority
    )

    private fun evaluate(
        signal: SystemSignal,
        rules: List<ReminderRule>,
        tasks: List<Task> = listOf(task()),
        mediaPlaying: Boolean = false,
        at: Long = nowMillis
    ) = engine.evaluate(
        signal = signal.copy(atMillis = at),
        tasks = tasks,
        nowMillis = at,
        rules = rules,
        mediaPlaying = mediaPlaying,
        mediaSilenceEnabled = true
    )

    @Test
    fun `wifi rule only matches configured ssid`() {
        val rules = listOf(rule(ReminderTriggerType.WIFI, wifiSsid = "公司WiFi"))
        val matched = evaluate(
            SystemSignal(SignalType.WIFI_CONNECTED, detail = "公司WiFi"),
            rules
        )
        assertEquals(1, matched.size)
        assertTrue(evaluate(
            SystemSignal(SignalType.WIFI_CONNECTED, detail = "家里WiFi"),
            rules
        ).isEmpty())
    }

    @Test
    fun `blank wifi ssid matches any network`() {
        val rules = listOf(rule(ReminderTriggerType.WIFI))
        assertEquals(
            1,
            evaluate(SystemSignal(SignalType.WIFI_CONNECTED, detail = "任意"), rules).size
        )
    }

    @Test
    fun `bluetooth name filter is case insensitive and partial`() {
        val rules = listOf(rule(ReminderTriggerType.BLUETOOTH, bluetoothName = "car audio"))
        assertEquals(
            1,
            evaluate(SystemSignal(SignalType.BLUETOOTH_CONNECTED, detail = "Car Audio"), rules).size
        )
    }

    @Test
    fun `bluetooth rule falls back to device address when name is unreadable`() {
        val rules = listOf(
            rule(ReminderTriggerType.BLUETOOTH, bluetoothAddress = "AA:BB:CC:DD:EE:FF")
        )
        assertEquals(
            1,
            evaluate(
                SystemSignal(
                    SignalType.BLUETOOTH_CONNECTED,
                    detail = "AA:BB:CC:DD:EE:FF",
                    extra = "AA:BB:CC:DD:EE:FF"
                ),
                rules
            ).size
        )
        assertTrue(evaluate(
            SystemSignal(
                SignalType.BLUETOOTH_CONNECTED,
                detail = "11:22:33:44:55:66",
                extra = "11:22:33:44:55:66"
            ),
            rules
        ).isEmpty())
    }

    @Test
    fun `bluetooth rule matches address even when name differs`() {
        val rules = listOf(
            rule(
                ReminderTriggerType.BLUETOOTH,
                bluetoothName = "车机",
                bluetoothAddress = "AA:BB:CC:DD:EE:FF"
            )
        )
        assertEquals(
            1,
            evaluate(
                SystemSignal(
                    SignalType.BLUETOOTH_CONNECTED,
                    detail = "Car Audio",
                    extra = "AA:BB:CC:DD:EE:FF"
                ),
                rules
            ).size
        )
    }

    @Test
    fun `app rule matches any of the selected packages`() {
        val rules = listOf(
            rule(
                ReminderTriggerType.APP_LAUNCH,
                appPackages = listOf("com.game", "com.video")
            )
        )
        assertEquals(
            1,
            evaluate(SystemSignal(SignalType.APP_FOREGROUND, detail = "com.video"), rules).size
        )
        assertTrue(evaluate(
            SystemSignal(SignalType.APP_FOREGROUND, detail = "com.chat"),
            rules
        ).isEmpty())
    }

    @Test
    fun `single message field keeps inserted fields as plain values`() {
        val suggestion = evaluate(
            SystemSignal(SignalType.APP_FOREGROUND, detail = "com.video"),
            listOf(
                rule(
                    ReminderTriggerType.APP_LAUNCH,
                    action = ReminderActionType.CUSTOM,
                    appPackages = listOf("com.video"),
                    message = "先写周报 10 分钟 19:30"
                )
            )
        ).first()
        assertEquals("先写周报 10 分钟 19:30", suggestion.message)
    }

    @Test
    fun `custom message is assembled from segments`() {
        val suggestion = evaluate(
            SystemSignal(SignalType.HEADSET_PLUGGED),
            listOf(
                rule(
                    ReminderTriggerType.HEADSET,
                    action = ReminderActionType.CUSTOM,
                    message = "到家啦",
                    messageTask = "写周报",
                    messageMinutes = "10",
                    messageTime = "19:30"
                )
            )
        ).first()
        assertEquals(
            "到家啦 「写周报」 还有 10 分钟，19:30 开始",
            suggestion.message
        )
        assertNull(suggestion.taskId)
    }

    @Test
    fun `time window and weekday filters are applied`() {
        val rules = listOf(
            rule(
                ReminderTriggerType.HEADSET,
                timeWindowEnabled = true,
                startMinute = 18 * 60,
                endMinute = 20 * 60
            )
        )
        assertTrue(evaluate(SystemSignal(SignalType.HEADSET_PLUGGED), rules).isEmpty())

        val wrongDay = listOf(
            rule(
                ReminderTriggerType.HEADSET,
                daysMask = 1 shl 1 // 周二，今天是周一
            )
        )
        assertTrue(evaluate(SystemSignal(SignalType.HEADSET_PLUGGED), wrongDay).isEmpty())
    }

    @Test
    fun `headset auto links a task before its prepare window`() {
        val suggestions = evaluate(
            SystemSignal(SignalType.HEADSET_PLUGGED),
            listOf(rule(ReminderTriggerType.HEADSET))
        )
        assertEquals(7L, suggestions.firstOrNull()?.taskId)
    }

    @Test
    fun `default copy includes task name clock and remaining minutes`() {
        val suggestion = evaluate(
            SystemSignal(SignalType.HEADSET_PLUGGED),
            listOf(rule(ReminderTriggerType.HEADSET))
        ).first()
        assertEquals(
            "戴上耳机啦。「写周报」10:00 开始，还有 30 分钟，要不要先留一段安静的准备时间？",
            suggestion.message
        )
    }

    @Test
    fun `calendar default copy uses the same clock and remaining style`() {
        val eventAt = date.atTime(10, 20)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val suggestion = evaluate(
            SystemSignal(
                SignalType.CALENDAR_SOON,
                detail = "周会",
                eventStartMillis = eventAt,
                eventEndMillis = eventAt + 30 * 60_000L
            ),
            listOf(rule(ReminderTriggerType.CALENDAR_SOON, leadMinutes = 60))
        ).first()
        assertEquals("日历里「周会」10:20 开始，还有 50 分钟。", suggestion.message)
    }

    @Test
    fun `task already in prepare window is not offered`() {
        val suggestions = evaluate(
            SystemSignal(SignalType.HEADSET_PLUGGED),
            listOf(rule(ReminderTriggerType.HEADSET)),
            tasks = listOf(task(prepareMinutes = 45))
        )
        assertNull(suggestions.firstOrNull()?.taskId)
    }

    @Test
    fun `custom message supports placeholders`() {
        val suggestion = evaluate(
            SystemSignal(SignalType.HEADSET_PLUGGED),
            listOf(
                rule(
                    ReminderTriggerType.HEADSET,
                    action = ReminderActionType.CUSTOM,
                    message = "准备「{task}」，{minutes}后开始"
                )
            )
        ).first()
        assertEquals("准备「写周报」，30 分钟后开始", suggestion.message)
        assertNull(suggestion.taskId)
    }

    @Test
    fun `invalid linked task falls back to text only`() {
        val suggestion = evaluate(
            SystemSignal(SignalType.CALL_ENDED),
            listOf(
                rule(
                    ReminderTriggerType.CALL_ENDED,
                    action = ReminderActionType.LINKED_TASK,
                    linkedTaskId = 999L,
                    message = "电话结束啦"
                )
            )
        ).first()
        assertNull(suggestion.taskId)
        assertEquals("电话结束啦", suggestion.message)
    }

    @Test
    fun `calendar rule honours its own lead time`() {
        val eventAt = nowMillis + 20 * 60_000L
        val signal = SystemSignal(
            SignalType.CALENDAR_SOON,
            detail = "周会",
            eventStartMillis = eventAt,
            eventEndMillis = eventAt + 30 * 60_000L
        )
        assertTrue(evaluate(
            signal,
            listOf(rule(ReminderTriggerType.CALENDAR_SOON, leadMinutes = 15))
        ).isEmpty())
        assertEquals(
            1,
            evaluate(
                signal,
                listOf(rule(ReminderTriggerType.CALENDAR_SOON, leadMinutes = 30))
            ).size
        )
    }

    @Test
    fun `media playback silences normal rules but keeps calendar`() {
        assertTrue(evaluate(
            SystemSignal(SignalType.HEADSET_PLUGGED),
            listOf(rule(ReminderTriggerType.HEADSET)),
            mediaPlaying = true
        ).isEmpty())

        val eventAt = nowMillis + 5 * 60_000L
        assertEquals(
            1,
            evaluate(
                SystemSignal(
                    SignalType.CALENDAR_SOON,
                    detail = "周会",
                    eventStartMillis = eventAt,
                    eventEndMillis = eventAt + 30 * 60_000L
                ),
                listOf(rule(ReminderTriggerType.CALENDAR_SOON)),
                mediaPlaying = true
            ).size
        )
    }

    @Test
    fun `disabled rule never fires`() {
        assertTrue(evaluate(
            SystemSignal(SignalType.HEADSET_PLUGGED),
            listOf(rule(ReminderTriggerType.HEADSET, enabled = false))
        ).isEmpty())
    }

    @Test
    fun `suggestions are sorted by priority`() {
        val suggestions = evaluate(
            SystemSignal(SignalType.HEADSET_PLUGGED),
            listOf(
                rule(ReminderTriggerType.HEADSET, id = 1, highPriority = false),
                rule(ReminderTriggerType.HEADSET, id = 2, highPriority = true)
            )
        )
        assertEquals(2L, suggestions.first().ruleId)
        assertTrue(suggestions.first().priority > suggestions.last().priority)
    }
}
