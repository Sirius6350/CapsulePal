package com.capsulepal.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import com.capsulepal.app.data.model.CategoryEntry
import com.capsulepal.app.data.model.RepeatRule
import com.capsulepal.app.data.model.TaskDraft
import com.capsulepal.app.ui.components.CapsuleButton
import com.capsulepal.app.ui.components.ChoiceChip
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * 日程待办的独立创建 / 编辑窗口。
 * 只保留日程真正需要的字段：名称、日期、开始时间、时长、分类、备注。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleEditorSheet(
    initial: TaskDraft,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (TaskDraft) -> Unit,
    onDelete: (Long) -> Unit,
    categoryLayout: List<CategoryEntry>,
    saving: Boolean,
    /** 删除这一天及之后的全部实例（保留历史）。 */
    onDeleteSeries: (Long) -> Unit = onDelete
) {
    var title by rememberSaveable(initial.id) { mutableStateOf(initial.title) }
    var note by rememberSaveable(initial.id) { mutableStateOf(initial.note) }
    var date by rememberSaveable(initial.id) { mutableStateOf(initial.date) }
    var hour by rememberSaveable(initial.id) { mutableIntStateOf(initial.startTime.hour) }
    var minute by rememberSaveable(initial.id) { mutableIntStateOf(initial.startTime.minute) }
    var duration by rememberSaveable(initial.id) {
        mutableIntStateOf(initial.durationMinutes.coerceIn(5, 24 * 60))
    }
    var category by rememberSaveable(initial.id) { mutableStateOf(initial.category) }
    var showDatePicker by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var editingHour by remember { mutableStateOf(false) }
    var editingMinute by remember { mutableStateOf(false) }

    val categories = remember(categoryLayout) {
        categoryLayout.filterIsInstance<CategoryEntry.BuiltIn>().map { it.category }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .heightIn(max = 720.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 22.dp, vertical = 18.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isNew) "新建日程" else "编辑日程",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "日程会同步出现在待办页时间线里",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }

                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(60) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("日程名称") },
                    placeholder = { Text("例如：去取快递") }
                )

                Spacer(Modifier.height(18.dp))
                FieldLabel("日期")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${date.monthValue} 月 ${date.dayOfMonth} 日 · ${weekdayLabel(date)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { showDatePicker = true }) { Text("选择日期") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceChip(
                        text = "今天",
                        selected = date == LocalDate.now(),
                        onClick = { date = LocalDate.now() }
                    )
                    ChoiceChip(
                        text = "明天",
                        selected = date == LocalDate.now().plusDays(1),
                        onClick = { date = LocalDate.now().plusDays(1) }
                    )
                }

                Spacer(Modifier.height(18.dp))
                FieldLabel("开始时间")
                TimeStepper(
                    hour = hour,
                    minute = minute,
                    onChange = { h, m -> hour = h; minute = m },
                    onEditHour = { editingHour = true },
                    onEditMinute = { editingMinute = true }
                )

                Spacer(Modifier.height(18.dp))
                FieldLabel("预计时长")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15, 30, 45, 60, 90, 120).forEach { value ->
                        ChoiceChip(
                            text = if (value < 60) "$value 分" else "${value / 60} 小时",
                            selected = duration == value,
                            onClick = { duration = value }
                        )
                    }
                }

                if (categories.isNotEmpty()) {
                    Spacer(Modifier.height(18.dp))
                    FieldLabel("分类")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        categories.forEach { item ->
                            ChoiceChip(
                                text = item.label,
                                selected = category == item,
                                onClick = { category = item }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                    label = { Text("备注（可选）") }
                )

                Spacer(Modifier.height(22.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!isNew) {
                        TextButton(
                            onClick = { confirmDelete = true },
                            enabled = !saving
                        ) {
                            Text("删除", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    CapsuleButton(
                        text = if (isNew) "加入日程" else "保存",
                        onClick = {
                            onSave(
                                initial.copy(
                                    title = title.trim(),
                                    note = note.trim(),
                                    date = date,
                                    startTime = LocalTime.of(hour, minute).withSecond(0).withNano(0),
                                    durationMinutes = duration,
                                    category = category,
                                    customCategory = if (category == initial.category) {
                                        initial.customCategory
                                    } else {
                                        ""
                                    }
                                )
                            )
                        },
                        enabled = title.isNotBlank() && !saving,
                        height = 46.dp
                    )
                }
            }
        }
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = date.toEpochDay() * 86_400_000L
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { millis ->
                            date = Instant.ofEpochMilli(millis)
                                .atZone(ZoneOffset.UTC)
                                .toLocalDate()
                        }
                        showDatePicker = false
                    }
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("取消") }
            }
        ) {
            DatePicker(state = pickerState)
        }
    }

    if (editingHour) {
        NumberInputDialog(
            title = "小时",
            initial = hour,
            range = 0..23,
            onDismiss = { editingHour = false },
            onConfirm = {
                hour = it
                editingHour = false
            }
        )
    }
    if (editingMinute) {
        NumberInputDialog(
            title = "分钟",
            initial = minute,
            range = 0..59,
            onDismiss = { editingMinute = false },
            onConfirm = {
                minute = it
                editingMinute = false
            }
        )
    }

    if (confirmDelete) {
        val repeating = initial.repeat != RepeatRule.ONCE
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(if (repeating) "删除这个重复待办？" else "删除这件事？") },
            text = {
                Text(
                    if (repeating) {
                        "「删除当天」只移除这一天；「删除全部」移除这一天及之后的全部实例，更早的记录会保留。"
                    } else {
                        "删掉后不会影响伙伴的成长记录。"
                    }
                )
            },
            confirmButton = {
                if (repeating) {
                    Row {
                        TextButton(
                            onClick = {
                                confirmDelete = false
                                onDelete(initial.id)
                            }
                        ) {
                            Text("删除当天")
                        }
                        TextButton(
                            onClick = {
                                confirmDelete = false
                                onDeleteSeries(initial.id)
                            }
                        ) {
                            Text("删除全部", color = MaterialTheme.colorScheme.error)
                        }
                    }
                } else {
                    TextButton(
                        onClick = {
                            confirmDelete = false
                            onDelete(initial.id)
                        }
                    ) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("留下") }
            }
        )
    }
}

@Composable
private fun NumberInputDialog(
    title: String,
    initial: Int,
    range: IntRange,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var text by remember { mutableStateOf(initial.toString()) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改$title") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { input -> text = input.filter { it.isDigit() }.take(2) },
                modifier = Modifier.focusRequester(focus),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val value = text.toIntOrNull() ?: initial
                    onConfirm(value.coerceIn(range.first, range.last))
                }
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
    androidx.compose.runtime.LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
}

private fun weekdayLabel(date: LocalDate): String = when (date.dayOfWeek.value) {
    1 -> "周一"
    2 -> "周二"
    3 -> "周三"
    4 -> "周四"
    5 -> "周五"
    6 -> "周六"
    else -> "周日"
}
