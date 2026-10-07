package com.qingkebiao.timetable

import androidx.compose.foundation.background
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

/**
 * 加一节课 / 一个事件，也用于编辑已有的那一节，或者照着某一节复制一份。
 *
 * 存进去的都带 manual = true —— 重新导入学校课表时这些不会被覆盖掉。
 * 编辑默认只改当前这一次；同一种安排（同星期、同时段、同地点）在别的周也有的话，
 * 可以选"应用到"哪几周，一次改完，不用一周一周点进去改。
 *
 * [existing] 必须是存着的原始那一节（不是调休搬过来显示的那份），id 和日期都按它来。
 * [template] 只在添加时用：复制一节课，名称地点老师时间都先填好，改个日期或时间就行。
 */
@Composable
fun SessionEditorDialog(
    pal: Palette,
    d: Derived,
    existing: Session?,
    defaultDate: LocalDate,
    onClose: () -> Unit,
    onSave: (List<Session>) -> Unit,
    onDelete: (Set<String>) -> Unit,
    template: Session? = null
) {
    val editing = existing != null
    val src = existing ?: template
    var title by remember { mutableStateOf(src?.title ?: "") }
    var location by remember { mutableStateOf(src?.location ?: "") }
    var teacher by remember { mutableStateOf(src?.teacher ?: "") }
    var note by remember { mutableStateOf(src?.note ?: "") }
    var date by remember {
        mutableStateOf(src?.start?.toLocalDate() ?: defaultDate)
    }
    var startMin by remember { mutableIntStateOf(src?.startMinute() ?: (8 * 60)) }
    var endMin by remember { mutableIntStateOf(src?.endMinute() ?: (9 * 60 + 40)) }
    var repeatCount by remember { mutableIntStateOf(1) }
    var confirmDelete by remember { mutableStateOf(false) }

    // 同一种安排在各周的那几节（包括这一节自己）。只有一节的话就不显示"应用到"
    val siblings = remember(existing) { existing?.let { d.siblingsOf(it) }.orEmpty() }
    var scope by remember { mutableStateOf(setOfNotNull(existing?.id)) }
    val targets: List<Session> = if (existing == null) emptyList() else
        (siblings.filter { it.id in scope } + existing).distinctBy { it.id }.sortedBy { it.start }

    // 结束时间永远在开始之后，改开始就把结束跟着推
    fun setStart(m: Int) {
        val dur = (endMin - startMin).coerceAtLeast(5)
        startMin = m
        endMin = (m + dur).coerceAtMost(24 * 60 - 5)
    }

    val periods = d.tt.periods.ifEmpty { DEFAULT_PERIODS }
    val maxRepeat = if (d.weeks > 0) (d.weeks - d.weekOf(date) + 1).coerceIn(1, 40) else 30

    fun save() {
        if (existing != null) {
            // 选中的每一周都按同一种方式改：日期挪了几天，其他周也挪几天；时间、地点这些直接换成新的。
            // origin 留着 —— 那边靠它记住"这节导入的课用户改过了"，重算课表时不再生成原样的一份
            val shift = date.toEpochDay() - existing.start.toLocalDate().toEpochDay()
            onSave(targets.map { sib ->
                val dt = sib.start.toLocalDate().plusDays(shift)
                sib.copy(
                    title = title.trim(),
                    location = location.trim(),
                    teacher = teacher.trim(),
                    note = note.trim(),
                    start = dt.atMinuteOfDay(startMin),
                    end = dt.atMinuteOfDay(maxOf(endMin, startMin + 5)),
                    manual = true
                )
            })
            return
        }
        val list = (0 until repeatCount).map { i ->
            val dt = date.plusWeeks(i.toLong())
            Session(
                id = newId(),
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

    val n = targets.size
    val header = when {
        editing -> "编辑"
        template != null -> "复制「${template.title}」"
        else -> "添加课程 / 事件"
    }

    Sheet(
        pal, header, onClose,
        footer = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (editing) {
                    if (confirmDelete) {
                        DangerChip(pal, if (n > 1) "确认删除 $n 节" else "确认删除", modifier = Modifier.height(Dim.touch)) {
                            onDelete(targets.map { it.id }.toSet())
                        }
                    } else {
                        DangerChip(pal, "删除", modifier = Modifier.height(Dim.touch)) { confirmDelete = true }
                    }
                    Spacer(Modifier.width(Dim.s))
                }
                PrimaryButton(
                    pal,
                    when {
                        editing && n > 1 -> "保存（$n 节）"
                        editing -> "保存"
                        else -> "添加"
                    },
                    enabled = title.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) { save() }
            }
        }
    ) {
        Column {
            if (template != null) {
                Hint(pal, "名称、地点、老师和时间都照原来那节填好了，改一下日期或时间就能添加。")
                Spacer(Modifier.height(Dim.s))
            }
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("名称") },
                placeholder = { Text("高等数学 / 期末考试 / 社团例会") },
                singleLine = true,
                shape = FieldShape,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(Dim.s))
            Row(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it },
                    label = { Text("地点") },
                    singleLine = true,
                    shape = FieldShape,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(Dim.s))
                OutlinedTextField(
                    value = teacher,
                    onValueChange = { teacher = it },
                    label = { Text("教师") },
                    singleLine = true,
                    shape = FieldShape,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(20.dp))
            Label(pal, "时间")
            Card(pal, color = pal.panel2) {
                Column(Modifier.padding(horizontal = Dim.l, vertical = Dim.m)) {
                    // 日期步进器比较宽，单独占一行，不和标题挤
                    Row(Modifier.fillMaxWidth().padding(top = Dim.xs), verticalAlignment = Alignment.CenterVertically) {
                        Text("日期", color = pal.ink, fontSize = Fs.body, modifier = Modifier.weight(1f))
                        if (d.weeks > 0) {
                            Text("第 ${d.weekOf(date)} 周", color = pal.muted, fontSize = Fs.caption)
                        }
                    }
                    Spacer(Modifier.height(Dim.s))
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        DateStepper(pal, date) { date = it }
                    }
                    Spacer(Modifier.height(Dim.xs))
                    FieldRow(pal, "开始") { TimeStepper(pal, startMin) { setStart(it) } }
                    FieldRow(pal, "结束") { TimeStepper(pal, endMin) { endMin = it } }
                }
            }

            Spacer(Modifier.height(Dim.m))
            Hint(pal, "或者直接点节次填时间（按设置里的作息表）：")
            Spacer(Modifier.height(Dim.s))
            ChipRow(pal, periods.map { "第${it.index}节" }) { i ->
                val p = periods[i]
                startMin = p.startMin
                endMin = p.endMin
            }
            Spacer(Modifier.height(6.dp))
            Hint(pal, "连上两节：先点前一节，再把「结束」往后调到位。")

            if (existing != null && siblings.size > 1) {
                Spacer(Modifier.height(20.dp))
                Label(pal, "应用到")
                Card(pal, color = pal.panel2) {
                    Column(Modifier.padding(horizontal = Dim.l, vertical = Dim.m)) {
                        val later = siblings.filter { it.start >= existing.start }.map { it.id }.toSet()
                        val all = siblings.map { it.id }.toSet()
                        ChoiceChips(
                            pal,
                            listOf("只改这一节", "这一节及以后", "全部 ${siblings.size} 周"),
                            when (scope) {
                                setOf(existing.id) -> 0
                                later -> 1
                                all -> 2
                                else -> -1
                            }
                        ) { i ->
                            scope = when (i) {
                                0 -> setOf(existing.id)
                                1 -> later
                                else -> all
                            }
                        }
                        Spacer(Modifier.height(Dim.m))
                        Hint(pal, "也可以一周一周点选（这一节本身总在里面）：")
                        Spacer(Modifier.height(Dim.s))
                        ToggleChips(
                            pal,
                            siblings.map { s ->
                                val sd = s.start.toLocalDate()
                                if (d.weeks > 0) "第${d.weekOf(sd)}周" else "${sd.monthValue}/${sd.dayOfMonth}"
                            },
                            siblings.indices.filter { siblings[it].id in scope }.toSet()
                        ) { i ->
                            val id = siblings[i].id
                            if (id != existing.id) scope = if (id in scope) scope - id else scope + id
                        }
                        Spacer(Modifier.height(Dim.s))
                        Text(
                            if (n > 1) "会改 $n 节。日期往后挪了几天，其他周也一起挪几天。"
                            else "只改这一节，其他周不动。",
                            color = pal.muted, fontSize = Fs.caption
                        )
                    }
                }
            }

            if (!editing) {
                Spacer(Modifier.height(20.dp))
                Label(pal, "重复")
                Card(pal, color = pal.panel2) {
                    Column(Modifier.padding(horizontal = Dim.l, vertical = Dim.xs)) {
                        FieldRow(
                            pal,
                            "连续几周",
                            if (repeatCount <= 1) "只加这一次" else "从第 ${d.weekOf(date)} 周起连续 $repeatCount 周"
                        ) {
                            IntStepper(pal, repeatCount, 1, maxRepeat, suffix = " 周") { repeatCount = it }
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("备注") },
                minLines = 2,
                maxLines = 4,
                shape = FieldShape,
                modifier = Modifier.fillMaxWidth()
            )

            if (editing && existing!!.manual.not()) {
                Spacer(Modifier.height(Dim.m))
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

    fun apply() {
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

    Sheet(
        pal, "调休设置", onClose,
        footer = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (current != null) {
                    OutlineChip(pal, "清除调休", modifier = Modifier.height(Dim.touch)) { onApply(target, null) }
                    Spacer(Modifier.width(Dim.s))
                }
                PrimaryButton(
                    pal, "应用到 ${target.monthValue}月${target.dayOfMonth}日",
                    modifier = Modifier.weight(1f)
                ) { apply() }
            }
        }
    ) {
        Column {
            Label(pal, "哪一天")
            Card(pal, color = pal.panel2) {
                Column(
                    Modifier.fillMaxWidth().padding(Dim.m),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    DateStepper(pal, target) { target = it }
                    Spacer(Modifier.height(Dim.s))
                    Text(
                        (if (d.weeks > 0) "第 ${d.weekOf(target)} 周 · " else "") +
                            "原本 $ownCount 节课" +
                            (if (current != null) " · 已设过调休" else ""),
                        color = pal.muted, fontSize = Fs.caption, maxLines = 1
                    )
                    if (target != LocalDate.now()) {
                        TextBtn(pal, "回到今天", color = pal.signal) { target = LocalDate.now() }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))

            Label(pal, "这天怎么上")
            listOf<Triple<OverrideKind?, String, String>>(
                Triple(null, "正常上课", "按这天本来的星期上课"),
                Triple(OverrideKind.HOLIDAY, "这天放假", "原本的课全部不上，手动加的事件仍然显示"),
                Triple(OverrideKind.FOLLOW, "按另一天的课上", "把来源日的课整体搬到这天，上课时刻不变")
            ).forEach { (k, name, desc) ->
                val on = kind == k
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (on) pal.ink.copy(alpha = 0.06f) else pal.panel2)
                        .border(1.dp, if (on) pal.ink else Color.Transparent, RoundedCornerShape(12.dp))
                        .clickable { kind = k }
                        .padding(horizontal = 14.dp, vertical = Dim.m),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 单选圈
                    Box(
                        Modifier
                            .size(18.dp)
                            .border(2.dp, if (on) pal.ink else pal.faint, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (on) Box(Modifier.size(8.dp).background(pal.ink, CircleShape))
                    }
                    Spacer(Modifier.width(Dim.m))
                    Column(Modifier.weight(1f)) {
                        Text(
                            name,
                            color = pal.ink,
                            fontSize = Fs.body,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal
                        )
                        Text(desc, color = pal.muted, fontSize = Fs.caption, lineHeight = 17.sp)
                    }
                }
                Spacer(Modifier.height(Dim.s))
            }

            if (kind == OverrideKind.FOLLOW) {
                Spacer(Modifier.height(Dim.m))
                Label(pal, "上哪一天的课")
                Card(pal, color = pal.panel2) {
                    Box(Modifier.fillMaxWidth().padding(Dim.m), contentAlignment = Alignment.Center) {
                        DateStepper(pal, followDate) { followDate = it }
                    }
                }
                Spacer(Modifier.height(Dim.s))
                MsgBox(
                    pal,
                    if (srcCount > 0)
                        "${followDate.monthValue}月${followDate.dayOfMonth}日 ${followDate.abbr()} 有 $srcCount 节课，" +
                            "会全部搬到 ${target.monthValue}月${target.dayOfMonth}日 ${target.abbr()}。"
                    else
                        "${followDate.monthValue}月${followDate.dayOfMonth}日 ${followDate.abbr()} 没有课，" +
                            "搬过来也是空的 —— 确认下日期对不对。",
                    error = srcCount == 0
                )
            }
        }
    }
}

/** 学期设置：开学第一周的周一 + 总周数，两者都可以完全手动定。 */
@Composable
fun TermSettings(pal: Palette, d: Derived, onApply: (Timetable) -> Unit) {
    val auto = d.tt.termStartEpochDay == null
    val autoWeeks = d.tt.termWeeks == null

    Hint(
        pal,
        "ICS 和教务系统网页都不带「第几周」，开学日期得由你定。改了之后所有周次立刻重算。"
    )
    Spacer(Modifier.height(Dim.m))

    Card(pal) {
        Column(Modifier.padding(Dim.l)) {
            Text("第 1 周的周一", color = pal.ink, fontSize = 15.sp)
            Text(
                if (auto) "自动：按最早一节课推算" else "手动设定",
                color = pal.muted, fontSize = Fs.caption
            )
            Spacer(Modifier.height(Dim.m))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                DateStepper(pal, d.termStart) {
                    // 学期总是从周一算起，随手点到周中也自动归到那周的周一
                    onApply(d.tt.copy(termStartEpochDay = it.mondayOf().toEpochDay()))
                }
            }
            Spacer(Modifier.height(Dim.m))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlineChip(pal, "设为本周周一") {
                    onApply(d.tt.copy(termStartEpochDay = LocalDate.now().mondayOf().toEpochDay()))
                }
                if (!auto) {
                    Spacer(Modifier.width(Dim.s))
                    TextBtn(pal, "恢复自动推算", color = pal.muted) {
                        onApply(d.tt.copy(termStartEpochDay = null))
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(Dim.m))
    Card(pal) {
        Column(Modifier.padding(horizontal = Dim.l, vertical = Dim.xs)) {
            FieldRow(
                pal,
                "总共几周",
                if (autoWeeks) "自动：按最后一节课推算" else "手动设定"
            ) {
                IntStepper(pal, d.weeks.coerceAtLeast(1), 1, 40, suffix = " 周") {
                    onApply(d.tt.copy(termWeeks = it))
                }
            }
            if (!autoWeeks) {
                TextBtn(pal, "恢复自动推算", color = pal.muted) { onApply(d.tt.copy(termWeeks = null)) }
                Spacer(Modifier.height(Dim.xs))
            }
        }
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

    Sheet(
        pal, "作息时间", onClose,
        footer = {
            PrimaryButton(pal, "保存并重算课表", modifier = Modifier.fillMaxWidth()) { onApply(work.toList()) }
        }
    ) {
        Column {
            Hint(
                pal,
                "教务系统只给「第几节」不给时刻，这张表决定课表上显示几点。改完已导入的课会按新时间重算。"
            )

            Spacer(Modifier.height(20.dp))
            Label(pal, "按等长生成")
            Card(pal, color = pal.panel2) {
                Column(Modifier.padding(horizontal = Dim.l, vertical = Dim.xs)) {
                    FieldRow(pal, "每节时长") { IntStepper(pal, lenMin, 30, 60, 5, " 分") { lenMin = it } }
                    FieldRow(pal, "课间") { IntStepper(pal, gapMin, 0, 30, 5, " 分") { gapMin = it } }
                    FieldRow(pal, "上午起点") { TimeStepper(pal, amStart) { amStart = it } }
                    FieldRow(pal, "上午几节") { IntStepper(pal, amCount, 0, 8) { amCount = it } }
                    FieldRow(pal, "下午起点") { TimeStepper(pal, pmStart) { pmStart = it } }
                    FieldRow(pal, "下午几节") { IntStepper(pal, pmCount, 0, 8) { pmCount = it } }
                    FieldRow(pal, "晚上起点") { TimeStepper(pal, evStart) { evStart = it } }
                    FieldRow(pal, "晚上几节") { IntStepper(pal, evCount, 0, 8) { evCount = it } }
                }
            }
            Spacer(Modifier.height(Dim.s))
            OutlineChip(pal, "按上面重新生成", modifier = Modifier.fillMaxWidth()) { regenerate() }

            Spacer(Modifier.height(Dim.xl))
            Label(pal, "逐节微调 · 共 ${work.size} 节")
            work.forEachIndexed { i, ps ->
                Card(pal, color = pal.panel2) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "第 ${ps.index} 节", color = pal.ink, fontSize = Fs.body,
                                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)
                            )
                            Text(
                                "${ps.endMin - ps.startMin} 分钟", color = pal.muted,
                                fontSize = Fs.caption, style = NumStyle
                            )
                        }
                        FieldRow(pal, "开始") {
                            TimeStepper(pal, ps.startMin) { m ->
                                val dur = (ps.endMin - ps.startMin).coerceAtLeast(5)
                                work[i] = ps.copy(startMin = m, endMin = (m + dur).coerceAtMost(24 * 60 - 1))
                            }
                        }
                        FieldRow(pal, "结束") {
                            TimeStepper(pal, ps.endMin) { m ->
                                work[i] = ps.copy(endMin = maxOf(m, ps.startMin + 5))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(Dim.s))
            }
        }
    }
}

/** 单选的一排按钮（三选一那种）。[selected] = -1 表示哪个都不是（逐周点选成了别的组合）。 */
@Composable
private fun ChoiceChips(pal: Palette, labels: List<String>, selected: Int, onPick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(Dim.rSmall))
                    .background(if (on) pal.ink else pal.panel)
                    .clickable { onPick(i) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label, color = if (on) pal.paper else pal.ink2, fontSize = 13.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** 可多选的一组小格子，一行五个。选中的是深底白字。 */
@Composable
private fun ToggleChips(pal: Palette, labels: List<String>, selected: Set<Int>, onToggle: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.chunked(5).forEachIndexed { r, row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEachIndexed { c, label ->
                    val i = r * 5 + c
                    val on = i in selected
                    Box(
                        Modifier
                            .weight(1f)
                            .height(34.dp)
                            .clip(RoundedCornerShape(Dim.rSmall))
                            .background(if (on) pal.ink else pal.panel)
                            .clickable { onToggle(i) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(label, color = if (on) pal.paper else pal.ink2, fontSize = 12.sp, style = NumStyle, maxLines = 1)
                    }
                }
                repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
