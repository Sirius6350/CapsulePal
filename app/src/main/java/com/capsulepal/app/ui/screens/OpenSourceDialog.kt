package com.capsulepal.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.capsulepal.app.R
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.capsulepal.app.ui.components.CapsuleButton
import com.capsulepal.app.ui.components.bounceClick

/**
 * 开源技术声明：只列运行时真正打包进 APK 的组件，点击展开许可证与版权声明。
 */
private data class OpenSourceEntry(
    val name: String,
    val usage: String,
    val license: String,
    val notice: String
)

private val openSourceEntries = listOf(
    OpenSourceEntry(
        name = "Kotlin 标准库 / kotlinx.coroutines",
        usage = "开发语言与协程运行时",
        license = "Apache-2.0",
        notice = "Copyright © JetBrains s.r.o. 依据 Apache License 2.0 授权使用；" +
            "按「现状」提供，不附带任何明示或暗示担保。许可证全文见 https://www.apache.org/licenses/LICENSE-2.0"
    ),
    OpenSourceEntry(
        name = "Jetpack Compose / Material 3",
        usage = "界面框架与组件体系",
        license = "Apache-2.0",
        notice = "Copyright © The Android Open Source Project. 依据 Apache License 2.0 授权使用；" +
            "按「现状」提供，不附带任何明示或暗示担保。许可证全文见 https://www.apache.org/licenses/LICENSE-2.0"
    ),
    OpenSourceEntry(
        name = "AndroidX Core / Activity",
        usage = "兼容层与页面容器",
        license = "Apache-2.0",
        notice = "Copyright © The Android Open Source Project. 依据 Apache License 2.0 授权使用；" +
            "按「现状」提供，不附带任何明示或暗示担保。许可证全文见 https://www.apache.org/licenses/LICENSE-2.0"
    ),
    OpenSourceEntry(
        name = "AndroidX Lifecycle",
        usage = "生命周期感知与状态管理",
        license = "Apache-2.0",
        notice = "Copyright © The Android Open Source Project. 依据 Apache License 2.0 授权使用；" +
            "按「现状」提供，不附带任何明示或暗示担保。许可证全文见 https://www.apache.org/licenses/LICENSE-2.0"
    ),
    OpenSourceEntry(
        name = "AndroidX Room",
        usage = "本地数据库",
        license = "Apache-2.0",
        notice = "Copyright © The Android Open Source Project. 依据 Apache License 2.0 授权使用；" +
            "按「现状」提供，不附带任何明示或暗示担保。许可证全文见 https://www.apache.org/licenses/LICENSE-2.0"
    ),
    OpenSourceEntry(
        name = "AndroidX DataStore",
        usage = "偏好与设置存储",
        license = "Apache-2.0",
        notice = "Copyright © The Android Open Source Project. 依据 Apache License 2.0 授权使用；" +
            "按「现状」提供，不附带任何明示或暗示担保。许可证全文见 https://www.apache.org/licenses/LICENSE-2.0"
    ),
    OpenSourceEntry(
        name = "6tail lunar（农历库）",
        usage = "农历、节气与传统节日计算",
        license = "MIT",
        notice = "Copyright © 6tail. 依据 MIT License 授权使用，可自由使用、修改与分发，" +
            "需保留版权与许可声明；按「现状」提供，不附带任何担保。许可证全文见 https://opensource.org/licenses/MIT"
    )
)

@Composable
fun OpenSourceDialog(onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.82f),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 22.dp, vertical = 18.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "开源技术",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = "本应用使用了以下开源技术与资源，点击可查看许可证与版权声明。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            painter = painterResource(R.drawable.ic_ui_close),
                            contentDescription = "关闭",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(openSourceEntries, key = { it.name }) { entry ->
                        OpenSourceRow(entry)
                    }
                    item {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "以上组件均随安装包本地打包，应用未申请网络权限，运行时不下载任何代码或资源。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                CapsuleButton(
                    text = "关闭",
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    height = 50.dp
                )
            }
        }
    }
}

@Composable
private fun OpenSourceRow(entry: OpenSourceEntry) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .bounceClick(shape = RoundedCornerShape(14.dp)) { expanded = !expanded }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${entry.usage} · ${entry.license}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                painter = painterResource(R.drawable.ic_ui_chevron_right),
                contentDescription = if (expanded) "收起" else "展开",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(if (expanded) 90f else 0f)
            )
        }
        if (expanded) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
            ) {
                Text(
                    text = entry.notice,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    }
}
