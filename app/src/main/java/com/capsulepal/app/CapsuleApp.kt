package com.capsulepal.app

import android.app.Application
import com.capsulepal.app.notifier.NotificationChannels

class CapsuleApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.ensure(this)
        container = AppContainer(this).also { it.start() }
    }
}
