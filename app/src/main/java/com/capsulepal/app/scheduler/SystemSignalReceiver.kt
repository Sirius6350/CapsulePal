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

/**
 * 清单注册的系统信号接收器。
 *
 * 充电、耳机、通话这类广播可以在 App 进程被回收后唤醒进程，
 * 让主动提醒不必依赖 App 一直停留在前台。
 */
class SystemSignalReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? CapsuleApp ?: return
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
        app.container.onExternalSignal(signal)
    }

    private companion object {
        /** 进程存活期间记住上一通电话状态，避免未接来电也触发“通话结束”。 */
        var lastCallState = TelephonyManager.CALL_STATE_IDLE
    }
}
