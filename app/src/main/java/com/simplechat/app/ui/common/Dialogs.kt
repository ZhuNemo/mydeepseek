package com.simplechat.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.simplechat.app.R
import com.simplechat.app.ui.common.AppIcons
import com.simplechat.app.ui.theme.LocalChatColors

/** 通用文本输入弹窗（重命名 / 编辑等）。 */
@Composable
fun AppTextInputDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    placeholder: String = "",
) {
    val colors = LocalChatColors.current
    var value by remember { mutableStateOf(initial) }
    val focusRequester = remember { FocusRequester() }
    val shape = PillShape

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Dialog(
        onDismissRequest = onDismiss,
        // 见 AiEditDialog 里同一处的注释：不吃掉 inset，imePadding() 才有值
        properties = DialogProperties(decorFitsSystemWindows = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .clip(ContainerShape)
                .background(colors.card)
                .padding(20.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.size(14.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(colors.fieldBackground)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    singleLine = true,
                    decorationBox = { inner ->
                        Box {
                            if (value.isEmpty() && placeholder.isNotEmpty()) {
                                Text(
                                    text = placeholder,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.placeholder,
                                )
                            }
                            inner()
                        }
                    },
                )
            }

            Spacer(Modifier.size(18.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                DialogTextAction(stringResource(R.string.action_cancel), onClick = onDismiss)
                Spacer(Modifier.size(8.dp))
                DialogTextAction(stringResource(R.string.action_save), primary = true, onClick = { onConfirm(value) })
            }
        }
    }
}

/**
 * 通用确认弹窗（删除等破坏性操作）。
 *
 * ### 为什么不用 `AlertDialog`
 *
 * 早先换成 M3 的 `AlertDialog` 是为了"右下角文字按钮 + 28dp 圆角"这套规范。
 * 但 M3 Expressive 之后，**破坏性确认**这一档的主流做法是：
 * 图标 + 标题在上，正文在下，**两个大按钮竖着占满宽度**（主操作实心、取消是文字）。
 * 文字按钮在拇指够得着的右下角固然克规，可"删除一条会话"是不可撤销的动作，
 * 让人去点一个小字，本来就不合适。
 *
 * 所以这里自绘：形状、字号、间距都取主题 token，交互与无障碍语义保持不变
 * （两个入口都是 `clickable`，读屏念得到）。
 *
 * @param extraContent 标题 / 正文与按钮之间的自定义内容（勾选框、说明等）。
 */
@Composable
fun AppConfirmDialog(
    title: String,
    message: String? = null,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    destructive: Boolean = false,
    extraContent: (@Composable () -> Unit)? = null,
) {
    val colors = LocalChatColors.current
    val accent = if (destructive) colors.danger else MaterialTheme.colorScheme.primary
    val accentContainer = if (destructive) {
        colors.danger.copy(alpha = 0.14f)
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(accentContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (destructive) AppIcons.Trash else AppIcons.Info,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.size(14.dp))
                Text(
                    text = title,
                    // titleLarge 是 M3 对话框标题的档位；这里同时把行高收紧一点，
                    // 长标题折两行时不至于把正文挤走
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            if (message != null) {
                Spacer(Modifier.size(12.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            extraContent?.let {
                Spacer(Modifier.size(14.dp))
                it()
            }

            Spacer(Modifier.size(24.dp))

            /*
             * 主操作：官方 [FilledTonalButton]，占满宽度、52dp 高。
             *
             * 用 tonal 而不是 filled：这是**确认**，不是"主行动"；tonal 的对比度
             * 足够让人一眼找到，又不会在屏幕上抢走正文。破坏性操作把容器换成
             * 危险色 —— 仍然走同一个组件，只是换配色，形状与尺寸完全一致。
             */
            FilledTonalButton(
                onClick = onConfirm,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = PillShape,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = if (destructive) {
                        colors.danger
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
                    contentColor = if (destructive) {
                        Color.White
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    },
                ),
            ) {
                Text(
                    text = confirmText,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                )
            }

            Spacer(Modifier.size(8.dp))

            // 取消：官方 TextButton，同样占满宽度（容易点中，又不抢注意力）
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = PillShape,
            ) {
                Text(
                    text = stringResource(R.string.action_cancel),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DialogTextAction(
    text: String,
    onClick: () -> Unit,
    primary: Boolean = false,
) {
    val colors = LocalChatColors.current
    Box(
        modifier = Modifier
            .clip(PillShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = if (primary) MaterialTheme.colorScheme.primary else colors.processText,
        )
    }
}
