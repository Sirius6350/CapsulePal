package com.capsulepal.app.data.repo

import com.capsulepal.app.data.db.BatteryDao
import com.capsulepal.app.data.db.BatteryHealthRecordEntity
import com.capsulepal.app.data.db.BatterySessionEntity
import com.capsulepal.app.monitor.BatteryCalc
import com.capsulepal.app.monitor.BatteryReading
import com.capsulepal.app.monitor.ChargeSessionView
import kotlin.math.max

/** 监测页一次刷新需要的会话/学习数据。 */
data class BatterySessionSnapshot(
    val currentCharge: ChargeSessionView?,
    val lastCharge: ChargeSessionView?,
    val todayChargeCount: Int,
    val learnedHealthPct: Float?,
    val learnedSamples: Int
)

/**
 * 充电会话与学习健康度的持久化。
 * 只写「开始 / 每分钟心跳 / 结束」三类记录，避免 1 秒轮询把数据库写热；
 * 会话外的聚合结果会缓存，只有会话切换、跨天或额定容量变化时才重查。
 */
class BatteryRepository(private val dao: BatteryDao) {

    private var activeSession: BatterySessionEntity? = null
    private var loaded = false
    private var quietSinceMillis = 0L
    private var fullQuietAchieved = false
    private var peakPowerW = 0f

    private var base: BaseSnapshot? = null

    private data class BaseSnapshot(
        val todayStart: Long,
        val ratedMah: Int,
        val lastCharge: ChargeSessionView?,
        val todayCount: Int,
        val learnedHealthPct: Float?,
        val learnedSamples: Int
    )

    suspend fun onReading(
        reading: BatteryReading,
        nowMillis: Long,
        ratedCapacityMah: Int,
        todayStartMillis: Long
    ): BatterySessionSnapshot {
        ensureLoaded(reading, ratedCapacityMah)

        val session = activeSession
        when {
            // 粘性广播偶发缺失时不要误判为拔电，仅返回现有快照。
            reading.levelPct == null -> Unit
            reading.charging -> {
                if (session == null) {
                    startSession(reading, nowMillis)
                } else {
                    updateSession(session, reading, nowMillis)
                }
            }
            session != null -> closeSession(
                session = session,
                endTs = nowMillis,
                endPct = reading.levelPct ?: session.endPct,
                endMah = reading.chargeCounterMah ?: session.endMah,
                ratedCapacityMah = ratedCapacityMah
            )
        }

        val cached = base
            ?.takeIf { it.todayStart == todayStartMillis && it.ratedMah == ratedCapacityMah }
            ?: buildBase(todayStartMillis, ratedCapacityMah).also { base = it }
        val current = activeSession?.let { open ->
            ChargeSessionView(
                id = open.id,
                startTs = open.startTs,
                endTs = 0L,
                startPct = open.startPct,
                endPct = reading.levelPct ?: open.endPct
            )
        }
        return BatterySessionSnapshot(
            currentCharge = current,
            lastCharge = cached.lastCharge,
            todayChargeCount = cached.todayCount,
            learnedHealthPct = cached.learnedHealthPct,
            learnedSamples = cached.learnedSamples
        )
    }

    /** 清空学习记录后重新开始学习。 */
    suspend fun resetLearning() {
        dao.clearHealthRecords()
        base = null
    }

    /** 清除全部数据时一并清空充电会话与学习健康度。 */
    suspend fun clearAll() {
        dao.clearSessions()
        dao.clearHealthRecords()
        activeSession = null
        loaded = true
        quietSinceMillis = 0L
        fullQuietAchieved = false
        peakPowerW = 0f
        base = null
    }

    /** 导入备份后丢弃内存中的会话缓存，从恢复后的数据库重新读取，避免误改刚导入的记录。 */
    fun resetRuntime() {
        activeSession = null
        loaded = false
        quietSinceMillis = 0L
        fullQuietAchieved = false
        peakPowerW = 0f
        base = null
    }

    /** 进程重启后首次读取：续接进行中的充电，或把上次没来得及结算的会话按最后心跳关掉。 */
    private suspend fun ensureLoaded(reading: BatteryReading, ratedCapacityMah: Int) {
        if (loaded) return
        loaded = true
        val open = dao.openChargeSession() ?: return
        if (reading.charging) {
            activeSession = open
            fullQuietAchieved = open.fullQuiet
            peakPowerW = open.peakPowerW
        } else {
            closeSession(
                session = open,
                endTs = open.lastTs,
                endPct = open.endPct,
                endMah = open.endMah,
                ratedCapacityMah = ratedCapacityMah
            )
        }
    }

    private suspend fun buildBase(todayStartMillis: Long, ratedCapacityMah: Int): BaseSnapshot {
        val last = dao.lastClosedChargeSession()?.let { closed ->
            ChargeSessionView(
                id = closed.id,
                startTs = closed.startTs,
                endTs = closed.endTs,
                startPct = closed.startPct,
                endPct = closed.endPct
            )
        }
        val samples = dao.recentHealthRecords(BatteryCalc.MAX_LEARNING_SAMPLES)
            .mapNotNull { record ->
                val health = BatteryCalc.healthPercentForSample(
                    estimator = record.estimator,
                    startMah = record.startMah,
                    startPct = record.startPct,
                    endMah = record.endMah,
                    endPct = record.endPct,
                    ratedMah = ratedCapacityMah
                ) ?: return@mapNotNull null
                // 明显失真的样本（额定容量填错、电量跳变）不参与学习。
                if (health !in 20f..150f) return@mapNotNull null
                BatteryCalc.HealthSample(health, record.weight)
            }
        return BaseSnapshot(
            todayStart = todayStartMillis,
            ratedMah = ratedCapacityMah,
            lastCharge = last,
            todayCount = dao.chargeCountSince(todayStartMillis),
            learnedHealthPct = BatteryCalc.weightedHealth(samples),
            learnedSamples = samples.size
        )
    }

    private suspend fun startSession(reading: BatteryReading, nowMillis: Long) {
        val pct = reading.levelPct ?: 0
        val mah = reading.chargeCounterMah ?: 0
        val entity = BatterySessionEntity(
            kind = KIND_CHARGE,
            startTs = nowMillis,
            endTs = 0L,
            lastTs = nowMillis,
            startPct = pct,
            endPct = pct,
            startMah = mah,
            endMah = mah,
            peakPowerW = 0f,
            fullQuiet = false
        )
        val id = dao.insertSession(entity)
        activeSession = entity.copy(id = id)
        quietSinceMillis = if (BatteryCalc.isFullQuietCandidate(reading)) nowMillis else 0L
        fullQuietAchieved = false
        peakPowerW = BatteryCalc.chargePowerW(reading.voltageMv, reading.currentMa) ?: 0f
        base = null
    }

    private suspend fun updateSession(
        session: BatterySessionEntity,
        reading: BatteryReading,
        nowMillis: Long
    ) {
        val power = BatteryCalc.chargePowerW(reading.voltageMv, reading.currentMa) ?: 0f
        peakPowerW = max(peakPowerW, power)

        if (BatteryCalc.isFullQuietCandidate(reading)) {
            if (quietSinceMillis == 0L) quietSinceMillis = nowMillis
            if (!fullQuietAchieved &&
                nowMillis - quietSinceMillis >= BatteryCalc.FULL_QUIET_WINDOW_MILLIS
            ) {
                fullQuietAchieved = true
                dao.markFullQuiet(session.id)
            }
        } else {
            quietSinceMillis = 0L
        }

        if (nowMillis - session.lastTs >= TOUCH_INTERVAL_MILLIS) {
            val pct = reading.levelPct ?: session.endPct
            val mah = reading.chargeCounterMah ?: session.endMah
            dao.touchSession(
                id = session.id,
                lastTs = nowMillis,
                endPct = pct,
                endMah = mah,
                peakPowerW = peakPowerW,
                fullQuiet = fullQuietAchieved
            )
            activeSession = session.copy(
                lastTs = nowMillis,
                endPct = pct,
                endMah = mah,
                peakPowerW = peakPowerW,
                fullQuiet = fullQuietAchieved
            )
        }
    }

    private suspend fun closeSession(
        session: BatterySessionEntity,
        endTs: Long,
        endPct: Int,
        endMah: Int,
        ratedCapacityMah: Int
    ) {
        val quiet = fullQuietAchieved || session.fullQuiet
        val amplitude = (endPct - session.startPct).coerceAtLeast(0)
        val reachedFull = endPct >= 100
        val weight = BatteryCalc.learningWeight(amplitude, quiet, reachedFull)
        dao.closeSession(
            id = session.id,
            endTs = endTs,
            endPct = endPct,
            endMah = endMah,
            peakPowerW = peakPowerW,
            fullQuiet = quiet
        )
        if (weight != null) {
            val estimator = if (reachedFull) {
                BatteryCalc.ESTIMATOR_FULL
            } else {
                BatteryCalc.ESTIMATOR_DELTA
            }
            val health = BatteryCalc.healthPercentForSample(
                estimator = estimator,
                startMah = session.startMah,
                startPct = session.startPct,
                endMah = endMah,
                endPct = endPct,
                ratedMah = ratedCapacityMah
            )
            if (health != null && health in 20f..150f) {
                dao.insertHealthRecord(
                    BatteryHealthRecordEntity(
                        ts = endTs,
                        sessionId = session.id,
                        kind = if (reachedFull) KIND_FULL else KIND_CHARGE,
                        weight = weight,
                        startMah = session.startMah,
                        startPct = session.startPct,
                        endMah = endMah,
                        endPct = endPct,
                        quiet = quiet,
                        estimator = estimator
                    )
                )
            }
        }
        activeSession = null
        quietSinceMillis = 0L
        fullQuietAchieved = false
        peakPowerW = 0f
        base = null
    }

    companion object {
        const val KIND_CHARGE = "CHARGE"
        const val KIND_FULL = "FULL"
        private const val TOUCH_INTERVAL_MILLIS = 60_000L
    }
}
