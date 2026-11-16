package com.capsulepal.app.widget

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.capsulepal.app.CapsuleApp
import com.capsulepal.app.R
import com.capsulepal.app.data.repo.InstantTodo
import com.capsulepal.app.ui.theme.CapsuleTheme
import kotlinx.coroutines.delay

/**
 * 桌面组件「即时待办入口」的输入页：透明 Activity + 居中弹窗。
 * 已有即时待办时不覆盖，只提供「完成 / 取消」。
 */
class InstantTodoCaptureActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as CapsuleApp).container
        val existing = container.instantTodoRepository.current()
        setContent {
            CapsuleTheme {
                if (existing != null) {
                    ExistingInstantDialog(
                        todo = existing,
                        onComplete = {
                            container.completeInstantTodo()
                            finish()
                        },
                        onDismiss = { finish() }
                    )
                } else {
                    CaptureDialog(
                        onCreate = { title ->
                            val created = container.createInstantTodo(title)
                            if (!created) {
                                Toast.makeText(
                                    this,
                                    "只能创建一个即时待办，请先完成旧的即时待办",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            created
                        },
                        onDismiss = { finish() },
                        onDone = { finish() }
                    )
                }
            }
        }
    }
}

@Composable
private fun CaptureDialog(
    onCreate: (String) -> Boolean,
    onDismiss: () -> Unit,
    onDone: () -> Unit
) {
    var title by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val confirm = {
        if (title.isNotBlank() && onCreate(title)) onDone()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.widget_instant_capture_title)) },
        text = {
            Column {
                Text(
                    text = "创建后立即上岛，直到完成。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(40) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    placeholder = { Text(stringResource(R.string.widget_instant_capture_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { confirm() })
                )
            }
        },
        confirmButton = {
            TextButton(onClick = confirm, enabled = title.isNotBlank()) { Text("创建") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
    LaunchedEffect(Unit) {
        // 等弹窗完成布局后再聚焦，输入法才会自动抬起。
        delay(150)
        focusRequester.requestFocus()
        keyboard?.show()
    }
}

@Composable
private fun ExistingInstantDialog(
    todo: InstantTodo,
    onComplete: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("已有即时待办") },
        text = { Text("「${todo.title}」还没完成，先收尾这一件吧。") },
        confirmButton = {
            TextButton(onClick = onComplete) { Text("完成") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
