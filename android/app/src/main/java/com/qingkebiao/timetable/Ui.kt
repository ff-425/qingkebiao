package com.qingkebiao.timetable

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.LocalDate

/*
 * 界面零件。刻意避开 M3 的 DatePicker / TimePicker / ModalBottomSheet ——
 * 那几个到现在还挂着 @ExperimentalMaterial3Api，签名会变。
 * 自己用步进按钮拼，零 API 风险，选日期选时间也够快。
 *
 * 所有尺寸都从下面这两组常量里取，不要在页面里随手写 9.dp、11.5.sp ——
 * 之前界面看着潦草，大半是因为十几种字号、十几种间距混在一起。
 */

/** 间距只用 4 的倍数；圆角分三档。 */
object Dim {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp

    /** 按钮、标签、输入框 */
    val rSmall = 10.dp
    /** 卡片 */
    val rCard = 16.dp
    /** 底部面板顶上那两个角 */
    val rSheet = 24.dp

    /** 可点区域的最小高度 */
    val touch = 44.dp
}

/** 字号五档。9、10sp 在手机上基本看不清，不再用。 */
object Fs {
    val display = 22.sp
    val title = 17.sp
    val body = 14.sp
    val caption = 12.sp
    val micro = 11.sp
}

/** 等宽数字。时间要上下对齐，但没必要把整行中文都换成等宽字体。 */
val NumStyle = TextStyle(fontFeatureSettings = "tnum")

val FieldShape = RoundedCornerShape(12.dp)

/* ----------------------------------------------------------- 容器 */

/**
 * 从底部弹出的面板。标题 + 关闭，内容可滚动，footer 固定在底部不跟着滚。
 */
@Composable
fun Sheet(
    pal: Palette,
    title: String,
    onClose: () -> Unit,
    footer: (@Composable () -> Unit)? = null,
    body: @Composable () -> Unit
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        val shown = remember { MutableTransitionState(false).apply { targetState = true } }
        val maxH = (LocalConfiguration.current.screenHeightDp * 0.9f).dp
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            // 点面板外面关掉。放在面板的兄弟位置而不是父级，
            // 否则点到面板里的空白处也会被它接住
            Box(
                Modifier.fillMaxSize().clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClose
                )
            )
            AnimatedVisibility(
                visibleState = shown,
                enter = slideInVertically { it / 4 } + fadeIn()
            ) {
                Surface(
                    color = pal.panel,
                    shape = RoundedCornerShape(topStart = Dim.rSheet, topEnd = Dim.rSheet),
                    modifier = Modifier.fillMaxWidth().widthIn(max = 640.dp).heightIn(max = maxH)
                ) {
                    Column(
                        Modifier.windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                    ) {
                        Box(
                            Modifier.fillMaxWidth().padding(top = Dim.s),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(Modifier.size(36.dp, 4.dp).background(pal.rule, CircleShape))
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(start = 20.dp, end = Dim.s, top = Dim.xs),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                title, color = pal.ink, fontSize = Fs.title,
                                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)
                            )
                            IconBtn(pal, Icons.Default.Close, "关闭", onClick = onClose)
                        }
                        Column(
                            Modifier
                                .weight(1f, fill = false)
                                .verticalScroll(rememberScrollState())
                                .padding(start = 20.dp, end = 20.dp, top = Dim.s, bottom = Dim.xl)
                        ) { body() }
                        if (footer != null) {
                            Box(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = Dim.l)) {
                                footer()
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 白底圆角卡片。页面上"一块一块"的内容都装在这里面，而不是用分隔线切。 */
@Composable
fun Card(
    pal: Palette,
    modifier: Modifier = Modifier,
    color: Color = pal.panel,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dim.rCard))
            .background(color),
        content = content
    )
}

/* ----------------------------------------------------------- 按钮 */

/** 主按钮：实心，一屏最多一个。 */
@Composable
fun PrimaryButton(
    pal: Palette,
    label: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier
            .heightIn(min = Dim.touch)
            .clip(RoundedCornerShape(Dim.rSmall))
            .background(if (enabled) pal.ink else pal.faint.copy(alpha = 0.5f))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label, color = pal.paper, fontSize = Fs.body, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

/** 次按钮：描边。名字沿用旧的，调用处太多。 */
@Composable
fun OutlineChip(
    pal: Palette,
    label: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier
            .heightIn(min = 38.dp)
            .clip(RoundedCornerShape(Dim.rSmall))
            .border(1.dp, pal.rule, RoundedCornerShape(Dim.rSmall))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (enabled) pal.ink2 else pal.faint.copy(alpha = 0.5f),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

/** 会删东西的按钮。 */
@Composable
fun DangerChip(pal: Palette, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .heightIn(min = 38.dp)
            .clip(RoundedCornerShape(Dim.rSmall))
            .background(pal.signal.copy(alpha = 0.1f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = pal.signal, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** 纯文字按钮：最轻的一级，用在"稍后""回到今天"这种地方。 */
@Composable
fun TextBtn(pal: Palette, label: String, color: Color = pal.ink2, onClick: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = 36.dp)
            .clip(RoundedCornerShape(Dim.rSmall))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** 圆形图标按钮，40dp。 */
@Composable
fun IconBtn(
    pal: Palette,
    icon: ImageVector,
    desc: String,
    enabled: Boolean = true,
    filled: Boolean = false,
    tint: Color = pal.ink2,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (filled) pal.panel else Color.Transparent)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon, contentDescription = desc,
            tint = if (enabled) tint else pal.faint.copy(alpha = 0.4f),
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
fun QSwitch(pal: Palette, checked: Boolean, onChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = pal.panel,
            checkedTrackColor = pal.ink,
            checkedBorderColor = pal.ink,
            uncheckedThumbColor = pal.faint,
            uncheckedTrackColor = pal.panel2,
            uncheckedBorderColor = pal.rule
        )
    )
}

/* ----------------------------------------------------------- 文字 */

@Composable
fun Tag(pal: Palette, label: String, bg: Color) {
    Box(
        Modifier
            .background(bg, RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp)
    ) {
        Text(label, color = pal.paper, fontSize = Fs.micro, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** 面板里的小节标题。 */
@Composable
fun Label(pal: Palette, text: String) {
    Text(text, color = pal.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(Dim.s))
}

@Composable
fun Hint(pal: Palette, text: String) {
    Text(text, color = pal.muted, fontSize = Fs.caption, lineHeight = 18.sp)
}

@Composable
fun MsgBox(pal: Palette, text: String, error: Boolean = false) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(
                if (error) pal.warn.copy(alpha = 0.12f) else pal.panel2,
                RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 14.dp, vertical = Dim.m)
    ) {
        Text(
            text,
            color = if (error) pal.warn else pal.ink2,
            fontSize = 13.sp,
            lineHeight = 19.sp
        )
    }
}

@Composable
fun DetailRow(pal: Palette, key: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(key, color = pal.muted, fontSize = 13.sp, modifier = Modifier.width(72.dp))
        Text(value, color = pal.ink, fontSize = Fs.body, modifier = Modifier.weight(1f))
    }
}

/* ----------------------------------------------------------- 设置列表 */

/** 设置页里的一组：组名 + 一张卡片。 */
@Composable
fun SettingsGroup(pal: Palette, title: String?, content: @Composable ColumnScope.() -> Unit) {
    if (title != null) {
        Text(
            title, color = pal.muted, fontSize = Fs.caption, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = Dim.xs, bottom = Dim.s)
        )
    }
    Card(pal, content = content)
    Spacer(Modifier.height(20.dp))
}

/**
 * 设置里的一行：左边标题 + 副标题，右边放开关、步进器或者一个箭头。
 * 给了 onClick 又没给 trailing，就自动显示箭头，表示"点进去还有"。
 */
@Composable
fun SettingItem(
    pal: Palette,
    title: String,
    sub: String? = null,
    titleColor: Color = pal.ink,
    badge: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = Dim.l, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = titleColor, fontSize = 15.sp)
                if (badge) {
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.size(7.dp).background(pal.signal, CircleShape))
                }
            }
            if (sub != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    sub, color = pal.muted, fontSize = Fs.caption, lineHeight = 17.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(Dim.m))
            trailing()
        } else if (onClick != null) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                tint = pal.faint, modifier = Modifier.size(20.dp)
            )
        }
    }
}

/* ----------------------------------------------------------- 表单 */

@Composable
fun StepButton(pal: Palette, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(pal.panel2)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (enabled) pal.ink2 else pal.faint.copy(alpha = 0.4f),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

/** 一行带标题的控件槽位：左边标题+副标题，右边内容。 */
@Composable
fun FieldRow(pal: Palette, title: String, sub: String? = null, content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = pal.ink, fontSize = Fs.body)
            if (sub != null) {
                Text(sub, color = pal.muted, fontSize = Fs.caption, lineHeight = 17.sp)
            }
        }
        Spacer(Modifier.width(Dim.m))
        content()
    }
}

/* ----------------------------------------------------------- 步进控件 */

/** 日期：‹‹ 一周  ‹ 一天  [显示]  一天 ›  一周 ›› */
@Composable
fun DateStepper(pal: Palette, value: LocalDate, onChange: (LocalDate) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepButton(pal, "«") { onChange(value.minusWeeks(1)) }
        Spacer(Modifier.width(Dim.xs))
        StepButton(pal, "‹") { onChange(value.minusDays(1)) }
        Column(
            Modifier.width(104.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "${value.monthValue}月${value.dayOfMonth}日",
                color = pal.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                style = NumStyle, maxLines = 1
            )
            Text(
                "${value.abbr()} · ${value.year}",
                color = pal.muted, fontSize = Fs.micro, style = NumStyle, maxLines = 1
            )
        }
        StepButton(pal, "›") { onChange(value.plusDays(1)) }
        Spacer(Modifier.width(Dim.xs))
        StepButton(pal, "»") { onChange(value.plusWeeks(1)) }
    }
}

/** 时间：‹ 5 分钟  [HH:MM]  5 分钟 › ，长间隔用 30 分钟那对。 */
@Composable
fun TimeStepper(pal: Palette, minute: Int, onChange: (Int) -> Unit) {
    fun shift(d: Int) = onChange(((minute + d) % 1440 + 1440) % 1440)
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepButton(pal, "«") { shift(-30) }
        Spacer(Modifier.width(Dim.xs))
        StepButton(pal, "‹") { shift(-5) }
        Box(Modifier.width(64.dp), contentAlignment = Alignment.Center) {
            Text(
                minute.hhmm(),
                color = pal.ink,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                style = NumStyle,
                maxLines = 1
            )
        }
        StepButton(pal, "›") { shift(5) }
        Spacer(Modifier.width(Dim.xs))
        StepButton(pal, "»") { shift(30) }
    }
}

@Composable
fun IntStepper(
    pal: Palette,
    value: Int,
    min: Int,
    max: Int,
    step: Int = 1,
    suffix: String = "",
    onChange: (Int) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepButton(pal, "−", value > min) { onChange((value - step).coerceAtLeast(min)) }
        Box(Modifier.width(60.dp), contentAlignment = Alignment.Center) {
            Text(
                "$value$suffix",
                color = pal.ink,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                style = NumStyle,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
        StepButton(pal, "+", value < max) { onChange((value + step).coerceAtMost(max)) }
    }
}

/** 一排可点的小标签，用来快速选"第几节"。 */
@Composable
fun ChipRow(pal: Palette, labels: List<String>, onPick: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.chunked(4).forEachIndexed { rowIdx, row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEachIndexed { i, label ->
                    val idx = rowIdx * 4 + i
                    Box(
                        Modifier
                            .weight(1f)
                            .height(38.dp)
                            .clip(RoundedCornerShape(Dim.rSmall))
                            .background(pal.panel2)
                            .clickable { onPick(idx) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label,
                            color = pal.ink2,
                            fontSize = 13.sp,
                            style = NumStyle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                // 最后一行凑满，避免宽度被拉开
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
