package com.simplechat.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.simplechat.app.R
import com.simplechat.app.ui.common.AppIcons

/**
 * 顶栏：`☰` 标题 ｜ 自动朗读 · 新建对话。设置入口在抽屉底部。
 *
 * ### 尺寸按 M3 的 Small top app bar
 *
 * 高度 **64dp**（不是早期那版 56dp），标题用 `titleLarge`，两侧动作是
 * **填充式圆形图标按钮**（40dp 色块 + 48dp 触控区）。
 *
 * 这是 M3 里"顶栏"和"随便一行图标 + 文字"最明显的分界：
 * 图标要么裸放（iOS 风格），要么放进一块**有底色的圆**（M3 的 filled icon button）。
 * 后者让顶栏在纯白 / 纯黑的正文上方有一层清楚的层次，也不会让图标看起来在飘。
 */
@Composable
fun ChatTopBar(
    title: String,
    onMenu: () -> Unit,
    onNewChat: () -> Unit,
    modifier: Modifier = Modifier,
    autoReadAloud: Boolean = false,
    readAloudAvailable: Boolean = true,
    onToggleAutoReadAloud: () -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(64.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TopBarAction(AppIcons.Menu, stringResource(R.string.topbar_history), onMenu)
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 6.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (readAloudAvailable) {
            val label = stringResource(if (autoReadAloud) R.string.auto_read_aloud_on else R.string.auto_read_aloud_off)
            TopBarAction(
                icon = if (autoReadAloud) AppIcons.Volume else AppIcons.VolumeOff,
                contentDescription = label,
                onClick = onToggleAutoReadAloud,
                // 开着的时候用主色 —— 与"已选中"的语义一致，不额外加一层底色
                tint = if (autoReadAloud) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                state = label,
            )
        }
        // 与长按菜单的「从这里新建对话」同一个图标 —— 同一个动作，同一个符号。
        // 圆圈加号留给输入栏的附件按钮（⊕ 加图片/文件），两者别混用。
        TopBarAction(AppIcons.MessageSquarePlus, stringResource(R.string.topbar_new_chat), onNewChat)
    }
}

/**
 * 顶栏动作：**填充式圆形图标按钮**。
 *
 * 结构是 M3 的两层：48dp 的触控区（吃掉水波纹，是圆的）+ 里面 40dp 的色块。
 * 不能把底色直接画在 48dp 那层上 —— 那样按钮会比旁边的标题高出一截，
 * 水波纹也会从色块边缘溢出来。
 */
@Composable
private fun TopBarAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    state: String? = null,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .semantics { if (state != null) stateDescription = state }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                // surfaceContainerHigh：浅色下是浅灰、深色下比页面亮一档，两套主题都看得见
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
