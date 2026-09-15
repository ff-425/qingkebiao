package com.qingkebiao.timetable

import androidx.compose.ui.graphics.Color

/** 和网页版同一套色：冷灰绿印刷底、发丝线、信号红只留给"现在"和"今天"。 */
data class Palette(
    val dark: Boolean,
    val paper: Color,
    val panel: Color,
    val panel2: Color,
    val ink: Color,
    val ink2: Color,
    val muted: Color,
    val faint: Color,
    val rule: Color,
    val ruleSoft: Color,
    val signal: Color
)

val LightPalette = Palette(
    dark = false,
    paper = Color(0xFFEBEEEC),
    panel = Color(0xFFFAFBFA),
    panel2 = Color(0xFFF2F4F3),
    ink = Color(0xFF111614),
    ink2 = Color(0xFF3C4643),
    muted = Color(0xFF69736F),
    faint = Color(0xFF96A09C),
    rule = Color(0xFFD2D8D5),
    ruleSoft = Color(0xFFE2E7E4),
    signal = Color(0xFFE03A2F)
)

val DarkPalette = Palette(
    dark = true,
    paper = Color(0xFF101312),
    panel = Color(0xFF171B1A),
    panel2 = Color(0xFF1D2221),
    ink = Color(0xFFE4E8E6),
    ink2 = Color(0xFFC3CAC7),
    muted = Color(0xFF8B9490),
    faint = Color(0xFF646D6A),
    rule = Color(0xFF272D2B),
    ruleSoft = Color(0xFF1F2524),
    signal = Color(0xFFFF5A4D)
)

/**
 * 手挑的十个色相，像地铁线路色：彼此分得开，且避开在浅底上发虚的黄绿带。
 * 课程超过十门时整环偏移 11°，继续错开。
 */
private val HUE_RING = listOf(352f, 22f, 42f, 96f, 162f, 196f, 218f, 256f, 288f, 322f)

fun hueForIndex(i: Int): Float =
    (HUE_RING[i % HUE_RING.size] + (i / HUE_RING.size) * 11f) % 360f

/** 课程名 → 色相。按名字排序后分配，保证同一份课表每次打开颜色一致。 */
fun courseHues(titles: List<String>): Map<String, Float> =
    titles.distinct().sorted().withIndex().associate { (i, t) -> t to hueForIndex(i) }

fun blockFill(hue: Float, dark: Boolean): Color =
    if (dark) Color.hsl(hue, 0.34f, 0.21f) else Color.hsl(hue, 0.66f, 0.86f)

fun blockEdge(hue: Float, dark: Boolean): Color =
    if (dark) Color.hsl(hue, 0.56f, 0.56f) else Color.hsl(hue, 0.52f, 0.48f)

/**
 * 已经上完的课降透明度。0.45 在浅色底上正好，但压在深色底上几乎看不见，
 * 所以暗色模式抬高一档。这是在模拟器上切暗色实际看出来的。
 */
fun pastAlpha(pal: Palette): Float = if (pal.dark) 0.58f else 0.45f

fun blockText(hue: Float, dark: Boolean): Color =
    if (dark) Color.hsl(hue, 0.62f, 0.84f) else Color.hsl(hue, 0.46f, 0.24f)
