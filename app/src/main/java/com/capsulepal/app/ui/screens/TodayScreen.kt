package com.capsulepal.app.ui.screens

import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.capsulepal.app.R
import com.capsulepal.app.data.model.ActiveTask
import com.capsulepal.app.data.model.CategoryEntry
import com.capsulepal.app.data.model.Priority
import com.capsulepal.app.data.model.Task
import com.capsulepal.app.data.model.TaskCategory
import com.capsulepal.app.data.model.TaskGroup
import com.capsulepal.app.data.model.TaskPhase
import com.capsulepal.app.data.model.TodoLayout
import com.capsulepal.app.data.model.displayCategory
import com.capsulepal.app.data.model.TaskKind
import com.capsulepal.app.data.model.iconRes
import com.capsulepal.app.data.model.TaskStatus
import com.capsulepal.app.data.model.UpcomingTask
import com.capsulepal.app.data.repo.InstantTodo
import com.capsulepal.app.domain.DailyRollup
import com.capsulepal.app.domain.HolidayProvider
import com.capsulepal.app.domain.TimeUtils
import com.capsulepal.app.domain.TriggerSuggestion
import com.capsulepal.app.partner.PartnerAppearance
import com.capsulepal.app.partner.PartnerCopy
import com.capsulepal.app.partner.PartnerMood
import com.capsulepal.app.partner.PartnerOutfit
import com.capsulepal.app.partner.PartnerState
import com.capsulepal.app.ui.CapsuleUiState
import com.capsulepal.app.ui.CapsuleViewModel
import com.capsulepal.app.ui.TodoRange
import com.capsulepal.app.ui.TodoUiState
import com.capsulepal.app.ui.components.AnimatedCheck
import com.capsulepal.app.ui.components.CapsuleButton
import com.capsulepal.app.ui.components.CapsuleProgressBar
import com.capsulepal.app.ui.components.PartnerFace
import com.capsulepal.app.ui.components.Pill
import com.capsulepal.app.ui.components.ProgressRing
import com.capsulepal.app.ui.components.SoftCard
import com.capsulepal.app.ui.components.bounceClick
import com.capsulepal.app.ui.theme.Butter
import com.capsulepal.app.ui.theme.Coral
import com.capsulepal.app.ui.theme.CoralDeep
import com.capsulepal.app.ui.theme.Lavender
import com.capsulepal.app.ui.theme.Mint
import kotlinx.coroutines.delay
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    state: CapsuleUiState,
    suggestion: TriggerSuggestion?,
    onAddTask: () -> Unit,
    onEditTask: (Task) -> Unit,
    onOpenSchedule: () -> Unit,
    onOpenSummary: () -> Unit,
    vm: CapsuleViewModel,
    showScheduleEntry: Boolean = true,
    showSettingsEntry: Boolean = false,
    onOpenSettings: () -> Unit = {}
) {
    val active = state.active
    val next = state.next
    val partner = state.partner
    // 用户自己建过待办后，模板只作为初始引导，不再出现在今天页。
    val hasUserTask = state.tasks.any { !it.fromTemplate }
    val recentSkip by vm.recentSkip.collectAsStateWithLifecycle()
    val highlightTaskId by vm.highlightTaskId.collectAsStateWithLifecycle()
    val partnerState = PartnerCopy.dayState(
        date = state.date,
        tasks = state.tasks,
        active = active,
        next = next,
        nowMillis = state.nowMillis,
        holidayName = state.holidayName,
        recentSkip = recentSkip
    )
    val monitor by vm.monitor.collectAsStateWithLifecycle()
    val todo by vm.todoState.collectAsStateWithLifecycle()
    val plansState by vm.plansState.collectAsStateWithLifecycle()
    val planTitles = remember(plansState) {
        plansState?.plans?.associate { it.plan.id to it.plan.title }.orEmpty()
    }
    val instantTodo by vm.instantTodo.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showInstantDialog by remember { mutableStateOf(false) }
    var instantDraft by remember { mutableStateOf("") }
    var showGroupManager by remember { mutableStateOf(false) }
    var actionTask by remember { mutableStateOf<Task?>(null) }
    var datePickerTask by remember { mutableStateOf<Task?>(null) }
    val partnerOutfit = remember(state.date, state.holidayName, partnerState.mood, monitor?.reading?.charging) {
        PartnerOutfit.auto(
            date = state.date,
            holidayName = state.holidayName,
            mood = partnerState.mood,
            charging = monitor?.reading?.charging == true
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 116.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                DateHeader(
                    state = state,
                    onOpenSummary = onOpenSummary,
                    onOpenSchedule = onOpenSchedule,
                    showScheduleEntry = showScheduleEntry,
                    showSettingsEntry = showSettingsEntry,
                    onOpenSettings = onOpenSettings
                )
            }
            item {
                PartnerBubble(
                    state = partnerState,
                    name = partner.name,
                    appearance = state.settings.partnerAppearance,
                    outfit = partnerOutfit,
                    suggestion = if (instantTodo == null) suggestion else null,
                    messageOverride = instantTodo?.let {
                        "当前有即时待办，「${it.title}」紧急优先！"
                    },
                    onClick = { vm.quickBackup() },
                    onAcceptSuggestion = vm::acceptSuggestion,
                    onDismissSuggestion = vm::dismissSuggestion
                )
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    instantTodo?.let { todo ->
                        InstantTodoCard(
                            todo = todo,
                            onComplete = vm::completeInstantTodo
                        )
                    }
                    when {
                        active != null -> ActiveCard(
                            active = active,
                            onStart = { vm.startNow(active.task, TaskPhase.IN_PROGRESS) },
                            onComplete = { vm.complete(active.task) },
                            onSkip = { vm.skip(active.task) }
                        )
                        instantTodo != null -> Unit
                        next != null -> NextCard(
                            next = next,
                            onStartPrepare = { vm.startNow(next.task, TaskPhase.PREPARE) },
                            onEdit = { onEditTask(next.task) }
                        )
                        else -> EmptyDayCard(
                            showTemplate = !hasUserTask,
                            onTemplate = { vm.applyTemplateFor(state.date) },
                            onAdd = onAddTask,
                            appearance = state.settings.partnerAppearance,
                            outfit = partnerOutfit
                        )
                    }
                }
            }
            val filter = todo
            if (filter == null) {
                if (state.tasks.isNotEmpty()) {
                    item { TimelineHeader(state) }
                    item {
                        TimelineCard(
                            tasks = state.tasks,
                            nowMillis = state.nowMillis,
                            highlightTaskId = highlightTaskId,
                            planTitles = planTitles,
                            onEdit = onEditTask,
                            onToggle = vm::toggle,
                            onRestore = vm::restore,
                            onLongPress = { actionTask = it }
                        )
                    }
                }
            } else {
                item {
                    TodoFilterBar(
                        todo = filter,
                        categories = state.settings.categoryLayout,
                        onRange = vm::setTodoRange,
                        onCustomDays = vm::setTodoCustomDays,
                        onGroup = vm::setTodoGroupFilter,
                        onCategory = vm::setTodoCategoryFilter,
                        onPriority = vm::setTodoPriorityFilter,
                        onPlan = vm::setTodoPlanFilter,
                        onLayout = vm::setTodoLayout,
                        onManageGroups = { showGroupManager = true }
                    )
                }
                val defaultView = filter.range == TodoRange.TODAY &&
                    filter.groupFilter == -1L &&
                    filter.categoryFilter == null &&
                    filter.priorityFilter == null &&
                    filter.planFilter == null &&
                    filter.layout == TodoLayout.LIST
                if (filter.tasks.isEmpty()) {
                    item {
                        SoftCard(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = if (defaultView) "今天还没有安排，先从一件小事开始吧。" else "这个筛选条件下没有待办。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else if (defaultView) {
                    item { TimelineHeader(state) }
                    item {
                        TimelineCard(
                            tasks = filter.tasks,
                            nowMillis = state.nowMillis,
                            highlightTaskId = highlightTaskId,
                            planTitles = planTitles,
                            onEdit = onEditTask,
                            onToggle = vm::toggle,
                            onRestore = vm::restore,
                            onLongPress = { actionTask = it }
                        )
                    }
                    if (!hasUserTask) {
                        item {
                            TextButton(
                                onClick = { vm.applyTemplateFor(state.date) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "按${HolidayProvider.templateType(state.date).label}模板补几件",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    filter.tasks.groupBy { it.date }.forEach { (date, dayTasks) ->
                        item(key = "day-$date") {
                            TodoDayHeader(date = date, today = filter.today)
                        }
                        if (filter.layout == TodoLayout.GRID) {
                            dayTasks.chunked(2).forEach { pair ->
                                item(key = "grid-${pair.first().id}") {
                                    TodoGridRow(
                                        tasks = pair,
                                        planTitles = planTitles,
                                        onEdit = onEditTask,
                                        onToggle = vm::toggle,
                                        onRestore = vm::restore,
                                        onLongPress = { actionTask = it }
                                    )
                                }
                            }
                        } else {
                            item(key = "list-$date") {
                                SoftCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    dayTasks.forEachIndexed { index, task ->
                                        TaskRow(
                                            task = task,
                                            highlighted = task.id == highlightTaskId,
                                            planTitles = planTitles,
                                            onClick = { onEditTask(task) },
                                            onToggle = { vm.toggle(task) },
                                            onRestore = { vm.restore(task) },
                                            onLongPress = { actionTask = it }
                                        )
                                        if (index != dayTasks.lastIndex) {
                                            HorizontalDivider(
                                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
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

        if (showGroupManager) {
            GroupManagerDialog(
                groups = todo?.groups.orEmpty(),
                onDismiss = { showGroupManager = false },
                onCreate = vm::createGroup,
                onRename = vm::renameGroup,
                onDelete = vm::deleteGroup,
                onMove = vm::moveGroup
            )
        }

        actionTask?.let { task ->
            AlertDialog(
                onDismissRequest = { actionTask = null },
                title = {
                    Text(
                        text = task.title,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                text = {
                    Column {
                        TaskActionRow(
                            title = "立即开始",
                            subtitle = "把开始时间改到现在，并进入进行中"
                        ) {
                            actionTask = null
                            vm.startRightNow(task)
                        }
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                        )
                        TaskActionRow(
                            title = "挪到明天",
                            subtitle = "保留 ${TimeUtils.formatClock(task.startTime)}，只改日期"
                        ) {
                            actionTask = null
                            vm.moveTaskTo(task, LocalDate.now().plusDays(1))
                        }
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                        )
                        TaskActionRow(title = "选其他日期", subtitle = null) {
                            actionTask = null
                            datePickerTask = task
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { actionTask = null }) { Text("取消") }
                }
            )
        }

        datePickerTask?.let { task ->
            val todayEpochDay = LocalDate.now().toEpochDay()
            val pickerState = rememberDatePickerState(
                initialSelectedDateMillis = maxOf(task.date.toEpochDay(), todayEpochDay) *
                    MILLIS_PER_DAY,
                selectableDates = object : SelectableDates {
                    override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                        utcTimeMillis / MILLIS_PER_DAY >= todayEpochDay
                }
            )
            DatePickerDialog(
                onDismissRequest = { datePickerTask = null },
                confirmButton = {
                    TextButton(
                        onClick = {
                            pickerState.selectedDateMillis?.let { millis ->
                                vm.moveTaskTo(task, LocalDate.ofEpochDay(millis / MILLIS_PER_DAY))
                            }
                            datePickerTask = null
                        }
                    ) { Text("确定") }
                },
                dismissButton = {
                    TextButton(onClick = { datePickerTask = null }) { Text("取消") }
                }
            ) {
                DatePicker(
                    state = pickerState,
                    title = { Text("挪到哪一天", modifier = Modifier.padding(16.dp)) }
                )
            }
        }

        Surface(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 24.dp)
                .size(56.dp)
                .clip(CircleShape)
                .combinedClickable(
                    onClickLabel = "添加一件事",
                    onLongClickLabel = "创建即时待办",
                    onClick = onAddTask,
                    onLongClick = {
                        if (instantTodo != null) {
                            Toast.makeText(
                                context,
                                "只能创建一个即时待办，请先完成旧的即时待办",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            instantDraft = ""
                            showInstantDialog = true
                        }
                    }
                ),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shadowElevation = 6.dp
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(R.drawable.ic_ui_add),
                    contentDescription = "添加一件事"
                )
            }
        }

        if (showInstantDialog) {
            InstantTodoDialog(
                title = instantDraft,
                onTitleChange = { instantDraft = it },
                onDismiss = { showInstantDialog = false },
                onConfirm = {
                    if (vm.createInstantTodo(instantDraft)) {
                        showInstantDialog = false
                        instantDraft = ""
                    } else {
                        Toast.makeText(
                            context,
                            "只能创建一个即时待办，请先完成旧的即时待办",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            )
        }
    }
}

@Composable
private fun InstantTodoDialog(
    title: String,
    onTitleChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("即时待办") },
        text = {
            Column {
                Text(
                    text = "临时的重要安排，创建后立即上岛，直到完成。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = title,
                    onValueChange = { onTitleChange(it.take(40)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    placeholder = { Text("例如：马上回个电话") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = title.isNotBlank()) {
                Text("创建")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
    LaunchedEffect(Unit) {
        // 等弹窗完成布局后再聚焦，输入法才会自动抬起。
        delay(150)
        focusRequester.requestFocus()
        keyboard?.show()
    }
}

private const val MILLIS_PER_DAY = 86_400_000L

@Composable
private fun TaskActionRow(title: String, subtitle: String?, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .bounceClick(shape = RoundedCornerShape(14.dp), onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DateHeader(
    state: CapsuleUiState,
    onOpenSummary: () -> Unit,
    onOpenSchedule: () -> Unit,
    showScheduleEntry: Boolean,
    showSettingsEntry: Boolean,
    onOpenSettings: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "${state.date.monthValue} 月 ${state.date.dayOfMonth} 日",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = TimeUtils.humanDate(state.date),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = buildCalendarLine(state),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (state.holidayName != null) {
                Spacer(Modifier.height(6.dp))
                Pill(
                    text = state.holidayName,
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    iconRes = R.drawable.ic_ui_sparkle
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HeaderIconButton(
                iconRes = R.drawable.ic_ui_chart,
                label = "总结",
                onClick = onOpenSummary
            )
            if (showScheduleEntry) {
                HeaderIconButton(
                    iconRes = R.drawable.ic_calendar,
                    label = "日程",
                    onClick = onOpenSchedule
                )
            }
            if (showSettingsEntry) {
                HeaderIconButton(
                    iconRes = R.drawable.ic_settings,
                    label = "设置",
                    onClick = onOpenSettings
                )
            }
        }
    }
}

@Composable
private fun HeaderIconButton(
    @androidx.annotation.DrawableRes iconRes: Int,
    label: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .bounceClick(onClick = onClick),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = label,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

private fun buildCalendarLine(state: CapsuleUiState): String {
    val parts = mutableListOf<String>()
    if (state.lunarLabel.isNotBlank()) parts += "农历${state.lunarLabel}"
    parts += if (state.isRestDay) "休息日" else "工作日"
    val holidayDays = state.nextHolidayDays
    if (state.nextHolidayName != null && holidayDays != null && holidayDays in 0..21) {
        parts += PartnerCopy.upcomingHoliday(state.nextHolidayName, holidayDays)
    }
    return parts.joinToString(" · ")
}

@Composable
private fun PartnerBubble(
    state: PartnerState,
    name: String,
    appearance: Set<PartnerAppearance>,
    outfit: PartnerOutfit?,
    suggestion: TriggerSuggestion?,
    onClick: () -> Unit,
    onAcceptSuggestion: () -> Unit,
    onDismissSuggestion: () -> Unit,
    messageOverride: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .bounceClick(onClick = onClick),
        verticalAlignment = Alignment.Top
    ) {
        PartnerFace(mood = state.mood, appearance = appearance, outfit = outfit, size = 64.dp)
        Spacer(Modifier.width(10.dp))
        Surface(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(topStart = 6.dp, topEnd = 22.dp, bottomEnd = 22.dp, bottomStart = 22.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Text(
                    text = messageOverride ?: state.message,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = if (messageOverride != null) FontWeight.SemiBold else FontWeight.Normal
                    ),
                    color = if (messageOverride != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
                if (suggestion != null) {
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            painter = painterResource(suggestion.iconRes),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = suggestion.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "先不用",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .bounceClick(onClick = onDismissSuggestion)
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                        if (suggestion.draft != null || suggestion.canStartPrepare) {
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = if (suggestion.draft != null) "加入日程" else "现在准备",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .bounceClick(onClick = onAcceptSuggestion)
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "$name · ${state.mood.label}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun InstantTodoCard(
    todo: InstantTodo,
    onComplete: () -> Unit
) {
    val now = rememberNow(60_000L)
    val minutes = ((now - todo.startedAtMillis) / 60_000L).coerceAtLeast(0L)
    val accent = MaterialTheme.colorScheme.error
    val container = MaterialTheme.colorScheme.errorContainer
    val onContainer = MaterialTheme.colorScheme.onErrorContainer

    SoftCard(color = container) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(accent),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_instant_eyes),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onError,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Pill(
                        text = "即时",
                        color = accent,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "紧急优先",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = accent
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = todo.title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = onContainer,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "进行中 · 已进行 $minutes 分钟",
                    style = MaterialTheme.typography.bodyMedium,
                    color = onContainer
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "直到完成 · 不计入今日进度",
                    style = MaterialTheme.typography.bodySmall,
                    color = onContainer.copy(alpha = 0.72f)
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        CapsuleButton(
            text = "完成这件事",
            onClick = onComplete,
            modifier = Modifier.fillMaxWidth(),
            color = accent,
            contentColor = MaterialTheme.colorScheme.onError
        )
    }
}

@Composable
private fun ActiveCard(
    active: ActiveTask,
    onStart: () -> Unit,
    onComplete: () -> Unit,
    onSkip: () -> Unit
) {
    val now = rememberNow(1_000L)
    val remaining = active.remainingMillis(now)
    val progress = active.progress(now)
    val isPrepare = active.phase == TaskPhase.PREPARE
    val accent = MaterialTheme.colorScheme.onPrimaryContainer

    SoftCard(
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Pill(
                        text = if (isPrepare) "准备窗口" else "正在进行",
                        color = accent.copy(alpha = 0.10f),
                        contentColor = accent
                    )
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        painter = painterResource(active.task.category.iconRes()),
                        contentDescription = null,
                        tint = accent.copy(alpha = 0.7f),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = active.task.displayCategory(),
                        style = MaterialTheme.typography.labelMedium,
                        color = accent.copy(alpha = 0.7f)
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = active.task.title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = accent,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = TimeUtils.formatCountdown(remaining),
                        style = MaterialTheme.typography.displayMedium.copy(
                            fontFeatureSettings = "tnum"
                        ),
                        color = accent
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (isPrepare) "后开始" else "剩余",
                        style = MaterialTheme.typography.bodySmall,
                        color = accent.copy(alpha = 0.7f),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${TimeUtils.formatClock(active.task.startMillis)} - " +
                        "${TimeUtils.formatClock(active.task.endMillis)} · " +
                        TimeUtils.formatDuration(active.task.durationMinutes),
                    style = MaterialTheme.typography.bodySmall,
                    color = accent.copy(alpha = 0.7f)
                )
            }
            Spacer(Modifier.width(8.dp))
            ProgressRing(
                progress = progress,
                size = 86.dp,
                strokeWidth = 9.dp,
                color = accent,
                trackColor = accent.copy(alpha = 0.14f)
            ) {
                Text(
                    text = "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                    color = accent
                )
            }
        }
        if (active.task.note.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = active.task.note,
                style = MaterialTheme.typography.bodySmall,
                color = accent.copy(alpha = 0.75f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CapsuleButton(
                text = if (isPrepare) "开始进行" else "完成这件事",
                onClick = if (isPrepare) onStart else onComplete,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
            CapsuleButton(
                text = "跳过",
                onClick = onSkip,
                modifier = Modifier.weight(0.6f),
                filled = false,
                color = accent
            )
        }
    }
}

@Composable
private fun NextCard(
    next: UpcomingTask,
    onStartPrepare: () -> Unit,
    onEdit: () -> Unit
) {
    SoftCard(color = MaterialTheme.colorScheme.surface) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Pill(
                    text = "下一件",
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = next.task.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "${TimeUtils.formatClock(next.task.startMillis)} 开始 · 还有 " +
                        TimeUtils.formatMinutesRounded(next.untilStartMillis),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(12.dp))
            Icon(
                painter = painterResource(next.task.category.iconRes()),
                contentDescription = next.task.displayCategory(),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(30.dp)
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CapsuleButton(
                text = if (next.task.prepareMinutes > 0) "提前进入准备" else "现在开始",
                onClick = onStartPrepare,
                modifier = Modifier.weight(1f),
                height = 46.dp
            )
            CapsuleButton(
                text = "调整",
                onClick = onEdit,
                modifier = Modifier.weight(0.5f),
                filled = false,
                height = 46.dp
            )
        }
    }
}

@Composable
private fun EmptyDayCard(
    showTemplate: Boolean,
    onTemplate: () -> Unit,
    onAdd: () -> Unit,
    appearance: Set<PartnerAppearance>,
    outfit: PartnerOutfit?
) {
    val hour = java.time.LocalTime.now().hour
    SoftCard(color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            PartnerFace(
                mood = PartnerMood.forHour(hour),
                appearance = appearance,
                outfit = outfit,
                size = 104.dp
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "今天还没有安排",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "先把一件小事放进时间线，伙伴会在准备窗口轻轻提醒你。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (showTemplate) {
                    CapsuleButton(
                        text = "从模板安排",
                        onClick = onTemplate,
                        modifier = Modifier.weight(1f),
                        height = 46.dp
                    )
                }
                CapsuleButton(
                    text = "添加一件",
                    onClick = onAdd,
                    modifier = Modifier.weight(1f),
                    filled = false,
                    height = 46.dp
                )
            }
        }
    }
}

@Composable
private fun TimelineHeader(state: CapsuleUiState) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "今天的时间线",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${state.doneCount} / ${state.tasks.size}",
                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(8.dp))
        CapsuleProgressBar(
            progress = DailyRollup.progress(state.tasks),
            color = MaterialTheme.colorScheme.secondary
        )
    }
}

@Composable
private fun TimelineCard(
    tasks: List<Task>,
    nowMillis: Long,
    highlightTaskId: Long?,
    planTitles: Map<Long, String>,
    onEdit: (Task) -> Unit,
    onToggle: (Task) -> Unit,
    onRestore: (Task) -> Unit,
    onLongPress: (Task) -> Unit
) {
    val nowIndex = remember(tasks, nowMillis) {
        tasks.indexOfFirst { it.startMillis > nowMillis }
    }
    SoftCard(
        contentPadding = PaddingValues(0.dp)
    ) {
        tasks.forEachIndexed { index, task ->
            if (index == nowIndex && nowIndex > 0) {
                NowMarker(nowMillis)
            }
            TaskRow(
                task = task,
                highlighted = task.id == highlightTaskId,
                planTitles = planTitles,
                onClick = { onEdit(task) },
                onToggle = { onToggle(task) },
                onRestore = { onRestore(task) },
                onLongPress = { onLongPress(task) }
            )
            if (index != tasks.lastIndex) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = 72.dp, end = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            }
        }
    }
}

@Composable
private fun NowMarker(nowMillis: Long) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "现在 ${TimeUtils.formatClock(nowMillis)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(10.dp))
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TaskRow(
    task: Task,
    highlighted: Boolean,
    planTitles: Map<Long, String>,
    onClick: () -> Unit,
    onToggle: () -> Unit,
    onRestore: () -> Unit,
    onLongPress: (Task) -> Unit
) {
    val done = task.status == TaskStatus.COMPLETED
    val skipped = task.status == TaskStatus.SKIPPED
    val finished = done || skipped
    val titleColor = priorityTitleColor(task.priority, finished)
    val categoryColor = categoryColor(task.category)
    // 新增待办后短暂高亮，让用户确认它落在时间线的哪个位置。
    val highlightColor by animateColorAsState(
        targetValue = if (highlighted) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        } else {
            Color.Transparent
        },
        animationSpec = tween(420),
        label = "taskHighlight"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(highlightColor)
            .bounceClick(
                onLongClick = if (task.isOpen) {
                    { onLongPress(task) }
                } else {
                    null
                },
                onClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.width(48.dp)) {
            Text(
                text = TimeUtils.formatClock(task.startTime),
                style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                color = titleColor
            )
            Text(
                text = TimeUtils.formatClock(
                    task.startTime.plusMinutes(task.durationMinutes.toLong())
                ),
                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(categoryColor.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(task.category.iconRes()),
                contentDescription = task.displayCategory(),
                tint = categoryColor,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SourceTag(task)
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.Medium,
                        textDecoration = if (finished) TextDecoration.LineThrough else null
                    ),
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                val planName = planTitles[task.planId]
                if (planName != null) {
                    Spacer(Modifier.width(6.dp))
                    PlanNameLabel(planName)
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = taskMeta(task),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        TaskStatusToggle(
            status = task.status,
            onToggle = onToggle,
            onRestore = onRestore
        )
    }
}

/**
 * 状态勾选按钮：单击在完成 / 跳过之间切换，长按恢复为空。
 * 列表与宫格共用，保证图标、底色与交互完全一致。
 */
@Composable
private fun TaskStatusToggle(
    status: TaskStatus,
    onToggle: () -> Unit,
    onRestore: () -> Unit
) {
    val done = status == TaskStatus.COMPLETED
    val skipped = status == TaskStatus.SKIPPED
    // 热区 40dp，视觉仍是 30dp。
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .combinedClickable(
                onClickLabel = when {
                    done -> "标为未完成"
                    skipped -> "标为完成"
                    else -> "完成"
                },
                onLongClickLabel = "恢复为空",
                onLongClick = onRestore,
                onClick = onToggle
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .then(
                    when {
                        done -> Modifier.background(
                            MaterialTheme.colorScheme.secondaryContainer
                        )
                        skipped -> Modifier.border(
                            width = 1.5.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                            shape = CircleShape
                        )
                        else -> Modifier.border(
                            width = 1.5.dp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                            shape = CircleShape
                        )
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            when {
                done -> AnimatedCheck(
                    checked = true,
                    size = 18.dp,
                    color = MaterialTheme.colorScheme.secondary
                )
                skipped -> Icon(
                    painter = painterResource(R.drawable.ic_ui_close),
                    contentDescription = "标为完成",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
                else -> Icon(
                    painter = painterResource(R.drawable.ic_ui_check),
                    contentDescription = "完成",
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

private fun taskMeta(task: Task): String {
    val parts = mutableListOf<String>()
    parts += task.displayCategory()
    parts += TimeUtils.formatDuration(task.durationMinutes)
    if (task.prepareMinutes > 0) parts += "提前 ${task.prepareMinutes} 分钟"
    if (task.steps.isNotEmpty()) parts += "${task.steps.size} 个步骤"
    if (task.priority == com.capsulepal.app.data.model.Priority.HIGH) parts += "重要"
    return parts.joinToString(" · ")
}

@Composable
private fun categoryColor(category: TaskCategory): Color = when (category) {
    TaskCategory.CUSTOM -> Coral
    TaskCategory.ROUTINE -> Mint
    TaskCategory.OUTING -> Butter
    TaskCategory.FOOD -> Coral
    TaskCategory.WATER -> Mint
    TaskCategory.PAYMENT -> Lavender
    TaskCategory.PET -> Butter
    TaskCategory.FOCUS -> CoralDeep
    TaskCategory.MEETING -> Lavender
    TaskCategory.MEDICATION -> Mint
    TaskCategory.EXERCISE -> CoralDeep
    TaskCategory.REST -> Lavender
    TaskCategory.PICKUP -> Butter
    TaskCategory.SOCIAL -> Coral
    TaskCategory.SHOPPING -> Mint
    TaskCategory.CHORES -> Butter
    TaskCategory.OTHER -> Lavender
}

@Composable
private fun rememberNow(intervalMillis: Long): Long {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(intervalMillis) {
        while (true) {
            delay(intervalMillis)
            now = System.currentTimeMillis()
        }
    }
    return now
}

private enum class FilterSection { RANGE, GROUP, CATEGORY, PRIORITY, PLAN }

private data class FilterOption(
    val label: String,
    val selected: Boolean,
    val onClick: () -> Unit
)

/** 待办页筛选条：每个类别一个按钮，点开后在弹窗里按行选择；最右侧切换列表/宫格。 */
@Composable
private fun TodoFilterBar(
    todo: TodoUiState,
    categories: List<CategoryEntry>,
    onRange: (TodoRange) -> Unit,
    onCustomDays: (Int) -> Unit,
    onGroup: (Long) -> Unit,
    onCategory: (TaskCategory?) -> Unit,
    onPriority: (Priority?) -> Unit,
    onPlan: (Long?) -> Unit,
    onLayout: (TodoLayout) -> Unit,
    onManageGroups: () -> Unit
) {
    var openSection by remember { mutableStateOf<FilterSection?>(null) }
    var showDays by remember { mutableStateOf(false) }

    val rangeLabel = when (todo.range) {
        TodoRange.TODAY -> "今天"
        TodoRange.TOMORROW -> "明天"
        TodoRange.THREE_DAYS -> "近3天"
        TodoRange.CUSTOM -> "近${todo.customDays}天"
    }
    val groupLabel = when (todo.groupFilter) {
        -1L -> "分组"
        0L -> "未分组"
        else -> todo.groups.firstOrNull { it.id == todo.groupFilter }?.name ?: "分组"
    }
    val categoryLabel = todo.categoryFilter?.label ?: "分类"
    val priorityLabel = todo.priorityFilter?.label ?: "重要程度"
    val planLabel = todo.plans.firstOrNull { it.id == todo.planFilter }?.title ?: "计划"

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterButton(rangeLabel, todo.range != TodoRange.TODAY) {
                openSection = FilterSection.RANGE
            }
            FilterButton(groupLabel, todo.groupFilter != -1L) {
                openSection = FilterSection.GROUP
            }
            FilterButton(categoryLabel, todo.categoryFilter != null) {
                openSection = FilterSection.CATEGORY
            }
            FilterButton(priorityLabel, todo.priorityFilter != null) {
                openSection = FilterSection.PRIORITY
            }
            FilterButton(planLabel, todo.planFilter != null) {
                openSection = FilterSection.PLAN
            }
        }
        ViewModeToggle(todo.layout) {
            onLayout(if (todo.layout == TodoLayout.LIST) TodoLayout.GRID else TodoLayout.LIST)
        }
    }

    when (openSection) {
        FilterSection.RANGE -> FilterDialog(
            title = "时间",
            options = listOf(
                FilterOption("今天", todo.range == TodoRange.TODAY) {
                    onRange(TodoRange.TODAY)
                    openSection = null
                },
                FilterOption("明天", todo.range == TodoRange.TOMORROW) {
                    onRange(TodoRange.TOMORROW)
                    openSection = null
                },
                FilterOption("近3天", todo.range == TodoRange.THREE_DAYS) {
                    onRange(TodoRange.THREE_DAYS)
                    openSection = null
                },
                FilterOption(
                    label = "自定义…（当前 ${todo.customDays} 天）",
                    selected = todo.range == TodoRange.CUSTOM
                ) {
                    openSection = null
                    showDays = true
                }
            ),
            onDismiss = { openSection = null }
        )

        FilterSection.GROUP -> FilterDialog(
            title = "分组",
            options = buildList {
                add(
                    FilterOption("全部分组", todo.groupFilter == -1L) {
                        onGroup(-1L)
                        openSection = null
                    }
                )
                add(
                    FilterOption("未分组", todo.groupFilter == 0L) {
                        onGroup(0L)
                        openSection = null
                    }
                )
                todo.groups.forEach { group ->
                    add(
                        FilterOption(group.name, todo.groupFilter == group.id) {
                            onGroup(group.id)
                            openSection = null
                        }
                    )
                }
                add(
                    FilterOption("管理分组…", selected = false) {
                        openSection = null
                        onManageGroups()
                    }
                )
            },
            onDismiss = { openSection = null }
        )

        FilterSection.CATEGORY -> {
            val options = buildList {
                add(
                    FilterOption("全部分类", todo.categoryFilter == null) {
                        onCategory(null)
                        openSection = null
                    }
                )
                val builtIns = categories.mapNotNull { (it as? CategoryEntry.BuiltIn)?.category }
                val hasCustom = categories.any { it is CategoryEntry.Custom }
                val list = if (hasCustom) builtIns + TaskCategory.CUSTOM else builtIns
                list.distinct().forEach { category ->
                    add(
                        FilterOption(category.label, todo.categoryFilter == category) {
                            onCategory(category)
                            openSection = null
                        }
                    )
                }
            }
            FilterDialog(
                title = "分类",
                options = options,
                onDismiss = { openSection = null }
            )
        }

        FilterSection.PRIORITY -> FilterDialog(
            title = "重要程度",
            options = buildList {
                add(
                    FilterOption("全部重要程度", todo.priorityFilter == null) {
                        onPriority(null)
                        openSection = null
                    }
                )
                Priority.entries.forEach { priority ->
                    add(
                        FilterOption(priority.label, todo.priorityFilter == priority) {
                            onPriority(priority)
                            openSection = null
                        }
                    )
                }
            },
            onDismiss = { openSection = null }
        )

        FilterSection.PLAN -> FilterDialog(
            title = "计划",
            options = buildList {
                add(
                    FilterOption("全部计划", todo.planFilter == null) {
                        onPlan(null)
                        openSection = null
                    }
                )
                todo.plans.forEach { plan ->
                    add(
                        FilterOption(plan.title, todo.planFilter == plan.id) {
                            onPlan(plan.id)
                            openSection = null
                        }
                    )
                }
            },
            onDismiss = { openSection = null }
        )

        null -> Unit
    }

    if (showDays) {
        CustomDaysDialog(
            initial = todo.customDays,
            onDismiss = { showDays = false },
            onConfirm = { days ->
                onCustomDays(days)
                onRange(TodoRange.CUSTOM)
                showDays = false
            }
        )
    }
}

@Composable
private fun FilterButton(
    text: String,
    active: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = CircleShape,
        color = if (active) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surface
        },
        border = BorderStroke(
            1.dp,
            if (active) Color.Transparent else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier.bounceClick(shape = CircleShape) { onClick() }
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (active) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
        )
    }
}

@Composable
private fun FilterDialog(
    title: String,
    options: List<FilterOption>,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                options.forEachIndexed { index, option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .bounceClick(shape = RoundedCornerShape(10.dp)) { option.onClick() }
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = option.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (option.selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (option.selected) {
                            Icon(
                                painter = painterResource(R.drawable.ic_ui_check),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    if (index != options.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

/** 列表/宫格切换：自绘图标，显示的是当前视图状态。 */
@Composable
private fun ViewModeToggle(layout: TodoLayout, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .bounceClick(shape = CircleShape) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        val iconColor = MaterialTheme.colorScheme.onSurface
        Canvas(modifier = Modifier.size(17.dp)) {
            val stroke = 1.6.dp.toPx()
            if (layout == TodoLayout.LIST) {
                val rowHeight = size.height / 3f
                for (row in 0..2) {
                    val centerY = rowHeight * row + rowHeight / 2f
                    drawCircle(
                        color = iconColor,
                        radius = stroke * 0.85f,
                        center = Offset(stroke, centerY)
                    )
                    drawLine(
                        color = iconColor,
                        start = Offset(stroke * 4f, centerY),
                        end = Offset(size.width, centerY),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round
                    )
                }
            } else {
                val gap = 2.2.dp.toPx()
                val cell = (size.width - gap) / 2f
                val radius = 2.dp.toPx()
                for (row in 0..1) {
                    for (col in 0..1) {
                        drawRoundRect(
                            color = iconColor,
                            topLeft = Offset(col * (cell + gap), row * (cell + gap)),
                            size = Size(cell, cell),
                            cornerRadius = CornerRadius(radius)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomDaysDialog(
    initial: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var text by remember { mutableStateOf(initial.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自定义范围") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "从今天起查看未来多少天的待办（1–30 天）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { input ->
                        text = input.filter { it.isDigit() }.take(2)
                    },
                    singleLine = true,
                    label = { Text("天数") }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val days = text.toIntOrNull()?.coerceIn(1, 30) ?: initial
                    onConfirm(days)
                }
            ) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun TodoDayHeader(date: LocalDate, today: LocalDate) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = TimeUtils.humanDate(date, today),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = TimeUtils.fullDate(date),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TodoGridRow(
    tasks: List<Task>,
    planTitles: Map<Long, String>,
    onEdit: (Task) -> Unit,
    onToggle: (Task) -> Unit,
    onRestore: (Task) -> Unit,
    onLongPress: (Task) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        tasks.forEach { task ->
            TaskGridCard(
                task = task,
                planTitles = planTitles,
                modifier = Modifier.weight(1f),
                onEdit = { onEdit(task) },
                onToggle = { onToggle(task) },
                onRestore = { onRestore(task) },
                onLongPress = { onLongPress(task) }
            )
        }
        if (tasks.size == 1) Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun TaskGridCard(
    task: Task,
    planTitles: Map<Long, String>,
    modifier: Modifier = Modifier,
    onEdit: () -> Unit,
    onToggle: () -> Unit,
    onRestore: () -> Unit,
    onLongPress: () -> Unit
) {
    val done = task.status == TaskStatus.COMPLETED
    val skipped = task.status == TaskStatus.SKIPPED
    val finished = done || skipped
    val categoryColor = categoryColor(task.category)
    val endTime = task.startTime.plusMinutes(task.durationMinutes.toLong())
    SoftCard(
        modifier = modifier.bounceClick(
            shape = RoundedCornerShape(22.dp),
            onLongClick = if (task.isOpen) onLongPress else null
        ) { onEdit() }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(categoryColor.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(task.category.iconRes()),
                        contentDescription = task.category.label,
                        tint = if (finished) MaterialTheme.colorScheme.onSurfaceVariant else categoryColor,
                        modifier = Modifier.size(15.dp)
                    )
                }
                Spacer(Modifier.width(6.dp))
                SourceTag(task)
                // 计划名跟在分类图标右侧，只占剩余空间，时间与标题都不受影响。
                val planName = planTitles[task.planId]
                if (planName != null) {
                    Spacer(Modifier.width(6.dp))
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        PlanNameLabel(name = planName, maxWidth = null)
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                }
                TaskStatusToggle(
                    status = task.status,
                    onToggle = onToggle,
                    onRestore = onRestore
                )
            }
            Text(
                text = task.title,
                style = MaterialTheme.typography.bodyMedium,
                color = priorityTitleColor(task.priority, finished),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textDecoration = if (done) TextDecoration.LineThrough else null
            )
            Text(
                text = "${TimeUtils.formatClock(task.startTime)}–${TimeUtils.formatClock(endTime)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Text(
                text = if (task.prepareMinutes > 0) {
                    "提前 ${task.prepareMinutes} 分钟准备"
                } else {
                    "无需提前准备"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                maxLines = 1
            )
        }
    }
}

/** 待办名称后的计划名称，过长时省略；宫格里空间更窄，单独收窄。 */
@Composable
private fun PlanNameLabel(
    name: String,
    modifier: Modifier = Modifier,
    maxWidth: Dp? = 96.dp
) {
    Text(
        text = name,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .then(if (maxWidth != null) Modifier.widthIn(max = maxWidth) else Modifier)
            .clip(RoundedCornerShape(6.dp))
            .background(Lavender.copy(alpha = 0.22f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

/**
 * 重要程度语义色：固定红/绿，不跟随各版主题换色，保证任何版本下三档都能区分。
 */
@Composable
private fun priorityTitleColor(priority: Priority, finished: Boolean): Color {
    if (finished) return MaterialTheme.colorScheme.onSurfaceVariant
    val dark = isSystemInDarkTheme()
    return when (priority) {
        Priority.HIGH -> if (dark) Color(0xFFFF9080) else Color(0xFFD6503F)
        Priority.LOW -> if (dark) Color(0xFF74D2AA) else Color(0xFF2F8F68)
        Priority.NORMAL -> MaterialTheme.colorScheme.onSurface
    }
}

/**
 * 三类待办共用一个时间线，用来源标签明确区分：
 * 日程待办、普通待办（普通待办不显示标签）；计划名称直接跟在标题后面。
 */
@Composable
private fun SourceTag(task: Task) {
    val label: String
    val background: Color
    val content: Color
    when {
        task.kind == TaskKind.SCHEDULE -> {
            label = "日程"
            background = Coral.copy(alpha = 0.16f)
            content = CoralDeep
        }
        else -> return
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = content,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
    Spacer(Modifier.width(6.dp))
}

@Composable
private fun GroupManagerDialog(
    groups: List<TaskGroup>,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
    onRename: (Long, String) -> Unit,
    onDelete: (Long) -> Unit,
    onMove: (Long, Int) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<TaskGroup?>(null) }
    var renameText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("分组管理") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (groups.isEmpty()) {
                    Text(
                        text = "还没有分组，新建一个用来归类待办。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                groups.forEachIndexed { index, group ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = group.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { onMove(group.id, -1) }, enabled = index > 0) {
                            Text("上移")
                        }
                        TextButton(onClick = { onMove(group.id, 1) }, enabled = index < groups.lastIndex) {
                            Text("下移")
                        }
                        TextButton(onClick = {
                            renameTarget = group
                            renameText = group.name
                        }) { Text("改名") }
                        TextButton(onClick = { onDelete(group.id) }) { Text("删除") }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(12) },
                        singleLine = true,
                        label = { Text("新分组") },
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = {
                            onCreate(name)
                            name = ""
                        },
                        enabled = name.isNotBlank()
                    ) { Text("添加") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )

    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名分组") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it.take(12) },
                    singleLine = true,
                    label = { Text("名称") }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRename(target.id, renameText)
                        renameTarget = null
                    },
                    enabled = renameText.isNotBlank()
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("取消") }
            }
        )
    }
}
