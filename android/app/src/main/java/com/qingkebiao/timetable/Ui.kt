package com.qingkebiao.timetable

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
 */

@Composable
fun Sheet(pal: Palette, title: String, onClose: () -> Unit, body: @Composable () -> Unit) {
    // usePlatformDefaultWidth = false 才能自己定宽度，默认那个窄框放不下表单
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            color = pal.panel,
            shape = RoundedCornerShape(4.dp),
            modifier = Modifier.fillMaxWidth(0.93f).heightIn(max = 580.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, color = pal.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    OutlineChip(pal, "关闭", true, onClose)
                }
                Spacer(Modifier.height(14.dp))
                Column(Modifier.verticalScroll(rememberScrollState())) { body() }
            }
        }
    }
}

@Composable
fun OutlineChip(pal: Palette, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .background(pal.panel2, RoundedCornerShape(2.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 9.dp, vertical = 8.dp)
    ) {
        Text(
            label,
            color = if (enabled) pal.ink2 else pal.faint.copy(alpha = 0.4f),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

@Composable
fun PrimaryButton(pal: Palette, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .background(if (enabled) pal.ink else pal.faint, RoundedCornerShape(2.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 9.dp)
    ) {
        Text(label, color = pal.paper, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun DangerChip(pal: Palette, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .background(pal.signal.copy(alpha = 0.12f), RoundedCornerShape(2.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 8.dp)
    ) {
        Text(label, color = pal.signal, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun Tag(pal: Palette, label: String, bg: Color) {
    Box(Modifier.background(bg, RoundedCornerShape(2.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Text(label, color = pal.paper, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
fun Label(pal: Palette, text: String) {
    Text(text, color = pal.faint, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(7.dp))
}

@Composable
fun Hint(pal: Palette, text: String) {
    Text(text, color = pal.muted, fontSize = 12.sp, lineHeight = 17.sp)
}

@Composable
fun MsgBox(pal: Palette, text: String, error: Boolean = false) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(
                if (error) pal.signal.copy(alpha = 0.1f) else pal.panel2,
                RoundedCornerShape(2.dp)
            )
            .padding(10.dp)
    ) {
        Text(
            text,
            color = if (error) pal.signal else pal.ink2,
            fontSize = 12.sp,
            lineHeight = 17.sp
        )
    }
}

@Composable
fun DetailRow(pal: Palette, key: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Text(key, color = pal.faint, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(2.dp))
        Text(value, color = pal.ink2, fontSize = 13.sp)
    }
    HorizontalDivider(thickness = 1.dp, color = pal.ruleSoft)
}

@Composable
fun StepButton(pal: Palette, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .size(34.dp)
            .background(pal.panel2, RoundedCornerShape(2.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (enabled) pal.ink2 else pal.faint.copy(alpha = 0.4f),
            fontSize = 15.sp,
            maxLines = 1
        )
    }
}

/** 一行带标题的控件槽位：左边标题+副标题，右边内容。 */
@Composable
fun FieldRow(pal: Palette, title: String, sub: String? = null, content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = pal.ink2, fontSize = 13.sp)
            if (sub != null) {
                Text(sub, color = pal.faint, fontSize = 11.sp, lineHeight = 15.sp)
            }
        }
        Spacer(Modifier.width(10.dp))
        content()
    }
}

/* ----------------------------------------------------------- 步进控件 */

/** 日期：‹‹ 一周  ‹ 一天  [显示]  一天 ›  一周 ›› */
@Composable
fun DateStepper(pal: Palette, value: LocalDate, onChange: (LocalDate) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepButton(pal, "«") { onChange(value.minusWeeks(1)) }
        Spacer(Modifier.width(3.dp))
        StepButton(pal, "‹") { onChange(value.minusDays(1)) }
        Column(
            Modifier.width(96.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                value.toString(),
                color = pal.ink,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1
            )
            Text(value.abbr(), color = pal.faint, fontSize = 10.sp, maxLines = 1)
        }
        StepButton(pal, "›") { onChange(value.plusDays(1)) }
        Spacer(Modifier.width(3.dp))
        StepButton(pal, "»") { onChange(value.plusWeeks(1)) }
    }
}

/** 时间：‹ 5 分钟  [HH:MM]  5 分钟 › ，长间隔用 30 分钟那对。 */
@Composable
fun TimeStepper(pal: Palette, minute: Int, onChange: (Int) -> Unit) {
    fun shift(d: Int) = onChange(((minute + d) % 1440 + 1440) % 1440)
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepButton(pal, "«") { shift(-30) }
        Spacer(Modifier.width(3.dp))
        StepButton(pal, "‹") { shift(-5) }
        Box(Modifier.width(56.dp), contentAlignment = Alignment.Center) {
            Text(
                minute.hhmm(),
                color = pal.ink,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
        StepButton(pal, "›") { shift(5) }
        Spacer(Modifier.width(3.dp))
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
        Box(Modifier.width(52.dp), contentAlignment = Alignment.Center) {
            Text(
                "$value$suffix",
                color = pal.ink,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
        StepButton(pal, "+", value < max) { onChange((value + step).coerceAtMost(max)) }
    }
}

/** 一排可点的小标签，用来快速选"第几节"。 */
@Composable
fun ChipRow(pal: Palette, labels: List<String>, onPick: (Int) -> Unit) {
    Column {
        labels.chunked(6).forEachIndexed { rowIdx, row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEachIndexed { i, label ->
                    val idx = rowIdx * 6 + i
                    Box(
                        Modifier
                            .weight(1f)
                            .background(pal.panel2, RoundedCornerShape(2.dp))
                            .clickable { onPick(idx) }
                            .padding(vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label,
                            color = pal.ink2,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                // 最后一行凑满，避免宽度被拉开
                repeat(6 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
