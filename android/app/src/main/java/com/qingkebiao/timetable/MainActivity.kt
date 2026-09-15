package com.qingkebiao.timetable

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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import com.qingkebiao.timetable.widget.refreshWidgets
import com.qingkebiao.timetable.widget.scheduleWidgetRefresh
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 全屏到状态栏/导航栏下面，再用 safeDrawing 把内容让开 ——
        // 这是网页版里 env(safe-area-inset-*) 那套事情的原生做法
        enableEdgeToEdge()
        scheduleWidgetRefresh(this)
        setContent { App() }
    }
}

private enum class ViewMode { Day, Week }

@Composable
private fun App() {
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
                Home(pal)
            }
        }
    }
}

@Composable
private fun Home(pal: Palette) {
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
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    val d = remember(tt) { Derived(tt) }
    val hues = remember(tt) { courseHues(tt.sessions.map { it.title }) }

    // 首次打开先装示例，让人立刻看见这东西长什么样（不落盘，导入真课表即覆盖）
    LaunchedEffect(Unit) {
        val stored = Store.load(ctx)
        tt = if (stored.sessions.isNotEmpty()) {
            stored
        } else {
            runCatching {
                Timetable(
                    sessions = Ics.parse(DEMO_ICS),
                    showWeekend = false,
                    sourceLabel = "示例课表"
                )
            }.getOrDefault(Timetable())
        }
        hourDp = 64.dp
    }

    // 课表一变就把周次落到今天所在的那一周
    LaunchedEffect(tt) {
        if (tt.sessions.isNotEmpty()) {
            val today = LocalDate.now()
            weekIdx = d.clampWeek(d.weekOf(today))
            dayDate = today
        }
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
            pal = pal,
            d = d,
            view = view,
            weekIdx = weekIdx,
            dayDate = dayDate,
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
            onImport = { showImport = true },
            onSettings = { showSettings = true }
        )

        NextUpStrip(pal, d, now)

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                d.isEmpty -> EmptyState(pal, onImport = { showImport = true }, onDemo = {
                    runCatching {
                        commit(
                            Timetable(
                                sessions = Ics.parse(DEMO_ICS),
                                showWeekend = false,
                                sourceLabel = "示例课表"
                            )
                        )
                    }
                })

                view == ViewMode.Day -> DayList(pal, d, hues, dayDate, now) { detail = it }

                else -> WeekGrid(pal, d, hues, weekIdx, hourDp, now) { detail = it }
            }
        }
    }

    if (showImport) {
        ImportDialog(pal, tt, onClose = { showImport = false }, onImported = { commit(it) })
    }
    if (showSettings) {
        SettingsDialog(
            pal = pal, d = d, hues = hues, hourDp = hourDp,
            onHourDp = { hourDp = it },
            onClose = { showSettings = false },
            onApply = { commit(it) }
        )
    }
    detail?.let { s ->
        DetailDialog(pal, d, hues, s) { detail = null }
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
    onImport: () -> Unit,
    onSettings: () -> Unit
) {
    val has = !d.isEmpty
    val dayMode = view == ViewMode.Day
    val canPrev = has && if (dayMode) d.weekOf(dayDate.minusDays(1)) >= 1 else weekIdx > 1
    val canNext = has && if (dayMode) d.weekOf(dayDate.plusDays(1)) <= d.weeks else weekIdx < d.weeks

    Column(Modifier.background(pal.panel)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StepButton(pal, "‹", canPrev) { onStep(-1) }

            Column(
                Modifier.width(74.dp).padding(horizontal = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (!has) "—" else if (dayMode) dayDate.abbr() else "第 $weekIdx 周",
                    color = pal.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1
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

            Spacer(Modifier.width(6.dp))
            OutlineChip(pal, if (dayMode) "今天" else "本周", enabled = has, onClick = onToday)

            Spacer(Modifier.weight(1f))

            SegToggle(pal, dayMode, onView)

            Spacer(Modifier.width(6.dp))
            IconChip(pal, "导入", onImport)
            Spacer(Modifier.width(4.dp))
            IconChip(pal, "设置", onSettings)
        }
        HorizontalDivider(thickness = 1.dp, color = pal.rule)
    }
}

@Composable
private fun StepButton(pal: Palette, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(34.dp)
            .background(pal.panel2, RoundedCornerShape(2.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (enabled) pal.ink2 else pal.faint.copy(alpha = 0.4f), fontSize = 16.sp)
    }
}

@Composable
private fun OutlineChip(pal: Palette, label: String, enabled: Boolean, onClick: () -> Unit) {
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
private fun IconChip(pal: Palette, label: String, onClick: () -> Unit) =
    OutlineChip(pal, label, true, onClick)

@Composable
private fun SegToggle(pal: Palette, dayMode: Boolean, onView: (ViewMode) -> Unit) {
    Row(Modifier.background(pal.panel2, RoundedCornerShape(2.dp))) {
        listOf(ViewMode.Day to "今日", ViewMode.Week to "周").forEach { (mode, label) ->
            val on = (mode == ViewMode.Day) == dayMode
            Box(
                Modifier
                    .background(if (on) pal.ink else Color.Transparent, RoundedCornerShape(2.dp))
                    .clickable { onView(mode) }
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Text(
                    label,
                    color = if (on) pal.paper else pal.muted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
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
        Modifier
            .fillMaxWidth()
            .background(pal.panel2)
            .padding(horizontal = 12.dp, vertical = 7.dp),
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
                val when_ = when {
                    mins < 60 -> "$mins 分钟后"
                    nd == today -> "今天 ${next.start.hhmm()}"
                    nd == today.plusDays(1) -> "明天 ${next.start.hhmm()}"
                    else -> "%02d-%02d %s %s".format(nd.monthValue, nd.dayOfMonth, nd.abbr(), next.start.hhmm())
                }
                Text(
                    "${next.title}${if (next.location.isNotBlank()) " · ${next.location}" else ""} · $when_",
                    color = pal.ink2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }

            else -> Text("课表已结束 · 没有更多安排", color = pal.faint, fontSize = 12.sp)
        }
    }
    HorizontalDivider(thickness = 1.dp, color = pal.rule)
}

@Composable
private fun Tag(pal: Palette, label: String, bg: Color) {
    Box(
        Modifier.background(bg, RoundedCornerShape(2.dp)).padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(label, color = pal.paper, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/* ------------------------------------------------------------------ 周视图 */

@Composable
private fun WeekGrid(
    pal: Palette,
    d: Derived,
    hues: Map<String, Float>,
    weekIdx: Int,
    hourDp: Dp,
    now: Long,
    onPick: (Session) -> Unit
) {
    val days = remember(d, weekIdx) { d.daysOfWeek(weekIdx) }
    val (lo, hi) = remember(d.tt) { timeBounds(d.tt.sessions) }
    val minuteDp = hourDp / 60f
    val total = minuteDp * (hi - lo)
    val today = LocalDate.now()
    val gutter = 40.dp
    val scroll = rememberScrollState()
    val density = LocalDensity.current

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
        // 表头
        Row(Modifier.fillMaxWidth().background(pal.panel)) {
            Box(Modifier.width(gutter).padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                Text(
                    "$weekIdx/${d.weeks}",
                    color = pal.faint, fontSize = 9.sp, fontFamily = FontFamily.Monospace
                )
            }
            days.forEach { day ->
                val isToday = day == today
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
                }
            }
        }
        HorizontalDivider(thickness = 1.dp, color = pal.rule)

        Row(Modifier.weight(1f).verticalScroll(scroll)) {
            // 时间轴
            Box(Modifier.width(gutter).height(total).background(pal.panel)) {
                var m = lo
                while (m <= hi) {
                    if (m % 60 == 0) {
                        Text(
                            m.hhmm(),
                            color = pal.faint,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            textAlign = TextAlign.End,
                            modifier = Modifier
                                .offset(y = minuteDp * (m - lo) - 6.dp)
                                .fillMaxWidth()
                                .padding(end = 5.dp),
                        )
                    }
                    m += 60
                }
            }

            days.forEach { day ->
                DayColumn(
                    pal = pal, d = d, hues = hues, day = day,
                    lo = lo, hi = hi, minuteDp = minuteDp, total = total,
                    isToday = day == today, now = now,
                    modifier = Modifier.weight(1f),
                    onPick = onPick
                )
            }
        }
    }
}

@Composable
private fun DayColumn(
    pal: Palette,
    d: Derived,
    hues: Map<String, Float>,
    day: LocalDate,
    lo: Int,
    hi: Int,
    minuteDp: Dp,
    total: Dp,
    isToday: Boolean,
    now: Long,
    modifier: Modifier,
    onPick: (Session) -> Unit
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
        modifier
            .height(total)
            .background(bg)
            .drawBehind {
                // 整点发丝线，半点更淡
                val perMin = minuteDp.toPx()
                var m = lo
                while (m <= hi) {
                    val y = (m - lo) * perMin
                    drawLine(
                        color = if (m % 60 == 0) pal.rule else pal.ruleSoft,
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 1f
                    )
                    m += 30
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
            val past = s.end < now
            val alpha = if (past) 0.45f else 1f

            Box(
                Modifier
                    .offset(
                        x = colWidth * (p.col.toFloat() / p.cols),
                        y = minuteDp * (st - lo)
                    )
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
                        s.title,
                        color = blockText(hue, pal.dark).copy(alpha = alpha),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 13.sp,
                        maxLines = if (h > 46.dp) 3 else 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (h > 40.dp) {
                        Text(
                            s.start.hhmm() + if (s.location.isNotBlank()) " · ${s.location}" else "",
                            color = blockText(hue, pal.dark).copy(alpha = alpha * 0.8f),
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // "现在"线：只画在今天那一列
        if (isToday) {
            val nowMin = now.toLocalDateTime().let { it.hour * 60 + it.minute }
            if (nowMin in lo..hi) {
                Box(
                    Modifier
                        .offset(y = minuteDp * (nowMin - lo))
                        .fillMaxWidth()
                        .height(1.5.dp)
                        .background(pal.signal)
                )
            }
        }
    }
}

/* ------------------------------------------------------------------ 今日视图 */

@Composable
private fun DayList(
    pal: Palette,
    d: Derived,
    hues: Map<String, Float>,
    day: LocalDate,
    now: Long,
    onPick: (Session) -> Unit
) {
    val items = remember(d, day) { d.sessionsOn(day) }
    val wk = d.weekOf(day)

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
                    val past = s.end < now
                    val hue = hues[s.title] ?: 0f
                    val alpha = if (past) 0.45f else 1f

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
                                Modifier.width(3.dp).height(16.dp)
                                    .background(blockEdge(hue, pal.dark).copy(alpha = alpha), RoundedCornerShape(1.dp))
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
                                }
                                val meta = listOf(s.location, s.teacher).filter { it.isNotBlank() }
                                if (meta.isNotEmpty()) {
                                    Spacer(Modifier.height(3.dp))
                                    Text(
                                        meta.joinToString(" · "),
                                        color = pal.muted.copy(alpha = alpha),
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
private fun EmptyState(pal: Palette, onImport: () -> Unit, onDemo: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("还没有课表", color = pal.ink2, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text("导入学校的 .ics 日历，或先看看示例。", color = pal.muted, fontSize = 13.sp)
            Spacer(Modifier.height(18.dp))
            Row {
                PrimaryButton(pal, "导入课表", onImport)
                Spacer(Modifier.width(8.dp))
                OutlineChip(pal, "载入示例", true, onDemo)
            }
        }
    }
}

@Composable
private fun PrimaryButton(pal: Palette, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .background(pal.ink, RoundedCornerShape(2.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp)
    ) {
        Text(label, color = pal.paper, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/* ------------------------------------------------------------------ 对话框 */

@Composable
private fun Sheet(pal: Palette, title: String, onClose: () -> Unit, body: @Composable () -> Unit) {
    // usePlatformDefaultWidth = false 才能自己定宽度，默认那个窄框放不下导入那三段
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            color = pal.panel,
            shape = RoundedCornerShape(4.dp),
            modifier = Modifier.fillMaxWidth(0.92f).heightIn(max = 560.dp)
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
private fun ImportDialog(
    pal: Palette,
    tt: Timetable,
    onClose: () -> Unit,
    onImported: (Timetable) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(tt.icsUrl) }
    var paste by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun runImport(label: String, url2: String = "", block: suspend () -> String) {
        busy = true
        msg = "处理中…"
        scope.launch {
            val r = runCatching {
                val text = block()
                Store.importIcs(ctx, text, label, url2)
            }
            busy = false
            r.fold(
                onSuccess = {
                    onImported(it)
                    val courses = it.sessions.map { s -> s.title }.distinct().size
                    msg = "导入成功：$courses 门课、${it.sessions.size} 节。周次对不上就到设置里整体挪一周。"
                },
                onFailure = { msg = "导入失败：${it.message ?: it.toString()}" }
            )
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) runImport("本地文件") { Store.readUri(ctx, uri) }
    }

    Sheet(pal, "导入课表", onClose) {
        Column {
            Label(pal, "1 · 从 .ics 文件导入")
            Text(
                "把学校给的 .ics 下载到手机，然后从这里选。",
                color = pal.muted, fontSize = 12.sp
            )
            Spacer(Modifier.height(8.dp))
            PrimaryButton(pal, "选择 .ics 文件") {
                picker.launch(arrayOf("text/calendar", "text/plain", "application/octet-stream", "*/*"))
            }

            Spacer(Modifier.height(20.dp))
            Label(pal, "2 · 直接拉订阅链接")
            Text(
                "原生没有 CORS 限制，学校的订阅链接可以直接拉。支持 webcal:// 开头。",
                color = pal.muted, fontSize = 12.sp
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = url, onValueChange = { url = it },
                placeholder = { Text("https://jwxt.example.edu.cn/....ics", fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            PrimaryButton(pal, if (busy) "拉取中…" else "拉取并导入") {
                if (url.isNotBlank() && !busy) runImport("订阅链接", url.trim()) { Store.fetchIcs(url) }
            }

            Spacer(Modifier.height(20.dp))
            Label(pal, "3 · 粘贴 ICS 文本")
            OutlinedTextField(
                value = paste, onValueChange = { paste = it },
                placeholder = { Text("BEGIN:VCALENDAR …", fontSize = 12.sp) },
                minLines = 3, maxLines = 6,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row {
                PrimaryButton(pal, "解析") {
                    if (paste.isNotBlank() && !busy) runImport("粘贴的文本") { paste }
                }
                Spacer(Modifier.width(8.dp))
                OutlineChip(pal, "载入示例", true) { runImport("示例课表") { DEMO_ICS } }
            }

            msg?.let {
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth().background(pal.panel2, RoundedCornerShape(2.dp)).padding(10.dp)) {
                    Text(it, color = pal.ink2, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun SettingsDialog(
    pal: Palette,
    d: Derived,
    hues: Map<String, Float>,
    hourDp: Dp,
    onHourDp: (Dp) -> Unit,
    onClose: () -> Unit,
    onApply: (Timetable) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    Sheet(pal, "设置", onClose) {
        Column {
            Label(pal, "学期")
            Text(
                "第 1 周的周一：${d.termStart}（共 ${d.weeks} 周）",
                color = pal.ink2, fontSize = 13.sp
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "ICS 里没有「第几周」这个信息，默认按最早一节课推算。差一周就在这里整体挪。",
                color = pal.muted, fontSize = 12.sp
            )
            Spacer(Modifier.height(8.dp))
            Row {
                OutlineChip(pal, "整体前移一周", true) {
                    onApply(d.tt.copy(termStartEpochDay = d.termStart.minusWeeks(1).toEpochDay()))
                }
                Spacer(Modifier.width(8.dp))
                OutlineChip(pal, "整体后移一周", true) {
                    onApply(d.tt.copy(termStartEpochDay = d.termStart.plusWeeks(1).toEpochDay()))
                }
            }

            Spacer(Modifier.height(20.dp))
            Label(pal, "显示")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("显示周六周日", color = pal.ink2, fontSize = 13.sp, modifier = Modifier.weight(1f))
                OutlineChip(pal, if (d.tt.showWeekend) "已开" else "已关", true) {
                    onApply(d.tt.copy(showWeekend = !d.tt.showWeekend))
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("时间轴缩放", color = pal.ink2, fontSize = 13.sp, modifier = Modifier.weight(1f))
                OutlineChip(pal, "小", true) { onHourDp((hourDp - 10.dp).coerceAtLeast(36.dp)) }
                Spacer(Modifier.width(6.dp))
                Text("${hourDp.value.toInt()}", color = pal.faint, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.width(6.dp))
                OutlineChip(pal, "大", true) { onHourDp((hourDp + 10.dp).coerceAtMost(140.dp)) }
            }

            if (d.tt.icsUrl.isNotBlank()) {
                Spacer(Modifier.height(20.dp))
                Label(pal, "同步")
                Text(d.tt.icsUrl, color = pal.muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 2)
                Spacer(Modifier.height(8.dp))
                PrimaryButton(pal, "重新拉取课表") {
                    scope.launch {
                        runCatching {
                            Store.importIcs(ctx, Store.fetchIcs(d.tt.icsUrl), "订阅链接", d.tt.icsUrl)
                        }.onSuccess { onApply(it) }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Label(pal, "课程（${hues.size} 门）")
            hues.keys.forEach { name ->
                Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(9.dp)
                            .background(blockEdge(hues[name] ?: 0f, pal.dark), RoundedCornerShape(2.dp))
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(name, color = pal.ink2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            Spacer(Modifier.height(20.dp))
            Label(pal, "数据")
            Text("课表只存在这台手机上，不上传。", color = pal.muted, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            OutlineChip(pal, "清空课表", true) {
                scope.launch { Store.clear(ctx) }
                onApply(Timetable())
                onClose()
            }
        }
    }
}

@Composable
private fun DetailDialog(
    pal: Palette,
    d: Derived,
    hues: Map<String, Float>,
    s: Session,
    onClose: () -> Unit
) {
    val same = d.tt.sessions.filter { it.title == s.title }
    val places = same.map { it.location }.filter { it.isNotBlank() }.distinct()
    val teachers = same.map { it.teacher }.filter { it.isNotBlank() }.distinct()
    val slots = same.map { "${it.start.toLocalDate().abbr()} ${it.start.hhmm()}–${it.end.hhmm()}" }.distinct()
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
                        "${s.start.toLocalDate()} ${s.start.toLocalDate().abbr()} · ${s.start.hhmm()}–${s.end.hhmm()} · ${(s.end - s.start) / 60000} 分钟 · 第 ${d.weekOf(s.start.toLocalDate())} 周",
                        color = pal.muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            if (places.isNotEmpty()) DetailRow(pal, "地点", places.joinToString(" / "))
            if (teachers.isNotEmpty()) DetailRow(pal, "教师", teachers.joinToString(" / "))
            DetailRow(pal, "上课时间", slots.joinToString("\n"))
            DetailRow(pal, "周次", d.weekRangeOf(s.title).ifBlank { "—" } + " 周")
            DetailRow(pal, "总节数", "${same.size} 次")
            val note = s.note.trim()
            if (note.isNotBlank()) DetailRow(pal, "备注", note)
        }
    }
}

@Composable
private fun DetailRow(pal: Palette, key: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Text(key, color = pal.faint, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(2.dp))
        Text(value, color = pal.ink2, fontSize = 13.sp)
    }
    HorizontalDivider(thickness = 1.dp, color = pal.ruleSoft)
}

@Composable
private fun Label(pal: Palette, text: String) {
    Text(text, color = pal.faint, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(7.dp))
}
