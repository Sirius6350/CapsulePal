package com.capsulepal.app.ui

import android.widget.Toast
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.capsulepal.app.R
import com.capsulepal.app.data.repo.InstantTodo
import kotlinx.coroutines.delay

/**
 * 即时待办输入弹窗。
 *
 * 以前由透明 Activity 承载，冷启动时窗口会先铺一层黑底再变透明，切换时明显抽搐；
 * 现在统一在应用内弹窗，桌面组件与长按菜单都走这里。
 */
@Composable
fun InstantCaptureDialog(
    existing: InstantTodo?,
    onCreate: (String) -> Boolean,
    onComplete: () -> Unit,
    onDismiss: () -> Unit
) {
    if (existing != null) {
        ExistingInstantDialog(todo = existing, onComplete = onComplete, onDismiss = onDismiss)
    } else {
        CaptureDialog(onCreate = onCreate, onDismiss = onDismiss, onDone = onDismiss)
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
    val context = LocalContext.current
    val confirm = {
        if (title.isNotBlank()) {
            if (onCreate(title)) {
                onDone()
            } else {
                Toast.makeText(
                    context,
                    "只能创建一个即时待办，请先完成旧的即时待办",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
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
