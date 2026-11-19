package com.capsulepal.app.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.capsulepal.app.data.repo.BatteryRepository
import com.capsulepal.app.data.repo.GeekRepository
import com.capsulepal.app.data.repo.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** 监测页需要的完整快照。 */
data class MonitorState(
    val reading: BatteryReading,
    val ratedCapacityMah: Int,
    val ratedIsAuto: Boolean,
    val systemDesignMah: Int?,
    val fullCapacityMah: Int?,
    val healthPredictionPct: Float?,
    val learnedHealthPct: Float?,
    val learnedSamples: Int,
    val currentCharge: ChargeSessionView?,
    val lastCharge: ChargeSessionView?,
    val todayChargeCount: Int,
    val updatedAt: Long,
    /** 额定容量的真实来源，用于界面标注。 */
    val ratedSource: CapacitySource = CapacitySource.NONE,
    /** 实际生效的芯数（1/2）。 */
    val batteryCells: Int = 1,
    /** 用户设置：0 自动，1 单芯，2 双芯。 */
    val batteryCellsSetting: Int = 0,
    /** 系统原始上报的设计容量（未乘芯数）。 */
    val systemDesignRawMah: Int? = null,
    /** 系统上报的设计标称电压。 */
    val designVoltageMv: Int? = null,
    /** 系统原始电压（未做双芯校正），供弹窗预览使用。 */
    val rawVoltageMv: Int? = null,
    /** 能量节点换算出的等效设计容量，用于双芯判定。 */
    val energyDesignMah: Int? = null
) {
    val chargePowerW: Float?
        get() = BatteryCalc.chargePowerW(reading.voltageMv, reading.currentMa)

    val chargeMethodLabel: String
        get() = BatteryCalc.describeCharging(reading)

    val technologyLabel: String
        get() = BatteryCalc.describeTechnology(reading.technology)
}

/**
 * 电池轮询：
 * - 充电（含满电）状态 1 秒一次；
 * - 放电状态 60 秒一次，与通知轮询一致；
 * - 设置里打开「始终保持高频」后任何状态都 1 秒一次。
 * 插拔电源时立即补一次读数，避免充电会话起止时间漂移。
 */
class BatteryMonitor(
    context: Context,
    private val repository: BatteryRepository,
    private val geekRepository: GeekRepository,
    private val settingsRepository: SettingsRepository,
    private val scope: CoroutineScope
) {

    private val appContext = context.applicationContext
    private val reader = BatteryReader(appContext)
    private val tickMutex = Mutex()

    private val _state = MutableStateFlow<MonitorState?>(null)
    val state: StateFlow<MonitorState?> = _state.asStateFlow()

    /**
     * 唤醒信号。
     * 主循环在放电状态下会睡满 60 秒，插电广播只是补一次读数的话，
     * 下一次采样仍要等这一觉睡完，功率就会冻结一分钟。
     * 这里用 conflated channel 立刻打断延时，切到 1 秒采样。
     */
    private val wakeSignal = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var alwaysHighFrequency = false

    /** 极客版充放电都按 1 秒采样。 */
    @Volatile
    private var geekHighFrequency = false

    private var loopJob: Job? = null

    /** 上一次学习到的整包容量；系统未提供设计容量时用它兜底。 */
    private var learnedCapacityMah: Int? = null

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            poke()
        }
    }

    fun start() {
        if (loopJob != null) return
        runCatching {
            // 系统广播必须显式声明导出，否则在高版本上注册会被静默拒绝，
            // 插电后要等到下一轮 60 秒轮询才发现，充电数据会一分钟不刷新。
            ContextCompat.registerReceiver(
                appContext,
                powerReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_POWER_CONNECTED)
                    addAction(Intent.ACTION_POWER_DISCONNECTED)
                },
                ContextCompat.RECEIVER_EXPORTED
            )
        }
        loopJob = scope.launch {
            while (isActive) {
                tick()
                val fast = alwaysHighFrequency || geekHighFrequency ||
                    _state.value?.reading?.let { it.charging || it.plugged } == true
                // 等待下一次采样；插拔电源会立刻打断，不用睡满 60 秒。
                withTimeoutOrNull(if (fast) HIGH_FREQ_INTERVAL_MILLIS else LOW_FREQ_INTERVAL_MILLIS) {
                    wakeSignal.receive()
                }
            }
        }
    }

    /** 进入前台、切换设置、插拔电源时立即刷新一次，并唤醒主循环。 */
    fun poke() {
        if (loopJob == null) return
        wakeSignal.trySend(Unit)
        scope.launch { tick() }
    }

    private suspend fun tick() = tickMutex.withLock {
        val now = System.currentTimeMillis()
        val settings = settingsRepository.settings.first()
        // 极客体验必须真的锁定 1 秒采样，不能只靠设置页的按钮外观。
        alwaysHighFrequency = settings.version.recordsBatteryTimeline ||
            settings.batteryHighFrequencyAlways
        geekHighFrequency = settings.version.recordsBatteryTimeline
        // 非极客版开启「为极客体验版本采样」且高频轮询在跑时，按极客的频率同步写库。
        val geekSampling = settings.version.recordsBatteryTimeline ||
            (settings.geekSamplingInOtherVersions && settings.batteryHighFrequencyAlways)

        val raw = reader.read()
        val correction = BatteryCalc.resolveCorrection(
            cellsSetting = settings.batteryCells,
            rawDesignMah = raw.designCapacityMah,
            energyDesignMah = raw.energyDesignCapacityMah,
            voltageMv = raw.voltageMv
        )
        // 双芯校正只作用于计算与展示，原始电量百分比等采样保持不动。
        val reading = BatteryCalc.applyCorrection(raw, correction)
        val resolution = BatteryCalc.resolveRatedCapacity(
            correction = correction,
            designRawMah = raw.designCapacityMah,
            ratedDesignMah = reading.designCapacityMah,
            learnedCapacityMah = learnedCapacityMah,
            manualMah = settings.ratedCapacityMah
        )
        val rated = resolution.ratedMah

        val snapshot = repository.onReading(
            reading = reading,
            nowMillis = now,
            ratedCapacityMah = rated,
            todayStartMillis = BatteryCalc.todayStart(now)
        )
        learnedCapacityMah = snapshot.learnedCapacityMah ?: learnedCapacityMah

        geekRepository.onReading(
            reading = reading,
            nowMillis = now,
            ratedCapacityMah = rated,
            recordTimeline = geekSampling,
            recordDischarge = settings.version.recordsDischargeHistory || geekSampling,
            recordAppPower = settings.version.recordsAppPower || geekSampling
        )

        val fullMah = reading.fullCapacityMah
            ?: BatteryCalc.estimateFullCapacityMah(reading.chargeCounterMah, reading.levelPct)
        _state.value = MonitorState(
            reading = reading,
            ratedCapacityMah = rated,
            ratedIsAuto = settings.ratedCapacityMah <= 0,
            systemDesignMah = resolution.designMah,
            fullCapacityMah = fullMah,
            healthPredictionPct = BatteryCalc.healthPercent(fullMah, rated),
            learnedHealthPct = snapshot.learnedHealthPct,
            learnedSamples = snapshot.learnedSamples,
            currentCharge = snapshot.currentCharge,
            lastCharge = snapshot.lastCharge,
            todayChargeCount = snapshot.todayChargeCount,
            updatedAt = now,
            ratedSource = resolution.source,
            batteryCells = resolution.cells,
            batteryCellsSetting = settings.batteryCells.coerceIn(0, 2),
            systemDesignRawMah = resolution.designRawMah,
            designVoltageMv = raw.designVoltageMv,
            rawVoltageMv = raw.voltageMv,
            energyDesignMah = raw.energyDesignCapacityMah
        )
    }

    private companion object {
        const val HIGH_FREQ_INTERVAL_MILLIS = 1_000L
        const val LOW_FREQ_INTERVAL_MILLIS = 60_000L
    }
}
