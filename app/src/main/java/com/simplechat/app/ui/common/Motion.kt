package com.simplechat.app.ui.common

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.unit.IntOffset

/**
 * 全局转场规格。
 *
 * **所有页面切换都从这里取** —— 时长、缓动、位移量只定义一次。
 * 不然「设置页滑 1/4 屏、搜索页滑 1/3 屏」这种不一致会一处长出来，
 * 而转场恰恰是那种每一处都差一点、整体就是不对劲的东西。
 */

/**
 * 转场时长 300ms。
 *
 * 对齐 Android 平台自己的 Activity 切换（Material 的 medium 档）。
 * 再短会"闪一下"，再长有拖沓感 —— 平台选这个数是有原因的。
 *
 * 抽屉展开成搜索页也用它，两处节奏才是一致的。
 */
const val MotionDurationMs = 300

/**
 * 折叠 / 展开的时长 220ms。
 *
 * 比转场快一档 —— 折叠的距离往往很大（一条几万像素的消息），拖久了会像在等它。
 * **用户气泡与思考面板共用这一个数**：两处都是"折叠"，节奏不一致会很明显。
 */
const val FoldAnimMs = 220

/** 缓动：平台标准的先快后慢。仍用于「无限循环」那类动画（转圈、呼吸）。 */
val MotionEasing: Easing = FastOutSlowInEasing

/**
 * 全局弹簧规格。
 *
 * M3 Expressive 把"状态变化"的默认曲线从 tween 换成了 spring —— 位移与尺寸
 * 不再是"在固定时间里走完"，而是"受力和阻尼决定何时停"。手感上的差别是
 * **起步更果断、收尾更自然**，而且被打断时不会跳（弹簧从当前速度接着算，
 * tween 会从零重来）—— 拖动到一半松手、连点两下这类交互全靠这一点。
 *
 * 阻尼取 [Spring.DampingRatioNoBouncy]：过冲在移动端很容易看起来像"没对齐"，
 * 只在需要强调"弹一下"的地方（见 [MotionBouncy]）才放开。
 */
val MotionSpring: SpringSpec<Float> = spring(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
)

/** 允许轻微过冲的弹簧，用于"被点中"这类需要一点回弹的反馈。 */
val MotionBouncy: SpringSpec<Float> = spring(
    dampingRatio = Spring.DampingRatioLowBouncy,
    stiffness = Spring.StiffnessMedium,
)

/**
 * 页面转场。
 *
 * ```
 * 前进 ── 新页从右侧整屏推入，旧页整屏推向左侧
 * 后退 ── 反过来
 * ```
 *
 * **两个刻意的选择**：
 *
 * 1. **整屏推，不是推一半**。出场页只推 1/3 屏的话，中途两页会在屏幕中间
 *    叠成重影；整屏推则两页首尾相接、严丝合缝，全程没有重叠也没有空档。
 * 2. **不叠淡入淡出**。位移途中两页都是半透明的，底下的空白会透出来，
 *    正是上面那团"糊"的来源。平台自己的 Activity 切换也不带淡出。
 *
 * 全屏页面之间的横向切换，本来就该是这个样子。
 *
 * @param forward 目标页比当前页**更深**（往栈里压）时为 true。
 *   同级之间互不跳转，所以只判深度就够。
 */
fun AnimatedContentTransitionScope<*>.screenTransform(forward: Boolean): ContentTransform {
    // 页面转场用弹簧：被打断时从当前速度接着走，不会"跳回去重来"
    val slide = spring<IntOffset>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )

    val enter = slideInHorizontally(slide) { width -> if (forward) width else -width }
    val exit = slideOutHorizontally(slide) { width -> if (forward) -width else width }

    return enter togetherWith exit
}
