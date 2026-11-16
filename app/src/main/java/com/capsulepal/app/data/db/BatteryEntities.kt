package com.capsulepal.app.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/** 一次充电会话；endTs = 0 表示仍在进行中。 */
@Entity(tableName = "battery_sessions")
data class BatterySessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val kind: String,
    val startTs: Long,
    val endTs: Long,
    val lastTs: Long,
    val startPct: Int,
    val endPct: Int,
    val startMah: Int,
    val endMah: Int,
    val peakPowerW: Float,
    val fullQuiet: Boolean
)

/** 学习健康度样本，一次合规充电会话只产出一条。 */
@Entity(tableName = "battery_health_records")
data class BatteryHealthRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val ts: Long,
    val sessionId: Long,
    val kind: String,
    val weight: Int,
    /** 原始测量值：健康度在读取时按当前额定容量重算，改额定容量不会污染历史。 */
    val startMah: Int,
    val startPct: Int,
    val endMah: Int,
    val endPct: Int,
    val quiet: Boolean,
    val estimator: String
)

/** 一次放电会话；仅极客版记录，endTs = 0 表示仍在进行中。 */
@Entity(tableName = "battery_discharge_sessions")
data class DischargeSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val startTs: Long,
    val endTs: Long,
    val lastTs: Long,
    val startPct: Int,
    val endPct: Int,
    val startMah: Int,
    val endMah: Int,
    /** 会话期间的平均放电功率（W，正数）。 */
    val avgPowerW: Float
)

/** 一次充电会话；仅极客版记录，endTs = 0 表示仍在进行中。 */
@Entity(tableName = "battery_charge_sessions")
data class ChargeSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val startTs: Long,
    val endTs: Long,
    val lastTs: Long,
    val startPct: Int,
    val endPct: Int,
    val startMah: Int,
    val endMah: Int,
    /** 会话期间的平均充电功率（W，正数）。 */
    val avgPowerW: Float,
    /** 会话期间的峰值充电功率（W）。 */
    val peakPowerW: Float
)

/** 24 小时电池采样，每分钟一条；仅极客版记录，只保留最近 24 小时。 */
@Entity(tableName = "battery_samples", indices = [Index("ts")])
data class BatterySampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val ts: Long,
    val levelPct: Int,
    val tempC: Float,
    val currentMa: Int,
    val powerW: Float
)

/** 按天聚合的应用功耗估算；仅极客版记录。 */
@Entity(tableName = "app_power_records", indices = [Index("epochDay")])
data class AppPowerRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    /** 记录归属的本地日期（epochDay）。 */
    val epochDay: Long,
    val packageName: String,
    val label: String,
    val foregroundMinutes: Int,
    /** 使用期间的平均电池温度（℃）；旧数据没有采样时为 -1。 */
    val avgTempC: Float,
    /** 使用期间的最高电池温度（℃）；旧数据没有采样时为 -1。 */
    val maxTempC: Float,
    /** 估算耗电（mAh），按整机放电量归一。 */
    val powerMah: Float
)

@Dao
abstract class BatteryDao {

    @Query("SELECT * FROM battery_sessions WHERE kind = 'CHARGE' AND endTs = 0 ORDER BY id DESC LIMIT 1")
    abstract suspend fun openChargeSession(): BatterySessionEntity?

    @Query("SELECT * FROM battery_sessions WHERE kind = 'CHARGE' AND endTs > 0 ORDER BY endTs DESC LIMIT 1")
    abstract suspend fun lastClosedChargeSession(): BatterySessionEntity?

    @Query("SELECT COUNT(*) FROM battery_sessions WHERE kind = 'CHARGE' AND startTs >= :fromTs")
    abstract suspend fun chargeCountSince(fromTs: Long): Int

    @Query("SELECT * FROM battery_health_records WHERE endPct > 0 ORDER BY ts DESC LIMIT :limit")
    abstract suspend fun recentHealthRecords(limit: Int): List<BatteryHealthRecordEntity>

    @Query("DELETE FROM battery_health_records")
    abstract suspend fun clearHealthRecords()

    @Insert
    abstract suspend fun insertSession(session: BatterySessionEntity): Long

    @Insert
    abstract suspend fun insertHealthRecord(record: BatteryHealthRecordEntity)

    // ---- 备份用：充放电会话与学习健康度 ----

    @Query("SELECT * FROM battery_sessions WHERE kind = 'CHARGE' AND startTs >= :fromTs ORDER BY startTs ASC")
    abstract suspend fun chargeSessionsSince(fromTs: Long): List<BatterySessionEntity>

    @Query("SELECT * FROM battery_health_records WHERE ts >= :fromTs ORDER BY ts ASC")
    abstract suspend fun healthRecordsSince(fromTs: Long): List<BatteryHealthRecordEntity>

    @Query("DELETE FROM battery_sessions")
    abstract suspend fun clearSessions()

    @Insert
    abstract suspend fun insertSessions(sessions: List<BatterySessionEntity>)

    @Insert
    abstract suspend fun insertHealthRecords(records: List<BatteryHealthRecordEntity>)

    @Query(
        "UPDATE battery_sessions SET lastTs = :lastTs, endPct = :endPct, endMah = :endMah, " +
            "peakPowerW = :peakPowerW, fullQuiet = :fullQuiet WHERE id = :id"
    )
    abstract suspend fun touchSession(
        id: Long,
        lastTs: Long,
        endPct: Int,
        endMah: Int,
        peakPowerW: Float,
        fullQuiet: Boolean
    )

    @Query("UPDATE battery_sessions SET fullQuiet = 1 WHERE id = :id")
    abstract suspend fun markFullQuiet(id: Long)

    @Query(
        "UPDATE battery_sessions SET endTs = :endTs, lastTs = :endTs, endPct = :endPct, " +
            "endMah = :endMah, peakPowerW = :peakPowerW, fullQuiet = :fullQuiet WHERE id = :id"
    )
    abstract suspend fun closeSession(
        id: Long,
        endTs: Long,
        endPct: Int,
        endMah: Int,
        peakPowerW: Float,
        fullQuiet: Boolean
    )

    // ---- 极客版：放电会话 ----

    @Query("SELECT * FROM battery_discharge_sessions WHERE endTs = 0 ORDER BY id DESC LIMIT 1")
    abstract suspend fun openDischargeSession(): DischargeSessionEntity?

    @Query("SELECT * FROM battery_discharge_sessions ORDER BY startTs DESC LIMIT :limit")
    abstract suspend fun recentDischargeSessions(limit: Int): List<DischargeSessionEntity>

    @Query("SELECT * FROM battery_discharge_sessions WHERE startTs >= :fromTs ORDER BY startTs DESC")
    abstract suspend fun dischargeSessionsSince(fromTs: Long): List<DischargeSessionEntity>

    @Insert
    abstract suspend fun insertDischargeSession(session: DischargeSessionEntity): Long

    @Query(
        "UPDATE battery_discharge_sessions SET lastTs = :lastTs, endPct = :endPct, " +
            "endMah = :endMah, avgPowerW = :avgPowerW WHERE id = :id"
    )
    abstract suspend fun touchDischargeSession(
        id: Long,
        lastTs: Long,
        endPct: Int,
        endMah: Int,
        avgPowerW: Float
    )

    @Query(
        "UPDATE battery_discharge_sessions SET endTs = :endTs, lastTs = :endTs, " +
            "endPct = :endPct, endMah = :endMah, avgPowerW = :avgPowerW WHERE id = :id"
    )
    abstract suspend fun closeDischargeSession(
        id: Long,
        endTs: Long,
        endPct: Int,
        endMah: Int,
        avgPowerW: Float
    )

    @Query("DELETE FROM battery_discharge_sessions")
    abstract suspend fun clearDischargeSessions()

    @Insert
    abstract suspend fun insertDischargeSessions(sessions: List<DischargeSessionEntity>)

    // ---- 极客版：充电会话 ----

    @Query("SELECT * FROM battery_charge_sessions WHERE endTs = 0 ORDER BY id DESC LIMIT 1")
    abstract suspend fun openChargeGeekSession(): ChargeSessionEntity?

    @Query("SELECT * FROM battery_charge_sessions ORDER BY startTs DESC LIMIT :limit")
    abstract suspend fun recentChargeSessions(limit: Int): List<ChargeSessionEntity>

    @Query("SELECT * FROM battery_charge_sessions WHERE startTs >= :fromTs ORDER BY startTs ASC")
    abstract suspend fun geekChargeSessionsSince(fromTs: Long): List<ChargeSessionEntity>

    @Insert
    abstract suspend fun insertChargeSession(session: ChargeSessionEntity): Long

    @Insert
    abstract suspend fun insertChargeSessions(sessions: List<ChargeSessionEntity>)

    @Query(
        "UPDATE battery_charge_sessions SET lastTs = :lastTs, endPct = :endPct, " +
            "endMah = :endMah, avgPowerW = :avgPowerW, peakPowerW = :peakPowerW WHERE id = :id"
    )
    abstract suspend fun touchChargeSession(
        id: Long,
        lastTs: Long,
        endPct: Int,
        endMah: Int,
        avgPowerW: Float,
        peakPowerW: Float
    )

    @Query(
        "UPDATE battery_charge_sessions SET endTs = :endTs, lastTs = :endTs, " +
            "endPct = :endPct, endMah = :endMah, avgPowerW = :avgPowerW, " +
            "peakPowerW = :peakPowerW WHERE id = :id"
    )
    abstract suspend fun closeChargeSession(
        id: Long,
        endTs: Long,
        endPct: Int,
        endMah: Int,
        avgPowerW: Float,
        peakPowerW: Float
    )

    @Query("DELETE FROM battery_charge_sessions")
    abstract suspend fun clearChargeSessions()

    // ---- 极客版：24 小时采样 ----

    @Insert
    abstract suspend fun insertSample(sample: BatterySampleEntity)

    @Insert
    abstract suspend fun insertSamples(samples: List<BatterySampleEntity>)

    @Query("SELECT * FROM battery_samples WHERE ts >= :fromTs ORDER BY ts ASC")
    abstract suspend fun samplesSince(fromTs: Long): List<BatterySampleEntity>

    @Query("DELETE FROM battery_samples WHERE ts < :beforeTs")
    abstract suspend fun pruneSamples(beforeTs: Long)

    @Query("DELETE FROM battery_samples")
    abstract suspend fun clearSamples()

    // ---- 极客版：应用功耗历史 ----

    @Insert
    abstract suspend fun insertAppPowerRecords(records: List<AppPowerRecordEntity>)

    @Query("DELETE FROM app_power_records WHERE epochDay = :epochDay")
    abstract suspend fun deleteAppPowerOfDay(epochDay: Long)

    @Query("SELECT * FROM app_power_records WHERE epochDay = :epochDay ORDER BY powerMah DESC")
    abstract suspend fun appPowerOfDay(epochDay: Long): List<AppPowerRecordEntity>

    @Query("SELECT * FROM app_power_records WHERE epochDay >= :fromDay ORDER BY epochDay DESC, powerMah DESC")
    abstract suspend fun appPowerSince(fromDay: Long): List<AppPowerRecordEntity>

    @Query("DELETE FROM app_power_records")
    abstract suspend fun clearAppPowerRecords()
}
