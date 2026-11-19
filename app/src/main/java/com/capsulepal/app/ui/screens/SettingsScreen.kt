package com.capsulepal.app.ui.screens

import android.Manifest
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.capsulepal.app.BuildConfig
import com.capsulepal.app.R
import com.capsulepal.app.data.model.CategoryCatalog
import com.capsulepal.app.data.model.CategoryEntry
import com.capsulepal.app.data.model.CapsuleVersion
import com.capsulepal.app.notifier.ValidationResult
import com.capsulepal.app.data.model.OverduePolicy
import com.capsulepal.app.data.model.TaskCategory
import com.capsulepal.app.data.model.iconRes
import com.capsulepal.app.domain.HolidayProvider
import com.capsulepal.app.ui.CapsuleUiState
import com.capsulepal.app.ui.CapsuleViewModel
import com.capsulepal.app.ui.PermissionRow
import com.capsulepal.app.ui.PermissionState
import com.capsulepal.app.ui.appDetailsIntent
import com.capsulepal.app.ui.batteryOptimizationIntent
import com.capsulepal.app.ui.exactAlarmSettingsIntent
import com.capsulepal.app.ui.notificationListenerIntent
import com.capsulepal.app.ui.openSafely
import com.capsulepal.app.ui.overlaySettingsIntent
import com.capsulepal.app.ui.readPermissionState
import com.capsulepal.app.ui.components.CapsuleButton
import com.capsulepal.app.ui.components.ChoiceChip
import com.capsulepal.app.ui.components.ElasticSwitch
import com.capsulepal.app.ui.components.Pill
import com.capsulepal.app.ui.components.SoftCard
import com.capsulepal.app.ui.components.bounceClick
import com.capsulepal.app.ui.theme.Butter
import com.capsulepal.app.ui.theme.Coral
import com.capsulepal.app.ui.theme.CoralSoft
import com.capsulepal.app.ui.theme.Mint
import com.capsulepal.app.ui.theme.MintSoft
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.math.roundToInt

private const val SETTINGS_HOME = 0
private const val SETTINGS_DATA = 2
private const val SETTINGS_KEEPALIVE = 3
private const val SETTINGS_OTHER = 4
private const val SETTINGS_ABOUT = 5

private const val GITEE_REPO_URL = "https://gitee.com/Sirius6350/CapsulePal"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: CapsuleUiState,
    vm: CapsuleViewModel,
    onShowColorOsGuide: () -> Unit,
    onOpenReminderRules: () -> Unit,
    onBack: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var permissions by remember { mutableStateOf(readPermissionState(context)) }
    val validation by vm.lastValidation.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val testStartedAt by vm.testStartedAt.collectAsStateWithLifecycle()
    val settings = state.settings
    var showCategoryOrderDialog by remember { mutableStateOf(false) }
    var showUserNotice by remember { mutableStateOf(false) }
    var showOpenSource by remember { mutableStateOf(false) }
    var showOverlayPermissionDialog by remember { mutableStateOf(false) }
    var pendingImportUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var showClearPastDialog by remember { mutableStateOf(false) }
    var showClearAllDialog by remember { mutableStateOf(false) }
    var showVersionDialog by remember { mutableStateOf(false) }
    var settingsPage by rememberSaveable { mutableStateOf(SETTINGS_HOME) }
    var pendingVersion by remember(settings.version) { mutableStateOf(settings.version) }
    val pastTaskCount by vm.pastTaskCount.collectAsStateWithLifecycle()
    var overlayAlphaValue by remember(settings.overlayAlphaSafe) {
        mutableFloatStateOf(settings.overlayAlphaSafe.toFloat())
    }
    val reminderRuleCount = settings.reminderRules.count { it.enabled }
    val hasTaskModules = settings.version.showTodoPage ||
        settings.version.showSchedulePage ||
        settings.version.showScheduleEntry ||
        settings.version.showPlansPage

    BackHandler(enabled = settingsPage != SETTINGS_HOME) { settingsPage = SETTINGS_HOME }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissions = readPermissionState(context)
                vm.refreshPermissionsState()
                // 从系统设置授权回来时重新挂一次悬浮点，否则会一直停留在“看不见”的状态。
                vm.refreshOverlayDot()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { permissions = readPermissionState(context) }

    val signalLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions = readPermissionState(context) }

    val backupDirLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> if (uri != null) vm.chooseBackupDirectory(uri) }

    val backupImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) pendingImportUri = uri }

    val backupDirLabel = remember(settings.backupDirUri) {
        if (settings.backupDirUri.isBlank()) {
            "未设置"
        } else {
            runCatching {
                android.net.Uri.decode(settings.backupDirUri)
                    .substringAfterLast('/')
                    .substringAfterLast(':')
            }.getOrDefault("已设置")
        }
    }

    if (pendingImportUri != null) {
        AlertDialog(
            onDismissRequest = { pendingImportUri = null },
            title = { Text("导入备份？") },
            text = {
                Text("导入会覆盖当前的所有待办、日程与主动提醒规则，且无法撤销。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val uri = pendingImportUri
                        pendingImportUri = null
                        uri?.let(vm::importBackup)
                    }
                ) {
                    Text("覆盖导入")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingImportUri = null }) { Text("取消") }
            }
        )
    }

    // 设置页整体关闭点击波纹，避免出现淡灰色方框。
    CompositionLocalProvider(
        LocalIndication provides NoIndication,
        LocalRippleConfiguration provides null
    ) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (settingsPage == SETTINGS_HOME) {
            item {
                Column {
                    if (onBack != null) {
                        TextButton(
                            onClick = onBack,
                            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_ui_back),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "返回",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Text(
                        text = "设置",
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = "版本、提醒、权限与本地数据。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            item {
                VersionCard(
                    version = settings.version,
                    onClick = {
                        pendingVersion = settings.version
                        showVersionDialog = true
                    }
                )
            }
            if (settings.version.showReminders) {
                item {
                    SettingsGroup(title = "提醒") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .bounceClick(
                                    shape = RoundedCornerShape(18.dp),
                                    onClick = onOpenReminderRules
                                )
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_ui_sparkle),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "主动提醒规则",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "按 WiFi、蓝牙、充电、日历、应用等情境自动提醒",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Pill(
                                text = "$reminderRuleCount 条开启",
                                color = MintSoft,
                                contentColor = Mint
                            )
                            Icon(
                                painter = painterResource(R.drawable.ic_ui_chevron_right),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .padding(start = 4.dp)
                                    .size(18.dp)
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        SettingSwitchRow(
                            title = "灵动岛之外再发一条通知",
                            description = "避免错过，结束后自动收起",
                            checked = settings.dualReminderEnabled,
                            onCheckedChange = vm::setDualReminder
                        )
                        if (settings.dualReminderEnabled && !permissions.notifications) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            PermissionRow(
                                iconRes = R.drawable.ic_ui_bell,
                                title = "通知权限",
                                description = "未授权时第二条通知不会出现，只保留灵动岛",
                                granted = false,
                                actionLabel = "去开启",
                                onClick = {
                                    if (Build.VERSION.SDK_INT >= 33) {
                                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    } else {
                                        openSafely(context, appDetailsIntent(context))
                                    }
                                }
                            )
                        }
                    }
                }
            }
            item {
                SettingsNavCard {
                    HomeNavRow(
                        iconRes = R.drawable.ic_ui_database,
                        title = "数据与备份",
                        detail = "本地备份、清理与判定",
                        onClick = { settingsPage = SETTINGS_DATA }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    HomeNavRow(
                        iconRes = R.drawable.ic_ui_shield,
                        title = "保活与权限",
                        detail = "保活通道、电池监测与权限引导",
                        onClick = { settingsPage = SETTINGS_KEEPALIVE }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    HomeNavRow(
                        iconRes = R.drawable.ic_settings,
                        title = "其他设置",
                        detail = "分类编辑与成长节奏",
                        onClick = { settingsPage = SETTINGS_OTHER }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    HomeNavRow(
                        iconRes = R.drawable.ic_capsule,
                        title = "关于应用",
                        detail = "应用信息、开源组件与数据隐私",
                        onClick = { settingsPage = SETTINGS_ABOUT }
                    )
                }
            }
        } else {
            item {
                Column {
                    TextButton(
                        onClick = { settingsPage = SETTINGS_HOME },
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_ui_back),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "返回",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = when (settingsPage) {
                            SETTINGS_DATA -> "数据与备份"
                            SETTINGS_KEEPALIVE -> "保活与权限"
                            SETTINGS_OTHER -> "其他设置"
                            else -> "关于应用"
                        },
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
        }
        if (settingsPage == SETTINGS_DATA) {
        item {
            SettingsGroup(title = "本地备份") {
                Column {
                    BackupRow(
                        title = "保存位置",
                        detail = backupDirLabel,
                        action = "选择",
                        onClick = { backupDirLauncher.launch(null) }
                    )
                    BackupDivider()
                    BackupRow(
                        title = "手动备份",
                        detail = "覆盖上一次的手动备份",
                        action = "立即备份",
                        onClick = { vm.manualBackup() }
                    )
                    BackupDivider()
                    BackupRow(
                        title = "导入备份",
                        detail = "覆盖当前待办、日程与主动规则",
                        action = "导入",
                        onClick = {
                            backupImportLauncher.launch(
                                arrayOf(
                                    "application/json",
                                    "text/plain",
                                    "application/octet-stream"
                                )
                            )
                        }
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "快速备份：点击「今天」页顶部伙伴气泡的任意区域。\n" +
                            "自动备份：新增待办、日程或主动规则时自动保存。\n" +
                            "三种备份各自固定一个文件，重复备份会覆盖上一次，不会越存越多。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        item {
            SettingsGroup(title = if (hasTaskModules) "清理与判定" else "本机数据") {
                if (hasTaskModules) {
                    Text(
                        text = "超时未处理判定",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = "第二天仍未完成的待办会按此方式结算，不发放经验。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OverduePolicy.entries.forEach { policy ->
                            ChoiceChip(
                                text = policy.label,
                                selected = settings.overduePolicy == policy,
                                onClick = { vm.setOverduePolicy(policy) }
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingSwitchRow(
                        title = "完成后次日隐藏",
                        description = "已完成或已跳过的待办从次日起不再显示在待办页，数据仍保留。",
                        checked = settings.hideFinishedAfterDay,
                        onCheckedChange = vm::setHideFinishedAfterDay
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    BackupRow(
                        title = "清除已过期待办与日程",
                        detail = "将删除 $pastTaskCount 条早于今天的记录，今天及以后保留",
                        action = "清除",
                        onClick = { showClearPastDialog = true }
                    )
                    BackupDivider()
                }
                BackupRow(
                    title = "清除全部本机数据",
                    detail = if (hasTaskModules) {
                        "待办、日程、分组、提醒规则、伙伴等级与设置都会清空"
                    } else {
                        "电池学习健康度、充放电记录、24 小时采样、应用功耗历史与设置都会清空"
                    },
                    action = "清除",
                    danger = true,
                    onClick = { showClearAllDialog = true }
                )
            }
        }
        }
        if (settingsPage == SETTINGS_KEEPALIVE) {
            item {
            SettingsGroup(title = "保活通道") {
                SettingSwitchRow(
                    title = "后台保持陪伴",
                    description = if (settings.version.recordsBatteryTimeline) {
                        "悬浮点显示开关；极客采样服务会常驻，不受此开关影响"
                    } else {
                        "悬浮点总开关；开启时重置到左上角（默认 80% 透明度、3 分钟移动）"
                    },
                    checked = settings.keepAliveEnabled,
                    onCheckedChange = { enabled ->
                        vm.setKeepAlive(enabled)
                        if (enabled && !permissions.overlay) {
                            showOverlayPermissionDialog = true
                        }
                    }
                )
                if (settings.keepAliveEnabled && !permissions.overlay) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .bounceClick(shape = RoundedCornerShape(14.dp)) {
                                showOverlayPermissionDialog = true
                            },
                        shape = RoundedCornerShape(14.dp),
                        color = CoralSoft
                    ) {
                        Text(
                            text = "还没授予悬浮窗权限，悬浮点不会显示。点此授权。",
                            style = MaterialTheme.typography.labelSmall,
                            color = Coral,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)
                        )
                    }
                }
                if (settings.keepAliveEnabled) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingSwitchRow(
                        title = "悬浮点隐藏",
                        description = "隐藏后固定 10% 透明度、1px 大小，仍按间隔移动",
                        checked = settings.overlayHidden,
                        onCheckedChange = { hidden -> vm.setOverlayHidden(hidden) }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column(modifier = Modifier.padding(vertical = 12.dp)) {
                        Text(
                            text = "悬浮点移动间隔",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Medium
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = "按设定间隔随机移动，避免长期停在同一个位置",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(1, 3, 5, 10).forEach { minutes ->
                                ChoiceChip(
                                    text = "$minutes 分",
                                    selected = settings.overlayMoveMinutesSafe == minutes,
                                    onClick = { vm.setOverlayMoveMinutes(minutes) }
                                )
                            }
                        }
                    }
                    if (!settings.overlayHidden) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Column(modifier = Modifier.padding(vertical = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "悬浮点透明度",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = FontWeight.Medium
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "${overlayAlphaValue.roundToInt()}%",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = FontWeight.Medium
                                    ),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = "范围 10%–80%，每次调整 10%；数值越低越隐蔽",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(2.dp))
                            Slider(
                                value = overlayAlphaValue,
                                onValueChange = { raw ->
                                    overlayAlphaValue =
                                        ((raw / 10f).roundToInt() * 10).coerceIn(10, 80).toFloat()
                                },
                                onValueChangeFinished = {
                                    vm.setOverlayAlphaPercent(overlayAlphaValue.roundToInt())
                                },
                                valueRange = 10f..80f,
                                steps = 6
                            )
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingSwitchRow(
                    title = "隐藏后台",
                    description = "从最近任务列表移除本应用卡片，防止误滑杀；保活服务与通知不受影响",
                    checked = settings.hiddenBackground,
                    onCheckedChange = vm::setHiddenBackground
                )
            }
        }
            if (settings.version.showReminders) {
                item {
                    IslandChannelGroup(
                        validation = validation,
                        now = now,
                        testStartedAt = testStartedAt,
                        onStartTest = vm::startIslandTest,
                        onRefreshNow = vm::refreshNotificationsNow,
                        onReschedule = {
                            vm.refreshPermissionsState()
                            Toast.makeText(context, "已重新调度全部提醒", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        if (settings.version.showMonitorPage) {
            item {
                SettingsGroup(title = "电池监测") {
                    if (settings.version.recordsBatteryTimeline) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "极客体验已锁定",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = FontWeight.Medium
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    text = "充放电统一每 1 秒采样，避免误关；采样由常驻保活服务驱动。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Pill(
                                text = "已锁定",
                                color = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    } else {
                        SettingSwitchRow(
                            title = "始终保持高频次监测轮询",
                            description = "充电时每 1 秒采集；打开后放电也按 1 秒，耗电略高",
                            checked = settings.batteryHighFrequencyAlways,
                            onCheckedChange = vm::setBatteryHighFrequencyAlways
                        )
                        if (settings.batteryHighFrequencyAlways) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            SettingSwitchRow(
                                title = "为极客体验版本采样",
                                description = "沿用 1 秒轮询同步记录 24 小时曲线、充放电会话与应用功耗；" +
                                    "切到极客体验即可看到这段历史，会常驻一条保活通知",
                                checked = settings.geekSamplingInOtherVersions,
                                onCheckedChange = vm::setGeekSamplingInOtherVersions
                            )
                        }
                    }
                }
            }
        }
        }
        if (settingsPage == SETTINGS_OTHER) {
        if (settings.version.showTodoPage || settings.version.showPartnerPage) {
            item {
                SettingsGroup(title = "成长节奏") {
                Text(
                    text = "每天前几次完成拿全额成长",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "之后递减，撤销完成会回退。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(3, 4, 5).forEach { count ->
                        ChoiceChip(
                            text = "$count 次",
                            selected = settings.dailyFullXpCount == count,
                            onClick = { vm.setDailyFullXp(count) }
                        )
                    }
                }
            }
        }
        }
        if (settings.version.showTodoPage) {
        item {
            SettingsGroup(title = "分类", contentPadding = PaddingValues(0.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .bounceClick(shape = RoundedCornerShape(26.dp)) { showCategoryOrderDialog = true }
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "分类编辑",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "前 7 个直接显示；点右侧按钮输入序号排序，自建分类可删除",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        painter = painterResource(R.drawable.ic_ui_chevron_right),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        }
        }
        if (settingsPage == SETTINGS_KEEPALIVE) {
        item {
            SettingsGroup(title = "权限与引导") {
                PermissionRow(
                    iconRes = R.drawable.ic_ui_bell,
                    title = "通知权限",
                    description = "普通提醒与灵动岛/流体云的基础",
                    granted = permissions.notifications,
                    onClick = {
                        if (Build.VERSION.SDK_INT >= 33) {
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            openSafely(context, appDetailsIntent(context))
                        }
                    }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                PermissionRow(
                    iconRes = R.drawable.ic_notification,
                    title = "悬浮窗权限",
                    description = "显示悬浮点需要它；隐藏后缩为 1px 纯保活",
                    granted = permissions.overlay,
                    onClick = { openSafely(context, overlaySettingsIntent(context)) }
                )
                if (settings.version.showReminders) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    PermissionRow(
                        iconRes = R.drawable.ic_timer,
                        title = "精确闹钟",
                        description = if (permissions.exactAlarm) {
                            "准备、开始、结束三个时间点可以准点触发"
                        } else {
                            "未授权时会自动降级为非精确调度，可能晚几分钟"
                        },
                        granted = permissions.exactAlarm,
                        onClick = { openSafely(context, exactAlarmSettingsIntent(context)) }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                PermissionRow(
                    iconRes = R.drawable.ic_ui_battery,
                    title = "电池优化白名单",
                    description = "允许后台活动，降低被系统回收的概率",
                    granted = permissions.batteryUnrestricted,
                    grantedLabel = "已放行",
                    onClick = { openSafely(context, batteryOptimizationIntent(context)) }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                PermissionRow(
                    iconRes = R.drawable.ic_ui_shield,
                    title = "后台守护",
                    description = "自启动、耗电管理、最近任务锁定",
                    granted = false,
                    actionLabel = "看步骤",
                    onClick = onShowColorOsGuide
                )
                if (settings.version.showReminders) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    PermissionRow(
                        iconRes = R.drawable.ic_calendar,
                        title = "日历 / 通话 / 蓝牙（可选）",
                        description = "读取日历事件、通话结束、耳机连接，只在本机使用",
                        granted = permissions.calendar && permissions.phoneState && permissions.bluetooth,
                        grantedLabel = "已授权",
                        actionLabel = "去授权",
                        onClick = {
                            val list = mutableListOf<String>()
                            list += Manifest.permission.READ_CALENDAR
                            list += Manifest.permission.READ_PHONE_STATE
                            if (Build.VERSION.SDK_INT >= 31) {
                                list += Manifest.permission.BLUETOOTH_CONNECT
                            }
                            signalLauncher.launch(list.toTypedArray())
                        }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    PermissionRow(
                        iconRes = R.drawable.ic_ui_headphone,
                        title = "媒体播放状态（可选）",
                        description = "需要通知使用权，只读取当前是否在播放",
                        granted = permissions.notificationListener,
                        grantedLabel = "已授权",
                        actionLabel = "去开启",
                        onClick = { openSafely(context, notificationListenerIntent()) }
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "可选权限未授权时，对应信号自动跳过。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        }
        if (settingsPage == SETTINGS_ABOUT) {
        item {
            SettingsGroup(title = "应用信息", contentPadding = PaddingValues(0.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .bounceClick(shape = RoundedCornerShape(26.dp)) { showUserNotice = true }
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "用户须知",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "使用注意事项",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        painter = painterResource(R.drawable.ic_ui_chevron_right),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .bounceClick(shape = RoundedCornerShape(26.dp)) { showOpenSource = true }
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "开源技术",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "使用的开源组件、许可证与版权声明",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        painter = painterResource(R.drawable.ic_ui_chevron_right),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        item {
            SettingsGroup(title = "数据与隐私") {
                PrivacyLine(iconRes = R.drawable.ic_ui_block, title = "没有 INTERNET 权限", detail = "从权限清单上杜绝任何外部数据拉取")
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                PrivacyLine(
                    iconRes = R.drawable.ic_calendar,
                    title = "本地节假日表 ${HolidayProvider.TABLE_VERSION}",
                    detail = "内置数据，随 App 版本更新，不联网查询"
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                PrivacyLine(iconRes = R.drawable.ic_ui_moon, title = "本地农历计算", detail = "离线库计算，零网络成本")
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                PrivacyLine(iconRes = R.drawable.ic_ui_database, title = "数据只留在本机", detail = "Room + DataStore，卸载即清除")
            }
        }
        }
        if (settingsPage == SETTINGS_HOME) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .bounceClick(shape = RoundedCornerShape(18.dp)) {
                            openSafely(
                                context,
                                Intent(Intent.ACTION_VIEW, android.net.Uri.parse(GITEE_REPO_URL))
                            )
                        }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "胶囊伙伴 ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "一次只陪当前这一件事 · 点此查看开源仓库",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }

    if (showOverlayPermissionDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showOverlayPermissionDialog = false },
            title = { Text("需要悬浮窗权限") },
            text = {
                Text(
                    "「后台保持陪伴」需要「显示在其他应用上层」权限。" +
                        "授权后回到本页，悬浮点会自动重置到屏幕左上角。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showOverlayPermissionDialog = false
                        openSafely(context, overlaySettingsIntent(context))
                    }
                ) { Text("去授权") }
            },
            dismissButton = {
                TextButton(onClick = { showOverlayPermissionDialog = false }) {
                    Text("稍后")
                }
            }
        )
    }

    if (showClearPastDialog) {
        AlertDialog(
            onDismissRequest = { showClearPastDialog = false },
            title = { Text("清除已过期待办与日程？") },
            text = {
                Text(
                    "将删除 $pastTaskCount 条日期早于今天的待办与日程，今天及以后的记录不受影响。" +
                        "此操作无法撤销，已导出的备份文件不会被删除。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearPastDialog = false
                        vm.clearPastTasks()
                    }
                ) { Text("清除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearPastDialog = false }) { Text("取消") }
            }
        )
    }

    if (showVersionDialog) {
        VersionPickerDialog(
            current = settings.version,
            pending = pendingVersion,
            onPick = { pendingVersion = it },
            onConfirm = {
                showVersionDialog = false
                if (pendingVersion != settings.version) vm.setVersion(pendingVersion)
            },
            onDismiss = { showVersionDialog = false }
        )
    }

    if (showClearAllDialog) {
        AlertDialog(
            onDismissRequest = { showClearAllDialog = false },
            title = { Text("清除全部本机数据？") },
            text = {
                Text(
                    "待办、日程、分组、提醒规则、伙伴等级、电池学习健康度、" +
                        "充放电记录、24 小时采样、应用功耗历史与全部设置都会清空，" +
                        "所有已调度的提醒也会取消。当前版本会保留。\n" +
                        "已导出的备份文件（包括本机备份）不受影响，可随时重新导入。\n" +
                        "此操作无法撤销。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearAllDialog = false
                        vm.clearAllLocalData()
                    }
                ) { Text("全部清除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllDialog = false }) { Text("取消") }
            }
        )
    }

    if (showCategoryOrderDialog) {
        CategoryOrderDialog(
            layout = settings.categoryLayout,
            onApply = vm::applyCategoryDraft,
            onDismiss = { showCategoryOrderDialog = false }
        )
    }

    if (showUserNotice) {
        UserNoticeDialog(
            mustAccept = false,
            onAccept = { showUserNotice = false },
            onDismiss = { showUserNotice = false }
        )
    }

    if (showOpenSource) {
        OpenSourceDialog(onDismiss = { showOpenSource = false })
    }

    }
}

@Composable
private fun SettingsNavCard(content: @Composable ColumnScope.() -> Unit) {
    SoftCard {
        Column(content = content)
    }
}

@Composable
private fun HomeNavRow(
    iconRes: Int,
    title: String,
    detail: String,
    trailing: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .bounceClick(shape = RoundedCornerShape(14.dp), onClick = onClick)
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(19.dp)
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            Pill(
                text = trailing,
                color = MintSoft,
                contentColor = Mint
            )
        }
        Icon(
            painter = painterResource(R.drawable.ic_ui_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(start = 4.dp)
                .size(18.dp)
        )
    }
}

@Composable
private fun IslandChannelGroup(
    validation: ValidationResult?,
    now: Long,
    testStartedAt: Long,
    onStartTest: () -> Unit,
    onRefreshNow: () -> Unit,
    onReschedule: () -> Unit
) {
    val context = LocalContext.current
    SettingsGroup(title = "上岛通道") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "实况通知",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            if (validation == null) {
                Pill(
                    text = "待校验",
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (validation.eligible) {
                Pill(text = "可上岛", color = MintSoft, contentColor = Mint)
            } else {
                Pill(text = "将降级", color = Coral.copy(alpha = 0.14f), contentColor = Coral)
            }
        }
        if (validation != null && !validation.eligible) {
            Spacer(Modifier.height(8.dp))
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    validation.reasons.take(4).forEach { reason ->
                        Text(
                            text = "· $reason",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = "模拟 1 分钟准备 + 1 分钟进行，不计成长与待办进度。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CapsuleButton(
                text = "上岛测试",
                onClick = onStartTest,
                modifier = Modifier.weight(1f),
                height = 46.dp
            )
            CapsuleButton(
                text = "刷新当前",
                onClick = onRefreshNow,
                modifier = Modifier.weight(1f),
                filled = false,
                height = 46.dp
            )
        }
        if (now - testStartedAt in 0L..125_000L) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "测试进行中，结束后自动收起。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .bounceClick(shape = RoundedCornerShape(14.dp), onClick = onReschedule)
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "重新调度全部提醒",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "按当前权限重新排一遍提醒与监听",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                painter = painterResource(R.drawable.ic_ui_chevron_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun VersionCard(version: CapsuleVersion, onClick: () -> Unit) {
    SoftCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 不用圆角裁剪：说明文字可能贴到卡片底边，18dp 圆角会把最后一行切掉。
                .bounceClick(shape = RectangleShape, onClick = onClick)
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "当前版本",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = version.label,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = version.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(12.dp))
            // 固定 28dp 槽位，避免箭头贴边或在不同字号下被裁切。
            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(R.drawable.ic_ui_chevron_right),
                    contentDescription = "切换版本",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun VersionPickerDialog(
    current: CapsuleVersion,
    pending: CapsuleVersion,
    onPick: (CapsuleVersion) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("切换版本") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 430.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "切换后立即生效，不用重启；数据不会丢失，" +
                        "隐藏的模块停止记录但已有数据保留。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                CapsuleVersion.entries.forEach { item ->
                    val selected = item == pending
                    val shape = RoundedCornerShape(14.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .background(
                                if (selected) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                }
                            )
                            .border(
                                width = if (selected) 1.5.dp else 1.dp,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                },
                                shape = shape
                            )
                            .bounceClick { onPick(item) }
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = item.label,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                if (item == current) {
                                    Spacer(Modifier.width(6.dp))
                                    Pill(
                                        text = "使用中",
                                        color = MaterialTheme.colorScheme.secondaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = item.summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "已有 · ${item.features.joinToString(" · ")}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = if (item.hiddenFeatures.isEmpty()) {
                                    "隐藏 · 无"
                                } else {
                                    "隐藏 · ${item.hiddenFeatures.joinToString(" · ")}"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (selected) {
                            Icon(
                                painter = painterResource(R.drawable.ic_ui_check),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("切换") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

// 不绘制任何点击反馈，供设置页覆盖 LocalIndication 使用。
private object NoIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = Node()

    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = System.identityHashCode(this)

    private class Node : androidx.compose.ui.Modifier.Node()
}

@Composable
private fun CategoryOrderDialog(
    layout: List<CategoryEntry>,
    onApply: (List<CategoryEntry>, List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
    // 横条只要向下移动超过 4dp，松手即视为关闭，不再判定面板/屏幕高度。
    val closeDragThresholdPx = with(density) { 4.dp.toPx() }

    // 草稿模式：排序、删除、恢复默认都只改本地，点「完成」才写入。
    val initialLayout = remember { layout }
    val categoryItems = remember {
        mutableStateListOf<CategoryEntry>().apply { addAll(initialLayout) }
    }

    // 位移用普通状态同步累加，拖动过程中不再逐帧起协程，避免偶发卡住。
    var sheetOffset by remember { mutableFloatStateOf(screenHeightPx) }
    var sheetEntered by remember { mutableStateOf(false) }
    var sheetDragging by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    var showDiscardConfirm by remember { mutableStateOf(false) }
    val animatedOffset by animateFloatAsState(
        targetValue = if (closing) screenHeightPx else sheetOffset,
        animationSpec = when {
            sheetDragging -> snap()
            !sheetEntered -> tween(durationMillis = 280)
            closing -> tween(durationMillis = 220)
            else -> spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)
        },
        label = "categorySheetOffset"
    )

    val hasChanges = categoryItems.toList() != initialLayout
    val requestDismiss: () -> Unit = {
        if (!closing) {
            if (hasChanges) showDiscardConfirm = true else closing = true
        }
    }
    LaunchedEffect(Unit) {
        sheetEntered = true
        sheetOffset = 0f
    }
    LaunchedEffect(closing) {
        if (closing) {
            delay(240L)
            onDismiss()
        }
    }
    BackHandler(onBack = requestDismiss)
    val dragState = rememberDraggableState { delta ->
        sheetOffset = (sheetOffset + delta).coerceIn(0f, screenHeightPx)
    }

    fun applyAndClose() {
        if (hasChanges) {
            val deleted = initialLayout.filterIsInstance<CategoryEntry.Custom>()
                .map { it.name }
                .filter { name ->
                    categoryItems.none { it is CategoryEntry.Custom && it.name == name }
                }
            onApply(categoryItems.toList(), deleted)
        }
        closing = true
    }

    var editingKey by remember { mutableStateOf<String?>(null) }
    var numberInput by remember { mutableStateOf("") }
    var inputError by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<CategoryEntry.Custom?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    fun commitMove(key: String, number: Int) {
        val from = categoryItems.indexOfFirst { it.key == key }
        if (from < 0) return
        // 0 与负数按第 1 位处理，超出条目数则放到最后。
        val destination = when {
            number <= 1 -> 0
            number > categoryItems.size -> categoryItems.lastIndex
            else -> number - 1
        }
        if (destination != from) {
            val entry = categoryItems.removeAt(from)
            categoryItems.add(destination, entry)
        }
        editingKey = null
        keyboard?.hide()
    }

    LaunchedEffect(editingKey) {
        if (editingKey != null) {
            inputError = false
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f))
                .pointerInput(Unit) {
                    detectTapGestures { requestDismiss() }
                }
        )
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .align(Alignment.BottomCenter)
                .offset { IntOffset(0, animatedOffset.roundToInt()) },
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                // 只有这条 44dp 横条能拖动，规则与任务编辑面板一致。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .draggable(
                            state = dragState,
                            orientation = Orientation.Vertical,
                            onDragStarted = { sheetDragging = true },
                            onDragStopped = {
                                sheetDragging = false
                                if (sheetOffset > closeDragThresholdPx) {
                                    requestDismiss()
                                    if (!closing) sheetOffset = 0f
                                } else {
                                    sheetOffset = 0f
                                }
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(38.dp)
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
                    )
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 28.dp)
                ) {
            Text(
                text = "分类编辑",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "左侧是显示顺序，前 7 个会直接出现在新增待办里。点排序按钮输入 1–${categoryItems.size} 后回车移动；0 当第 1 位，超出范围放到最后。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            LazyColumn(
                modifier = Modifier.heightIn(max = 400.dp)
            ) {
                items(categoryItems, key = { it.key }) { entry ->
                    val index = categoryItems.indexOf(entry)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${index + 1}",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = if (index < 7) FontWeight.Bold else FontWeight.Normal
                            ),
                            color = if (index < 7) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            textAlign = TextAlign.Center,
                            modifier = Modifier.width(24.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            painter = painterResource(entry.iconRes()),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = entry.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (editingKey == entry.key) {
                            BasicTextField(
                                value = numberInput,
                                onValueChange = { value ->
                                    numberInput = value.filter { it.isDigit() }.take(3)
                                    inputError = false
                                },
                                modifier = Modifier
                                    .width(64.dp)
                                    .height(40.dp)
                                    .focusRequester(focusRequester),
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurface,
                                    textAlign = TextAlign.Center
                                ),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Number,
                                    imeAction = ImeAction.Done
                                ),
                                keyboardActions = KeyboardActions(
                                    onDone = {
                                        val number = numberInput.trim().toIntOrNull()
                                        if (number == null) {
                                            inputError = true
                                        } else {
                                            commitMove(entry.key, number)
                                        }
                                    }
                                ),
                                decorationBox = { innerTextField ->
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        border = if (inputError) {
                                            BorderStroke(1.5.dp, MaterialTheme.colorScheme.error)
                                        } else {
                                            null
                                        }
                                    ) {
                                        Box(
                                            modifier = Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            innerTextField()
                                        }
                                    }
                                }
                            )
                        } else {
                            SortIconButton(
                                onClick = {
                                    editingKey = entry.key
                                    numberInput = ""
                                }
                            )
                            if (entry is CategoryEntry.Custom) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .bounceClick(shape = CircleShape) {
                                            pendingDelete = entry
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_ui_delete),
                                        contentDescription = "删除${entry.name}",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (inputError) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "输入一个数字后再回车",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            pendingDelete?.let { target ->
                Spacer(Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "删除「${target.name}」？已用过的待办会保留原名称",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { pendingDelete = null }) {
                            Text("取消")
                        }
                        TextButton(
                            onClick = {
                                categoryItems.remove(target)
                                pendingDelete = null
                            }
                        ) {
                            Text("删除", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = {
                        val customNames = categoryItems
                            .filterIsInstance<CategoryEntry.Custom>()
                            .map { it.name }
                        categoryItems.clear()
                        categoryItems.addAll(
                            CategoryCatalog.layout(CategoryCatalog.defaultOrder, customNames)
                        )
                        editingKey = null
                        pendingDelete = null
                    }
                ) {
                    Text("恢复默认顺序", style = MaterialTheme.typography.labelMedium)
                }
                CapsuleButton(
                    text = "完成",
                    onClick = { applyAndClose() },
                    modifier = Modifier.width(120.dp),
                    height = 44.dp
                )
            }
        }
    }
    }
    }

    if (showDiscardConfirm) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = { Text("放弃更改？") },
            text = { Text("这次的排序和删除还没有保存。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardConfirm = false
                        closing = true
                    }
                ) {
                    Text("放弃并关闭")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirm = false }) { Text("继续编辑") }
            }
        )
    }
}

private val CategoryEntry.key: String
    get() = when (this) {
        is CategoryEntry.BuiltIn -> "builtin:${category.name}"
        is CategoryEntry.Custom -> "custom:$name"
    }

private val CategoryEntry.label: String
    get() = when (this) {
        is CategoryEntry.BuiltIn -> category.label
        is CategoryEntry.Custom -> name
    }

private fun CategoryEntry.iconRes(): Int = when (this) {
    is CategoryEntry.BuiltIn -> category.iconRes()
    is CategoryEntry.Custom -> R.drawable.ic_cat_custom
}

@Composable
private fun SortIconButton(onClick: () -> Unit) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .size(44.dp)
            .bounceClick(shape = CircleShape, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(16.dp)) {
            val stroke = 1.6.dp.toPx()
            val cx = size.width / 2f
            val top = size.height * 0.12f
            val bottom = size.height * 0.88f
            val wing = size.width * 0.2f
            val arrow = size.height * 0.2f
            drawLine(tint, Offset(cx, top), Offset(cx, bottom), stroke, StrokeCap.Round)
            drawLine(tint, Offset(cx, top), Offset(cx - wing, top + arrow), stroke, StrokeCap.Round)
            drawLine(tint, Offset(cx, top), Offset(cx + wing, top + arrow), stroke, StrokeCap.Round)
            drawLine(tint, Offset(cx, bottom), Offset(cx - wing, bottom - arrow), stroke, StrokeCap.Round)
            drawLine(tint, Offset(cx, bottom), Offset(cx + wing, bottom - arrow), stroke, StrokeCap.Round)
        }
    }
}

@Composable
private fun SettingsGroup(
    title: String,
    contentPadding: PaddingValues = PaddingValues(18.dp),
    content: @Composable () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )
        SoftCard(contentPadding = contentPadding) { content() }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(10.dp))
        ElasticSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

@Composable
private fun BackupRow(
    title: String,
    detail: String,
    action: String,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    val accent = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .bounceClick(onClick = onClick)
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(10.dp))
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = accent.copy(alpha = 0.12f)
        ) {
            Text(
                text = action,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                color = accent,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun BackupDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    )
}

@Composable
private fun PrivacyLine(@DrawableRes iconRes: Int, title: String, detail: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(6.dp)
                    .size(16.dp)
            )
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
