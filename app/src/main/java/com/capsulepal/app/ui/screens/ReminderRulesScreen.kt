package com.capsulepal.app.ui.screens

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.Indication
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.capsulepal.app.R
import com.capsulepal.app.data.model.AppSettings
import com.capsulepal.app.data.model.ReminderActionType
import com.capsulepal.app.data.model.ReminderRule
import com.capsulepal.app.data.model.ReminderTriggerType
import com.capsulepal.app.data.model.ReminderRules
import com.capsulepal.app.data.model.Task
import com.capsulepal.app.data.model.iconRes
import com.capsulepal.app.data.model.stripMessageTokens
import com.capsulepal.app.signals.BluetoothDeviceReader
import com.capsulepal.app.signals.WifiSsidReader
import com.capsulepal.app.signals.connectedDevices
import com.capsulepal.app.ui.CapsuleViewModel
import com.capsulepal.app.ui.PermissionState
import com.capsulepal.app.ui.components.ElasticSwitch
import com.capsulepal.app.ui.components.Pill
import com.capsulepal.app.ui.components.SoftCard
import com.capsulepal.app.ui.components.bounceClick
import com.capsulepal.app.ui.readPermissionState
import com.capsulepal.app.ui.theme.Butter
import com.capsulepal.app.ui.theme.ButterSoft
import com.capsulepal.app.ui.theme.Coral
import com.capsulepal.app.ui.theme.CoralSoft
import com.capsulepal.app.ui.theme.Ink
import com.capsulepal.app.ui.theme.Lavender
import com.capsulepal.app.ui.theme.Mint
import com.capsulepal.app.ui.theme.MintSoft
import com.capsulepal.app.ui.usageAccessSettingsIntent
import androidx.compose.ui.node.DelegatableNode
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderRulesScreen(
    settings: AppSettings,
    tasks: List<Task>,
    vm: CapsuleViewModel,
    onBack: () -> Unit
) {
    // 没有待办模块的版本（极客体验）只保留纯自定义提醒，
    // 其余版本与完整本体一致：自定义 / 关联指定待办 / 自动关联最近待办。
    val allowTaskLink = settings.version.showTodoPage
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val linkedTasks by vm.openTasks.collectAsStateWithLifecycle()
    var permissions by remember { mutableStateOf(readPermissionState(context)) }
    var editing by remember { mutableStateOf<ReminderRule?>(null) }

    LaunchedEffect(Unit) { vm.refreshOpenTasks() }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissions = readPermissionState(context)
                vm.refreshReminderPermissions()
                vm.refreshOpenTasks()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions = readPermissionState(context) }
    val bluetoothLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { permissions = readPermissionState(context) }
    val calendarLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { permissions = readPermissionState(context) }
    val phoneLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { permissions = readPermissionState(context) }

    fun requestPermission(type: ReminderTriggerType) {
        when (type) {
            ReminderTriggerType.WIFI -> locationLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
            ReminderTriggerType.BLUETOOTH ->
                bluetoothLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            ReminderTriggerType.CALENDAR_SOON ->
                calendarLauncher.launch(Manifest.permission.READ_CALENDAR)
            ReminderTriggerType.CALL_ENDED ->
                phoneLauncher.launch(Manifest.permission.READ_PHONE_STATE)
            ReminderTriggerType.APP_LAUNCH ->
                runCatching { context.startActivity(usageAccessSettingsIntent()) }
            else -> Unit
        }
    }

    // 主动提醒页同样关闭点击水波纹，避免出现灰色方块。
    CompositionLocalProvider(
        LocalIndication provides ReminderNoIndication,
        LocalRippleConfiguration provides null
    ) {
        val rule = editing
        if (rule != null) {
            // 极客体验里，待办类规则只读：能看详情，但不可编辑、不参与触发。
            val readOnly = !allowTaskLink && rule.action != ReminderActionType.CUSTOM
            if (readOnly) {
                ReminderRuleDetail(rule = rule, onBack = { editing = null })
            } else {
                RuleEditor(
                    initial = rule,
                    isNew = settings.reminderRules.none { it.id == rule.id },
                    tasks = linkedTasks.ifEmpty { tasks },
                    allowTaskLink = allowTaskLink,
                    permissions = permissions,
                    onRequestPermission = ::requestPermission,
                    onDismiss = { editing = null },
                    onSave = { updated ->
                        vm.saveReminderRule(updated)
                        vm.refreshOpenTasks()
                        editing = null
                    },
                    onDelete = {
                        vm.deleteReminderRule(rule.id)
                        editing = null
                    }
                )
            }
        } else {
            ReminderRuleList(
                settings = settings,
                permissions = permissions,
                linkedTasks = linkedTasks,
                allowTaskLink = allowTaskLink,
                vm = vm,
                onBack = onBack,
                onEdit = { editing = it },
                onNew = {
                    val nextId = (settings.reminderRules.maxOfOrNull { it.id } ?: 0L) + 1L
                    editing = ReminderRules.emptyRule(nextId)
                },
                onRequestPermission = ::requestPermission
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReminderRuleList(
    settings: AppSettings,
    permissions: PermissionState,
    linkedTasks: List<Task>,
    allowTaskLink: Boolean,
    vm: CapsuleViewModel,
    onBack: () -> Unit,
    onEdit: (ReminderRule) -> Unit,
    onNew: () -> Unit,
    onRequestPermission: (ReminderTriggerType) -> Unit
) {
    val context = LocalContext.current
    // 长按进入多选，选中后统一删除，避免逐条进编辑页。
    var selectionMode by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<Long>() }
    var confirmDelete by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 顶栏做成吸顶头，滚到列表底部时「取消 / 删除」仍在原位，不用滚回顶部。
            stickyHeader {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(top = 10.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TopBarPill(text = "返回", onClick = onBack)
                    Spacer(Modifier.weight(1f))
                    if (selectionMode) {
                        TextButton(
                            onClick = {
                                selectionMode = false
                                selectedIds.clear()
                            }
                        ) {
                            Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(
                            enabled = selectedIds.isNotEmpty(),
                            onClick = { confirmDelete = true }
                        ) {
                            Text(
                                text = "删除 ${selectedIds.size}",
                                color = if (selectedIds.isEmpty()) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    Coral
                                }
                            )
                        }
                    } else {
                        Pill(
                            text = "${settings.reminderRules.count { it.enabled && (allowTaskLink || it.action == ReminderActionType.CUSTOM) }} 条生效",
                            color = MintSoft,
                            contentColor = Mint
                        )
                    }
                }
            }
            item {
                Column {
                    Text(
                        text = "主动提醒",
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "按情境自动提醒，全部在本机计算，不联网也不上传",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item { SectionLabel("防打扰") }
            item {
                GlobalReminderCard(
                    settings = settings,
                    vm = vm
                )
            }

            item { SectionLabel("按需授权") }
            item {
                PermissionCard(
                    permissions = permissions,
                    onRequestLocation = { onRequestPermission(ReminderTriggerType.WIFI) },
                    onRequestBluetooth = { onRequestPermission(ReminderTriggerType.BLUETOOTH) },
                    onRequestCalendar = { onRequestPermission(ReminderTriggerType.CALENDAR_SOON) },
                    onRequestPhone = { onRequestPermission(ReminderTriggerType.CALL_ENDED) },
                    onRequestUsage = { onRequestPermission(ReminderTriggerType.APP_LAUNCH) }
                )
            }

            item { SectionLabel("我的规则") }
            if (!allowTaskLink) {
                item {
                    Text(
                        text = "当前版本只能触发自定义提醒；待办类规则以灰色只读展示，" +
                            "切回其它版本后自动恢复触发。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            }
            items(settings.reminderRules, key = { it.id }) { rule ->
                val muted = !allowTaskLink && rule.action != ReminderActionType.CUSTOM
                RuleCard(
                    rule = rule,
                    muted = muted,
                    missingPermission = missingPermission(rule, permissions),
                    linkedTaskMissing = rule.action == ReminderActionType.LINKED_TASK &&
                        linkedTasks.none { it.id == rule.linkedTaskId },
                    selectionMode = selectionMode,
                    selected = selectedIds.contains(rule.id),
                    onLongClick = {
                        selectionMode = true
                        if (!selectedIds.contains(rule.id)) selectedIds.add(rule.id)
                    },
                    onClick = {
                        if (selectionMode) {
                            if (!selectedIds.remove(rule.id)) selectedIds.add(rule.id)
                        } else {
                            onEdit(rule)
                        }
                    },
                    onToggle = if (muted) {
                        null
                    } else {
                        { enabled -> vm.setReminderRuleEnabled(rule.id, enabled) }
                    }
                )
            }

            item {
                SoftCard(
                    modifier = Modifier.bounceClick(
                        shape = RoundedCornerShape(26.dp),
                        onClick = onNew
                    ),
                    contentPadding = PaddingValues(18.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(CoralSoft, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "＋",
                                style = MaterialTheme.typography.headlineSmall,
                                color = Coral
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "新建主动提醒",
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "选择触发时机，再决定提醒什么",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Pill(text = "新建", color = CoralSoft, contentColor = Coral)
                    }
                }
            }

            item { SectionLabel("诊断") }
            item { ReminderDiagnosticsCard(vm) }

            item {
                Text(
                    text = "3 秒内同时满足多条规则时，只展示优先级最高的一条；" +
                        "免打扰时段内全部静默。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除主动提醒") },
            text = { Text("将删除选中的 ${selectedIds.size} 条规则，删除后无法恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        selectedIds.forEach { vm.deleteReminderRule(it) }
                        selectedIds.clear()
                        selectionMode = false
                        confirmDelete = false
                    }
                ) {
                    Text("删除", color = Coral)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun ReminderDiagnosticsCard(vm: CapsuleViewModel) {
    val entries by vm.reminderTrace.collectAsStateWithLifecycle()
    val diagnostics = vm.reminderDiagnostics()
    var showTrace by remember { mutableStateOf(false) }
    SoftCard(contentPadding = PaddingValues(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "运行状态",
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.SemiBold
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
            DiagnosticsStatusRow("通知权限", diagnostics.notificationsAllowed)
            DiagnosticsStatusRow("后台监听", diagnostics.serviceRunning)
            DiagnosticsStatusRow("使用情况访问", diagnostics.usageAccess)
            DiagnosticsStatusRow("日历权限", diagnostics.calendarPermission)
            // 触发记录收进独立入口，避免诊断卡被日志撑长。
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .bounceClick(shape = RoundedCornerShape(14.dp)) { showTrace = true },
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "最近触发记录",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.weight(1f))
                    Pill(
                        text = "${entries.size} 条",
                        color = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "›",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (showTrace) {
        ReminderTraceDialog(
            entries = entries,
            onDismiss = { showTrace = false },
            onClear = vm::clearReminderTrace
        )
    }
}

@Composable
private fun ReminderTraceDialog(
    entries: List<com.capsulepal.app.domain.ReminderTraceEntry>,
    onDismiss: () -> Unit,
    onClear: () -> Unit
) {
    val timeFormat = remember { DateTimeFormatter.ofPattern("HH:mm:ss") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("最近触发记录") },
        text = {
            if (entries.isEmpty()) {
                Text(
                    text = "还没有记录。触发一次充电或切换应用后回到这里；" +
                        "如果仍为空，说明信号没有到达 App，请检查后台监听与通知权限。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    entries.forEach { entry ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = timeFormat.format(
                                    Instant.ofEpochMilli(entry.atMillis)
                                        .atZone(ZoneId.systemDefault())
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = entry.title,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = entry.detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (entry.success) Mint else Coral
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        dismissButton = if (entries.isNotEmpty()) {
            { TextButton(onClick = onClear) { Text("清空") } }
        } else {
            null
        }
    )
}

@Composable
private fun DiagnosticsStatusRow(label: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(if (ok) Mint else Coral, CircleShape)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = if (ok) "正常" else "未开启",
            style = MaterialTheme.typography.labelSmall,
            color = if (ok) Mint else Coral
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp)
    )
}

@Composable
private fun TopBarPill(
    text: String,
    primary: Boolean = false,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.bounceClick(shape = CircleShape, onClick = onClick),
        shape = CircleShape,
        color = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        contentColor = if (primary) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurface
        }
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun OptionChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.bounceClick(shape = CircleShape, onClick = onClick),
        shape = CircleShape,
        color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
            maxLines = 1
        )
    }
}

@Composable
private fun GlobalReminderCard(
    settings: AppSettings,
    vm: CapsuleViewModel
) {
    var quietTarget by remember { mutableStateOf<Boolean?>(null) }
    SoftCard {
        Text(
            text = "全局冷却",
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "冷却期内只放行高优先级规则，避免连续弹岛。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        PresetOptionRow(
            presets = listOf(5, 15, 30),
            value = settings.reminderCooldownMinutes,
            customInitial = 60,
            customTitle = "自定义全局冷却",
            suffix = "分钟",
            range = 0..120,
            label = { if (it == 0) "关闭" else "$it 分钟" },
            specialLabel = "关闭冷却",
            specialValue = 0,
            onChange = vm::setReminderCooldown
        )
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "免打扰时段",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Medium
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (settings.quietHoursEnabled) {
                        "时段内只记录不弹出、不响铃，高优先级也不例外。"
                    } else {
                        "默认关闭；开启后夜间提醒只记录，不打扰休息。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            ElasticSwitch(
                checked = settings.quietHoursEnabled,
                onCheckedChange = vm::setQuietHoursEnabled
            )
        }
        if (settings.quietHoursEnabled) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill(
                    text = formatMinute(settings.quietStartMinute),
                    color = ButterSoft,
                    contentColor = Ink,
                    modifier = Modifier.bounceClick(
                        shape = CircleShape,
                        onClick = { quietTarget = true }
                    )
                )
                Spacer(Modifier.width(6.dp))
                Text("–", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Pill(
                    text = formatMinute(settings.quietEndMinute),
                    color = ButterSoft,
                    contentColor = Ink,
                    modifier = Modifier.bounceClick(
                        shape = CircleShape,
                        onClick = { quietTarget = false }
                    )
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "播放媒体时静默",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Medium
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "看视频、打游戏、听歌时，只保留日历提醒。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            ElasticSwitch(
                checked = settings.mediaSilenceEnabled,
                onCheckedChange = vm::setMediaSilence
            )
        }
    }

    quietTarget?.let { isStart ->
        ReminderTimePickerDialog(
            initialMinute = if (isStart) settings.quietStartMinute else settings.quietEndMinute,
            title = if (isStart) "免打扰开始时间" else "免打扰结束时间",
            onDismiss = { quietTarget = null },
            onConfirm = { minute ->
                vm.setQuietHours(
                    if (isStart) minute else settings.quietStartMinute,
                    if (isStart) settings.quietEndMinute else minute
                )
                quietTarget = null
            }
        )
    }
}

@Composable
private fun PermissionCard(
    permissions: PermissionState,
    onRequestLocation: () -> Unit,
    onRequestBluetooth: () -> Unit,
    onRequestCalendar: () -> Unit,
    onRequestPhone: () -> Unit,
    onRequestUsage: () -> Unit
) {
    SoftCard {
        Text(
            text = "只有对应的规则会用到这些权限",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        PermissionLine(
            "日历",
            "读取事件开始时间，忽略全天事件",
            permissions.calendar,
            onRequestCalendar
        )
        PermissionLine(
            "定位",
            "仅用于读取当前 WiFi 名称",
            permissions.location,
            onRequestLocation
        )
        PermissionLine(
            "附近的设备",
            "读取蓝牙设备名",
            permissions.bluetooth,
            onRequestBluetooth
        )
        PermissionLine(
            "通话状态",
            "判断一通电话是否真正接通过",
            permissions.phoneState,
            onRequestPhone
        )
        PermissionLine(
            "使用情况访问",
            "检测打开了哪个应用",
            permissions.usageAccess,
            onRequestUsage
        )
    }
}

@Composable
private fun PermissionLine(
    title: String,
    detail: String,
    granted: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .bounceClick(shape = RoundedCornerShape(14.dp), onClick = onClick)
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Pill(
            text = if (granted) "已授权" else "去授权",
            color = if (granted) MintSoft else CoralSoft,
            contentColor = if (granted) Mint else Coral
        )
    }
}

@Composable
private fun RuleCard(
    rule: ReminderRule,
    missingPermission: String?,
    linkedTaskMissing: Boolean,
    muted: Boolean = false,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onLongClick: () -> Unit = {},
    onClick: () -> Unit,
    onToggle: ((Boolean) -> Unit)?
) {
    SoftCard(
        modifier = Modifier
            .alpha(if (muted && !selectionMode) 0.55f else 1f)
            .bounceClick(
                shape = RoundedCornerShape(26.dp),
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TriggerCircle(type = rule.type, size = 42.dp, iconSize = 22.dp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = rule.displayName,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (missingPermission != null) {
                        Spacer(Modifier.width(6.dp))
                        Pill(text = "待授权", color = CoralSoft, contentColor = Coral)
                    } else if (rule.highPriority) {
                        Spacer(Modifier.width(6.dp))
                        Pill(text = "高优先级", color = ButterSoft, contentColor = Ink)
                    }
                }
                Text(
                    text = rule.conditionSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(6.dp))
            when {
                selectionMode -> SelectionMark(selected)
                onToggle != null -> ElasticSwitch(
                    checked = rule.enabled,
                    onCheckedChange = onToggle
                )
                else -> Pill(
                    text = "不触发",
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(
                text = rule.type.label,
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = rule.actionSummary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (missingPermission != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "缺少「$missingPermission」权限，授权后自动生效。",
                style = MaterialTheme.typography.labelSmall,
                color = Coral
            )
        } else if (linkedTaskMissing) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "关联的待办已完成或删除，提醒会退化为只显示文案。",
                style = MaterialTheme.typography.labelSmall,
                color = Coral
            )
        }
    }
}

@Composable
private fun SelectionMark(selected: Boolean) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .background(
                color = if (selected) Coral else MaterialTheme.colorScheme.surfaceVariant,
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Text(text = "✓", style = MaterialTheme.typography.labelMedium, color = Color.White)
        }
    }
}

@Composable
private fun ReminderRuleDetail(rule: ReminderRule, onBack: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TopBarPill(text = "返回", onClick = onBack)
                Spacer(Modifier.weight(1f))
                Pill(
                    text = "只读",
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column {
                Text(
                    text = rule.displayName,
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "${rule.type.label} · ${rule.conditionSummary}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            SoftCard {
                Text(
                    text = "当前版本不触发",
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "这是一条待办类提醒。极客体验没有待办模块，所以这里只做只读展示；" +
                        "规则本身没有被修改，切回其它版本后会自动恢复触发。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            SoftCard {
                RuleDetailLine("提醒动作", rule.actionSummary)
                Spacer(Modifier.height(10.dp))
                RuleDetailLine("生效时间", rule.conditionSummary)
                Spacer(Modifier.height(10.dp))
                RuleDetailLine(
                    "优先级",
                    if (rule.highPriority) "高优先级" else "普通"
                )
                val message = rule.customMessage.stripMessageTokens()
                if (message.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    RuleDetailLine("提醒文案", message)
                }
            }
            SoftCard(
                modifier = Modifier.bounceClick(
                    shape = RoundedCornerShape(26.dp),
                    onClick = onBack
                ),
                contentPadding = PaddingValues(16.dp)
            ) {
                Text(
                    text = "知道了",
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun RuleDetailLine(label: String, value: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(64.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun RuleEditor(
    initial: ReminderRule,
    isNew: Boolean,
    tasks: List<Task>,
    allowTaskLink: Boolean,
    permissions: PermissionState,
    onRequestPermission: (ReminderTriggerType) -> Unit,
    onDismiss: () -> Unit,
    onSave: (ReminderRule) -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    var draft by remember {
        mutableStateOf(
            initial.migratedForEditor().let {
                // 极客体验没有待办：历史规则里的关联动作在这里降级为纯自定义。
                if (allowTaskLink) it else it.copy(action = ReminderActionType.CUSTOM)
            }
        )
    }
    var pendingPermission by remember { mutableStateOf<String?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showAppPicker by remember { mutableStateOf(false) }
    var showTaskPicker by remember { mutableStateOf(false) }
    var showTriggerPicker by remember { mutableStateOf(false) }

    BackHandler { onDismiss() }

    fun normalized(): ReminderRule = draft.copy(
        name = draft.name.trim(),
        message = draft.message.trim(),
        messageTask = draft.messageTask.trim(),
        messageMinutes = draft.messageMinutes.trim(),
        messageTime = draft.messageTime.trim(),
        wifiSsid = draft.wifiSsid.trim(),
        bluetoothName = draft.bluetoothName.trim(),
        bluetoothAddress = draft.bluetoothAddress.trim()
    )

    fun save() {
        val ready = missingPermission(draft, permissions)
        if (ready != null) {
            pendingPermission = ready
            return
        }
        onSave(normalized())
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TopBarPill(text = "取消", onClick = onDismiss)
                Text(
                    text = if (isNew) "新建主动提醒" else "编辑主动提醒",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
                TopBarPill(text = "保存", primary = true, onClick = { save() })
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
            ) {
                SectionLabel("如果")
                ConditionCard(
                    draft = draft,
                    permissions = permissions,
                    context = context,
                    onDraftChange = { draft = it },
                    onOpenPicker = { showTriggerPicker = true },
                    onPickApp = {
                        if (!permissions.usageAccess) {
                            onRequestPermission(ReminderTriggerType.APP_LAUNCH)
                        } else {
                            showAppPicker = true
                        }
                    },
                    onRequestPermission = onRequestPermission
                )

                Spacer(Modifier.height(16.dp))
                SectionLabel("就")
                ActionCard(
                    draft = draft,
                    tasks = tasks,
                    allowTaskLink = allowTaskLink,
                    onDraftChange = { draft = it },
                    onPickTask = { showTaskPicker = true }
                )

                Spacer(Modifier.height(16.dp))
                SectionLabel("更多")
                MoreSettingsCard(
                    draft = draft,
                    onDraftChange = { draft = it }
                )

                Spacer(Modifier.height(18.dp))
                SoftCard(
                    modifier = Modifier.bounceClick(
                        shape = RoundedCornerShape(26.dp),
                        onClick = ::save
                    ),
                    color = MaterialTheme.colorScheme.primary,
                    contentPadding = PaddingValues(vertical = 16.dp)
                ) {
                    Text(
                        text = "保存规则",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = MaterialTheme.colorScheme.onPrimary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (!isNew) {
                    TextButton(
                        onClick = { showDeleteConfirm = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("删除这条规则", color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.height(28.dp))
            }
        }
    }

    if (showTriggerPicker) {
        TriggerPickerDialog(
            selected = draft.type,
            onDismiss = { showTriggerPicker = false },
            onPick = { type ->
                draft = draft.copy(
                    type = type,
                    dailyLimit = if (isNew) {
                        type.defaultDailyLimit
                    } else {
                        draft.dailyLimit
                    }
                )
                showTriggerPicker = false
            }
        )
    }

    if (pendingPermission != null) {
        AlertDialog(
            onDismissRequest = { pendingPermission = null },
            title = { Text("需要先授权") },
            text = {
                Text(
                    "这条规则需要「${pendingPermission}」权限。规则会保持开启，授权完成后自动生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onSave(normalized())
                    pendingPermission = null
                    onRequestPermission(draft.type)
                }) { Text("保存并去授权") }
            },
            dismissButton = {
                TextButton(onClick = {
                    onSave(normalized())
                    pendingPermission = null
                }) { Text("仅保存") }
            }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除规则？") },
            text = { Text("删除后这条主动提醒不会再触发。") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDelete()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            }
        )
    }

    if (showAppPicker) {
        AppPickerDialog(
            initialPicked = draft.appPackages.toSet(),
            onDismiss = { showAppPicker = false },
            onPick = { apps ->
                draft = draft.copy(
                    appPackages = apps.map { it.first },
                    appLabels = apps.map { it.second }
                )
                showAppPicker = false
            }
        )
    }

    if (showTaskPicker) {
        TaskPickerDialog(
            tasks = tasks,
            onDismiss = { showTaskPicker = false },
            onPick = { task ->
                draft = draft.copy(linkedTaskId = task.id)
                showTaskPicker = false
            }
        )
    }
}

@Composable
private fun ConditionCard(
    draft: ReminderRule,
    permissions: PermissionState,
    context: Context,
    onDraftChange: (ReminderRule) -> Unit,
    onOpenPicker: () -> Unit,
    onPickApp: () -> Unit,
    onRequestPermission: (ReminderTriggerType) -> Unit
) {
    var timeTarget by remember { mutableStateOf<ReminderTimeTarget?>(null) }
    var showBluetoothPicker by remember { mutableStateOf(false) }
    SoftCard(contentPadding = PaddingValues(16.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .bounceClick(
                    shape = RoundedCornerShape(18.dp),
                    onClick = onOpenPicker
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TriggerCircle(type = draft.type, size = 46.dp, iconSize = 24.dp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = draft.type.label,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = draft.type.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            Pill(text = "更换", color = CoralSoft, contentColor = Coral)
        }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(12.dp))

        when (draft.type) {
            ReminderTriggerType.WIFI -> {
                Text(
                    text = "留空表示连接任意 WiFi。填写的名称需与系统 WiFi 名称完全一致。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = draft.wifiSsid,
                    onValueChange = { onDraftChange(draft.copy(wifiSsid = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("例如：公司WiFi") },
                    shape = RoundedCornerShape(16.dp)
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedPillButton("读取当前 WiFi") {
                        if (!permissions.location) {
                            onRequestPermission(ReminderTriggerType.WIFI)
                        } else if (!WifiSsidReader.locationEnabled(context)) {
                            Toast.makeText(
                                context,
                                "请先打开系统定位服务，再读取 WiFi 名称",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            val name = WifiSsidReader.currentSsid(context)
                            if (name.isBlank()) {
                                Toast.makeText(
                                    context,
                                    "当前未连接 WiFi 或系统未返回名称，可手动填写",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                onDraftChange(draft.copy(wifiSsid = name))
                            }
                        }
                    }
                    if (draft.wifiSsid.isNotBlank()) {
                        OutlinedPillButton("任意 WiFi") {
                            onDraftChange(draft.copy(wifiSsid = ""))
                        }
                    }
                }
            }
            ReminderTriggerType.BLUETOOTH -> {
                Text(
                    text = "留空表示连接任意蓝牙设备。建议从已配对设备中选择，匹配更准确。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = draft.bluetoothName,
                    onValueChange = {
                        onDraftChange(
                            draft.copy(bluetoothName = it, bluetoothAddress = "")
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("例如：Car Audio") },
                    shape = RoundedCornerShape(16.dp)
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedPillButton("选择蓝牙设备") {
                        if (!permissions.bluetooth) {
                            onRequestPermission(ReminderTriggerType.BLUETOOTH)
                        } else {
                            showBluetoothPicker = true
                        }
                    }
                    if (draft.bluetoothName.isNotBlank() ||
                        draft.bluetoothAddress.isNotBlank()
                    ) {
                        OutlinedPillButton("任意设备") {
                            onDraftChange(
                                draft.copy(bluetoothName = "", bluetoothAddress = "")
                            )
                        }
                    }
                }
            }
            ReminderTriggerType.APP_LAUNCH -> {
                Text(
                    text = if (draft.appLabels.isEmpty()) {
                        "当前为任意应用。建议至少选择一个应用，避免打扰。"
                    } else {
                        "已选择 ${draft.appLabels.size} 个应用，可多选"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (draft.appLabels.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        draft.appLabels.take(4).forEachIndexed { index, label ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val packageName = draft.appPackages.getOrNull(index)
                                val icon = remember(packageName) {
                                    packageName?.let { loadAppIcon(context, it) }
                                }
                                if (icon != null) {
                                    Image(
                                        bitmap = icon,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .background(Coral, CircleShape)
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                        if (draft.appLabels.size > 4) {
                            Text(
                                text = "还有 ${draft.appLabels.size - 4} 个…",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedPillButton(
                        if (draft.appPackages.isEmpty()) "选择应用" else "修改应用"
                    ) { onPickApp() }
                    if (draft.appPackages.isNotEmpty()) {
                        OutlinedPillButton("任意应用") {
                            onDraftChange(draft.copy(appPackages = emptyList(), appLabels = emptyList()))
                        }
                    }
                }
            }
            ReminderTriggerType.CALENDAR_SOON -> {
                Text(
                    text = "提前多少分钟提醒（全天事件自动忽略）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                PresetOptionRow(
                    presets = listOf(5, 10, 15),
                    value = draft.calendarLeadMinutes,
                    customInitial = 30,
                    customTitle = "自定义提前时间",
                    suffix = "分钟",
                    range = 1..180,
                    label = { "$it 分钟" },
                    onChange = { onDraftChange(draft.copy(calendarLeadMinutes = it)) }
                )
            }
            else -> Text(
                text = "系统发出事件时触发，无需额外条件。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "限定时间段",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (draft.timeWindowEnabled) {
                        "${formatMinute(draft.startMinuteOfDay)} – ${
                            formatMinute(draft.endMinuteOfDay)
                        }"
                    } else {
                        "全天都可以触发"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            ElasticSwitch(
                checked = draft.timeWindowEnabled,
                onCheckedChange = { onDraftChange(draft.copy(timeWindowEnabled = it)) }
            )
        }
        if (draft.timeWindowEnabled) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill(
                    text = formatMinute(draft.startMinuteOfDay),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.bounceClick(shape = CircleShape) {
                        timeTarget = ReminderTimeTarget.WINDOW_START
                    }
                )
                Spacer(Modifier.width(6.dp))
                Text("–", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Pill(
                    text = formatMinute(draft.endMinuteOfDay),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.bounceClick(shape = CircleShape) {
                        timeTarget = ReminderTimeTarget.WINDOW_END
                    }
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            text = "生效星期",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(4.dp))
        DaySelector(
            daysMask = draft.daysMask,
            onChange = { onDraftChange(draft.copy(daysMask = it)) }
        )
    }

    timeTarget?.let { target ->
        val initial = when (target) {
            ReminderTimeTarget.WINDOW_START -> draft.startMinuteOfDay
            ReminderTimeTarget.WINDOW_END -> draft.endMinuteOfDay
        }
        ReminderTimePickerDialog(
            initialMinute = initial,
            title = when (target) {
                ReminderTimeTarget.WINDOW_START -> "选择时间段开始"
                ReminderTimeTarget.WINDOW_END -> "选择时间段结束"
            },
            onDismiss = { timeTarget = null },
            onConfirm = { minute ->
                onDraftChange(
                    when (target) {
                        ReminderTimeTarget.WINDOW_START ->
                            draft.copy(startMinuteOfDay = minute)
                        ReminderTimeTarget.WINDOW_END ->
                            draft.copy(endMinuteOfDay = minute)
                    }
                )
                timeTarget = null
            }
        )
    }

    if (showBluetoothPicker) {
        BluetoothPickerDialog(
            context = context,
            selectedAddress = draft.bluetoothAddress,
            onDismiss = { showBluetoothPicker = false },
            onPick = { name, address ->
                onDraftChange(draft.copy(bluetoothName = name, bluetoothAddress = address))
                showBluetoothPicker = false
            }
        )
    }
}

private enum class ReminderTimeTarget {
    WINDOW_START,
    WINDOW_END
}

@Composable
private fun DaySelector(daysMask: Int, onChange: (Int) -> Unit) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        ReminderRule.DAY_LABELS.forEachIndexed { index, label ->
            val bit = 1 shl index
            Surface(
                modifier = Modifier
                    .size(34.dp)
                    .bounceClick(shape = CircleShape) { onChange(daysMask xor bit) },
                shape = CircleShape,
                color = if (daysMask and bit != 0) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                contentColor = if (daysMask and bit != 0) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(4.dp))
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        listOf("每天" to ReminderRule.ALL_DAYS,
            "工作日" to ReminderRule.WEEKDAYS,
            "周末" to ReminderRule.WEEKEND).forEach { (label, mask) ->
            OutlinedPillButton(label) { onChange(mask) }
        }
    }
}

@Composable
private fun ActionCard(
    draft: ReminderRule,
    tasks: List<Task>,
    allowTaskLink: Boolean,
    onDraftChange: (ReminderRule) -> Unit,
    onPickTask: () -> Unit
) {
    val actions = if (allowTaskLink) {
        ReminderActionType.entries
    } else {
        listOf(ReminderActionType.CUSTOM)
    }
    SoftCard(contentPadding = PaddingValues(16.dp)) {
        actions.forEach { action ->
            val selected = draft.action == action
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .bounceClick(
                        shape = RoundedCornerShape(16.dp),
                        onClick = { onDraftChange(draft.copy(action = action)) }
                    )
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                            CircleShape
                        )
                        .then(
                            if (selected) {
                                Modifier
                            } else {
                                Modifier.background(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    CircleShape
                                )
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (selected) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .background(MaterialTheme.colorScheme.onPrimary, CircleShape)
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = action.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(12.dp))

        when (draft.action) {
            ReminderActionType.CUSTOM -> MessageComposer(
                draft = draft,
                onDraftChange = onDraftChange
            )
            ReminderActionType.LINKED_TASK -> {
                val linked = tasks.firstOrNull { it.id == draft.linkedTaskId }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 整行可点，但不能用圆角裁剪：行高较矮时 16dp 圆角会切掉首字。
                        .bounceClick(shape = RoundedCornerShape(0.dp)) { onPickTask() },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = linked?.title ?: "选择一条未完成待办",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Medium
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (linked != null) {
                                "${linked.date} ${linked.startTime.toString().take(5)}"
                            } else {
                                "待办完成或删除后，会退化为只显示自定义提醒"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Pill(text = "选择", color = CoralSoft, contentColor = Coral)
                }
                Spacer(Modifier.height(10.dp))
                MessageComposer(draft = draft, onDraftChange = onDraftChange)
            }
            ReminderActionType.AUTO_TASK -> Text(
                text = "自动挑选最近的一条未完成待办，并在提醒里直接提供「开始准备」。没有待办时只显示文案。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MessageComposer(
    draft: ReminderRule,
    onDraftChange: (ReminderRule) -> Unit
) {
    // 只按规则 id 初始化；插入字段时直接更新本地 TextFieldValue，保留光标位置。
    var fieldValue by remember(draft.id) {
        mutableStateOf(
            TextFieldValue(
                text = draft.message,
                selection = TextRange(draft.message.length)
            )
        )
    }
    var slotDialog by remember { mutableStateOf<Int?>(null) }

    fun applyEdit(next: TextFieldValue) {
        val oldText = fieldValue.text
        val newText = next.text
        if (oldText == newText) {
            fieldValue = next
            return
        }
        val tokens = tokenSpans(oldText)
        val prefix = commonPrefixLength(oldText, newText)
        val suffix = commonSuffixLength(oldText, newText, prefix)
        val removeStart = prefix
        val removeEnd = oldText.length - suffix

        // 字段是不可拆分的区块：不允许在区块内部插入或替换。
        if (newText.length > oldText.length &&
            tokens.any { prefix > it.start && prefix < it.end }
        ) {
            return
        }

        var result = newText
        var caretShift = 0
        if (newText.length < oldText.length) {
            tokens.forEach { token ->
                if (removeEnd > token.start && removeStart < token.end) {
                    val before = (removeStart - token.start).coerceIn(0, token.length)
                    val after = (token.end - removeEnd).coerceIn(0, token.length)
                    val from = prefix - before
                    val to = prefix + after
                    if (from >= 0 && from <= result.length && to in from..result.length) {
                        result = result.removeRange(from, to)
                        caretShift += to - from
                    }
                }
            }
        }
        val caret = (next.selection.min - caretShift).coerceIn(0, result.length)
        fieldValue = TextFieldValue(result, TextRange(caret))
        onDraftChange(
            draft.copy(
                message = result,
                messageSlotsMask = recomputeSlotsMask(oldText, result, draft.messageSlotsMask)
            )
        )
    }

    fun insertSlot(value: String, slot: Int) {
        val oldText = fieldValue.text
        val tokens = tokenSpans(oldText)
        val selection = fieldValue.selection
        var start = selection.min
        var end = selection.max
        val touched = tokens.firstOrNull { it.start < end && it.end > start }
        if (touched != null) {
            start = minOf(start, touched.start)
            end = maxOf(end, touched.end)
        } else if (selection.collapsed) {
            // 光标落在区块内部时，插到区块后面，避免把区块拆开。
            tokens.firstOrNull { start > it.start && start < it.end }?.let {
                start = it.end
                end = it.end
            }
        }
        val marked = buildString {
            append(ReminderRule.tokenOpen(slot))
            append(value)
            append(ReminderRule.tokenClose(slot))
        }
        val updated = oldText.replaceRange(start, end, marked)
        fieldValue = TextFieldValue(
            text = updated,
            selection = TextRange(start + marked.length)
        )
        onDraftChange(
            draft.copy(
                message = updated,
                messageSlotsMask =
                    recomputeSlotsMask(oldText, updated, draft.messageSlotsMask) or slot
            )
        )
    }

    val taskColor = Coral
    val minutesColor = Mint
    val timeColor = MaterialTheme.colorScheme.onSurface
    val tokenTransformation = remember(taskColor, minutesColor, timeColor) {
        ReminderTokenTransformation(taskColor, minutesColor, timeColor)
    }

    Text(
        text = "提醒文案",
        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
        color = MaterialTheme.colorScheme.onSurface
    )
    Text(
        text = "先输入文字，把光标放在需要的位置，再点下方按钮插入字段；每个字段只能用一次。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = fieldValue,
        onValueChange = { applyEdit(it) },
        visualTransformation = tokenTransformation,
        modifier = Modifier.fillMaxWidth(),
        singleLine = false,
        minLines = 3,
        placeholder = { Text("例如：到家啦，先准备一下") },
        shape = RoundedCornerShape(16.dp)
    )
    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        listOf(
            Triple(ReminderRule.SLOT_TASK, "待办", CoralSoft to Coral),
            Triple(ReminderRule.SLOT_MINUTES, "计时", MintSoft to Mint),
            Triple(ReminderRule.SLOT_TIME, "时间", MaterialTheme.colorScheme.surfaceVariant to
                MaterialTheme.colorScheme.onSurface)
        ).forEach { (slot, label, colors) ->
            val used = draft.messageSlotsMask and slot != 0
            Pill(
                text = if (used) "$label 已用" else label,
                color = if (used) MaterialTheme.colorScheme.surfaceVariant else colors.first,
                contentColor = if (used) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    colors.second
                },
                modifier = if (used) {
                    Modifier
                } else {
                    Modifier.bounceClick(shape = CircleShape) { slotDialog = slot }
                }
            )
        }
    }
    if (draft.message.isBlank()) {
        Spacer(Modifier.height(6.dp))
        Text(
            text = "留空将使用默认提醒文案。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    when (slotDialog) {
        ReminderRule.SLOT_TASK -> TextEntryDialog(
            title = "插入待办名",
            placeholder = "例如：写周报",
            onDismiss = { slotDialog = null },
            onConfirm = { value ->
                insertSlot(value, ReminderRule.SLOT_TASK)
                slotDialog = null
            }
        )
        ReminderRule.SLOT_MINUTES -> NumberEntryDialog(
            title = "插入剩余分钟",
            initial = 10,
            range = 1..600,
            suffix = "分钟",
            onDismiss = { slotDialog = null },
            onConfirm = { value ->
                insertSlot("$value 分钟", ReminderRule.SLOT_MINUTES)
                slotDialog = null
            }
        )
        ReminderRule.SLOT_TIME -> ReminderTimePickerDialog(
            initialMinute = LocalTime.now().let { it.hour * 60 + it.minute },
            title = "插入开始时间",
            onDismiss = { slotDialog = null },
            onConfirm = { value ->
                insertSlot(formatMinute(value), ReminderRule.SLOT_TIME)
                slotDialog = null
            }
        )
    }
}

/** 文案里一个内联字段在原始文本中的范围（含两侧不可见标记）。 */
private data class MessageToken(val slot: Int, val start: Int, val end: Int) {
    val length: Int get() = end - start
}

private fun tokenSpans(text: String): List<MessageToken> {
    val tokens = mutableListOf<MessageToken>()
    var slot = 0
    var start = -1
    for (i in text.indices) {
        when (text[i]) {
            ReminderRule.TOKEN_TASK_OPEN -> {
                slot = ReminderRule.SLOT_TASK
                start = i
            }
            ReminderRule.TOKEN_MINUTES_OPEN -> {
                slot = ReminderRule.SLOT_MINUTES
                start = i
            }
            ReminderRule.TOKEN_TIME_OPEN -> {
                slot = ReminderRule.SLOT_TIME
                start = i
            }
            ReminderRule.TOKEN_TASK_CLOSE,
            ReminderRule.TOKEN_MINUTES_CLOSE,
            ReminderRule.TOKEN_TIME_CLOSE -> {
                if (start >= 0) {
                    tokens += MessageToken(slot, start, i + 1)
                    start = -1
                }
            }
        }
    }
    return tokens
}

/** 字段被整体删掉后，把对应的“已用”位清掉；旧规则里没有标记的位保持原样。 */
private fun recomputeSlotsMask(oldText: String, newText: String, oldMask: Int): Int {
    val oldSlots = tokenSpans(oldText).map { it.slot }.toSet()
    val newSlots = tokenSpans(newText).map { it.slot }.toSet()
    var mask = oldMask
    oldSlots.forEach { slot ->
        if (slot !in newSlots) mask = mask and slot.inv()
    }
    return mask
}

private fun commonPrefixLength(a: String, b: String): Int {
    val max = minOf(a.length, b.length)
    var i = 0
    while (i < max && a[i] == b[i]) i++
    return i
}

private fun commonSuffixLength(a: String, b: String, prefix: Int): Int {
    val max = minOf(a.length, b.length) - prefix
    var i = 0
    while (i < max && a[a.length - 1 - i] == b[b.length - 1 - i]) i++
    return i
}

/**
 * 把内联字段的标记隐藏掉，并按字段类型着色。
 * 标记只存在于文本中，用户看到的是连续的文字；光标落在区块边界时映射到标记外侧。
 */
private class ReminderTokenTransformation(
    private val taskColor: Color,
    private val minutesColor: Color,
    private val timeColor: Color
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        if (raw.none { ReminderRule.isTokenMark(it) }) {
            return TransformedText(text, OffsetMapping.Identity)
        }
        val builder = AnnotatedString.Builder()
        val originalToTransformed = IntArray(raw.length + 1)
        var transformed = 0
        var spanStart = -1
        var spanColor: Color? = null

        for (i in raw.indices) {
            val char = raw[i]
            originalToTransformed[i] = transformed
            when (char) {
                ReminderRule.TOKEN_TASK_OPEN -> {
                    spanStart = transformed
                    spanColor = taskColor
                }
                ReminderRule.TOKEN_MINUTES_OPEN -> {
                    spanStart = transformed
                    spanColor = minutesColor
                }
                ReminderRule.TOKEN_TIME_OPEN -> {
                    spanStart = transformed
                    spanColor = timeColor
                }
                ReminderRule.TOKEN_TASK_CLOSE,
                ReminderRule.TOKEN_MINUTES_CLOSE,
                ReminderRule.TOKEN_TIME_CLOSE -> {
                    val color = spanColor
                    if (spanStart >= 0 && color != null && transformed > spanStart) {
                        builder.addStyle(
                            SpanStyle(color = color, fontWeight = FontWeight.Medium),
                            spanStart,
                            transformed
                        )
                    }
                    spanStart = -1
                    spanColor = null
                }
                else -> {
                    builder.append(char)
                    transformed++
                }
            }
        }
        originalToTransformed[raw.length] = transformed

        val transformedToOriginal = IntArray(transformed + 1) { -1 }
        var t = 0
        for (i in raw.indices) {
            when (raw[i]) {
                ReminderRule.TOKEN_TASK_OPEN,
                ReminderRule.TOKEN_MINUTES_OPEN,
                ReminderRule.TOKEN_TIME_OPEN -> {
                    if (transformedToOriginal[t] == -1) transformedToOriginal[t] = i
                }
                ReminderRule.TOKEN_TASK_CLOSE,
                ReminderRule.TOKEN_MINUTES_CLOSE,
                ReminderRule.TOKEN_TIME_CLOSE -> {
                    transformedToOriginal[t] = i + 1
                }
                else -> {
                    if (transformedToOriginal[t] == -1) transformedToOriginal[t] = i
                    t++
                }
            }
        }
        transformedToOriginal[transformed] = raw.length
        for (i in 1..transformed) {
            if (transformedToOriginal[i] == -1) {
                transformedToOriginal[i] = transformedToOriginal[i - 1]
            }
        }

        return TransformedText(
            builder.toAnnotatedString(),
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int): Int =
                    originalToTransformed[offset.coerceIn(0, raw.length)]

                override fun transformedToOriginal(offset: Int): Int =
                    transformedToOriginal[offset.coerceIn(0, transformed)]
            }
        )
    }
}

@Composable
private fun TextEntryDialog(
    title: String,
    placeholder: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(placeholder) },
                shape = RoundedCornerShape(16.dp)
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val value = text.trim()
                    if (value.isNotEmpty()) onConfirm(value)
                }
            ) { Text("插入") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun MoreSettingsCard(
    draft: ReminderRule,
    onDraftChange: (ReminderRule) -> Unit
) {
    SoftCard(contentPadding = PaddingValues(16.dp)) {
        Text(
            text = "规则名称（可选）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = draft.name,
            onValueChange = { onDraftChange(draft.copy(name = it)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text(draft.displayName) },
            shape = RoundedCornerShape(16.dp)
        )

        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(12.dp))

        Text(
            text = "每日上限",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(6.dp))
        PresetOptionRow(
            presets = listOf(1, 2, 3),
            value = draft.dailyLimit,
            customInitial = 5,
            customTitle = "自定义每日上限",
            suffix = "次",
            range = 0..20,
            label = { if (it == 0) "不限" else "$it 次" },
            specialLabel = "不限次数",
            specialValue = 0,
            onChange = { onDraftChange(draft.copy(dailyLimit = it)) }
        )
        Text(
            text = if (draft.effectiveDailyLimit == 0) {
                "当前不限次数，仍受全局冷却与免打扰约束。"
            } else {
                "当前按每日 ${draft.effectiveDailyLimit} 次计算，跨天自动重置。"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "高优先级",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "可以穿透全局冷却，但仍受每日上限与免打扰约束。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            ElasticSwitch(
                checked = draft.highPriority,
                onCheckedChange = { onDraftChange(draft.copy(highPriority = it)) }
            )
        }
    }
}

@Composable
private fun TriggerPickerDialog(
    selected: ReminderTriggerType,
    onDismiss: () -> Unit,
    onPick: (ReminderTriggerType) -> Unit
) {
    val groups = listOf(
        "手机连接" to listOf(
            ReminderTriggerType.WIFI,
            ReminderTriggerType.HEADSET,
            ReminderTriggerType.BLUETOOTH
        ),
        "手机状态" to listOf(
            ReminderTriggerType.CHARGING_START,
            ReminderTriggerType.CHARGING_STOP
        ),
        "手机通讯" to listOf(ReminderTriggerType.CALL_ENDED),
        "日程与应用" to listOf(
            ReminderTriggerType.CALENDAR_SOON,
            ReminderTriggerType.APP_LAUNCH
        )
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 40.dp),
            contentAlignment = Alignment.Center
        ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 640.dp)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TopBarPill(text = "取消", onClick = onDismiss)
                    Text(
                        text = "添加触发条件",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(64.dp))
                }
                Spacer(Modifier.height(10.dp))
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 8.dp)
                ) {
                    groups.forEach { (title, types) ->
                        Text(
                            text = title,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                        )
                        types.chunked(3).forEach { rowTypes ->
                            Row(modifier = Modifier.fillMaxWidth()) {
                                rowTypes.forEach { type ->
                                    TriggerOption(
                                        type = type,
                                        selected = type == selected,
                                        onClick = { onPick(type) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                repeat(3 - rowTypes.size) {
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun TriggerOption(
    type: ReminderTriggerType,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .bounceClick(shape = RoundedCornerShape(18.dp), onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        TriggerCircle(type = type, size = 54.dp, iconSize = 26.dp, highlighted = selected)
        Spacer(Modifier.height(6.dp))
        Text(
            text = type.label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            textAlign = TextAlign.Center,
            maxLines = 2,
            lineHeight = 15.sp
        )
    }
}

@Composable
private fun TriggerCircle(
    type: ReminderTriggerType,
    size: androidx.compose.ui.unit.Dp,
    iconSize: androidx.compose.ui.unit.Dp,
    highlighted: Boolean = false
) {
    val (background, content) = triggerColors(type)
    Box(
        modifier = Modifier
            .size(size)
            .background(background, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(type.iconRes),
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(iconSize)
        )
        if (highlighted) {
            Box(
                modifier = Modifier
                    .size(size)
                    .background(Color.Transparent, CircleShape)
            )
        }
    }
}

@Composable
private fun triggerColors(type: ReminderTriggerType): Pair<Color, Color> = when (type) {
    ReminderTriggerType.CALENDAR_SOON -> Coral to Color.White
    ReminderTriggerType.WIFI,
    ReminderTriggerType.CALL_ENDED -> Mint to Color.White
    ReminderTriggerType.HEADSET,
    ReminderTriggerType.BLUETOOTH,
    ReminderTriggerType.CHARGING_STOP,
    ReminderTriggerType.APP_LAUNCH -> Lavender to Color.White
    ReminderTriggerType.CHARGING_START -> Butter to Ink
}

@Composable
private fun BigTimePill(minute: Int, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.bounceClick(shape = RoundedCornerShape(18.dp), onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = CoralSoft,
        contentColor = Coral
    ) {
        Text(
            text = formatMinute(minute),
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun OutlinedPillButton(text: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.bounceClick(shape = CircleShape, onClick = onClick),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
        )
    }
}

private data class AppEntry(val packageName: String, val label: String, val icon: ImageBitmap?)

@Composable
private fun AppPickerDialog(
    initialPicked: Set<String>,
    onDismiss: () -> Unit,
    onPick: (List<Pair<String, String>>) -> Unit
) {
    val context = LocalContext.current
    val apps = remember(context) { loadLaunchableApps(context) }
    var selected by remember { mutableStateOf(initialPicked) }
    var query by remember { mutableStateOf("") }
    val filtered = apps.filter {
        query.isBlank() ||
            it.label.contains(query, ignoreCase = true) ||
            it.packageName.contains(query, ignoreCase = true)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 40.dp),
            contentAlignment = Alignment.Center
        ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 640.dp)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TopBarPill(text = "取消", onClick = onDismiss)
                    Text(
                        text = "选择应用",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f)
                    )
                    TopBarPill(
                        text = if (selected.isEmpty()) "任意" else "完成 ${selected.size}",
                        primary = true,
                        onClick = {
                            onPick(
                                apps.filter { it.packageName in selected }
                                    .map { it.packageName to it.label }
                            )
                        }
                    )
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("搜索应用") },
                    shape = RoundedCornerShape(16.dp)
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                ) {
                    item {
                        AppPickerRow(
                            icon = null,
                            title = "任意应用",
                            subtitle = "不筛选，打开任何应用都评估这条规则",
                            checked = selected.isEmpty(),
                            onClick = {
                                selected = emptySet()
                                onPick(emptyList())
                            }
                        )
                    }
                    items(filtered, key = { it.packageName }) { app ->
                        AppPickerRow(
                            icon = app.icon,
                            title = app.label,
                            subtitle = app.packageName,
                            checked = app.packageName in selected,
                            onClick = {
                                selected = if (app.packageName in selected) {
                                    selected - app.packageName
                                } else {
                                    selected + app.packageName
                                }
                            }
                        )
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun AppPickerRow(
    icon: ImageBitmap?,
    title: String,
    subtitle: String,
    checked: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .bounceClick(shape = RoundedCornerShape(16.dp), onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(34.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_rule_app),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        Checkbox(
            checked = checked,
            onCheckedChange = { onClick() }
        )
    }
}

@Suppress("DEPRECATION")
private fun loadLaunchableApps(context: Context): List<AppEntry> = runCatching {
    val manager = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val resolveInfos = if (Build.VERSION.SDK_INT >= 33) {
        manager.queryIntentActivities(
            intent,
            PackageManager.ResolveInfoFlags.of(0L)
        )
    } else {
        manager.queryIntentActivities(intent, 0)
    }
    resolveInfos
        .distinctBy { it.activityInfo.packageName }
        .map { info ->
            val pkg = info.activityInfo.packageName
            val label = runCatching { info.loadLabel(manager).toString() }.getOrDefault(pkg)
            val icon = runCatching {
                info.loadIcon(manager).toBitmap(96, 96).asImageBitmap()
            }.getOrNull()
            AppEntry(pkg, label, icon)
        }
        .sortedBy { it.label.lowercase() }
}.getOrDefault(emptyList())

@Composable
private fun TaskPickerDialog(
    tasks: List<Task>,
    onDismiss: () -> Unit,
    onPick: (Task) -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    text = "选择关联待办",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(8.dp))
                if (tasks.isEmpty()) {
                    Text(
                        text = "当前没有未完成的待办，先创建一条吧。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                        items(tasks, key = { it.id }) { task ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .bounceClick(shape = RoundedCornerShape(16.dp)) {
                                        onPick(task)
                                    }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = task.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "${task.date} ${
                                            task.startTime.toString().take(5)
                                        }",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End)
                ) { Text("取消") }
            }
        }
    }
}

private fun missingPermission(rule: ReminderRule, permissions: PermissionState): String? = when {
    rule.type == ReminderTriggerType.WIFI && rule.wifiSsid.isNotBlank() &&
        !permissions.location -> "定位"
    rule.type == ReminderTriggerType.BLUETOOTH &&
        (rule.bluetoothName.isNotBlank() || rule.bluetoothAddress.isNotBlank()) &&
        !permissions.bluetooth -> "附近的设备"
    rule.type == ReminderTriggerType.CALENDAR_SOON && !permissions.calendar -> "日历"
    rule.type == ReminderTriggerType.CALL_ENDED && !permissions.phoneState -> "通话状态"
    rule.type == ReminderTriggerType.APP_LAUNCH && !permissions.usageAccess -> "使用情况访问"
    else -> null
}

private fun formatMinute(minuteOfDay: Int): String =
    ReminderRule.formatMinute(minuteOfDay)

/** 旧版本把文案拆成 messageTask / messageMinutes / messageTime，打开编辑器时合并到单输入框。 */
private fun ReminderRule.migratedForEditor(): ReminderRule {
    val hasLegacy = messageTask.isNotBlank() ||
        messageMinutes.isNotBlank() ||
        messageTime.isNotBlank()
    if (!hasLegacy) return this
    var mask = messageSlotsMask
    fun marked(slot: Int, text: String): String = buildString {
        append(ReminderRule.tokenOpen(slot))
        append(text)
        append(ReminderRule.tokenClose(slot))
    }
    val parts = mutableListOf<String>()
    if (message.isNotBlank()) parts += message.trim()
    if (messageTask.isNotBlank()) {
        parts += marked(ReminderRule.SLOT_TASK, messageTask.trim())
        mask = mask or ReminderRule.SLOT_TASK
    }
    if (messageMinutes.isNotBlank()) {
        parts += marked(ReminderRule.SLOT_MINUTES, "${messageMinutes.trim()} 分钟")
        mask = mask or ReminderRule.SLOT_MINUTES
    }
    if (messageTime.isNotBlank()) {
        parts += marked(ReminderRule.SLOT_TIME, messageTime.trim())
        mask = mask or ReminderRule.SLOT_TIME
    }
    return copy(
        message = parts.joinToString(" "),
        messageTask = "",
        messageMinutes = "",
        messageTime = "",
        messageSlotsMask = mask
    )
}

/** 三枚预设 + 「自定义」按钮，超出宽度自动换行，避免最后一个被挤成竖形椭圆。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetOptionRow(
    presets: List<Int>,
    value: Int,
    customInitial: Int,
    customTitle: String,
    suffix: String,
    range: IntRange,
    label: (Int) -> String,
    onChange: (Int) -> Unit,
    specialLabel: String? = null,
    specialValue: Int? = null
) {
    var showCustom by remember { mutableStateOf(false) }
    val isPreset = value in presets
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        presets.forEach { preset ->
            OptionChip(
                text = label(preset),
                selected = value == preset,
                onClick = { onChange(preset) }
            )
        }
        OptionChip(
            text = if (isPreset) "自定义" else label(value),
            selected = !isPreset,
            onClick = { showCustom = true }
        )
    }
    if (showCustom) {
        NumberEntryDialog(
            title = customTitle,
            initial = if (isPreset) customInitial else value,
            range = range,
            suffix = suffix,
            specialLabel = specialLabel,
            specialValue = specialValue,
            onDismiss = { showCustom = false },
            onConfirm = { picked ->
                onChange(picked)
                showCustom = false
            }
        )
    }
}

@Composable
private fun NumberEntryDialog(
    title: String,
    initial: Int,
    range: IntRange,
    suffix: String,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
    specialLabel: String? = null,
    specialValue: Int? = null
) {
    var text by remember {
        mutableStateOf(initial.coerceIn(range.first, range.last).toString())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { input ->
                        text = input.filter(Char::isDigit).take(4)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    suffix = { Text(suffix) },
                    shape = RoundedCornerShape(16.dp)
                )
                if (specialLabel != null && specialValue != null) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedPillButton(specialLabel) { onConfirm(specialValue) }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val parsed = text.toIntOrNull()
                        ?: return@TextButton
                    onConfirm(parsed.coerceIn(range.first, range.last))
                }
            ) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 与「新增或编辑待办」一致的小时/分钟滚轮，点击数字可直接输入。 */
@Composable
private fun ReminderTimePickerDialog(
    initialMinute: Int,
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    val safeInitial = initialMinute.coerceIn(0, 24 * 60 - 1)
    var hour by remember { mutableIntStateOf(safeInitial / 60) }
    var minute by remember { mutableIntStateOf(safeInitial % 60) }
    var editPart by remember { mutableStateOf<String?>(null) }
    var editText by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(8.dp))
                TimeStepper(
                    hour = hour,
                    minute = minute,
                    onChange = { h, m ->
                        hour = h
                        minute = m
                    },
                    onEditHour = {
                        editText = "%02d".format(hour)
                        editPart = "hour"
                    },
                    onEditMinute = {
                        editText = "%02d".format(minute)
                        editPart = "minute"
                    }
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    TextButton(onClick = { onConfirm(hour * 60 + minute) }) {
                        Text("完成")
                    }
                }
            }
        }
    }

    editPart?.let { part ->
        AlertDialog(
            onDismissRequest = { editPart = null },
            title = { Text(if (part == "hour") "修改小时" else "修改分钟") },
            text = {
                OutlinedTextField(
                    value = editText,
                    onValueChange = { input ->
                        editText = input.filter(Char::isDigit).take(2)
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    suffix = { Text(if (part == "hour") "时" else "分") },
                    shape = RoundedCornerShape(16.dp)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val parsed = editText.toIntOrNull()
                        if (parsed != null) {
                            if (part == "hour") {
                                hour = parsed.coerceIn(0, 23)
                            } else {
                                minute = parsed.coerceIn(0, 59)
                            }
                        }
                        editPart = null
                    }
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { editPart = null }) { Text("取消") }
            }
        )
    }
}

private data class BluetoothEntry(
    val name: String,
    val address: String,
    val connected: Boolean
)

private fun readBluetoothDevices(context: Context): List<BluetoothEntry> = runCatching {
    val manager = context.getSystemService(BluetoothManager::class.java)
        ?: return emptyList()
    val adapter = manager.adapter ?: return emptyList()
    // 复用信号源的读取逻辑，经典蓝牙（A2DP/HEADSET）与 BLE 都能识别。
    val connected = connectedDevices(context)
    val bonded = runCatching { adapter.bondedDevices.orEmpty() }
        .getOrDefault(emptySet())
    val entries = linkedMapOf<String, BluetoothEntry>()
    bonded.forEach { device ->
        val (name, address) = BluetoothDeviceReader.describe(context, device)
        if (address.isNotBlank()) {
            entries[address] = BluetoothEntry(
                name = BluetoothDeviceReader.displayName(connected[address] ?: name, address),
                address = address,
                connected = address in connected
            )
        }
    }
    connected.forEach { (address, name) ->
        if (address !in entries) {
            entries[address] = BluetoothEntry(
                name = BluetoothDeviceReader.displayName(name, address),
                address = address,
                connected = true
            )
        }
    }
    entries.values.sortedWith(
        compareByDescending<BluetoothEntry> { it.connected }.thenBy { it.name }
    )
}.getOrDefault(emptyList())

@Composable
private fun BluetoothPickerDialog(
    context: Context,
    selectedAddress: String,
    onDismiss: () -> Unit,
    onPick: (String, String) -> Unit
) {
    val devices = remember(context) { readBluetoothDevices(context) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 60.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TopBarPill(text = "取消", onClick = onDismiss)
                        Text(
                            text = "选择蓝牙设备",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(64.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    if (devices.isEmpty()) {
                        Text(
                            text = "没有读到已配对设备。请先在系统设置里配对蓝牙，" +
                                "或直接在输入框填写设备名称；留空表示任意设备。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    } else {
                        Text(
                            text = "已连接与已配对设备都会列出，选择后按设备地址精确匹配。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                        LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                            items(devices, key = { it.address }) { device ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .bounceClick(shape = RoundedCornerShape(16.dp)) {
                                            onPick(device.name, device.address)
                                        }
                                        .padding(vertical = 10.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .background(Lavender, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            painter = painterResource(
                                                R.drawable.ic_rule_bluetooth
                                            ),
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = device.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = device.address,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (device.connected) {
                                        Pill(
                                            text = "已连接",
                                            color = MintSoft,
                                            contentColor = Mint
                                        )
                                    } else if (device.address.equals(
                                            selectedAddress,
                                            ignoreCase = true
                                        )
                                    ) {
                                        Pill(
                                            text = "已选择",
                                            color = CoralSoft,
                                            contentColor = Coral
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun loadAppIcon(context: Context, packageName: String): ImageBitmap? = runCatching {
    context.packageManager.getApplicationIcon(packageName)
        .toBitmap(96, 96)
        .asImageBitmap()
}.getOrNull()

// 不绘制任何点击反馈，供主动提醒页覆盖 LocalIndication 使用。
private object ReminderNoIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = Node()

    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = System.identityHashCode(this)

    private class Node : androidx.compose.ui.Modifier.Node()
}
