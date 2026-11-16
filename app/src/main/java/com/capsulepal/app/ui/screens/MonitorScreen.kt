package com.capsulepal.app.ui.screens

import android.app.ActivityManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.capsulepal.app.data.model.CapsuleVersion
import com.capsulepal.app.monitor.BatteryCalc
import com.capsulepal.app.monitor.MonitorState
import com.capsulepal.app.ui.GeekUiState
import com.capsulepal.app.ui.components.InlineStat
import com.capsulepal.app.ui.components.Pill
import com.capsulepal.app.ui.components.ProgressRing
import com.capsulepal.app.ui.components.RatedCapacityDialog
import com.capsulepal.app.ui.components.SoftCard
import com.capsulepal.app.ui.theme.Butter
import com.capsulepal.app.ui.theme.Coral
import com.capsulepal.app.ui.theme.Mint
import com.capsulepal.app.ui.theme.MintSoft
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun MonitorScreen(
    state: MonitorState?,
    version: CapsuleVersion,
    geek: GeekUiState,
    onSetRatedCapacity: (Int) -> Unit,
    onRefresh: () -> Unit,
    onRefreshGeek: () -> Unit,
    onResetLearning: () -> Unit
) {
    var showRatedDialog by remember { mutableStateOf(false) }
    val geekMode = version.showGeekTools

    LaunchedEffect(geekMode) {
        onRefresh()
        if (geekMode) {
            while (true) {
                onRefreshGeek()
                delay(30_000L)
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Column {
                Text(
                    text = "监测",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = if (geekMode) {
                        "充放电均按 1 秒采样；1 小时曲线只保存在内存里。"
                    } else {
                        "充电时每 1 秒采样一次，放电时与通知轮询同为 60 秒。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (geekMode) {
            item {
                GeekMascotBubble(charging = state?.reading?.charging == true)
            }
        }

        item {
            HealthCard(state = state, onEditRated = { showRatedDialog = true })
        }

        item {
            RealtimeCard(state = state, geekMode = geekMode)
        }

        item {
            ChargeCard(state = state)
        }

        item {
            LearningCard(state = state, onResetLearning = onResetLearning)
        }

        if (geekMode) {
            val now = System.currentTimeMillis()
            val hourAgo = now - 3_600_000L
            val temps = geek.timeline.filter { it.ts >= hourAgo && it.tempC > 0f }
            item {
                GeekCurveCard(
                    title = "1 小时温度",
                    subtitle = "每分钟一个采样点",
                    values = temps.map { it.tempC },
                    color = Coral,
                    currentText = temps.lastOrNull()?.tempC
                        ?.let { BatteryCalc.formatTemp(it) } ?: "--",
                    startLabel = temps.firstOrNull()?.ts?.let { timeLabel(it, "HH:mm") } ?: "",
                    midLabel = midTimeLabel(
                        temps.firstOrNull()?.ts,
                        temps.lastOrNull()?.ts
                    ),
                    endLabel = temps.lastOrNull()?.ts?.let { timeLabel(it, "HH:mm") } ?: "",
                    yFormatter = { String.format(Locale.CHINA, "%.1f", it) }
                )
            }
            val fpsPoints = geek.runtimePoints.filter { it.fps != null }
            item {
                GeekCurveCard(
                    title = "1 小时帧率",
                    subtitle = "10 秒一个点，不写库",
                    values = fpsPoints.map { it.fps ?: 0f },
                    color = Mint,
                    currentText = geek.runtime.fps?.let {
                        String.format(Locale.CHINA, "%.1f FPS", it)
                    } ?: "--",
                    startLabel = fpsPoints.firstOrNull()?.ts?.let { timeLabel(it, "HH:mm") } ?: "",
                    midLabel = midTimeLabel(
                        fpsPoints.firstOrNull()?.ts,
                        fpsPoints.lastOrNull()?.ts
                    ),
                    endLabel = fpsPoints.lastOrNull()?.ts?.let { timeLabel(it, "HH:mm") } ?: "",
                    yFormatter = { String.format(Locale.CHINA, "%.1f", it) }
                )
            }
            val memoryPoints = geek.runtimePoints.filter { it.memoryUsedMb != null }
            item {
                GeekCurveCard(
                    title = "1 小时内存",
                    subtitle = "整机已用内存",
                    values = memoryPoints.map { (it.memoryUsedMb ?: 0).toFloat() },
                    color = Butter,
                    currentText = geek.runtime.let { snapshot ->
                        val used = snapshot.memoryUsedMb
                        val total = snapshot.memoryTotalMb
                        when {
                            used == null -> "--"
                            total == null -> String.format(Locale.CHINA, "%.1f GB", used / 1024f)
                            else -> String.format(
                                Locale.CHINA,
                                "%.1f/%.1f GB",
                                used / 1024f,
                                total / 1024f
                            )
                        }
                    },
                    startLabel = memoryPoints.firstOrNull()?.ts?.let { timeLabel(it, "HH:mm") } ?: "",
                    midLabel = midTimeLabel(
                        memoryPoints.firstOrNull()?.ts,
                        memoryPoints.lastOrNull()?.ts
                    ),
                    endLabel = memoryPoints.lastOrNull()?.ts?.let { timeLabel(it, "HH:mm") } ?: "",
                    yFormatter = { String.format(Locale.CHINA, "%.1f", it / 1024f) }
                )
            }
        }

        item {
            Text(
                text = "健康度与容量均为系统电量计 + 额定容量的估算值，仅供参考。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp)
            )
        }

        if (geekMode) {
            item {
                DeviceInfoCard()
            }
        }
    }

    if (showRatedDialog && state != null) {
        RatedCapacityDialog(
            ratedIsAuto = state.ratedIsAuto,
            ratedCapacityMah = state.ratedCapacityMah,
            systemDesignMah = state.systemDesignMah,
            onDismiss = { showRatedDialog = false },
            onConfirm = { mah ->
                onSetRatedCapacity(mah)
                showRatedDialog = false
            }
        )
    }
}

@Composable
private fun DeviceInfoCard() {
    val context = LocalContext.current
    val device = listOf(Build.BRAND, Build.MODEL)
        .filter { it.isNotBlank() }
        .joinToString(" ")
    val metrics = context.resources.displayMetrics
    val memory = ActivityManager.MemoryInfo()
    runCatching {
        context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(memory)
    }
    val storage = runCatching { StatFs(Environment.getDataDirectory().absolutePath) }.getOrNull()

    SoftCard {
        Text(
            text = "本机信息",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(8.dp))
        GeekStatRow(label = "设备", value = device.ifBlank { "--" })
        GeekStatRow(
            label = "系统",
            value = "Android ${Build.VERSION.RELEASE} · SDK ${Build.VERSION.SDK_INT}"
        )
        GeekStatRow(
            label = "处理器",
            value = processorLabel()
        )
        GeekStatRow(
            label = "屏幕",
            value = "${metrics.widthPixels} × ${metrics.heightPixels} · ${metrics.densityDpi} dpi"
        )
        GeekStatRow(
            label = "运行内存",
            value = String.format(Locale.CHINA, "%.1f GB", memory.totalMem / 1024f / 1024f / 1024f)
        )
        if (storage != null) {
            GeekStatRow(
                label = "存储",
                value = String.format(
                    Locale.CHINA,
                    "%.0f / %.0f GB 可用",
                    storage.availableBytes / 1024f / 1024f / 1024f,
                    storage.totalBytes / 1024f / 1024f / 1024f
                )
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "本机信息只在本页展示，不上传、不写库。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 曲线横轴中间刻度：取首尾时间的中点。 */
private fun midTimeLabel(firstTs: Long?, lastTs: Long?): String =
    if (firstTs == null || lastTs == null) "" else timeLabel((firstTs + lastTs) / 2, "HH:mm")

/** 处理器：优先 SoC 厂商 + 型号，取不到时退回硬件代号，并附上首选 ABI。 */
private fun processorLabel(): String {
    val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(Build.SOC_MANUFACTURER, Build.SOC_MODEL)
            .filter { it.isNotBlank() && it != Build.UNKNOWN }
            .joinToString(" ")
    } else {
        ""
    }
    val name = soc.ifBlank {
        listOf(Build.HARDWARE, Build.BOARD)
            .filter { it.isNotBlank() && it != Build.UNKNOWN }
            .joinToString(" ")
    }
    val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
    return listOf(name, abi).filter { it.isNotBlank() }.joinToString(" · ")
}

@Composable
private fun HealthCard(
    state: MonitorState?,
    onEditRated: () -> Unit
) {
    val health = state?.healthPredictionPct
    val ringColor = when {
        health == null -> MaterialTheme.colorScheme.outlineVariant
        health >= 85f -> Mint
        health >= 70f -> Butter
        else -> Coral
    }
    SoftCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProgressRing(
                progress = (health ?: 0f) / 100f,
                size = 108.dp,
                strokeWidth = 10.dp,
                color = ringColor
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = health?.let { BatteryCalc.formatHealth(it) } ?: "--",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontFeatureSettings = "tnum"
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "健康度预测",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 18.dp)
            ) {
                val charging = state?.reading?.charging == true
                Pill(
                    text = when {
                        state == null -> "读取中"
                        charging -> "正在充电"
                        else -> "使用中"
                    },
                    color = if (charging) MintSoft else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (charging) Mint else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = state?.reading?.chargeCounterMah?.let { "$it mAh" } ?: "--",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontFeatureSettings = "tnum"
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
                val predictedFull = state?.fullCapacityMah
                if (predictedFull != null) {
                    Text(
                        text = "（预测满充为 $predictedFull mAh）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = state?.let {
                        when {
                            !it.ratedIsAuto -> "额定 ${it.ratedCapacityMah} mAh · 手动"
                            it.systemDesignMah != null -> "额定 ${it.ratedCapacityMah} mAh · 系统设计"
                            else -> "额定 ${it.ratedCapacityMah} mAh · 默认值（未读到系统容量）"
                        }
                    } ?: "额定容量 --",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (state != null) {
                    Text(
                        text = "学习健康度以这个额定容量为基准",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                    )
                }
                TextButton(
                    onClick = onEditRated,
                    enabled = state != null,
                    contentPadding = PaddingValues(horizontal = 0.dp)
                ) {
                    Text("修改额定容量", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun RealtimeCard(state: MonitorState?, geekMode: Boolean) {
    val reading = state?.reading
    SoftCard {
        Text(
            text = "实时数据",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            StatCell(
                label = "电池温度",
                value = reading?.tempC?.let { BatteryCalc.formatTemp(it) } ?: "--",
                modifier = Modifier.weight(1f)
            )
            StatCell(
                label = "电池电压",
                value = reading?.voltageMv?.let { BatteryCalc.formatVoltage(it) } ?: "--",
                modifier = Modifier.weight(1f)
            )
        }
        StatDivider()
        Row(modifier = Modifier.fillMaxWidth()) {
            StatCell(
                label = "电流",
                value = reading?.currentMa?.let { BatteryCalc.formatCurrent(it) } ?: "--",
                modifier = Modifier.weight(1f)
            )
            StatCell(
                label = "充电功率",
                value = if (reading?.charging == true) {
                    state?.chargePowerW?.let { BatteryCalc.formatPower(it) } ?: "--"
                } else {
                    "--"
                },
                modifier = Modifier.weight(1f)
            )
        }
        StatDivider()
        Row(modifier = Modifier.fillMaxWidth()) {
            StatCell(
                label = "今天充电次数",
                value = state?.let { "${it.todayChargeCount} 次" } ?: "--",
                modifier = Modifier.weight(1f)
            )
            StatCell(
                label = "电池循环次数",
                value = reading?.cycleCount?.let { "$it 次" } ?: "系统未提供",
                modifier = Modifier.weight(1f)
            )
        }
        if (reading?.charging == false) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (geekMode) {
                    "极客版充放电均为 1 秒采样，数据同时写入 24 小时曲线。"
                } else {
                    "放电状态为低频采样；可在设置里打开「始终保持高频次监测轮询」。"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ChargeCard(state: MonitorState?) {
    val live = state?.reading?.charging == true
    val session = if (live) state?.currentCharge else state?.lastCharge
    val now = state?.updatedAt ?: 0L

    SoftCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (live) "本次充电" else "上次充电",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            if (session != null) {
                Pill(
                    text = if (live) "进行中" else "已结束",
                    color = if (live) MintSoft else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (live) Mint else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        if (session == null) {
            Text(
                text = "还没有充电记录。插上电源后会自动开始统计。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Row(modifier = Modifier.fillMaxWidth()) {
                InlineStat(
                    label = "充电时长",
                    value = BatteryCalc.formatDuration(session.durationMinutes(now)),
                    modifier = Modifier.weight(1f)
                )
                InlineStat(
                    label = "充电速度",
                    value = session.speedPctPerMin(now)
                        ?.let { BatteryCalc.formatSpeed(it) } ?: "--",
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "${session.startPct}% → ${session.endPct}%，速度按电量变化估算。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LearningCard(state: MonitorState?, onResetLearning: () -> Unit) {
    val learned = state?.learnedHealthPct
    SoftCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "学习健康度",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Pill(
                text = "${state?.learnedSamples ?: 0}/${BatteryCalc.MAX_LEARNING_SAMPLES} 个样本",
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = learned?.let { BatteryCalc.formatHealth(it) } ?: "暂无",
            style = MaterialTheme.typography.displayMedium.copy(
                fontFeatureSettings = "tnum"
            ),
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(6.dp))
        TextButton(
            onClick = onResetLearning,
            enabled = (state?.learnedSamples ?: 0) > 0,
            contentPadding = PaddingValues(horizontal = 0.dp)
        ) {
            Text("重新学习", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun StatCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.SemiBold,
                fontFeatureSettings = "tnum"
            ),
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StatDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(vertical = 10.dp)
    )
}
