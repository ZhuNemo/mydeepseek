package com.simplechat.app.ui.markdown

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 逐块渲染 Markdown —— 把单次整篇渲染换成"一块一次"。
 *
 * - **已完成的块**：文本不变 → `key` 不变 → Compose 跳过重组 → 不重解析
 * - **最后一块**（流式尾块）：只有它随 token 变化而重解析
 * - **尾块套高度下限**：半截语法引起的折行/换排版不再让高度回缩
 *
 * 分工：分块负责"降低单次解析量"（见 [splitMarkdownBlocks]），
 * 上游的帧对齐采样继续当"频率上限"（只是不许它更慢），两者叠加。
 *
 * ### 这里**没有任何出字特效**，是刻意的
 *
 * 流式期间只有一件事该发生：字按上游给了的节拍出现（见 `MarkdownText` 的
 * `rememberSmoothReveal`）。试过的三种附加效果都不划算，全部撤掉：
 *
 * | 做法 | 结果 |
 * |---|---|
 * | 段落入场的 `blur + alpha` | 每个新段落重演一次，流式期间看着像整段在闪 |
 * | 块底渐变遮罩 | 把整行洗掉，读起来是"一条线把字切了"，不是淡入 |
 * | 逐字改透明度 | 每帧重建 `AnnotatedString` 并重排，排版漂移 → 整段在抖 |
 *
 * 教训：出字的观感属于**采样节奏**，不该再用绘制层去补偿它 ——
 * 每多一层，就要多付一次"每帧重排"或"整块重绘"的代价。
 *
 * @param source      已 [normalizeSoftBreaks] 的 Markdown 文本。
 * @param streaming   是否处于流式态；为 true 时最后一块套高度下限。
 * @param blockGap    块间距（外层 Column 那一层）。
 * @param renderBlock 单块渲染器。`modifier` 用传入的那个；`isTail` 表示这是流式尾块
 *                   （只有它随 token 变化，也只有它能走"纯段落"快捷路径）。
 */
@Composable
internal fun MarkdownTextBlocks(
    source: String,
    streaming: Boolean,
    modifier: Modifier = Modifier,
    blockGap: Dp = 12.dp,
    renderBlock: @Composable (text: String, modifier: Modifier, isTail: Boolean) -> Unit,
) {
    // 切块是 O(n) 纯计算；source 没变就不重算（结果也进缓存，见 [splitBlocksCached]）。
    val blocks = remember(source) { splitBlocksCached(source) }

    /*
     * ══════════════════════════════════════════════════════════════
     *  渐进式渲染：首帧只画前 [MarkdownInitialBlocks] 块，剩下的逐帧补
     * ══════════════════════════════════════════════════════════════
     *
     * ### 为什么必须有这一段
     *
     * 这个 Column **不受 LazyList 管辖** —— 它是一整条消息的**内部**布局。
     * 所以下面对 `blocks` 的遍历是**全量组合**：一条 20 万字的回复 = 100 块，
     * 100 块在**同一帧**里全部组合，而每块的 `markdownStateOf` 是**主线程同步解析**
     * （见 `MarkdownText` 的 `MarkdownStateCache`：为了让高度第一次测量就真实，
     * 解析刻意不走库的异步入口）。
     *
     * 解析量 ≈ 1.7px/字 那条经验的另一面：**每块解析是毫秒级**，100 块串起来
     * 就是几秒到几十秒。这段时间主线程完全不响应 → 系统判 ANR 并杀掉进程。
     * 用户看到的就是「点开一个超长对话，卡死，然后闪退」。
     *
     * 而打开会话时的预热曾经把超长消息**排除**在外的，理由是"预热只会把后台
     * 线程占满" —— 但把活留给主线程不是省掉了它，而是把它挪到了最不该待的地方。
     * 现在两头对齐：预热覆盖首帧要用的块（同一条切块路径、同一种归一化），
     * 首帧也只组合这么多块。
     *
     * ### 为什么不会看到"内容长出来"
     *
     * 列表是**顶部为原点**（见 `MessageList` 的 `reverseLayout = false` 说明），
     * 新块接在**下方**：已显示的部分纹丝不动，视口也不跳。用户唯一可能
     * 察觉的场景是"打开后立刻往下猛滑"，那时会看到后几屏在补 —— 比崩掉好得多。
     *
     * ### 为什么流式态不窗口化
     *
     * 流式消息本来就在持续变长，每帧都重切、只有尾块变。此时再插一个
     * "补窗"循环，两套逐帧逻辑会互相抢帧，出字节奏反而会顿。流式消息的
     * 块数也远没到需要窗口化的量级。
     */
    val initialVisible = if (streaming) blocks.size else minOf(MarkdownInitialBlocks, blocks.size)
    var visible by remember(source) { mutableIntStateOf(initialVisible) }

    LaunchedEffect(source, streaming) {
        // 流式结束（或本来就是流式）→ 保证全部块可见，别把消息截断
        if (streaming || blocks.size <= visible) {
            if (visible != blocks.size) visible = blocks.size
            return@LaunchedEffect
        }
        while (visible < blocks.size) {
            // 让出一帧：这一帧的渲染先做完，再加下一批
            withFrameNanos { }
            visible = minOf(visible + MarkdownVisibleBlockBatch, blocks.size)
        }
    }

    // 视图而不是拷贝：`take()` 每次重组都会新建一个 List，而这里每帧都在变
    val shown = blocks.subList(0, visible)

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(blockGap),
    ) {
        shown.forEachIndexed { index, block ->
            val isTail = streaming && index == blocks.lastIndex
            /*
             * 尾块才套高度下限，**代码块例外**。
             *
             * 半成品语法（未闭合的 `**`、未成对的列表符号）会让同一段文字重新排版、
             * 高度变矮，于是界面上下抽一下 —— 钉住历史最大高度正是为了这个。
             * 但代码块的展开 / 收起**本来就要变高度**：钉住之后高度只会涨，
             * 收起来会留下一大片空白（用户点了"收起"却没收回，像是坏了）。
             */
            val pinHeight = isTail && !block.text.contains("```") && !block.text.contains("~~~")
            // key 用块起始 offset：追加内容时前面的块 key 不变 → 跳过重组与重解析
            key(block.key) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    renderBlock(
                        block.text,
                        if (pinHeight) {
                            Modifier.retainMinHeightWhileStreaming(
                                streaming = true,
                                resetKey = block.key,
                            )
                        } else {
                            Modifier
                        },
                        isTail,
                    )
                }
            }
        }
    }
}

/**
 * 首帧渲染多少块。
 *
 * 一块最大 2000 字（见 `TEXT_CHUNK_MAX_CHARS`），12 块 ≈ 2.4 万字 ≈ 16–30 屏，
 * 正常消息（哪怕很长）几乎都不会触发补窗；触发时也只是"慢慢铺开"。
 *
 * 这个值同时被 `ChatViewModel` 的预热使用（预热同样的块数），
 * 所以**首帧要用的块一定已经在缓存里**，主线程只做排版、不解析。
 */
internal const val MarkdownInitialBlocks = 12

/**
 * 每帧补多少块。
 *
 * 4 块 ≈ 8000 字，单帧解析成本约 30–60ms —— 会掉帧，但**不会卡死主线程**，
 * 而且掉的是补窗那几帧，用户此时通常还在看首屏。
 */
private const val MarkdownVisibleBlockBatch = 4
