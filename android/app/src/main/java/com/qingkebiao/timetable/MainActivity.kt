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
import java.io.File
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

    var recovered by remember { mutableStateOf<String?>(null) }
    var showUpdate by remember { mutableStateOf(false) }
    val newVersion = rememberUpdateCheck(tt.updateUrl, resumeTick)
    // 同一个版本一天只主动弹一次。点了"稍后"今天就不再打扰，
    // 顶上那条横幅一直留着 —— 想更新随时点得到，但不会每次切回来都糊你一脸
    val showPrompt = newVersion != null && !showUpdate &&
        !(tt.updateSnoozeCode == newVersion.versionCode &&
            tt.updateSnoozeDay == LocalDate.now().toEpochDay())

    // 调课提醒。教务系统要登录才能看，没法真正后台静默查，
    // 所以退而求其次：隔几天提醒一次，点一下就进去对。
    var syncDismissed by remember { mutableStateOf(false) }
    val daysSinceSync = remember(tt.lastSyncEpochDay) {
        tt.lastSyncEpochDay?.let { LocalDate.now().toEpochDay() - it }
    }
    val syncDue = tt.jwxtPage.isNotBlank() && tt.syncRemindDays > 0 && !syncDismissed &&
        (daysSinceSync == null || daysSinceSync >= tt.syncRemindDays)

    fun openSync() {
        ctx.startActivity(
            Intent(ctx, WebImportActivity::class.java)
                .putExtra(WebImportActivity.EXTRA_URL, tt.jwxtPage)
                .putExtra(WebImportActivity.EXTRA_HOME, tt.jwxtHome)
                .putExtra(WebImportActivity.EXTRA_HAS_TERM, true)
        )
    }

    // 首次打开先装示例，让人立刻看见这东西长什么样（不落盘，导入真课表即覆盖）
    LaunchedEffect(Unit) {
        val stored = Store.load(ctx)
        // 装了新版、或者上次退出后闹钟被系统清了，开一次 App 就补回来
        Reminders.reschedule(ctx, stored)
        recovered = Store.lastRecovery
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
            // 课表一变，已经排好的提醒闹钟就都作废了，重排
            Reminders.reschedule(ctx, next)
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

        // 有新版就在这儿说一声，点一下就能更新完 —— 不用再下文件、进文件管理器
        newVersion?.let { m ->
            if (!showUpdate) {
                Row(
                    Modifier.fillMaxWidth().background(pal.signal.copy(alpha = 0.1f))
                        .clickable { showUpdate = true }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Tag(pal, "新版", pal.signal)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "v${m.versionName.ifBlank { m.versionCode.toString() }}" +
                            (if (m.notes.isNotBlank()) " · ${m.notes}" else "") + " · 点这里更新",
                        color = pal.signal, fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                HorizontalDivider(thickness = 1.dp, color = pal.rule)
            }
        }

        if (syncDue) {
            Row(
                Modifier.fillMaxWidth().background(pal.panel2)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (daysSinceSync == null) "还没和教务系统对过课表"
                    else "已经 $daysSinceSync 天没查调课了",
                    color = pal.ink2, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                OutlineChip(pal, "查一下") { openSync() }
                Spacer(Modifier.width(6.dp))
                OutlineChip(pal, "以后") { syncDismissed = true }
            }
            HorizontalDivider(thickness = 1.dp, color = pal.rule)
        }

        // 数据读不出来这种事必须说出来。静默变空是最坏的表现：
        // 用户以为自己手贱删了，其实文件还在。
        recovered?.let {
            Row(
                Modifier.fillMaxWidth().background(pal.signal.copy(alpha = 0.1f))
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "上次的数据读不出来，原文件已经留着没删。设置 → 数据 里可以导出。",
                    color = pal.signal, fontSize = 12.sp, lineHeight = 16.sp,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                OutlineChip(pal, "知道了") { recovered = null; Store.lastRecovery = null }
            }
            HorizontalDivider(thickness = 1.dp, color = pal.rule)
        }

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
            onEditOverride = { overrideDate = it },
            onOpenUpdate = { showSettings = false; showUpdate = true },
            hasUpdate = newVersion != null
        )
    }
    if (showUpdate) {
        UpdateSheet(
            pal = pal, tt = tt, found = newVersion,
            onClose = { showUpdate = false },
            onApply = { commit(it) }
        )
    } else if (showPrompt && newVersion != null) {
        fun snooze() = commit(
            tt.copy(
                updateSnoozeCode = newVersion.versionCode,
                updateSnoozeDay = LocalDate.now().toEpochDay()
            )
        )
        UpdatePrompt(
            pal = pal, m = newVersion,
            onLater = { snooze() },
            onUpdate = { snooze(); showUpdate = true }
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
    var msg by remember { mutableStateOf<String?>(null) }
    var err by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<Uri?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) { err = false; msg = null; picked = uri }
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
                    "不需要为学校单独写登录逻辑。\n" +
                    "填的是网址，不是 App 名字 —— 形如 jwxt.xxx.edu.cn、ehall.xxx.edu.cn。" +
                    "不知道就在电脑上打开教务系统，抄地址栏那一串。"
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = jwxtUrl, onValueChange = { jwxtUrl = it },
                placeholder = { Text("教务系统网址，如 jwxt.xxx.edu.cn", fontSize = 12.sp) },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            PrimaryButton(pal, "打开并抓取", enabled = jwxtUrl.isNotBlank()) {
                val u = normalizeSiteUrl(jwxtUrl)
                if (u == null) {
                    err = true
                    msg = "「${jwxtUrl.trim()}」不是网址。这里要填的是你在电脑浏览器里" +
                        "打开教务系统时，地址栏上那一串，形如 jwxt.xxx.edu.cn 或 " +
                        "ehall.xxx.edu.cn —— 不是 App 或门户的名字。"
                } else {
                    webImport.launch(
                        Intent(ctx, WebImportActivity::class.java)
                            .putExtra(WebImportActivity.EXTRA_URL, u)
                            .putExtra(WebImportActivity.EXTRA_HOME, u)
                            .putExtra(WebImportActivity.EXTRA_HAS_TERM, tt.termStartEpochDay != null)
                    )
                }
            }

            Spacer(Modifier.height(22.dp))
            Label(pal, "2 · 从文件导入")
            Hint(
                pal,
                "学校发的总课表。Excel（.xlsx）、CSV、截图照片、PDF 都行。" +
                    "表格里要有「星期一…星期五」这样的表头。" +
                    "图片和 PDF 靠识别，会掉字，导入前务必对一遍。"
            )
            Spacer(Modifier.height(8.dp))
            PrimaryButton(pal, "选择文件") {
                picker.launch(
                    arrayOf(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "text/csv", "text/comma-separated-values", "text/plain",
                        "application/vnd.ms-excel", "application/pdf", "image/*", "*/*"
                    )
                )
            }

            msg?.let {
                Spacer(Modifier.height(14.dp))
                MsgBox(pal, it, err)
            }
        }
    }

    picked?.let { uri ->
        FileImportDialog(
            pal = pal, tt = tt, uri = uri,
            onClose = { picked = null },
            onImported = { onImported(it) }
        )
    }
}

/* ------------------------------------------------------------------ 设置 */

@Composable
private fun SettingsDialog(
    pal: Palette, d: Derived, hues: Map<String, Float>, hourDp: Dp,
    onHourDp: (Dp) -> Unit, onClose: () -> Unit,
    onApply: (Timetable) -> Unit, onEditOverride: (LocalDate) -> Unit,
    onOpenUpdate: () -> Unit, hasUpdate: Boolean
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf<String?>(null) }
    var showPeriods by remember { mutableStateOf(false) }
    // 会覆盖/清掉数据的按钮一律两步，误触一下不至于把整张课表没了
    var confirmResync by remember { mutableStateOf(false) }
    var confirmRefetch by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    // 权限状态是系统里的，Compose 感知不到变化；从系统设置页回来后靠这个刷一下
    var permTick by remember { mutableIntStateOf(0) }
    val notifPerm = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { permTick++ }

    val backupExport = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) scope.launch {
            runCatching { Store.exportTo(ctx, uri) }.fold(
                onSuccess = { msg = "已导出 $it 节课的备份。换手机或重装后用「从备份恢复」读回来。" },
                onFailure = { msg = "导出失败：${it.message}" }
            )
        }
    }
    val backupImport = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) scope.launch {
            runCatching { Store.restoreFrom(ctx, uri) }.fold(
                onSuccess = { onApply(it); msg = "已恢复 ${it.sessions.size} 节课。" },
                onFailure = { msg = "恢复失败：${it.message}" }
            )
        }
    }

    var corrupt by remember { mutableStateOf(Store.corruptFiles(ctx)) }
    var pendingExport by remember { mutableStateOf<File?>(null) }
    // 隔离起来的文件在应用私有目录里，手机上翻不到。能导出来才叫"留着"。
    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        val src = pendingExport
        if (uri != null && src != null) {
            runCatching {
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(src.readBytes()) }
            }.fold(
                onSuccess = { msg = "已导出。" },
                onFailure = { msg = "导出失败：${it.message}" }
            )
        }
        pendingExport = null
    }

    val appVersion = remember {
        runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }

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

            Spacer(Modifier.height(22.dp))
            Label(pal, "上课提醒")
            Hint(pal, "上课前推一条通知。调休算数：放假那天不会响，调过来的课按新日期响。")
            Spacer(Modifier.height(8.dp))
            FieldRow(pal, "开启提醒", if (d.tt.remindEnabled) "当前：开" else "当前：关") {
                OutlineChip(pal, if (d.tt.remindEnabled) "关掉" else "打开") {
                    val turningOn = !d.tt.remindEnabled
                    if (turningOn && android.os.Build.VERSION.SDK_INT >= 33) {
                        notifPerm.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                    onApply(d.tt.copy(remindEnabled = turningOn))
                    permTick++
                }
            }
            if (d.tt.remindEnabled) {
                FieldRow(pal, "提前多久", "上课前 ${d.tt.remindMinutes} 分钟") {
                    IntStepper(pal, d.tt.remindMinutes, 0, 120, 5, " 分") {
                        onApply(d.tt.copy(remindMinutes = it))
                    }
                }

                // 权限状态。这两样任何一个没给，提醒就是哑的 —— 必须说出来，
                // 不然用户开了开关以为好了，等漏了课才发现
                val canPost = remember(permTick) { Reminders.canPost(ctx) }
                val canExact = remember(permTick) { Reminders.canExact(ctx) }
                if (!canPost) {
                    Spacer(Modifier.height(6.dp))
                    MsgBox(pal, "系统里这个 App 的通知是关的，提醒发不出来。", error = true)
                    Spacer(Modifier.height(6.dp))
                    OutlineChip(pal, "去开通知") {
                        Reminders.openNotificationSettings(ctx); permTick++
                    }
                }
                if (!canExact) {
                    Spacer(Modifier.height(6.dp))
                    MsgBox(
                        pal,
                        "没有「闹钟和提醒」权限，系统可能把提醒推迟十几分钟才发，提前量就不准了。",
                        error = true
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlineChip(pal, "去开精确闹钟") {
                        Reminders.openExactAlarmSettings(ctx); permTick++
                    }
                }

                Spacer(Modifier.height(6.dp))
                val next3 = remember(d, d.tt.remindMinutes) {
                    d.upcoming(System.currentTimeMillis(), 3)
                }
                if (next3.isEmpty()) {
                    Hint(pal, "接下来没有课，暂时没有要提醒的。")
                } else {
                    Hint(pal, "接下来会在这几个时间点响：")
                    next3.forEach { s ->
                        Text(
                            "  ${(s.start - d.tt.remindMinutes * 60_000L).hhmm()}  →  " +
                                "${s.title} ${s.start.toLocalDate().abbr()} ${s.start.hhmm()}",
                            color = pal.muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlineChip(pal, "试一条") { Reminders.testNotify(ctx, d.tt.remindMinutes); permTick++ }
                Spacer(Modifier.height(6.dp))
                Hint(pal, "小米还要把本应用加进「自启动」和省电白名单，否则后台会被杀，提醒就不响了。")
            }

            if (d.tt.jwxtPage.isNotBlank()) {
                Spacer(Modifier.height(22.dp))
                Label(pal, "查调课")
                Hint(
                    pal,
                    "打开上次出课表的那一页，抓下来和现在这份比一遍，" +
                        "把学校改动过的地方列出来，你确认了才写进去。" +
                        "作息、开学日期、你手动加的课和调休记录都不会被动。"
                )
                Spacer(Modifier.height(8.dp))
                FieldRow(
                    pal, "上次对过",
                    d.tt.lastSyncEpochDay?.let {
                        val n = LocalDate.now().toEpochDay() - it
                        if (n <= 0) "今天" else "$n 天前"
                    } ?: "还没对过"
                ) { Spacer(Modifier.width(0.dp)) }
                FieldRow(
                    pal, "隔几天提醒一次",
                    if (d.tt.syncRemindDays <= 0) "关掉了，不提醒" else "首页会出一条提示"
                ) {
                    IntStepper(pal, d.tt.syncRemindDays, 0, 30, suffix = " 天") {
                        onApply(d.tt.copy(syncRemindDays = it))
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (!confirmResync) {
                    PrimaryButton(pal, "去查一下有没有调课") { confirmResync = true }
                } else {
                    MsgBox(
                        pal,
                        "会打开教务系统那一页重新抓一次。抓完先把变动列给你看，" +
                            "你点了「应用」才会改课表；直接关掉的话什么都不变。"
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PrimaryButton(pal, "确认，去查") {
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
            Label(pal, "版本与更新")
            FieldRow(
                pal,
                if (appVersion.isNotBlank()) "当前版本 v$appVersion" else "当前版本",
                when {
                    hasUpdate -> "有新版可以更新"
                    d.tt.updateUrl.isBlank() -> "已关掉检查，不会联网"
                    else -> "自动检查：${d.tt.updateUrl}"
                }
            ) {
                OutlineChip(pal, if (hasUpdate) "去更新" else "检查更新", onClick = onOpenUpdate)
            }

            Spacer(Modifier.height(22.dp))
            Label(pal, "数据")
            Hint(
                pal,
                "课表只存在这台手机上，不上传。手动添加的条目在重新导入时会保留。" +
                    "换手机、卸载重装之前先导出一份备份。"
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PrimaryButton(pal, "导出备份") { backupExport.launch(Store.backupName()) }
                Spacer(Modifier.width(8.dp))
                OutlineChip(pal, "从备份恢复") { backupImport.launch(arrayOf("application/json", "*/*")) }
            }

            if (corrupt.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                MsgBox(
                    pal,
                    "有 ${corrupt.size} 份读不出来的旧数据被留了下来，没有覆盖掉。" +
                        "导出来发我，多半能把里面手动加的课和调休捞回来。",
                    error = true
                )
                corrupt.forEach { f ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                f.name.removePrefix("timetable.corrupt-").removeSuffix(".json"),
                                color = pal.ink2, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            Text("${f.length()} 字节", color = pal.faint, fontSize = 11.sp)
                        }
                        OutlineChip(pal, "导出") {
                            pendingExport = f
                            exporter.launch(f.name)
                        }
                        Spacer(Modifier.width(6.dp))
                        DangerChip(pal, "删除") {
                            f.delete()
                            corrupt = Store.corruptFiles(ctx)
                        }
                    }
                }
            }

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
                        // 更新地址不是课表数据，清课表不该把它一起清掉
                        onApply(Timetable(updateUrl = d.tt.updateUrl))
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
