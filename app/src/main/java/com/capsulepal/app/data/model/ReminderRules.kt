package com.capsulepal.app.data.model

import androidx.annotation.DrawableRes
import com.capsulepal.app.R
import com.capsulepal.app.domain.SignalType
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek

/**
 * 主动提醒的触发源。
 *
 * 每条用户规则由一个主事件（本枚举）+ 若干状态过滤条件组成。
 * 条件之间是 AND 关系：主事件到来时，只有全部条件成立才会提醒。
 */
enum class ReminderTriggerType(
    val label: String,
    val description: String,
    val defaultEnabled: Boolean,
    val defaultDailyLimit: Int,
    val defaultPriority: Int
) {
    WIFI(
        "连接 WiFi",
        "连上指定（或任意）WiFi 时提醒，可用来区分家、公司",
        defaultEnabled = true,
        defaultDailyLimit = 3,
        defaultPriority = 1
    ),
    HEADSET(
        "插入有线耳机",
        "有线耳机插入时提醒，适合听播客、进入专注",
        defaultEnabled = true,
        defaultDailyLimit = 3,
        defaultPriority = 1
    ),
    BLUETOOTH(
        "连接蓝牙设备",
        "连上车载蓝牙、运动耳机等指定设备时提醒",
        defaultEnabled = true,
        defaultDailyLimit = 3,
        defaultPriority = 1
    ),
    CHARGING_START(
        "开始充电",
        "插上电源时提醒，适合睡前整理、出门准备",
        defaultEnabled = true,
        defaultDailyLimit = 3,
        defaultPriority = 2
    ),
    CHARGING_STOP(
        "停止充电",
        "拔掉电源时提醒，适合出门前确认随身物品",
        defaultEnabled = true,
        defaultDailyLimit = 3,
        defaultPriority = 2
    ),
    CALL_ENDED(
        "通话结束",
        "挂断电话后提醒，衔接下一件事",
        defaultEnabled = true,
        defaultDailyLimit = 3,
        defaultPriority = 2
    ),
    CALENDAR_SOON(
        "系统日历临近",
        "日历事件开始前 N 分钟提醒，全天事件自动忽略",
        defaultEnabled = true,
        defaultDailyLimit = 3,
        defaultPriority = 3
    ),
    APP_LAUNCH(
        "打开指定应用",
        "打开选中的应用时提醒，可多选，需要「使用情况访问」权限",
        defaultEnabled = false,
        defaultDailyLimit = 2,
        defaultPriority = 0
    )
}

enum class ReminderActionType(val label: String) {
    CUSTOM("仅显示自定义提醒"),
    LINKED_TASK("关联指定待办"),
    AUTO_TASK("自动关联最近待办")
}

/**
 * 用户可编辑的主动提醒规则。
 *
 * [dailyLimit] 为 0 表示不限次数；[highPriority] 打开后可穿透全局冷却，
 * 但仍受每日上限与免打扰时段约束。
 */
data class ReminderRule(
    val id: Long,
    val name: String = "",
    val type: ReminderTriggerType,
    val enabled: Boolean = true,
    val action: ReminderActionType = ReminderActionType.AUTO_TASK,
    /** 固定文案；旧版本里也可能是带占位符的整段文案。 */
    val message: String = "",
    /** 多段输入：待办名。 */
    val messageTask: String = "",
    /** 多段输入：剩余分钟。 */
    val messageMinutes: String = "",
    /** 多段输入：开始时间。 */
    val messageTime: String = "",
    /** 已被「待办 / 计时 / 时间」按钮插入过的字段位掩码，1=待办 2=计时 4=时间。 */
    val messageSlotsMask: Int = 0,
    val linkedTaskId: Long = 0L,
    val wifiSsid: String = "",
    val bluetoothName: String = "",
    /** 蓝牙设备地址，名称因权限不可读时用地址兜底匹配。 */
    val bluetoothAddress: String = "",
    val appPackages: List<String> = emptyList(),
    val appLabels: List<String> = emptyList(),
    /** 指定时间触发的分钟数（0–1439）。 */
    val triggerMinuteOfDay: Int = 8 * 60,
    val calendarLeadMinutes: Int = 15,
    val timeWindowEnabled: Boolean = false,
    val startMinuteOfDay: Int = 0,
    val endMinuteOfDay: Int = 23 * 60 + 59,
    val daysMask: Int = ALL_DAYS,
    val dailyLimit: Int = 0,
    val highPriority: Boolean = false
) {
    val displayName: String
        get() = name.trim().ifBlank {
            when {
                type == ReminderTriggerType.WIFI && wifiSsid.isNotBlank() -> "连上 $wifiSsid"
                type == ReminderTriggerType.BLUETOOTH &&
                    (bluetoothName.isNotBlank() || bluetoothAddress.isNotBlank()) ->
                    "连上 ${bluetoothName.ifBlank { bluetoothAddress }}"
                type == ReminderTriggerType.APP_LAUNCH && appLabels.isNotEmpty() ->
                    if (appLabels.size == 1) {
                        "打开 ${appLabels.first()}"
                    } else {
                        "打开 ${appLabels.size} 个应用"
                    }
                else -> type.label
            }
        }

    val priority: Int
        get() = if (highPriority) HIGH_PRIORITY_WEIGHT else type.defaultPriority

    /** 0 表示不限次数。 */
    val effectiveDailyLimit: Int
        get() = dailyLimit.coerceIn(0, 20)

    /** 多段输入拼接后的自定义提醒文案。 */
    val customMessage: String
        get() {
            // 内联字段用不可见标记包裹，只在编辑框里用于着色与整体删除；
            // 真正发出去的文案必须先把标记剥掉。
            val lead = message.trim().stripMessageTokens()
            val task = messageTask.trim()
            val minutes = messageMinutes.trim()
            val time = messageTime.trim()
            if (task.isBlank() && minutes.isBlank() && time.isBlank()) return lead
            val builder = StringBuilder()
            if (lead.isNotEmpty()) builder.append(lead)
            if (task.isNotEmpty()) {
                if (builder.isNotEmpty()) builder.append(' ')
                builder.append('「').append(task).append('」')
            }
            val tail = buildList {
                if (minutes.isNotEmpty()) add("还有 $minutes 分钟")
                if (time.isNotEmpty()) add("$time 开始")
            }.joinToString("，")
            if (tail.isNotEmpty()) {
                if (builder.isNotEmpty()) builder.append(' ')
                builder.append(tail)
            }
            return builder.toString()
        }

    fun matchesDay(day: DayOfWeek): Boolean =
        daysMask and (1 shl (day.value - 1)) != 0

    val timeWindowLabel: String
        get() = if (!timeWindowEnabled) {
            "全天"
        } else {
            "${formatMinute(startMinuteOfDay)}–${formatMinute(endMinuteOfDay)}"
        }

    val daysLabel: String
        get() {
            if (daysMask == ALL_DAYS) return "每天"
            if (daysMask == WEEKDAYS) return "工作日"
            if (daysMask == WEEKEND) return "周末"
            return DAY_LABELS.filterIndexed { index, _ ->
                daysMask and (1 shl index) != 0
            }.joinToString("、")
        }

    val conditionSummary: String
        get() {
            val parts = mutableListOf<String>()
            when (type) {
                ReminderTriggerType.WIFI ->
                    parts += if (wifiSsid.isBlank()) "任意 WiFi" else "WiFi：$wifiSsid"
                ReminderTriggerType.BLUETOOTH ->
                    parts += when {
                        bluetoothName.isNotBlank() -> "设备：$bluetoothName"
                        bluetoothAddress.isNotBlank() -> "设备：$bluetoothAddress"
                        else -> "任意蓝牙设备"
                    }
                ReminderTriggerType.APP_LAUNCH -> parts += if (appPackages.isEmpty()) {
                    "任意应用"
                } else {
                    val names = appLabels.ifEmpty { appPackages }
                    val shown = names.take(2).joinToString("、")
                    if (names.size > 2) "应用：$shown 等 ${names.size} 个" else "应用：$shown"
                }
                ReminderTriggerType.CALENDAR_SOON ->
                    parts += "提前 $calendarLeadMinutes 分钟"
                else -> Unit
            }
            if (timeWindowEnabled) parts += timeWindowLabel
            if (daysMask != ALL_DAYS) parts += daysLabel
            return parts.joinToString(" · ").ifBlank { "无附加条件" }
        }

    val actionSummary: String
        get() = when (action) {
            ReminderActionType.CUSTOM ->
                customMessage.ifBlank { "自定义提醒" }
            ReminderActionType.LINKED_TASK -> "关联指定待办"
            ReminderActionType.AUTO_TASK -> "自动关联最近待办"
        }

    companion object {
        const val ALL_DAYS = 0x7F
        const val WEEKDAYS = 0x1F
        const val WEEKEND = 0x60
        const val HIGH_PRIORITY_WEIGHT = 3

        const val SLOT_TASK = 1
        const val SLOT_MINUTES = 2
        const val SLOT_TIME = 4

        /** 三种内联字段各自使用一对私有区字符，编辑框据此着色并整体删除。 */
        const val TOKEN_TASK_OPEN = '\uE000'
        const val TOKEN_TASK_CLOSE = '\uE001'
        const val TOKEN_MINUTES_OPEN = '\uE002'
        const val TOKEN_MINUTES_CLOSE = '\uE003'
        const val TOKEN_TIME_OPEN = '\uE004'
        const val TOKEN_TIME_CLOSE = '\uE005'

        fun tokenOpen(slot: Int): Char = when (slot) {
            SLOT_MINUTES -> TOKEN_MINUTES_OPEN
            SLOT_TIME -> TOKEN_TIME_OPEN
            else -> TOKEN_TASK_OPEN
        }

        fun tokenClose(slot: Int): Char = when (slot) {
            SLOT_MINUTES -> TOKEN_MINUTES_CLOSE
            SLOT_TIME -> TOKEN_TIME_CLOSE
            else -> TOKEN_TASK_CLOSE
        }

        fun isTokenMark(char: Char): Boolean =
            char in TOKEN_TASK_OPEN..TOKEN_TIME_CLOSE

        val DAY_LABELS = listOf("一", "二", "三", "四", "五", "六", "日")

        fun formatMinute(minuteOfDay: Int): String {
            val safe = minuteOfDay.coerceIn(0, 24 * 60 - 1)
            return "%02d:%02d".format(safe / 60, safe % 60)
        }
    }
}

/** 去掉提醒文案里的内联字段标记，得到真正展示/发送的文本。 */
fun String.stripMessageTokens(): String =
    if (none { ReminderRule.isTokenMark(it) }) this
    else filterNot { ReminderRule.isTokenMark(it) }

/** 依赖 App 进程常驻监听的触发源。 */
val ReminderTriggerType.needsBackgroundWatch: Boolean
    get() = this != ReminderTriggerType.CALENDAR_SOON

/** 系统信号到主动提醒触发源的映射，供规则引擎与诊断页共用。 */
fun SignalType.toReminderTriggerType(): ReminderTriggerType? = when (this) {
    SignalType.WIFI_CONNECTED -> ReminderTriggerType.WIFI
    SignalType.HEADSET_PLUGGED -> ReminderTriggerType.HEADSET
    SignalType.BLUETOOTH_CONNECTED -> ReminderTriggerType.BLUETOOTH
    SignalType.POWER_CONNECTED -> ReminderTriggerType.CHARGING_START
    SignalType.POWER_DISCONNECTED -> ReminderTriggerType.CHARGING_STOP
    SignalType.CALL_ENDED -> ReminderTriggerType.CALL_ENDED
    SignalType.CALENDAR_SOON -> ReminderTriggerType.CALENDAR_SOON
    SignalType.APP_FOREGROUND -> ReminderTriggerType.APP_LAUNCH
    else -> null
}

@get:DrawableRes
val ReminderTriggerType.iconRes: Int
    get() = when (this) {
        ReminderTriggerType.WIFI -> R.drawable.ic_ui_wifi
        ReminderTriggerType.HEADSET -> R.drawable.ic_ui_headphone
        ReminderTriggerType.BLUETOOTH -> R.drawable.ic_rule_bluetooth
        ReminderTriggerType.CHARGING_START -> R.drawable.ic_ui_charger
        ReminderTriggerType.CHARGING_STOP -> R.drawable.ic_rule_unplug
        ReminderTriggerType.CALL_ENDED -> R.drawable.ic_ui_phone
        ReminderTriggerType.CALENDAR_SOON -> R.drawable.ic_calendar
        ReminderTriggerType.APP_LAUNCH -> R.drawable.ic_rule_app
    }

object ReminderRules {

    fun defaults(): List<ReminderRule> = ReminderTriggerType.entries.mapIndexed { index, type ->
        ReminderRule(
            id = index + 1L,
            type = type,
            enabled = type.defaultEnabled,
            action = ReminderActionType.AUTO_TASK,
            dailyLimit = type.defaultDailyLimit
        )
    }

    fun emptyRule(id: Long, type: ReminderTriggerType = ReminderTriggerType.WIFI): ReminderRule =
        ReminderRule(
            id = id,
            type = type,
            enabled = true,
            action = ReminderActionType.AUTO_TASK,
            dailyLimit = type.defaultDailyLimit
        )
}

object ReminderRuleCodec {

    fun encode(rules: List<ReminderRule>): String {
        val array = JSONArray()
        rules.forEach { rule ->
            array.put(
                JSONObject().apply {
                    put("id", rule.id)
                    put("name", rule.name)
                    put("type", rule.type.name)
                    put("enabled", rule.enabled)
                    put("action", rule.action.name)
                    put("message", rule.message)
                    put("msgTask", rule.messageTask)
                    put("msgMinutes", rule.messageMinutes)
                    put("msgTime", rule.messageTime)
                    put("msgMask", rule.messageSlotsMask)
                    put("taskId", rule.linkedTaskId)
                    put("wifi", rule.wifiSsid)
                    put("bt", rule.bluetoothName)
                    put("btAddr", rule.bluetoothAddress)
                    put("pkgs", JSONArray(rule.appPackages))
                    put("appLabels", JSONArray(rule.appLabels))
                    put("at", rule.triggerMinuteOfDay)
                    put("lead", rule.calendarLeadMinutes)
                    put("window", rule.timeWindowEnabled)
                    put("start", rule.startMinuteOfDay)
                    put("end", rule.endMinuteOfDay)
                    put("days", rule.daysMask)
                    put("limit", rule.dailyLimit)
                    put("high", rule.highPriority)
                }
            )
        }
        return array.toString()
    }

    /** 没有数据或解析失败时返回 null，由调用方回落到默认规则，避免误清空用户配置。 */
    fun decode(raw: String?): List<ReminderRule>? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val type = runCatching {
                    ReminderTriggerType.valueOf(item.optString("type"))
                }.getOrNull() ?: return@mapNotNull null
                val packages = item.optJSONArray("pkgs").toStringList().ifEmpty {
                    listOfNotNull(item.optString("pkg").takeIf { it.isNotBlank() })
                }
                val labels = item.optJSONArray("appLabels").toStringList().ifEmpty {
                    listOfNotNull(item.optString("appLabel").takeIf { it.isNotBlank() })
                }
                ReminderRule(
                    id = item.optLong("id", index + 1L),
                    name = item.optString("name"),
                    type = type,
                    enabled = item.optBoolean("enabled", true),
                    action = runCatching {
                        ReminderActionType.valueOf(item.optString("action"))
                    }.getOrDefault(ReminderActionType.AUTO_TASK),
                    message = item.optString("message"),
                    messageTask = item.optString("msgTask"),
                    messageMinutes = item.optString("msgMinutes"),
                    messageTime = item.optString("msgTime"),
                    messageSlotsMask = item.optInt("msgMask", 0),
                    linkedTaskId = item.optLong("taskId", 0L),
                    wifiSsid = item.optString("wifi"),
                    bluetoothName = item.optString("bt"),
                    bluetoothAddress = item.optString("btAddr"),
                    appPackages = packages,
                    appLabels = labels,
                    triggerMinuteOfDay = item.optInt("at", 8 * 60).coerceIn(0, 24 * 60 - 1),
                    calendarLeadMinutes = item.optInt("lead", 15).coerceIn(1, 180),
                    timeWindowEnabled = item.optBoolean("window", false),
                    startMinuteOfDay = item.optInt("start", 0).coerceIn(0, 24 * 60 - 1),
                    endMinuteOfDay = item.optInt("end", 24 * 60 - 1)
                        .coerceIn(0, 24 * 60 - 1),
                    daysMask = item.optInt("days", ReminderRule.ALL_DAYS),
                    dailyLimit = item.optInt("limit", type.defaultDailyLimit)
                        .coerceIn(0, 20),
                    highPriority = item.optBoolean("high", false)
                )
            }
        }.getOrNull()
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { index ->
            optString(index).takeIf { it.isNotBlank() }
        }
    }
}
