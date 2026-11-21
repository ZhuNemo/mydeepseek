package com.simplechat.app.ui.markdown

import android.animation.ValueAnimator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.currentCoroutineContext

/**
 * 流式正文的「逐字显示」节拍器。
 *
 * ### 它解决的是"到达"与"显示"不同步
 *
 * 网络侧的增量是**突发**的（一个 SSE 包 0 字、下一个 12 字）。来多少画多少，
 * 排版与阅读位置就会一段一段跳。这一层把两者解耦：内容照旧实时进状态，
 * 但**交给自己决定的一个前缀**去渲染。
 *
 * ### 节拍按**真实帧间隔**分配，不是固定 tick
 *
 * 旧实现是"每 32ms 推进一步、每次放 30% 积压"，并且空闲时每 10ms 轮询一次。
 * 两个毛病：
 *
 * 1. 32ms 的固定节拍在 120Hz 屏上是"两帧才动一次"，在卡顿的机器上又会
 *    连续掉拍 —— 同一个数字不可能同时适配两种设备；
 * 2. 空闲轮询是纯空转：一屏十几条消息，每条都挂一个每 10ms 醒一次的协程。
 *
 * 现在改成两条硬规则：
 *
 * - **按帧分配**：`withFrameNanos` 给出真实的帧间隔 dt，本帧要放的字符数
 *   由 dt 算出来（基础速度 [RevealCharsPerSecond] 字/秒，积压多时按
 *   [CatchUpHalfLifeMs] 追加追赶量）。小数余额留到下一帧，不会出现
 *   "半个字跳一下"。
 * - **追平就挂起**：`snapshotFlow` 等内容真的变化才醒来，不再轮询。
 *
 * ### 冷却上限 96ms，不是 2 秒
 *
 * 一次提交把帧拖长了多少，就冷却多少（×2）。上限从原先的 2 秒收到
 * [MaxCooldownMs]：慢帧只该**短暂**让出一点时间给触摸与滚动，不该变成
 * "卡一次就停更两秒"——后者在长回答里就是"卡死"的观感。
 *
 * ### 系统关闭动画时直接给全文
 *
 * `ValueAnimator.areAnimatorsEnabled()` 为 false（开发者选项 / 无障碍设置里
 * 关掉了动画）时，逐字显示反而是一种障碍 —— 直接显示全文。
 *
 * @param streaming 是否处于流式态。false 时**不挂任何协程**，直接返回全文。
 * @param value     当前已经收到的完整文本。
 * @return 本帧应当渲染的前缀。
 */
@Composable
internal fun rememberSmoothReveal(streaming: Boolean, value: String): String {
    // 非流式：没有"逐字"可言，也不该为已定稿的消息留一个常驻协程
    if (!streaming) return value

    // 系统关了动画 → 不做逐字显示（读一次即可，运行期改设置会重建 Activity）
    val animatorsEnabled = remember {
        runCatching { ValueAnimator.areAnimatorsEnabled() }.getOrDefault(true)
    }
    if (!animatorsEnabled) return value

    var shown by remember { mutableStateOf(value) }
    // 协程里要读到**最新**的 value：参数是不可变的，只有这个 State 会更新
    val latest by rememberUpdatedState(value)

    LaunchedEffect(Unit) {
        var carry = 0f          // 上一帧没用完的小数字符，留到下一帧
        var lastFrameNs = 0L    // 上一次提交所在帧的开始时刻

        while (currentCoroutineContext().isActive) {
            val target = latest
            val current = shown

            // 没有新内容：挂起，等内容真的变化（不再 10ms 轮询一次）
            if (target == current) {
                snapshotFlow { latest }.first { it != current }
                continue
            }

            /*
             * 整段被换掉了（切版本 / 重新生成 / 从别处切回来）：
             * 没有"逐步浮现"可言，直接跳过去 —— 否则会看到旧内容被一个字一个字擦掉。
             */
            if (!target.startsWith(current)) {
                shown = target
                carry = 0f
                lastFrameNs = 0L
                continue
            }

            // 对齐到帧开始：提交 → 切块 → 解析尾块 → 排版 → 绘制，全部落在同一帧
            val frameStart = withFrameNanos { it }
            val dtMs = if (lastFrameNs == 0L) {
                DefaultFrameMs
            } else {
                ((frameStart - lastFrameNs) / 1_000_000f).coerceIn(MinFrameMs, MaxFrameMs)
            }
            lastFrameNs = frameStart

            val backlog = target.length - current.length
            val base = RevealCharsPerSecond * dtMs / 1000f + carry
            // 积压越多追得越快：每 CatchUpHalfLifeMs 吃掉积压的一半
            val catchUp = backlog * (dtMs / CatchUpHalfLifeMs) * 0.5f
            val advance = max(base, min(catchUp, backlog.toFloat()))

            if (advance < 1f) {
                // 这一帧还凑不满一个字：把余额存下来，下一帧连本带利一起放
                carry = advance
                continue
            }
            val whole = advance.toInt()
            carry = advance - whole

            var end = min(current.length + whole, target.length)
            // 不要把代理对切成两半（emoji 会闪一帧方块）
            if (end < target.length && target[end - 1].isHighSurrogate()) end -= 1
            if (end <= current.length) continue

            shown = target.substring(0, end)

            // 再等一帧：它的开始时刻减去这一帧的开始，就是这次提交的**实际**代价
            val nextFrame = withFrameNanos { it }
            val frameCostMs = (nextFrame - frameStart) / 1_000_000L
            val cooldown = cooldownAfter(frameCostMs)
            if (cooldown > 0L) delay(cooldown)
        }
    }

    // 流式态返回采样值；`streaming` 变 false 时上面已直接返回全文，不会留下"差一点"
    return shown
}

/** 基础出字速度：每秒 60 个 UTF-16 字符。与 60Hz 屏大致是"一帧一个字"。 */
private const val RevealCharsPerSecond = 60f

/** 积压的追赶半衰期：积压多时，每 70ms 吃掉当前积压的一半左右。 */
private const val CatchUpHalfLifeMs = 70f

/** 帧间隔的采样兜底：第一次提交时没有"上一帧"，按 60Hz 算。 */
private const val DefaultFrameMs = 16f

/** 帧间隔的下界：同一帧内多次提交时避免除零式爆炸（dt 极小 → 一个字都不放）。 */
private const val MinFrameMs = 1f

/**
 * 帧间隔的上界。
 *
 * 切到后台再回来、或系统卡了一下时 dt 可能是几百毫秒 —— 按它算出来的追赶量
 * 会把积压一口气全放掉（等于没节流）。夹到 [MaxFrameMs] 之内，一次最多放
 * 一整屏的量。
 */
private const val MaxFrameMs = 64f

/** 一帧的预算（毫秒）。没超过它就不用冷却。 */
private const val FrameBudgetMs = 16L

/**
 * 冷却上限（毫秒）。
 *
 * 比旧版的 2 秒小一个数量级：慢帧只该短暂让出一点时间，不该让出字停住。
 * 真遇到"一次渲染几百毫秒"的巨文档，96ms 的间隔也已经是每秒十次更新。
 */
private const val MaxCooldownMs = 96L

/**
 * 这一次提交把帧拖长了多少 → 下一次提交前要等多久。
 *
 * 纯函数，有单测（`SmoothRevealTest`）。
 */
internal fun cooldownAfter(frameCostMs: Long): Long {
    val over = frameCostMs - FrameBudgetMs
    return if (over <= 0L) 0L else (over * 2).coerceAtMost(MaxCooldownMs)
}
