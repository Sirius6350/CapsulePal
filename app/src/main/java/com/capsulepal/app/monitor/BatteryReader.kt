package com.capsulepal.app.monitor

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

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
    /** BatteryManager.EXTRA_PLUGGED 原始值：1 AC / 2 USB / 4 无线 / 8 底座。 */
    val pluggedType: Int = 0,
    val designCapacityMah: Int? = null,
    val fullCapacityMah: Int? = null,
    /** 能量节点按 3.85V 单芯电压换算出的等效容量，用于判断系统上报的是单芯还是整包。 */
    val energyDesignCapacityMah: Int? = null,
    val energyFullCapacityMah: Int? = null,
    /** 内核上报的电池技术，如 Li-ion / Li-poly / LiFePO4。 */
    val technology: String? = null,
    /** 充电类型，如 Fast / Standard / Adaptive / Trickle。 */
    val chargeType: String? = null,
    /** usb 节点的 REAL_TYPE / USB_TYPE / TYPEC_MODE，用于推断充电协议。 */
    val usbRealType: String? = null,
    val usbType: String? = null,
    val typecMode: String? = null,
    /** 充电器协商上限，读不到为 null。 */
    val usbVoltageMaxMv: Int? = null,
    val usbCurrentMaxMa: Float? = null,
    /** 设计标称电压，用于展示单芯/双芯校正前后的电压。 */
    val designVoltageMv: Int? = null
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
        val pluggedType = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0

        val statusFull = status == BatteryManager.BATTERY_STATUS_FULL
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || statusFull

        val pct = if (level >= 0 && scale > 0) {
            (level * 100f / scale).toInt().coerceIn(0, 100)
        } else {
            null
        }

        // sysfs 作为官方 API 的补充：容量、技术、充电协议只有部分机型通过公开 API 暴露。
        val supply = readPowerSupply()

        return BatteryReading(
            tempC = temp.takeIf { it != Int.MIN_VALUE }?.div(10f),
            levelPct = pct,
            chargeCounterMah = readChargeCounterMah() ?: supply.chargeCounterMah,
            voltageMv = readVoltageMv(voltage.takeIf { it > 0 }) ?: supply.voltageMv,
            currentMa = readCurrentMa() ?: supply.currentMa,
            cycleCount = readCycleCount(intent),
            charging = charging,
            statusFull = statusFull,
            plugged = pluggedType != 0,
            pluggedType = pluggedType,
            designCapacityMah = readSysfsCapacity(DESIGN_CAPACITY_PATHS)
                ?: supply.chargeDesignMah
                ?: supply.energyDesignMah
                ?: readPowerProfileCapacityMah(),
            fullCapacityMah = readSysfsCapacity(FULL_CAPACITY_PATHS)
                ?: supply.chargeFullMah
                ?: supply.energyFullMah,
            energyDesignCapacityMah = supply.energyDesignMah,
            energyFullCapacityMah = supply.energyFullMah,
            // 参考 App 显示的 Li-ion 就是来自这个官方字段，sysfs 只作兜底。
            technology = intent?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)
                ?.trim()
                ?.takeIf { it.isNotEmpty() && !it.equals("Unknown", ignoreCase = true) }
                ?: supply.technology,
            chargeType = supply.chargeType,
            usbRealType = supply.usbRealType,
            usbType = supply.usbType,
            typecMode = supply.typecMode,
            usbVoltageMaxMv = supply.usbVoltageMaxMv,
            usbCurrentMaxMa = supply.usbCurrentMaxMa,
            designVoltageMv = supply.designVoltageMv
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

    // ------------------------------------------------------------ sysfs 枚举兜底

    /** 枚举 /sys/class/power_supply，兼容 battery 之外的 bms / usb / wireless / dock 命名。 */
    private val supplyDirs: List<File> by lazy {
        runCatching {
            File(POWER_SUPPLY_DIR).listFiles()?.filter { it.isDirectory }
        }.getOrNull().orEmpty()
    }

    private data class SupplySnapshot(
        val chargeDesignMah: Int? = null,
        val chargeFullMah: Int? = null,
        val energyDesignMah: Int? = null,
        val energyFullMah: Int? = null,
        val chargeCounterMah: Int? = null,
        val voltageMv: Int? = null,
        val currentMa: Float? = null,
        val technology: String? = null,
        val chargeType: String? = null,
        val usbRealType: String? = null,
        val usbType: String? = null,
        val typecMode: String? = null,
        val usbVoltageMaxMv: Int? = null,
        val usbCurrentMaxMa: Float? = null,
        val designVoltageMv: Int? = null
    )

    /** 先按 uevent 读取（与节点名无关），失败再退回同名单文件。 */
    private fun readPowerSupply(): SupplySnapshot {
        val dirs = supplyDirs
        if (dirs.isEmpty()) return SupplySnapshot()
        val battery = dirs.firstOrNull { it.name == "battery" }
            ?: dirs.firstOrNull { dir ->
                dir.uevent()["POWER_SUPPLY_TYPE"].equals("Battery", ignoreCase = true)
            }
        val event = battery?.uevent().orEmpty()
        val voltageUv = battery?.let { nodeLong(it, event, "voltage_now") }
        val designVoltageUv = battery?.let {
            nodeLong(it, event, "voltage_min_design")
                ?: nodeLong(it, event, "voltage_max_design")
        }
        // 能量节点的 µWh 按 3.85V 单芯电压换算，得到与厂商标称一致的「等效 mAh」。
        val nominalCellUv = NOMINAL_CELL_VOLTAGE_UV

        val usb = pickUsbNode(dirs)
        val usbEvent = usb?.second.orEmpty()

        return SupplySnapshot(
            chargeDesignMah = battery?.let {
                chargeToMah(nodeLong(it, event, "charge_full_design"))
            },
            chargeFullMah = battery?.let {
                chargeToMah(nodeLong(it, event, "charge_full"))
            },
            energyDesignMah = battery?.let {
                energyToMah(nodeLong(it, event, "energy_full_design"), nominalCellUv)
            },
            energyFullMah = battery?.let {
                energyToMah(nodeLong(it, event, "energy_full"), nominalCellUv)
            },
            chargeCounterMah = battery?.let {
                chargeToMah(nodeLong(it, event, "charge_counter"))
                    ?: energyToMah(nodeLong(it, event, "energy_counter"), nominalCellUv)
            },
            voltageMv = normalizeMv(voltageUv ?: 0L),
            currentMa = signedMilli(battery?.let { nodeLong(it, event, "current_now") }),
            technology = battery?.let { textValue(it, event, "technology") },
            chargeType = battery?.let { textValue(it, event, "charge_type") }
                ?: usb?.first?.let { textValue(it, usbEvent, "charge_type") },
            usbRealType = textValue(usbEvent, "real_type"),
            usbType = textValue(usbEvent, "usb_type"),
            typecMode = textValue(usbEvent, "typec_mode"),
            usbVoltageMaxMv = microVoltToMv(
                usb?.first?.let { nodeLong(it, usbEvent, "voltage_max") }
            ),
            usbCurrentMaxMa = microToMilli(
                usb?.first?.let { nodeLong(it, usbEvent, "current_max") }
            ),
            designVoltageMv = normalizeMv(designVoltageUv ?: 0L)
        )
    }

    private fun File.uevent(): Map<String, String> {
        val map = HashMap<String, String>()
        runCatching {
            File(this, "uevent").forEachLine { line ->
                val index = line.indexOf('=')
                if (index > 0) {
                    map[line.substring(0, index).trim()] = line.substring(index + 1).trim()
                }
            }
        }
        return map
    }

    private fun nodeLong(dir: File, event: Map<String, String>, name: String): Long? =
        event["POWER_SUPPLY_${name.uppercase(Locale.ROOT)}"]?.toLongOrNull()
            ?: readLong(File(dir, name).path)

    private fun textValue(dir: File, event: Map<String, String>, name: String): String? =
        textValue(event, name)
            ?: runCatching { File(dir, name).readText().trim() }.getOrNull()
                ?.takeIf { it.isNotEmpty() }

    private fun textValue(event: Map<String, String>, name: String): String? =
        event["POWER_SUPPLY_${name.uppercase(Locale.ROOT)}"]?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals("Unknown", ignoreCase = true) }

    /** 充电节点按「在线优先、协商功率其次」挑一个，避免把无线的 5W 当成有线的 100W。 */
    private fun pickUsbNode(dirs: List<File>): Pair<File, Map<String, String>>? {
        val candidates = dirs.mapNotNull { dir ->
            if (dir.name == "battery" || dir.name == "bms") return@mapNotNull null
            val event = dir.uevent()
            val relevant = event.keys.any { it in USB_KEYS } ||
                dir.name.contains("usb") ||
                dir.name.contains("charger") ||
                dir.name.contains("wireless") ||
                dir.name.contains("dock")
            if (relevant) dir to event else null
        }
        return candidates.maxWithOrNull(
            compareBy<Pair<File, Map<String, String>>> {
                if (it.second["POWER_SUPPLY_ONLINE"] == "1") 1 else 0
            }.thenBy {
                (it.second["POWER_SUPPLY_VOLTAGE_MAX"]?.toLongOrNull() ?: 0L) *
                    (it.second["POWER_SUPPLY_CURRENT_MAX"]?.toLongOrNull() ?: 0L)
            }
        )
    }

    /** 电荷节点标准单位 µAh；个别节点直接给 mAh，按合理值域兜底。 */
    private fun chargeToMah(raw: Long?): Int? {
        val value = raw?.takeIf { it > 0 } ?: return null
        val fromMicro = value / 1000L
        return when {
            fromMicro in 500L..30_000L -> fromMicro.toInt()
            value in 500L..30_000L -> value.toInt()
            else -> null
        }
    }

    /** 能量节点标准单位 µWh：µWh ÷ µV = Ah，再 ×1000 得 mAh。 */
    private fun energyToMah(raw: Long?, voltageUv: Long?): Int? {
        val value = raw?.takeIf { it > 0 } ?: return null
        val voltage = voltageUv?.takeIf { it in 2_500_000L..10_000_000L } ?: 3_850_000L
        val mah = (value.toDouble() / voltage * 1000.0).roundToInt()
        return mah.takeIf { it in 500..30_000 }
    }

    private fun microVoltToMv(raw: Long?): Int? {
        val value = raw?.takeIf { it > 0 } ?: return null
        val millivolt = value / 1000L
        return when {
            millivolt in 1000L..60_000L -> millivolt.toInt()
            value in 1000L..60_000L -> value.toInt()
            else -> null
        }
    }

    private fun microToMilli(raw: Long?): Float? {
        val value = raw?.takeIf { it > 0 } ?: return null
        val milli = value / 1000f
        return when {
            milli in 1f..20_000f -> milli
            value in 1L..20_000L -> value.toFloat()
            else -> null
        }
    }

    private fun signedMilli(raw: Long?): Float? {
        val value = raw?.takeIf { it != 0L } ?: return null
        val sign = if (value < 0) -1f else 1f
        return normalizeMa(abs(value))?.let { it * sign }
    }

    /**
     * 最后兜底：部分机型只在 framework 的 power_profile.xml 里给出 battery.capacity。
     * PowerProfile 是隐藏类，新系统可能拒绝反射；失败时静默返回 null。
     */
    private fun readPowerProfileCapacityMah(): Int? = runCatching {
        val cls = Class.forName("com.android.internal.os.PowerProfile")
        val instance = cls.getConstructor(Context::class.java).newInstance(appContext)
        val value = cls.getMethod("getBatteryCapacity").invoke(instance)
        val mah = when (value) {
            is Double -> value.roundToInt()
            is Float -> value.roundToInt()
            is Number -> value.toInt()
            else -> null
        }
        mah?.takeIf { it in 500..30_000 }
    }.getOrNull()

    private companion object {
        const val POWER_SUPPLY_DIR = "/sys/class/power_supply"

        /** 单芯标称电压：能量节点 µWh ÷ 该值 = 厂商标称口径的等效 mAh。 */
        const val NOMINAL_CELL_VOLTAGE_UV = 3_850_000L

        val USB_KEYS = setOf(
            "POWER_SUPPLY_REAL_TYPE",
            "POWER_SUPPLY_USB_TYPE",
            "POWER_SUPPLY_TYPEC_MODE",
            "POWER_SUPPLY_VOLTAGE_MAX",
            "POWER_SUPPLY_CURRENT_MAX"
        )

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
