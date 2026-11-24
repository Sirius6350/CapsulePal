package com.capsulepal.app.scheduler

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.TelephonyManager
import com.capsulepal.app.CapsuleApp
import com.capsulepal.app.domain.SignalType
import com.capsulepal.app.domain.SystemSignal
import com.capsulepal.app.signals.BluetoothDeviceReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 清单注册的系统信号接收器。
 *
 * 充电、耳机、通话这类广播可以在 App 进程被回收后唤醒进程，
 * 让主动提醒不必依赖 App 一直停留在前台。
 *
 * WiFi 连接没有可用的清单广播，改由 PendingIntent 版网络回调唤醒
 * （见 [WifiSignalSource.setWakeEnabled]），拉起后统一走 [ACTION_WIFI_AVAILABLE]。
 */
class SystemSignalReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? CapsuleApp ?: return
        if (intent.action == ACTION_WIFI_AVAILABLE) {
            deliver(app) { app.container.onWifiWake() }
            return
        }
        val signal = when (intent.action) {
            Intent.ACTION_POWER_CONNECTED -> SystemSignal(SignalType.POWER_CONNECTED)
            Intent.ACTION_POWER_DISCONNECTED -> SystemSignal(SignalType.POWER_DISCONNECTED)
            Intent.ACTION_HEADSET_PLUG -> {
                val plugged = intent.getIntExtra("state", 0) == 1
                SystemSignal(
                    if (plugged) SignalType.HEADSET_PLUGGED else SignalType.HEADSET_UNPLUGGED
                )
            }
            TelephonyManager.ACTION_PHONE_STATE_CHANGED -> {
                val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
                when (state) {
                    TelephonyManager.EXTRA_STATE_RINGING -> {
                        lastCallState = TelephonyManager.CALL_STATE_RINGING
                        return
                    }
                    TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                        lastCallState = TelephonyManager.CALL_STATE_OFFHOOK
                        return
                    }
                    TelephonyManager.EXTRA_STATE_IDLE -> {
                        val wasConnected =
                            lastCallState == TelephonyManager.CALL_STATE_OFFHOOK
                        lastCallState = TelephonyManager.CALL_STATE_IDLE
                        if (!wasConnected) return
                        SystemSignal(SignalType.CALL_ENDED)
                    }
                    else -> return
                }
            }
            BluetoothDevice.ACTION_ACL_CONNECTED,
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                val device = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(
                        BluetoothDevice.EXTRA_DEVICE,
                        BluetoothDevice::class.java
                    )
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                } ?: return
                val (name, address) = BluetoothDeviceReader.describe(context, device)
                val label = BluetoothDeviceReader.displayName(name, address)
                SystemSignal(
                    type = if (intent.action == BluetoothDevice.ACTION_ACL_CONNECTED) {
                        SignalType.BLUETOOTH_CONNECTED
                    } else {
                        SignalType.BLUETOOTH_DISCONNECTED
                    },
                    detail = if (name.isNotBlank()) name else address.ifBlank { label },
                    extra = address
                )
            }
            else -> return
        }
        deliver(app) { app.container.onExternalSignal(signal) }
    }

    /**
     * 被广播唤醒的进程在 onReceive 返回后随时可能被冻结，
     * 用 goAsync 把进程撑到提醒真正发出（含 3 秒仲裁窗口），避免提醒丢失。
     */
    private fun deliver(app: CapsuleApp, block: suspend () -> Unit) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                runCatching { block() }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        /** 系统级网络唤醒广播（由 PendingIntent 发出，不需要清单 intent-filter）。 */
        const val ACTION_WIFI_AVAILABLE = "com.capsulepal.app.action.WIFI_AVAILABLE"

        /** 进程存活期间记住上一通电话状态，避免未接来电也触发“通话结束”。 */
        private var lastCallState = TelephonyManager.CALL_STATE_IDLE
    }
}
