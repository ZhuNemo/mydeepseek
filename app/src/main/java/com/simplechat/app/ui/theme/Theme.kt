package com.simplechat.app.ui.theme

import androidx.annotation.StringRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.luminance
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.simplechat.app.R
import com.simplechat.app.data.Res

/** 主题模式，对应设置页的「外观」三项。 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * 主题色。设置页「个性化 → 主题色」。
 *
 * 只影响**品牌色那一族**：主色（按钮 / 链接 / 选中态 / 发送键 / 进度环）、
 * 选中态的浅底、以及用户气泡。页面底色、正文颜色、功能色（危险 / 警告）都不动 ——
 * 换主题色不该把"有错误"这类语义色也换掉。
 *
 * 取值存的是 `name`；认不出来就回落到 [BLUE]（见 `MainActivity`）。
 */
enum class AccentColor(@StringRes private val labelRes: Int) {
    BLUE(R.string.theme_color_blue),
    GREEN(R.string.theme_color_green),
    RED(R.string.theme_color_red),
    PINK(R.string.theme_color_pink),
    PURPLE(R.string.theme_color_purple),
    BROWN(R.string.theme_color_brown),
    GRAY(R.string.theme_color_gray),
    ;

    /** 颜色名，随界面语言取（`theme_color_*`）。 */
    val label: String get() = Res.get(labelRes)
}

/** 该主题色在指定明暗下的**主色**。 */
fun AccentColor.primary(dark: Boolean): Color = when (this) {
    AccentColor.BLUE -> if (dark) BrandBlueDark else BrandBlue
    AccentColor.GREEN -> if (dark) AccentGreenDark else AccentGreenLight
    AccentColor.RED -> if (dark) AccentRedDark else AccentRedLight
    AccentColor.PINK -> if (dark) AccentPinkDark else AccentPinkLight
    AccentColor.PURPLE -> if (dark) AccentPurpleDark else AccentPurpleLight
    AccentColor.BROWN -> if (dark) AccentBrownDark else AccentBrownLight
    AccentColor.GRAY -> if (dark) AccentGrayDark else AccentGrayLight
}

/**
 * 由主色**派生**出来的浅色底：选中态填充 + 用户气泡。
 *
 * 刻意不逐个手写色值 —— 7 个主题色 × 2 档明暗 = 28 个手调颜色，
 * 既难保证它们跟主色"是同一个色相"，改一个还要跟着改一片。
 * 这里按固定比例把主色掺进页面底色，整套自动跟着主色走。
 *
 * 比例（浅 0.16 / 深 0.22）是照原来那版蓝色反推的：算出来与
 * `#E8EDFF` / `#243056` 基本重合，所以**默认的蓝色观感不变**。
 */
private fun tintOf(primary: Color, dark: Boolean): Color =
    lerp(if (dark) PageBgDark else Color.White, primary, if (dark) 0.22f else 0.16f)

/**
 * Material3 ColorScheme 之外的扩展语义色。
 * 这些是 DeepSeek 观感的关键，Material 没有对应槽位。
 */
@Immutable
data class ChatColors(
    val userBubble: Color,
    val onUserBubble: Color,
    val processText: Color,
    val processBar: Color,
    val placeholder: Color,
    val pageBackground: Color,
    val settingsBackground: Color,
    val card: Color,
    val outline: Color,
    val divider: Color,
    val danger: Color,
    /** 上下文接近阈值这类「注意」型状态，不是错误。 */
    val warning: Color,
    val capsuleSelected: Color,
    val fieldBackground: Color,
    /** 长按菜单等浮层表面。 */
    val menuBackground: Color,
    /**
     * Markdown 专用配色（对齐官方 Web 端的 token，见 [MarkdownCodeBgLight]）。
     *
     * 与 [fieldBackground] 分开是刻意的：输入框底色偏深一档才压得住，
     * 而代码块/行内代码要更浅 —— 直接复用会让整段代码"发灰"。
     */
    val markdownCodeBlock: Color,
    val markdownInlineCode: Color,
    val markdownQuoteBar: Color,
    val markdownRule: Color,
    /**
     * 抽屉 / 弹层背后的遮罩。
     *
     * 放在这里而不是用 Material 的 `colorScheme.scrim`：那个值在两套主题下
     * 都是同一个黑，**深色模式下盖在近黑底上等于没有**。这里按主题分别取值。
     */
    val scrim: Color,
)

private val LightChatColors = ChatColors(
    userBubble = UserBubbleLight,
    onUserBubble = TextPrimaryLight,
    processText = ProcessTextLight,
    processBar = ProcessBarLight,
    placeholder = PlaceholderLight,
    pageBackground = PageBgLight,
    settingsBackground = SettingsBgLight,
    card = CardLight,
    outline = OutlineLight,
    divider = DividerLight,
    danger = DangerRed,
    warning = WarningAmber,
    capsuleSelected = CapsuleSelectedLight,
    fieldBackground = FieldBgLight,
    menuBackground = MenuBgLight,
    markdownCodeBlock = MarkdownCodeBgLight,
    markdownInlineCode = MarkdownInlineCodeBgLight,
    markdownQuoteBar = MarkdownQuoteBarLight,
    markdownRule = MarkdownRuleLight,
    scrim = Color.Black.copy(alpha = 0.45f),
)

private val DarkChatColors = ChatColors(
    userBubble = UserBubbleDark,
    onUserBubble = TextPrimaryDark,
    processText = ProcessTextDark,
    processBar = ProcessBarDark,
    placeholder = PlaceholderDark,
    pageBackground = PageBgDark,
    settingsBackground = SettingsBgDark,
    card = CardDark,
    outline = OutlineDark,
    divider = DividerDark,
    danger = DangerRedDark,
    warning = WarningAmberDark,
    capsuleSelected = CapsuleSelectedDark,
    fieldBackground = FieldBgDark,
    menuBackground = MenuBgDark,
    markdownCodeBlock = MarkdownCodeBgDark,
    markdownInlineCode = MarkdownInlineCodeBgDark,
    markdownQuoteBar = MarkdownQuoteBarDark,
    markdownRule = MarkdownRuleDark,
    scrim = Color.Black.copy(alpha = 0.62f),
)

val LocalChatColors = staticCompositionLocalOf { LightChatColors }

/**
 * M3 的圆角阶梯。
 *
 * 之前一直用的是 Material 的默认档（4 / 8 / 12 / 16 / 28），
 * 那套是按"信息密度高的工具类应用"调的；而这里要的是 M3 **Expressive** 的观感 ——
 * 大容器 24dp、对话框与浮层 28dp、小控件 8dp。差一档不明显，但**整屏**看下来
 * 就是"这个是 M3，那个只是像 M3"。
 *
 * 只声明这五档：界面上的圆角必须从这里取，别在调用点随手写 `RoundedCornerShape(13.dp)` ——
 * 一旦允许随手写，半年后就会集齐十几个互不相同的圆角。
 */
val SimpleChatShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * 从 M3 的 [ColorScheme] 派生出这个应用自己的语义色板。
 *
 * ### 为什么保留 [ChatColors] 这一层，而不是让界面直接用 `MaterialTheme.colorScheme`
 *
 * 界面上有 123 处 `LocalChatColors.current.xxx`。直接换成语义化程度更低的
 * `colorScheme.surfaceContainerHigh` 之类，等于把 123 个调用点连同它们的
 * **业务含义**（"这是气泡"、"这是输入框底"）一起重写 —— 改完没人知道
 * 某个地方为什么是那个颜色。保留这一层，映射关系只在这一个函数里，
 * 换主题体系时也只改这里。
 *
 * 映射原则：**先找语义最接近的 M3 角色，找不到才用固定色**。
 * 目前只有 `warning` 与 `scrim` 是固定的 —— M3 没有"警告"角色，
 * 而 `scrim` 需要按明暗分别取值（用 `colorScheme.scrim` 在深色下盖在近黑底上等于没有）。
 */
private fun chatColors(scheme: ColorScheme): ChatColors = ChatColors(
    // 用户气泡走 primaryContainer：浅品牌底 + 深品牌字，正是 M3 的"强调容器"语义
    userBubble = scheme.primaryContainer,
    onUserBubble = scheme.onPrimaryContainer,
    processText = scheme.onSurfaceVariant,
    processBar = scheme.outlineVariant,
    placeholder = scheme.onSurfaceVariant,
    pageBackground = scheme.background,
    settingsBackground = scheme.surface,
    card = scheme.surfaceContainer,
    outline = scheme.outlineVariant,
    divider = scheme.outlineVariant.copy(alpha = 0.7f),
    danger = scheme.error,
    // M3 没有"警告"角色；tertiary 是品牌色的第三档，用来提示"注意"语义会串
    warning = WarningAmber,
    capsuleSelected = scheme.secondaryContainer,
    fieldBackground = scheme.surfaceContainerHighest,
    menuBackground = scheme.surfaceContainerHigh,
    markdownCodeBlock = scheme.surfaceContainerHighest,
    markdownInlineCode = scheme.surfaceContainerHigh,
    markdownQuoteBar = scheme.primary,
    markdownRule = scheme.outlineVariant,
    scrim = Color.Black.copy(alpha = if (scheme.background.luminance() > 0.5f) 0.45f else 0.62f),
)

/**
 * 当前是不是深色。
 *
 * 存在的理由只有一个：**系统栏（状态栏图标 / 导航栏「小白条」）要跟 App 的主题走，
 * 而不是跟系统的**。设置页可以强制浅色或深色，系统的深浅完全可能是另一个值 ——
 * 不看着这个值，就会出现「深色 App 顶着一排看不见的状态栏图标 / 底下一条小白条」。
 *
 * 见 [ApplySystemBarAppearance]。
 */
val LocalIsDarkTheme = staticCompositionLocalOf { false }

/**
 * 当前选中的主题色（设置页的「个性化 → 主题色」）。
 *
 * 与 `MaterialTheme.colorScheme.primary` 的区别：primary 是**算出来的**色板成员，
 * 会被 tonal palette 调整过；有些地方（分享图片的背景、保险箱的锁形标识）
 * 需要"用户当初选的那个色"本身，而不是它的某个 tonal 变体。
 */
val LocalAccentColor = staticCompositionLocalOf { AccentColor.BLUE }

/**
 * 用 Material Color Utilities 生成整套 M3 色板。
 *
 * 关键：**seed 只有一个**（取浅色那档的品牌色）。早先的写法给深浅两套颜色，
 * 那是手调色板的思路；MCU 的 tonal palette 是从**单一 seed** 推导出
 * 0–100 的明度阶梯，深浅模式各取不同档位 —— 给它两个 seed 等于让它算两遍，
 * 深色下的主色会与浅色下的色相漂移。
 *
 * [style] 对应设置页「调色板样式」：TonalSpot（默认，最克制）/ Vibrant /
 * Expressive / Neutral / Monochrome / Fidelity / Content / Rainbow / FruitSalad。
 */
private fun buildScheme(
    dark: Boolean,
    seed: Color,
    pureBlack: Boolean,
): ColorScheme {
    /*
     * 固定 [PaletteStyle.Fidelity]。
     *
     * 之前把 style 暴露成设置项，结果是**两个入口在同时决定颜色**：
     * 「主题色」选色相、「调色板样式」选推导方式 —— 用户改了一个看不到预期效果，
     * 因为另一个在拦着。现在只留「主题色」，推导方式由这里固定。
     *
     * 为什么是 Fidelity 而不是默认的 TonalSpot：
     * TonalSpot 会把 primary 的 chroma **钳到 36**，于是 #4D6BFE 这种本身很艳的
     * 品牌色被洗成灰蓝 —— 那正是"饱和度不够"的根源。Fidelity 直接用 seed 的
     * hue + chroma；不用 Vibrant 是因为它会给「灰」这种低饱和 seed 强行加色。
     */
    val base = dynamicColorScheme(
        seedColor = seed,
        isDark = dark,
        style = PaletteStyle.Fidelity,
    )

    /*
     * ══════════════════════════════════════════════════════════
     *  **中性色一律收归中性**：主题色只染"本来就有颜色的东西"
     * ══════════════════════════════════════════════════════════
     *
     * MCU 生成的是**整套**色板：不只 primary 带色，连 background / surface /
     * outline / onSurface 都是"带一点点色相的中性色"。Material 自己的演示里
     * 这很协调，但在这里用户看到的是"整个界面都被染色了" —— 换个主题色，
     * 背景和卡片跟着泛蓝 / 泛绿。
     *
     * 覆盖规则：
     * - **保留** base 的 primary / secondary / tertiary / error 及其容器与 on*
     * - **覆盖** 所有中性角色（背景、表面、描边、正文色）为固定灰阶
     *
     * 结果：背景永远纯白（浅色）/ 纯黑或近黑（深色），卡片是中性灰，
     * 只有按钮、选中态、用户气泡、开关这些本来有颜色的地方跟着主题色变。
     *
     * `surfaceTint` 一起覆盖成透明：M3 用它给 elevated 表面叠色，
     * 保留的话卡片会泛出主题色 —— 正是要避免的。
     */
    return if (dark) {
        base.copy(
            background = if (pureBlack) Color.Black else Color(0xFF0E0E10),
            surface = if (pureBlack) Color.Black else Color(0xFF0E0E10),
            surfaceDim = if (pureBlack) Color.Black else Color(0xFF0B0B0D),
            surfaceBright = Color(0xFF3A3A3E),
            surfaceVariant = Color(0xFF1E1F22),
            surfaceContainerLowest = if (pureBlack) Color.Black else Color(0xFF09090B),
            surfaceContainerLow = if (pureBlack) Color(0xFF080808) else Color(0xFF141417),
            surfaceContainer = if (pureBlack) Color(0xFF101010) else Color(0xFF1A1A1E),
            surfaceContainerHigh = if (pureBlack) Color(0xFF181818) else Color(0xFF222226),
            surfaceContainerHighest = if (pureBlack) Color(0xFF202020) else Color(0xFF2A2A2F),
            surfaceTint = Color.Transparent,
            onBackground = Color(0xFFE9E9EC),
            onSurface = Color(0xFFE9E9EC),
            onSurfaceVariant = Color(0xFF9DA2AB),
            outline = Color(0xFF3C3F45),
            outlineVariant = Color(0xFF2A2C31),
            inverseSurface = Color(0xFFE9E9EC),
            inverseOnSurface = Color(0xFF17181B),
            scrim = Color.Black,
        )
    } else {
        base.copy(
            background = Color.White,
            surface = Color.White,
            surfaceDim = Color(0xFFDDDEE2),
            surfaceBright = Color.White,
            surfaceVariant = Color(0xFFF1F2F4),
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = Color(0xFFF8F8FA),
            surfaceContainer = Color(0xFFF2F3F5),
            surfaceContainerHigh = Color(0xFFEBECEF),
            surfaceContainerHighest = Color(0xFFE4E5E9),
            surfaceTint = Color.Transparent,
            onBackground = Color(0xFF17181B),
            onSurface = Color(0xFF17181B),
            onSurfaceVariant = Color(0xFF5B6068),
            outline = Color(0xFF8B8F97),
            outlineVariant = Color(0xFFE3E4E8),
            inverseSurface = Color(0xFF303135),
            inverseOnSurface = Color(0xFFF4F4F6),
            scrim = Color.Black,
        )
    }
}

@Composable
fun SimpleChatTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    accent: AccentColor = AccentColor.BLUE,
    fontScale: Float = 1f,
    /** 深色下把中性底压到纯黑（OLED 省电 / 观感）。 */
    pureBlack: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    /*
     * 色板按 (seed, 明暗, style, 纯黑) 缓存：生成一整套 tonal palette 要跑
     * 完整的 HCT 色彩空间换算，比一次重组贵得多 —— 不缓存的话每次重组都重算。
     */
    val scheme = remember(dark, accent, pureBlack) {
        buildScheme(
            dark = dark,
            seed = accent.primary(dark = false),
            pureBlack = pureBlack,
        )
    }
    val colors = remember(scheme) { chatColors(scheme) }

    val baseDensity = LocalDensity.current

    CompositionLocalProvider(
        LocalChatColors provides colors,
        LocalIsDarkTheme provides dark,
        LocalAccentColor provides accent,
        LocalDensity provides Density(
            density = baseDensity.density,
            fontScale = baseDensity.fontScale * fontScale,
        ),
    ) {
        MaterialTheme(
            colorScheme = scheme,
            shapes = SimpleChatShapes,
            typography = SimpleChatTypography,
        ) {
            CompositionLocalProvider(LocalContentColor provides scheme.onBackground, content = content)
        }
    }
}
