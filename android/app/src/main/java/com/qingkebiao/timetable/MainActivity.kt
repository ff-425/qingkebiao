package com.qingkebiao.timetable

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qingkebiao.timetable.widget.refreshWidgets
import com.qingkebiao.timetable.widget.scheduleWidgetRefresh
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    /**
     * 每次回到前台 +1。
     * Activity 不会被销毁，Compose 的状态就一直留着 —— 上次翻到第 12 周，
     * 明天打开还是第 12 周。课表这种东西，打开就该是今天。
     */
    private val resumeTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 全屏到状态栏/导航栏下面，再用 safeDrawing 把内容让开 ——
        // 这是网页版里 env(safe-area-inset-*) 那套事情的原生做法
        enableEdgeToEdge()
        scheduleWidgetRefresh(this)
        setContent { App(resumeTick.intValue) }
    }

    override fun onStart() {
        super.onStart()
        resumeTick.intValue++
    }
}

private enum class ViewMode { Day, Week }

@Composable
private fun App(resumeTick: Int) {
    val dark = isSystemInDarkTheme()
    val pal = remember(dark) { if (dark) DarkPalette else LightPalette }

    val scheme = remember(pal) {
        if (pal.dark) darkColorScheme(
            background = pal.paper, surface = pal.panel, surfaceVariant = pal.panel2,
            onBackground = pal.ink, onSurface = pal.ink, onSurfaceVariant = pal.ink2,
            primary = pal.signal, onPrimary = pal.paper, outline = pal.rule
        ) else lightColorScheme(
            background = pal.paper, surface = pal.panel, surfaceVariant = pal.panel2,
            onBackground = pal.ink, onSurface = pal.ink, onSurfaceVariant = pal.ink2,
            primary = pal.signal, onPrimary = pal.paper, outline = pal.rule
        )
    }

    MaterialTheme(colorScheme = scheme) {
        Surface(color = pal.paper, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                Home(pal, resumeTick)
            }
        }
    }
}

@Composable
private fun Home(pal: Palette, resumeTick: Int) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var tt by remember { mutableStateOf(Timetable()) }
    var view by remember { mutableStateOf(ViewMode.Day) }
    var weekIdx by remember { mutableIntStateOf(1) }
    var dayDate by remember { mutableStateOf(LocalDate.now()) }
    var hourDp by remember { mutableStateOf(64.dp) }
    var showImport by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<Session?>(null) }
    var editorFor by remember { mutableStateOf<Session?>(null) }
    var editorOpen by remember { mutableStateOf(false) }
    var overrideDate by remember { mutableStateOf<LocalDate?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    val d = remember(tt) { Derived(tt) }
    val hues = remember(tt) { courseHues(tt.sessions.map { it.title }) }

    // 首次打开先装示例，让人立刻看见这东西长什么样（不落盘，导入真课表即覆盖）
    LaunchedEffect(Unit) {
        val stored = Store.load(ctx)
        tt = if (stored.sessions.isNotEmpty()) stored else stored.copy(
            sessions = runCatching { Ics.parse(DEMO_ICS) }.getOrDefault(emptyList()),
            sourceLabel = "示例课表",
            showWeekend = false
        )
    }

    LaunchedEffect(tt.sessions.size, tt.termStartEpochDay, tt.termWeeks) {
        if (tt.sessions.isNotEmpty()) {
            weekIdx = d.clampWeek(d.weekOf(LocalDate.now()))
        }
    }

    // 每次回到前台都归位到今天 / 本周，不保留上次翻到哪儿了
    LaunchedEffect(resumeTick) {
        val today = LocalDate.now()
        view = ViewMode.Day
        dayDate = today
        weekIdx = d.clampWeek(d.weekOf(today))
    }

    // "进行中""还有几分钟"这些要自己走，半分钟一拍足够
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    fun commit(next: Timetable) {
        tt = next
        scope.launch {
            Store.save(ctx, next)
            refreshWidgets(ctx)
        }
    }

    /** 调休搬过来的课，id 上带了 "@日期" 后缀，改的时候要落回原始那节。 */
    fun baseId(s: Session) = s.id.substringBefore('@')

    fun saveSessions(list: List<Session>) {
        val ids = list.map { it.id }.toSet()
        commit(tt.copy(sessions = (tt.sessions.filterNot { it.id in ids } + list).sortedBy { it.start }))
    }

    fun deleteSession(s: Session) {
        val id = baseId(s)
        commit(tt.copy(sessions = tt.sessions.filterNot { baseId(it) == id }))
    }

    fun setOverride(date: LocalDate, ov: DayOverride?) {
        val rest = tt.overrides.filterNot { it.dateEpochDay == date.toEpochDay() }
        commit(tt.copy(overrides = rest + listOfNotNull(ov)))
    }

    fun step(dir: Int) {
        if (view == ViewMode.Day) {
            val nd = dayDate.plusDays(dir.toLong())
            val w = d.weekOf(nd)
            if (w < 1 || w > d.weeks) return
            dayDate = nd
            weekIdx = w
        } else {
            weekIdx = d.clampWeek(weekIdx + dir)
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(
            pal = pal, d = d, view = view, weekIdx = weekIdx, dayDate = dayDate,
            onStep = { step(it) },
            onToday = {
                val today = LocalDate.now()
                dayDate = today
                weekIdx = d.clampWeek(d.weekOf(today))
            },
            onView = {
                view = it
                if (it == ViewMode.Day) {
                    val today = LocalDate.now()
                    dayDate = if (d.weeks > 0 && d.weekOf(today) == weekIdx) today
                    else d.mondayOfWeek(weekIdx)
                } else if (d.weeks > 0) {
                    weekIdx = d.clampWeek(d.weekOf(dayDate))
                }
            },
            onAdd = { editorFor = null; editorOpen = true },
            onImport = { showImport = true },
            onSettings = { showSettings = true }
        )

        NextUpStrip(pal, d, now)

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                d.isEmpty -> EmptyState(
                    pal,
                    onImport = { showImport = true },
                    onAdd = { editorFor = null; editorOpen = true },
                    onDemo = {
                        runCatching {
                            commit(
                                tt.copy(
                                    sessions = Ics.parse(DEMO_ICS),
                                    showWeekend = false,
                                    sourceLabel = "示例课表"
                                )
                            )
                        }
                    }
                )

                view == ViewMode.Day -> DayList(
                    pal, d, hues, dayDate, now,
                    onPick = { detail = it },
                    onOverride = { overrideDate = dayDate }
                )

                else -> WeekGrid(pal, d, hues, weekIdx, hourDp, now) { detail = it }
            }
        }
    }

    if (showImport) {
        ImportDialog(
            pal, tt,
            onClose = { showImport = false },
            onImported = { tt = it; scope.launch { refreshWidgets(ctx) } }
        )
    }
    if (showSettings) {
        SettingsDialog(
            pal = pal, d = d, hues = hues, hourDp = hourDp,
            onHourDp = { hourDp = it },
            onClose = { showSettings = false },
            onApply = { commit(it) },
            onEditOverride = { overrideDate = it }
        )
    }
    if (editorOpen) {
        SessionEditorDialog(
            pal = pal, d = d, existing = editorFor,
            defaultDate = if (view == ViewMode.Day) dayDate else d.mondayOfWeek(weekIdx),
            onClose = { editorOpen = false },
            onSave = { saveSessions(it); editorOpen = false; detail = null },
            onDelete = { deleteSession(it); editorOpen = false; detail = null }
        )
    }
    overrideDate?.let { date ->
        DayOverrideDialog(
            pal, d, date,
            onClose = { overrideDate = null },
            onApply = { day, ov -> setOverride(day, ov); overrideDate = null }
        )
    }
    detail?.let { s ->
        DetailDialog(
            pal, d, hues, s,
            onClose = { detail = null },
            onEdit = { editorFor = s.copy(id = s.id.substringBefore('@')); editorOpen = true }
        )
    }
}

/* ------------------------------------------------------------------ 顶栏 */

@Composable
private fun TopBar(
    pal: Palette,
    d: Derived,
    view: ViewMode,
    weekIdx: Int,
    dayDate: LocalDate,
    onStep: (Int) -> Unit,
    onToday: () -> Unit,
    onView: (ViewMode) -> Unit,
    onAdd: () -> Unit,
    onImport: () -> Unit,
    onSettings: () -> Unit
) {
    val has = !d.isEmpty
    val dayMode = view == ViewMode.Day
    val canPrev = has && if (dayMode) d.weekOf(dayDate.minusDays(1)) >= 1 else weekIdx > 1
    val canNext = has && if (dayMode) d.weekOf(dayDate.plusDays(1)) <= d.weeks else weekIdx < d.weeks

    Column(Modifier.background(pal.panel)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StepButton(pal, "‹", canPrev) { onStep(-1) }

            Column(
                Modifier.width(72.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (!has) "—" else if (dayMode) dayDate.abbr() else "第 $weekIdx 周",
                    color = pal.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1
                )
                if (has) {
                    val sub = if (dayMode) {
                        "%02d/%02d".format(dayDate.monthValue, dayDate.dayOfMonth)
                    } else {
                        val mon = d.mondayOfWeek(weekIdx)
                        val sun = mon.plusDays(6)
                        "%02d/%02d–%02d/%02d".format(
                            mon.monthValue, mon.dayOfMonth, sun.monthValue, sun.dayOfMonth
                        )
                    }
                    Text(sub, color = pal.faint, fontSize = 9.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
                }
            }

            StepButton(pal, "›", canNext) { onStep(1) }
            Spacer(Modifier.width(5.dp))
            OutlineChip(pal, if (dayMode) "今天" else "本周", has, onToday)

            Spacer(Modifier.weight(1f))

            SegToggle(pal, dayMode, onView)
            Spacer(Modifier.width(5.dp))
            StepButton(pal, "+", true, onAdd)
            Spacer(Modifier.width(3.dp))
            StepButton(pal, "↓", true, onImport)
            Spacer(Modifier.width(3.dp))
            StepButton(pal, "⚙", true, onSettings)
        }
        HorizontalDivider(thickness = 1.dp, color = pal.rule)
    }
}

@Composable
private fun SegToggle(pal: Palette, dayMode: Boolean, onView: (ViewMode) -> Unit) {
    Row(Modifier.background(pal.panel2, RoundedCornerShape(2.dp))) {
        listOf(ViewMode.Day to "今日", ViewMode.Week to "周").forEach { (mode, label) ->
            val on = (mode == ViewMode.Day) == dayMode
            Box(
                Modifier
                    .background(if (on) pal.ink else Color.Transparent, RoundedCornerShape(2.dp))
                    .clickable { onView(mode) }
                    .padding(horizontal = 9.dp, vertical = 8.dp)
            ) {
                Text(
                    label, color = if (on) pal.paper else pal.muted,
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1
                )
            }
        }
    }
}

/* ------------------------------------------------------------------ 下一节条 */

@Composable
private fun NextUpStrip(pal: Palette, d: Derived, now: Long) {
    val live = d.current(now)
    val next = if (live == null) d.next(now) else null

    Row(
        Modifier.fillMaxWidth().background(pal.panel2).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when {
            d.isEmpty -> Text("未导入课表", color = pal.faint, fontSize = 12.sp)

            live != null -> {
                Tag(pal, "进行中", pal.signal)
                Spacer(Modifier.width(8.dp))
                Text(
                    "${live.title}${if (live.location.isNotBlank()) " · ${live.location}" else ""} · 还有 ${(live.end - now) / 60000} 分钟下课",
                    color = pal.ink2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }

            next != null -> {
                Tag(pal, "下一节", pal.ink)
                Spacer(Modifier.width(8.dp))
                val mins = (next.start - now) / 60000
                val today = LocalDate.now()
                val nd = next.start.toLocalDate()
                val whenText = when {
                    mins < 60 -> "$mins 分钟后"
                    nd == today -> "今天 ${next.start.hhmm()}"
                    nd == today.plusDays(1) -> "明天 ${next.start.hhmm()}"
                    else -> "%02d-%02d %s %s".format(nd.monthValue, nd.dayOfMonth, nd.abbr(), next.start.hhmm())
                }
                Text(
                    "${next.title}${if (next.location.isNotBlank()) " · ${next.location}" else ""} · $whenText",
                    color = pal.ink2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }

            else -> Text("课表已结束 · 没有更多安排", color = pal.faint, fontSize = 12.sp)
        }
    }
    HorizontalDivider(thickness = 1.dp, color = pal.rule)
}

/* ------------------------------------------------------------------ 周视图 */

@Composable
private fun WeekGrid(
    pal: Palette, d: Derived, hues: Map<String, Float>,
    weekIdx: Int, hourDp: Dp, now: Long, onPick: (Session) -> Unit
) {
    val days = remember(d, weekIdx) { d.daysOfWeek(weekIdx) }
    val (lo, hi) = remember(d.tt) { timeBounds(d.tt.sessions) }
    val minuteDp = hourDp / 60f
    val total = minuteDp * (hi - lo)
    val today = LocalDate.now()
    val gutter = 46.dp
    val scroll = rememberScrollState()
    val density = LocalDensity.current

    // 刻度取课本身的起止时刻。整点没有任何事发生，写 09:00 只会让人去心算。
    val marks = remember(d.tt.sessions, lo, hi) { timeMarks(d.tt.sessions, lo, hi) }
    // 挤在一起的标签读不了，按当前缩放留出最小间距，开始时刻优先保留
    val labels = remember(marks, minuteDp) {
        val minGap = if (minuteDp.value > 0.01f) (11.dp / minuteDp) else 20f
        val picked = ArrayList<TimeMark>()
        for (m in marks.filter { it.isStart }) {
            if (picked.none { kotlin.math.abs(it.minute - m.minute) < minGap }) picked.add(m)
        }
        for (m in marks.filter { !it.isStart }) {
            if (picked.none { kotlin.math.abs(it.minute - m.minute) < minGap }) picked.add(m)
        }
        picked.sortedBy { it.minute }
    }

    // 竖直落点：本周第一节课停在顶部附近，别把早八滚出屏幕
    LaunchedEffect(weekIdx, hi, lo, minuteDp) {
        val first = days.flatMap { d.sessionsOn(it) }.filter { !it.allDay }
            .minOfOrNull { it.startMinute() }
        if (first != null) {
            val targetDp = minuteDp * (maxOf(lo, first - 20) - lo)
            scroll.scrollTo(with(density) { targetDp.roundToPx() }.coerceAtLeast(0))
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(pal.panel)) {
            Box(Modifier.width(gutter).padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                Text("$weekIdx/${d.weeks}", color = pal.faint, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            }
            days.forEach { day ->
                val isToday = day == today
                val ov = d.overrideFor(day)
                Column(
                    Modifier.weight(1f).padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        day.abbr(),
                        color = if (isToday) pal.signal else pal.ink2,
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1
                    )
                    Text(
                        "%02d-%02d".format(day.monthValue, day.dayOfMonth),
                        color = if (isToday) pal.signal else pal.faint,
                        fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1
                    )
                    // 调休的日子给个小标记，不然课变了却看不出原因
                    if (ov != null) {
                        Text(
                            if (ov.kind == OverrideKind.HOLIDAY) "假" else "调",
                            color = pal.signal, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1
                        )
                    }
                }
            }
        }
        HorizontalDivider(thickness = 1.dp, color = pal.rule)

        Row(Modifier.weight(1f).verticalScroll(scroll)) {
            Box(Modifier.width(gutter).height(total).background(pal.panel)) {
                labels.forEach { mk ->
                    Text(
                        mk.minute.hhmm(),
                        // 上课时刻是要看的，下课时刻只是参照，压暗一档
                        color = if (mk.isStart) pal.ink2 else pal.faint,
                        fontSize = if (mk.isStart) 10.sp else 9.sp,
                        fontWeight = if (mk.isStart) FontWeight.SemiBold else FontWeight.Normal,
                        fontFamily = FontFamily.Monospace, textAlign = TextAlign.End, maxLines = 1,
                        modifier = Modifier
                            .offset(y = minuteDp * (mk.minute - lo) - 6.dp)
                            .fillMaxWidth()
                            .padding(end = 5.dp)
                    )
                }
            }
            days.forEach { day ->
                DayColumn(
                    pal, d, hues, day, lo, hi, minuteDp, total,
                    day == today, now, marks, Modifier.weight(1f), onPick
                )
            }
        }
    }
}

@Composable
private fun DayColumn(
    pal: Palette, d: Derived, hues: Map<String, Float>, day: LocalDate,
    lo: Int, hi: Int, minuteDp: Dp, total: Dp,
    isToday: Boolean, now: Long, marks: List<TimeMark>,
    modifier: Modifier, onPick: (Session) -> Unit
) {
    val items = remember(d, day) { d.sessionsOn(day) }
    val placed = remember(items) { layoutDay(items.filter { !it.allDay }) }
    val weekend = day.dayOfWeek.value >= 6

    val bg = when {
        isToday -> pal.signal.copy(alpha = 0.04f)
        weekend -> pal.ink.copy(alpha = 0.025f)
        else -> Color.Transparent
    }

    BoxWithConstraints(
        modifier.height(total).background(bg).drawBehind {
            val perMin = minuteDp.toPx()
            // 网格线画在上下课时刻上，和课程块的边缘正好重合
            marks.forEach { mk ->
                val y = (mk.minute - lo) * perMin
                drawLine(
                    color = if (mk.isStart) pal.rule else pal.ruleSoft,
                    start = Offset(0f, y), end = Offset(size.width, y), strokeWidth = 1f
                )
            }
            drawLine(pal.ruleSoft, Offset(0f, 0f), Offset(0f, size.height), 1f)
        }
    ) {
        val colWidth = maxWidth

        placed.forEach { p ->
            val s = p.session
            val st = maxOf(lo, s.startMinute())
            val en = minOf(hi, s.endMinute())
            val h = (minuteDp * (en - st) - 2.dp).coerceAtLeast(18.dp)
            val hue = hues[s.title] ?: 0f
            // 暗色底上 0.45 会把已过的课压到几乎看不见，单独抬一档
            val alpha = if (s.end < now) pastAlpha(pal) else 1f

            Box(
                Modifier
                    .offset(x = colWidth * (p.col.toFloat() / p.cols), y = minuteDp * (st - lo))
                    .width(colWidth / p.cols)
                    .height(h)
                    .padding(end = 2.dp)
                    .background(blockFill(hue, pal.dark).copy(alpha = alpha), RoundedCornerShape(2.dp))
                    .drawBehind {
                        drawLine(
                            blockEdge(hue, pal.dark).copy(alpha = alpha),
                            Offset(1.5f, 0f), Offset(1.5f, size.height), 3f
                        )
                    }
                    .clickable { onPick(s) }
                    .padding(start = 5.dp, end = 3.dp, top = 3.dp, bottom = 2.dp)
            ) {
                Column {
                    Text(
                        s.title, color = blockText(hue, pal.dark).copy(alpha = alpha),
                        fontSize = 11.sp, fontWeight = FontWeight.SemiBold, lineHeight = 13.sp,
                        maxLines = if (h > 46.dp) 3 else 2, overflow = TextOverflow.Ellipsis
                    )
                    if (h > 40.dp) {
                        Text(
                            s.start.hhmm() + if (s.location.isNotBlank()) " · ${s.location}" else "",
                            color = blockText(hue, pal.dark).copy(alpha = alpha * 0.8f),
                            fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        if (isToday) {
            val nowMin = now.toLocalDateTime().let { it.hour * 60 + it.minute }
            if (nowMin in lo..hi) {
                Box(
                    Modifier.offset(y = minuteDp * (nowMin - lo))
                        .fillMaxWidth().height(1.5.dp).background(pal.signal)
                )
            }
        }
    }
}

/* ------------------------------------------------------------------ 今日视图 */

@Composable
private fun DayList(
    pal: Palette, d: Derived, hues: Map<String, Float>,
    day: LocalDate, now: Long, onPick: (Session) -> Unit, onOverride: () -> Unit
) {
    val items = remember(d, day) { d.sessionsOn(day) }
    val wk = d.weekOf(day)
    val ov = d.overrideFor(day)

    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 9.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            Text(day.abbr(), color = pal.ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(9.dp))
            Text(
                "$day · ${if (wk in 1..d.weeks) "第 $wk 周" else "不在学期内"} · ${items.size} 节",
                color = pal.faint, fontSize = 11.sp, fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.weight(1f))
            OutlineChip(pal, if (ov == null) "调休" else "调休中", onClick = onOverride)
        }

        if (ov != null) {
            Spacer(Modifier.height(2.dp))
            MsgBox(
                pal,
                when (ov.kind) {
                    OverrideKind.HOLIDAY -> "这天放假，原本的课不上。"
                    OverrideKind.FOLLOW -> {
                        val src = ov.followEpochDay?.let { LocalDate.ofEpochDay(it) }
                        "这天调休，上 $src ${src?.abbr() ?: ""} 的课。"
                    }
                }
            )
            Spacer(Modifier.height(8.dp))
        }

        HorizontalDivider(thickness = 1.dp, color = pal.rule)

        if (items.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("这天没课", color = pal.ink2, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${day.abbr()} · %02d-%02d".format(day.monthValue, day.dayOfMonth),
                        color = pal.muted, fontSize = 13.sp
                    )
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(items) { s ->
                    val live = now in s.start until s.end
                    val soon = !live && s.start > now && s.start - now < 45 * 60_000
                    val alpha = if (s.end < now) pastAlpha(pal) else 1f
                    val hue = hues[s.title] ?: 0f

                    Column(Modifier.fillMaxWidth().clickable { onPick(s) }) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                            Column(Modifier.width(56.dp)) {
                                Text(
                                    s.start.hhmm(), color = pal.ink2.copy(alpha = alpha),
                                    fontSize = 12.sp, fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    s.end.hhmm(), color = pal.faint.copy(alpha = alpha),
                                    fontSize = 11.sp, fontFamily = FontFamily.Monospace
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Box(
                                Modifier.width(3.dp).height(16.dp).background(
                                    blockEdge(hue, pal.dark).copy(alpha = alpha), RoundedCornerShape(1.dp)
                                )
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        s.title, color = pal.ink.copy(alpha = alpha),
                                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    if (live) { Spacer(Modifier.width(7.dp)); Tag(pal, "进行中", pal.signal) }
                                    if (soon) { Spacer(Modifier.width(7.dp)); Tag(pal, "即将开始", pal.ink) }
                                    if (s.manual) { Spacer(Modifier.width(7.dp)); Tag(pal, "手动", pal.muted) }
                                }
                                val meta = listOf(s.location, s.teacher).filter { it.isNotBlank() }
                                if (meta.isNotEmpty()) {
                                    Spacer(Modifier.height(3.dp))
                                    Text(
                                        meta.joinToString(" · "), color = pal.muted.copy(alpha = alpha),
                                        fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                        HorizontalDivider(thickness = 1.dp, color = pal.ruleSoft)
                    }
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ 空状态 */

@Composable
private fun EmptyState(pal: Palette, onImport: () -> Unit, onAdd: () -> Unit, onDemo: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("还没有课表", color = pal.ink2, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text("导入学校课表，或者自己一节一节加。", color = pal.muted, fontSize = 13.sp)
            Spacer(Modifier.height(18.dp))
            Row {
                PrimaryButton(pal, "导入课表", onClick = onImport)
                Spacer(Modifier.width(8.dp))
                OutlineChip(pal, "手动添加", onClick = onAdd)
                Spacer(Modifier.width(8.dp))
                OutlineChip(pal, "载入示例", onClick = onDemo)
            }
        }
    }
}

/* ------------------------------------------------------------------ 导入 */

@Composable
private fun ImportDialog(
    pal: Palette, tt: Timetable, onClose: () -> Unit, onImported: (Timetable) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var jwxtUrl by remember { mutableStateOf(tt.jwxtHome) }
    var url by remember { mutableStateOf(tt.icsUrl) }
    var paste by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    var err by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    fun runImport(label: String, url2: String = "", block: suspend () -> String) {
        busy = true; err = false; msg = "处理中…"
        scope.launch {
            val r = runCatching { Store.importIcs(ctx, block(), label, url2) }
            busy = false
            r.fold(
                onSuccess = {
                    onImported(it)
                    val courses = it.sessions.map { s -> s.title }.distinct().size
                    err = false
                    msg = "导入成功：$courses 门课、${it.sessions.size} 节。周次不对就到设置里改开学日期。"
                },
                onFailure = { err = true; msg = "导入失败：${it.message ?: it.toString()}" }
            )
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) runImport("本地文件") { Store.readUri(ctx, uri) }
    }
    val webImport = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        scope.launch { onImported(Store.load(ctx)) }
    }

    Sheet(pal, "导入课表", onClose) {
        Column {
            Label(pal, "1 · 从教务系统网页导入")
            Hint(
                pal,
                "打开内置浏览器，你自己登录、点到课表页面，再抓取。验证码、统一身份认证都由你本人处理，" +
                    "不需要为学校单独写登录逻辑。"
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = jwxtUrl, onValueChange = { jwxtUrl = it },
                placeholder = { Text("教务系统网址，如 jwxt.xxx.edu.cn", fontSize = 12.sp) },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            PrimaryButton(pal, "打开并抓取", enabled = jwxtUrl.isNotBlank()) {
                var u = jwxtUrl.trim()
                if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
                webImport.launch(
                    Intent(ctx, WebImportActivity::class.java)
                        .putExtra(WebImportActivity.EXTRA_URL, u)
                        .putExtra(WebImportActivity.EXTRA_HOME, u)
                        .putExtra(WebImportActivity.EXTRA_HAS_TERM, tt.termStartEpochDay != null)
                )
            }

            Spacer(Modifier.height(22.dp))
            Label(pal, "2 · 从 .ics 文件导入")
            Spacer(Modifier.height(4.dp))
            PrimaryButton(pal, "选择 .ics 文件") {
                picker.launch(arrayOf("text/calendar", "text/plain", "application/octet-stream", "*/*"))
            }

            Spacer(Modifier.height(22.dp))
            Label(pal, "3 · 直接拉 ICS 订阅链接")
            Hint(pal, "原生没有 CORS 限制，学校的订阅链接可以直接拉。支持 webcal:// 开头。")
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = url, onValueChange = { url = it },
                placeholder = { Text("https://….ics", fontSize = 12.sp) },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            PrimaryButton(pal, if (busy) "拉取中…" else "拉取并导入", enabled = url.isNotBlank() && !busy) {
                runImport("订阅链接", url.trim()) { Store.fetchIcs(url) }
            }

            Spacer(Modifier.height(22.dp))
            Label(pal, "4 · 粘贴 ICS 文本")
            OutlinedTextField(
                value = paste, onValueChange = { paste = it },
                placeholder = { Text("BEGIN:VCALENDAR …", fontSize = 12.sp) },
                minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row {
                PrimaryButton(pal, "解析", enabled = paste.isNotBlank() && !busy) {
                    runImport("粘贴的文本") { paste }
                }
                Spacer(Modifier.width(8.dp))
                OutlineChip(pal, "载入示例") { runImport("示例课表") { DEMO_ICS } }
            }

            msg?.let {
                Spacer(Modifier.height(14.dp))
                MsgBox(pal, it, err)
            }
        }
    }
}

/* ------------------------------------------------------------------ 设置 */

@Composable
private fun SettingsDialog(
    pal: Palette, d: Derived, hues: Map<String, Float>, hourDp: Dp,
    onHourDp: (Dp) -> Unit, onClose: () -> Unit,
    onApply: (Timetable) -> Unit, onEditOverride: (LocalDate) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf<String?>(null) }
    var showPeriods by remember { mutableStateOf(false) }
    // 会覆盖/清掉数据的按钮一律两步，误触一下不至于把整张课表没了
    var confirmResync by remember { mutableStateOf(false) }
    var confirmRefetch by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    // 改学期起点或作息表都要把已导入的课重算一遍；
    // 其余设置（周末、调休、缩放）不碰课程，走普通 onApply，
    // 免得把用户删掉的导入条目又算回来。
    val applyAndRecompute: (Timetable) -> Unit = { onApply(Store.recomputeFromBlocks(it)) }

    if (showPeriods) {
        PeriodEditorDialog(
            pal = pal,
            periods = d.tt.periods,
            onClose = { showPeriods = false },
            onApply = { ps ->
                applyAndRecompute(d.tt.copy(periods = ps, periodsSource = "manual"))
                showPeriods = false
                msg = "作息表已保存，课表时间已按新作息重算。"
            }
        )
    }

    Sheet(pal, "设置", onClose) {
        Column {
            TermSettings(pal, d, applyAndRecompute)

            Spacer(Modifier.height(22.dp))
            Label(pal, "调休")
            Hint(pal, "某天放假，或者某天按另一天的课上。也可以在「今日」视图右上角直接改当天。")
            Spacer(Modifier.height(8.dp))
            if (d.tt.overrides.isEmpty()) {
                Hint(pal, "还没有调休记录。")
            } else {
                d.tt.overrides.sortedBy { it.dateEpochDay }.forEach { ov ->
                    val date = LocalDate.ofEpochDay(ov.dateEpochDay)
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "$date ${date.abbr()}", color = pal.ink2,
                                fontSize = 12.5.sp, fontFamily = FontFamily.Monospace
                            )
                            Text(
                                when (ov.kind) {
                                    OverrideKind.HOLIDAY -> "放假"
                                    OverrideKind.FOLLOW -> {
                                        val s = ov.followEpochDay?.let { LocalDate.ofEpochDay(it) }
                                        "上 $s ${s?.abbr() ?: ""} 的课"
                                    }
                                },
                                color = pal.faint, fontSize = 11.sp
                            )
                        }
                        OutlineChip(pal, "改") { onEditOverride(date) }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlineChip(pal, "添加调休") { onEditOverride(LocalDate.now()) }
            Spacer(Modifier.height(4.dp))
            Hint(pal, "点进去可以选任意一天，不限于今天 —— 调休通知一般提前发。")

            if (d.tt.jwxtPage.isNotBlank()) {
                Spacer(Modifier.height(22.dp))
                Label(pal, "重新同步")
                Hint(
                    pal,
                    "直接打开上次出课表的那一页。登录状态通常还在，页面一加载就自动解析，" +
                        "不用再从菜单里点进去。"
                )
                Spacer(Modifier.height(8.dp))
                if (!confirmResync) {
                    PrimaryButton(pal, "重新同步课表") { confirmResync = true }
                } else {
                    MsgBox(
                        pal,
                        "重新同步会用教务系统上的课表覆盖现在这份。" +
                            "你手动加的课和事件会保留，手动改过的那几节也保留；" +
                            "其余导入来的都会按网页重来一遍。"
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PrimaryButton(pal, "确认，去同步") {
                            confirmResync = false
                            ctx.startActivity(
                                Intent(ctx, WebImportActivity::class.java)
                                    .putExtra(WebImportActivity.EXTRA_URL, d.tt.jwxtPage)
                                    .putExtra(WebImportActivity.EXTRA_HOME, d.tt.jwxtHome)
                                    .putExtra(WebImportActivity.EXTRA_HAS_TERM, true)
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        OutlineChip(pal, "取消") { confirmResync = false }
                    }
                }
            }

            Spacer(Modifier.height(22.dp))
            Label(pal, "作息")
            Hint(
                pal,
                if (d.tt.zfBlocks.isEmpty())
                    "教务系统导入的课表只给「第几节」，靠这张表换算成具体时间。"
                else
                    "当前课表来自教务系统，共 ${d.tt.zfBlocks.size} 个课程块。改作息表会原地重算，不用重新抓网页。"
            )
            Spacer(Modifier.height(8.dp))
            FieldRow(
                pal, "节次时间",
                "第1节 ${d.tt.periods.firstOrNull()?.startMin?.hhmm() ?: "—"} 起，共 ${d.tt.periods.size} 节"
            ) {
                OutlineChip(pal, "编辑") { showPeriods = true }
            }

            Spacer(Modifier.height(22.dp))
            Label(pal, "显示")
            FieldRow(pal, "显示周六周日", if (d.tt.showWeekend) "当前：显示" else "当前：只显示周一到周五") {
                OutlineChip(pal, if (d.tt.showWeekend) "关闭" else "打开") {
                    onApply(d.tt.copy(showWeekend = !d.tt.showWeekend))
                }
            }
            FieldRow(pal, "时间轴缩放", "每小时 ${hourDp.value.toInt()} dp") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StepButton(pal, "−") { onHourDp((hourDp - 10.dp).coerceAtLeast(36.dp)) }
                    Spacer(Modifier.width(6.dp))
                    StepButton(pal, "+") { onHourDp((hourDp + 10.dp).coerceAtMost(140.dp)) }
                }
            }

            if (d.tt.icsUrl.isNotBlank()) {
                Spacer(Modifier.height(22.dp))
                Label(pal, "同步")
                Text(
                    d.tt.icsUrl, color = pal.muted, fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))
                if (!confirmRefetch) {
                    PrimaryButton(pal, "重新拉取课表") { confirmRefetch = true }
                } else {
                    MsgBox(pal, "会用订阅链接上的内容覆盖现在这份，手动添加的条目保留。")
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PrimaryButton(pal, "确认拉取") {
                            confirmRefetch = false
                            scope.launch {
                                runCatching {
                                    Store.importIcs(ctx, Store.fetchIcs(d.tt.icsUrl), "订阅链接", d.tt.icsUrl)
                                }.fold(
                                    onSuccess = { onApply(it); msg = "已更新，手动添加的条目保留。" },
                                    onFailure = { msg = "拉取失败：${it.message}" }
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        OutlineChip(pal, "取消") { confirmRefetch = false }
                    }
                }
            }

            Spacer(Modifier.height(22.dp))
            Label(pal, "课程（${hues.size} 门）")
            hues.keys.forEach { name ->
                Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(9.dp).background(
                            blockEdge(hues[name] ?: 0f, pal.dark), RoundedCornerShape(2.dp)
                        )
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        name, color = pal.ink2, fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.height(22.dp))
            Label(pal, "数据")
            Hint(pal, "课表只存在这台手机上，不上传。手动添加的条目在重新导入时会保留。")
            Spacer(Modifier.height(8.dp))
            if (!confirmClear) {
                DangerChip(pal, "清空课表") { confirmClear = true }
            } else {
                MsgBox(
                    pal,
                    "确定要清空吗？${d.tt.sessions.size} 节课、" +
                        "${d.tt.overrides.size} 条调休记录和作息设置都会删掉，删了没法撤销。" +
                        "只是想换一份课表的话，直接重新导入就行，不用先清空。",
                    error = true
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DangerChip(pal, "确认清空") {
                        scope.launch { Store.clear(ctx) }
                        onApply(Timetable())
                        onClose()
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlineChip(pal, "取消") { confirmClear = false }
                }
            }

            msg?.let {
                Spacer(Modifier.height(14.dp))
                MsgBox(pal, it)
            }
        }
    }
}

/* ------------------------------------------------------------------ 详情 */

@Composable
private fun DetailDialog(
    pal: Palette, d: Derived, hues: Map<String, Float>, s: Session,
    onClose: () -> Unit, onEdit: () -> Unit
) {
    val same = d.tt.sessions.filter { it.title == s.title }
    // 一门课可能"周一在这上、周四在那上"。地点和时间必须成对列，
    // 分开列成两串就没法对应了 —— 这是之前那版最容易看错的地方。
    val arrangements = remember(d, s.title) { d.arrangementsOf(s.title) }
    val mine = remember(arrangements, s) { arrangements.matching(s) }
    val hue = hues[s.title] ?: 0f

    Sheet(pal, "课程详情", onClose) {
        Column {
            Row {
                Box(
                    Modifier.width(4.dp).height(40.dp)
                        .background(blockEdge(hue, pal.dark), RoundedCornerShape(2.dp))
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(s.title, color = pal.ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${s.start.toLocalDate()} ${s.start.toLocalDate().abbr()} · " +
                            "${s.start.hhmm()}–${s.end.hhmm()} · ${(s.end - s.start) / 60000} 分钟 · " +
                            "第 ${d.weekOf(s.start.toLocalDate())} 周",
                        color = pal.muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Label(pal, if (arrangements.size > 1) "上课安排（${arrangements.size} 种）" else "上课安排")
            if (arrangements.size > 1) {
                Hint(pal, "这门课不止一种安排，时间和地点是一一对应的。")
                Spacer(Modifier.height(8.dp))
            }
            arrangements.forEach { a ->
                ArrangementCard(pal, d, a, hue, current = a === mine)
                Spacer(Modifier.height(6.dp))
            }

            Spacer(Modifier.height(10.dp))
            DetailRow(pal, "周次", d.weekRangeOf(s.title).ifBlank { "—" } + " 周")
            DetailRow(pal, "总节数", "${same.size} 次")
            if (s.note.isNotBlank()) DetailRow(pal, "备注", s.note.trim())

            Spacer(Modifier.height(16.dp))
            PrimaryButton(pal, "编辑这一节", onClick = onEdit)
        }
    }
}

/** 一种上课安排：星期、时段、地点、教师、周次全在一块儿，不拆开。 */
@Composable
private fun ArrangementCard(
    pal: Palette, d: Derived, a: Arrangement, hue: Float, current: Boolean
) {
    val periodText = periodLabel(d.tt.periods, a.startMin, a.endMin)
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                if (current) pal.signal.copy(alpha = 0.07f) else pal.panel2,
                RoundedCornerShape(2.dp)
            )
            .padding(10.dp)
    ) {
        Box(
            Modifier.width(3.dp).height(34.dp)
                .background(blockEdge(hue, pal.dark), RoundedCornerShape(2.dp))
        )
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${DAY_ABBR[a.weekday % 7]} ${a.startMin.hhmm()}–${a.endMin.hhmm()}",
                    color = pal.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace, maxLines = 1
                )
                if (periodText.isNotBlank()) {
                    Spacer(Modifier.width(7.dp))
                    Text(periodText, color = pal.faint, fontSize = 11.sp, maxLines = 1)
                }
                Spacer(Modifier.weight(1f))
                if (current) Tag(pal, "本次", pal.signal)
            }
            Spacer(Modifier.height(3.dp))
            Text(
                a.location.ifBlank { "未标注地点" },
                color = if (a.location.isBlank()) pal.faint else pal.ink2,
                fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                listOfNotNull(
                    compressWeeks(a.weeks).ifBlank { null }?.let { "第 $it 周" },
                    a.teacher.ifBlank { null },
                    "${a.count} 次"
                ).joinToString(" · "),
                color = pal.muted, fontSize = 11.sp, maxLines = 2
            )
        }
    }
}
