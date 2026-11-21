package com.capsulepal.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.capsulepal.app.R
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.capsulepal.app.domain.DailyRollup
import com.capsulepal.app.domain.GrowthRules
import com.capsulepal.app.partner.PartnerAppearance
import com.capsulepal.app.partner.PartnerMood
import com.capsulepal.app.partner.PartnerOutfit
import com.capsulepal.app.ui.CapsuleUiState
import com.capsulepal.app.ui.components.CapsuleProgressBar
import com.capsulepal.app.ui.components.InlineStat
import com.capsulepal.app.ui.components.PartnerFace
import com.capsulepal.app.ui.components.Pill
import com.capsulepal.app.ui.components.SoftCard
import com.capsulepal.app.ui.components.bounceClick
import com.capsulepal.app.ui.theme.Butter
import com.capsulepal.app.ui.theme.Coral
import com.capsulepal.app.ui.theme.Mint
import java.time.LocalTime

private data class Unlock(val level: Int, val title: String, val detail: String)

private val unlocks = listOf(
    Unlock(1, "初识", "刚开始一起生活"),
    Unlock(3, "默契", "已经习惯彼此的节奏"),
    Unlock(5, "同行", "每天都会见上一面"),
    Unlock(7, "搭子", "配合得越来越顺"),
    Unlock(9, "守护者", "重要的日子从不缺席"),
    Unlock(12, "传说伙伴", "一起走了很久很久")
)

@Composable
fun PartnerScreen(
    state: CapsuleUiState,
    charging: Boolean,
    onRename: (String) -> Unit,
    onToggleAppearance: (PartnerAppearance, Boolean) -> Unit
) {
    val partner = state.partner
    val levelInfo = remember(partner.totalXp) { GrowthRules.levelInfo(partner.totalXp) }
    val hour = LocalTime.now().hour
    val mood = when {
        state.active != null -> PartnerMood.FOCUS
        charging -> PartnerMood.CHARGING
        hour >= 23 || hour < 6 -> PartnerMood.SLEEPY
        else -> PartnerMood.IDLE
    }
    val autoOutfit = remember(state.date, state.holidayName, mood, charging) {
        PartnerOutfit.auto(state.date, state.holidayName, mood, charging)
    }
    var editingName by remember { mutableStateOf(false) }
    var nameDraft by remember { mutableStateOf(partner.name) }
    var showTitleLadder by remember { mutableStateOf(false) }
    val shownOutfit = autoOutfit

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "伙伴",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "已陪伴你 ${partner.companionDays} 天，完成 ${partner.completedTotal} 件事了",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(206.dp)
                        .clip(CircleShape)
                        .background(partnerBackdrop(shownOutfit)),
                    contentAlignment = Alignment.Center
                ) {
                    PartnerFace(
                        mood = mood,
                        size = 176.dp,
                        appearance = state.settings.partnerAppearance,
                        outfit = shownOutfit,
                        charging = charging,
                        progress = DailyRollup.progress(state.tasks)
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = partner.name,
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .bounceClick {
                                nameDraft = partner.name
                                editingName = true
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_ui_edit),
                            contentDescription = "改名字",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Pill(
                    text = "Lv.${levelInfo.level} ${levelInfo.title} · ${mood.label}",
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }

        item {
            SoftCard {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = "成长",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = if (levelInfo.isMax) {
                            "已满级"
                        } else {
                            "${levelInfo.xpIntoLevel} / ${levelInfo.xpForNextLevel}"
                        },
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFeatureSettings = "tnum"
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(10.dp))
                CapsuleProgressBar(
                    progress = levelInfo.progress,
                    color = Mint,
                    height = 8.dp
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (levelInfo.isMax) {
                        "已经到最高等级，之后只记录陪伴的日子。"
                    } else {
                        "还差 ${(levelInfo.xpForNextLevel - levelInfo.xpIntoLevel).coerceAtLeast(0)} 成长到 Lv.${levelInfo.level + 1}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    InlineStat(
                        value = "${partner.completedTotal}",
                        label = "累计完成",
                        modifier = Modifier.weight(1f)
                    )
                    InlineStat(
                        value = "${partner.completedToday}",
                        label = "今日完成",
                        modifier = Modifier.weight(1f)
                    )
                    InlineStat(
                        value = "${partner.totalXp}",
                        label = "总成长",
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        item {
            SoftCard {
                Text(
                    text = "形象",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PartnerAppearance.entries.forEach { option ->
                        val checked = option in state.settings.partnerAppearance
                        FilterChip(
                            selected = checked,
                            onClick = { onToggleAppearance(option, !checked) },
                            label = { Text(text = option.label) }
                        )
                    }
                }
            }
        }

        item {
            SoftCard(
                modifier = Modifier.bounceClick(shape = RoundedCornerShape(26.dp)) {
                    showTitleLadder = true
                }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "称号阶梯",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        painter = painterResource(R.drawable.ic_ui_chevron_right),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }

    if (showTitleLadder) {
        AlertDialog(
            onDismissRequest = { showTitleLadder = false },
            title = { Text("称号阶梯") },
            text = {
                Column {
                    unlocks.forEach { unlock ->
                        UnlockRow(
                            unlock = unlock,
                            unlocked = levelInfo.level >= unlock.level
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTitleLadder = false }) { Text("知道了") }
            }
        )
    }

    if (editingName) {
        AlertDialog(
            onDismissRequest = { editingName = false },
            title = { Text("给它改个名字") },
            text = {
                OutlinedTextField(
                    value = nameDraft,
                    onValueChange = { nameDraft = it.take(8) },
                    singleLine = true,
                    placeholder = { Text("小胶囊") }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRename(nameDraft)
                        editingName = false
                    }
                ) { Text("就叫这个") }
            },
            dismissButton = {
                TextButton(onClick = { editingName = false }) { Text("算了") }
            }
        )
    }
}

@Composable
private fun UnlockRow(unlock: Unlock, unlocked: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(
                    if (unlocked) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            if (unlocked) {
                Icon(
                    painter = painterResource(R.drawable.ic_ui_check),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(16.dp)
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.ic_ui_lock),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(14.dp)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Lv.${unlock.level} ${unlock.title}",
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (unlocked) FontWeight.SemiBold else FontWeight.Normal
                ),
                color = if (unlocked) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Text(
                text = unlock.detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun partnerBackdrop(outfit: PartnerOutfit?): Brush {
    val colors = when (outfit) {
        PartnerOutfit.NEW_YEAR -> listOf(Color(0xFFFFF0D6), Color(0xFFFFD9C0))
        PartnerOutfit.SPRING_FESTIVAL -> listOf(Color(0xFFFFE9DC), Color(0xFFFFC6B0))
        PartnerOutfit.QINGMING -> listOf(Color(0xFFE9F3EC), Color(0xFFCFE4D7))
        PartnerOutfit.LABOR_DAY -> listOf(Color(0xFFFFF2D8), Color(0xFFF6DCB0))
        PartnerOutfit.DRAGON_BOAT -> listOf(Color(0xFFE9F2E6), Color(0xFFCFE3D2))
        PartnerOutfit.MID_AUTUMN -> listOf(Color(0xFFEDF0FA), Color(0xFFD3DCF0))
        PartnerOutfit.NATIONAL_DAY -> listOf(Color(0xFFFFE6DC), Color(0xFFFFC3B3))
        PartnerOutfit.CHRISTMAS -> listOf(Color(0xFFE9F4EC), Color(0xFFF7DDD7))
        PartnerOutfit.HALLOWEEN -> listOf(Color(0xFFF3E8F5), Color(0xFFFFDFC0))
        PartnerOutfit.NIGHT_CAP -> listOf(Color(0xFFEAEDF7), Color(0xFFD3DAEB))
        PartnerOutfit.FOCUS_GOGGLES -> listOf(Color(0xFFE6F2EC), Color(0xFFCFE6DC))
        PartnerOutfit.CONFETTI -> listOf(Color(0xFFFFF2DE), Color(0xFFFFDFC8))
        PartnerOutfit.CHARGE_BOLT -> listOf(Color(0xFFFFF5DD), Color(0xFFFCE2B6))
        null -> listOf(
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.50f),
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.24f)
        )
    }
    return Brush.linearGradient(colors)
}
