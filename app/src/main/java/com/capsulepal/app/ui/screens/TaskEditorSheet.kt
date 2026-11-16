package com.capsulepal.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import com.capsulepal.app.R
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.capsulepal.app.data.model.CategoryCatalog
import com.capsulepal.app.data.model.CategoryEntry
import com.capsulepal.app.data.model.MAX_CONSECUTIVE_DAYS
import com.capsulepal.app.data.model.MIN_CONSECUTIVE_DAYS
import com.capsulepal.app.data.model.Priority
import com.capsulepal.app.data.model.Plan
import com.capsulepal.app.data.model.RepeatRule
import com.capsulepal.app.data.model.Task
import com.capsulepal.app.data.model.TaskCategory
import com.capsulepal.app.data.model.TaskGroup
import com.capsulepal.app.data.model.TaskKind
import com.capsulepal.app.data.model.TaskDraft
import com.capsulepal.app.data.model.TaskStepDraft
import com.capsulepal.app.data.model.iconRes
import com.capsulepal.app.domain.TimeUtils
import com.capsulepal.app.ui.components.CapsuleButton
import com.capsulepal.app.ui.components.ChoiceChip
import com.capsulepal.app.ui.components.ElasticSwitch
import kotlinx.coroutines.delay
import com.capsulepal.app.ui.components.bounceClick
import com.capsulepal.app.ui.theme.Coral
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.roundToInt

fun Task.toDraft(): TaskDraft = TaskDraft(
    id = id,
    title = title,
    shortTitle = shortTitle,
    date = date,
    startTime = startTime,
    durationMinutes = durationMinutes,
    prepareMinutes = prepareMinutes,
    category = category,
    customCategory = customCategory,
    priority = priority,
    note = note,
    steps = steps.map { TaskStepDraft(id = it.id, title = it.title, durationMinutes = it.durationMinutes) },
    repeat = repeat,
    repeatDays = repeatDays,
    repeatCount = repeatCount,
    skipHolidays = skipHolidays,
    fromTemplate = fromTemplate,
    groupId = groupId,
    planId = planId,
    kind = kind
)

fun newTaskDraft(
    date: LocalDate,
    category: TaskCategory = TaskCategory.ROUTINE,
    planId: Long = 0L,
    kind: TaskKind = TaskKind.NORMAL
): TaskDraft {
    val now = java.time.LocalDateTime.now().withSecond(0).withNano(0)
    val rounded = now.plusMinutes(((5 - now.minute % 5) % 5).toLong())
    val targetDate = if (date == now.toLocalDate()) rounded.toLocalDate() else date
    return TaskDraft(
        date = targetDate,
        startTime = rounded.toLocalTime(),
        title = "",
        durationMinutes = 5,
        prepareMinutes = 0,
        category = category,
        planId = planId,
        kind = kind
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskEditorSheet(
    initial: TaskDraft,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (TaskDraft) -> Unit,
    groups: List<TaskGroup> = emptyList(),
    plans: List<Plan> = emptyList(),
    /** 从计划内创建时传入：固定归属，并把日期限制在计划区间内。 */
    planContext: Plan? = null,
    onDelete: (Long) -> Unit,
    categoryLayout: List<CategoryEntry> = CategoryCatalog.defaultLayout,
    onAddCustomCategory: (String) -> Unit = {},
    saving: Boolean = false
) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
    val sheetHeightPx = screenHeightPx * 0.92f
    // 横条只要向下移动超过 4dp，松手即视为关闭，不再判定面板/屏幕高度。
    val closeDragThresholdPx = with(density) { 4.dp.toPx() }

    var title by remember { mutableStateOf(initial.title) }
    var shortTitle by remember { mutableStateOf(initial.shortTitle) }
    var date by remember { mutableStateOf(initial.date) }
    var hour by remember { mutableIntStateOf(initial.startTime.hour) }
    var minute by remember { mutableIntStateOf(initial.startTime.minute) }
    var duration by remember { mutableIntStateOf(initial.durationMinutes) }
    var prepare by remember { mutableIntStateOf(initial.prepareMinutes) }
    var category by remember { mutableStateOf(initial.category) }
    var groupId by remember { mutableStateOf(initial.groupId) }
    var planId by remember { mutableStateOf(initial.planId) }
    var customCategoryName by remember { mutableStateOf(initial.customCategory) }
    var saveCustomPermanently by remember { mutableStateOf(false) }
    val entries = remember(categoryLayout, initial.customCategory) {
        buildList {
            addAll(categoryLayout)
            val name = initial.customCategory.trim()
            if (name.isNotBlank() && none { it is CategoryEntry.Custom && it.name == name }) {
                add(CategoryEntry.Custom(name))
            }
        }
    }
    val categoryOptions = remember(entries) {
        entries.map { entry ->
            when (entry) {
                is CategoryEntry.BuiltIn -> CategoryOption(entry.category)
                is CategoryEntry.Custom -> CategoryOption(TaskCategory.CUSTOM, entry.name)
            }
        }
    }
    val headOptions = categoryOptions.take(7)
    val moreOptions = categoryOptions.drop(7)
    var showMoreCategories by remember {
        mutableStateOf(
            entries.indexOfFirst { entry ->
                when (entry) {
                    is CategoryEntry.BuiltIn ->
                        entry.category == initial.category && initial.customCategory.isBlank()
                    is CategoryEntry.Custom -> entry.name == initial.customCategory
                }
            }.let { it < 0 || it >= 7 }
        )
    }
    var priority by remember { mutableStateOf(initial.priority) }
    var note by remember { mutableStateOf(initial.note) }
    var confirmDelete by remember { mutableStateOf(false) }
    val steps = remember {
        mutableStateListOf<TaskStepDraft>().apply { addAll(initial.steps) }
    }
    var repeat by remember { mutableStateOf(initial.repeat) }
    var repeatCount by remember {
        mutableIntStateOf(
            if (initial.repeatCount > 0) initial.repeatCount else 7
        )
    }
    var countEditing by remember { mutableStateOf(false) }
    val repeatDays = remember {
        mutableStateListOf<DayOfWeek>().apply { addAll(initial.repeatDays) }
    }
    var skipHolidays by remember { mutableStateOf(initial.skipHolidays) }
    var editField by remember { mutableStateOf<String?>(null) }
    var editText by remember { mutableStateOf("") }
    var editingStep by remember { mutableStateOf(-1) }
    var showCalendar by remember { mutableStateOf(false) }
    val datePickerState = rememberDatePickerState()
    val durationPresets = listOf(5, 10, 15, 25, 30, 45, 60, 90)
    val preparePresets = listOf(0, 5, 10, 15, 20, 30)

    // 位移用普通状态同步累加，拖动过程中不再逐帧起协程，避免偶发卡住。
    var sheetOffset by remember { mutableFloatStateOf(sheetHeightPx) }
    var sheetEntered by remember { mutableStateOf(false) }
    var sheetDragging by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    var showDiscardConfirm by remember { mutableStateOf(false) }
    val animatedOffset by animateFloatAsState(
        targetValue = if (closing) sheetHeightPx else sheetOffset,
        animationSpec = when {
            sheetDragging -> snap()
            !sheetEntered -> tween(durationMillis = 280)
            closing -> tween(durationMillis = 220)
            else -> spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)
        },
        label = "taskSheetOffset"
    )
    val hasTextEdits = title != initial.title ||
        shortTitle != initial.shortTitle ||
        note != initial.note
    val requestDismiss: () -> Unit = {
        if (!closing && !saving) {
            if (hasTextEdits) showDiscardConfirm = true else closing = true
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
        sheetOffset = (sheetOffset + delta).coerceIn(0f, sheetHeightPx)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 遮罩：点按关闭，但不拦截卡片上的手势。
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
                .fillMaxHeight(0.92f)
                .align(Alignment.BottomCenter)
                .offset { IntOffset(0, animatedOffset.roundToInt()) },
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                // 只有这条 48dp 热区能拖动整张卡片，内容区滑动与卡片完全解耦。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .draggable(
                            state = dragState,
                            orientation = Orientation.Vertical,
                            onDragStarted = { sheetDragging = true },
                            onDragStopped = {
                                sheetDragging = false
                                if (sheetOffset > closeDragThresholdPx) {
                                    requestDismiss()
                                    // 被“未保存”确认拦下时先把卡片收回原位。
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = when {
                            planContext != null && isNew -> "计划内新待办"
                            planContext != null -> "调整计划待办"
                            isNew -> "添加一件事"
                            else -> "调整这件事"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = requestDismiss) {
                        Text("关闭", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (planContext != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .width(4.dp)
                                .height(14.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(Color(planContext.color.argb))
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "加入计划 · ${planContext.title}" +
                                if (planContext.isPeriod) "（周期计划）" else "",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
            ) {
                var titleHadFocus by remember { mutableStateOf(false) }
                var titleTouched by remember { mutableStateOf(false) }
                OutlinedTextField(
                    value = title,
                    onValueChange = {
                        title = it
                        if (titleTouched && it.isNotBlank()) titleTouched = false
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { state ->
                            if (state.isFocused) {
                                titleHadFocus = true
                            } else if (titleHadFocus) {
                                titleTouched = true
                            }
                        },
                    label = { Text("待办名称") },
                    placeholder = { Text("比如：出门准备、上午专注") },
                    isError = titleTouched && title.isBlank(),
                    supportingText = if (titleTouched && title.isBlank()) {
                        { Text("写个名字才能保存") }
                    } else {
                        null
                    },
                    singleLine = true,
                    shape = MaterialTheme.shapes.large,
                    textStyle = MaterialTheme.typography.bodyLarge
                )
                OutlinedTextField(
                    value = shortTitle,
                    onValueChange = { shortTitle = it.take(2) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    label = { Text("胶囊简称") },
                    placeholder = { Text("两字，可留空") },
                    supportingText = { Text("岛上空间有限，填两个字更清楚；留空则显示全名") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.large,
                    textStyle = MaterialTheme.typography.bodyMedium
                )

                Spacer(Modifier.height(18.dp))
                FieldLabel("安排在")
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
                    val onQuickDay = date == LocalDate.now() || date == LocalDate.now().plusDays(1)
                    ChoiceChip(
                        text = if (onQuickDay) "日历" else "${date.monthValue} 月 ${date.dayOfMonth} 日",
                        selected = !onQuickDay,
                        onClick = {
                            datePickerState.selectedDateMillis = date.toEpochDay() * 86_400_000L
                            showCalendar = true
                        }
                    )
                }

                Spacer(Modifier.height(18.dp))
                FieldLabel("开始时间")
                TimeStepper(
                    hour = hour,
                    minute = minute,
                    onChange = { newHour, newMinute ->
                        hour = newHour
                        minute = newMinute
                    },
                    onEditHour = {
                        editText = "%02d".format(hour)
                        editField = "hour"
                    },
                    onEditMinute = {
                        editText = "%02d".format(minute)
                        editField = "minute"
                    }
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "准备窗口会从 ${TimeUtils.formatClock(
                        LocalTime.of(hour, minute).minusMinutes(prepare.toLong())
                    )} 开始",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(18.dp))
                FieldLabel("持续多久")
                val quickDurations = remember(durationPresets) { durationPresets.take(3) }
                val restDurations = remember(durationPresets) { durationPresets.drop(3) }
                val customDuration = duration !in durationPresets
                var showMoreDurations by remember { mutableStateOf(false) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    quickDurations.forEach { option ->
                        ChoiceChip(
                            text = TimeUtils.formatDuration(option),
                            selected = duration == option,
                            onClick = { duration = option }
                        )
                    }
                    Box {
                        ChoiceChip(
                            text = if (customDuration) "${duration} 分钟" else "更多",
                            selected = customDuration || duration in restDurations,
                            onClick = { showMoreDurations = true }
                        )
                        DropdownMenu(
                            expanded = showMoreDurations,
                            onDismissRequest = { showMoreDurations = false }
                        ) {
                            restDurations.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(TimeUtils.formatDuration(option)) },
                                    onClick = {
                                        duration = option
                                        showMoreDurations = false
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("自定义时长…") },
                                onClick = {
                                    showMoreDurations = false
                                    editText = duration.toString()
                                    editField = "duration"
                                }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))
                FieldLabel("提前多久进入准备窗口")
                val quickPrepares = remember(preparePresets) {
                    preparePresets.filter { it != 0 }.take(3)
                }
                val restPrepares = remember(preparePresets) {
                    preparePresets.filter { it != 0 && it !in quickPrepares }
                }
                val customPrepare = prepare != 0 && prepare !in preparePresets
                var showMorePrepares by remember { mutableStateOf(false) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceChip(
                        text = "不提前",
                        selected = prepare == 0,
                        onClick = { prepare = 0 }
                    )
                    quickPrepares.forEach { option ->
                        ChoiceChip(
                            text = "$option 分钟",
                            selected = prepare == option,
                            onClick = { prepare = option }
                        )
                    }
                    Box {
                        ChoiceChip(
                            text = if (customPrepare) "${prepare} 分钟" else "更多",
                            selected = customPrepare || prepare in restPrepares,
                            onClick = { showMorePrepares = true }
                        )
                        DropdownMenu(
                            expanded = showMorePrepares,
                            onDismissRequest = { showMorePrepares = false }
                        ) {
                            restPrepares.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text("$option 分钟") },
                                    onClick = {
                                        prepare = option
                                        showMorePrepares = false
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("自定义时长…") },
                                onClick = {
                                    showMorePrepares = false
                                    editText = (if (prepare == 0) 5 else prepare).toString()
                                    editField = "prepare"
                                }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))
                FieldLabel("属于哪一类")
                val isOptionSelected = { option: CategoryOption ->
                    category == option.category &&
                        (option.category != TaskCategory.CUSTOM || customCategoryName == option.customName)
                }
                val onOptionClick = { option: CategoryOption ->
                    category = option.category
                    customCategoryName = option.customName
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        headOptions.take(4).forEach { option ->
                            CategoryGridChip(
                                option = option,
                                selected = isOptionSelected(option),
                                onClick = { onOptionClick(option) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        headOptions.drop(4).forEach { option ->
                            CategoryGridChip(
                                option = option,
                                selected = isOptionSelected(option),
                                onClick = { onOptionClick(option) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        CategoryMoreChip(
                            expanded = showMoreCategories,
                            remaining = moreOptions.size,
                            onClick = { showMoreCategories = !showMoreCategories },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (showMoreCategories) {
                        moreOptions.chunked(4).forEach { row ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                row.forEach { option ->
                                    CategoryGridChip(
                                        option = option,
                                        selected = isOptionSelected(option),
                                        onClick = { onOptionClick(option) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                repeat(4 - row.size) {
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
                if (category == TaskCategory.CUSTOM) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = customCategoryName,
                        onValueChange = { customCategoryName = it.take(8) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("给这一类起个名字") },
                        singleLine = true,
                        shape = MaterialTheme.shapes.large,
                        textStyle = MaterialTheme.typography.bodyMedium
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "保存为永久分类",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "关闭时只用于这一件事",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        ElasticSwitch(
                            checked = saveCustomPermanently,
                            onCheckedChange = { saveCustomPermanently = it }
                        )
                    }
                }

                Spacer(Modifier.height(18.dp))
                FieldLabel("分组")
                if (groups.isEmpty()) {
                    Text(
                        text = "还没有分组，可在待办页的「分组管理」里新建。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ChoiceChip(
                            text = "未分组",
                            selected = groupId == 0L,
                            onClick = { groupId = 0L }
                        )
                        groups.forEach { group ->
                            ChoiceChip(
                                text = group.name,
                                selected = groupId == group.id,
                                onClick = { groupId = group.id }
                            )
                        }
                    }
                }

                if (planContext == null) {
                    Spacer(Modifier.height(18.dp))
                    FieldLabel("所属计划")
                    if (plans.isEmpty()) {
                        Text(
                            text = "还没有计划，可在「计划」页新建。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ChoiceChip(
                                text = "不关联",
                                selected = planId == 0L,
                                onClick = { planId = 0L }
                            )
                            plans.forEach { plan ->
                                ChoiceChip(
                                    text = plan.title,
                                    selected = planId == plan.id,
                                    onClick = { planId = plan.id }
                                )
                            }
                        }
                    }
                } else if (planContext.isPeriod) {
                    val range = buildString {
                        append("计划区间：")
                        planContext.startDate?.let { append("${it.monthValue}/${it.dayOfMonth} 起") }
                        planContext.targetDate?.let { append(" · ${it.monthValue}/${it.dayOfMonth} 止") }
                    }
                    Text(
                        text = "$range，日期会自动收进区间",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }

                Spacer(Modifier.height(18.dp))
                FieldLabel("重要程度")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Priority.entries.forEach { option ->
                        ChoiceChip(
                            text = option.label,
                            selected = priority == option,
                            onClick = { priority = option }
                        )
                    }
                }

                Spacer(Modifier.height(18.dp))
                FieldLabel("重复")
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    RepeatRule.entries.forEach { option ->
                        ChoiceChip(
                            text = option.label,
                            selected = repeat == option,
                            onClick = {
                                repeat = option
                                if (option == RepeatRule.WEEKLY && repeatDays.isEmpty()) {
                                    repeatDays.add(date.dayOfWeek)
                                }
                            }
                        )
                    }
                }
                if (repeat == RepeatRule.CONSECUTIVE) {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "连续天数",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "含起始日，共 $repeatCount 天；点数字可直接输入",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        CountStepButton(
                            text = "−",
                            enabled = repeatCount > MIN_CONSECUTIVE_DAYS
                        ) { repeatCount = (repeatCount - 1).coerceAtLeast(MIN_CONSECUTIVE_DAYS) }
                        Text(
                            text = "$repeatCount",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier
                                .width(52.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                                .bounceClick { countEditing = true }
                                .padding(vertical = 6.dp)
                        )
                        CountStepButton(
                            text = "+",
                            enabled = repeatCount < MAX_CONSECUTIVE_DAYS
                        ) { repeatCount = (repeatCount + 1).coerceAtMost(MAX_CONSECUTIVE_DAYS) }
                    }
                    if (countEditing) {
                        val focusRequester = remember { FocusRequester() }
                        val keyboard = LocalSoftwareKeyboardController.current
                        var input by remember { mutableStateOf(repeatCount.toString()) }
                        LaunchedEffect(Unit) {
                            focusRequester.requestFocus()
                            keyboard?.show()
                        }
                        AlertDialog(
                            onDismissRequest = { countEditing = false },
                            title = { Text("连续天数") },
                            text = {
                                OutlinedTextField(
                                    value = input,
                                    onValueChange = { value ->
                                        input = value.filter { it.isDigit() }.take(4)
                                    },
                                    label = { Text("天数（至少 $MIN_CONSECUTIVE_DAYS 天）") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .focusRequester(focusRequester)
                                )
                            },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        repeatCount = (input.toIntOrNull() ?: repeatCount)
                                            .coerceIn(MIN_CONSECUTIVE_DAYS, MAX_CONSECUTIVE_DAYS)
                                        countEditing = false
                                    }
                                ) { Text("确定") }
                            },
                            dismissButton = {
                                TextButton(onClick = { countEditing = false }) { Text("取消") }
                            }
                        )
                    }
                    if (planContext?.targetDate != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "计划结束日 ${planContext.targetDate!!.monthValue}/" +
                                "${planContext.targetDate!!.dayOfMonth} 会自动截断重复",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (repeat == RepeatRule.WEEKLY) {
                    Spacer(Modifier.height(10.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(weekdayOptions) { (day, label) ->
                            ChoiceChip(
                                text = label,
                                selected = day in repeatDays,
                                onClick = {
                                    if (day in repeatDays) {
                                        if (repeatDays.size > 1) repeatDays.remove(day)
                                    } else {
                                        repeatDays.add(day)
                                    }
                                }
                            )
                        }
                    }
                    if (repeatDays.isEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "至少选一天",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                if (repeat != RepeatRule.ONCE && repeat != RepeatRule.CONSECUTIVE) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "节假日自动跳过",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "周末与法定节假日不生成待办",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        ElasticSwitch(
                            checked = skipHolidays,
                            onCheckedChange = { skipHolidays = it }
                        )
                    }
                }

                Spacer(Modifier.height(18.dp))
                FieldLabel("步骤（可选）")
                steps.forEachIndexed { index, step ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${index + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(20.dp)
                        )
                        OutlinedTextField(
                            value = step.title,
                            onValueChange = { value ->
                                steps[index] = step.copy(title = value)
                            },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("做什么") },
                            singleLine = true,
                            shape = MaterialTheme.shapes.large,
                            textStyle = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (step.durationMinutes == 0) "不计时" else "${step.durationMinutes} 分",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .bounceClick {
                                    editingStep = index
                                    editText = step.durationMinutes.toString()
                                    editField = "step"
                                }
                                .padding(horizontal = 6.dp, vertical = 8.dp)
                        )
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .bounceClick { steps.removeAt(index) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_ui_close),
                                contentDescription = "删除步骤",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
                TextButton(onClick = { steps.add(TaskStepDraft(title = "", durationMinutes = 5)) }) {
                    Text("+ 添加一个步骤", style = MaterialTheme.typography.labelMedium)
                }

                Spacer(Modifier.height(12.dp))
                FieldLabel("备注（可选）")
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("想留给自己的话") },
                    minLines = 2,
                    maxLines = 4,
                    shape = MaterialTheme.shapes.large,
                    textStyle = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(20.dp))
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (!isNew) {
                    CapsuleButton(
                        text = "删除",
                        onClick = { confirmDelete = true },
                        modifier = Modifier.weight(0.6f),
                        filled = false,
                        enabled = !saving,
                        color = MaterialTheme.colorScheme.error,
                        height = 52.dp
                    )
                }
                CapsuleButton(
                    text = if (saving) "保存中…" else "保存",
                    onClick = {
                        val useRepeat = isNew
                        if (category == TaskCategory.CUSTOM &&
                            saveCustomPermanently &&
                            customCategoryName.isNotBlank()
                        ) {
                            onAddCustomCategory(customCategoryName.trim())
                        }
                        val windowStart = planContext?.startDate
                        val windowEnd = planContext?.targetDate
                        val savedDate = when {
                            windowStart != null && date.isBefore(windowStart) -> windowStart
                            windowEnd != null && date.isAfter(windowEnd) -> windowEnd
                            else -> date
                        }
                        onSave(
                            initial.copy(
                                title = title.trim(),
                                shortTitle = shortTitle.trim(),
                                date = savedDate,
                                startTime = LocalTime.of(hour, minute),
                                durationMinutes = duration,
                                prepareMinutes = prepare,
                                category = category,
                                customCategory = if (category == TaskCategory.CUSTOM) {
                                    customCategoryName.trim()
                                } else {
                                    ""
                                },
                                priority = priority,
                                note = note.trim(),
                                groupId = groupId,
                                planId = planContext?.id ?: planId,
                                steps = steps.filter { it.title.isNotBlank() },
                                repeat = if (useRepeat && repeat == RepeatRule.WEEKLY &&
                                    repeatDays.isEmpty()
                                ) RepeatRule.ONCE else if (useRepeat) repeat else initial.repeat,
                                repeatDays = if (useRepeat) repeatDays.toSet() else initial.repeatDays,
                                repeatCount = if (useRepeat && repeat == RepeatRule.CONSECUTIVE) {
                                    repeatCount
                                } else {
                                    initial.repeatCount
                                },
                                skipHolidays = if (useRepeat &&
                                    repeat != RepeatRule.ONCE &&
                                    repeat != RepeatRule.CONSECUTIVE
                                ) {
                                    skipHolidays
                                } else {
                                    initial.skipHolidays
                                }
                            )
                        )
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !saving && title.isNotBlank() &&
                        (category != TaskCategory.CUSTOM || customCategoryName.isNotBlank()),
                    height = 52.dp
                )
            }
        }
    }
    }

    if (showDiscardConfirm) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = { Text("放弃这次修改？") },
            text = { Text("名称、简称或备注的修改还没有保存。") },
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

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这件事？") },
            text = { Text("删掉后不会影响伙伴的成长记录。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete(initial.id)
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("留下") }
            }
        )
    }

    if (showCalendar) {
        DatePickerDialog(
            onDismissRequest = { showCalendar = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            date = LocalDate.ofEpochDay(millis / 86_400_000L)
                        }
                        showCalendar = false
                    }
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showCalendar = false }) { Text("取消") }
            }
        ) {
            DatePicker(state = datePickerState, showModeToggle = false)
        }
    }

    editField?.let { field ->
        AlertDialog(
            onDismissRequest = { editField = null },
            title = {
                Text(
                    when (field) {
                        "hour" -> "修改小时"
                        "minute" -> "修改分钟"
                        "prepare" -> "自定义准备窗口"
                        "step" -> "步骤计时"
                        else -> "自定义时长"
                    }
                )
            },
            text = {
                OutlinedTextField(
                    value = editText,
                    onValueChange = { input -> editText = input.filter(Char::isDigit).take(3) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    suffix = {
                        Text(
                            if (field == "duration" || field == "prepare" || field == "step") {
                                "分钟"
                            } else if (field == "hour") {
                                "时"
                            } else {
                                "分"
                            }
                        )
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val value = editText.toIntOrNull()
                        if (value != null) {
                            when (field) {
                                "hour" -> hour = value.coerceIn(0, 23)
                                "minute" -> minute = value.coerceIn(0, 59)
                                "prepare" -> prepare = value.coerceIn(1, 120)
                                "step" -> {
                                    val index = editingStep
                                    if (index in steps.indices) {
                                        steps[index] = steps[index].copy(
                                            durationMinutes = value.coerceIn(0, 600)
                                        )
                                    }
                                }
                                else -> duration = value.coerceIn(1, 600)
                            }
                        }
                        editField = null
                    }
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { editField = null }) { Text("取消") }
            }
        )
    }
}

private val weekdayOptions = listOf(
    DayOfWeek.MONDAY to "一",
    DayOfWeek.TUESDAY to "二",
    DayOfWeek.WEDNESDAY to "三",
    DayOfWeek.THURSDAY to "四",
    DayOfWeek.FRIDAY to "五",
    DayOfWeek.SATURDAY to "六",
    DayOfWeek.SUNDAY to "日"
)

@Composable
internal fun FieldLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
private fun CountStepButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(
                if (enabled) {
                    MaterialTheme.colorScheme.surfaceVariant
                } else {
                    Color.Transparent
                }
            )
            .bounceClick(enabled = enabled, shape = CircleShape, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            }
        )
    }
}

@Composable
internal fun TimeStepper(
    hour: Int,
    minute: Int,
    onChange: (Int, Int) -> Unit,
    onEditHour: () -> Unit,
    onEditMinute: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        StepperColumn(
            value = "%02d".format(hour),
            onUp = { onChange((hour + 1) % 24, minute) },
            onDown = { onChange((hour + 23) % 24, minute) },
            onValueClick = onEditHour
        )
        Text(
            text = ":",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp)
        )
        StepperColumn(
            value = "%02d".format(minute),
            onUp = {
                val total = hour * 60 + minute + 5
                onChange((total / 60) % 24, total % 60)
            },
            onDown = {
                val total = hour * 60 + minute - 5 + 24 * 60
                onChange((total / 60) % 24, total % 60)
            },
            onValueClick = onEditMinute
        )
    }
}

@Composable
internal fun StepperColumn(
    value: String,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onValueClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(width = 64.dp, height = 30.dp)
                .bounceClick(onClick = onUp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_ui_chevron_up),
                contentDescription = "增加",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.displayMedium.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .bounceClick(onClick = onValueClick)
                .padding(horizontal = 4.dp)
        )
        Box(
            modifier = Modifier
                .size(width = 64.dp, height = 30.dp)
                .bounceClick(onClick = onDown),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_ui_chevron_down),
                contentDescription = "减少",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private data class CategoryOption(
    val category: TaskCategory,
    val customName: String = ""
) {
    val label: String
        get() = if (category == TaskCategory.CUSTOM && customName.isNotBlank()) customName else category.label
}

@Composable
private fun CategoryGridChip(
    option: CategoryOption,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val background = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val content = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        modifier = modifier
            .height(34.dp)
            .bounceClick(shape = CircleShape, onClick = onClick),
        shape = CircleShape,
        color = background,
        contentColor = content
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(option.category.iconRes()),
                contentDescription = null,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(3.dp))
            Text(
                text = option.label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun CategoryMoreChip(
    expanded: Boolean,
    remaining: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .height(34.dp)
            .bounceClick(shape = CircleShape, onClick = onClick),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = Coral
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (expanded) "收起" else "更多",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                maxLines = 1
            )
            if (!expanded) {
                Spacer(Modifier.width(3.dp))
                Text(
                    text = "$remaining",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                )
            }
        }
    }
}
