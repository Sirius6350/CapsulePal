package com.capsulepal.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.capsulepal.app.data.model.iconRes
import com.capsulepal.app.domain.SummaryRange
import com.capsulepal.app.domain.SummaryStats
import com.capsulepal.app.domain.SummaryCategory
import com.capsulepal.app.domain.SummaryTrendPoint
import com.capsulepal.app.ui.components.CapsuleProgressBar
import com.capsulepal.app.ui.components.SoftCard
import com.capsulepal.app.ui.components.bounceClick
import com.capsulepal.app.ui.theme.Butter
import com.capsulepal.app.ui.theme.Coral
import com.capsulepal.app.ui.theme.Mint
import kotlin.math.roundToInt

@Composable
fun SummaryScreen(
    state: SummaryStats?,
    onBack: () -> Unit,
    onSelectRange: (SummaryRange) -> Unit,
    onShift: (Long) -> Unit,
    onToday: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
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
                            text = "← 返回待办",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = "总结",
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "完成、跳过与分类分布，都只统计本机记录",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SummaryRange.entries.forEach { range ->
                        RangeChip(
                            label = range.label,
                            selected = state?.range == range,
                            onClick = { onSelectRange(range) }
                        )
                    }
                }
            }

            if (state != null) {
                item {
                    PeriodHeader(
                        state = state,
                        onPrev = { onShift(-1) },
                        onNext = { onShift(1) },
                        onToday = onToday
                    )
                }

                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatTile(
                            label = "完成",
                            value = state.completed.toString(),
                            accent = Mint,
                            modifier = Modifier.weight(1f)
                        )
                        StatTile(
                            label = "跳过",
                            value = state.skipped.toString(),
                            accent = Butter,
                            modifier = Modifier.weight(1f)
                        )
                        StatTile(
                            label = "完成率",
                            value = "${state.percent}%",
                            accent = Coral,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                item {
                    Text(
                        text = "共 ${state.total} 条待办 · 未完成 ${state.open} 条",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                item {
                    TrendCard(trend = state.trend)
                }

                item {
                    SoftCard {
                        Text(
                            text = "分类分布",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(10.dp))
                        if (state.categories.isEmpty()) {
                            Text(
                                text = "这段时间还没有待办记录",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            state.categories.forEachIndexed { index, category ->
                                if (index > 0) Spacer(Modifier.height(12.dp))
                                CategoryRow(category)
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(12.dp).navigationBarsPadding()) }
        }
    }
}

@Composable
private fun RangeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .clip(CircleShape)
            .bounceClick(onClick = onClick),
        shape = CircleShape,
        color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surface
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Box(modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                )
            )
        }
    }
}

@Composable
private fun PeriodHeader(
    state: SummaryStats,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = periodLabel(state),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "完成率 = 完成 / 全部待办",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onClick = onPrev) { Text("‹") }
        TextButton(onClick = onToday) { Text("今天") }
        TextButton(onClick = onNext) { Text("›") }
    }
}

@Composable
private fun StatTile(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(accent)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun TrendCard(trend: List<SummaryTrendPoint>) {
    SoftCard {
        Text(
            text = "完成趋势",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(10.dp))
        if (trend.all { it.total == 0 }) {
            Text(
                text = "这段时间还没有待办记录",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            val max = trend.maxOf { it.completed }.coerceAtLeast(1)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                trend.forEach { point ->
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.BottomCenter
                        ) {
                            if (point.completed > 0) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .fillMaxHeight(
                                            (point.completed.toFloat() / max).coerceIn(0.08f, 1f)
                                        )
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Mint)
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = point.completed.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = point.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryRow(category: SummaryCategory) {
    val percent = if (category.total == 0) {
        0
    } else {
        (category.completed * 100.0 / category.total).roundToInt()
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painter = painterResource(category.category.iconRes()),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = category.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "完成 ${category.completed} / 共 ${category.total} · $percent%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(5.dp))
            CapsuleProgressBar(
                progress = category.fraction,
                modifier = Modifier.fillMaxWidth(),
                color = Mint,
                height = 5.dp
            )
        }
    }
}

private fun periodLabel(state: SummaryStats): String = when (state.range) {
    SummaryRange.DAY -> "${state.start.monthValue} 月 ${state.start.dayOfMonth} 日"
    SummaryRange.WEEK ->
        "${state.start.monthValue} 月 ${state.start.dayOfMonth} 日 – " +
            "${state.end.monthValue} 月 ${state.end.dayOfMonth} 日"
    SummaryRange.MONTH -> "${state.anchor.year} 年 ${state.anchor.monthValue} 月"
    SummaryRange.YEAR -> "${state.anchor.year} 年"
}
