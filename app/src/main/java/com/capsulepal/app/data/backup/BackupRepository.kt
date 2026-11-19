package com.capsulepal.app.data.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.capsulepal.app.data.db.AppPowerRecordEntity
import com.capsulepal.app.data.db.BatteryDao
import com.capsulepal.app.data.db.BatteryHealthRecordEntity
import com.capsulepal.app.data.db.BatterySampleEntity
import com.capsulepal.app.data.db.BatterySessionEntity
import com.capsulepal.app.data.db.ChargeSessionEntity
import com.capsulepal.app.data.db.DischargeSessionEntity
import com.capsulepal.app.data.model.AppSettings
import com.capsulepal.app.data.model.CategoryCatalog
import com.capsulepal.app.data.model.OverduePolicy
import com.capsulepal.app.data.model.Plan
import com.capsulepal.app.data.model.PlanColor
import com.capsulepal.app.data.model.PlanType
import com.capsulepal.app.data.model.Priority
import com.capsulepal.app.data.model.ReminderRuleCodec
import com.capsulepal.app.data.model.RepeatRule
import com.capsulepal.app.data.model.Task
import com.capsulepal.app.data.model.TaskCategory
import com.capsulepal.app.data.model.TaskGroup
import com.capsulepal.app.data.model.TaskKind
import com.capsulepal.app.data.model.TaskStatus
import com.capsulepal.app.data.model.TaskStep
import com.capsulepal.app.data.model.TodoLayout
import com.capsulepal.app.data.repo.PartnerBackup
import com.capsulepal.app.data.repo.PartnerRepository
import com.capsulepal.app.data.repo.PlanRepository
import com.capsulepal.app.data.repo.BatteryRepository
import com.capsulepal.app.data.repo.GeekRepository
import com.capsulepal.app.data.repo.SettingsRepository
import com.capsulepal.app.data.repo.TaskBackupItem
import com.capsulepal.app.data.repo.TaskRepository
import com.capsulepal.app.partner.PartnerAppearance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/** 备份的三种触发方式：快速（点「今天」页气泡）、自动（新增数据时）、手动（设置页按钮）。 */
enum class BackupKind { QUICK, AUTO, MANUAL }

data class BackupImportResult(val taskCount: Int, val ruleCount: Int)

/**
 * 本地备份：把伙伴成长、待办与日程、用户主动提醒规则写成一个 JSON 文件。
 *
 * 三种备份共用用户在设置里选定的目录（SAF 持久化授权），
 * 各自固定一个文件名：再次备份时直接覆盖上一次的同类型备份，不会不断堆积新文件。
 */
class BackupRepository(
    context: Context,
    private val taskRepository: TaskRepository,
    private val planRepository: PlanRepository,
    private val partnerRepository: PartnerRepository,
    private val settingsRepository: SettingsRepository,
    private val batteryDao: BatteryDao,
    private val batteryRepository: BatteryRepository,
    private val geekRepository: GeekRepository,
    private val onRestored: suspend () -> Unit
) {

    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val mutex = Mutex()
    private val appVersion: String = runCatching {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
    }.getOrNull().orEmpty()

    /** 记录用户选择的备份目录，并把这个目录的读写权限持久化下来。 */
    suspend fun setDirectory(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            resolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            settingsRepository.setBackupDirUri(uri.toString())
            true
        }.getOrDefault(false)
    }

    suspend fun hasDirectory(): Boolean =
        settingsRepository.settings.first().backupDirUri.isNotBlank()

    suspend fun export(kind: BackupKind): Result<String> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val dir = settingsRepository.settings.first().backupDirUri
                if (dir.isBlank()) {
                    throw IllegalStateException("请先在设置里选择备份保存位置")
                }
                val root = Uri.parse(dir)
                val name = fileName(kind)
                val json = buildJson()
                // createDocument 的 parent 必须是 document URI；直接传 tree URI 会抛 Invalid URI。
                val parent = if (DocumentsContract.isTreeUri(root)) {
                    DocumentsContract.buildDocumentUriUsingTree(
                        root,
                        DocumentsContract.getTreeDocumentId(root)
                    )
                } else {
                    root
                }
                val document = findChild(root, name)
                    ?: DocumentsContract.createDocument(resolver, parent, MIME_JSON, name)
                    ?: throw IllegalStateException("无法在所选目录创建文件")
                writeText(document, json)
                if (kind == BackupKind.MANUAL) deleteLegacyManualBackups(root, document)
                name
            }
        }
    }

    suspend fun import(uri: Uri): Result<BackupImportResult> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val raw = resolver.openInputStream(uri)
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.use { it.readText() }
                    ?: throw IllegalStateException("无法读取所选文件")
                val root = JSONObject(raw)
                val version = root.optInt("schemaVersion", 0)
                if (version !in 1..SCHEMA_VERSION) {
                    throw IllegalStateException("备份文件版本不支持")
                }
                val partnerJson = root.optJSONObject("partner")
                    ?: throw IllegalStateException("备份文件缺少伙伴数据")
                val tasksJson = root.optJSONArray("tasks")
                    ?: throw IllegalStateException("备份文件缺少待办数据")
                val tasks = (0 until tasksJson.length()).mapNotNull { index ->
                    tasksJson.optJSONObject(index)?.let(::parseTask)
                }
                // schemaVersion 1 没有计划数据，按空列表处理即可。
                val plansJson = root.optJSONArray("plans")
                val plans = plansJson?.let { array ->
                    (0 until array.length()).mapNotNull { index ->
                        array.optJSONObject(index)?.let(::parsePlan)
                    }
                }.orEmpty()
                // schemaVersion 3 起包含分组；旧备份没有该字段时保留本机现有分组。
                val groupsJson = root.optJSONArray("groups")
                val groups = groupsJson?.let { array ->
                    (0 until array.length()).mapNotNull { index ->
                        array.optJSONObject(index)?.let(::parseGroup)
                    }
                }
                val partner = PartnerBackup(
                    name = partnerJson.optString("name", "小胶囊"),
                    totalXp = partnerJson.optInt("totalXp", 0),
                    completedTotal = partnerJson.optInt("completedTotal", 0),
                    completedToday = partnerJson.optInt("completedToday", 0),
                    lastCompletionEpochDay = partnerJson.optLong("lastCompletionEpochDay", 0L),
                    companionSinceEpochDay = partnerJson.optLong("companionSinceEpochDay", 0L)
                )
                val rules = ReminderRuleCodec.decode(root.optString("rules"))
                val currentSettings = settingsRepository.settings.first()
                val settingsJson = root.optJSONObject("settings")

                taskRepository.replaceAll(tasks)
                groups?.let { taskRepository.replaceGroups(it) }
                planRepository.replaceAll(plans)
                partnerRepository.restore(partner)
                // schemaVersion 5 起包含完整用户偏好；旧备份保留本机设置。
                settingsJson?.let {
                    settingsRepository.applyBackup(parseSettings(it, currentSettings))
                }
                if (rules != null) settingsRepository.setReminderRules(rules)
                // schemaVersion 6 起包含电池数据；旧备份保留本机已有电池数据。
                root.optJSONObject("battery")?.let { restoreBattery(it) }
                onRestored()
                BackupImportResult(taskCount = tasks.size, ruleCount = rules?.size ?: 0)
            }
        }
    }

    private suspend fun buildJson(): String {
        val partner = partnerRepository.snapshot()
        val tasks = taskRepository.backupItems()
        val plans = planRepository.plans()
        val groups = taskRepository.groups()
        val settings = settingsRepository.settings.first()
        val rules = settings.reminderRules
        return JSONObject().apply {
            put("schemaVersion", SCHEMA_VERSION)
            put("exportedAt", System.currentTimeMillis())
            put("appVersion", appVersion)
            put(
                "partner",
                JSONObject().apply {
                    put("name", partner.name)
                    put("totalXp", partner.totalXp)
                    put("completedTotal", partner.completedTotal)
                    put("completedToday", partner.completedToday)
                    put("lastCompletionEpochDay", partner.lastCompletionEpochDay)
                    put("companionSinceEpochDay", partner.companionSinceEpochDay)
                }
            )
            put(
                "tasks",
                JSONArray().apply {
                    tasks.forEach { put(taskToJson(it)) }
                }
            )
            put(
                "plans",
                JSONArray().apply {
                    plans.forEach { put(planToJson(it)) }
                }
            )
            put(
                "groups",
                JSONArray().apply {
                    groups.forEach { group ->
                        put(
                            JSONObject().apply {
                                put("id", group.id)
                                put("name", group.name)
                                put("sortOrder", group.sortOrder)
                            }
                        )
                    }
                }
            )
            put("rules", ReminderRuleCodec.encode(rules))
            put("settings", settingsToJson(settings))
            put("battery", batteryToJson())
        }.toString(2)
    }

    /** 电池明细只备份近 7 天；进行中的会话按最后心跳落成已结束，避免恢复后半开。 */
    private suspend fun batteryToJson(): JSONObject {
        val from = System.currentTimeMillis() - BATTERY_DETAIL_WINDOW_MILLIS
        val sessions = batteryDao.chargeSessionsSince(from)
        val health = batteryDao.healthRecordsSince(from)
        val discharge = batteryDao.dischargeSessionsSince(from)
        val geekCharge = batteryDao.geekChargeSessionsSince(from)
        val samples = batteryDao.samplesSince(from)
        val appPower = batteryDao.appPowerSince(
            LocalDate.now().minusDays(BATTERY_DETAIL_DAYS - 1L).toEpochDay()
        )
        return JSONObject().apply {
            put(
                "sessions",
                JSONArray().apply {
                    sessions.forEach { session ->
                        put(
                            JSONObject().apply {
                                put("id", session.id)
                                put("kind", session.kind)
                                put("startTs", session.startTs)
                                put("endTs", if (session.endTs > 0L) session.endTs else session.lastTs)
                                put("lastTs", session.lastTs)
                                put("startPct", session.startPct)
                                put("endPct", session.endPct)
                                put("startMah", session.startMah)
                                put("endMah", session.endMah)
                                put("peakPowerW", session.peakPowerW.toDouble())
                                put("fullQuiet", session.fullQuiet)
                            }
                        )
                    }
                }
            )
            put(
                "health",
                JSONArray().apply {
                    health.forEach { record ->
                        put(
                            JSONObject().apply {
                                put("id", record.id)
                                put("ts", record.ts)
                                put("sessionId", record.sessionId)
                                put("kind", record.kind)
                                put("weight", record.weight)
                                put("startMah", record.startMah)
                                put("startPct", record.startPct)
                                put("endMah", record.endMah)
                                put("endPct", record.endPct)
                                put("quiet", record.quiet)
                                put("estimator", record.estimator)
                            }
                        )
                    }
                }
            )
            put(
                "discharge",
                JSONArray().apply {
                    discharge.forEach { session ->
                        put(
                            JSONObject().apply {
                                put("id", session.id)
                                put("startTs", session.startTs)
                                put("endTs", if (session.endTs > 0L) session.endTs else session.lastTs)
                                put("lastTs", session.lastTs)
                                put("startPct", session.startPct)
                                put("endPct", session.endPct)
                                put("startMah", session.startMah)
                                put("endMah", session.endMah)
                                put("avgPowerW", session.avgPowerW.toDouble())
                            }
                        )
                    }
                }
            )
            put(
                "geekCharge",
                JSONArray().apply {
                    geekCharge.forEach { session ->
                        put(
                            JSONObject().apply {
                                put("id", session.id)
                                put("startTs", session.startTs)
                                put("endTs", if (session.endTs > 0L) session.endTs else session.lastTs)
                                put("lastTs", session.lastTs)
                                put("startPct", session.startPct)
                                put("endPct", session.endPct)
                                put("startMah", session.startMah)
                                put("endMah", session.endMah)
                                put("avgPowerW", session.avgPowerW.toDouble())
                                put("peakPowerW", session.peakPowerW.toDouble())
                            }
                        )
                    }
                }
            )
            put(
                "samples",
                JSONArray().apply {
                    samples.forEach { sample ->
                        put(
                            JSONObject().apply {
                                put("ts", sample.ts)
                                put("levelPct", sample.levelPct)
                                put("tempC", sample.tempC.toDouble())
                                put("currentMa", sample.currentMa)
                                put("powerW", sample.powerW.toDouble())
                            }
                        )
                    }
                }
            )
            put(
                "appPower",
                JSONArray().apply {
                    appPower.forEach { record ->
                        put(
                            JSONObject().apply {
                                put("epochDay", record.epochDay)
                                put("packageName", record.packageName)
                                put("label", record.label)
                                put("foregroundMinutes", record.foregroundMinutes)
                                put("avgTempC", record.avgTempC.toDouble())
                                put("maxTempC", record.maxTempC.toDouble())
                                put("powerMah", record.powerMah.toDouble())
                            }
                        )
                    }
                }
            )
        }
    }

    private suspend fun restoreBattery(json: JSONObject) {
        geekRepository.clearAll()
        batteryDao.clearSessions()
        batteryDao.clearHealthRecords()
        batteryRepository.resetRuntime()
        json.optJSONArray("sessions")?.let { array ->
            val sessions = (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                BatterySessionEntity(
                    id = item.optLong("id", 0L),
                    kind = item.optString("kind", "CHARGE"),
                    startTs = item.optLong("startTs", 0L),
                    endTs = item.optLong("endTs", 0L),
                    lastTs = item.optLong("lastTs", 0L),
                    startPct = item.optInt("startPct", 0),
                    endPct = item.optInt("endPct", 0),
                    startMah = item.optInt("startMah", 0),
                    endMah = item.optInt("endMah", 0),
                    peakPowerW = item.optDouble("peakPowerW", 0.0).toFloat(),
                    fullQuiet = item.optBoolean("fullQuiet", false)
                )
            }
            if (sessions.isNotEmpty()) batteryDao.insertSessions(sessions)
        }
        json.optJSONArray("health")?.let { array ->
            val records = (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                BatteryHealthRecordEntity(
                    id = item.optLong("id", 0L),
                    ts = item.optLong("ts", 0L),
                    sessionId = item.optLong("sessionId", 0L),
                    kind = item.optString("kind", "CHARGE"),
                    weight = item.optInt("weight", 0),
                    startMah = item.optInt("startMah", 0),
                    startPct = item.optInt("startPct", 0),
                    endMah = item.optInt("endMah", 0),
                    endPct = item.optInt("endPct", 0),
                    quiet = item.optBoolean("quiet", false),
                    estimator = item.optString("estimator", "LEGACY")
                )
            }
            if (records.isNotEmpty()) batteryDao.insertHealthRecords(records)
        }
        json.optJSONArray("discharge")?.let { array ->
            val sessions = (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                DischargeSessionEntity(
                    id = item.optLong("id", 0L),
                    startTs = item.optLong("startTs", 0L),
                    endTs = item.optLong("endTs", 0L),
                    lastTs = item.optLong("lastTs", 0L),
                    startPct = item.optInt("startPct", 0),
                    endPct = item.optInt("endPct", 0),
                    startMah = item.optInt("startMah", 0),
                    endMah = item.optInt("endMah", 0),
                    avgPowerW = item.optDouble("avgPowerW", 0.0).toFloat()
                )
            }
            if (sessions.isNotEmpty()) batteryDao.insertDischargeSessions(sessions)
        }
        json.optJSONArray("geekCharge")?.let { array ->
            val sessions = (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                ChargeSessionEntity(
                    id = item.optLong("id", 0L),
                    startTs = item.optLong("startTs", 0L),
                    endTs = item.optLong("endTs", 0L),
                    lastTs = item.optLong("lastTs", 0L),
                    startPct = item.optInt("startPct", 0),
                    endPct = item.optInt("endPct", 0),
                    startMah = item.optInt("startMah", 0),
                    endMah = item.optInt("endMah", 0),
                    avgPowerW = item.optDouble("avgPowerW", 0.0).toFloat(),
                    peakPowerW = item.optDouble("peakPowerW", 0.0).toFloat()
                )
            }
            if (sessions.isNotEmpty()) batteryDao.insertChargeSessions(sessions)
        }
        json.optJSONArray("samples")?.let { array ->
            val samples = (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                BatterySampleEntity(
                    ts = item.optLong("ts", 0L),
                    levelPct = item.optInt("levelPct", 0),
                    tempC = item.optDouble("tempC", 0.0).toFloat(),
                    currentMa = item.optInt("currentMa", 0),
                    powerW = item.optDouble("powerW", 0.0).toFloat()
                )
            }
            if (samples.isNotEmpty()) batteryDao.insertSamples(samples)
        }
        json.optJSONArray("appPower")?.let { array ->
            val records = (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                AppPowerRecordEntity(
                    epochDay = item.optLong("epochDay", 0L),
                    packageName = item.optString("packageName"),
                    label = item.optString("label"),
                    foregroundMinutes = item.optInt("foregroundMinutes", 0),
                    avgTempC = item.optDouble("avgTempC", -1.0).toFloat(),
                    maxTempC = item.optDouble("maxTempC", -1.0).toFloat(),
                    powerMah = item.optDouble("powerMah", 0.0).toFloat()
                )
            }
            if (records.isNotEmpty()) batteryDao.insertAppPowerRecords(records)
        }
    }

    /** 用户偏好（不含通知授权、备份目录、首次引导等设备状态）。 */
    private fun settingsToJson(settings: AppSettings): JSONObject = JSONObject().apply {
        put("keepAliveEnabled", settings.keepAliveEnabled)
        put("overlayHidden", settings.overlayHidden)
        put("overlayMoveMinutes", settings.overlayMoveMinutes)
        put("overlayAlphaPercent", settings.overlayAlphaPercent)
        put("reminderCooldownMinutes", settings.reminderCooldownMinutes)
        put("quietHoursEnabled", settings.quietHoursEnabled)
        put("quietStartMinute", settings.quietStartMinute)
        put("quietEndMinute", settings.quietEndMinute)
        put("mediaSilenceEnabled", settings.mediaSilenceEnabled)
        put("dailyFullXpCount", settings.dailyFullXpCount)
        put("dualReminderEnabled", settings.dualReminderEnabled)
        put("ratedCapacityMah", settings.ratedCapacityMah)
        put("batteryCells", settings.batteryCells)
        put("batteryHighFrequencyAlways", settings.batteryHighFrequencyAlways)
        put("geekSamplingInOtherVersions", settings.geekSamplingInOtherVersions)
        put(
            "partnerAppearance",
            JSONArray().apply { settings.partnerAppearance.forEach { put(it.name) } }
        )
        put("todoLayout", settings.todoLayout.name)
        put("todoCustomRangeDays", settings.todoCustomRangeDays)
        put("overduePolicy", settings.overduePolicy.name)
        put("hideFinishedAfterDay", settings.hideFinishedAfterDay)
        put("metricOverlayEnabled", settings.metricOverlayEnabled)
        put("metricOverlayAlphaPercent", settings.metricOverlayAlphaPercent)
        put("metricOverlayScalePercent", settings.metricOverlayScalePercent)
        put("metricOverlayShowTemp", settings.metricOverlayShowTemp)
        put("metricOverlayShowBattery", settings.metricOverlayShowBattery)
        put("metricOverlayShowFps", settings.metricOverlayShowFps)
        put("metricOverlayShowMemory", settings.metricOverlayShowMemory)
        put(
            "customCategories",
            JSONArray().apply { settings.customCategories.forEach { put(it) } }
        )
        put("categoryLayout", CategoryCatalog.encodeLayout(settings.categoryLayout))
    }

    private fun parseSettings(json: JSONObject, base: AppSettings): AppSettings {
        val customCategories = json.optJSONArray("customCategories")?.let { array ->
            (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
                .toSet()
        } ?: base.customCategories
        val appearance = json.optJSONArray("partnerAppearance")?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                runCatching { PartnerAppearance.valueOf(array.optString(index)) }.getOrNull()
            }.toSet()
        }?.takeIf { it.isNotEmpty() } ?: base.partnerAppearance
        return base.copy(
            keepAliveEnabled = json.optBoolean("keepAliveEnabled", base.keepAliveEnabled),
            overlayHidden = json.optBoolean("overlayHidden", base.overlayHidden),
            overlayMoveMinutes = json.optInt("overlayMoveMinutes", base.overlayMoveMinutes),
            overlayAlphaPercent = json.optInt("overlayAlphaPercent", base.overlayAlphaPercent),
            reminderCooldownMinutes = json.optInt(
                "reminderCooldownMinutes",
                base.reminderCooldownMinutes
            ),
            quietHoursEnabled = json.optBoolean("quietHoursEnabled", base.quietHoursEnabled),
            quietStartMinute = json.optInt("quietStartMinute", base.quietStartMinute),
            quietEndMinute = json.optInt("quietEndMinute", base.quietEndMinute),
            mediaSilenceEnabled = json.optBoolean(
                "mediaSilenceEnabled",
                base.mediaSilenceEnabled
            ),
            dailyFullXpCount = json.optInt("dailyFullXpCount", base.dailyFullXpCount),
            dualReminderEnabled = json.optBoolean(
                "dualReminderEnabled",
                base.dualReminderEnabled
            ),
            ratedCapacityMah = json.optInt("ratedCapacityMah", base.ratedCapacityMah),
            batteryCells = json.optInt("batteryCells", base.batteryCells).coerceIn(0, 2),
            batteryHighFrequencyAlways = json.optBoolean(
                "batteryHighFrequencyAlways",
                base.batteryHighFrequencyAlways
            ),
            geekSamplingInOtherVersions = json.optBoolean(
                "geekSamplingInOtherVersions",
                base.geekSamplingInOtherVersions
            ),
            partnerAppearance = appearance,
            todoLayout = runCatching {
                TodoLayout.valueOf(json.optString("todoLayout", base.todoLayout.name))
            }.getOrDefault(base.todoLayout),
            todoCustomRangeDays = json.optInt(
                "todoCustomRangeDays",
                base.todoCustomRangeDays
            ),
            overduePolicy = runCatching {
                OverduePolicy.valueOf(json.optString("overduePolicy", base.overduePolicy.name))
            }.getOrDefault(base.overduePolicy),
            hideFinishedAfterDay = json.optBoolean(
                "hideFinishedAfterDay",
                base.hideFinishedAfterDay
            ),
            metricOverlayAlphaPercent = json.optInt(
                "metricOverlayAlphaPercent",
                base.metricOverlayAlphaPercent
            ),
            metricOverlayEnabled = json.optBoolean(
                "metricOverlayEnabled",
                base.metricOverlayEnabled
            ),
            metricOverlayScalePercent = json.optInt(
                "metricOverlayScalePercent",
                base.metricOverlayScalePercent
            ),
            metricOverlayShowTemp = json.optBoolean(
                "metricOverlayShowTemp",
                base.metricOverlayShowTemp
            ),
            metricOverlayShowBattery = json.optBoolean(
                "metricOverlayShowBattery",
                base.metricOverlayShowBattery
            ),
            metricOverlayShowFps = json.optBoolean(
                "metricOverlayShowFps",
                base.metricOverlayShowFps
            ),
            metricOverlayShowMemory = json.optBoolean(
                "metricOverlayShowMemory",
                base.metricOverlayShowMemory
            ),
            customCategories = customCategories,
            categoryLayout = CategoryCatalog.parseLayout(
                json.optString("categoryLayout").takeIf { it.isNotBlank() },
                CategoryCatalog.defaultOrder,
                customCategories
            )
        )
    }

    private fun planToJson(plan: Plan): JSONObject = JSONObject().apply {
        put("id", plan.id)
        put("title", plan.title)
        put("note", plan.note)
        put("color", plan.color.name)
        put("type", plan.type.name)
        put("startDate", plan.startDate?.toString() ?: "")
        put("targetDate", plan.targetDate?.toString() ?: "")
        put("createdAtEpochDay", plan.createdAtEpochDay)
        put("archived", plan.archived)
    }

    private fun parsePlan(json: JSONObject): Plan? {
        val title = json.optString("title").trim()
        if (title.isEmpty()) return null
        val targetDate = json.optString("targetDate")
            .takeIf { it.isNotBlank() }
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val startDate = json.optString("startDate")
            .takeIf { it.isNotBlank() }
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        // schemaVersion 4 起才有 type；旧备份按「有目标日期即周期计划」推断。
        val type = runCatching { PlanType.valueOf(json.optString("type")) }.getOrNull()
            ?: if (targetDate != null) PlanType.PERIOD else PlanType.REPEAT
        val createdAt = json.optLong("createdAtEpochDay", LocalDate.now().toEpochDay())
        return Plan(
            id = json.optLong("id", 0L),
            title = title,
            note = json.optString("note"),
            color = runCatching { PlanColor.valueOf(json.optString("color")) }
                .getOrDefault(PlanColor.CORAL),
            type = type,
            startDate = startDate
                ?: if (type == PlanType.PERIOD) LocalDate.ofEpochDay(createdAt) else null,
            targetDate = targetDate,
            createdAtEpochDay = createdAt,
            archived = json.optBoolean("archived", false)
        )
    }

    private fun parseGroup(json: JSONObject): TaskGroup? {
        val name = json.optString("name").trim()
        if (name.isEmpty()) return null
        return TaskGroup(
            id = json.optLong("id", 0L),
            name = name,
            sortOrder = json.optInt("sortOrder", 0)
        )
    }

    private fun taskToJson(item: TaskBackupItem): JSONObject {
        val task = item.task
        return JSONObject().apply {
            put("id", task.id)
            put("title", task.title)
            put("shortTitle", task.shortTitle)
            put("date", task.date.toString())
            put("startMinute", task.startTime.hour * 60 + task.startTime.minute)
            put("durationMinutes", task.durationMinutes)
            put("prepareMinutes", task.prepareMinutes)
            put("category", task.category.name)
            put("customCategory", task.customCategory)
            put("priority", task.priority.name)
            put("status", task.status.name)
            put("note", task.note)
            put("repeat", task.repeat.name)
            put(
                "repeatDays",
                JSONArray().apply { task.repeatDays.sortedBy { it.value }.forEach { put(it.value) } }
            )
            put("repeatCount", task.repeatCount)
            put("skipHolidays", task.skipHolidays)
            put("seriesId", task.seriesId)
            put("awardedXp", item.awardedXp)
            put("fromTemplate", task.fromTemplate)
            put("groupId", task.groupId)
            put("planId", task.planId)
            put("kind", task.kind.name)
            put(
                "steps",
                JSONArray().apply {
                    task.steps.sortedBy { it.order }.forEach { step ->
                        put(
                            JSONObject().apply {
                                put("order", step.order)
                                put("title", step.title)
                                put("durationMinutes", step.durationMinutes)
                            }
                        )
                    }
                }
            )
        }
    }

    private fun parseTask(json: JSONObject): TaskBackupItem? {
        val title = json.optString("title").trim()
        if (title.isEmpty()) return null
        val date = runCatching { LocalDate.parse(json.optString("date")) }.getOrNull()
            ?: return null
        val startMinute = json.optInt("startMinute", 0).coerceIn(0, 24 * 60 - 1)
        val repeatDays = json.optJSONArray("repeatDays")?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                runCatching { DayOfWeek.of(array.optInt(index)) }.getOrNull()
            }.toSet()
        } ?: emptySet()
        val steps = json.optJSONArray("steps")?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                val step = array.optJSONObject(index) ?: return@mapNotNull null
                TaskStep(
                    order = step.optInt("order", index),
                    title = step.optString("title"),
                    durationMinutes = step.optInt("durationMinutes", 0)
                )
            }
        } ?: emptyList()
        val task = Task(
            id = json.optLong("id", 0L),
            title = title,
            shortTitle = json.optString("shortTitle"),
            date = date,
            startTime = LocalTime.ofSecondOfDay(startMinute * 60L),
            durationMinutes = json.optInt("durationMinutes", 30).coerceAtLeast(1),
            prepareMinutes = json.optInt("prepareMinutes", 0).coerceAtLeast(0),
            category = runCatching { TaskCategory.valueOf(json.optString("category")) }
                .getOrDefault(TaskCategory.OTHER),
            customCategory = json.optString("customCategory"),
            priority = runCatching { Priority.valueOf(json.optString("priority")) }
                .getOrDefault(Priority.NORMAL),
            status = runCatching { TaskStatus.valueOf(json.optString("status")) }
                .getOrDefault(TaskStatus.IDLE),
            note = json.optString("note"),
            steps = steps,
            repeat = runCatching { RepeatRule.valueOf(json.optString("repeat")) }
                .getOrDefault(RepeatRule.ONCE),
            repeatDays = repeatDays,
            repeatCount = json.optInt("repeatCount", 0),
            skipHolidays = json.optBoolean("skipHolidays", false),
            seriesId = json.optLong("seriesId", 0L),
            fromTemplate = json.optBoolean("fromTemplate", false),
            groupId = json.optLong("groupId", 0L),
            planId = json.optLong("planId", 0L),
            kind = runCatching { TaskKind.valueOf(json.optString("kind")) }
                .getOrDefault(TaskKind.NORMAL)
        )
        return TaskBackupItem(task = task, awardedXp = json.optInt("awardedXp", 0))
    }

    /** 优先用截断模式覆盖写入，个别 provider 不支持时退回普通写模式。 */
    private fun writeText(document: Uri, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val stream = runCatching { resolver.openOutputStream(document, "wt") }.getOrNull()
            ?: resolver.openOutputStream(document, "w")
            ?: throw IllegalStateException("无法写入备份文件")
        stream.use {
            it.write(bytes)
            it.flush()
        }
    }

    private fun fileName(kind: BackupKind): String = when (kind) {
        BackupKind.QUICK -> "胶囊伙伴-快速备份.json"
        BackupKind.AUTO -> "胶囊伙伴-自动备份.json"
        BackupKind.MANUAL -> "胶囊伙伴-手动备份.json"
    }

    /** 旧版本按时间戳生成的手动备份会一直堆着，写入新文件后顺手清掉，只保留当前这一份。 */
    private fun deleteLegacyManualBackups(root: Uri, keep: Uri) {
        runCatching {
            val treeId = DocumentsContract.getTreeDocumentId(root)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(root, treeId)
            val legacy = mutableListOf<Uri>()
            resolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
                ),
                null,
                null,
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(1) ?: continue
                    if (!LEGACY_MANUAL_NAME.matches(name)) continue
                    val uri = DocumentsContract.buildDocumentUriUsingTree(root, cursor.getString(0))
                    if (uri != keep) legacy += uri
                }
            }
            legacy.forEach { uri -> runCatching { DocumentsContract.deleteDocument(resolver, uri) } }
        }
    }

    /** 同名备份直接覆盖，避免目录里堆满重复文件。 */
    private fun findChild(root: Uri, name: String): Uri? = runCatching {
        val treeId = DocumentsContract.getTreeDocumentId(root)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(root, treeId)
        resolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            ),
            null,
            null,
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == name) {
                    return DocumentsContract.buildDocumentUriUsingTree(root, cursor.getString(0))
                }
            }
        }
        null
    }.getOrNull()

    private companion object {
        const val SCHEMA_VERSION = 9
        const val MIME_JSON = "application/json"

        /** 电池明细备份窗口：近 7 天。 */
        const val BATTERY_DETAIL_DAYS = 7L
        const val BATTERY_DETAIL_WINDOW_MILLIS = BATTERY_DETAIL_DAYS * 24 * 60 * 60_000L

        /** 1.0.0 及更早版本生成的「手动备份-日期-时间.json」，写入新备份时清理掉。 */
        val LEGACY_MANUAL_NAME = Regex("""胶囊伙伴-手动备份-\d{8}-\d{6}\.json""")
    }
}
