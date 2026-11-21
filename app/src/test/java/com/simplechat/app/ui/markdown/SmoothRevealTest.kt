package com.simplechat.app.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 流式渲染的**自适应冷却**。
 *
 * 规则：这次提交把帧拖长了多少，下次提交前就等它的两倍，
 * **上限 [MaxCooldownMs] 量级**（96ms）。
 *
 * 上限是这条规则里最要紧的数字。旧版上限是 2 秒 —— 一次慢帧就让出字停两秒，
 * 长回答里那就是"卡死"的观感。慢帧只该**短暂**让出一点时间给触摸与滚动。
 *
 * 判错的代价：冷却给大了 → 文字一跳一跳；给小了 → 长文档每帧超时、整屏卡顿。
 */
class SmoothRevealTest {

    @Test
    fun `一帧之内不算贵，不需要冷却`() {
        assertEquals(0L, cooldownAfter(0))
        assertEquals(0L, cooldownAfter(8))
        assertEquals(0L, cooldownAfter(16))
    }

    @Test
    fun `超出预算的部分按两倍冷却`() {
        // 一帧拖到 24ms：超了 8ms → 冷却 16ms
        assertEquals(16L, cooldownAfter(24))
        // 一帧 40ms：超 24 → 48
        assertEquals(48L, cooldownAfter(40))
    }

    @Test
    fun `冷却有上限，不会因为一次异常就停更`() {
        // 96ms 是天花板：再慢的帧也只让出这一小段，不会退化成"停更"
        assertEquals(96L, cooldownAfter(64))
        assertEquals(96L, cooldownAfter(5_000))
        assertEquals(96L, cooldownAfter(Long.MAX_VALUE / 2))
    }

    @Test
    fun `耗时越长冷却越长（单调不减）`() {
        val samples = listOf(16L, 17L, 24L, 33L, 50L, 120L, 400L)
        val cooldowns = samples.map(::cooldownAfter)
        cooldowns.zipWithNext().forEach { (a, b) ->
            assertTrue("冷却应当单调不减：$cooldowns", b >= a)
        }
    }
}
