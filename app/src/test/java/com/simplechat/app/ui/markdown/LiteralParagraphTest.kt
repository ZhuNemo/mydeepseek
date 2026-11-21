package com.simplechat.app.ui.markdown

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「普通段落」快捷路径的判据。
 *
 * 这条判据**只能错在保守的一侧**：判成普通段落却其实带语法，用户会看到
 * `**加粗**` 这种原始标记直接印在屏幕上；判成不普通只是多走一次库的渲染，
 * 成本是几百微秒。所以下面这些用例的重点全在"该退出的都退出了"。
 */
class LiteralParagraphTest {

    @Test
    fun `普通段落走快捷路径`() {
        assertTrue(isPlainParagraph("这是一段普通的中文回复，没有任何语法标记。"))
        assertTrue(isPlainParagraph("A plain English paragraph without any markup."))
        assertTrue(isPlainParagraph("列表符号在行中间不算：a - b 这样的减法是对的。"))
        assertTrue(isPlainParagraph("价格是 5 > 3 的关系，行内的 > 不是引用。"))
    }

    @Test
    fun `空内容不算段落`() {
        assertFalse(isPlainParagraph(""))
        assertFalse(isPlainParagraph("   \n  "))
    }

    @Test
    fun `多行一律交回库`() {
        assertFalse(isPlainParagraph("第一行\n第二行"))
    }

    @Test
    fun `行首是块级标记时不走快捷路径`() {
        assertFalse(isPlainParagraph("# 标题"))
        assertFalse(isPlainParagraph("> 引用"))
        assertFalse(isPlainParagraph("- 列表项"))
        assertFalse(isPlainParagraph("+ 列表项"))
        assertFalse(isPlainParagraph("* 列表项"))
        assertFalse(isPlainParagraph("1. 有序列表"))
        assertFalse(isPlainParagraph("12) 有序列表"))
        assertFalse(isPlainParagraph("```kotlin"))
        assertFalse(isPlainParagraph("| a | b |"))
        assertFalse(isPlainParagraph("<div>x</div>"))
    }

    @Test
    fun `行内标记出现即退出`() {
        assertFalse(isPlainParagraph("这是 **加粗** 文字"))
        assertFalse(isPlainParagraph("这是 *斜体* 文字"))
        assertFalse(isPlainParagraph("这是 `行内代码`"))
        assertFalse(isPlainParagraph("这是 [链接](https://example.com)"))
        assertFalse(isPlainParagraph("这是 ~~删除线~~"))
        assertFalse(isPlainParagraph("转义一下 \\* 星号"))
        assertFalse(isPlainParagraph("表格 a|b"))
    }
}
