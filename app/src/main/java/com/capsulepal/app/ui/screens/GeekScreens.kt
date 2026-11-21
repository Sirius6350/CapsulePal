package com.capsulepal.app.ui.screens

import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.graphics.drawable.toBitmap
import com.capsulepal.app.R
import com.capsulepal.app.data.db.BatterySampleEntity
import com.capsulepal.app.data.db.ChargeSessionEntity
import com.capsulepal.app.data.db.DischargeSessionEntity
import com.capsulepal.app.data.repo.AppPowerItem
import com.capsulepal.app.monitor.BatteryCalc
import com.capsulepal.app.monitor.MonitorState
import com.capsulepal.app.monitor.RuntimeMetrics
import com.capsulepal.app.partner.PartnerMood
import com.capsulepal.app.signals.UsageAccess
import com.capsulepal.app.ui.CapsuleViewModel
import com.capsulepal.app.ui.GeekUiState
import com.capsulepal.app.ui.components.CapsuleButton
import com.capsulepal.app.ui.components.ElasticSwitch
import com.capsulepal.app.ui.components.InlineStat
import com.capsulepal.app.ui.components.PartnerFace
import com.capsulepal.app.ui.components.Pill
import com.capsulepal.app.ui.components.SoftCard
import com.capsulepal.app.ui.components.bounceClick
import com.capsulepal.app.ui.openSafely
import com.capsulepal.app.ui.overlaySettingsIntent
import com.capsulepal.app.ui.usageAccessSettingsIntent
import com.capsulepal.app.ui.theme.Butter
import com.capsulepal.app.ui.theme.Coral
import com.capsulepal.app.ui.theme.Mint
import com.capsulepal.app.ui.theme.MintSoft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// ---------------------------------------------------------------- 共用组件

@Composable
internal fun GeekHeader(title: String, subtitle: String) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 自绘折线卡片：固定高度、渐变填充、末端圆点，带纵轴刻度与三段横轴时间。
 * 数据不足两个点时显示占位，不画一条假线。
 */
@Composable
internal fun GeekCurveCard(
    title: String,
    subtitle: String?,
    values: List<Float>,
    color: Color,
    currentText: String,
    startLabel: String,
    midLabel: String,
    endLabel: String,
    modifier: Modifier = Modifier,
    minValue: Float? = null,
    maxValue: Float? = null,
    yFormatter: ((Float) -> String)? = null,
    height: Int = 96
) {
    SoftCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                text = currentText,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontFeatureSettings = "tnum"
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(Modifier.height(10.dp))
        if (values.size < 2) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(height.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "正在积累数据，稍后再来",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            val sampled = values.downsample()
            val lo = minValue ?: (sampled.minOrNull() ?: 0f)
            val hi = maxValue ?: (sampled.maxOrNull() ?: 1f)
            val span = (hi - lo).takeIf { it > 0.001f } ?: 1f
            val flatLine = hi - lo <= 0.001f
            val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
            val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
            val hasAxis = yFormatter != null
            Row(modifier = Modifier.fillMaxWidth()) {
                if (yFormatter != null) {
                    Column(
                        modifier = Modifier
                            .width(40.dp)
                            .height(height.dp),
                        verticalArrangement = Arrangement.SpaceBetween,
                        horizontalAlignment = Alignment.End
                    ) {
                        AxisLabel(yFormatter(hi), axisColor)
                        AxisLabel(yFormatter((hi + lo) / 2f), axisColor)
                        AxisLabel(yFormatter(lo), axisColor)
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Canvas(
                    modifier = Modifier
                        .weight(1f)
                        .height(height.dp)
                ) {
                    val stepX = size.width / (sampled.size - 1)
                    fun yOf(value: Float): Float = if (flatLine) {
                        size.height / 2f
                    } else {
                        size.height - ((value - lo) / span).coerceIn(0f, 1f) * size.height
                    }

                    for (i in 0..2) {
                        val y = size.height * i / 2f
                        drawLine(
                            color = gridColor,
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = 1f
                        )
                    }
                    val line = Path().apply {
                        moveTo(0f, yOf(sampled.first()))
                        for (index in 1 until sampled.size) {
                            lineTo(index * stepX, yOf(sampled[index]))
                        }
                    }
                    val fill = Path().apply {
                        addPath(line)
                        lineTo(size.width, size.height)
                        lineTo(0f, size.height)
                        close()
                    }
                    drawPath(
                        path = fill,
                        brush = Brush.verticalGradient(
                            listOf(color.copy(alpha = 0.24f), Color.Transparent)
                        )
                    )
                    drawPath(
                        path = line,
                        color = color,
                        style = Stroke(
                            width = 2.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                    drawCircle(
                        color = color,
                        radius = 3.dp.toPx(),
                        center = Offset(size.width, yOf(sampled.last()))
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = if (hasAxis) 48.dp else 0.dp)
            ) {
                AxisLabel(startLabel, axisColor)
                Spacer(Modifier.weight(1f))
                AxisLabel(midLabel, axisColor)
                Spacer(Modifier.weight(1f))
                AxisLabel(endLabel, axisColor)
            }
        }
    }
}

@Composable
private fun AxisLabel(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(
            fontFeatureSettings = "tnum"
        ),
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Clip
    )
}

/**
 * 极客版监测页顶部的伙伴气泡：没有独立伙伴页，用轮播的方式陪在监测页。
 */
@Composable
internal fun GeekMascotBubble(charging: Boolean) {
    val messages = remember(charging) {
        if (charging) {
            listOf(
                "充电中，功率和温度我都在看着。",
                "这次充电会记成一条会话，充满再核对。",
                "如果温度偏高，先把厚壳摘下来吧。",
                "充电功率每秒刷新，不用反复点。"
            )
        } else {
            listOf(
                "今天的电量曲线，我在本机慢慢画。",
                "哪个应用最费电，功耗页能翻到。",
                "掉电变快时，先看看后台谁在忙。",
                "24 小时采样只留在本机，不上传。"
            )
        }
    }
    var index by remember(charging) { mutableStateOf(0) }
    LaunchedEffect(charging) {
        while (true) {
            delay(6_000L)
            index = (index + 1) % messages.size
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        PartnerFace(
            mood = if (charging) PartnerMood.CHARGING else PartnerMood.FOCUS,
            size = 62.dp,
            charging = charging
        )
        Spacer(Modifier.width(12.dp))
        Surface(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Text(
                    text = if (charging) "充电陪护" else "极客模式",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(3.dp))
                Crossfade(targetState = index, label = "geekBubble") { current ->
                    Text(
                        text = messages[current],
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

/** 极客页的一个指标行。 */
@Composable
internal fun GeekStatRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.Medium,
                fontFeatureSettings = "tnum"
            ),
            color = valueColor
        )
    }
}

/** 轻薄水平条，用于应用功耗占比。 */
@Composable
private fun GeekBar(fraction: Float, color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(5.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(color)
        )
    }
}

// ---------------------------------------------------------------- 功耗页

@Composable
fun GeekPowerScreen(state: MonitorState?, vm: CapsuleViewModel) {
    val geek by vm.geek.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        while (true) {
            vm.refreshGeek()
            delay(60_000L)
        }
    }

    val reading = state?.reading
    val charging = reading?.charging == true || reading?.plugged == true
    val livePowerW = BatteryCalc.chargePowerW(reading?.voltageMv, reading?.currentMa)
    val estimate = estimateDischarge(
        charges = geek.charges,
        discharges = geek.discharges,
        levelPct = reading?.levelPct,
        nowMillis = System.currentTimeMillis(),
        charging = charging
    )
    val level = reading?.levelPct

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            GeekHeader(
                title = "功耗",
                subtitle = "功率来自电池电流与电压，应用功耗按使用情况估算。"
            )
        }

        item {
            SoftCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "当前功率",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (charging) "充电中，每秒刷新" else "放电中，每秒刷新",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Pill(
                        text = if (charging) "充电" else "放电",
                        color = if (charging) MintSoft else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (charging) Mint else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = livePowerW?.let { BatteryCalc.formatPower(it) } ?: "--",
                    style = MaterialTheme.typography.displayMedium.copy(
                        fontFeatureSettings = "tnum"
                    ),
                    color = if (charging) Mint else MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    InlineStat(
                        label = "电流",
                        value = reading?.currentMa?.let { BatteryCalc.formatCurrent(it) } ?: "--",
                        modifier = Modifier.weight(1f)
                    )
                    InlineStat(
                        label = "电压",
                        value = reading?.voltageMv?.let { BatteryCalc.formatVoltage(it) } ?: "--",
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                GeekStatRow(
                    label = "电池温度",
                    value = reading?.tempC?.let { BatteryCalc.formatTemp(it) } ?: "--"
                )
                GeekStatRow(
                    label = "剩余电量",
                    value = level?.let { "$it%" } ?: "--"
                )
                GeekStatRow(
                    label = "今天充电次数",
                    value = state?.let { "${it.todayChargeCount} 次" } ?: "--"
                )
            }
        }

        item {
            SoftCard {
                Text(
                    text = "续航预测",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (estimate?.reference == true) {
                        "充电中，按上一次完整放电推算"
                    } else {
                        "按本次放电时长与电量差推算"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                if (estimate == null || level == null) {
                    Text(
                        text = if (charging) {
                            "充电中。完成一次 10 分钟以上、掉电 1% 的放电后，这里会给出参考值。"
                        } else {
                            "本次放电满 10 分钟且掉电 1% 后，这里会给出 %/h 与预计可用时长。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    val hoursLeft = (level / estimate.ratePctPerHour).coerceIn(0f, 400f)
                    Text(
                        text = formatHours(hoursLeft),
                        style = MaterialTheme.typography.displayMedium.copy(
                            fontFeatureSettings = "tnum"
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (estimate.reference) "按上次放电速率折算" else "预计可用时长",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    GeekStatRow(
                        label = if (estimate.reference) "上次掉电速度" else "掉电速度",
                        value = String.format(Locale.CHINA, "%.1f%%/h", estimate.ratePctPerHour)
                    )
                    GeekStatRow(
                        label = if (estimate.reference) "上次放电时长" else "已放电",
                        value = formatDuration(estimate.elapsedMillis)
                    )
                }
            }
        }

        item {
            SoftCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "应用功耗估算",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Pill(
                        text = "估算",
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "今日 00:00 至今；时长取系统前台统计，功耗与温度取同时段电池采样",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                val items = geek.appPowerToday
                if (items.isEmpty()) {
                    val context = LocalContext.current
                    val granted = UsageAccess.granted(context)
                    Text(
                        text = if (granted) {
                            "今天还没有足够的用量记录，继续使用后会出现在这里。"
                        } else {
                            "需要「使用情况访问」权限才能统计前台时长。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!granted) {
                        Spacer(Modifier.height(10.dp))
                        CapsuleButton(
                            text = "去开启使用情况访问",
                            onClick = { openSafely(context, usageAccessSettingsIntent()) },
                            filled = false,
                            height = 44.dp
                        )
                    }
                } else {
                    val maxMah = items.maxOf { it.powerMah }.coerceAtLeast(0.01f)
                    items.forEach { item ->
                        AppPowerRow(item = item, maxMah = maxMah)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "今日前台合计约 ${items.sumOf { it.powerMah.toDouble() }.roundToInt()} mAh",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            Text(
                text = "功耗数值均为估算，受系统统计口径与使用场景影响，仅供参考。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }
}

@Composable
private fun AppPowerRow(item: AppPowerItem, maxMah: Float) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        AppIcon(packageName = item.packageName, label = item.label, size = 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = String.format(Locale.CHINA, "%.2f W", item.avgPowerW),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFeatureSettings = "tnum"
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = "平均温度 ${formatTemp(item.avgTempC)} · 最高温度 ${formatTemp(item.maxTempC)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "使用时长 ${formatMinutes(item.foregroundMinutes)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            GeekBar(
                fraction = (item.powerMah / maxMah).coerceIn(0f, 1f),
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/** 应用图标：按包名缓存，避免列表滚动时反复查 PackageManager。 */
@Composable
private fun AppIcon(packageName: String, label: String, size: androidx.compose.ui.unit.Dp) {
    val context = LocalContext.current
    var icon by remember(packageName) { mutableStateOf(APP_ICON_CACHE.get(packageName)) }
    LaunchedEffect(packageName) {
        if (icon != null) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager.getApplicationIcon(packageName)
                    .toBitmap(width = 108, height = 108)
                    .asImageBitmap()
            }.getOrNull()
        }
        if (loaded != null) {
            APP_ICON_CACHE.put(packageName, loaded)
            icon = loaded
        }
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(9.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        val bitmap = icon
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = label,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = label.take(1),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private val APP_ICON_CACHE = android.util.LruCache<String, ImageBitmap>(96)

// ---------------------------------------------------------------- 电池页

@Composable
fun GeekBatteryScreen(state: MonitorState?, vm: CapsuleViewModel) {
    val geek by vm.geek.collectAsStateWithLifecycle()
    var showSessions by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            vm.refreshGeek()
            delay(60_000L)
        }
    }
    if (showSessions) {
        SessionHistoryScreen(
            charges = geek.charges,
            discharges = geek.discharges,
            onBack = { showSessions = false },
            onClearCharges = vm::clearChargeSessions,
            onClearDischarges = vm::clearDischargeSessions
        )
        return
    }

    val samples = geek.timeline
    val latest = samples.lastOrNull()
    val reading = state?.reading

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            GeekHeader(
                title = "电池",
                subtitle = "24 小时采样每分钟一条；充放电会话按 1 秒刷新。"
            )
        }

        item {
            GeekCurveCard(
                title = "24 小时电量",
                subtitle = "${samples.size} 个采样点",
                values = samples.map { it.levelPct.toFloat() },
                color = MaterialTheme.colorScheme.primary,
                currentText = (latest?.levelPct ?: reading?.levelPct)?.let { "$it%" } ?: "--",
                startLabel = samples.firstOrNull()?.ts?.let { timeLabel(it, "HH:mm") } ?: "",
                midLabel = samples.midTimeLabel(),
                endLabel = samples.lastOrNull()?.ts?.let { timeLabel(it, "HH:mm") } ?: "",
                minValue = 0f,
                maxValue = 100f,
                yFormatter = { String.format(Locale.CHINA, "%.0f%%", it) }
            )
        }

        item {
            val active = geek.discharges.firstOrNull { it.endTs == 0L }
            val charging = reading?.charging == true || reading?.plugged == true
            SoftCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "当前会话",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Pill(
                        text = when {
                            charging -> "充电"
                            active != null -> "放电中"
                            else -> "待机"
                        },
                        color = if (charging) MintSoft else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (charging) Mint else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(10.dp))
                if (charging) {
                    val currentCharge = state?.currentCharge
                    GeekStatRow(
                        label = "充电功率",
                        value = state?.chargePowerW?.let { BatteryCalc.formatPower(it) } ?: "--",
                        valueColor = Mint
                    )
                    GeekStatRow(
                        label = "电池温度",
                        value = reading?.tempC?.let { BatteryCalc.formatTemp(it) } ?: "--"
                    )
                    GeekStatRow(
                        label = "本次充电",
                        value = currentCharge?.let {
                            val now = state?.updatedAt ?: System.currentTimeMillis()
                            "${it.startPct}% → ${it.endPct}% · ${BatteryCalc.formatDuration(it.durationMinutes(now))}"
                        } ?: "--"
                    )
                } else if (active != null) {
                    val minutes = ((System.currentTimeMillis() - active.startTs) / 60_000L).toInt()
                    GeekStatRow(
                        label = "开始时间",
                        value = timeLabel(active.startTs, "MM-dd HH:mm")
                    )
                    GeekStatRow(
                        label = "已放电",
                        value = BatteryCalc.formatDuration(minutes)
                    )
                    GeekStatRow(
                        label = "电量变化",
                        value = "${active.startPct}% → ${active.endPct}%"
                    )
                    GeekStatRow(
                        label = "平均功率",
                        value = BatteryCalc.formatPower(active.avgPowerW)
                    )
                } else {
                    Text(
                        text = "插上电源或拔掉电源后，这里会显示当前充放电会话。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            SoftCard(
                modifier = Modifier.bounceClick(
                    shape = RoundedCornerShape(26.dp),
                    onClick = { showSessions = true }
                )
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "充放电会话",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = "充电 ${geek.charges.size} 条 · 放电 ${geek.discharges.size} 条",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Pill(
                        text = "查看",
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        painter = painterResource(R.drawable.ic_ui_chevron_right),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        item {
            Text(
                text = "24 小时采样、充放电会话与应用功耗只在极客体验记录；切换到其他版本后停止新增，数据保留。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }
}

@Composable
private fun DischargeRow(session: DischargeSessionEntity) {
    val ongoing = session.endTs == 0L
    val endTs = if (ongoing) session.lastTs else session.endTs
    Column(modifier = Modifier.padding(vertical = 9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${session.startPct}% → ${session.endPct}%",
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.Medium,
                    fontFeatureSettings = "tnum"
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
            if (ongoing) {
                Spacer(Modifier.width(6.dp))
                Pill(
                    text = "进行中",
                    color = Mint.copy(alpha = 0.16f),
                    contentColor = Mint
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = BatteryCalc.formatPower(session.avgPowerW),
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFeatureSettings = "tnum"
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = "${timeLabel(session.startTs, "MM-dd HH:mm")} → " +
                (if (ongoing) "现在" else timeLabel(endTs, "HH:mm")) +
                " · ${BatteryCalc.formatDuration(((endTs - session.startTs) / 60_000L).toInt())}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ChargeRow(session: ChargeSessionEntity) {
    val ongoing = session.endTs == 0L
    val endTs = if (ongoing) session.lastTs else session.endTs
    Column(modifier = Modifier.padding(vertical = 9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${session.startPct}% → ${session.endPct}%",
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.Medium,
                    fontFeatureSettings = "tnum"
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
            if (ongoing) {
                Spacer(Modifier.width(6.dp))
                Pill(
                    text = "充电中",
                    color = Mint.copy(alpha = 0.16f),
                    contentColor = Mint
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = "均 ${BatteryCalc.formatPower(session.avgPowerW)}",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFeatureSettings = "tnum"
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = "${timeLabel(session.startTs, "MM-dd HH:mm")} → " +
                (if (ongoing) "现在" else timeLabel(endTs, "HH:mm")) +
                " · ${BatteryCalc.formatDuration(((endTs - session.startTs) / 60_000L).toInt())}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "峰值 ${BatteryCalc.formatPower(session.peakPowerW)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 充放电会话历史：Tab 切换充电 / 放电，分别支持一键清空。 */
@Composable
private fun SessionHistoryScreen(
    charges: List<ChargeSessionEntity>,
    discharges: List<DischargeSessionEntity>,
    onBack: () -> Unit,
    onClearCharges: () -> Unit,
    onClearDischarges: () -> Unit
) {
    BackHandler(onBack = onBack)
    var tab by remember { mutableStateOf(0) }
    var confirming by remember { mutableStateOf(false) }
    val chargeList = remember(charges) { charges.sortedByDescending { it.startTs } }
    val dischargeList = remember(discharges) { discharges.sortedByDescending { it.startTs } }
    val count = if (tab == 0) chargeList.size else dischargeList.size

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .bounceClick(onClick = onBack),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_ui_chevron_left),
                        contentDescription = "返回",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        text = "充放电会话",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "仅极客体验记录，删除后不可恢复",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SessionTab("充电会话", selected = tab == 0) { tab = 0 }
                SessionTab("放电会话", selected = tab == 1) { tab = 1 }
            }
        }

        item {
            SoftCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (tab == 0) "共 $count 条充电会话" else "共 $count 条放电会话",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = { confirming = true },
                        enabled = count > 0
                    ) {
                        Text(
                            text = if (tab == 0) "清空充电" else "清空放电",
                            color = if (count > 0) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            }
                        )
                    }
                }
            }
        }

        if (count == 0) {
            item {
                SoftCard {
                    Text(
                        text = if (tab == 0) {
                            "还没有充电会话。开始充电后会自动记录。"
                        } else {
                            "还没有放电会话。拔掉电源使用后会自动记录。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(if (tab == 0) chargeList.map { it.id } else dischargeList.map { it.id }) { id ->
                SoftCard {
                    if (tab == 0) {
                        chargeList.firstOrNull { it.id == id }?.let { ChargeRow(it) }
                    } else {
                        dischargeList.firstOrNull { it.id == id }?.let { DischargeRow(it) }
                    }
                }
            }
        }

        item {
            Text(
                text = "会话按 1 秒刷新，平均功率取会话期间的电池采样；最多展示最近 20 条。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(if (tab == 0) "清空充电会话？" else "清空放电会话？") },
            text = {
                Text(
                    if (tab == 0) {
                        "将删除全部 $count 条充电会话，此操作不可恢复。"
                    } else {
                        "将删除全部 $count 条放电会话，此操作不可恢复。"
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        if (tab == 0) onClearCharges() else onClearDischarges()
                    }
                ) {
                    Text("清空", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun SessionTab(text: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(999.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .bounceClick(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 9.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            ),
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

// ---------------------------------------------------------------- 悬浮窗页

@Composable
fun GeekOverlayScreen(state: MonitorState?, vm: CapsuleViewModel) {
    val ui by vm.uiState.collectAsStateWithLifecycle()
    val settings = ui?.settings ?: return
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasPermission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasPermission = Settings.canDrawOverlays(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val enabled = settings.metricOverlayEnabled
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            GeekHeader(
                title = "悬浮窗",
                subtitle = "独立指标悬浮窗，与保活悬浮点互不影响。"
            )
        }

        item {
            SoftCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "指标悬浮窗",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "在任意界面显示电量、温度、帧率与内存",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    ElasticSwitch(
                        checked = enabled,
                        onCheckedChange = { vm.setMetricOverlayEnabled(it) }
                    )
                }
                if (!hasPermission) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "还没有悬浮窗权限；开启后窗口不会出现。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Coral
                    )
                    Spacer(Modifier.height(8.dp))
                    CapsuleButton(
                        text = "去开启悬浮窗权限",
                        onClick = { openSafely(context, overlaySettingsIntent(context)) },
                        filled = false,
                        height = 44.dp
                    )
                }
            }
        }

        if (enabled) {
            item {
                SoftCard {
                    Text(
                        text = "外观",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(12.dp))
                    SliderRow(
                        label = "不透明度",
                        valueText = "${settings.metricOverlayAlphaSafe}%",
                        value = settings.metricOverlayAlphaSafe.toFloat(),
                        range = 10f..100f,
                        steps = 8,
                        onValueChange = { vm.setMetricOverlayAlpha(it.roundToInt()) }
                    )
                    Spacer(Modifier.height(6.dp))
                    SliderRow(
                        label = "整体大小",
                        valueText = "${settings.metricOverlayScaleSafe}%",
                        value = settings.metricOverlayScaleSafe.toFloat(),
                        range = 80f..140f,
                        steps = 5,
                        onValueChange = { vm.setMetricOverlayScale(it.roundToInt()) }
                    )
                    Spacer(Modifier.height(4.dp))
                    ToggleRow(
                        label = "横排展示",
                        checked = settings.metricOverlayHorizontal,
                        onCheckedChange = { vm.setMetricOverlayHorizontal(it) }
                    )
                    Text(
                        text = "横排时右侧会预留固定留白，数值实时变化也不会拉扯窗口宽度。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "按住窗口可拖动位置，双击窗口即可关闭。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item {
                SoftCard {
                    Text(
                        text = "显示指标",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(4.dp))
                    ToggleRow(
                        label = "温度",
                        checked = settings.metricOverlayShowTemp,
                        onCheckedChange = { vm.setMetricOverlayShowTemp(it) }
                    )
                    ToggleRow(
                        label = "电量",
                        checked = settings.metricOverlayShowBattery,
                        onCheckedChange = { vm.setMetricOverlayShowBattery(it) }
                    )
                    ToggleRow(
                        label = "帧率",
                        checked = settings.metricOverlayShowFps,
                        onCheckedChange = { vm.setMetricOverlayShowFps(it) }
                    )
                    ToggleRow(
                        label = "内存",
                        checked = settings.metricOverlayShowMemory,
                        onCheckedChange = { vm.setMetricOverlayShowMemory(it) }
                    )
                }
            }
        }

        item {
            val reading = state?.reading
            SoftCard {
                Text(
                    text = "当前指标",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(8.dp))
                GeekStatRow(
                    label = "电池温度",
                    value = reading?.tempC?.let { BatteryCalc.formatTemp(it) } ?: "--"
                )
                GeekStatRow(
                    label = "剩余电量",
                    value = reading?.levelPct?.let { "$it%" } ?: "--"
                )
                GeekStatRow(
                    label = "屏幕帧率",
                    value = RuntimeMetrics.fpsText().let { if (it == "--") "--" else "$it FPS" }
                )
                GeekStatRow(
                    label = "运行内存",
                    value = RuntimeMetrics.memoryText()
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "帧率与内存只保存在内存中，不写库、不进备份。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            Text(
                text = "指标悬浮窗由前台保活服务维持；切回其他版本后会自动隐藏，不残留窗口。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = valueText,
            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
    Slider(
        value = value.coerceIn(range.start, range.endInclusive),
        onValueChange = onValueChange,
        valueRange = range,
        steps = steps
    )
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        ElasticSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

// ---------------------------------------------------------------- 纯逻辑/格式化

private fun List<Float>.downsample(maxPoints: Int = 120): List<Float> {
    if (size <= maxPoints) return this
    return (0 until maxPoints).map { index ->
        this[index * (size - 1) / (maxPoints - 1)]
    }
}

/** 一次放电推算结果；reference = true 表示充电中，取的是上一次完整放电。 */
private data class DischargeEstimate(
    val ratePctPerHour: Float,
    val elapsedMillis: Long,
    val reference: Boolean
)

/** 门槛：放电满 10 分钟且至少掉 1%，否则整数百分比的量化误差会主导结果。 */
private const val MIN_DISCHARGE_MILLIS = 10 * 60_000L
private const val MIN_DROP_PCT = 1

/**
 * 掉电速度 = 掉电百分比 / 放电时长，续航 = 剩余电量 / 掉电速度。
 *
 * 放电起点优先取「上一次充电结束」的时刻与电量：放电会话只在 App 运行时才开始记录，
 * 拔电到进程起来之间可能已隔一段时间，充电会话结束时的电量更贴近真实起点。
 * 没有充电记录时退回尚未结束的放电会话；充电中则拿上一次完整放电当参考值。
 */
private fun estimateDischarge(
    charges: List<ChargeSessionEntity>,
    discharges: List<DischargeSessionEntity>,
    levelPct: Int?,
    nowMillis: Long,
    charging: Boolean
): DischargeEstimate? {
    val level = levelPct ?: return null

    if (charging) {
        // 当前电量在回升，公式不成立，只能给上一次完整放电的参考值。
        val last = discharges
            .filter { it.endTs > it.startTs }
            .maxByOrNull { it.endTs }
            ?: return null
        return buildRate(
            startMillis = last.startTs,
            endMillis = last.endTs,
            startPct = last.startPct,
            endPct = last.endPct,
            reference = true
        )
    }

    val lastCharge = charges
        .filter { it.endTs > it.startTs }
        .maxByOrNull { it.endTs }
    val openDischarge = discharges
        .filter { it.endTs == 0L && it.startTs <= nowMillis }
        .maxByOrNull { it.startTs }

    val startMillis: Long
    val startPct: Int
    if (lastCharge != null && (openDischarge == null || lastCharge.endTs >= openDischarge.startTs)) {
        startMillis = lastCharge.endTs
        startPct = lastCharge.endPct
    } else if (openDischarge != null) {
        startMillis = openDischarge.startTs
        startPct = openDischarge.startPct
    } else {
        return null
    }

    return buildRate(
        startMillis = startMillis,
        endMillis = nowMillis,
        startPct = startPct,
        endPct = level,
        reference = false
    )
}

private fun buildRate(
    startMillis: Long,
    endMillis: Long,
    startPct: Int,
    endPct: Int,
    reference: Boolean
): DischargeEstimate? {
    val elapsed = endMillis - startMillis
    val drop = startPct - endPct
    if (elapsed < MIN_DISCHARGE_MILLIS || drop < MIN_DROP_PCT) return null
    val rate = drop / (elapsed / 3_600_000f)
    if (rate <= 0f) return null
    return DischargeEstimate(
        ratePctPerHour = rate,
        elapsedMillis = elapsed,
        reference = reference
    )
}

private fun formatDuration(millis: Long): String {
    val totalMinutes = (millis / 60_000L).toInt()
    return when {
        totalMinutes < 60 -> "$totalMinutes 分"
        else -> "${totalMinutes / 60} 小时 ${totalMinutes % 60} 分"
    }
}

private fun formatHours(hours: Float): String {
    val totalMinutes = (hours * 60f).roundToInt()
    return when {
        totalMinutes < 1 -> "不足 1 分"
        totalMinutes < 60 -> "约 $totalMinutes 分"
        else -> "约 ${totalMinutes / 60} 小时 ${totalMinutes % 60} 分"
    }
}

private fun formatMinutes(minutes: Int): String = when {
    minutes <= 0 -> "0 分"
    minutes < 60 -> "$minutes 分"
    else -> "${minutes / 60} 小时 ${minutes % 60} 分"
}

private fun formatTemp(value: Float?): String =
    if (value != null && value > 0f) String.format(Locale.CHINA, "%.1f℃", value) else "--"

internal fun timeLabel(ts: Long, pattern: String): String =
    Instant.ofEpochMilli(ts)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern(pattern))

/** 曲线横轴中间刻度：取首尾时间的中点。 */
internal fun List<BatterySampleEntity>.midTimeLabel(): String {
    val first = firstOrNull()?.ts ?: return ""
    val last = lastOrNull()?.ts ?: return ""
    return timeLabel((first + last) / 2, "HH:mm")
}

/** 供监测页复用：24 小时样本的曲线数值。 */
internal fun BatterySampleEntity.tempOrNull(): Float? = tempC.takeIf { it > 0f }
