package com.capsulepal.app.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.capsulepal.app.R
import com.capsulepal.app.data.model.AppSettings
import com.capsulepal.app.data.model.CapsulePage
import com.capsulepal.app.data.model.CapsuleVersion
import com.capsulepal.app.data.model.CategoryEntry
import com.capsulepal.app.data.model.Plan
import com.capsulepal.app.data.model.TaskCategory
import com.capsulepal.app.data.model.TaskKind
import com.capsulepal.app.partner.PartnerMood
import com.capsulepal.app.ui.components.CapsuleButton
import com.capsulepal.app.ui.components.PartnerFace
import com.capsulepal.app.ui.components.SoftCard
import com.capsulepal.app.ui.components.bounceClick
import androidx.activity.compose.BackHandler
import com.capsulepal.app.ui.screens.GeekBatteryScreen
import com.capsulepal.app.ui.screens.GeekOverlayScreen
import com.capsulepal.app.ui.screens.GeekPowerScreen
import com.capsulepal.app.ui.screens.MonitorScreen
import com.capsulepal.app.ui.screens.PartnerScreen
import com.capsulepal.app.ui.screens.PlansScreen
import com.capsulepal.app.ui.screens.ReminderRulesScreen
import com.capsulepal.app.ui.screens.ScheduleEditorSheet
import com.capsulepal.app.ui.screens.ScheduleScreen
import com.capsulepal.app.ui.screens.SettingsScreen
import com.capsulepal.app.ui.screens.SummaryScreen
import com.capsulepal.app.ui.screens.TaskEditorSheet
import com.capsulepal.app.ui.screens.TodayScreen
import com.capsulepal.app.ui.screens.UserNoticeDialog
import com.capsulepal.app.ui.screens.newTaskDraft
import com.capsulepal.app.ui.screens.toDraft
import com.capsulepal.app.ui.theme.CapsuleTheme
import com.capsulepal.app.ui.theme.Coral
import com.capsulepal.app.ui.theme.Mint
import kotlinx.coroutines.delay
import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import java.time.LocalDate

@DrawableRes
private fun CapsulePage.iconRes(): Int = when (this) {
    CapsulePage.POWER -> R.drawable.ic_ui_bolt
    CapsulePage.BATTERY -> R.drawable.ic_ui_battery
    CapsulePage.MONITOR -> R.drawable.ic_monitor
    CapsulePage.PARTNER -> R.drawable.ic_capsule
    CapsulePage.TODAY -> R.drawable.ic_today
    CapsulePage.SCHEDULE -> R.drawable.ic_calendar
    CapsulePage.PLANS -> R.drawable.ic_ui_plan
    CapsulePage.OVERLAY -> R.drawable.ic_ui_layers
    CapsulePage.SETTINGS -> R.drawable.ic_settings
}

@Composable
fun AppRoot(
    requestedPage: CapsulePage? = null,
    onRequestHandled: () -> Unit = {}
) {
    val vm: CapsuleViewModel = viewModel()
    val state by vm.uiState.collectAsStateWithLifecycle()
    val completion by vm.completion.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val suggestion by vm.suggestion.collectAsStateWithLifecycle()
    val schedule by vm.scheduleState.collectAsStateWithLifecycle()
    val plans by vm.plansState.collectAsStateWithLifecycle()
    val summary by vm.summaryState.collectAsStateWithLifecycle()
    val monitor by vm.monitor.collectAsStateWithLifecycle()
    val geek by vm.geek.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        vm.toast.collect { text ->
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        }
    }

    var tab by rememberSaveable { mutableStateOf(CapsulePage.TODAY) }
    var editor by remember { mutableStateOf<com.capsulepal.app.data.model.TaskDraft?>(null) }
    var editorPlan by remember { mutableStateOf<com.capsulepal.app.data.model.Plan?>(null) }
    var editorKind by remember { mutableStateOf(TaskKind.NORMAL) }
    var editorSaving by remember { mutableStateOf(false) }
    var showColorOsGuide by remember { mutableStateOf(false) }
    var showReminderRules by rememberSaveable { mutableStateOf(false) }
    var showSchedule by rememberSaveable { mutableStateOf(false) }
    var showSummary by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }

    val current = state
    if (current == null) {
        LoadingScreen()
        return
    }

    val version = current.settings.version
    // 切换版本后，如果当前页在新版本里不存在，回到中间页。
    LaunchedEffect(version) {
        if (!version.usesBottomBar) {
            tab = CapsulePage.TODAY
        } else if (tab !in version.pages) {
            tab = version.centerPage ?: version.pages.first()
        }
    }

    // 桌面组件点进来：落到对应页面；当前版本没有这一页时保持默认页。
    LaunchedEffect(requestedPage, version) {
        val page = requestedPage ?: return@LaunchedEffect
        if (version.usesBottomBar && page in version.pages) {
            tab = page
        }
        onRequestHandled()
    }

    CapsuleTheme(version = version) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                if (version.usesBottomBar) {
                    CapsuleBottomBar(
                        pages = version.pages,
                        center = version.centerPage,
                        selected = tab,
                        onSelect = { tab = it }
                    )
                }
            }
        ) { padding ->
            Crossfade(
                targetState = if (version.usesBottomBar) tab else CapsulePage.TODAY,
                animationSpec = tween(220),
                label = "capsuleTab",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) { selected ->
                when (selected) {
                    CapsulePage.MONITOR -> MonitorScreen(
                        state = monitor,
                        version = version,
                        geek = geek,
                        onSetRatedCapacity = vm::setRatedCapacity,
                        onSetBatteryCells = vm::setBatteryCells,
                        onRefresh = vm::refreshMonitor,
                        onRefreshGeek = vm::refreshGeek,
                        onResetLearning = vm::resetBatteryLearning
                    )
                    CapsulePage.POWER -> GeekPowerScreen(
                        state = monitor,
                        vm = vm
                    )
                    CapsulePage.BATTERY -> GeekBatteryScreen(
                        state = monitor,
                        vm = vm
                    )
                    CapsulePage.OVERLAY -> GeekOverlayScreen(
                        state = monitor,
                        vm = vm
                    )
                    CapsulePage.SCHEDULE -> {
                        val monthState = schedule
                        if (monthState == null) {
                            LoadingScreen()
                        } else {
                            ScheduleTab(
                                state = monthState,
                                onSelectDate = vm::selectDate,
                                onChangeMonth = vm::changeMonth,
                                onGoToday = vm::goToday,
                                onAddTask = { date ->
                                    editorKind = TaskKind.SCHEDULE
                                    editorPlan = null
                                    editor = newTaskDraft(
                                        date = date,
                                        category = current.settings.defaultCategory(),
                                        kind = TaskKind.SCHEDULE
                                    )
                                },
                                onEditTask = { task ->
                                    editorKind = task.kind
                                    editor = task.toDraft()
                                }
                            )
                        }
                    }
                    CapsulePage.TODAY -> TodayScreen(
                        state = current,
                        suggestion = suggestion,
                        showScheduleEntry = version.showScheduleEntry,
                        showSettingsEntry = !version.usesBottomBar,
                        onOpenSettings = { showSettings = true },
                        onAddTask = {
                            editorKind = TaskKind.NORMAL
                            editor = newTaskDraft(
                                current.date,
                                current.settings.defaultCategory()
                            )
                        },
                        onEditTask = { task ->
                            editorKind = task.kind
                            editor = task.toDraft()
                        },
                        onOpenSchedule = { showSchedule = true },
                        onOpenSummary = { showSummary = true },
                        vm = vm
                    )
                    CapsulePage.PLANS -> PlansScreen(
                        state = plans,
                        vm = vm,
                        onSavePlan = { vm.savePlan(it) },
                        onArchivePlan = vm::setPlanArchived,
                        onDeletePlan = vm::deletePlan,
                        onAddTask = { plan ->
                            editorKind = TaskKind.NORMAL
                            editorPlan = plan
                            editor = newTaskDraft(
                                date = planTaskDate(plan, current.date),
                                category = current.settings.defaultCategory(),
                                planId = plan.id
                            )
                        },
                        onQuickAddTask = { plan, title ->
                            vm.saveDraft(
                                newTaskDraft(
                                    date = planTaskDate(plan, current.date),
                                    category = current.settings.defaultCategory(),
                                    planId = plan.id
                                ).copy(title = title)
                            )
                        },
                        onEditTask = { task ->
                            editorKind = TaskKind.NORMAL
                            editor = task.toDraft()
                        },
                        onToggleTask = vm::toggle,
                        onDeleteSeries = vm::deleteSeries
                    )
                    CapsulePage.PARTNER -> PartnerScreen(
                        state = current,
                        charging = monitor?.reading?.charging == true,
                        onRename = vm::renamePartner,
                        onToggleAppearance = vm::setPartnerAppearance
                    )
                    CapsulePage.SETTINGS -> SettingsScreen(
                        state = current,
                        vm = vm,
                        onShowColorOsGuide = { showColorOsGuide = true },
                        onOpenReminderRules = { showReminderRules = true }
                    )
                }
            }
        }

        // 极简待办版没有底栏，设置从右上角进入，作为整页浮层显示。
        if (showSettings) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                Box(modifier = Modifier.statusBarsPadding()) {
                    SettingsScreen(
                        state = current,
                        vm = vm,
                        onShowColorOsGuide = { showColorOsGuide = true },
                        onOpenReminderRules = { showReminderRules = true },
                        onBack = { showSettings = false }
                    )
                }
            }
        }
        BackHandler(enabled = showSettings) { showSettings = false }

        AnimatedVisibility(
            visible = showReminderRules,
            enter = fadeIn(tween(180)) + slideInVertically(tween(240)) { it / 12 },
            exit = fadeOut(tween(160)) + slideOutVertically(tween(200)) { it / 12 }
        ) {
            ReminderRulesScreen(
                settings = current.settings,
                tasks = current.tasks,
                vm = vm,
                onBack = { showReminderRules = false }
            )
        }
        BackHandler(enabled = showReminderRules) { showReminderRules = false }

        if (showSchedule) {
            val monthState = schedule
            if (monthState == null) {
                LoadingScreen()
            } else {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Box(modifier = Modifier.statusBarsPadding()) {
                        ScheduleScreen(
                            state = monthState,
                            onSelectDate = vm::selectDate,
                            onChangeMonth = vm::changeMonth,
                            onGoToday = vm::goToday,
                            onAddTask = { date ->
                                editorKind = TaskKind.SCHEDULE
                                editorPlan = null
                                editor = newTaskDraft(
                                    date = date,
                                    category = current.settings.defaultCategory(),
                                    kind = TaskKind.SCHEDULE
                                )
                            },
                            onEditTask = { task ->
                                editorKind = task.kind
                                editor = task.toDraft()
                            },
                            onBack = { showSchedule = false }
                        )
                    }
                }
            }
        }
        BackHandler(enabled = showSchedule) { showSchedule = false }

        if (showSummary) {
            SummaryScreen(
                state = summary,
                onBack = { showSummary = false },
                onSelectRange = vm::setSummaryRange,
                onShift = vm::shiftSummary,
                onToday = vm::resetSummaryToToday
            )
        }
        BackHandler(enabled = showSummary) { showSummary = false }

        FeedbackBanner(
            completionText = completion?.let {
                "「${it.taskTitle}」完成 · +${it.gainedXp} 成长"
            },
            message = message,
            iconRes = if (completion != null) R.drawable.ic_ui_sparkle else R.drawable.ic_ui_chat,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(
                    start = 20.dp,
                    end = 20.dp,
                    bottom = if (version.usesBottomBar) 96.dp else 24.dp
                )
        )

        fun closeEditor() {
            editor = null
            editorPlan = null
            editorSaving = false
        }

        fun persistEditor(draft: com.capsulepal.app.data.model.TaskDraft) {
            if (!editorSaving) {
                editorSaving = true
                vm.saveDraft(draft) { saved ->
                    editorSaving = false
                    if (saved) closeEditor()
                }
            }
        }

        editor?.let { draft ->
            if (editorKind == TaskKind.SCHEDULE) {
                ScheduleEditorSheet(
                    initial = draft,
                    isNew = draft.id == 0L,
                    onDismiss = { closeEditor() },
                    onSave = { persistEditor(it) },
                    onDelete = { id ->
                        vm.deleteTask(id)
                        closeEditor()
                    },
                    onDeleteSeries = { id ->
                        vm.deleteTaskFrom(id)
                        closeEditor()
                    },
                    categoryLayout = current.settings.categoryLayout,
                    saving = editorSaving
                )
            } else {
                TaskEditorSheet(
                    initial = draft,
                    isNew = draft.id == 0L,
                    groups = vm.todoState.collectAsStateWithLifecycle().value?.groups.orEmpty(),
                    plans = plans?.plans?.map { it.plan }?.filter { !it.archived }.orEmpty(),
                    planContext = editorPlan,
                    onDismiss = { closeEditor() },
                    onSave = { persistEditor(it) },
                    onDelete = { id ->
                        vm.deleteTask(id)
                        closeEditor()
                    },
                    onDeleteSeries = { id ->
                        vm.deleteTaskFrom(id)
                        closeEditor()
                    },
                    categoryLayout = current.settings.categoryLayout,
                    onAddCustomCategory = vm::addCustomCategory,
                    saving = editorSaving
                )
            }
        }

        if (!current.settings.noticeAccepted) {
            UserNoticeDialog(
                mustAccept = true,
                onAccept = vm::acceptUserNotice,
                onDismiss = {}
            )
        } else if (!current.settings.onboardingDone) {
            OnboardingOverlay(
                name = current.partner.name,
                onDone = vm::completeOnboarding,
                onShowColorOsGuide = { showColorOsGuide = true }
            )
        }

        if (showColorOsGuide) {
            ColorOsGuideDialog(onDismiss = { showColorOsGuide = false })
        }
    }

    LaunchedEffect(completion, message) {
        if (completion != null || message != null) {
            delay(3_400L)
            vm.dismissCompletion()
            vm.clearMessage()
        }
    }
    }
}

private fun AppSettings.defaultCategory(): TaskCategory =
    categoryLayout.filterIsInstance<CategoryEntry.BuiltIn>().firstOrNull()?.category
        ?: TaskCategory.ROUTINE

/** 计划内新建待办默认落在今天，并收进周期计划的起止区间。 */
private fun planTaskDate(plan: Plan, today: LocalDate): LocalDate {
    val start = plan.startDate
    val end = plan.targetDate
    return when {
        start != null && today.isBefore(start) -> start
        end != null && today.isAfter(end) -> end
        else -> today
    }
}

@Composable
private fun LoadingScreen() {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            PartnerFace(mood = PartnerMood.IDLE, size = 104.dp)
            Spacer(Modifier.height(12.dp))
            Text(
                text = "小胶囊正在醒来…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ScheduleTab(
    state: com.capsulepal.app.ui.ScheduleUiState,
    onSelectDate: (java.time.LocalDate) -> Unit,
    onChangeMonth: (Long) -> Unit,
    onGoToday: () -> Unit,
    onAddTask: (java.time.LocalDate) -> Unit,
    onEditTask: (com.capsulepal.app.data.model.Task) -> Unit
) {
    ScheduleScreen(
        state = state,
        onSelectDate = onSelectDate,
        onChangeMonth = onChangeMonth,
        onGoToday = onGoToday,
        onAddTask = onAddTask,
        onEditTask = onEditTask,
        onBack = {},
        showBack = false
    )
}

@Composable
private fun CapsuleBottomBar(
    pages: List<CapsulePage>,
    center: CapsulePage?,
    selected: CapsulePage,
    onSelect: (CapsulePage) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 8.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                pages.forEach { item ->
                    val isSelected = item == selected
                    val isCenter = item == center
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 52.dp)
                            // 非中间项任何状态都不加外层包裹，只靠图标与文字颜色表达选中。
                            .bounceClick { onSelect(item) }
                            .padding(vertical = 5.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        if (isCenter) {
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(item.iconRes()),
                                    contentDescription = item.label,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        } else {
                            Icon(
                                painter = painterResource(item.iconRes()),
                                contentDescription = item.label,
                                tint = if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(19.dp)
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = item.label,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                            ),
                            color = when {
                                isCenter -> MaterialTheme.colorScheme.primary
                                isSelected -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FeedbackBanner(
    completionText: String?,
    message: String?,
    @DrawableRes iconRes: Int,
    modifier: Modifier = Modifier
) {
    val text = completionText ?: message
    AnimatedVisibility(
        visible = text != null,
        enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { it / 2 },
        exit = fadeOut(tween(160)) + slideOutVertically(tween(200)) { it / 2 },
        modifier = modifier
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shadowElevation = 6.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = text.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
}

@Composable
private fun OnboardingOverlay(
    name: String,
    onDone: () -> Unit,
    onShowColorOsGuide: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var permissions by remember { mutableStateOf(readPermissionState(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissions = readPermissionState(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { permissions = readPermissionState(context) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(28.dp))
            PartnerFace(mood = PartnerMood.IDLE, size = 124.dp)
            Spacer(Modifier.height(12.dp))
            Text(
                text = "胶囊伙伴",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "一次只陪当前这一件事",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))

            SoftCard {
                Text(
                    text = "它是怎么陪你的",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(10.dp))
                OnboardingPoint(R.drawable.ic_calendar, "全天日程在这里排", "准备窗口、进行中、完成后衔接下一件")
                OnboardingPoint(R.drawable.ic_notification, "岛上只看当前一件", "Android16及以上系统上岛，不做常驻动画")
                OnboardingPoint(R.drawable.ic_ui_lock, "数据只留在本机", "没有 INTERNET 权限，也没有外部接口")
            }

            Spacer(Modifier.height(16.dp))

            SoftCard {
                Text(
                    text = "先把关键权限打开",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "之后也可以在设置里补开。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                PermissionRow(
                    iconRes = R.drawable.ic_ui_bell,
                    title = "通知",
                    description = "提醒和上岛的基础",
                    granted = permissions.notifications,
                    onClick = {
                        if (Build.VERSION.SDK_INT >= 33) {
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                )
                PermissionRow(
                    iconRes = R.drawable.ic_notification,
                    title = "悬浮窗",
                    description = "1px 透明悬浮点保活",
                    granted = permissions.overlay,
                    onClick = { openSafely(context, overlaySettingsIntent(context)) }
                )
                PermissionRow(
                    iconRes = R.drawable.ic_timer,
                    title = "精确闹钟",
                    description = "准备/开始/结束准点触发",
                    granted = permissions.exactAlarm,
                    onClick = { openSafely(context, exactAlarmSettingsIntent(context)) }
                )
                PermissionRow(
                    iconRes = R.drawable.ic_ui_battery,
                    title = "电池优化白名单",
                    description = "降低后台被回收的概率",
                    granted = permissions.batteryUnrestricted,
                    grantedLabel = "已放行",
                    onClick = { openSafely(context, batteryOptimizationIntent(context)) }
                )
                TextButton(
                    onClick = onShowColorOsGuide,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(
                        text = "后台设置步骤",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                Spacer(Modifier.height(4.dp))
                CapsuleButton(
                    text = "开始使用",
                    onClick = onDone,
                    modifier = Modifier.fillMaxWidth(),
                    height = 54.dp
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "$name 会在第一个准备窗口前出现。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }

            Spacer(Modifier.height(20.dp))
            Text(
                text = "所有数据本地产生、本地消费",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun OnboardingPoint(@DrawableRes iconRes: Int, title: String, detail: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(18.dp)
        )
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

@Composable
private fun ColorOsGuideDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("后台守护") },
        text = {
            Column {
                Text(
                    text = "Android16及以上系统需要手动放行几步，否则锁屏后容易被清掉：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                GuideStep("1", "设置 → 应用管理 → 胶囊伙伴 → 自启动 / 关联启动：打开")
                GuideStep("2", "设置 → 电池 → 应用耗电管理 → 胶囊伙伴：允许后台活动")
                GuideStep("3", "最近任务 → 下拉胶囊伙伴卡片 → 锁定")
                GuideStep("4", "设置 → 通知与状态栏 → 流体云 / 实况通知：确认开启")
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "悬浮窗权限被系统回收时，App 内会提示重新打开。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    openSafely(context, colorOsAutoStartIntent(context))
                    onDismiss()
                }
            ) { Text("去自启动设置") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("知道了") }
        }
    )
}

@Composable
private fun GuideStep(index: String, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = index,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
