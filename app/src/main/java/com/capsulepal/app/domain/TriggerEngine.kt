package com.capsulepal.app.domain

import com.capsulepal.app.data.model.ReminderActionType
import com.capsulepal.app.data.model.ReminderRule
import com.capsulepal.app.data.model.ReminderTriggerType
import com.capsulepal.app.data.model.Task
import com.capsulepal.app.data.model.iconRes
import com.capsulepal.app.data.model.toReminderTriggerType
import java.time.Instant
import java.time.ZoneId

/**
 * 纯本地规则引擎：
 * 主事件（[SignalType]）+ 状态过滤条件（时间段、星期、SSID、设备名、应用、提前量）
 * + 动作（自定义文案 / 关联待办 / 自动最近待办）。
 */
class TriggerEngine {

    fun evaluate(
        signal: SystemSignal,
        tasks: List<Task>,
        nowMillis: Long,
        rules: List<ReminderRule>,
        mediaPlaying: Boolean,
        mediaSilenceEnabled: Boolean,
        allowTaskLink: Boolean = true
    ): List<TriggerSuggestion> {
        val triggerType = signal.type.toReminderTriggerType() ?: return emptyList()
        if (signal.type == SignalType.SCREEN_OFF) return emptyList()
        if (mediaSilenceEnabled && mediaPlaying && triggerType != ReminderTriggerType.CALENDAR_SOON) {
            return emptyList()
        }

        val zone = ZoneId.systemDefault()
        val nowTime = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val minuteOfDay = nowTime.hour * 60 + nowTime.minute
        val day = nowTime.dayOfWeek

        return rules.asSequence()
            .filter { it.type == triggerType && it.enabled }
            // 没有待办模块的版本（极客体验）只能触发纯自定义提醒；
            // 待办类规则保留在列表里只读展示，切回其它版本后自动恢复触发。
            .filter { allowTaskLink || it.action == ReminderActionType.CUSTOM }
            .filter { it.matchesDay(day) }
            .filter { matchesWindow(it, minuteOfDay) }
            .filter { matchesDetail(it, signal) }
            .mapNotNull { rule -> build(rule, signal, tasks, nowMillis) }
            .sortedByDescending { it.priority }
            .toList()
    }

    private fun matchesWindow(rule: ReminderRule, minuteOfDay: Int): Boolean {
        if (!rule.timeWindowEnabled) return true
        val start = rule.startMinuteOfDay
        val end = rule.endMinuteOfDay
        return if (start <= end) {
            minuteOfDay in start..end
        } else {
            // 跨零点，例如 22:00–06:00。
            minuteOfDay >= start || minuteOfDay <= end
        }
    }

    private fun matchesDetail(rule: ReminderRule, signal: SystemSignal): Boolean = when (rule.type) {
        ReminderTriggerType.WIFI ->
            rule.wifiSsid.isBlank() || normalize(rule.wifiSsid) == normalize(signal.detail)
        ReminderTriggerType.BLUETOOTH -> matchesBluetooth(rule, signal)
        ReminderTriggerType.APP_LAUNCH ->
            rule.appPackages.isEmpty() || signal.detail in rule.appPackages
        ReminderTriggerType.CALENDAR_SOON -> {
            val before = signal.eventStartMillis - signal.atMillis
            before > 0L && before <= rule.calendarLeadMinutes * 60_000L
        }
        else -> true
    }

    /** 名称可用时按名称包含匹配；名称读不到时用设备地址精确匹配。 */
    private fun matchesBluetooth(rule: ReminderRule, signal: SystemSignal): Boolean {
        val name = rule.bluetoothName.trim()
        val address = rule.bluetoothAddress.trim()
        if (name.isBlank() && address.isBlank()) return true
        val signalName = normalize(signal.detail)
        val signalAddress = signal.extra.trim()
        if (name.isNotBlank() && signalName.contains(normalize(name))) return true
        if (address.isNotBlank() &&
            (signalAddress.equals(address, ignoreCase = true) ||
                signal.detail.equals(address, ignoreCase = true))
        ) {
            return true
        }
        return false
    }

    private fun build(
        rule: ReminderRule,
        signal: SystemSignal,
        tasks: List<Task>,
        nowMillis: Long
    ): TriggerSuggestion? {
        val autoTask = autoTask(rule.type, tasks, nowMillis)
        val action = rule.action
        val linkedTask = if (action == ReminderActionType.LINKED_TASK) {
            tasks.firstOrNull { it.id == rule.linkedTaskId }
                ?.takeIf { it.isOpen && beforePrepare(it, nowMillis) }
        } else {
            null
        }
        val task = when (action) {
            ReminderActionType.AUTO_TASK -> autoTask
            ReminderActionType.LINKED_TASK -> linkedTask
            // 纯自定义提醒不提供“开始准备”，但文案里仍可使用最近待办作为占位符。
            ReminderActionType.CUSTOM -> null
        }
        val message = renderMessage(
            rule = rule,
            signal = signal,
            task = if (action == ReminderActionType.CUSTOM) autoTask else task,
            nowMillis = nowMillis
        )
        if (message.isBlank()) return null
        return TriggerSuggestion(
            type = rule.type,
            ruleId = rule.id,
            ruleName = rule.displayName,
            priority = rule.priority,
            iconRes = rule.type.iconRes,
            title = rule.displayName,
            message = message,
            taskId = task?.id,
            atMillis = nowMillis
        )
    }

    private fun autoTask(
        type: ReminderTriggerType,
        tasks: List<Task>,
        nowMillis: Long
    ): Task? {
        val within = when (type) {
            ReminderTriggerType.HEADSET -> 45 * 60_000L
            ReminderTriggerType.CHARGING_START -> 60 * 60_000L
            ReminderTriggerType.CHARGING_STOP -> 60 * 60_000L
            ReminderTriggerType.WIFI -> 30 * 60_000L
            ReminderTriggerType.BLUETOOTH -> 60 * 60_000L
            ReminderTriggerType.CALL_ENDED -> 20 * 60_000L
            ReminderTriggerType.CALENDAR_SOON -> 30 * 60_000L
            ReminderTriggerType.APP_LAUNCH -> 120 * 60_000L
        }
        return ActiveTaskResolver.suggestedPrepareTask(tasks, nowMillis, within)
            ?.takeIf { beforePrepare(it, nowMillis) }
    }

    private fun renderMessage(
        rule: ReminderRule,
        signal: SystemSignal,
        task: Task?,
        nowMillis: Long
    ): String {
        val custom = rule.customMessage.trim()
        if (custom.isNotEmpty()) {
            // 多段输入已经是成品文案；只有旧版本留下的整段模板才继续解析占位符。
            val hasSegments = rule.messageTask.isNotBlank() ||
                rule.messageMinutes.isNotBlank() ||
                rule.messageTime.isNotBlank()
            return if (hasSegments) custom else applyPlaceholders(custom, task, nowMillis)
        }
        return defaultMessage(rule, signal, task, nowMillis)
    }

    private fun applyPlaceholders(template: String, task: Task?, nowMillis: Long): String {
        val taskTitle = task?.title.orEmpty()
        val clock = task?.startTime?.toString()?.take(5).orEmpty()
        val remaining = if (task != null && task.startMillis > nowMillis) {
            TimeUtils.formatMinutesRounded(task.startMillis - nowMillis)
        } else {
            ""
        }
        return template
            .replace("{task}", taskTitle.ifBlank { "接下来的安排" })
            .replace("{待办}", taskTitle.ifBlank { "接下来的安排" })
            .replace("{time}", clock)
            .replace("{时间}", clock)
            .replace("{minutes}", remaining)
            .replace("{剩余}", remaining)
            .trim()
    }

    private fun defaultMessage(
        rule: ReminderRule,
        signal: SystemSignal,
        task: Task?,
        nowMillis: Long
    ): String {
        val type = rule.type
        if (type == ReminderTriggerType.CALENDAR_SOON) {
            val title = signal.detail.ifBlank { "日历事件" }
            val clock = TimeUtils.formatClock(signal.eventStartMillis)
            val minutes = ((signal.eventStartMillis - nowMillis) / 60_000L)
                .coerceAtLeast(1)
            return "日历里「$title」$clock 开始，还有 $minutes 分钟。"
        }
        if (type == ReminderTriggerType.APP_LAUNCH && task == null) {
            val app = signal.extra.ifBlank { signal.detail }
            return if (app.isBlank()) {
                "打开应用了，先想一下今天最重要的一件事。"
            } else {
                "打开「$app」了，今天最重要的一件事做完了吗？"
            }
        }

        // 所有默认文案统一为：情境一句 + 待办名、开始时间、剩余分钟。
        val upcoming = if (task != null) taskScheduleText(task, nowMillis) else ""
        return when (type) {
            ReminderTriggerType.HEADSET -> if (task != null) {
                "戴上耳机啦。$upcoming，要不要先留一段安静的准备时间？"
            } else {
                "戴上耳机啦，先听点喜欢的，慢慢进入状态。"
            }
            ReminderTriggerType.CHARGING_START -> if (task != null) {
                "电在慢慢回来。$upcoming，可以顺手准备一下。"
            } else {
                "电在慢慢回来，也可以趁这会儿整理一下今天。"
            }
            ReminderTriggerType.CHARGING_STOP -> if (task != null) {
                "拔掉电源了。出门前确认一下：$upcoming。"
            } else {
                "拔掉电源了，出门前记得带上随身物品。"
            }
            ReminderTriggerType.WIFI -> if (task != null) {
                "已经连上 WiFi 了。$upcoming，不着急，先缓口气。"
            } else {
                "已经连上 WiFi 了，先安顿一下。"
            }
            ReminderTriggerType.BLUETOOTH -> if (task != null) {
                "蓝牙已经连上。$upcoming，要不要先准备一下？"
            } else {
                "蓝牙已经连上，出发前再确认一下随身物品。"
            }
            ReminderTriggerType.CALL_ENDED -> if (task != null) {
                "电话结束啦，接下来是$upcoming。"
            } else {
                "电话结束啦，先喝口水再继续。"
            }
            ReminderTriggerType.APP_LAUNCH -> if (task != null) {
                "今天最重要的一件事还没做完：$upcoming。"
            } else {
                "打开了应用，先停三秒：现在这件事真的更重要吗？"
            }
            else -> "现在适合看一眼接下来的安排。"
        }
    }

    private fun taskScheduleText(task: Task, nowMillis: Long): String {
        val clock = TimeUtils.formatClock(task.startTime)
        val remaining = TimeUtils.formatMinutesRounded(task.startMillis - nowMillis)
        return "「${task.title}」$clock 开始，还有 $remaining"
    }

    /**
     * 主动提醒只在待办进入准备窗口之前出现。
     * 一旦到了用户设置的准备时间，就应该由任务本身的上岛通知接管。
     */
    private fun beforePrepare(task: Task, nowMillis: Long): Boolean =
        if (task.prepareMinutes > 0) {
            nowMillis < task.prepareStartMillis
        } else {
            nowMillis < task.startMillis
        }

    private fun normalize(value: String): String =
        value.trim().removeSurrounding("\"").lowercase()
}
