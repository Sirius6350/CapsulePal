package com.capsulepal.app.monitor

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.io.File
import kotlin.math.abs

/** 电池瞬时读数，全部来自系统公开 API 与内核节点，不需要额外权限。 */
data class BatteryReading(
    val tempC: Float? = null,
    val levelPct: Int? = null,
    val chargeCounterMah: Int? = null,
    val voltageMv: Int? = null,
    val currentMa: Float? = null,
    val cycleCount: Int? = null,
    val charging: Boolean = false,
    val statusFull: Boolean = false,
    val plugged: Boolean = false,
    val designCapacityMah: Int? = null,
    val fullCapacityMah: Int? = null
)

/**
 * 读取一次电池快照。
 * 电流/电压存在厂商口径差异，沿用 Syscan 的多标度归一化方案；失败时回退 sysfs。
 */
class BatteryReader(context: Context) {

    private val appContext = context.applicationContext
    private val batteryManager: BatteryManager? =
        appContext.getSystemService(BatteryManager::class.java)

    fun read(): BatteryReading {
        val intent = try {
            appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (_: Exception) {
            null
        }

        val temp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?: Int.MIN_VALUE
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val voltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Int.MIN_VALUE)
            ?: Int.MIN_VALUE
        val status = intent?.getIntExtra(
            BatteryManager.EXTRA_STATUS,
            BatteryManager.BATTERY_STATUS_UNKNOWN
        ) ?: BatteryManager.BATTERY_STATUS_UNKNOWN
        val plugged = (intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0

        val statusFull = status == BatteryManager.BATTERY_STATUS_FULL
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || statusFull

        val pct = if (level >= 0 && scale > 0) {
            (level * 100f / scale).toInt().coerceIn(0, 100)
        } else {
            null
        }

        return BatteryReading(
            tempC = temp.takeIf { it != Int.MIN_VALUE }?.div(10f),
            levelPct = pct,
            chargeCounterMah = readChargeCounterMah(),
            voltageMv = readVoltageMv(voltage.takeIf { it > 0 }),
            currentMa = readCurrentMa(),
            cycleCount = readCycleCount(intent),
            charging = charging,
            statusFull = statusFull,
            plugged = plugged,
            designCapacityMah = readSysfsCapacity(DESIGN_CAPACITY_PATHS),
            fullCapacityMah = readSysfsCapacity(FULL_CAPACITY_PATHS)
        )
    }

    /** CHARGE_COUNTER 标准单位 µAh；个别机型直接给 mAh，按合理值域兜底。 */
    private fun readChargeCounterMah(): Int? {
        val raw = try {
            batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        } catch (_: Exception) {
            null
        } ?: return null
        if (raw == Int.MIN_VALUE || raw <= 0) return null
        val fromMicro = raw / 1000
        return when {
            fromMicro in 1..30_000 -> fromMicro
            raw in 1..30_000 -> raw
            else -> null
        }
    }

    private fun readVoltageMv(extraMv: Int?): Int? {
        extraMv?.let { raw -> normalizeMv(raw.toLong())?.let { return it } }
        for (path in SYSFS_VOLTAGE_PATHS) {
            val raw = readLong(path) ?: continue
            normalizeMv(raw)?.let { return it }
        }
        return null
    }

    /** 同一原始值按 µV/mV 等常见标度依次解释，取落在 2500~9500mV 合理域的首解。 */
    private fun normalizeMv(raw: Long): Int? {
        if (raw <= 0L) return null
        for (candidate in longArrayOf(raw, raw / 1000L, raw * 1000L, raw * 10L, raw * 100L, raw / 10L)) {
            if (candidate in 2500L..9500L) return candidate.toInt()
        }
        return null
    }

    /** 电流口径：直读（mA）优先，÷1000（µA）兜底，取 1~10000mA 合理域首解并保留符号。 */
    private fun readCurrentMa(): Float? {
        val raw = try {
            batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        } catch (_: Exception) {
            null
        }
        if (raw != null && raw != Int.MIN_VALUE && raw != 0) {
            val sign = if (raw < 0) -1f else 1f
            normalizeMa(abs(raw.toLong()))?.let { return it * sign }
        }
        for (path in SYSFS_CURRENT_PATHS) {
            val value = readLong(path) ?: continue
            if (value == 0L) continue
            val sign = if (value < 0) -1f else 1f
            normalizeMa(abs(value))?.let { return it * sign }
        }
        return null
    }

    private fun normalizeMa(absRaw: Long): Float? {
        for (candidate in longArrayOf(absRaw, absRaw / 1000L)) {
            if (candidate in 1L..10_000L) return candidate.toFloat()
        }
        return null
    }

    /**
     * 循环计数：API 34+ 的 EXTRA_CYCLE_COUNT 优先，sysfs 节点兜底（无 root 时可能被拒绝）。
     * 值域 [1, 3000] 过滤未知值。
     */
    private fun readCycleCount(intent: Intent?): Int? {
        intent?.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1)
            ?.takeIf { it in 1..3000 }
            ?.let { return it }
        for (path in SYSFS_CYCLE_PATHS) {
            val value = readLong(path) ?: continue
            if (value in 1L..3000L) return value.toInt()
        }
        return null
    }

    /** 容量节点标准单位 µAh；个别节点直接给 mAh，按合理值域兜底。 */
    private fun readSysfsCapacity(paths: Array<String>): Int? {
        for (path in paths) {
            val raw = readLong(path) ?: continue
            if (raw <= 0L) continue
            val fromMicro = raw / 1000L
            if (fromMicro in 500L..30_000L) return fromMicro.toInt()
            if (raw in 500L..30_000L) return raw.toInt()
        }
        return null
    }

    private fun readLong(path: String): Long? = try {
        File(path).readText().trim().toLongOrNull()
    } catch (_: Exception) {
        null
    }

    private companion object {
        val DESIGN_CAPACITY_PATHS = arrayOf(
            "/sys/class/power_supply/battery/charge_full_design",
            "/sys/class/power_supply/bms/charge_full_design"
        )
        val FULL_CAPACITY_PATHS = arrayOf(
            "/sys/class/power_supply/battery/charge_full",
            "/sys/class/power_supply/bms/charge_full"
        )
        val SYSFS_VOLTAGE_PATHS = arrayOf(
            "/sys/class/power_supply/battery/voltage_now",
            "/sys/class/power_supply/bms/voltage_now",
            "/sys/class/power_supply/battery/batt_volt",
            "/sys/class/power_supply/battery/voltage_avg"
        )
        val SYSFS_CURRENT_PATHS = arrayOf(
            "/sys/class/power_supply/battery/current_now",
            "/sys/class/power_supply/bms/current_now"
        )
        val SYSFS_CYCLE_PATHS = arrayOf(
            "/sys/class/power_supply/bms/cycle_count_monotonic",
            "/sys/class/power_supply/battery/cycle_count_monotonic",
            "/sys/class/power_supply/battery/fg_cycle_count",
            "/sys/class/power_supply/battery/cycle_count",
            "/sys/class/power_supply/bms/cycle_count",
            "/sys/class/power_supply/battery/batt_cycle_count"
        )
    }
}
