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
    val updatedAt: Long
) {
    val chargePowerW: Float?
        get() = BatteryCalc.chargePowerW(reading.voltageMv, reading.currentMa)
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

        val reading = reader.read()
        val manual = settings.ratedCapacityMah.takeIf { it > 0 }
        val rated = manual ?: reading.designCapacityMah ?: BatteryCalc.DEFAULT_RATED_MAH

        val snapshot = repository.onReading(
            reading = reading,
            nowMillis = now,
            ratedCapacityMah = rated,
            todayStartMillis = BatteryCalc.todayStart(now)
        )

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
            ratedIsAuto = manual == null,
            systemDesignMah = reading.designCapacityMah,
            fullCapacityMah = fullMah,
            healthPredictionPct = BatteryCalc.healthPercent(fullMah, rated),
            learnedHealthPct = snapshot.learnedHealthPct,
            learnedSamples = snapshot.learnedSamples,
            currentCharge = snapshot.currentCharge,
            lastCharge = snapshot.lastCharge,
            todayChargeCount = snapshot.todayChargeCount,
            updatedAt = now
        )
    }

    private companion object {
        const val HIGH_FREQ_INTERVAL_MILLIS = 1_000L
        const val LOW_FREQ_INTERVAL_MILLIS = 60_000L
    }
}
