package com.qingkebiao.timetable

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

/**
 * 加一节课 / 一个事件，也用于编辑已有的那一节。
 *
 * 存进去的都带 manual = true —— 重新导入学校课表时这些不会被覆盖掉。
 * 编辑只改当前这一次，不动同名课程的其他场次，这样行为可预期。
 */
@Composable
fun SessionEditorDialog(
    pal: Palette,
    d: Derived,
    existing: Session?,
    defaultDate: LocalDate,
    onClose: () -> Unit,
    onSave: (List<Session>) -> Unit,
    onDelete: (Session) -> Unit
) {
    val editing = existing != null
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var location by remember { mutableStateOf(existing?.location ?: "") }
    var teacher by remember { mutableStateOf(existing?.teacher ?: "") }
    var note by remember { mutableStateOf(existing?.note ?: "") }
    var date by remember {
        mutableStateOf(existing?.start?.toLocalDate() ?: defaultDate)
    }
    var startMin by remember { mutableIntStateOf(existing?.startMinute() ?: (8 * 60)) }
    var endMin by remember { mutableIntStateOf(existing?.endMinute() ?: (9 * 60 + 40)) }
    var repeatCount by remember { mutableIntStateOf(1) }
    var confirmDelete by remember { mutableStateOf(false) }

    // 结束时间永远在开始之后，改开始就把结束跟着推
    fun setStart(m: Int) {
        val dur = (endMin - startMin).coerceAtLeast(5)
        startMin = m
        endMin = (m + dur).coerceAtMost(24 * 60 - 5)
    }

    val periods = d.tt.periods.ifEmpty { DEFAULT_PERIODS }
    val maxRepeat = if (d.weeks > 0) (d.weeks - d.weekOf(date) + 1).coerceIn(1, 40) else 30

    Sheet(pal, if (editing) "编辑" else "添加课程 / 事件", onClose) {
        Column {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("名称", fontSize = 12.sp) },
                placeholder = { Text("高等数学 / 期末考试 / 社团例会", fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it },
                    label = { Text("地点", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = teacher,
                    onValueChange = { teacher = it },
                    label = { Text("教师", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(18.dp))
            Label(pal, "时间")
            Text(
                "日期" + if (d.weeks > 0) "  ·  第 ${d.weekOf(date)} 周" else "",
                color = pal.ink2, fontSize = 13.sp
            )
            Spacer(Modifier.height(6.dp))
            DateStepper(pal, date) { date = it }

            Spacer(Modifier.height(10.dp))
            FieldRow(pal, "开始") { TimeStepper(pal, startMin) { setStart(it) } }
            FieldRow(pal, "结束") { TimeStepper(pal, endMin) { endMin = it } }

            Spacer(Modifier.height(6.dp))
            Hint(pal, "或者直接点节次填时间（按设置里的作息表）：")
            Spacer(Modifier.height(5.dp))
            ChipRow(pal, periods.map { "第${it.index}节" }) { i ->
                val p = periods[i]
                startMin = p.startMin
                endMin = p.endMin
            }
            Spacer(Modifier.height(4.dp))
            Hint(pal, "连上两节：先点前一节，再长按后一节右边的「结束 »」调到位即可。")

            if (!editing) {
                Spacer(Modifier.height(18.dp))
                Label(pal, "重复")
                FieldRow(
                    pal,
                    "连续几周",
                    if (repeatCount <= 1) "只加这一次" else "从第 ${d.weekOf(date)} 周起连续 $repeatCount 周"
                ) {
                    IntStepper(pal, repeatCount, 1, maxRepeat, suffix = " 周") { repeatCount = it }
                }
            }

            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("备注", fontSize = 12.sp) },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                PrimaryButton(pal, if (editing) "保存" else "添加", enabled = title.isNotBlank()) {
                    val n = if (editing) 1 else repeatCount
                    val list = (0 until n).map { i ->
                        val dt = date.plusWeeks(i.toLong())
                        Session(
                            id = if (editing && i == 0) existing!!.id else newId(),
                            title = title.trim(),
                            location = location.trim(),
                            teacher = teacher.trim(),
                            note = note.trim(),
                            start = dt.atMinuteOfDay(startMin),
                            end = dt.atMinuteOfDay(maxOf(endMin, startMin + 5)),
                            manual = true
                        )
                    }
                    onSave(list)
                }
                Spacer(Modifier.weight(1f))
                if (editing) {
                    if (confirmDelete) {
                        DangerChip(pal, "确认删除") { onDelete(existing!!) }
                    } else {
                        DangerChip(pal, "删除") { confirmDelete = true }
                    }
                }
            }

            if (editing && existing!!.manual.not()) {
                Spacer(Modifier.height(10.dp))
                MsgBox(pal, "这节课来自导入的课表。保存后它会变成手动条目，" +
                        "以后重新导入学校课表不会再覆盖你的修改。")
            }
        }
    }
}

/**
 * 调休。国内校历常见两种情况：某天整天放假，或者某天按另一天的课上
 * （"10 月 11 日周六上 10 月 1 日周三的课"）。
 * 两者独立设置 —— 通常放假那天也要单独标一下放假。
 *
 * 哪一天调休是在这里选的，不限于"今天"：通知一般提前一两周发，
 * 看到的当天就应该能把后面那天设好。
 */
@Composable
fun DayOverrideDialog(
    pal: Palette,
    d: Derived,
    date: LocalDate,
    onClose: () -> Unit,
    onApply: (LocalDate, DayOverride?) -> Unit
) {
    var target by remember { mutableStateOf(date) }
    val current = d.overrideFor(target)
    var kind by remember { mutableStateOf(current?.kind) }   // null = 正常上课
    var followDate by remember {
        mutableStateOf(
            current?.followEpochDay?.let { LocalDate.ofEpochDay(it) } ?: date.minusDays(1)
        )
    }

    // 换了日期就显示那天已有的设置，而不是把上一天的选择带过去
    LaunchedEffect(target) {
        val ov = d.overrideFor(target)
        kind = ov?.kind
        followDate = ov?.followEpochDay?.let { LocalDate.ofEpochDay(it) } ?: target.minusDays(1)
    }

    val srcCount = d.tt.sessions.count { it.start.toLocalDate() == followDate && !it.manual }
    val ownCount = d.tt.sessions.count { it.start.toLocalDate() == target && !it.manual }

    Sheet(pal, "调休设置", onClose) {
        Column {
            Label(pal, "哪一天")
            DateStepper(pal, target) { target = it }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlineChip(pal, "今天") { target = LocalDate.now() }
                Spacer(Modifier.width(6.dp))
                Text(
                    "${target.abbr()}" +
                        (if (d.weeks > 0) " · 第 ${d.weekOf(target)} 周" else "") +
                        " · 原本 $ownCount 节课" +
                        (if (current != null) " · 已设过调休" else ""),
                    color = pal.faint, fontSize = 11.sp, maxLines = 1
                )
            }
            Spacer(Modifier.height(16.dp))

            listOf<Triple<OverrideKind?, String, String>>(
                Triple(null, "正常上课", "按这天本来的星期上课"),
                Triple(OverrideKind.HOLIDAY, "这天放假", "原本的课全部不上，手动加的事件仍然显示"),
                Triple(OverrideKind.FOLLOW, "按另一天的课上", "把来源日的课整体搬到这天，上课时刻不变")
            ).forEach { (k, name, desc) ->
                val on = kind == k
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            if (on) pal.signal.copy(alpha = 0.09f) else pal.panel2,
                            RoundedCornerShape(2.dp)
                        )
                        .clickable { kind = k }
                        .padding(11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(9.dp)
                            .background(
                                if (on) pal.signal else pal.faint.copy(alpha = 0.5f),
                                RoundedCornerShape(5.dp)
                            )
                    )
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            name,
                            color = if (on) pal.ink else pal.ink2,
                            fontSize = 13.sp,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal
                        )
                        Text(desc, color = pal.faint, fontSize = 11.sp, lineHeight = 15.sp)
                    }
                }
                Spacer(Modifier.height(6.dp))
            }

            if (kind == OverrideKind.FOLLOW) {
                Spacer(Modifier.height(8.dp))
                Label(pal, "上哪一天的课")
                DateStepper(pal, followDate) { followDate = it }
                Spacer(Modifier.height(8.dp))
                MsgBox(
                    pal,
                    if (srcCount > 0)
                        "$followDate ${followDate.abbr()} 有 $srcCount 节课，会全部搬到 $target ${target.abbr()}。"
                    else
                        "$followDate ${followDate.abbr()} 没有课，搬过来也是空的 —— 确认下日期对不对。",
                    error = srcCount == 0
                )
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(thickness = 1.dp, color = pal.ruleSoft)
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                PrimaryButton(pal, "应用到 $target") {
                    onApply(
                        target,
                        when (kind) {
                            null -> null        // 清除这天的调休
                            OverrideKind.HOLIDAY -> DayOverride(target.toEpochDay(), OverrideKind.HOLIDAY)
                            OverrideKind.FOLLOW -> DayOverride(
                                target.toEpochDay(),
                                OverrideKind.FOLLOW,
                                followDate.toEpochDay()
                            )
                        }
                    )
                }
                Spacer(Modifier.weight(1f))
                if (current != null) {
                    OutlineChip(pal, "清除调休") { onApply(target, null) }
                }
            }
        }
    }
}

/** 学期设置：开学第一周的周一 + 总周数，两者都可以完全手动定。 */
@Composable
fun TermSettings(pal: Palette, d: Derived, onApply: (Timetable) -> Unit) {
    val auto = d.tt.termStartEpochDay == null
    val autoWeeks = d.tt.termWeeks == null

    Label(pal, "学期")
    Hint(
        pal,
        "ICS 和教务系统网页都不带「第几周」这个信息，所以开学日期得由你定。" +
            "改这里，所有周次立刻跟着重算。"
    )
    Spacer(Modifier.height(10.dp))

    FieldRow(pal, "第 1 周的周一", if (auto) "当前：自动按最早一节课推算" else "当前：手动设定") {
        Spacer(Modifier.width(0.dp))
    }
    DateStepper(pal, d.termStart) {
        // 学期总是从周一算起，随手点到周中也自动归到那周的周一
        onApply(d.tt.copy(termStartEpochDay = it.mondayOf().toEpochDay()))
    }
    Spacer(Modifier.height(6.dp))
    Row {
        OutlineChip(pal, "归到本周周一") {
            onApply(d.tt.copy(termStartEpochDay = LocalDate.now().mondayOf().toEpochDay()))
        }
        Spacer(Modifier.width(8.dp))
        if (!auto) {
            OutlineChip(pal, "恢复自动推算") { onApply(d.tt.copy(termStartEpochDay = null)) }
        }
    }

    Spacer(Modifier.height(16.dp))
    FieldRow(
        pal,
        "总共几周",
        if (autoWeeks) "自动：按最后一节课推算出 ${d.weeks} 周" else "手动设定"
    ) {
        IntStepper(pal, d.weeks.coerceAtLeast(1), 1, 40, suffix = " 周") {
            onApply(d.tt.copy(termWeeks = it))
        }
    }
    if (!autoWeeks) {
        OutlineChip(pal, "恢复自动推算") { onApply(d.tt.copy(termWeeks = null)) }
    }
}

/**
 * 节次时间表编辑器。
 *
 * 教务系统的课表页只给"第几节"，不给时刻，所以这张表必须由用户定。
 * 上面提供"按等长重排"一键生成（绝大多数学校就是等长 + 固定课间），
 * 下面每一节还能单独微调，应付大课间、午休这种不规则安排。
 */
@Composable
fun PeriodEditorDialog(
    pal: Palette,
    periods: List<PeriodSlot>,
    onClose: () -> Unit,
    onApply: (List<PeriodSlot>) -> Unit
) {
    val work = remember {
        mutableStateListOf<PeriodSlot>().also { it.addAll(periods.ifEmpty { DEFAULT_PERIODS }) }
    }
    var lenMin by remember { mutableIntStateOf(45) }
    var gapMin by remember { mutableIntStateOf(5) }
    var amStart by remember { mutableIntStateOf(8 * 60) }
    var pmStart by remember { mutableIntStateOf(14 * 60) }
    var evStart by remember { mutableIntStateOf(18 * 60 + 30) }
    var amCount by remember { mutableIntStateOf(5) }
    var pmCount by remember { mutableIntStateOf(4) }
    var evCount by remember { mutableIntStateOf(3) }

    fun regenerate() {
        val out = ArrayList<PeriodSlot>()
        var idx = 1
        for ((start, count) in listOf(amStart to amCount, pmStart to pmCount, evStart to evCount)) {
            var t = start
            repeat(count) {
                out.add(PeriodSlot(idx, t, t + lenMin))
                t += lenMin + gapMin
                idx++
            }
        }
        work.clear()
        work.addAll(out)
    }

    Sheet(pal, "节次时间", onClose) {
        Column {
            Hint(
                pal,
                "教务系统只给「第几节」不给时刻，这张表决定课表上显示几点。" +
                    "改完已导入的课表会按新时间重算。"
            )

            Spacer(Modifier.height(16.dp))
            Label(pal, "按等长重排")
            FieldRow(pal, "每节时长") { IntStepper(pal, lenMin, 30, 60, 5, " 分") { lenMin = it } }
            FieldRow(pal, "课间") { IntStepper(pal, gapMin, 0, 30, 5, " 分") { gapMin = it } }
            FieldRow(pal, "上午起点", "共 $amCount 节") { TimeStepper(pal, amStart) { amStart = it } }
            FieldRow(pal, "上午几节") { IntStepper(pal, amCount, 0, 8) { amCount = it } }
            FieldRow(pal, "下午起点") { TimeStepper(pal, pmStart) { pmStart = it } }
            FieldRow(pal, "下午几节") { IntStepper(pal, pmCount, 0, 8) { pmCount = it } }
            FieldRow(pal, "晚上起点") { TimeStepper(pal, evStart) { evStart = it } }
            FieldRow(pal, "晚上几节") { IntStepper(pal, evCount, 0, 8) { evCount = it } }
            Spacer(Modifier.height(8.dp))
            OutlineChip(pal, "按上面重排") { regenerate() }

            Spacer(Modifier.height(20.dp))
            Label(pal, "逐节微调（共 ${work.size} 节）")
            work.forEachIndexed { i, ps ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(
                        "第 ${ps.index} 节   ${ps.startMin.hhmm()} – ${ps.endMin.hhmm()}",
                        color = pal.ink2, fontSize = 13.sp, fontFamily = FontFamily.Monospace
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TimeStepper(pal, ps.startMin) { m ->
                            val dur = (ps.endMin - ps.startMin).coerceAtLeast(5)
                            work[i] = ps.copy(startMin = m, endMin = (m + dur).coerceAtMost(24 * 60 - 1))
                        }
                        Spacer(Modifier.width(6.dp))
                        TimeStepper(pal, ps.endMin) { m ->
                            work[i] = ps.copy(endMin = maxOf(m, ps.startMin + 5))
                        }
                    }
                }
                HorizontalDivider(thickness = 1.dp, color = pal.ruleSoft)
            }

            Spacer(Modifier.height(16.dp))
            PrimaryButton(pal, "保存并重算课表") { onApply(work.toList()) }
        }
    }
}
