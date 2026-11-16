package com.capsulepal.app.notifier

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

data class ValidationResult(
    val eligible: Boolean,
    val reasons: List<String>
) {
    val summary: String
        get() = if (eligible) "满足实况通知条件" else reasons.joinToString("；")
}

object NotificationValidator {

    fun validate(context: Context, notification: Notification, channelId: String): ValidationResult {
        val reasons = mutableListOf<String>()

        if (Build.VERSION.SDK_INT < 36) {
            reasons += "系统版本低于 Android 16，无法使用实况通知"
        }
        if (!hasPostPermission(context)) {
            reasons += "缺少通知权限"
        }

        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager == null) {
            reasons += "通知服务不可用"
            return ValidationResult(false, reasons)
        }
        if (!manager.areNotificationsEnabled()) {
            reasons += "系统通知总开关已关闭"
        }

        val channel = manager.getNotificationChannel(channelId)
        if (channel == null) {
            reasons += "通知渠道尚未创建"
        } else if (channel.importance == NotificationManager.IMPORTANCE_NONE) {
            reasons += "通知渠道已被关闭"
        } else if (channel.importance == NotificationManager.IMPORTANCE_MIN) {
            reasons += "通知渠道等级过低（IMPORTANCE_MIN）"
        }

        if (Build.VERSION.SDK_INT >= 36) {
            if (!manager.canPostPromotedNotifications()) {
                reasons += "系统未允许实况通知（canPostPromotedNotifications = false）"
            }
            if (!notification.hasPromotableCharacteristics()) {
                reasons += "通知结构不满足提升条件"
            }
        }

        if (notification.contentView != null) {
            reasons += "使用了自定义 RemoteViews"
        }
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) {
            reasons += "被设置为通知组摘要"
        }
        if (notification.flags and Notification.FLAG_ONGOING_EVENT == 0) {
            reasons += "通知不是进行中（ongoing）"
        }
        if (notification.extras.getCharSequence(Notification.EXTRA_TITLE).isNullOrBlank()) {
            reasons += "缺少 contentTitle"
        }

        return ValidationResult(reasons.isEmpty(), reasons)
    }

    fun hasPostPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun canPostPromoted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 36) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return manager.canPostPromotedNotifications()
    }
}
