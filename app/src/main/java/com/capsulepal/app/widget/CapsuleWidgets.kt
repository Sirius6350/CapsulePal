package com.capsulepal.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.BatteryManager
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.capsulepal.app.CapsuleApp
import com.capsulepal.app.MainActivity
import com.capsulepal.app.R
import com.capsulepal.app.data.db.CapsuleDatabase
import com.capsulepal.app.data.db.toDomain
import com.capsulepal.app.data.model.AppSettings
import com.capsulepal.app.data.model.CapsulePage
import com.capsulepal.app.data.model.CapsuleVersion
import com.capsulepal.app.data.model.Task
import com.capsulepal.app.data.model.TaskStatus
import com.capsulepal.app.data.repo.InstantTodo
import com.capsulepal.app.data.repo.SettingsRepository
import com.capsulepal.app.notifier.NotificationActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * 三个桌面组件的统一刷新入口。
 *
 * 组件运行在桌面进程里，读不到 App 的内存数据，所以这里自己读 Room / DataStore，
 * 再按组件当前尺寸挑选要展示的区块。刷新由三处驱动：
 * 1. AppWidgetProvider 的系统回调与 30 分钟定时；
 * 2. App 进程存活时（前台、任务变化、即时待办变化）主动推送；
 * 3. 点击组件时顺带刷新。
 */
object CapsuleWidgets {

    /** 进程内合并刷新，避免电池轮询每秒都重建 Bitmap。 */
    private const val MIN_INTERVAL_MILLIS = 20_000L

    /** 今日待办组件最多能排几行；实际行数由组件高度决定。 */
    private const val MAX_TODO_ROWS = 60

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lastRunAt = AtomicLong(0L)

    fun requestUpdate(context: Context, minIntervalMillis: Long = MIN_INTERVAL_MILLIS) {
        val appContext = context.applicationContext
        val now = System.currentTimeMillis()
        if (now - lastRunAt.get() < minIntervalMillis) return
        lastRunAt.set(now)
        scope.launch { runCatching { updateAll(appContext) } }
    }

    suspend fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val metricIds = manager.getAppWidgetIds(
            ComponentName(context, MetricWidgetProvider::class.java)
        )
        val todoIds = manager.getAppWidgetIds(
            ComponentName(context, TodoWidgetProvider::class.java)
        )
        val instantIds = manager.getAppWidgetIds(
            ComponentName(context, InstantWidgetProvider::class.java)
        )
        if (metricIds.isEmpty() && todoIds.isEmpty() && instantIds.isEmpty()) return

        val snapshot = readSnapshot(context)
        if (metricIds.isNotEmpty()) updateMetric(context, manager, metricIds, snapshot)
        if (todoIds.isNotEmpty()) updateTodo(context, manager, todoIds, snapshot)
        if (instantIds.isNotEmpty()) updateInstant(context, manager, instantIds, snapshot)
    }

    // ---- 数据快照 ----

    private data class Snapshot(
        val settings: AppSettings,
        val levelPct: Int?,
        val tempC: Float?,
        val chargeCount: Int,
        val tasks: List<Task>,
        val instant: InstantTodo?
    )

    private suspend fun readSnapshot(context: Context): Snapshot {
        val settings = SettingsRepository(context).settings.first()
        val database = CapsuleDatabase.get(context)
        val tasks = database.taskDao()
            .getForDay(LocalDate.now().toEpochDay())
            .map { it.toDomain() }
            .filter { settings.version.isTaskVisible(it) }
        val chargeCount = runCatching {
            val todayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault())
                .toInstant().toEpochMilli()
            database.batteryDao().chargeCountSince(todayStart)
        }.getOrDefault(0)
        val instant = (context as? CapsuleApp)?.container?.instantTodoRepository?.current()
        val (level, temp) = readBattery(context)
        return Snapshot(settings, level, temp, chargeCount, tasks, instant)
    }

    private fun readBattery(context: Context): Pair<Int?, Float?> {
        val intent = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else null
        val rawTemp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?: Int.MIN_VALUE
        val temp = if (rawTemp != Int.MIN_VALUE) rawTemp / 10f else null
        return pct to temp
    }

    // ---- 电量与温度 ----

    private fun updateMetric(
        context: Context,
        manager: AppWidgetManager,
        ids: IntArray,
        snapshot: Snapshot
    ) {
        val density = context.resources.displayMetrics.density
        val batteryPage = batteryPage(snapshot.settings.version)
        val tempText = snapshot.tempC
            ?.let { String.format(Locale.CHINA, "%.1f°C", it) }
            ?: "--"
        val chargeText = "${snapshot.chargeCount} 次"

        ids.forEach { id ->
            val options = runCatching { manager.getAppWidgetOptions(id) }.getOrNull()
            val minWidth = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) ?: 250
            val minHeight = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT) ?: 110
            // 高瘦尺寸改用竖版：电量环在上，温度与充电次数在下；宽扁尺寸保持左右排布。
            val tall = minHeight > minWidth * 1.3f
            val views = RemoteViews(
                context.packageName,
                if (tall) R.layout.widget_metric_tall else R.layout.widget_metric
            )
            // 环的直径跟着可用空间走，拉大组件时一起变大。
            val ringDp = if (tall) {
                minOf(minWidth * 0.72f, minHeight * 0.34f).coerceIn(56f, 160f)
            } else {
                minOf(minWidth * 0.30f, minHeight * 0.62f).coerceIn(48f, 120f)
            }
            val ring = ringBitmap(context, (ringDp * density).toInt(), snapshot.levelPct)
            views.setImageViewBitmap(R.id.widget_metric_ring, ring)
            views.setViewLayoutWidth(
                R.id.widget_metric_ring, ringDp, TypedValue.COMPLEX_UNIT_DIP
            )
            views.setViewLayoutHeight(
                R.id.widget_metric_ring, ringDp, TypedValue.COMPLEX_UNIT_DIP
            )
            // 字号随环一起缩放，小尺寸不会挤，大尺寸不会显得空。
            val tempSp = (ringDp * if (tall) 0.34f else 0.27f).coerceIn(17f, 36f)
            val labelSp = (ringDp * 0.16f).coerceIn(10f, 14f)
            val chargeSp = (ringDp * 0.22f).coerceIn(13f, 24f)
            views.setTextViewText(R.id.widget_metric_temp, tempText)
            views.setTextViewText(R.id.widget_metric_charge, chargeText)
            views.setTextViewTextSize(
                R.id.widget_metric_temp, TypedValue.COMPLEX_UNIT_SP, tempSp
            )
            views.setTextViewTextSize(
                R.id.widget_metric_charge, TypedValue.COMPLEX_UNIT_SP, chargeSp
            )
            views.setTextViewTextSize(
                R.id.widget_metric_temp_label, TypedValue.COMPLEX_UNIT_SP, labelSp
            )
            views.setTextViewTextSize(
                R.id.widget_metric_charge_label, TypedValue.COMPLEX_UNIT_SP, labelSp
            )
            // 2x2 这种矮宽尺寸收掉电量环，只留温度与充电次数。
            views.setViewVisibility(
                R.id.widget_metric_ring,
                if (tall || minWidth >= 170) View.VISIBLE else View.GONE
            )
            // 空间越大，内边距略微放大，避免内容贴边。
            val padding = ((if (minWidth >= 300) 18 else 14) * density).toInt()
            views.setViewPadding(
                R.id.widget_metric_root, padding, padding, padding, padding
            )
            views.setOnClickPendingIntent(
                R.id.widget_metric_ring,
                pageIntent(context, batteryPage, id * 10 + 1)
            )
            views.setOnClickPendingIntent(
                R.id.widget_metric_info,
                pageIntent(context, batteryPage, id * 10 + 2)
            )
            views.setOnClickPendingIntent(
                R.id.widget_metric_root,
                pageIntent(context, batteryPage, id * 10 + 3)
            )
            manager.updateAppWidget(id, views)
        }
    }

    /** 电量优先跳到版本内的电池/监测页；都没有时只能回待办页。 */
    private fun batteryPage(version: CapsuleVersion): CapsulePage = when {
        CapsulePage.BATTERY in version.pages -> CapsulePage.BATTERY
        CapsulePage.MONITOR in version.pages -> CapsulePage.MONITOR
        else -> CapsulePage.TODAY
    }

    private fun ringBitmap(context: Context, sizePx: Int, percent: Int?): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val stroke = sizePx * 0.09f
        val inset = stroke / 2f + 1f
        val rect = RectF(inset, inset, sizePx - inset, sizePx - inset)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
        }
        paint.color = ContextCompat.getColor(context, R.color.widget_track)
        canvas.drawArc(rect, 0f, 360f, false, paint)
        if (percent != null) {
            paint.color = ContextCompat.getColor(context, R.color.widget_accent)
            canvas.drawArc(rect, -90f, 360f * percent.coerceIn(0, 100) / 100f, false, paint)
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(context, R.color.widget_ink)
            textSize = sizePx * 0.27f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val text = percent?.let { "$it%" } ?: "--"
        val baseline = sizePx / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(text, sizePx / 2f, baseline, textPaint)
        return bitmap
    }

    // ---- 今日待办 ----

    private fun updateTodo(
        context: Context,
        manager: AppWidgetManager,
        ids: IntArray,
        snapshot: Snapshot
    ) {
        val tasks = snapshot.tasks
        val total = tasks.size
        val done = tasks.count { it.status == TaskStatus.COMPLETED }
        val percent = if (total > 0) done * 100 / total else 0
        val now = System.currentTimeMillis()
        // 离当前时间最近的未完成待办：不管已经过了开始时间还是即将开始。
        val nearest = tasks
            .filter { it.isOpen }
            .sortedBy { abs(it.startMillis - now) }

        ids.forEach { id ->
            val options = runCatching { manager.getAppWidgetOptions(id) }.getOrNull()
            val minHeight = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT) ?: 120
            // 高度决定能放下几行：扣除标题与进度条后，每行约 30dp。
            val capacity = ((minHeight - 72) / 30).coerceIn(0, MAX_TODO_ROWS)
            val views = RemoteViews(context.packageName, R.layout.widget_todo)
            views.setTextViewText(R.id.widget_todo_count, "$done/$total")
            views.setTextViewText(R.id.widget_todo_percent, "$percent%")
            views.setProgressBar(R.id.widget_todo_progress, 100, percent, false)
            views.setTextViewText(
                R.id.widget_todo_empty,
                when {
                    total == 0 -> "今天还没有安排"
                    nearest.isEmpty() -> "今天的待办都完成了"
                    else -> ""
                }
            )
            views.setViewVisibility(
                R.id.widget_todo_empty,
                if (nearest.isEmpty()) View.VISIBLE else View.GONE
            )
            // 分区点击：点标题或进度条都回到待办页。
            views.setOnClickPendingIntent(
                R.id.widget_todo_header,
                pageIntent(context, CapsulePage.TODAY, id * 10 + 1)
            )
            views.setOnClickPendingIntent(
                R.id.widget_todo_progress,
                pageIntent(context, CapsulePage.TODAY, id * 10 + 2)
            )
            // 行数不再写死在布局里，按组件高度动态添加，拉多高就能显示多少条。
            views.removeAllViews(R.id.widget_todo_rows)
            nearest.take(capacity).forEachIndexed { index, task ->
                val row = RemoteViews(context.packageName, R.layout.widget_todo_item)
                row.setTextViewText(
                    R.id.widget_todo_item_time,
                    String.format(
                        Locale.CHINA,
                        "%02d:%02d",
                        task.startTime.hour,
                        task.startTime.minute
                    )
                )
                row.setTextViewText(R.id.widget_todo_item_title, task.title)
                row.setOnClickPendingIntent(
                    R.id.widget_todo_item_root,
                    pageIntent(context, CapsulePage.TODAY, id * 10 + 3 + index)
                )
                views.addView(R.id.widget_todo_rows, row)
            }
            manager.updateAppWidget(id, views)
        }
    }

    // ---- 即时待办入口 ----

    private fun updateInstant(
        context: Context,
        manager: AppWidgetManager,
        ids: IntArray,
        snapshot: Snapshot
    ) {
        val instant = snapshot.instant
        ids.forEach { id ->
            val options = runCatching { manager.getAppWidgetOptions(id) }.getOrNull()
            val minWidth = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) ?: 60
            val minHeight = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT) ?: 60
            // 1x1 / 1x2 只留加号；2 列以内且不矮的尺寸用竖版，避免横排把文字挤没。
            val compact = minWidth < 110
            val tall = !compact && minWidth < 170 && minHeight >= 100
            val singleLine = !compact && !tall && minHeight < 110
            val views = RemoteViews(
                context.packageName,
                if (tall) R.layout.widget_instant_tall else R.layout.widget_instant
            )
            views.setViewVisibility(
                R.id.widget_instant_main,
                if (compact) View.GONE else View.VISIBLE
            )
            views.setViewVisibility(
                R.id.widget_instant_solo,
                if (compact) View.VISIBLE else View.GONE
            )
            // 加号与文字随尺寸缩放，小尺寸不挤、大尺寸不空。
            val plusDp = when {
                compact -> (minOf(minWidth, minHeight) * 0.42f).coerceIn(40f, 72f)
                tall -> (minWidth * 0.40f).coerceIn(44f, 84f)
                else -> (minHeight * 0.33f).coerceIn(30f, 48f)
            }
            val plusId = if (compact) R.id.widget_instant_solo else R.id.widget_instant_plus
            views.setViewLayoutWidth(plusId, plusDp, TypedValue.COMPLEX_UNIT_DIP)
            views.setViewLayoutHeight(plusId, plusDp, TypedValue.COMPLEX_UNIT_DIP)
            views.setTextViewTextSize(
                plusId, TypedValue.COMPLEX_UNIT_SP, (plusDp * 0.55f).coerceIn(20f, 42f)
            )
            val titleSp = (minOf(minWidth, minHeight) * 0.14f).coerceIn(13f, 20f)
            views.setTextViewTextSize(
                R.id.widget_instant_title, TypedValue.COMPLEX_UNIT_SP, titleSp
            )
            views.setTextViewTextSize(
                R.id.widget_instant_subtitle,
                TypedValue.COMPLEX_UNIT_SP,
                (titleSp * 0.78f).coerceIn(9f, 14f)
            )
            views.setTextViewTextSize(
                R.id.widget_instant_action,
                TypedValue.COMPLEX_UNIT_SP,
                (titleSp * 0.85f).coerceIn(11f, 15f)
            )
            if (!compact) {
                views.setTextViewText(
                    R.id.widget_instant_title,
                    when {
                        instant != null -> instant.title
                        singleLine -> "即时"
                        else -> "即时待办"
                    }
                )
                views.setViewVisibility(
                    R.id.widget_instant_subtitle,
                    if (singleLine) View.GONE else View.VISIBLE
                )
                if (instant == null) {
                    views.setTextViewText(R.id.widget_instant_subtitle, "立即上岛")
                } else {
                    views.setTextViewText(R.id.widget_instant_subtitle, "即时待办 · 进行中")
                }
            }
            // 竖版但高度不够时先收起「完成」，避免文字或按钮被裁掉。
            val showAction = !compact && instant != null && (!tall || minHeight >= 150)
            views.setViewVisibility(
                R.id.widget_instant_action,
                if (showAction) View.VISIBLE else View.GONE
            )
            val mainIntent = if (instant != null) {
                pageIntent(context, CapsulePage.TODAY, id * 10 + 1)
            } else {
                captureIntent(context, id * 10 + 2)
            }
            views.setOnClickPendingIntent(R.id.widget_instant_main, mainIntent)
            views.setOnClickPendingIntent(R.id.widget_instant_root, mainIntent)
            views.setOnClickPendingIntent(R.id.widget_instant_solo, mainIntent)
            if (instant != null) {
                views.setOnClickPendingIntent(
                    R.id.widget_instant_action,
                    NotificationActions.completeInstant(context)
                )
            }
            manager.updateAppWidget(id, views)
        }
    }

    // ---- 跳转 ----

    fun pageIntent(context: Context, page: CapsulePage, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_WIDGET_PAGE, page.name)
            // PendingIntent 判重不看 extras，用 data 区分不同页面的同一个组件。
            data = Uri.parse("capsulepal://widget/${page.name.lowercase(Locale.US)}")
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun captureIntent(context: Context, requestCode: Int): PendingIntent {
        val intent = Intent(context, InstantTodoCaptureActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
