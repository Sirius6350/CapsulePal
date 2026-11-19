package com.capsulepal.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.capsulepal.app.monitor.BatteryCalc
import com.capsulepal.app.monitor.CapacitySource

/**
 * 修改额定容量与电池芯数。
 * 额定容量传 0 表示恢复自动；芯数 0 自动、1 单芯、2 双芯。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RatedCapacityDialog(
    ratedIsAuto: Boolean,
    ratedCapacityMah: Int,
    systemDesignRawMah: Int?,
    designVoltageMv: Int?,
    rawVoltageMv: Int?,
    energyDesignMah: Int?,
    batteryCellsSetting: Int,
    ratedSource: CapacitySource,
    onDismiss: () -> Unit,
    onConfirm: (mah: Int, cells: Int) -> Unit
) {
    var text by remember {
        mutableStateOf(if (ratedIsAuto) "" else ratedCapacityMah.toString())
    }
    var cells by remember { mutableStateOf(batteryCellsSetting.coerceIn(0, 2)) }

    val parsed = text.toIntOrNull()
    val valid = text.isBlank() || (parsed != null && parsed in 1000..20000)

    val correction = BatteryCalc.resolveCorrection(
        cells,
        systemDesignRawMah,
        energyDesignMah,
        rawVoltageMv
    )
    val correctedDesign = when {
        correction.cells != 2 -> systemDesignRawMah
        energyDesignMah != null -> energyDesignMah
        else -> BatteryCalc.scaledCapacity(systemDesignRawMah, correction.capacityScale)
    }
    val correctedVoltage = designVoltageMv?.let {
        if (correction.voltageScale == 2) it * 2 else it
    }
    val correctionNote = when {
        correction.cells != 2 -> null
        correction.capacityScale == 2 && correction.voltageScale == 2 ->
            "系统只上报了单芯容量和电压，按双芯 ×2 校正。"
        correction.capacityScale == 2 ->
            "系统只上报了单芯容量，容量按两芯相加；电压已是整包。"
        else -> "系统上报的容量已是整包等效值，不再 ×2。"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("电池容量与芯数") },
        text = {
            Column {
                Text(
                    text = "系统只上报单芯容量的机型，可在这里按双芯校正。" +
                        "自动模式会在设计容量不超过 " +
                        "${BatteryCalc.AUTO_DUAL_CELL_MAX_MAH} mAh 时按双芯处理。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0 to "自动", 1 to "单芯", 2 to "双芯").forEach { (value, label) ->
                        FilterChip(
                            selected = cells == value,
                            onClick = { cells = value },
                            label = { Text(label) }
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                if (systemDesignRawMah != null) {
                    Text(
                        text = buildString {
                            append("系统原始：")
                            append(systemDesignRawMah)
                            append(" mAh")
                            designVoltageMv?.let {
                                append(" / ")
                                append(BatteryCalc.formatVoltage(it))
                            }
                            append("\n校正后：")
                            append(correctedDesign ?: systemDesignRawMah)
                            append(" mAh")
                            correctedVoltage?.let {
                                append(" / ")
                                append(BatteryCalc.formatVoltage(it))
                            }
                            if (correction.cells == 2) append("（双芯）")
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                } else {
                    Text(
                        text = "未读到系统设计容量，可手动填写，或等待学习估算。",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(4.dp))
                if (correctionNote != null) {
                    Text(
                        text = correctionNote,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                }
                Text(
                    text = "当前来源：${ratedSource.label}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { input ->
                        text = input.filter { it.isDigit() }.take(5)
                    },
                    label = { Text("额定容量（mAh）") },
                    placeholder = { Text("留空表示自动") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(
                    onClick = { onConfirm(0, cells) },
                    contentPadding = PaddingValues(horizontal = 0.dp)
                ) {
                    Text("恢复自动", style = MaterialTheme.typography.labelMedium)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onConfirm(parsed ?: 0, cells) }) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
