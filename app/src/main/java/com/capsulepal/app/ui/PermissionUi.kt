package com.capsulepal.app.ui

import android.Manifest
import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.capsulepal.app.R
import com.capsulepal.app.ui.components.Pill
import com.capsulepal.app.ui.components.bounceClick
import com.capsulepal.app.ui.theme.Mint
import com.capsulepal.app.ui.theme.MintSoft

data class PermissionState(
    val notifications: Boolean = false,
    val overlay: Boolean = false,
    val exactAlarm: Boolean = false,
    val batteryUnrestricted: Boolean = false,
    val calendar: Boolean = false,
    val phoneState: Boolean = false,
    val bluetooth: Boolean = false,
    val notificationListener: Boolean = false,
    val location: Boolean = false,
    val usageAccess: Boolean = false
) {
    val coreReady: Boolean
        get() = notifications && overlay && exactAlarm
}

fun readPermissionState(context: Context): PermissionState = PermissionState(
    notifications = hasNotificationPermission(context),
    overlay = Settings.canDrawOverlays(context),
    exactAlarm = context.getSystemService(AlarmManager::class.java)
        ?.canScheduleExactAlarms() == true,
    batteryUnrestricted = context.getSystemService(PowerManager::class.java)
        ?.isIgnoringBatteryOptimizations(context.packageName) == true,
    calendar = granted(context, Manifest.permission.READ_CALENDAR),
    phoneState = granted(context, Manifest.permission.READ_PHONE_STATE),
    bluetooth = Build.VERSION.SDK_INT < 31 ||
        granted(context, Manifest.permission.BLUETOOTH_CONNECT),
    notificationListener = NotificationManagerCompat
        .getEnabledListenerPackages(context)
        .contains(context.packageName),
    // 后台也要能读 WiFi 名称：Android 13+ 依赖「附近的设备」，只有定位会被系统抹掉 SSID。
    location = com.capsulepal.app.signals.WifiSsidReader.hasSsidPermission(context),
    usageAccess = com.capsulepal.app.signals.UsageAccess.granted(context)
)

fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < 33 ||
        granted(context, Manifest.permission.POST_NOTIFICATIONS)

private fun granted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

fun openSafely(context: Context, intent: Intent) {
    runCatching {
        if (context !is android.app.Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}

fun overlaySettingsIntent(context: Context): Intent =
    Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}")
    )

fun exactAlarmSettingsIntent(context: Context): Intent =
    Intent(
        Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
        Uri.parse("package:${context.packageName}")
    )

fun batteryOptimizationIntent(context: Context): Intent =
    Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}")
    )

fun appDetailsIntent(context: Context): Intent =
    Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${context.packageName}")
    )

fun notificationListenerIntent(): Intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

fun usageAccessSettingsIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

/**
 * ColorOS 的自启动页没有公开标准 action，逐个探测，找不到就退回应用详情。
 */
fun colorOsAutoStartIntent(context: Context): Intent {
    val candidates = listOf(
        ComponentName(
            "com.coloros.safecenter",
            "com.coloros.safecenter.permission.startup.StartupAppListActivity"
        ),
        ComponentName(
            "com.oppo.safe",
            "com.oppo.safe.permission.startup.StartupAppListActivity"
        ),
        ComponentName(
            "com.coloros.oppoappmgmt",
            "com.coloros.oppomgr.permission.startup.StartupAppListActivity"
        )
    )
    candidates.forEach { component ->
        val intent = Intent().setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val resolved = context.packageManager.resolveActivity(intent, 0)
        if (resolved != null) return intent
    }
    return appDetailsIntent(context)
}

@Composable
fun PermissionRow(
    @androidx.annotation.DrawableRes iconRes: Int,
    title: String,
    description: String,
    granted: Boolean,
    modifier: Modifier = Modifier,
    grantedLabel: String = "已开启",
    actionLabel: String = "去开启",
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (granted) Modifier else Modifier.bounceClick(onClick = onClick))
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(10.dp))
        if (granted) {
            Pill(text = grantedLabel, color = MintSoft, contentColor = Mint)
        } else {
            Pill(
                text = actionLabel,
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}

@Composable
fun PermissionSectionHint(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun rememberPermissionContext(): Context = LocalContext.current
