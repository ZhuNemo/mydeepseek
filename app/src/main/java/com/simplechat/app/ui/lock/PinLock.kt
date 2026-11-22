package com.simplechat.app.ui.lock

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simplechat.app.R
import com.simplechat.app.data.Res
import com.simplechat.app.ui.common.AppIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * PIN 解锁界面（应用锁与保险箱共用）。
 *
 * ### 为什么重写成这一版
 *
 * 上一版是"标题 + 六个圆点 + 九宫格"，能跑但每一下点击都没有回应：
 * 按键不缩放、圆点只是从空心变实心、输错没有任何反馈。这一版按
 * **Material 3 Expressive 的"每个操作都要有回应"**来写：
 *
 * - 按键按下缩到 0.85（弹簧回弹，不是线性动画）；
 * - 每输一位，那一个圆点先放大到 1.5 倍、变成一个圆角方，再弹回圆形 ——
 *   这是 Expressive 的 morphing 手感，看一眼就知道"这一位吃进去了"；
 * - 输错时整排按键从左到右依次抖一下（30ms 错峰），并触发拒绝触觉；
 * - 退格长按清空（和 June 的键盘一致）。
 *
 * ### 状态归谁
 *
 * 这里**不判断密码对不对**：组件只负责"输满 N 位就交出去"。校验与错误文案
 * 由调用方（应用锁 / 保险箱）给回来 —— 这样同一个键盘能服务两种场景，
 * 而 PBKDF2 的 150ms 计算不会卡在组合里。
 *
 * @param error 非空时显示错误文案并触发一次抖动；调用方在每次校验失败后传入。
 * @param busy 校验中：键盘置灰、忽略输入（避免连点提交两次）。
 */
@Composable
fun PinLockScreen(
    title: String,
    onPinComplete: (String) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    error: String? = null,
    busy: Boolean = false,
    pinLength: Int = PinLength,
    /** 键盘左下角的自定义键（忘记 PIN / 使用屏幕锁定）。不传则留空。 */
    onAuxClick: (() -> Unit)? = null,
    auxIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    auxLabel: String? = null,
    /** 键盘下方的补充入口（忘记密码 / 使用屏幕锁定）。 */
    footer: (@Composable () -> Unit)? = null,
) {
    var pin by remember { mutableStateOf("") }
    var waveTrigger by remember { mutableIntStateOf(0) }
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    /*
     * 错误信号 → 清空输入 + 触发一次波浪。
     *
     * 用 error 的"变化"当触发条件（而不是 boolean）：调用方连续两次输错时，
     * error 文案相同但仍是新的一次失败 —— 这里靠 `waveTrigger` 自增来区分，
     * 所以不依赖 error 本身是否相等。
     */
    LaunchedEffect(error) {
        if (error != null) {
            pin = ""
            waveTrigger++
            haptic.performHapticFeedback(HapticFeedbackType.Reject)
        }
    }

    // 输满自动提交：留 100ms 让最后一位的圆点动画走完，否则"点完就跳走"很生硬
    LaunchedEffect(pin) {
        if (pin.length == pinLength && !busy) {
            delay(PinAutoSubmitDelayMs)
            onPinComplete(pin)
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        val compact = maxHeight < 640.dp
        val buttonSize = when {
            maxWidth < 340.dp -> 62.dp
            maxWidth < 400.dp -> 70.dp
            else -> 78.dp
        }
        val gap = if (maxWidth < 360.dp) 12.dp else 20.dp

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = if (compact) 16.dp else 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            AppBadge()

            Spacer(Modifier.height(if (compact) 14.dp else 22.dp))

            Text(
                text = error ?: title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Medium,
                color = if (error != null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                textAlign = TextAlign.Center,
            )

            if (subtitle != null && error == null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(if (compact) 18.dp else 30.dp))

            PinDots(filled = pin.length, total = pinLength)

            Spacer(Modifier.height(if (compact) 20.dp else 36.dp))

            NumberPad(
                waveTrigger = waveTrigger,
                buttonSize = buttonSize,
                gap = gap,
                enabled = !busy,
                backspaceEnabled = pin.isNotEmpty(),
                onDigit = { digit ->
                    if (pin.length < pinLength && !busy) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        pin += digit
                    }
                },
                onBackspace = {
                    if (pin.isNotEmpty()) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        pin = pin.dropLast(1)
                    }
                },
                onClear = {
                    if (pin.isNotEmpty()) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        pin = ""
                    }
                },
                onAuxClick = onAuxClick,
                auxIcon = auxIcon,
                auxLabel = auxLabel,
            )

            if (busy) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = Res.get(R.string.lock_verifying),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            footer?.let {
                Spacer(Modifier.height(12.dp))
                it()
            }
        }
    }
}

/** 应用图标底座：与桌面图标同源，让"这是哪个应用在要密码"一眼可见。 */
@Composable
private fun AppBadge() {
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = AppIcons.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp),
        )
    }
}

/**
 * 输入进度：一排圆点。
 *
 * 已填的那一位在"刚输入"时会放大 + 变成圆角方，再弹回圆形 —— 见 [PinDot]。
 */
@Composable
private fun PinDots(filled: Int, total: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(total) { index ->
            PinDot(filled = index < filled, isLast = index == filled - 1)
        }
    }
}

@Composable
private fun PinDot(filled: Boolean, isLast: Boolean) {
    var morph by remember { mutableStateOf(false) }
    val scale = remember { Animatable(1f) }

    LaunchedEffect(filled, isLast) {
        if (filled && isLast) {
            morph = true
            scale.animateTo(1.45f, tween(PinPopUpMs))
            scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
            morph = false
        } else if (filled) {
            // 后面那几位被"推"成实心时也给一个小回弹，但不做变形（避免整排一起跳）
            morph = false
            scale.snapTo(1f)
        } else if (!filled) {
            morph = false
            scale.snapTo(1f)
        }
    }

    val size by animateDpAsState(
        targetValue = if (morph) 22.dp else 14.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "dotSize",
    )

    Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
        if (filled) {
            Box(
                modifier = Modifier
                    .size(size)
                    .scale(scale.value)
                    .clip(if (morph) RoundedCornerShape(7.dp) else CircleShape)
                    .background(MaterialTheme.colorScheme.onSurface),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .border(2.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), CircleShape),
            )
        }
    }
}

@Composable
private fun NumberPad(
    waveTrigger: Int,
    buttonSize: Dp,
    gap: Dp,
    enabled: Boolean,
    backspaceEnabled: Boolean,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    onAuxClick: (() -> Unit)?,
    auxIcon: androidx.compose.ui.graphics.vector.ImageVector?,
    auxLabel: String?,
) {
    // 每个数字一个缩放值，错误时依次抖一下
    val scales = remember { List(10) { Animatable(1f) } }

    LaunchedEffect(waveTrigger) {
        if (waveTrigger == 0) return@LaunchedEffect
        (1..9).toList().plus(0).forEachIndexed { index, digit ->
            launch {
                delay(index * PinWaveStaggerMs)
                scales[digit].animateTo(0.82f, tween(PinWaveDownMs))
                scales[digit].animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
        (1..9).chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                row.forEach { digit ->
                    NumberKey(
                        digits = digit.toString(),
                        buttonSize = buttonSize,
                        scaleValue = scales[digit].value,
                        enabled = enabled,
                        onClick = { onDigit(digit.digitToChar()) },
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            // 左下：辅助键（忘记 PIN / 屏幕锁定）；没有就留空占位，保持九宫格居中
            if (onAuxClick != null && (auxIcon != null || auxLabel != null)) {
                PadKey(
                    buttonSize = buttonSize,
                    enabled = enabled,
                    filled = false,
                    onClick = onAuxClick,
                ) {
                    if (auxIcon != null) {
                        Icon(
                            imageVector = auxIcon,
                            contentDescription = auxLabel,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(buttonSize * 0.4f),
                        )
                    } else if (auxLabel != null) {
                        Text(
                            text = auxLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            } else {
                Spacer(Modifier.size(buttonSize))
            }

            NumberKey(
                digits = "0",
                buttonSize = buttonSize,
                scaleValue = scales[0].value,
                enabled = enabled,
                onClick = { onDigit('0') },
            )

            PadKey(
                buttonSize = buttonSize,
                enabled = enabled && backspaceEnabled,
                filled = false,
                onClick = onBackspace,
                onLongClick = onClear,
            ) {
                Icon(
                    imageVector = AppIcons.Backspace,
                    contentDescription = Res.get(R.string.lock_backspace),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(buttonSize * 0.4f),
                )
            }
        }
    }
}

@Composable
private fun NumberKey(
    digits: String,
    buttonSize: Dp,
    scaleValue: Float,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    PadKey(
        buttonSize = buttonSize,
        enabled = enabled,
        filled = true,
        externalScale = scaleValue,
        onClick = onClick,
    ) {
        Text(
            text = digits,
            style = MaterialTheme.typography.titleLarge,
            // 80dp 的键上 28sp 正好；小屏（62dp）用 22sp，否则数字会顶到边
            fontSize = if (buttonSize < 72.dp) 22.sp else 28.sp,
            fontWeight = FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PadKey(
    buttonSize: Dp,
    enabled: Boolean,
    filled: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    externalScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    // 按下 0.88 + 弹簧回弹：手感比线性 tween 更像"真的按下去"
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "pressScale",
    )
    val alpha by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.35f,
        animationSpec = tween(120),
        label = "padAlpha",
    )

    Surface(
        modifier = Modifier
            .size(buttonSize)
            .scale(pressScale * externalScale)
            .alpha(alpha)
            .clip(CircleShape)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        shape = CircleShape,
        color = if (filled) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surface
        },
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

/** 六位：与保险箱现有的 PIN 长度一致。 */
const val PinLength: Int = 6

private const val PinAutoSubmitDelayMs = 100L
private const val PinWaveStaggerMs = 28L
private const val PinWaveDownMs = 90
private const val PinPopUpMs = 120
