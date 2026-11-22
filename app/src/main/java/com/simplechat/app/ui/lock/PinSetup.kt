package com.simplechat.app.ui.lock

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.simplechat.app.R
import com.simplechat.app.data.Res

/**
 * 设置应用锁密码（两步：输入 → 确认）。
 *
 * ### 为什么是一整屏 Dialog 而不是普通弹窗
 *
 * 数字键盘本身要占满下半屏（80dp 的键 × 4 行），塞进居中的卡片里会挤成一团、
 * 还会被输入法顶得跳。全屏 Dialog 也是系统应用锁的做法 —— 这一步值得"独占"。
 *
 * 失败（两次不一致）时回到第一步重来，并保留错误提示：不做"只重输第二位"，
 * 那种流程用户根本记不住自己刚才输的是哪一遍。
 */
@Composable
fun PinSetupFlow(
    onDone: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var first by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            if (first == null) {
                PinLockScreen(
                    title = Res.get(R.string.lock_set_title),
                    subtitle = Res.get(R.string.lock_pin_hint),
                    error = error,
                    onPinComplete = { pin ->
                        first = pin
                        error = null
                    },
                )
            } else {
                PinLockScreen(
                    title = Res.get(R.string.lock_confirm_title),
                    error = error,
                    onPinComplete = { pin ->
                        if (pin == first) {
                            onDone(pin)
                        } else {
                            first = null
                            error = Res.get(R.string.lock_mismatch)
                        }
                    },
                )
            }
        }
    }
}
