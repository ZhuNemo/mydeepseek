package com.simplechat.app.ui.common

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 「加载中」指示器：**会变形的容器 + 里面绕着走的一颗点**。
 *
 * ### 为什么自己画，而不是用 Material 的转圈
 *
 * 平台给的那颗 `CircularProgressIndicator` 是**一个弧长会伸缩的圆环**，
 * 在 Material 3 里它属于"进度环"，表达的是"这件事有个进度，正在走"。
 * 而这里的场景（等模型第一个 token、切会话读库）**没有进度可言** ——
 * 只有"还没好"。
 *
 * Material 3 Expressive 把这类场景单列了一档：**contained loading indicator**，
 * 形态是一个会**变形**的容器（正圆 ↔ 圆角方），里面有一块在绕着走的内容。
 * 本项目的 Compose BOM（2026.09.00）解析到的 material3 是 1.4.0，
 * 这一档组件还没有（`ContainedLoadingIndicator` 不存在），所以按它的形态自己画。
 *
 * ### 两个参数决定观感
 *
 * - **容器**：圆角半径在"正圆"与"圆角方"之间往返（[MorphPeriodMs] 一个来回），
 *   这是它看起来"活着"的原因；
 * - **轨道点**：绕中心匀速转（[OrbitPeriodMs] 一圈）。圆角在变时方向才看得出来，
 *   所以两者周期刻意不成整数比 —— 否则每两圈就会"对齐"一次，看起来像在抖。
 *
 * 颜色全部取主题：容器用 `primaryContainer`、轨道点用 `primary`，
 * 换主题色时整个指示器跟着走。调用方可以覆盖，比如放在深色卡片上时。
 */
@Composable
fun ContainedLoading(
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    color: Color = MaterialTheme.colorScheme.primary,
    /** 无障碍描述。传 null 时不加语义（旁边通常已经有"加载中"这行字）。 */
    contentDescription: String? = null,
) {
    val transition = rememberInfiniteTransition(label = "containedLoading")
    /** 0 = 正圆，1 = 圆角方。往返一次一个周期。 */
    val morph by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(MorphPeriodMs, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "morph",
    )
    /** 轨道点的角度。 */
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(OrbitPeriodMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "orbit",
    )

    val description = contentDescription
    Box(
        modifier = modifier
            .size(size)
            .then(
                if (description == null) Modifier
                else Modifier.semantics { this.contentDescription = description },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = this.size.width
            val h = this.size.height
            val side = minOf(w, h)
            val cx = w / 2f
            val cy = h / 2f

            // 容器圆角：从正圆（半径 = 半边）收到圆角方（0.22 倍）
            val outerRadius = side / 2f * (1f - 0.56f * morph)
            drawRoundRect(
                color = containerColor,
                topLeft = Offset(cx - side / 2f, cy - side / 2f),
                size = Size(side, side),
                cornerRadius = CornerRadius(outerRadius, outerRadius),
            )

            // 轨道点：绕中心转，半径随尺寸走（小尺寸下也留得下）
            val orbit = side * 0.24f
            val dot = side * 0.13f * (1f - 0.18f * morph)
            rotate(degrees = angle, pivot = Offset(cx, cy)) {
                drawCircle(color = color, radius = dot, center = Offset(cx + orbit, cy))
            }
        }
    }
}

/** 容器变形一个来回的时长。 */
private const val MorphPeriodMs = 1100

/** 轨道点转一圈的时长。与 [MorphPeriodMs] 刻意不成整数比，避免周期性"对齐"。 */
private const val OrbitPeriodMs = 2600
