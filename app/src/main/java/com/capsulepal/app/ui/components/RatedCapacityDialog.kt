package com.capsulepal.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
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

/**
 * 修改额定容量。传 0 表示恢复自动（优先系统设计容量，读不到时 5000mAh）。
 */
@Composable
fun RatedCapacityDialog(
    ratedIsAuto: Boolean,
    ratedCapacityMah: Int,
    systemDesignMah: Int?,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var text by remember {
        mutableStateOf(if (ratedIsAuto) "" else ratedCapacityMah.toString())
    }
    val parsed = text.toIntOrNull()
    val valid = parsed != null && parsed in 1000..20000

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改额定容量") },
        text = {
            Column {
                Text(
                    text = "额定容量用于估算电池健康度。自动模式优先读取系统设计容量，" +
                        "读不到时按 ${BatteryCalc.DEFAULT_RATED_MAH} mAh 计算。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { input ->
                        text = input.filter { it.isDigit() }.take(5)
                    },
                    label = { Text("额定容量（mAh）") },
                    placeholder = { Text("例如 5000") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = systemDesignMah?.let { "系统设计容量：$it mAh" }
                        ?: "未读到系统设计容量，当前回落 ${BatteryCalc.DEFAULT_RATED_MAH} mAh",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(
                    onClick = { onConfirm(0) },
                    contentPadding = PaddingValues(horizontal = 0.dp)
                ) {
                    Text("恢复自动", style = MaterialTheme.typography.labelMedium)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { parsed?.let(onConfirm) }) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
