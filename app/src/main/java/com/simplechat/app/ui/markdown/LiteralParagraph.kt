package com.simplechat.app.ui.markdown

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.simplechat.app.data.LocaleHelper
import com.simplechat.app.ui.theme.markdownBodyStyle

/**
 * 「普通段落」的快捷渲染：**不进第三方库的组件树**。
 *
 * ### 为什么值得为一段普通文字单开一条路
 *
 * 流式期间真正在变的是**尾块**，而尾块绝大多数时候就是一段普通中文/英文段落。
 * 走库的路径时，这一段每帧都要：解析成 AST → 建 `AnnotatedString` →
 * 跑 `ExtendedSpans`（行内代码胶囊、搜索高亮）→ 组件树排版。
 * 其中解析与建串是纯开销：段落里没有任何语法要解释。
 *
 * 判定通过后直接用 `Text` 排版，字形、字号、行高、对齐全部取
 * [markdownBodyStyle] —— 与库的 `paragraph` 样式**同一个来源**，
 * 所以定稿那一刻（尾块交回库渲染）像素级一致，不会"闪一下换了字体"。
 *
 * ### 判定必须保守
 *
 * 判错的代价是**屏幕上露出原始 Markdown 语法**（`**加粗**` 原样显示），
 * 比多花几百微秒糟得多。所以这里只认"一眼就是纯文字"的段落：
 * 单行、行首不是块级标记、行内不含任何标记字符。拿不准就返回 false。
 */

/** 行首出现即代表块级语法的字符（标题 / 引用 / 列表 / 代码 / 表格 / HTML）。 */
private const val BlockStartChars = "#>-+*`|~<"

/** 行内出现就退出快捷路径的字符：加粗、斜体、代码、链接、转义、公式、表格、删除线。 */
private const val InlineMarkers = "*_`[]\\$|~"

/** 有序列表的标记：`1.` / `1)`（只在行首才算）。 */
private val OrderedMarker = Regex("""^\d{1,9}[.)](\s|$)""")

/**
 * 这一段是不是"可以安全地当纯文字渲染"的普通段落？
 *
 * 纯函数，有单测（`LiteralParagraphTest`）。
 */
internal fun isPlainParagraph(text: String): Boolean {
    val s = text.trim()
    if (s.isEmpty()) return false
    // 多行一律交回库：软换行、列表续行、引用续行的规则太多，省这一点不划算
    if (s.contains('\n')) return false
    // 行首是块级标记 → 标题 / 引用 / 列表 / 代码 / 表格
    if (s[0] in BlockStartChars) return false
    // 有序列表
    if (OrderedMarker.containsMatchIn(s)) return false
    // 行内标记：出现任何一个就交回库
    for (c in s) if (c in InlineMarkers) return false
    /*
     * 裸链接也交回库。
     *
     * 库里会给 `https://…` 自动加上可点击的链接样式；纯 `Text` 不会。
     * 如果这里放过去，流式期间是一段黑字、定稿那一瞬间变成蓝色可点 ——
     * 那正是"版式在定稿时跳一下"，比省下的那点解析时间糟得多。
     */
    val lower = s.lowercase()
    if ("http://" in lower || "https://" in lower || "www." in lower) return false
    return true
}

/**
 * 新追加文字「淡入」的渐变高度。
 *
 * 约两行半：再短看不出过渡，再长会把还没读完的上一段也一起洗淡。
 */
private val AppendFadeHeight = 72.dp

/**
 * 纯段落渲染 + **新追加文字的淡入**。
 *
 * ### 淡入是「静态蒙版」，不是动画
 *
 * 第一版写成"每次追加都起一段 150ms 的 alpha 动画"。实测不行：流式期间
 * 每帧只追加一到三个字，动画每帧都被下一次追加打断 —— 最新的那几个字
 * 永远停在接近透明的状态，一帧之后又突然变实，看起来是**尾巴在闪**。
 *
 * 现在改成不动画：以"上一次渲染到的位置"为起点，往下 [AppendFadeHeight]
 * 铺一道 0 → 1 的透明度渐变。
 *
 * - 刚出现的字在渐变的顶部（几乎透明），随内容增长自然被推到下方、变成实色 ——
 *   全程没有动画状态，也就没有"被打断"这回事；
 * - 跨度固定，蒙版的图层高度因此有界：尾块几千像素高也只开这一小条离屏缓冲；
 * - 只改**绘制**时的 alpha，排版一次都不重做。
 *
 * 早先试过的两种做法都撤掉了：逐字改透明度要每帧重建 `AnnotatedString`
 * 并重排（整段在抖）；块底渐变遮罩会把整行洗掉（像被一条线切了）。
 */
@Composable
internal fun LiteralParagraph(
    text: String,
    modifier: Modifier = Modifier,
    textColor: Color? = null,
) {
    val cjk = LocaleHelper.isCjkUi(LocalContext.current)
    val style = markdownBodyStyle(cjk)
    val color = textColor ?: MaterialTheme.colorScheme.onBackground

    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    /*
     * 上一次渲染的文本，用来算"这次新追加的从第几个字符开始"。
     *
     * 存整串而不是只存长度：必须确认是**纯追加**（`text` 以前一次的文本开头），
     * 否则换消息 / 重新生成这类整段替换也会被当成"新内容"而整体淡入一次。
     */
    var previous by remember { mutableStateOf("") }
    val appendedFrom = if (previous.isNotEmpty() && text.length > previous.length &&
        text.startsWith(previous)
    ) {
        previous.length
    } else {
        0
    }
    SideEffect { previous = text }

    Text(
        text = text,
        modifier = modifier
            .fillMaxWidth()
            .drawWithContent {
                val result = layout
                val measuredLength = result?.layoutInput?.text?.length ?: 0
                // 没有排版结果、没有新内容、或起点已经失效 → 正常画一遍就够
                if (result == null || appendedFrom <= 0 ||
                    appendedFrom >= measuredLength
                ) {
                    drawContent()
                    return@drawWithContent
                }

                // 新内容从哪一行开始：这一行的顶边就是渐变的上沿
                val line = result.getLineForOffset(appendedFrom)
                val bandTop = result.getLineTop(line).coerceIn(0f, size.height)
                if (bandTop >= size.height) {
                    drawContent()
                    return@drawWithContent
                }

                // 上半部分（已经定稿的文字）：原样画
                clipRect(bottom = bandTop) { drawContent() }

                /*
                 * 下半部分：先画进一个**只覆盖渐变高度**的图层，再用 `DstIn`
                 * 铺一道 0 → 1 的蒙版。图层高度受限，所以这笔开销与消息长度无关。
                 */
                val bandBottom = (bandTop + AppendFadeHeight.toPx()).coerceAtMost(size.height)
                val layer = drawContext.canvas.saveLayer(
                    Rect(0f, bandTop, size.width, bandBottom),
                    Paint(),
                )
                clipRect(top = bandTop) { drawContent() }
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black),
                        startY = bandTop,
                        endY = bandBottom,
                    ),
                    blendMode = BlendMode.DstIn,
                )
                drawContext.canvas.restoreToCount(layer)
            },
        color = color,
        style = style,
        onTextLayout = { layout = it },
    )
}
