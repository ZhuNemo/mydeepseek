package com.simplechat.app.ui.markdown

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hrm.latex.renderer.Latex
import com.hrm.latex.renderer.model.LatexConfig
import com.hrm.latex.renderer.model.LatexTheme
import com.simplechat.app.R
import com.simplechat.app.ui.theme.LocalIsDarkTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 带公式渲染的正文：按 [splitMath] 把内容切成文本段与公式段，交替渲染。
 *
 * - 文本段交给 [MarkdownBody]（原有的一整套 Markdown / 代码块 / 块级增量渲染都不变）
 * - 公式段交给 `latex-renderer`（Compose 原生 Canvas 渲染，**不用 WebView**）
 *
 * ### 显示进度在这里**整条回复共用一份**
 *
 * 逐字显示（[rememberSmoothReveal]）刻意提到了这一层，而不是留给每段
 * [MarkdownText] 各自去做：
 *
 * 一条带公式的回复会被切成 `文本 / 公式 / 文本 / …`。如果每段自己采样，
 * 公式后面那段会**从头再浮现一次** —— 前面已经读过的字先是消失、再一个字一个字长出来。
 * 更糟的是那段是**新解析**的（key 变了），一次重排 + 一次重播。
 *
 * 放在这里之后，进度是"整条回复已经揭示到第几个字符"，切段只是切窗口：
 * 新增内容只出现在末尾那一小段，前面的段文本不变、直接命中缓存、连重组都跳过。
 *
 * ### 为什么不用 WebView + KaTeX
 * 每个公式一个 WebView 实例，在长消息列表里滚动会明显掉帧 —— 而那正是本项目
 * 要解决的第一个问题（见 PRD「轻量流畅是第一要义」）。原生 Canvas 渲染没有这层开销。
 *
 * ### 代价：行内公式不跟着文字走
 * 公式是独立的一块，行内公式也会**独自占一行**。这是"不引 WebView"必然的取舍：
 * 要做到真正的行内混排，得把公式排版成一段 `AnnotatedString` 并塞进
 * Markdown 渲染器的内联流里，那是另一个量级的工程。对长文创作来说，
 * 公式偶尔出现，独占一行读得懂；而 WebView 卡顿是每一条消息都要付的代价。
 */
@Composable
fun MarkdownWithMath(
    content: String,
    modifier: Modifier = Modifier,
    streaming: Boolean = false,
    /** 见 [MarkdownText] 的 `textColor`。 */
    textColor: Color? = null,
) {
    // 整条回复一份进度；公式段与文本段共用它，不会各自重播
    val revealed = rememberSmoothReveal(streaming = streaming, value = content)
    // 切分是纯字符串操作，跟着已揭示的内容走；文本没变就不重切
    val segments = remember(revealed) { splitMath(revealed) }

    // 没有公式：直接走原来的路径，一个字节的额外开销都没有
    if (segments.size == 1 && segments.first() is ContentSegment.Text) {
        MarkdownBody(
            content = (segments.first() as ContentSegment.Text).text,
            modifier = modifier,
            streaming = streaming,
            textColor = textColor,
        )
        return
    }

    val fontSize = LatexFontSize
    /*
     * 公式主题：**透明底 + 正文色**。
     *
     * ⚠️ 不能用库的 `LatexTheme.material3()` —— 那个会把 `colorScheme.surface`
     * 当作公式画布底色（浅色下是纯白、深色下是卡片灰），于是每段公式后面
     * 拖着一整条色块（真机反馈的灰底就是这么来的）。官方 KaTeX 是透明底。
     *
     * 也不能用 `LatexTheme.auto()`：它按**系统**深浅色选色板，而本项目允许
     * 强制浅色 / 深色，两者可能不一致 —— 会出现"深色页面上一段黑公式"。
     */
    val dark = LocalIsDarkTheme.current
    val formulaColor = textColor ?: MaterialTheme.colorScheme.onBackground
    val latexTheme = remember(dark, formulaColor) {
        if (dark) LatexTheme.dark(color = formulaColor) else LatexTheme.light(color = formulaColor)
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        segments.forEach { segment ->
            when (segment) {
                is ContentSegment.Text -> MarkdownBody(
                    content = segment.text,
                    /*
                     * 每一段都按流式处理 —— 它们**同属一条正在生成的回复**。
                     *
                     * 只有最后一段在长，但前面的段也必须"一次全部显示出来"：
                     * 若给它们 `streaming = false`，`MarkdownTextBlocks` 会按
                     * "首屏只画 12 块"的规则逐帧补窗，于是公式**前面**已经读完的
                     * 那一大段会在流式期间重新"一屏一屏长出来"。
                     */
                    streaming = streaming,
                    textColor = textColor,
                )

                is ContentSegment.Formula -> CompatibleFormula(
                    segment = segment,
                    config = LatexConfig(fontSize = fontSize, theme = latexTheme),
                    streaming = streaming,
                )
            }
        }
    }
}

/**
 * 公式排版要等文本**稳定**多久（毫秒，仅流式期间）。
 *
 * LaTeX 解析 + 排版是毫秒级操作，一帧做一次就是几十毫秒的主线程开销；
 * 而流式期间公式的 tex 每个 token 都在变 —— 每帧重排一遍既看不出差别，又占满帧预算。
 */
private const val FormulaSettleMs = 120L

@Composable
private fun CompatibleFormula(
    segment: ContentSegment.Formula,
    config: LatexConfig,
    streaming: Boolean,
) {
    /*
     * 已经排好版的那一份。
     *
     * 流式期间 tex 还在长，新的一份要等 [FormulaSettleMs] 才出来 ——
     * 这段空窗期里继续显示**上一份**（只要它还是当前 tex 的前缀），
     * 而不是先缩回等宽源码再变回公式（那是一次肉眼可见的闪）。
     */
    var rendered by remember { mutableStateOf<LatexPresentation?>(null) }
    var renderedTex by remember { mutableStateOf("") }

    LaunchedEffect(segment.tex, streaming) {
        val growing = renderedTex.isNotEmpty() && segment.tex.startsWith(renderedTex)
        // 只对"还在长"的公式等一等；新出现的公式立刻排版（没有旧内容可显示）
        if (streaming && growing) delay(FormulaSettleMs)
        val next = withContext(Dispatchers.Default) { prepareLatexPresentation(segment.tex) }
        rendered = next
        renderedTex = segment.tex
    }

    // 只有"同一段公式在增长"时才沿用旧结果；换了另一段公式就老老实实等新的
    val presentation = rendered?.takeIf {
        renderedTex == segment.tex || segment.tex.startsWith(renderedTex)
    }

    var sourceVisible by remember(segment.tex) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = if (segment.display) 4.dp else 0.dp)) {
        if (presentation?.simplified == true) TextButton(onClick = { sourceVisible = !sourceVisible }) {
            Text(stringResource(if (sourceVisible) R.string.latex_show_rendered else R.string.latex_compatible_source))
        }
        if (presentation?.nativeTex != null && !sourceVisible) {
            // Horizontal scrolling keeps wide formulas accessible instead of clipping content.
            Latex(latex = presentation.nativeTex, config = config,
                modifier = Modifier.horizontalScroll(rememberScrollState()))
        } else {
            if (presentation != null && presentation.nativeTex == null) {
                Text(stringResource(R.string.latex_source_fallback), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SelectionContainer {
                Text(segment.tex, style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace))
            }
        }
    }
}

/**
 * 公式字号。与正文同档（17sp 左右），不随系统字号线性放大 ——
 * 公式排版对字号敏感，过大会撑破行宽，过小则看不清上下标。
 */
private val LatexFontSize = 17.sp
