package com.capsulepal.app

import android.app.ActivityManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import com.capsulepal.app.data.model.CapsulePage
import com.capsulepal.app.ui.AppRoot
import com.capsulepal.app.ui.theme.CapsuleTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** 桌面组件点进来时希望落到的页面；消费后清空。 */
    private val requestedPage = MutableStateFlow<CapsulePage?>(null)

    /** 长按图标快捷方式（创建待办 / 即时上岛）；消费后清空。 */
    private val requestedAction = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as CapsuleApp).container
        lifecycleScope.launch {
            container.settingsRepository.settings.collect { settings ->
                applyRecentsVisibility(settings.hiddenBackground)
            }
        }
        handlePageIntent(intent)
        handleActionIntent(intent)
        setContent {
            val page by requestedPage.collectAsState()
            val action by requestedAction.collectAsState()
            CapsuleTheme {
                AppRoot(
                    requestedPage = page,
                    onRequestHandled = { requestedPage.value = null },
                    quickAction = action,
                    onQuickActionHandled = { requestedAction.value = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePageIntent(intent)
        handleActionIntent(intent)
    }

    private fun handlePageIntent(intent: Intent?) {
        val name = intent?.getStringExtra(EXTRA_WIDGET_PAGE) ?: return
        intent.removeExtra(EXTRA_WIDGET_PAGE)
        requestedPage.value = CapsulePage.entries.firstOrNull { it.name == name } ?: return
    }

    private fun handleActionIntent(intent: Intent?) {
        val action = intent?.action ?: return
        if (action != ACTION_CREATE_TODO && action != ACTION_INSTANT_CAPTURE) return
        // 清掉 action，避免旋转屏幕重建时重复弹窗。
        intent.action = null
        requestedAction.value = action
    }

    /** 隐藏后台：把当前任务从最近任务列表移除；关闭后再放回来。 */
    private fun applyRecentsVisibility(hidden: Boolean) {
        val manager = getSystemService(ActivityManager::class.java) ?: return
        runCatching {
            val task = manager.appTasks.firstOrNull { it.taskInfo.taskId == taskId }
                ?: manager.appTasks.firstOrNull()
            task?.setExcludeFromRecents(hidden)
        }
    }

    override fun onResume() {
        super.onResume()
        (application as CapsuleApp).container.onForeground(true)
    }

    override fun onPause() {
        (application as CapsuleApp).container.onForeground(false)
        super.onPause()
    }

    companion object {
        /** 桌面组件携带的目标页面名（CapsulePage.name）。 */
        const val EXTRA_WIDGET_PAGE = "com.capsulepal.app.extra.WIDGET_PAGE"

        /** 长按图标「创建待办」：落到待办页并直接打开新建窗口。 */
        const val ACTION_CREATE_TODO = "com.capsulepal.app.action.CREATE_TODO"

        /** 长按图标「即时上岛」：在应用内弹出现即时待办输入框。 */
        const val ACTION_INSTANT_CAPTURE = "com.capsulepal.app.action.INSTANT_CAPTURE"
    }
}
