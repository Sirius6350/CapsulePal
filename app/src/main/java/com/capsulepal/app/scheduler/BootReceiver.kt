package com.capsulepal.app.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.capsulepal.app.CapsuleApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != Intent.ACTION_TIME_CHANGED &&
            action != Intent.ACTION_TIMEZONE_CHANGED
        ) return

        val app = context.applicationContext as? CapsuleApp ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                app.container.refreshAfterBoot()
            } finally {
                pending.finish()
            }
        }
    }
}
