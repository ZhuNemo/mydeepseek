package com.simplechat.app.ui.markdown

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.simplechat.app.data.LocaleHelper
import com.simplechat.app.ui.theme.markdownBodyStyle

/**
 * 「普通段落」的快捷渲染：**不进第三方库的组件树**。
 *
 * ### 为什么值得为一段普通文字单开一条路
 *
 * 流式期间真正在变的是**尾块**，而尾块绝大多数时候就是一段普通中文 / 英文段落。
 * 走库的路径时，这一段每帧都要：解析成 AST → 建 `AnnotatedString` →
 * 跑 `ExtendedSpans`（行内代码胶囊、搜索高亮）→ 组件树排版。
 * 其中解析与建串是纯开销：段落里没有任何语法要解释。
 *
 * 判定通过后直接用 `Text` 排版，字号、行高、对齐全部取 [markdownBodyStyle] ——
 * 与库的 `paragraph` 样式**同一个来源**，所以定稿那一刻（尾块交回库渲染）
 * 像素级一致，不会"闪一下换了字体"。
 *
 * ### 判定必须保守
 *
 * 判错的代价是**屏幕上露出原始 Markdown 语法**（`**加粗**` 原样显示），
 * 比多花几百微秒糟得多。所以这里只认"一眼就是纯文字"的段落：
 * 单行、行首不是块级标记、行内不含任何标记字符、不含裸链接。拿不准就返回 false。
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
 * 纯段落渲染。
 *
 * ### 这里**不做**新追加字符的淡入（刻意）
 *
 * 上游那份策略里有一条"新追加字符淡入 150ms"。在**他们的**节奏下（一次到达一批）
 * 那是成立的；但这一侧已经先把到达摊成了"每帧一到三个字"（见
 * [rememberSmoothReveal]），再叠一层 150ms 的淡入会出两个问题：
 *
 * 1. 动画每帧都被下一次追加打断 —— 最新的那几个字永远停在接近透明，一帧后又
 *    突然变实，看起来是**尾巴在闪**（第一版实现就是这么写的，已撤掉）；
 * 2. 若改成"按位置渐变"（新内容顶部最淡、往下恢复），最新的那几行会在整整
 *    两三行的窗口里都是半透明的 —— 慢速流式下等于**看不见刚写出来的字**。
 *
 * 而逐字显示本身已经提供了"字在长出来"的观感。所以这里就老老实实用 `Text`：
 * 省掉库的解析与建串已经是这条路的全部收益，再往上加效果只会把它变成新的抖动源。
 */
@Composable
internal fun LiteralParagraph(
    text: String,
    modifier: Modifier = Modifier,
    textColor: Color? = null,
) {
    val cjk = LocaleHelper.isCjkUi(LocalContext.current)
    Text(
        text = text,
        modifier = modifier.fillMaxWidth(),
        color = textColor ?: MaterialTheme.colorScheme.onBackground,
        style = markdownBodyStyle(cjk),
    )
}
