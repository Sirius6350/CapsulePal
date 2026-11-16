package com.capsulepal.app.data.repo

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.capsulepal.app.data.model.AppSettings
import com.capsulepal.app.data.model.CategoryCatalog
import com.capsulepal.app.data.model.CategoryEntry
import com.capsulepal.app.data.model.CapsuleVersion
import com.capsulepal.app.data.model.OverduePolicy
import com.capsulepal.app.data.model.ReminderRule
import com.capsulepal.app.data.model.ReminderRuleCodec
import com.capsulepal.app.data.model.ReminderRules
import com.capsulepal.app.data.model.TodoLayout
import com.capsulepal.app.domain.ReminderPolicy
import com.capsulepal.app.partner.PartnerAppearance
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.time.Instant
import java.time.ZoneId

class SettingsRepository(context: Context) {

    private val dataStore = context.applicationContext.capsuleDataStore

    val settings: Flow<AppSettings> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { prefs ->
            val customCategories = prefs[Keys.CUSTOM_CATEGORIES] ?: emptySet()
            val categoryOrder = CategoryCatalog.parse(prefs[Keys.CATEGORY_ORDER])
            AppSettings(
                keepAliveEnabled = prefs[Keys.KEEP_ALIVE] ?: true,
                overlayHidden = prefs[Keys.OVERLAY_HIDDEN] ?: false,
                overlayMoveMinutes = prefs[Keys.OVERLAY_MOVE_MINUTES] ?: 3,
                overlayAlphaPercent = (prefs[Keys.OVERLAY_ALPHA] ?: 80)
                    .let { (it / 10 * 10).coerceIn(10, 80) },
                onboardingDone = prefs[Keys.ONBOARDING_DONE] ?: false,
                exactAlarmAsked = prefs[Keys.EXACT_ALARM_ASKED] ?: false,
                reminderRules = ReminderRuleCodec.decode(prefs[Keys.REMINDER_RULES])
                    ?: emptyList(),
                reminderCooldownMinutes = (prefs[Keys.REMINDER_COOLDOWN] ?: 30)
                    .coerceIn(0, 24 * 60),
                quietHoursEnabled = prefs[Keys.QUIET_ENABLED] ?: false,
                quietStartMinute = (prefs[Keys.QUIET_START] ?: (23 * 60))
                    .coerceIn(0, 24 * 60 - 1),
                quietEndMinute = (prefs[Keys.QUIET_END] ?: (7 * 60))
                    .coerceIn(0, 24 * 60 - 1),
                mediaSilenceEnabled = prefs[Keys.MEDIA_SILENCE] ?: true,
                dailyFullXpCount = prefs[Keys.DAILY_FULL_XP] ?: 4,
                customCategories = customCategories,
                categoryLayout = CategoryCatalog.parseLayout(
                    prefs[Keys.CATEGORY_LAYOUT],
                    categoryOrder,
                    customCategories
                ),
                acknowledgedTaskId = prefs[Keys.ACK_TASK] ?: 0L,
                dualReminderEnabled = prefs[Keys.DUAL_REMINDER] ?: false,
                ratedCapacityMah = prefs[Keys.RATED_CAPACITY] ?: 0,
                batteryHighFrequencyAlways = prefs[Keys.BATTERY_HIGH_FREQ] ?: false,
                geekSamplingInOtherVersions = prefs[Keys.GEEK_SAMPLING_OTHER] ?: false,
                partnerAppearance = prefs[Keys.PARTNER_APPEARANCE]?.mapNotNull {
                    // 旧的「边缘呼吸光」在 1.5.28 改名为「屏幕反光」，读取时做一次兼容映射。
                    val name = if (it == LEGACY_SCREEN_REFLECTION) {
                        PartnerAppearance.SCREEN_REFLECTION.name
                    } else {
                        it
                    }
                    runCatching { PartnerAppearance.valueOf(name) }.getOrNull()
                }?.toSet() ?: PartnerAppearance.defaults,
                noticeAccepted = prefs[Keys.NOTICE_ACCEPTED] ?: false,
                backupDirUri = prefs[Keys.BACKUP_DIR_URI] ?: "",
                todoLayout = runCatching {
                    TodoLayout.valueOf(prefs[Keys.TODO_LAYOUT] ?: TodoLayout.LIST.name)
                }.getOrDefault(TodoLayout.LIST),
                todoCustomRangeDays = prefs[Keys.TODO_RANGE_DAYS] ?: 7,
                overduePolicy = runCatching {
                    OverduePolicy.valueOf(
                        prefs[Keys.OVERDUE_POLICY] ?: OverduePolicy.MARK_DONE.name
                    )
                }.getOrDefault(OverduePolicy.MARK_DONE),
                hideFinishedAfterDay = prefs[Keys.HIDE_FINISHED_AFTER_DAY] ?: true,
                version = CapsuleVersion.fromName(prefs[Keys.VERSION]),
                metricOverlayEnabled = prefs[Keys.METRIC_OVERLAY_ENABLED] ?: false,
                metricOverlayAlphaPercent = (prefs[Keys.METRIC_OVERLAY_ALPHA] ?: 80)
                    .coerceIn(10, 100),
                metricOverlayScalePercent = (prefs[Keys.METRIC_OVERLAY_SCALE] ?: 100)
                    .let { (it / 10 * 10).coerceIn(80, 140) },
                metricOverlayShowTemp = prefs[Keys.METRIC_OVERLAY_TEMP] ?: true,
                metricOverlayShowBattery = prefs[Keys.METRIC_OVERLAY_BATTERY] ?: true,
                metricOverlayShowFps = prefs[Keys.METRIC_OVERLAY_FPS] ?: true,
                metricOverlayShowMemory = prefs[Keys.METRIC_OVERLAY_MEMORY] ?: true,
                metricOverlayHorizontal = prefs[Keys.METRIC_OVERLAY_HORIZONTAL] ?: false,
                metricOverlayOffsetX = prefs[Keys.METRIC_OVERLAY_X] ?: -1,
                metricOverlayOffsetY = prefs[Keys.METRIC_OVERLAY_Y] ?: -1,
                hiddenBackground = prefs[Keys.HIDDEN_BACKGROUND] ?: false
            )
        }

    suspend fun setKeepAliveEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.KEEP_ALIVE] = enabled }
    }

    /** 打开总开关时把悬浮点恢复到默认状态：显示、80% 透明度、3 分钟移动。 */
    suspend fun enableKeepAliveWithDefaults() {
        dataStore.edit {
            it[Keys.KEEP_ALIVE] = true
            it[Keys.OVERLAY_HIDDEN] = false
            it[Keys.OVERLAY_MOVE_MINUTES] = 3
            it[Keys.OVERLAY_ALPHA] = 80
        }
    }

    suspend fun setOverlayHidden(hidden: Boolean) {
        dataStore.edit { it[Keys.OVERLAY_HIDDEN] = hidden }
    }

    suspend fun setOverlayMoveMinutes(minutes: Int) {
        dataStore.edit { it[Keys.OVERLAY_MOVE_MINUTES] = minutes.coerceIn(1, 30) }
    }

    /** 透明度只能落在 10–80 之间且为 10 的整数倍。 */
    suspend fun setOverlayAlphaPercent(percent: Int) {
        dataStore.edit { it[Keys.OVERLAY_ALPHA] = (percent / 10 * 10).coerceIn(10, 80) }
    }

    suspend fun setOnboardingDone(done: Boolean = true) {
        dataStore.edit { it[Keys.ONBOARDING_DONE] = done }
    }

    suspend fun setExactAlarmAsked(asked: Boolean = true) {
        dataStore.edit { it[Keys.EXACT_ALARM_ASKED] = asked }
    }

    suspend fun setReminderRules(rules: List<ReminderRule>) {
        dataStore.edit { it[Keys.REMINDER_RULES] = ReminderRuleCodec.encode(rules) }
    }

    suspend fun setReminderCooldownMinutes(minutes: Int) {
        dataStore.edit {
            it[Keys.REMINDER_COOLDOWN] = if (minutes <= 0) 0 else minutes.coerceIn(5, 120)
        }
    }

    suspend fun setQuietHours(startMinute: Int, endMinute: Int) {
        dataStore.edit { prefs ->
            prefs[Keys.QUIET_START] = startMinute.coerceIn(0, 24 * 60 - 1)
            prefs[Keys.QUIET_END] = endMinute.coerceIn(0, 24 * 60 - 1)
        }
    }

    /** 首次开启免打扰时写入默认 23:00–07:00，之后保留用户改过的时间。 */
    suspend fun setQuietHoursEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.QUIET_ENABLED] = enabled
            if (enabled) {
                if (prefs[Keys.QUIET_START] == null) prefs[Keys.QUIET_START] = 23 * 60
                if (prefs[Keys.QUIET_END] == null) prefs[Keys.QUIET_END] = 7 * 60
            }
        }
    }

    suspend fun setMediaSilenceEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.MEDIA_SILENCE] = enabled }
    }

    suspend fun setDailyFullXpCount(count: Int) {
        dataStore.edit { it[Keys.DAILY_FULL_XP] = count.coerceIn(3, 5) }
    }

    suspend fun setAcknowledgedTask(taskId: Long) {
        dataStore.edit { it[Keys.ACK_TASK] = taskId }
    }

    /** 待办被恢复为空后，重新允许它上岛。 */
    suspend fun clearAcknowledgedTask(taskId: Long) {
        dataStore.edit { prefs ->
            if (prefs[Keys.ACK_TASK] == taskId) {
                prefs[Keys.ACK_TASK] = 0L
            }
        }
    }

    suspend fun setDualReminderEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.DUAL_REMINDER] = enabled }
    }

    /** 0 表示恢复自动读取系统设计容量。 */
    suspend fun setRatedCapacity(mah: Int) {
        dataStore.edit { prefs ->
            if (mah <= 0) {
                prefs.remove(Keys.RATED_CAPACITY)
            } else {
                prefs[Keys.RATED_CAPACITY] = mah.coerceIn(1000, 20_000)
            }
        }
    }

    suspend fun setNoticeAccepted(accepted: Boolean = true) {
        dataStore.edit { it[Keys.NOTICE_ACCEPTED] = accepted }
    }

    suspend fun setBatteryHighFrequencyAlways(enabled: Boolean) {
        dataStore.edit { it[Keys.BATTERY_HIGH_FREQ] = enabled }
    }

    /** 非极客版同步记录极客数据；依赖高频轮询，因此关闭高频时自动失效。 */
    suspend fun setGeekSamplingInOtherVersions(enabled: Boolean) {
        dataStore.edit { it[Keys.GEEK_SAMPLING_OTHER] = enabled }
    }

    suspend fun setBackupDirUri(uri: String) {
        dataStore.edit { it[Keys.BACKUP_DIR_URI] = uri }
    }

    suspend fun setTodoLayout(layout: TodoLayout) {
        dataStore.edit { it[Keys.TODO_LAYOUT] = layout.name }
    }

    suspend fun setTodoCustomRangeDays(days: Int) {
        dataStore.edit { it[Keys.TODO_RANGE_DAYS] = days.coerceIn(1, 30) }
    }

    suspend fun setOverduePolicy(policy: OverduePolicy) {
        dataStore.edit { it[Keys.OVERDUE_POLICY] = policy.name }
    }

    suspend fun setHideFinishedAfterDay(enabled: Boolean) {
        dataStore.edit { it[Keys.HIDE_FINISHED_AFTER_DAY] = enabled }
    }

    /**
     * 切换版本：即时生效，数据保留；非极客版停止产生极客专属数据。
     * 进入极客体验时把「始终高频」真实写入开启，并记住进入前的值；
     * 离开极客体验时恢复用户原来的选择，避免普通版本也被永久锁在 1 秒。
     */
    suspend fun setVersion(version: CapsuleVersion) {
        dataStore.edit { prefs ->
            val previous = CapsuleVersion.fromName(prefs[Keys.VERSION])
            val wasGeek = previous.recordsBatteryTimeline
            val isGeek = version.recordsBatteryTimeline
            if (isGeek && !wasGeek) {
                prefs[Keys.BATTERY_HIGH_FREQ_BEFORE_GEEK] =
                    prefs[Keys.BATTERY_HIGH_FREQ] ?: false
                prefs[Keys.BATTERY_HIGH_FREQ] = true
            } else if (!isGeek && wasGeek) {
                prefs[Keys.BATTERY_HIGH_FREQ] =
                    prefs[Keys.BATTERY_HIGH_FREQ_BEFORE_GEEK] ?: false
            }
            prefs[Keys.VERSION] = version.name
        }
    }

    suspend fun setMetricOverlayEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.METRIC_OVERLAY_ENABLED] = enabled }
    }

    suspend fun setMetricOverlayAlphaPercent(percent: Int) {
        dataStore.edit { it[Keys.METRIC_OVERLAY_ALPHA] = percent.coerceIn(10, 100) }
    }

    suspend fun setMetricOverlayScalePercent(percent: Int) {
        dataStore.edit {
            it[Keys.METRIC_OVERLAY_SCALE] = (percent / 10 * 10).coerceIn(80, 140)
        }
    }

    suspend fun setMetricOverlayShowTemp(show: Boolean) {
        dataStore.edit { it[Keys.METRIC_OVERLAY_TEMP] = show }
    }

    suspend fun setMetricOverlayShowBattery(show: Boolean) {
        dataStore.edit { it[Keys.METRIC_OVERLAY_BATTERY] = show }
    }

    suspend fun setMetricOverlayShowFps(show: Boolean) {
        dataStore.edit { it[Keys.METRIC_OVERLAY_FPS] = show }
    }

    suspend fun setMetricOverlayShowMemory(show: Boolean) {
        dataStore.edit { it[Keys.METRIC_OVERLAY_MEMORY] = show }
    }

    suspend fun setMetricOverlayHorizontal(horizontal: Boolean) {
        dataStore.edit { it[Keys.METRIC_OVERLAY_HORIZONTAL] = horizontal }
    }

    /** 拖动结束后写回位置；-1 表示恢复默认位置。 */
    suspend fun setMetricOverlayPosition(x: Int, y: Int) {
        dataStore.edit { prefs ->
            prefs[Keys.METRIC_OVERLAY_X] = x
            prefs[Keys.METRIC_OVERLAY_Y] = y
        }
    }

    suspend fun setHiddenBackground(enabled: Boolean) {
        dataStore.edit { it[Keys.HIDDEN_BACKGROUND] = enabled }
    }

    /**
     * 清除全部本机数据：设置与伙伴共用同一个 DataStore 文件，这里整体清空即可。
     * 只保留「须知已确认 / 引导已完成 / 当前版本」三个标记：
     * 前两个避免清空后又把用户挡在弹窗里，版本则按用户要求保持不变。
     */
    suspend fun reset() {
        dataStore.edit { prefs ->
            val noticeAccepted = prefs[Keys.NOTICE_ACCEPTED]
            val onboardingDone = prefs[Keys.ONBOARDING_DONE]
            val version = prefs[Keys.VERSION]
            prefs.clear()
            if (noticeAccepted != null) prefs[Keys.NOTICE_ACCEPTED] = noticeAccepted
            if (onboardingDone != null) prefs[Keys.ONBOARDING_DONE] = onboardingDone
            if (version != null) prefs[Keys.VERSION] = version
        }
    }

    suspend fun setPartnerAppearance(appearance: PartnerAppearance, enabled: Boolean) {
        dataStore.edit { prefs ->
            val current = prefs[Keys.PARTNER_APPEARANCE]?.toMutableSet()
                ?: PartnerAppearance.defaults.map { it.name }.toMutableSet()
            // 旧版本的 RIM_LIGHT 也要一起清掉，否则关闭后会被读取逻辑映射回「屏幕反光」。
            current -= LEGACY_SCREEN_REFLECTION
            if (enabled) current += appearance.name else current -= appearance.name
            prefs[Keys.PARTNER_APPEARANCE] = current
        }
    }

    /**
     * 主动提醒的三层闸门：单条规则每日上限 → 全局冷却（高优先级可穿透）→ 同一事件/待办去重。
     * 返回放行结果，只有 ALLOWED 才写入计数与时间戳，便于诊断页展示被哪一层拦下。
     */
    suspend fun tryConsumeReminder(
        rule: ReminderRule,
        cooldownKey: String?,
        nowMillis: Long,
        globalCooldownMinutes: Int
    ): ReminderPolicy.GateResult {
        var result = ReminderPolicy.GateResult.DAILY_LIMIT
        dataStore.edit { prefs ->
            val today = Instant.ofEpochMilli(nowMillis)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .toEpochDay()
            val counts = decodeCounts(prefs[Keys.REMINDER_DAILY_COUNTS])
                .filterValues { it.first == today }
                .toMutableMap()
            val entry = counts[rule.id.toString()]
            val used = entry?.second ?: 0
            val limit = rule.effectiveDailyLimit
            val dailyAllowed = limit <= 0 || used < limit

            val cooldownMillis = globalCooldownMinutes.coerceAtLeast(0) * 60_000L
            val globalAllowed = rule.highPriority || cooldownMillis == 0L ||
                nowMillis - (prefs[Keys.REMINDER_LAST_AT] ?: 0L) >= cooldownMillis

            val eventTimes = decodeTimes(prefs[Keys.REMINDER_TASK_TIMES])
                .filterValues { nowMillis - it < TASK_CLEANUP_MILLIS }
                .toMutableMap()
            val eventCooldown = when {
                cooldownKey?.startsWith("calendar:") == true -> CALENDAR_EVENT_COOLDOWN_MILLIS
                // 打开应用是高频动作，靠全局冷却兜底即可，单应用只做短去重。
                cooldownKey?.startsWith("app:") == true -> APP_EVENT_COOLDOWN_MILLIS
                else -> EVENT_COOLDOWN_MILLIS
            }
            val eventAllowed = cooldownKey == null ||
                nowMillis - (eventTimes[cooldownKey] ?: 0L) >= eventCooldown

            result = when {
                !dailyAllowed -> ReminderPolicy.GateResult.DAILY_LIMIT
                !globalAllowed -> ReminderPolicy.GateResult.GLOBAL_COOLDOWN
                !eventAllowed -> ReminderPolicy.GateResult.EVENT_DUPLICATE
                else -> {
                    counts[rule.id.toString()] = today to (used + 1)
                    prefs[Keys.REMINDER_LAST_AT] = nowMillis
                    if (cooldownKey != null) eventTimes[cooldownKey] = nowMillis
                    ReminderPolicy.GateResult.ALLOWED
                }
            }

            prefs[Keys.REMINDER_DAILY_COUNTS] = encodeCounts(counts)
            prefs[Keys.REMINDER_TASK_TIMES] = encodeTimes(eventTimes)
        }
        return result
    }

    suspend fun addCustomCategory(name: String) {
        val clean = name.trim()
        if (clean.isBlank()) return
        dataStore.edit { prefs ->
            val current = prefs[Keys.CUSTOM_CATEGORIES]?.toMutableSet() ?: mutableSetOf()
            current.add(clean)
            prefs[Keys.CUSTOM_CATEGORIES] = current
            writeLayout(prefs, current)
        }
    }

    suspend fun deleteCustomCategory(name: String) {
        dataStore.edit { prefs ->
            val current = (prefs[Keys.CUSTOM_CATEGORIES] ?: emptySet()) - name
            prefs[Keys.CUSTOM_CATEGORIES] = current
            writeLayout(prefs, current)
        }
    }

    suspend fun resetCategoryLayout() {
        dataStore.edit { prefs ->
            val custom = prefs[Keys.CUSTOM_CATEGORIES] ?: emptySet()
            val default = CategoryCatalog.layout(CategoryCatalog.defaultOrder, custom)
            prefs[Keys.CATEGORY_ORDER] = CategoryCatalog.encode(CategoryCatalog.defaultOrder)
            prefs[Keys.CATEGORY_LAYOUT] = CategoryCatalog.encodeLayout(default)
        }
    }

    suspend fun setCategoryLayout(layout: List<CategoryEntry>) {
        dataStore.edit { prefs ->
            val custom = prefs[Keys.CUSTOM_CATEGORIES] ?: emptySet()
            writeLayout(prefs, custom, layout)
        }
    }

    /** 布局与旧的内置顺序同时写入，旧版本升级上来也能正确读取。 */
    private fun writeLayout(
        prefs: androidx.datastore.preferences.core.MutablePreferences,
        custom: Set<String>,
        layout: List<CategoryEntry>? = null
    ) {
        val order = CategoryCatalog.parse(prefs[Keys.CATEGORY_ORDER])
        val current = layout ?: CategoryCatalog.parseLayout(
            prefs[Keys.CATEGORY_LAYOUT],
            order,
            custom
        )
        val normalized = CategoryCatalog.normalizeLayout(current, custom)
        prefs[Keys.CATEGORY_ORDER] = CategoryCatalog.encode(
            normalized.mapNotNull { (it as? CategoryEntry.BuiltIn)?.category }
        )
        prefs[Keys.CATEGORY_LAYOUT] = CategoryCatalog.encodeLayout(normalized)
    }

    private object Keys {
        val KEEP_ALIVE = booleanPreferencesKey("keep_alive_enabled")
        val OVERLAY_HIDDEN = booleanPreferencesKey("overlay_hidden")
        val OVERLAY_MOVE_MINUTES = intPreferencesKey("overlay_move_minutes")
        val OVERLAY_ALPHA = intPreferencesKey("overlay_alpha_percent")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val NOTICE_ACCEPTED = booleanPreferencesKey("user_notice_accepted")
        val EXACT_ALARM_ASKED = booleanPreferencesKey("exact_alarm_asked")
        val REMINDER_RULES = stringPreferencesKey("reminder_rules_v2")
        val REMINDER_COOLDOWN = intPreferencesKey("reminder_global_cooldown")
        val QUIET_ENABLED = booleanPreferencesKey("reminder_quiet_enabled")
        val QUIET_START = intPreferencesKey("reminder_quiet_start")
        val QUIET_END = intPreferencesKey("reminder_quiet_end")
        val MEDIA_SILENCE = booleanPreferencesKey("reminder_media_silence")
        val DAILY_FULL_XP = intPreferencesKey("daily_full_xp")
        val ACK_TASK = longPreferencesKey("acknowledged_task_id")
        val DUAL_REMINDER = booleanPreferencesKey("dual_reminder_enabled")
        val RATED_CAPACITY = intPreferencesKey("rated_capacity_mah")
        val BATTERY_HIGH_FREQ = booleanPreferencesKey("battery_high_frequency_always")
        val GEEK_SAMPLING_OTHER = booleanPreferencesKey("geek_sampling_in_other_versions")
        val PARTNER_APPEARANCE = stringSetPreferencesKey("partner_appearance")
        val BACKUP_DIR_URI = stringPreferencesKey("backup_dir_uri")

        /** 待办页布局（LIST / GRID）。 */
        val TODO_LAYOUT = stringPreferencesKey("todo_layout")

        /** 待办页「近 n 天」自定义天数。 */
        val TODO_RANGE_DAYS = intPreferencesKey("todo_range_days")

        /** 早于今天仍未处理的待办的结算方式（OverduePolicy.name）。 */
        val OVERDUE_POLICY = stringPreferencesKey("overdue_policy")

        /** 已完成 / 已跳过的待办是否从次日起隐藏。 */
        val HIDE_FINISHED_AFTER_DAY = booleanPreferencesKey("hide_finished_after_day")

        /** 当前版本（CapsuleVersion.name）；版本本身不进备份。 */
        val VERSION = stringPreferencesKey("capsule_version")

        /** 极客版独立指标悬浮窗。 */
        val METRIC_OVERLAY_ENABLED = booleanPreferencesKey("metric_overlay_enabled")
        val METRIC_OVERLAY_ALPHA = intPreferencesKey("metric_overlay_alpha")
        val METRIC_OVERLAY_SCALE = intPreferencesKey("metric_overlay_scale")
        val METRIC_OVERLAY_TEMP = booleanPreferencesKey("metric_overlay_temp")
        val METRIC_OVERLAY_BATTERY = booleanPreferencesKey("metric_overlay_battery")
        val METRIC_OVERLAY_FPS = booleanPreferencesKey("metric_overlay_fps")
        val METRIC_OVERLAY_MEMORY = booleanPreferencesKey("metric_overlay_memory")
        val METRIC_OVERLAY_HORIZONTAL = booleanPreferencesKey("metric_overlay_horizontal")
        val METRIC_OVERLAY_X = intPreferencesKey("metric_overlay_x")
        val METRIC_OVERLAY_Y = intPreferencesKey("metric_overlay_y")
        val HIDDEN_BACKGROUND = booleanPreferencesKey("hidden_background")

        /** 进入极客体验前用户对「始终高频」的选择，离开时恢复。 */
        val BATTERY_HIGH_FREQ_BEFORE_GEEK = booleanPreferencesKey("battery_high_freq_before_geek")
        val CUSTOM_CATEGORIES = stringSetPreferencesKey("custom_categories")
        val CATEGORY_ORDER = stringPreferencesKey("category_order")
        val CATEGORY_LAYOUT = stringPreferencesKey("category_layout")
        val REMINDER_TASK_TIMES = stringPreferencesKey("reminder_task_times")
        val REMINDER_DAILY_COUNTS = stringPreferencesKey("reminder_daily_counts")
        val REMINDER_LAST_AT = longPreferencesKey("reminder_last_shown_at")
    }

    /**
     * 导入备份时一次性写回所有用户偏好。
     * 设备相关标记（通知授权、备份目录、首次引导等）不覆盖。
     */
    suspend fun applyBackup(settings: AppSettings) {
        dataStore.edit { prefs ->
            prefs[Keys.KEEP_ALIVE] = settings.keepAliveEnabled
            prefs[Keys.OVERLAY_HIDDEN] = settings.overlayHidden
            prefs[Keys.OVERLAY_MOVE_MINUTES] = settings.overlayMoveMinutes
            prefs[Keys.OVERLAY_ALPHA] = settings.overlayAlphaSafe
            prefs[Keys.REMINDER_RULES] = ReminderRuleCodec.encode(settings.reminderRules)
            prefs[Keys.REMINDER_COOLDOWN] = settings.reminderCooldownMinutes
            prefs[Keys.QUIET_ENABLED] = settings.quietHoursEnabled
            prefs[Keys.QUIET_START] = settings.quietStartMinute
            prefs[Keys.QUIET_END] = settings.quietEndMinute
            prefs[Keys.MEDIA_SILENCE] = settings.mediaSilenceEnabled
            prefs[Keys.DAILY_FULL_XP] = settings.dailyFullXpCount
            prefs[Keys.DUAL_REMINDER] = settings.dualReminderEnabled
            prefs[Keys.RATED_CAPACITY] = settings.ratedCapacityMah
            // 极客体验期间备份里的旧值也不能把 1 秒采样关掉。
            prefs[Keys.BATTERY_HIGH_FREQ] =
                if (CapsuleVersion.fromName(prefs[Keys.VERSION]).recordsBatteryTimeline) {
                    true
                } else {
                    settings.batteryHighFrequencyAlways
                }
            prefs[Keys.GEEK_SAMPLING_OTHER] = settings.geekSamplingInOtherVersions
            prefs[Keys.PARTNER_APPEARANCE] =
                settings.partnerAppearance.map { it.name }.toSet()
            prefs[Keys.TODO_LAYOUT] = settings.todoLayout.name
            prefs[Keys.TODO_RANGE_DAYS] = settings.todoCustomRangeDays
            prefs[Keys.OVERDUE_POLICY] = settings.overduePolicy.name
            prefs[Keys.HIDE_FINISHED_AFTER_DAY] = settings.hideFinishedAfterDay
            prefs[Keys.METRIC_OVERLAY_ENABLED] = settings.metricOverlayEnabled
            prefs[Keys.METRIC_OVERLAY_ALPHA] = settings.metricOverlayAlphaSafe
            prefs[Keys.METRIC_OVERLAY_SCALE] = settings.metricOverlayScaleSafe
            prefs[Keys.METRIC_OVERLAY_TEMP] = settings.metricOverlayShowTemp
            prefs[Keys.METRIC_OVERLAY_BATTERY] = settings.metricOverlayShowBattery
            prefs[Keys.METRIC_OVERLAY_FPS] = settings.metricOverlayShowFps
            prefs[Keys.METRIC_OVERLAY_MEMORY] = settings.metricOverlayShowMemory
            prefs[Keys.METRIC_OVERLAY_HORIZONTAL] = settings.metricOverlayHorizontal
            prefs[Keys.HIDDEN_BACKGROUND] = settings.hiddenBackground
            prefs[Keys.CUSTOM_CATEGORIES] = settings.customCategories
            prefs[Keys.CATEGORY_LAYOUT] = CategoryCatalog.encodeLayout(settings.categoryLayout)
        }
    }

    private fun decodeTimes(raw: String?): MutableMap<String, Long> {
        if (raw.isNullOrBlank()) return mutableMapOf()
        return raw.split(';')
            .mapNotNull { entry ->
                val parts = entry.split('=')
                if (parts.size != 2) return@mapNotNull null
                val value = parts[1].toLongOrNull() ?: return@mapNotNull null
                parts[0] to value
            }
            .toMap()
            .toMutableMap()
    }

    private fun encodeTimes(times: Map<String, Long>): String =
        times.entries.joinToString(";") { "${it.key}=${it.value}" }

    /** 编码为 `ruleId=dayEpoch:count;...`。 */
    private fun decodeCounts(raw: String?): MutableMap<String, Pair<Long, Int>> {
        if (raw.isNullOrBlank()) return mutableMapOf()
        return raw.split(';')
            .mapNotNull { entry ->
                val parts = entry.split('=')
                if (parts.size != 2) return@mapNotNull null
                val values = parts[1].split(':')
                if (values.size != 2) return@mapNotNull null
                val day = values[0].toLongOrNull() ?: return@mapNotNull null
                val count = values[1].toIntOrNull() ?: return@mapNotNull null
                parts[0] to (day to count)
            }
            .toMap()
            .toMutableMap()
    }

    private fun encodeCounts(counts: Map<String, Pair<Long, Int>>): String =
        counts.entries.joinToString(";") { "${it.key}=${it.value.first}:${it.value.second}" }

    private companion object {
        const val EVENT_COOLDOWN_MILLIS = 30 * 60_000L
        const val APP_EVENT_COOLDOWN_MILLIS = 60_000L
        const val CALENDAR_EVENT_COOLDOWN_MILLIS = 6 * 60 * 60_000L
        const val TASK_CLEANUP_MILLIS = 24 * 60 * 60_000L

        /** 1.5.27 及更早版本里「屏幕反光」的旧枚举名。 */
        const val LEGACY_SCREEN_REFLECTION = "RIM_LIGHT"
    }
}
