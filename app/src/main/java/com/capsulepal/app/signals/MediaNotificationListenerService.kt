package com.capsulepal.app.signals

import android.content.ComponentName
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class MediaNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        refresh()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        refresh()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        refresh()
    }

    private fun refresh() {
        MediaSignalStore.update(readPlaying())
    }

    private fun readPlaying(): Boolean {
        val manager = getSystemService(MediaSessionManager::class.java) ?: return false
        val component = ComponentName(this, MediaNotificationListenerService::class.java)
        val sessions = runCatching { manager.getActiveSessions(component) }.getOrNull() ?: return false
        return sessions.any { it.playbackState?.state == PlaybackState.STATE_PLAYING }
    }
}
