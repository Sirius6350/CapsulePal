package com.capsulepal.app.domain

import com.capsulepal.app.data.model.ReminderTriggerType
import com.capsulepal.app.data.model.TaskDraft

enum class SignalType {
    SCREEN_ON,
    SCREEN_UNLOCKED,
    SCREEN_OFF,
    POWER_CONNECTED,
    POWER_DISCONNECTED,
    HEADSET_PLUGGED,
    HEADSET_UNPLUGGED,
    WIFI_CONNECTED,
    WIFI_DISCONNECTED,
    BLUETOOTH_CONNECTED,
    BLUETOOTH_DISCONNECTED,
    CALL_RINGING,
    CALL_OFFHOOK,
    CALL_ENDED,
    MEDIA_STARTED,
    MEDIA_STOPPED,
    CALENDAR_SOON,
    TIME_POINT,
    APP_FOREGROUND,
    ACTIVITY_STILL,
    ACTIVITY_MOVING
}

data class SystemSignal(
    val type: SignalType,
    val atMillis: Long = System.currentTimeMillis(),
    /** WiFi SSID、蓝牙名、应用包名或日历标题。 */
    val detail: String = "",
    val extra: String = "",
    val eventStartMillis: Long = 0L,
    val eventEndMillis: Long = 0L
)

data class TriggerSuggestion(
    val type: ReminderTriggerType,
    val ruleId: Long,
    val ruleName: String,
    val priority: Int,
    @androidx.annotation.DrawableRes val iconRes: Int,
    val title: String,
    val message: String,
    val taskId: Long? = null,
    val draft: TaskDraft? = null,
    val atMillis: Long = System.currentTimeMillis()
) {
    val canStartPrepare: Boolean get() = taskId != null
}
