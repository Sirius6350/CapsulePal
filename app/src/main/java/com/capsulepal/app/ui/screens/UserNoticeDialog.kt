package com.capsulepal.app.ui.screens

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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.capsulepal.app.R
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.capsulepal.app.ui.components.CapsuleButton

private val noticeHighlights = listOf("Android16", "本地离线", "任何", "异常高")

/**
 * 用户须知：首次启动必须确认；设置页「关于」里可再次打开。
 */
@Composable
fun UserNoticeDialog(
    mustAccept: Boolean,
    onAccept: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = { if (!mustAccept) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = !mustAccept,
            dismissOnClickOutside = !mustAccept
        )
    ) {
        val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.8f).dp
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .heightIn(max = maxHeight),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 18.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "用户须知",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = "使用前请完整阅读以下内容",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (!mustAccept) {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                painter = painterResource(R.drawable.ic_ui_close),
                                contentDescription = "关闭",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                ) {
                    NoticeParagraph(
                        text = "1. 欢迎使用“胶囊伙伴”，这是一款面向Android16及以上系统版本的轻量本地离线应用，" +
                            "未申请任何网络权限。当您发现应用出现任何流量消耗或异常高的存储占用时，" +
                            "请立刻停止使用并卸载。"
                    )
                    Spacer(Modifier.height(14.dp))
                    NoticeParagraph(
                        text = "2. 为确保能完整使用该应用内的核心功能，" +
                            "请确保您的设备搭载的是Android16及以上的系统版本。"
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Spacer(Modifier.height(14.dp))
                CapsuleButton(
                    text = if (mustAccept) "我已阅读并同意" else "知道了",
                    onClick = if (mustAccept) onAccept else onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    height = 50.dp
                )
            }
        }
    }
}

@Composable
private fun NoticeParagraph(text: String) {
    val accent = MaterialTheme.colorScheme.primary
    val annotated = remember(text, accent) { highlight(text, accent) }
    Text(
        text = annotated,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onBackground
    )
}

private fun highlight(raw: String, accent: androidx.compose.ui.graphics.Color): AnnotatedString =
    buildAnnotatedString {
        var cursor = 0
        while (cursor < raw.length) {
            val next = noticeHighlights
                .mapNotNull { word ->
                    raw.indexOf(word, cursor).takeIf { it >= 0 }?.let { it to word }
                }
                .minByOrNull { it.first }
            if (next == null) {
                append(raw.substring(cursor))
                break
            }
            append(raw.substring(cursor, next.first))
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = accent)) {
                append(next.second)
            }
            cursor = next.first + next.second.length
        }
    }
