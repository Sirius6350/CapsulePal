package com.capsulepal.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import com.capsulepal.app.R
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.capsulepal.app.data.model.Plan
import com.capsulepal.app.data.model.PlanColor
import com.capsulepal.app.data.model.PlanType
import com.capsulepal.app.data.model.RepeatRule
import com.capsulepal.app.data.model.Task
import com.capsulepal.app.data.model.TaskStatus
import com.capsulepal.app.data.model.displayCategory
import com.capsulepal.app.data.model.iconRes
import com.capsulepal.app.domain.PlanProgress
import com.capsulepal.app.domain.RepeatPlanStats
import com.capsulepal.app.ui.PlanCard
import com.capsulepal.app.ui.PlansUiState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.capsulepal.app.ui.CapsuleViewModel
import com.capsulepal.app.ui.components.CapsuleButton
import com.capsulepal.app.ui.components.CapsuleProgressBar
import com.capsulepal.app.ui.components.ChoiceChip
import com.capsulepal.app.ui.components.Pill
import com.capsulepal.app.ui.components.SoftCard
import com.capsulepal.app.ui.components.bounceClick
import com.capsulepal.app.ui.theme.Coral
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

private const val MILLIS_PER_DAY = 86_400_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlansScreen(
    state: PlansUiState?,
    vm: CapsuleViewModel,
    onSavePlan: (Plan) -> Unit,
    onArchivePlan: (Plan, Boolean) -> Unit,
    onDeletePlan: (Plan) -> Unit,
    onAddTask: (Plan) -> Unit,
    onQuickAddTask: (Plan, String) -> Unit,
    onEditTask: (Task) -> Unit,
    onToggleTask: (Task) -> Unit,
    onDeleteSeries: (Long) -> Unit
) {
    var selectedId by rememberSaveable { mutableStateOf(0L) }
    var editorOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Plan?>(null) }
    var deleteTarget by remember { mutableStateOf<Plan?>(null) }

    val selected = state?.plans?.firstOrNull { it.plan.id == selectedId }
    BackHandler(enabled = selected != null) { selectedId = 0L }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        if (state == null) {
            Box(modifier = Modifier.fillMaxSize())
        } else if (selected == null) {
            PlanList(
                state = state,
                onOpen = { selectedId = it.plan.id },
                onNew = {
                    editing = null
                    editorOpen = true
                }
            )
        } else {
            PlanDetail(
                card = selected,
                vm = vm,
                onBack = { selectedId = 0L },
                onEdit = {
                    editing = selected.plan
                    editorOpen = true
                },
                onArchive = { onArchivePlan(selected.plan, !selected.plan.archived) },
                onDelete = { deleteTarget = selected.plan },
                onAddTask = { onAddTask(selected.plan) },
                onQuickAddTask = { title -> onQuickAddTask(selected.plan, title) },
                onEditTask = onEditTask,
                onToggleTask = onToggleTask,
                onDeleteSeries = onDeleteSeries
            )
        }
    }

    if (editorOpen) {
        PlanEditorDialog(
            initial = editing,
            onDismiss = { editorOpen = false },
            onSave = { plan ->
                onSavePlan(plan)
                editorOpen = false
            }
        )
    }

    deleteTarget?.let { plan ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除计划") },
            text = { Text("「${plan.title}」删除后，计划内的待办会保留，只解除关联。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeletePlan(plan)
                        deleteTarget = null
                        selectedId = 0L
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun PlanList(
    state: PlansUiState,
    onOpen: (PlanCard) -> Unit,
    onNew: () -> Unit
) {
    var showArchived by rememberSaveable { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 116.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column {
                Text(
                    text = "计划",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "把大目标拆成一件件能勾掉的待办",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                CapsuleButton(
                    text = "新建计划",
                    onClick = onNew,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        if (state.active.isEmpty()) {
            item {
                SoftCard {
                    Text(
                        text = "还没有进行中的计划",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "新建一个计划，再把待办放进去；每完成一条，计划的进度就会往前走一点。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(state.active, key = { it.plan.id }) { card ->
                PlanSummaryCard(card = card, onClick = { onOpen(card) })
            }
        }

        if (state.archived.isNotEmpty()) {
            item {
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "已归档 ${state.archived.size}",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { showArchived = !showArchived }) {
                        Text(if (showArchived) "收起" else "展开")
                    }
                }
            }
            if (showArchived) {
                items(state.archived, key = { it.plan.id }) { card ->
                    PlanSummaryCard(card = card, onClick = { onOpen(card) }, dimmed = true)
                }
            }
        }
    }
}

@Composable
private fun PlanSummaryCard(
    card: PlanCard,
    onClick: () -> Unit,
    dimmed: Boolean = false
) {
    val accent = Color(card.plan.color.argb)
    SoftCard(modifier = Modifier.bounceClick(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(38.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (dimmed) accent.copy(alpha = 0.4f) else accent)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = card.plan.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (card.plan.note.isNotBlank()) {
                    Text(
                        text = card.plan.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (card.plan.isPeriod) {
                card.plan.targetDate?.let { date ->
                    Pill(
                        text = targetLabel(date),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Pill(
                    text = "重复计划",
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        if (card.plan.isPeriod) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CapsuleProgressBar(
                    progress = card.progress.fraction,
                    modifier = Modifier.weight(1f),
                    color = accent,
                    height = 7.dp
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "${card.progress.percent}%",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(Modifier.height(5.dp))
            Text(
                text = progressLine(card.progress),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(
                text = repeatLine(card.stats),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "不设百分比，完成一次记一次",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PlanDetail(
    card: PlanCard,
    vm: CapsuleViewModel,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onAddTask: () -> Unit,
    onQuickAddTask: (String) -> Unit,
    onEditTask: (Task) -> Unit,
    onToggleTask: (Task) -> Unit,
    onDeleteSeries: (Long) -> Unit
) {
    val accent = Color(card.plan.color.argb)
    var quickTitle by rememberSaveable { mutableStateOf("") }
    var groupMode by rememberSaveable(card.plan.id) { mutableStateOf(PlanGroupMode.DATE) }
    val expandedSeries = remember(card.plan.id) { mutableStateMapOf<Long, Boolean>() }
    val tasks by remember(card.plan.id) { vm.tasksForPlan(card.plan.id) }
        .collectAsStateWithLifecycle(emptyList())
    var pendingSeriesDelete by remember(card.plan.id) { mutableStateOf<List<Task>?>(null) }
    Box(modifier = Modifier.fillMaxSize()) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column {
                TextButton(
                    onClick = onBack,
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "← 全部计划",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .width(5.dp)
                            .height(42.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(accent)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = card.plan.title,
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        if (card.plan.note.isNotBlank()) {
                            Text(
                                text = card.plan.note,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = planHeaderLine(card.plan) +
                        if (card.plan.archived) " · 已归档" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            SoftCard {
                if (card.plan.isPeriod) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = "${card.progress.percent}%",
                            style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = progressLine(card.progress),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    CapsuleProgressBar(
                        progress = card.progress.fraction,
                        color = accent,
                        height = 8.dp
                    )
                } else {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = "${card.stats.completed}",
                            style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "次完成",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "本周 ${card.stats.thisWeek} 次 · 连续 ${card.stats.streak} 天",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onEdit) { Text("编辑") }
                TextButton(onClick = onArchive) {
                    Text(if (card.plan.archived) "恢复" else "归档")
                }
                TextButton(onClick = onDelete) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (tasks.isEmpty()) "计划内待办" else "计划内待办 · ${tasks.size} 条",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onAddTask) { Text("+ 添加待办") }
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = quickTitle,
                    onValueChange = { quickTitle = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("快速加一条，自动归入本计划") },
                    textStyle = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.width(8.dp))
                CapsuleButton(
                    text = "添加",
                    onClick = {
                        val title = quickTitle.trim()
                        if (title.isNotEmpty()) {
                            onQuickAddTask(title)
                            quickTitle = ""
                        }
                    },
                    enabled = quickTitle.isNotBlank(),
                    modifier = Modifier.width(84.dp),
                    height = 48.dp
                )
            }
        }

        if (tasks.isEmpty()) {
            item {
                SoftCard {
                    Text(
                        text = "计划里还没有待办",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (card.plan.isPeriod) {
                            "添加后，完成这些待办就会推进上面的进度。"
                        } else {
                            "添加后，每完成一次就会累计一次完成次数。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceChip(
                        text = "按日期",
                        selected = groupMode == PlanGroupMode.DATE,
                        onClick = { groupMode = PlanGroupMode.DATE }
                    )
                    ChoiceChip(
                        text = "按系列",
                        selected = groupMode == PlanGroupMode.SERIES,
                        onClick = { groupMode = PlanGroupMode.SERIES }
                    )
                }
            }

            if (groupMode == PlanGroupMode.DATE) {
                val today = LocalDate.now()
                tasks.groupBy { it.date }.toSortedMap().forEach { (date, dayTasks) ->
                    item(key = "day-$date") {
                        PlanDateHeader(date = date, today = today, count = dayTasks.size)
                    }
                    items(dayTasks.sortedBy { it.startTime }, key = { "t-${it.id}" }) { task ->
                        PlanTaskRow(
                            task = task,
                            accent = accent,
                            onToggle = { onToggleTask(task) },
                            onClick = { onEditTask(task) }
                        )
                    }
                }
            } else {
                val series = tasks.filter { it.seriesId != 0L }
                    .groupBy { it.seriesId }
                    .values
                    .map { rows -> rows.sortedBy { it.date } }
                    .sortedBy { rows ->
                        rows.firstOrNull { it.isOpen }?.date ?: LocalDate.MAX
                    }
                val singles = tasks.filter { it.seriesId == 0L }.sortedBy { it.date }

                if (series.isNotEmpty()) {
                    item(key = "series-hint") {
                        Text(
                            text = "长按某个系列，可以一次删除整组待办",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                series.forEach { rows ->
                    val seriesId = rows.first().seriesId
                    val expanded = expandedSeries[seriesId] == true
                    item(key = "series-$seriesId") {
                        PlanSeriesRow(
                            rows = rows,
                            expanded = expanded,
                            accent = accent,
                            onExpand = { expandedSeries[seriesId] = !expanded },
                            onLongPress = { pendingSeriesDelete = rows }
                        )
                    }
                    if (expanded) {
                        items(rows, key = { "st-${it.id}" }) { task ->
                            PlanTaskRow(
                                task = task,
                                accent = accent,
                                onToggle = { onToggleTask(task) },
                                onClick = { onEditTask(task) }
                            )
                        }
                    }
                }

                if (singles.isNotEmpty()) {
                    item(key = "singles") {
                        Text(
                            text = "单独待办 · ${singles.size} 条",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                    items(singles, key = { "o-${it.id}" }) { task ->
                        PlanTaskRow(
                            task = task,
                            accent = accent,
                            onToggle = { onToggleTask(task) },
                            onClick = { onEditTask(task) }
                        )
                    }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
    }

    pendingSeriesDelete?.let { rows ->
        AlertDialog(
            onDismissRequest = { pendingSeriesDelete = null },
            title = { Text("删除整个系列？") },
            text = {
                Text(
                    "「${rows.first().title}」这个系列共有 ${rows.size} 个实例（含已完成）。" +
                        "删除后不可恢复，之后也不会再自动生成。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteSeries(rows.first().seriesId)
                        pendingSeriesDelete = null
                    }
                ) {
                    Text("全部删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingSeriesDelete = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun PlanTaskRow(
    task: Task,
    accent: Color,
    onToggle: () -> Unit,
    onClick: () -> Unit
) {
    val finished = !task.isOpen
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .bounceClick(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .then(
                    if (finished) {
                        Modifier.background(
                            if (task.status == TaskStatus.COMPLETED) accent else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                        )
                    } else {
                        Modifier.border(
                            width = 1.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                            shape = CircleShape
                        )
                    }
                )
                .bounceClick(onClick = onToggle),
            contentAlignment = Alignment.Center
        ) {
            when (task.status) {
                TaskStatus.COMPLETED -> Icon(
                    painter = painterResource(R.drawable.ic_ui_check),
                    contentDescription = "完成",
                    tint = Color.White,
                    modifier = Modifier.size(15.dp)
                )
                TaskStatus.SKIPPED -> Text(
                    text = "–",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> Unit
            }
        }
        Spacer(Modifier.width(12.dp))
        Icon(
            painter = painterResource(task.category.iconRes()),
            contentDescription = null,
            tint = if (finished) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.primary
            },
            modifier = Modifier.size(17.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = task.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textDecoration = if (task.status == TaskStatus.COMPLETED) {
                    TextDecoration.LineThrough
                } else {
                    TextDecoration.None
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${task.date.monthValue}/${task.date.dayOfMonth} · " +
                    task.displayCategory() +
                    when (task.status) {
                        TaskStatus.COMPLETED -> " · 已完成"
                        TaskStatus.SKIPPED -> " · 已跳过"
                        else -> ""
                    },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = String.format("%02d:%02d", task.startTime.hour, task.startTime.minute),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private enum class PlanGroupMode { DATE, SERIES }

@Composable
private fun PlanDateHeader(date: LocalDate, today: LocalDate, count: Int) {
    val label = when (date) {
        today -> "今天"
        today.plusDays(1) -> "明天"
        today.minusDays(1) -> "昨天"
        else -> "${date.monthValue} 月 ${date.dayOfMonth} 日"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "${date.monthValue}/${date.dayOfMonth} · 周${weekdayName(date.dayOfWeek)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = "$count 条",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 按系列折叠的一行：显示规则、完成数与下一次日期，点开再看每个实例。 */
@Composable
private fun PlanSeriesRow(
    rows: List<Task>,
    expanded: Boolean,
    accent: Color,
    onExpand: () -> Unit,
    onLongPress: () -> Unit
) {
    val head = rows.first()
    val done = rows.count { it.status == TaskStatus.COMPLETED }
    val skipped = rows.count { it.status == TaskStatus.SKIPPED }
    val next = rows.firstOrNull { it.isOpen }?.date
    val rule = when (head.repeat) {
        RepeatRule.DAILY -> "每天"
        RepeatRule.WEEKLY -> "每周"
        RepeatRule.CONSECUTIVE -> "连续 ${head.repeatCount} 天"
        RepeatRule.ONCE -> "重复"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(accent.copy(alpha = 0.10f))
            .bounceClick(onClick = onExpand, onLongClick = onLongPress)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(head.category.iconRes()),
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = head.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = buildString {
                    append(rule)
                    append(" · 完成 $done/${rows.size}")
                    if (skipped > 0) append(" · 跳过 $skipped")
                    next?.let { append(" · 下次 ${it.monthValue}/${it.dayOfMonth}") }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = if (expanded) "收起" else "展开",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun weekdayName(day: DayOfWeek): String = when (day) {
    DayOfWeek.MONDAY -> "一"
    DayOfWeek.TUESDAY -> "二"
    DayOfWeek.WEDNESDAY -> "三"
    DayOfWeek.THURSDAY -> "四"
    DayOfWeek.FRIDAY -> "五"
    DayOfWeek.SATURDAY -> "六"
    DayOfWeek.SUNDAY -> "日"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlanEditorDialog(
    initial: Plan?,
    onDismiss: () -> Unit,
    onSave: (Plan) -> Unit
) {
    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var note by remember { mutableStateOf(initial?.note.orEmpty()) }
    var color by remember { mutableStateOf(initial?.color ?: PlanColor.CORAL) }
    var type by remember { mutableStateOf(initial?.type ?: PlanType.REPEAT) }
    var startDate by remember { mutableStateOf(initial?.startDate ?: LocalDate.now()) }
    var endDate by remember { mutableStateOf(initial?.targetDate) }
    var picking by remember { mutableStateOf<PlanDateTarget?>(null) }

    val periodInvalid = type == PlanType.PERIOD &&
        (endDate == null || endDate!!.isBefore(startDate))
    val canSave = title.isNotBlank() && !periodInvalid

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新建计划" else "编辑计划") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("计划名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注（可选）") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "计划类型",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlanType.entries.forEach { option ->
                        ChoiceChip(
                            text = option.label,
                            selected = type == option,
                            onClick = { type = option }
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (type == PlanType.REPEAT) {
                        "不设终点，进度按完成次数累计。"
                    } else {
                        "必须设置开始与结束日期；计划内的重复待办会在结束日截止。"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(14.dp))
                if (type == PlanType.PERIOD) {
                    PlanDateRow(
                        label = "开始日期",
                        date = startDate,
                        onPick = { picking = PlanDateTarget.START }
                    )
                    Spacer(Modifier.height(8.dp))
                    PlanDateRow(
                        label = "结束日期（必填）",
                        date = endDate,
                        onPick = { picking = PlanDateTarget.END },
                        onClear = { endDate = null }
                    )
                    if (periodInvalid) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "结束日期不能为空，也不能早于开始日期",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                } else {
                    PlanDateRow(
                        label = "目标日期（可选）",
                        date = endDate,
                        onPick = { picking = PlanDateTarget.END },
                        onClear = { endDate = null }
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "颜色",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PlanColor.entries.forEach { item ->
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(Color(item.argb))
                                .bounceClick { color = item },
                            contentAlignment = Alignment.Center
                        ) {
                            if (color == item) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_ui_check),
                                    contentDescription = item.label,
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    onSave(
                        Plan(
                            id = initial?.id ?: 0L,
                            title = title.trim(),
                            note = note.trim(),
                            color = color,
                            type = type,
                            startDate = if (type == PlanType.PERIOD) startDate else null,
                            targetDate = endDate,
                            createdAtEpochDay = initial?.createdAtEpochDay
                                ?: LocalDate.now().toEpochDay(),
                            archived = initial?.archived ?: false
                        )
                    )
                }
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )

    picking?.let { target ->
        val current = if (target == PlanDateTarget.START) startDate else endDate
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = (current ?: LocalDate.now()).toEpochDay() * MILLIS_PER_DAY
        )
        DatePickerDialog(
            onDismissRequest = { picking = null },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { millis ->
                            val picked = LocalDate.ofEpochDay(millis / MILLIS_PER_DAY)
                            if (target == PlanDateTarget.START) startDate = picked else endDate = picked
                        }
                        picking = null
                    }
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { picking = null }) { Text("取消") }
            }
        ) {
            DatePicker(
                state = pickerState,
                title = {
                    Text(
                        text = if (target == PlanDateTarget.START) "开始日期" else "结束日期",
                        modifier = Modifier.padding(16.dp)
                    )
                }
            )
        }
    }
}

private enum class PlanDateTarget { START, END }

@Composable
private fun PlanDateRow(
    label: String,
    date: LocalDate?,
    onPick: () -> Unit,
    onClear: (() -> Unit)? = null
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = date?.let { "${it.year} 年 ${it.monthValue} 月 ${it.dayOfMonth} 日" }
                    ?: "未设置",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        TextButton(onClick = onPick) {
            Text(if (date == null) "选择" else "更改")
        }
        if (date != null && onClear != null) {
            TextButton(onClick = onClear) { Text("清除") }
        }
    }
}

private fun progressLine(progress: PlanProgress): String = buildString {
    append("完成 ${progress.completed} / ${progress.total} 条")
    if (progress.skipped > 0) append(" · 跳过 ${progress.skipped}")
    if (progress.open > 0) append(" · 未完成 ${progress.open}")
}

private fun repeatLine(stats: RepeatPlanStats): String =
    "已完成 ${stats.completed} 次 · 本周 ${stats.thisWeek} 次 · 连续 ${stats.streak} 天"

private fun targetLabel(date: LocalDate): String {
    val days = ChronoUnit.DAYS.between(LocalDate.now(), date)
    return when {
        days > 0 -> "${date.monthValue}/${date.dayOfMonth} · 剩 $days 天"
        days == 0L -> "今天到期"
        else -> "${date.monthValue}/${date.dayOfMonth} · 已过期"
    }
}

private fun planHeaderLine(plan: Plan): String {
    if (!plan.isPeriod) {
        val target = plan.targetDate
        val tail = target?.let { " · 目标 ${it.monthValue}/${it.dayOfMonth}" } ?: " · 不设终点"
        return "重复计划 · 按完成次数记录$tail"
    }
    val start = plan.startDate
    val end = plan.targetDate
    val startText = start?.let { "${it.monthValue}/${it.dayOfMonth} 起" } ?: "未设开始日期"
    if (end == null) return "周期计划 · $startText · 未设结束日期"
    val remain = ChronoUnit.DAYS.between(LocalDate.now(), end)
    val endText = when {
        remain > 0 -> "${end.monthValue}/${end.dayOfMonth} 结束 · 剩 $remain 天"
        remain == 0L -> "${end.monthValue}/${end.dayOfMonth} 结束 · 今天到期"
        else -> "${end.monthValue}/${end.dayOfMonth} 结束 · 已过期 ${-remain} 天"
    }
    return "周期计划 · $startText · $endText"
}
