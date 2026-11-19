package com.capsulepal.app.signals

import android.content.Context
import com.capsulepal.app.domain.SignalType
import com.capsulepal.app.domain.SystemSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

class SystemSignalProvider(
    private val context: Context,
    private val scope: CoroutineScope
) {

    private val _signals = MutableSharedFlow<SystemSignal>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val signals: SharedFlow<SystemSignal> = _signals.asSharedFlow()

    val mediaPlaying = MediaSignalStore.playing

    private val emit: (SystemSignal) -> Unit = { _signals.tryEmit(it) }

    private val power = PowerSignalSource(context, emit)
    private val headset = HeadsetSignalSource(context, emit)
    private val wifi = WifiSignalSource(context, scope, emit)
    private val bluetooth = BluetoothSignalSource(context, scope, emit)
    private val call = CallSignalSource(context, emit)
    private val appUsage = AppUsageSignalSource(context, scope, emit)
    private val calendar = CalendarSignalSource(context, emit)
    private val media = MediaSignalSource(context, emit)

    private var started = false

    fun start() {
        if (started) return
        started = true
        power.start()
        headset.start()
        wifi.start()
        bluetooth.start()
        call.start()
        appUsage.start()
        calendar.start()

        scope.launch {
            MediaSignalStore.playing
                .drop(1)
                .distinctUntilChanged()
                .collect { media.onPlayingChanged(it) }
        }
    }

    fun stop() {
        if (!started) return
        power.stop()
        headset.stop()
        wifi.stop()
        bluetooth.stop()
        call.stop()
        appUsage.stop()
        calendar.stop()
        started = false
    }

    /**
     * 通话监听等信号源依赖运行时权限，用户刚授权时进程里的接收器可能还没注册。
     * 规则保存 / 回到前台时重试一次，避免「授权了但规则不触发」。
     */
    fun refreshWakeableSources() {
        if (!started) return
        call.start()
        appUsage.start()
    }

    fun refreshCalendar(leadMinutes: Int = 15) = calendar.checkSoon(leadMinutes)

    fun firstUpcomingCalendarEvent(): CalendarEvent? = calendar.firstUpcoming()

    fun calendarPermissionGranted(): Boolean = calendar.hasPermission()
}
