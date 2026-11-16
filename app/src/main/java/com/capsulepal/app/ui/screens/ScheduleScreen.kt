package com.capsulepal.app.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.capsulepal.app.R
import com.capsulepal.app.data.model.RepeatRule
import com.capsulepal.app.data.model.Task
import com.capsulepal.app.data.model.TaskStatus
import com.capsulepal.app.data.model.displayCategory
import com.capsulepal.app.data.model.iconRes
import com.capsulepal.app.domain.LunarProvider
import com.capsulepal.app.domain.TimeUtils
import com.capsulepal.app.ui.ScheduleUiState
import com.capsulepal.app.ui.components.CapsuleButton
import com.capsulepal.app.ui.components.SoftCard
import com.capsulepal.app.ui.components.bounceClick
import com.capsulepal.app.ui.theme.Coral
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.ceil

@Composable
fun ScheduleScreen(
    state: ScheduleUiState,
    onSelectDate: (LocalDate) -> Unit,
    onChangeMonth: (Long) -> Unit,
    onGoToday: () -> Unit,
    onAddTask: (LocalDate) -> Unit,
    onEditTask: (Task) -> Unit,
    onBack: () -> Unit,
    showBack: Boolean = true
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column {
                if (showBack) {
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
                            text = "返回待办",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(
                    text = "日程",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "挑一天，把要做的事放进去",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            SoftCard {
                MonthHeader(
                    month = state.month,
                    onPrev = { onChangeMonth(-1) },
                    onNext = { onChangeMonth(1) },
                    onToday = onGoToday
                )
                Spacer(Modifier.height(10.dp))
                WeekdayHeader()
                Spacer(Modifier.height(4.dp))
                MonthGrid(
                    month = state.month,
                    selected = state.selectedDate,
                    today = state.today,
                    markedDays = state.markedDays,
                    onSelect = onSelectDate
                )
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${state.selectedDate.monthValue} 月 ${state.selectedDate.dayOfMonth} 日 · " +
                            TimeUtils.humanDate(state.selectedDate, state.today),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = buildString {
                            if (state.isRestDay) append("休息日 · ")
                            if (state.isEstimatedHoliday) append("预估 · ")
                            append(LunarProvider.label(state.selectedDate))
                            append(" · ")
                            append(if (state.tasks.isEmpty()) "还没有安排" else "${state.tasks.size} 件事")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Box(
                    modifier = Modifier
                        .height(38.dp)
                        .widthIn(min = 76.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .bounceClick(shape = CircleShape) { onAddTask(state.selectedDate) }
                        .padding(horizontal = 18.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "添加",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimary,
                        maxLines = 1
                    )
                }
            }
        }

        if (state.tasks.isEmpty()) {
            item {
                SoftCard {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_calendar),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(34.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "这一天还很空",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "放一件小事进来，伙伴会按时间提醒你。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(state.tasks, key = { it.id }) { task ->
                ScheduleTaskRow(task = task, onClick = { onEditTask(task) })
            }
        }
    }
}

@Composable
private fun MonthHeader(
    month: YearMonth,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "${month.year} 年 ${month.monthValue} 月",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        NavButton(text = "‹", onClick = onPrev)
        Spacer(Modifier.width(4.dp))
        NavButton(text = "›", onClick = onNext)
        Spacer(Modifier.width(8.dp))
        Text(
            text = "今天",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(CircleShape)
                .bounceClick(onClick = onToday)
                .padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun NavButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .bounceClick(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun WeekdayHeader() {
    val labels = listOf("一", "二", "三", "四", "五", "六", "日")
    Row(modifier = Modifier.fillMaxWidth()) {
        labels.forEach { label ->
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

@Composable
private fun MonthGrid(
    month: YearMonth,
    selected: LocalDate,
    today: LocalDate,
    markedDays: Set<Long>,
    onSelect: (LocalDate) -> Unit
) {
    val firstDay = month.atDay(1)
    val leading = firstDay.dayOfWeek.value - 1
    val daysInMonth = month.lengthOfMonth()
    val rows = ceil((leading + daysInMonth) / 7f).toInt()

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(rows) { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                repeat(7) { column ->
                    val index = row * 7 + column - leading + 1
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        if (index in 1..daysInMonth) {
                            val date = month.atDay(index)
                            DayCell(
                                date = date,
                                selected = date == selected,
                                today = date == today,
                                marked = date.toEpochDay() in markedDays,
                                onClick = { onSelect(date) }
                            )
                        } else {
                            Spacer(Modifier.height(42.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    selected: Boolean,
    today: Boolean,
    marked: Boolean,
    onClick: () -> Unit
) {
    val isWeekend = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY
    val background = when {
        selected -> MaterialTheme.colorScheme.primary
        else -> Color.Transparent
    }
    val textColor = when {
        selected -> MaterialTheme.colorScheme.onPrimary
        today -> MaterialTheme.colorScheme.primary
        isWeekend -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface
    }
    Column(
        modifier = Modifier
            .clip(MaterialTheme.shapes.large)
            .bounceClick(onClick = onClick)
            .padding(vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(background)
                .then(
                    if (today && !selected) {
                        Modifier.background(
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                            CircleShape
                        )
                    } else {
                        Modifier
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = date.dayOfMonth.toString(),
                style = MaterialTheme.typography.bodyMedium,
                color = textColor
            )
        }
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .size(4.dp)
                .clip(CircleShape)
                .background(if (marked) Coral else Color.Transparent)
        )
    }
}

@Composable
private fun ScheduleTaskRow(task: Task, onClick: () -> Unit) {
    val done = task.status == TaskStatus.COMPLETED || task.status == TaskStatus.SKIPPED
    SoftCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // bounceClick 默认按 18dp 圆角裁剪，文字贴着左边缘时会被圆角切掉首字，
                // 这里留出内边距并把裁剪圆角收小，保证时间和时长完整显示。
                .bounceClick(shape = RoundedCornerShape(12.dp), onClick = onClick)
                .padding(start = 8.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 固定 58dp 在放大字号下会把时间和时长裁掉，改成按内容自适应、最少 58dp。
            Column(modifier = Modifier.widthIn(min = 58.dp)) {
                Text(
                    text = TimeUtils.formatClock(task.startTime),
                    style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                    color = if (done) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    softWrap = false
                )
                Text(
                    text = TimeUtils.formatDuration(task.durationMinutes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(task.category.iconRes()),
                        contentDescription = task.displayCategory(),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = task.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textDecoration = if (done) TextDecoration.LineThrough else null
                    )
                }
                if (task.repeat != RepeatRule.ONCE) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = repeatLabel(task),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (done) {
                    Icon(
                        painter = painterResource(R.drawable.ic_ui_check),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

private fun repeatLabel(task: Task): String = when (task.repeat) {
    RepeatRule.ONCE -> ""
    RepeatRule.DAILY -> if (task.skipHolidays) "每天 · 跳过节假日" else "每天"
    RepeatRule.WEEKLY -> {
        val days = task.repeatDays
            .sortedBy { it.value }
            .joinToString("") { dayLabel(it) }
        val base = "每周$days"
        if (task.skipHolidays) "$base · 跳过节假日" else base
    }
    RepeatRule.CONSECUTIVE -> "连续 ${task.repeatCount} 天"
}

private fun dayLabel(day: DayOfWeek): String = when (day) {
    DayOfWeek.MONDAY -> "一"
    DayOfWeek.TUESDAY -> "二"
    DayOfWeek.WEDNESDAY -> "三"
    DayOfWeek.THURSDAY -> "四"
    DayOfWeek.FRIDAY -> "五"
    DayOfWeek.SATURDAY -> "六"
    DayOfWeek.SUNDAY -> "日"
}
