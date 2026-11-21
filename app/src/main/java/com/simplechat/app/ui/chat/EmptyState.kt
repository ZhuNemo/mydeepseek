package com.simplechat.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.simplechat.app.R
import com.simplechat.app.ui.common.AppIcons

/**
 * 空态：一块**带底色的圆角方** + 一句话。
 *
 * 图标不再裸放在页面中央，而是坐在一块 96dp、32dp 圆角的
 * `surfaceContainer` 色块里 —— 空白的页面需要一件"有体积"的东西压住；
 * 一个 44dp 的线框图标摆在正中，只会让这块空白显得更空。
 *
 * 刻意**不写说明小字**：空态该做的是提示"这里能干什么"，一句话够了；
 * 再加一行解释只会让这块空白显得更空。
 *
 * **暂不暴露模式切换** —— 扮演模式是远期功能，现在把「创作 / 扮演」两个
 * 并列摆出来，等于让用户在两件当前不等价的事情之间做选择。
 * 默认创作模式，`ConversationMode` 保留给以后。
 */
@Composable
fun EmptyState(
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .background(MaterialTheme.colorScheme.surfaceContainer, IconContainerShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = AppIcons.MessageSquare,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(44.dp),
            )
        }

        Spacer(Modifier.size(24.dp))

        Text(
            text = stringResource(R.string.empty_greeting),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 图标底座的圆角。
 *
 * 32dp 是"一块方形色块"与"一颗圆角矩形"的分界：96dp 的方块配 32dp 圆角，
 * 四条边还剩 32dp 的笔直段，形状一眼认得出来；再大就成胶囊了。
 */
private val IconContainerShape = RoundedCornerShape(32.dp)
