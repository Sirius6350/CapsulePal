package com.capsulepal.app.data.repo

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.view.inputmethod.InputMethodManager
import com.capsulepal.app.data.db.AppPowerRecordEntity
import com.capsulepal.app.data.db.BatteryDao
import com.capsulepal.app.data.db.BatterySampleEntity
import com.capsulepal.app.data.db.ChargeSessionEntity
import com.capsulepal.app.data.db.DischargeSessionEntity
import com.capsulepal.app.monitor.BatteryCalc
import com.capsulepal.app.monitor.BatteryReading
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max

/** 极客页展示的一条应用功耗估算（Scene 风格：功耗 / 温度 / 时长）。 */
data class AppPowerItem(
    val packageName: String,
    val label: String,
    val foregroundMinutes: Int,
    val powerMah: Float,
    /** 使用期间的平均功耗（W）。 */
    val avgPowerW: Float,
    /** 使用期间的平均电池温度（℃）；无采样时为 null。 */
    val avgTempC: Float?,
    /** 使用期间的最高电池温度（℃）；无采样时为 null。 */
    val maxTempC: Float?
)

/**
 * 极客版专属数据：放电会话、24 小时电池采样、应用功耗历史。
 *
 * 非极客版调用时只做「收尾」：把仍在进行的放电会话关掉，之后不再产生新数据；
 * 已有数据原样保留，切回极客版后从当天继续。
 */
class GeekRepository(
    context: Context,
    private val dao: BatteryDao
) {

    private val appContext = context.applicationContext

    private var activeDischarge: DischargeSessionEntity? = null
    private var dischargeLoaded = false
    private var avgPowerW = 0f
    private var activeCharge: ChargeSessionEntity? = null
    private var chargeLoaded = false
    private var chargeAvgPowerW = 0f
    private var chargePeakPowerW = 0f
    private var lastSampleTs = 0L
    private var lastAppPowerRefresh = 0L
    private var lastPruneTs = 0L
    /** 应用功耗刷新互斥：采样线程与页面刷新可能同时触发，避免同一包重复写库。 */
    private val appPowerMutex = Mutex()
    private var excludedPackageCache: Set<String>? = null

    suspend fun onReading(
        reading: BatteryReading,
        nowMillis: Long,
        ratedCapacityMah: Int,
        recordTimeline: Boolean,
        recordDischarge: Boolean,
        recordAppPower: Boolean
    ) {
        ensureDischargeLoaded(reading, nowMillis)
        ensureChargeLoaded(reading)

        if (recordTimeline && reading.levelPct != null) {
            maybeRecordSample(reading, nowMillis)
        }
        if (recordDischarge) {
            updateDischarge(reading, nowMillis)
            updateCharge(reading, nowMillis)
        } else {
            closeDischargeIfOpen(reading, nowMillis)
            closeChargeIfOpen(reading, nowMillis)
        }
        if (recordAppPower && nowMillis - lastAppPowerRefresh >= APP_POWER_REFRESH_MILLIS) {
            lastAppPowerRefresh = nowMillis
            runCatching { refreshAppPower(nowMillis) }
        }
        runCatching { pruneSamplesOccasionally(nowMillis) }
    }

    /** 清理过期的 24 小时采样；1 秒轮询下不必每次都执行 DELETE。 */
    private suspend fun pruneSamplesOccasionally(nowMillis: Long) {
        if (nowMillis - lastPruneTs < PRUNE_INTERVAL_MILLIS) return
        lastPruneTs = nowMillis
        dao.pruneSamples(nowMillis - SAMPLE_RETENTION_MILLIS)
    }

    suspend fun timeline24h(nowMillis: Long = System.currentTimeMillis()): List<BatterySampleEntity> =
        dao.samplesSince(nowMillis - SAMPLE_RETENTION_MILLIS)

    suspend fun recentDischargeSessions(limit: Int = 20): List<DischargeSessionEntity> =
        dao.recentDischargeSessions(limit)

    suspend fun recentChargeSessions(limit: Int = 20): List<ChargeSessionEntity> =
        dao.recentChargeSessions(limit)

    suspend fun clearDischargeSessions() {
        dao.clearDischargeSessions()
        activeDischarge = null
        avgPowerW = 0f
    }

    suspend fun clearChargeSessions() {
        dao.clearChargeSessions()
        activeCharge = null
        chargeAvgPowerW = 0f
        chargePeakPowerW = 0f
    }

    suspend fun appPowerToday(): List<AppPowerItem> =
        dao.appPowerOfDay(LocalDate.now().toEpochDay())
            .distinctBy { it.packageName }
            .filterNot { it.packageName in excludedPackages() }
            .map { it.toItem() }

    /** 应用功耗历史（按天）。 */
    suspend fun appPowerHistory(days: Int = 7): List<AppPowerItem> {
        val from = LocalDate.now().minusDays(days.toLong() - 1).toEpochDay()
        return dao.appPowerSince(from)
            .distinctBy { it.epochDay to it.packageName }
            .filterNot { it.packageName in excludedPackages() }
            .map { it.toItem() }
    }

    suspend fun clearAll() {
        dao.clearDischargeSessions()
        dao.clearChargeSessions()
        dao.clearSamples()
        dao.clearAppPowerRecords()
        activeDischarge = null
        activeCharge = null
        avgPowerW = 0f
        chargeAvgPowerW = 0f
        chargePeakPowerW = 0f
        lastSampleTs = 0L
    }

    // ---- 24 小时采样 ----

    private suspend fun maybeRecordSample(reading: BatteryReading, nowMillis: Long) {
        if (nowMillis - lastSampleTs < SAMPLE_INTERVAL_MILLIS) return
        lastSampleTs = nowMillis
        val power = BatteryCalc.chargePowerW(reading.voltageMv, reading.currentMa) ?: 0f
        dao.insertSample(
            BatterySampleEntity(
                ts = nowMillis,
                levelPct = reading.levelPct ?: return,
                tempC = reading.tempC ?: 0f,
                currentMa = reading.currentMa?.toInt() ?: 0,
                powerW = if (reading.charging) power else -power
            )
        )
    }

    // ---- 放电会话 ----

    private suspend fun ensureDischargeLoaded(reading: BatteryReading, nowMillis: Long) {
        if (dischargeLoaded) return
        dischargeLoaded = true
        val open = dao.openDischargeSession() ?: return
        if (!reading.charging && !reading.plugged && reading.levelPct != null) {
            activeDischarge = open
            avgPowerW = open.avgPowerW
        } else {
            dao.closeDischargeSession(
                id = open.id,
                endTs = open.lastTs,
                endPct = open.endPct,
                endMah = open.endMah,
                avgPowerW = open.avgPowerW
            )
        }
    }

    private suspend fun updateDischarge(reading: BatteryReading, nowMillis: Long) {
        val level = reading.levelPct ?: return
        val mah = reading.chargeCounterMah ?: 0
        val power = BatteryCalc.chargePowerW(reading.voltageMv, reading.currentMa) ?: 0f
        val active = activeDischarge
        if (reading.charging || reading.plugged) {
            if (active != null) {
                closeDischarge(active, nowMillis, level, mah)
            }
            return
        }
        if (active == null) {
            val entity = DischargeSessionEntity(
                startTs = nowMillis,
                endTs = 0L,
                lastTs = nowMillis,
                startPct = level,
                endPct = level,
                startMah = mah,
                endMah = mah,
                avgPowerW = power
            )
            val id = dao.insertDischargeSession(entity)
            activeDischarge = entity.copy(id = id)
            avgPowerW = power
            return
        }
        // 按时间加权更新平均功率，只做内存计算，落库仍保持一分钟一次。
        val span = (nowMillis - active.lastTs).coerceAtLeast(0L)
        val total = (nowMillis - active.startTs).coerceAtLeast(1L)
        val previousSpan = (active.lastTs - active.startTs).coerceAtLeast(0L)
        avgPowerW = ((avgPowerW * previousSpan + power * span) / total).toFloat()
        if (nowMillis - active.lastTs >= TOUCH_INTERVAL_MILLIS) {
            dao.touchDischargeSession(
                id = active.id,
                lastTs = nowMillis,
                endPct = level,
                endMah = mah,
                avgPowerW = avgPowerW
            )
            activeDischarge = active.copy(
                lastTs = nowMillis,
                endPct = level,
                endMah = mah,
                avgPowerW = avgPowerW
            )
        }
    }

    private suspend fun closeDischargeIfOpen(reading: BatteryReading, nowMillis: Long) {
        val active = activeDischarge ?: return
        closeDischarge(
            session = active,
            endTs = nowMillis,
            endPct = reading.levelPct ?: active.endPct,
            endMah = reading.chargeCounterMah ?: active.endMah
        )
    }

    private suspend fun closeDischarge(
        session: DischargeSessionEntity,
        endTs: Long,
        endPct: Int,
        endMah: Int
    ) {
        dao.closeDischargeSession(
            id = session.id,
            endTs = endTs,
            endPct = endPct,
            endMah = endMah,
            avgPowerW = avgPowerW
        )
        activeDischarge = null
        avgPowerW = 0f
    }

    // ---- 充电会话 ----

    private suspend fun ensureChargeLoaded(reading: BatteryReading) {
        if (chargeLoaded) return
        chargeLoaded = true
        val open = dao.openChargeGeekSession() ?: return
        if (reading.charging) {
            activeCharge = open
            chargeAvgPowerW = open.avgPowerW
            chargePeakPowerW = open.peakPowerW
        } else {
            dao.closeChargeSession(
                id = open.id,
                endTs = open.lastTs,
                endPct = open.endPct,
                endMah = open.endMah,
                avgPowerW = open.avgPowerW,
                peakPowerW = open.peakPowerW
            )
        }
    }

    private suspend fun updateCharge(reading: BatteryReading, nowMillis: Long) {
        val level = reading.levelPct ?: return
        val mah = reading.chargeCounterMah ?: 0
        val power = BatteryCalc.chargePowerW(reading.voltageMv, reading.currentMa) ?: 0f
        val active = activeCharge
        if (!reading.charging) {
            if (active != null) {
                closeCharge(active, nowMillis, level, mah)
            }
            return
        }
        if (active == null) {
            val entity = ChargeSessionEntity(
                startTs = nowMillis,
                endTs = 0L,
                lastTs = nowMillis,
                startPct = level,
                endPct = level,
                startMah = mah,
                endMah = mah,
                avgPowerW = power,
                peakPowerW = power
            )
            val id = dao.insertChargeSession(entity)
            activeCharge = entity.copy(id = id)
            chargeAvgPowerW = power
            chargePeakPowerW = power
            return
        }
        chargePeakPowerW = max(chargePeakPowerW, power)
        val span = (nowMillis - active.lastTs).coerceAtLeast(0L)
        val total = (nowMillis - active.startTs).coerceAtLeast(1L)
        val previousSpan = (active.lastTs - active.startTs).coerceAtLeast(0L)
        chargeAvgPowerW = ((chargeAvgPowerW * previousSpan + power * span) / total).toFloat()
        if (nowMillis - active.lastTs >= TOUCH_INTERVAL_MILLIS) {
            dao.touchChargeSession(
                id = active.id,
                lastTs = nowMillis,
                endPct = level,
                endMah = mah,
                avgPowerW = chargeAvgPowerW,
                peakPowerW = chargePeakPowerW
            )
            activeCharge = active.copy(
                lastTs = nowMillis,
                endPct = level,
                endMah = mah,
                avgPowerW = chargeAvgPowerW,
                peakPowerW = chargePeakPowerW
            )
        }
    }

    private suspend fun closeChargeIfOpen(reading: BatteryReading, nowMillis: Long) {
        val active = activeCharge ?: return
        closeCharge(
            session = active,
            endTs = nowMillis,
            endPct = reading.levelPct ?: active.endPct,
            endMah = reading.chargeCounterMah ?: active.endMah
        )
    }

    private suspend fun closeCharge(
        session: ChargeSessionEntity,
        endTs: Long,
        endPct: Int,
        endMah: Int
    ) {
        dao.closeChargeSession(
            id = session.id,
            endTs = endTs,
            endPct = endPct,
            endMah = endMah,
            avgPowerW = chargeAvgPowerW,
            peakPowerW = chargePeakPowerW
        )
        activeCharge = null
        chargeAvgPowerW = 0f
        chargePeakPowerW = 0f
    }

    // ---- 应用功耗估算 ----

    /**
     * Scene 风格的应用功耗（今日 00:00 至今）：
     * - 使用时长取系统聚合的前台时长，避免事件流漏发 PAUSED 时算出「微博 8 小时」；
     * - 平均功耗取该应用前台时段内电池实时放电功率的均值（采样里的 powerW）；
     * - 平均 / 最高温度取同一时段的电池采样；
     * - 时段内没有放电采样（例如全程充电）时退回今天的平均放电功率。
     * 读不到使用情况权限时静默跳过。
     */
    suspend fun refreshAppPower(nowMillis: Long) = appPowerMutex.withLock {
        val zone = ZoneId.systemDefault()
        val startMillis = LocalDate.now().atStartOfDay(zone).toInstant().toEpochMilli()
        if (nowMillis <= startMillis) return

        val intervals = foregroundIntervals(startMillis, nowMillis)
        val aggregated = aggregatedForegroundMinutes(startMillis, nowMillis)
        if (intervals.isEmpty() && aggregated.isEmpty()) return
        val samples = dao.samplesSince(startMillis)
        val pm = appContext.packageManager
        val today = LocalDate.now().toEpochDay()
        val excluded = excludedPackages()

        val records = (aggregated.keys + intervals.keys).mapNotNull { packageName ->
            if (packageName in excluded) return@mapNotNull null
            val ranges = intervals[packageName].orEmpty()
            val stat = statOf(ranges, samples)
            val minutes = aggregated[packageName] ?: stat.minutes
            if (minutes <= 0) return@mapNotNull null
            val info = runCatching { pm.getApplicationInfo(packageName, 0) }.getOrNull()
            val label = info?.let {
                runCatching { pm.getApplicationLabel(it).toString() }.getOrNull()
            } ?: packageName
            val powerW = stat.avgPowerW ?: fallbackPowerW(info)
            AppPowerRecordEntity(
                epochDay = today,
                packageName = packageName,
                label = label,
                foregroundMinutes = minutes,
                avgTempC = stat.avgTempC ?: -1f,
                maxTempC = stat.maxTempC ?: -1f,
                // W × 小时 ÷ 标称电压 → mAh。
                powerMah = powerW * (minutes / 60f) / NOMINAL_VOLTAGE * 1000f
            )
        }.sortedByDescending { it.powerMah }.take(MAX_APP_RECORDS)
        if (records.isEmpty()) return

        dao.deleteAppPowerOfDay(today)
        dao.insertAppPowerRecords(records)
    }

    /**
     * 不计入应用功耗的包名：
     * 本应用自身、输入法（打字时会被误判成前台）、桌面/启动器、系统界面与系统设置。
     * 这些要么是陪伴工具自身开销，要么属于公共底座，留在列表里只会污染排序。
     */
    private fun excludedPackages(): Set<String> {
        excludedPackageCache?.let { return it }
        val result = mutableSetOf(
            appContext.packageName,
            "android",
            "com.android.systemui",
            "com.android.settings"
        )
        runCatching {
            appContext.getSystemService(InputMethodManager::class.java)
                ?.inputMethodList
                ?.forEach { result += it.packageName }
        }
        runCatching {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            appContext.packageManager.queryIntentActivities(home, 0)
                .forEach { result += it.activityInfo.packageName }
        }
        excludedPackageCache = result
        return result
    }

    private data class IntervalStat(
        val minutes: Int,
        val avgTempC: Float?,
        val maxTempC: Float?,
        val avgPowerW: Float?
    )

    /** 系统聚合的前台分钟数（按包名）。 */
    private fun aggregatedForegroundMinutes(startMillis: Long, endMillis: Long): Map<String, Int> {
        val manager = appContext.getSystemService(UsageStatsManager::class.java) ?: return emptyMap()
        val stats = runCatching {
            manager.queryAndAggregateUsageStats(startMillis, endMillis)
        }.getOrNull() ?: return emptyMap()
        return stats.values.mapNotNull { stat ->
            val packageName = stat.packageName ?: return@mapNotNull null
            val minutes = (stat.totalTimeInForeground / 60_000L).toInt()
            if (minutes <= 0) null else packageName to minutes
        }.toMap()
    }

    /**
     * 把当天的事件流还原成每个应用的前台区间。
     * 以「新的 ACTIVITY_RESUMED」作为上一个应用区间的结束信号，
     * 不依赖一定存在配对的 PAUSED；灭屏与锁屏也会结束当前区间。
     */
    @Suppress("InlinedApi")
    private fun foregroundIntervals(
        startMillis: Long,
        endMillis: Long
    ): Map<String, List<LongRange>> {
        val manager = appContext.getSystemService(UsageStatsManager::class.java) ?: return emptyMap()
        val events = runCatching {
            manager.queryEvents(startMillis, endMillis)
        }.getOrNull() ?: return emptyMap()
        val result = mutableMapOf<String, MutableList<LongRange>>()
        var currentPackage: String? = null
        var currentStart = 0L

        fun close(atMillis: Long) {
            val packageName = currentPackage ?: return
            if (atMillis > currentStart) {
                result.getOrPut(packageName) { mutableListOf() } += currentStart until atMillis
            }
            currentPackage = null
        }

        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    val packageName = event.packageName ?: continue
                    close(event.timeStamp)
                    currentPackage = packageName
                    currentStart = event.timeStamp
                }
                UsageEvents.Event.ACTIVITY_PAUSED -> {
                    if (event.packageName != null && event.packageName == currentPackage) {
                        close(event.timeStamp)
                    }
                }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE,
                UsageEvents.Event.KEYGUARD_SHOWN -> close(event.timeStamp)
            }
        }
        close(endMillis)
        return result
    }

    /** 汇总前台时长，并按区间时长加权平均电池温度与实时放电功率。 */
    private fun statOf(intervals: List<LongRange>, samples: List<BatterySampleEntity>): IntervalStat {
        var totalMs = 0L
        var weightedTemp = 0.0
        var weightedTempMs = 0L
        var maxTemp: Float? = null
        var powerSum = 0.0
        var powerCount = 0
        intervals.forEach { range ->
            val duration = range.last - range.first + 1
            if (duration <= 0L) return@forEach
            totalMs += duration
            var tempSum = 0.0
            var tempCount = 0
            samples.forEach { sample ->
                if (sample.ts in range) {
                    if (sample.tempC > 0f) {
                        tempSum += sample.tempC
                        tempCount++
                        if (maxTemp == null || sample.tempC > maxTemp!!) maxTemp = sample.tempC
                    }
                    // 采样里的 powerW 放电为负，取反得到放电功率。
                    val watts = -sample.powerW
                    if (watts > 0.05f) {
                        powerSum += watts
                        powerCount++
                    }
                }
            }
            if (tempCount > 0) {
                weightedTemp += tempSum / tempCount * duration
                weightedTempMs += duration
            }
        }
        return IntervalStat(
            minutes = (totalMs / 60_000L).toInt(),
            avgTempC = if (weightedTempMs > 0L) {
                (weightedTemp / weightedTempMs).toFloat()
            } else {
                null
            },
            maxTempC = maxTemp,
            avgPowerW = if (powerCount > 0) (powerSum / powerCount).toFloat() else null
        )
    }

    /** 设备读不到电流时，按应用类别给一个合理的功率兜底，避免整页都是 0 W。 */
    private fun fallbackPowerW(info: ApplicationInfo?): Float = when (info?.category) {
        ApplicationInfo.CATEGORY_GAME -> 2.8f
        ApplicationInfo.CATEGORY_VIDEO -> 2.3f
        ApplicationInfo.CATEGORY_MAPS -> 2.1f
        ApplicationInfo.CATEGORY_IMAGE -> 1.8f
        ApplicationInfo.CATEGORY_SOCIAL -> 1.6f
        ApplicationInfo.CATEGORY_NEWS -> 1.4f
        ApplicationInfo.CATEGORY_AUDIO -> 0.5f
        ApplicationInfo.CATEGORY_PRODUCTIVITY -> 1.2f
        else -> 1.4f
    }

    private fun AppPowerRecordEntity.toItem() = AppPowerItem(
        packageName = packageName,
        label = label,
        foregroundMinutes = foregroundMinutes,
        powerMah = powerMah,
        // mAh → Wh → 平均功率：mAh × 3.85V ÷ 1000 ÷ 小时。
        avgPowerW = if (foregroundMinutes > 0) {
            powerMah * NOMINAL_VOLTAGE / 1000f / (foregroundMinutes / 60f)
        } else {
            0f
        },
        avgTempC = avgTempC.takeIf { it > 0f },
        maxTempC = maxTempC.takeIf { it > 0f }
    )

    private companion object {
        const val SAMPLE_INTERVAL_MILLIS = 60_000L
        const val SAMPLE_RETENTION_MILLIS = 24 * 60 * 60_000L
        const val PRUNE_INTERVAL_MILLIS = 10 * 60_000L
        const val TOUCH_INTERVAL_MILLIS = 60_000L
        const val APP_POWER_REFRESH_MILLIS = 15 * 60_000L
        /** 手机电池的标称电压，用于 mAh → W 的折算。 */
        const val NOMINAL_VOLTAGE = 3.85f
        const val MAX_APP_RECORDS = 40
    }
}
