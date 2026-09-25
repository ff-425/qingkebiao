package com.qingkebiao.timetable

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qingkebiao.timetable.widget.refreshWidgets
import com.qingkebiao.timetable.widget.scheduleWidgetRefresh
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
// 本包里有个同名的 Arrangement（一门课的上课安排），布局用的那个起个别名
import androidx.compose.foundation.layout.Arrangement as Arr

class MainActivity : ComponentActivity() {
    /**
     * 每次回到前台 +1。
     * Activity 不会被销毁，Compose 的状态就一直留着 —— 上次翻到第 12 周，
     * 明天打开还是第 12 周。课表这种东西，打开就该是今天。
     */
    private val resumeTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 全屏到状态栏/导航栏下面。顶栏自己把背景铺到状态栏底下，
        // 内容再各自让开 —— 这样状态栏和顶栏之间不会有一道色差。
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

/** 翻页、推入设置页的动画时长。短一点，手感跟手，也少画几帧。 */
internal const val PAGE_MS = 220

/** 内容区现在显示的是哪一"页"。翻页动画靠比较前后两页决定往哪边滑。 */
private data class Page(val view: ViewMode, val day: LocalDate?, val week: Int) {
    val order: Long get() = day?.toEpochDay() ?: week.toLong()
}

@Composable
private fun App(resumeTick: Int) {
    val dark = isSystemInDarkTheme()
    val pal = remember(dark) { if (dark) DarkPalette else LightPalette }

    val scheme = remember(pal) {
        if (pal.dark) darkColorScheme(
            background = pal.paper, surface = pal.panel, surfaceVariant = pal.panel2,
            onBackground = pal.ink, onSurface = pal.ink, onSurfaceVariant = pal.ink2,
            primary = pal.ink, onPrimary = pal.paper, outline = pal.rule
        ) else lightColorScheme(
            background = pal.paper, surface = pal.panel, surfaceVariant = pal.panel2,
            onBackground = pal.ink, onSurface = pal.ink, onSurfaceVariant = pal.ink2,
            primary = pal.ink, onPrimary = pal.paper, outline = pal.rule
        )
    }

    MaterialTheme(colorScheme = scheme) {
        Surface(color = pal.paper, modifier = Modifier.fillMaxSize()) {
            Home(pal, resumeTick)
        }
    }
}

@Composable
private fun Home(pal: Palette, resumeTick: Int) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    var tt by remember { mutableStateOf(Timetable()) }
    var view by remember { mutableStateOf(ViewMode.Day) }
    var weekIdx by remember { mutableIntStateOf(1) }
    var dayDate by remember { mutableStateOf(LocalDate.now()) }
    var hourDp by remember { mutableStateOf(64.dp) }
    var showImport by remember { mutableStateOf(false) }
    /** 打开导入框时默认选"作为新学期"（从设置 → 学期里点进来的） */
    var importAsNew by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var settingsPage by remember { mutableStateOf(SPage.Root) }
    var endedDismissed by remember { mutableStateOf(false) }
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
    // 顶上那张提示卡一直留着 —— 想更新随时点得到，但不会每次切回来都糊你一脸
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
        // 有 termId 的空课表是用户自己新建的空白学期，不能拿示例把它盖掉
        tt = if (stored.sessions.isNotEmpty() || stored.termId.isNotBlank()) stored else stored.copy(
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

    /** 学期里第一节 / 最后一节课那天。翻页翻出学期范围时落到这里。 */
    fun firstDay(): LocalDate =
        tt.sessions.minByOrNull { it.start }?.start?.toLocalDate() ?: d.termStart
    fun lastDay(): LocalDate =
        tt.sessions.maxByOrNull { it.end }?.end?.toLocalDate() ?: d.mondayOfWeek(maxOf(1, d.weeks)).plusDays(6)

    /**
     * "今天"该落在哪天。平时就是今天；但看的是已经结束的学期（从历史里切回来的），
     * 今天早就不在学期里了，停在今天只会看到一片空白，所以落到最后一天。
     * 还没开学的情况照旧停在今天 —— 那时"下一节是哪天"的提示正有用。
     */
    fun anchorDay(): LocalDate {
        val today = LocalDate.now()
        return if (d.weeks > 0 && d.weekOf(today) > d.weeks) lastDay() else today
    }

    // 每次回到前台都归位到今天 / 本周，不保留上次翻到哪儿了；
    // 切换学期、导入新学期之后同理
    LaunchedEffect(resumeTick, tt.termId, tt.sessions.isEmpty()) {
        val day = anchorDay()
        view = ViewMode.Day
        dayDate = day
        weekIdx = d.clampWeek(d.weekOf(day))
    }

    // "进行中""还有几分钟"这些要自己走，半分钟一拍足够
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    /** 整份课表换掉（切学期、导入完成）。数据已经在盘上了，这里只刷新界面、小组件和提醒。 */
    fun replaceActive(next: Timetable) {
        tt = next
        endedDismissed = false
        scope.launch {
            refreshWidgets(ctx)
            Reminders.reschedule(ctx, next)
        }
    }

    fun openImport(asNew: Boolean) {
        importAsNew = asNew
        showImport = true
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

    val dayMode = view == ViewMode.Day
    val has = !d.isEmpty
    val canPrev = has && if (dayMode) d.weekOf(dayDate.minusDays(1)) >= 1 else weekIdx > 1
    val canNext = has && if (dayMode) d.weekOf(dayDate.plusDays(1)) <= d.weeks else weekIdx < d.weeks

    fun step(dir: Int) {
        if (view == ViewMode.Day) {
            val nd = dayDate.plusDays(dir.toLong())
            val w = d.weekOf(nd)
            when {
                w in 1..d.weeks -> { dayDate = nd; weekIdx = w }
                // 停在学期外面（开学前 / 结束后）往学期里翻，直接跳到学期边上那天，
                // 不然要一天天空翻过整个假期
                dir > 0 && w < 1 -> { dayDate = firstDay(); weekIdx = d.clampWeek(d.weekOf(dayDate)) }
                dir < 0 && w > d.weeks -> { dayDate = lastDay(); weekIdx = d.clampWeek(d.weekOf(dayDate)) }
            }
        } else {
            weekIdx = d.clampWeek(weekIdx + dir)
        }
    }

    fun goToday() {
        val day = anchorDay()
        dayDate = day
        weekIdx = d.clampWeek(d.weekOf(day))
    }

    val today = LocalDate.now()
    val anchor = anchorDay()
    val atToday = if (dayMode) dayDate == anchor else weekIdx == d.clampWeek(d.weekOf(anchor))
    // 这学期已经上完了（多半是该换下学期了，或者正在翻历史学期）
    val termEnded = has && d.weeks > 0 && d.weekOf(today) > d.weeks && tt.sourceLabel != "示例课表"

    // 手势里拿到的永远是最新的 step，不用因为课表一变就重建手势
    val stepRef = rememberUpdatedState<(Int) -> Unit> { step(it) }
    val swipePx = with(density) { 56.dp.toPx() }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
        ) {
            TopBar(
                pal = pal, d = d, view = view, weekIdx = weekIdx, dayDate = dayDate,
                canPrev = canPrev, canNext = canNext, atToday = atToday,
                settingsBadge = newVersion != null,
                onStep = { step(it) },
                onToday = { goToday() },
                onView = {
                    view = it
                    if (it == ViewMode.Day) {
                        dayDate = if (d.weeks > 0 && d.weekOf(today) == weekIdx) today
                        else d.mondayOfWeek(weekIdx)
                    } else if (d.weeks > 0) {
                        weekIdx = d.clampWeek(d.weekOf(dayDate))
                    }
                },
                onAdd = { editorFor = null; editorOpen = true },
                onSettings = { settingsPage = SPage.Root; showSettings = true }
            )

            // 顶上的提示同一时间只放一条，按轻重排：数据出事 > 有新版 > 该查调课了。
            // 以前是三条横幅一起往下叠，首页像是拼起来的。
            val rec = recovered
            when {
                // 数据读不出来这种事必须说出来。静默变空是最坏的表现：
                // 用户以为自己手贱删了，其实文件还在。
                rec != null -> NoticeCard(
                    pal, "上次的数据读不出来，原文件已经留着没删。设置 → 数据与隐私 里可以导出。",
                    accent = pal.warn,
                    primary = "知道了" to { recovered = null; Store.lastRecovery = null }
                )

                // 首次打开会自动放一份示例课表。导入入口挪进设置以后，
                // 新装的同学看到的就是一份假的"高等数学"，首页上没有任何地方说这是示例、该去哪导入。
                tt.sourceLabel == "示例课表" -> NoticeCard(
                    pal, "这是示例课表，导入你自己的就会替换掉",
                    accent = pal.signal,
                    primary = "导入" to { openImport(false) },
                    onClick = { openImport(false) }
                )

                // 有新版就在这儿说一声，点一下就能更新完 —— 不用再下文件、进文件管理器
                newVersion != null && !showUpdate -> NoticeCard(
                    pal,
                    "有新版本 v${newVersion.versionName.ifBlank { newVersion.versionCode.toString() }}" +
                        if (newVersion.notes.isNotBlank()) " · ${newVersion.notes}" else "",
                    accent = pal.signal,
                    primary = "更新" to { showUpdate = true },
                    onClick = { showUpdate = true }
                )

                // 学期结束了：可能是该导入下学期了，也可能是从历史里切回来翻旧课表
                termEnded && !endedDismissed -> NoticeCard(
                    pal, "「${tt.termTitle()}」已经结束了",
                    accent = pal.ink,
                    primary = "换学期" to { settingsPage = SPage.Terms; showSettings = true },
                    secondary = "知道了" to { endedDismissed = true }
                )

                syncDue && !termEnded -> NoticeCard(
                    pal,
                    if (daysSinceSync == null) "还没和教务系统对过课表"
                    else "已经 $daysSinceSync 天没查调课了",
                    accent = pal.ink,
                    primary = "查一下" to { openSync() },
                    secondary = "以后" to { syncDismissed = true }
                )
            }

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    // 左右滑翻页。只认水平方向，竖着滚课表不受影响
                    .pointerInput(Unit) {
                        var dx = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { dx = 0f },
                            onDragEnd = {
                                if (dx < -swipePx) stepRef.value(1)
                                else if (dx > swipePx) stepRef.value(-1)
                            }
                        ) { change, amount ->
                            change.consume()
                            dx += amount
                        }
                    }
            ) {
                if (d.isEmpty) {
                    EmptyState(
                        pal,
                        onImport = { openImport(false) },
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
                } else {
                    AnimatedContent(
                        targetState = Page(view, if (dayMode) dayDate else null, if (dayMode) 0 else weekIdx),
                        // 只平移、不做淡入淡出：淡入淡出要把整页先画到离屏缓冲再混合，
                        // 周视图那么多色块，每帧都这么来一遍，中低端机上就是掉帧的主要来源。
                        // 今日 / 本周切换直接换，不做动画 —— 两种视图长得完全不一样，滑过去反而晃眼。
                        transitionSpec = {
                            if (initialState.view != targetState.view) {
                                EnterTransition.None togetherWith ExitTransition.None
                            } else {
                                val fwd = targetState.order > initialState.order
                                slideInHorizontally(tween(PAGE_MS, easing = FastOutSlowInEasing)) { w ->
                                    if (fwd) w else -w
                                } togetherWith slideOutHorizontally(tween(PAGE_MS, easing = FastOutSlowInEasing)) { w ->
                                    if (fwd) -w else w
                                }
                            }
                        },
                        label = "page"
                    ) { pg ->
                        if (pg.view == ViewMode.Day && pg.day != null) {
                            DayList(
                                pal, d, hues, pg.day, now,
                                onPick = { detail = it },
                                onOverride = { overrideDate = pg.day }
                            )
                        } else {
                            WeekGrid(pal, d, hues, pg.week, hourDp, now) { detail = it }
                        }
                    }
                }
            }
        }

        // 设置是一整页，从右边推进来；系统返回键先退子页面，再关掉设置
        AnimatedVisibility(
            visible = showSettings,
            enter = slideInHorizontally(tween(PAGE_MS, easing = FastOutSlowInEasing)) { it },
            exit = slideOutHorizontally(tween(PAGE_MS, easing = FastOutSlowInEasing)) { it }
        ) {
            SettingsPage(
                pal = pal, d = d, hues = hues, hourDp = hourDp,
                onHourDp = { hourDp = it },
                onClose = { showSettings = false },
                onApply = { commit(it) },
                onEditOverride = { overrideDate = it },
                onOpenUpdate = { showUpdate = true },
                onImport = { openImport(it) },
                onReplace = { replaceActive(it) },
                hasUpdate = newVersion != null,
                initialPage = settingsPage
            )
        }
    }

    if (showImport) {
        ImportDialog(
            pal, tt, presetNew = importAsNew,
            onClose = { showImport = false },
            onImported = { replaceActive(it) }
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

private fun LocalDate.md(): String = "${monthValue}月${dayOfMonth}日"

@Composable
private fun TopBar(
    pal: Palette,
    d: Derived,
    view: ViewMode,
    weekIdx: Int,
    dayDate: LocalDate,
    canPrev: Boolean,
    canNext: Boolean,
    atToday: Boolean,
    settingsBadge: Boolean,
    onStep: (Int) -> Unit,
    onToday: () -> Unit,
    onView: (ViewMode) -> Unit,
    onAdd: () -> Unit,
    onSettings: () -> Unit
) {
    val has = !d.isEmpty
    val dayMode = view == ViewMode.Day
    val today = LocalDate.now()

    val title = when {
        !has -> "清课表"
        dayMode -> "${dayDate.md()} ${dayDate.abbr()}"
        else -> "第 $weekIdx 周"
    }
    val sub = when {
        !has -> "还没有课表"
        dayMode -> {
            val wk = d.weekOf(dayDate)
            val rel = when (dayDate) {
                today -> " · 今天"
                today.plusDays(1) -> " · 明天"
                today.minusDays(1) -> " · 昨天"
                else -> ""
            }
            (if (wk in 1..d.weeks) "第 $wk 周" else "不在学期内") + rel
        }
        else -> {
            val mon = d.mondayOfWeek(weekIdx)
            "${mon.md()} – ${mon.plusDays(6).md()}" + if (atToday) " · 本周" else " · 共 ${d.weeks} 周"
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 20.dp, end = Dim.s, top = Dim.s, bottom = Dim.s)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    title, color = pal.ink, fontSize = Fs.display, fontWeight = FontWeight.Bold,
                    style = NumStyle, maxLines = 1
                )
                Text(sub, color = pal.muted, fontSize = 13.sp, style = NumStyle, maxLines = 1)
            }
            IconBtn(pal, Icons.Default.Add, "添加课程", onClick = onAdd)
            Box {
                IconBtn(pal, Icons.Default.Settings, "设置", onClick = onSettings)
                if (settingsBadge) {
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp)
                            .size(8.dp).background(pal.signal, CircleShape)
                    )
                }
            }
        }
        if (has) {
            Spacer(Modifier.height(Dim.m))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SegToggle(pal, dayMode, onView)
                Spacer(Modifier.weight(1f))
                if (!atToday) {
                    TextBtn(pal, if (dayMode) "回到今天" else "回到本周", color = pal.signal, onClick = onToday)
                }
                IconBtn(pal, Icons.AutoMirrored.Filled.KeyboardArrowLeft, "上一页", canPrev) { onStep(-1) }
                IconBtn(pal, Icons.AutoMirrored.Filled.KeyboardArrowRight, "下一页", canNext) { onStep(1) }
            }
        }
    }
}

@Composable
private fun SegToggle(pal: Palette, dayMode: Boolean, onView: (ViewMode) -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(pal.rule.copy(alpha = 0.55f))
            .padding(3.dp)
    ) {
        listOf(ViewMode.Day to "今日", ViewMode.Week to "本周").forEach { (mode, label) ->
            val on = (mode == ViewMode.Day) == dayMode
            Box(
                Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (on) pal.panel else Color.Transparent)
                    .clickable { onView(mode) }
                    .padding(horizontal = 20.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label, color = if (on) pal.ink else pal.muted,
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1
                )
            }
        }
    }
}

/* ------------------------------------------------------------------ 提示卡 */

@Composable
private fun NoticeCard(
    pal: Palette,
    text: String,
    accent: Color,
    primary: Pair<String, () -> Unit>,
    secondary: Pair<String, () -> Unit>? = null,
    onClick: (() -> Unit)? = null
) {
    Row(
        Modifier
            .padding(horizontal = Dim.l, vertical = Dim.xs)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(pal.panel)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 14.dp, end = Dim.xs, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).background(accent, CircleShape))
        Spacer(Modifier.width(10.dp))
        Text(
            text, color = pal.ink2, fontSize = 13.sp, lineHeight = 18.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
        )
        secondary?.let { (label, act) -> TextBtn(pal, label, color = pal.muted, onClick = act) }
        TextBtn(pal, primary.first, color = if (accent == pal.ink) pal.ink else accent, onClick = primary.second)
    }
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
    val gutter = 42.dp
    val scroll = rememberScrollState()
    val density = LocalDensity.current

    // 刻度取课本身的起止时刻。整点没有任何事发生，写 09:00 只会让人去心算。
    val marks = remember(d.tt.sessions, lo, hi) { timeMarks(d.tt.sessions, lo, hi) }
    // 挤在一起的标签读不了，按当前缩放留出最小间距，开始时刻优先保留
    val labels = remember(marks, minuteDp) {
        val minGap = if (minuteDp.value > 0.01f) (14.dp / minuteDp) else 20f
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

    // 整张表是一块从底部升起的白卡，和上面灰底的顶栏分开，不再靠一条条分隔线切
    Column(
        Modifier
            .fillMaxSize()
            .padding(top = Dim.xs)
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .background(pal.panel)
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = Dim.s)) {
            Spacer(Modifier.width(gutter))
            days.forEach { day ->
                val isToday = day == today
                val ov = d.overrideFor(day)
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        day.abbr().removePrefix("周"),
                        color = if (isToday) pal.signal else pal.muted,
                        fontSize = Fs.caption, fontWeight = FontWeight.SemiBold, maxLines = 1
                    )
                    Spacer(Modifier.height(3.dp))
                    // 今天那一列的日期放进红色圆里，比单把字变红好认得多
                    Box(
                        Modifier
                            .heightIn(min = 28.dp)
                            .widthIn(min = 28.dp)
                            .background(if (isToday) pal.signal else Color.Transparent, CircleShape)
                            .padding(horizontal = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (day.dayOfMonth == 1) "${day.monthValue}月" else "${day.dayOfMonth}",
                            color = if (isToday) pal.paper else pal.ink2,
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, style = NumStyle, maxLines = 1
                        )
                    }
                    // 调休的日子给个小标记，不然课变了却看不出原因
                    if (ov != null) {
                        Text(
                            if (ov.kind == OverrideKind.HOLIDAY) "放假" else "调休",
                            color = pal.warn, fontSize = Fs.micro, fontWeight = FontWeight.Bold, maxLines = 1
                        )
                    }
                }
            }
        }
        HorizontalDivider(thickness = 1.dp, color = pal.ruleSoft)

        Row(
            Modifier
                .weight(1f)
                .verticalScroll(scroll)
                .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
        ) {
            Box(Modifier.width(gutter).height(total)) {
                labels.forEach { mk ->
                    Text(
                        mk.minute.hhmm(),
                        // 上课时刻是要看的，下课时刻只是参照，压暗一档
                        color = if (mk.isStart) pal.ink2 else pal.faint,
                        fontSize = Fs.micro,
                        fontWeight = if (mk.isStart) FontWeight.SemiBold else FontWeight.Normal,
                        style = NumStyle, textAlign = TextAlign.End, maxLines = 1,
                        modifier = Modifier
                            .offset(y = minuteDp * (mk.minute - lo) - 7.dp)
                            .fillMaxWidth()
                            .padding(end = 6.dp)
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
        weekend -> pal.ink.copy(alpha = 0.02f)
        else -> Color.Transparent
    }

    val density = LocalDensity.current
    // 每个课程块的上沿和高度，只在课表或缩放变了才重算
    val geo = remember(placed, lo, hi, minuteDp) {
        placed.map { p ->
            val st = maxOf(lo, p.session.startMinute())
            val en = minOf(hi, p.session.endMinute())
            val top = minuteDp * (st - lo) + 1.dp
            val h = (minuteDp * (en - st) - 2.dp).coerceAtLeast(20.dp)
            top to h
        }
    }
    val nowMin = if (isToday) now.toLocalDateTime().let { it.hour * 60 + it.minute } else -1
    val showNow = isToday && nowMin in lo..hi

    // 自己排版，不用 BoxWithConstraints：那个是"先量宽度再组合内容"的子组合，
    // 一屏七列、翻页时新旧两页同时在，就是十几次子组合，开销比直接排大得多。
    Layout(
        modifier = modifier.height(total).background(bg).drawBehind {
            val perMin = minuteDp.toPx()
            // 网格线画在上下课时刻上，和课程块的边缘正好重合
            marks.forEach { mk ->
                val y = (mk.minute - lo) * perMin
                drawLine(
                    color = if (mk.isStart) pal.rule.copy(alpha = 0.7f) else pal.ruleSoft,
                    start = Offset(0f, y), end = Offset(size.width, y), strokeWidth = 1f
                )
            }
            drawLine(pal.ruleSoft, Offset(0f, 0f), Offset(0f, size.height), 1f)
        },
        content = {
            placed.forEachIndexed { i, p ->
                val s = p.session
                // 暗色底上 0.45 会把已过的课压到几乎看不见，单独抬一档
                val alpha = if (s.end < now) pastAlpha(pal) else 1f
                WeekBlock(pal, s, hues[s.title] ?: 0f, alpha, geo[i].second, onPick)
            }
            if (showNow) {
                Box(Modifier.fillMaxWidth().height(2.dp).background(pal.signal))
                Box(Modifier.size(8.dp).background(pal.signal, CircleShape))
            }
        }
    ) { measurables, constraints ->
        val w = constraints.maxWidth
        val placeables = measurables.mapIndexed { i, m ->
            if (i < placed.size) {
                val p = placed[i]
                val bw = w / p.cols
                val bh = with(density) { geo[i].second.roundToPx() }
                m.measure(Constraints.fixed(bw, bh))
            } else {
                m.measure(Constraints(maxWidth = w))
            }
        }
        layout(w, constraints.maxHeight) {
            placeables.forEachIndexed { i, pl ->
                if (i < placed.size) {
                    val p = placed[i]
                    pl.place(w * p.col / p.cols, with(density) { geo[i].first.roundToPx() })
                } else {
                    // 当前时刻：一条红线 + 左端一个圆点
                    val y = with(density) { (minuteDp * (nowMin - lo)).roundToPx() }
                    pl.place(0, y - pl.height / 2)
                }
            }
        }
    }
}

/** 周视图里的一个课程块。 */
@Composable
private fun WeekBlock(
    pal: Palette, s: Session, hue: Float, alpha: Float, h: Dp, onPick: (Session) -> Unit
) {
    val ink = blockText(hue, pal.dark).copy(alpha = alpha)
    val edge = blockEdge(hue, pal.dark).copy(alpha = alpha)
    Box(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 1.5.dp)
            // 用带圆角的背景，不用 clip：clip 会给每个块单独开一个图层
            .background(blockFill(hue, pal.dark).copy(alpha = alpha), RoundedCornerShape(6.dp))
            .drawBehind {
                val bar = 3.dp.toPx()
                val inset = 3.dp.toPx()
                drawRoundRect(
                    edge, topLeft = Offset(0f, inset),
                    size = Size(bar, (size.height - inset * 2).coerceAtLeast(0f)),
                    cornerRadius = CornerRadius(bar / 2)
                )
            }
            .clickable { onPick(s) }
            .padding(start = 6.dp, end = 3.dp, top = 4.dp, bottom = 3.dp)
    ) {
        Column {
            Text(
                s.title, color = ink,
                fontSize = Fs.caption, fontWeight = FontWeight.SemiBold, lineHeight = 15.sp,
                maxLines = if (h >= 64.dp) 3 else 2, overflow = TextOverflow.Ellipsis
            )
            // 表格里时间已经由左边的刻度给了，块里写地点更有用
            if (h >= 50.dp) {
                Spacer(Modifier.height(2.dp))
                Text(
                    s.location.ifBlank { s.start.hhmm() },
                    color = ink.copy(alpha = alpha * 0.8f),
                    fontSize = Fs.micro, lineHeight = 13.sp,
                    maxLines = if (h >= 90.dp) 2 else 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/* ------------------------------------------------------------------ 今日视图 */

/** "下一节"什么时候：一小时内说几分钟后，再远就说哪天几点。 */
private fun whenText(s: Session, now: Long): String {
    val mins = (s.start - now) / 60000
    val today = LocalDate.now()
    val nd = s.start.toLocalDate()
    return when {
        mins < 60 -> "$mins 分钟后"
        nd == today -> "今天 ${s.start.hhmm()}"
        nd == today.plusDays(1) -> "明天 ${s.start.hhmm()}"
        else -> "${nd.md()} ${nd.abbr()} ${s.start.hhmm()}"
    }
}

private fun gapText(min: Long): String =
    if (min < 60) "课间 $min 分钟"
    else "空闲 ${min / 60} 小时" + if (min % 60 > 0) " ${min % 60} 分钟" else ""

private val TIME_COL = 52.dp

@Composable
private fun DayList(
    pal: Palette, d: Derived, hues: Map<String, Float>,
    day: LocalDate, now: Long, onPick: (Session) -> Unit, onOverride: () -> Unit
) {
    val items = remember(d, day) { d.sessionsOn(day) }
    val ov = d.overrideFor(day)
    val isToday = day == LocalDate.now()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // "下一节"要把往后三周的课都过一遍，别每次重组都算
    val next = remember(d, now, isToday) { if (isToday) d.next(now) else null }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Dim.l, end = Dim.l, top = Dim.xs, bottom = Dim.xl + navBottom
        )
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(bottom = Dim.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (items.isEmpty()) "没有安排" else "共 ${items.size} 节",
                    color = pal.muted, fontSize = 13.sp, modifier = Modifier.weight(1f)
                )
                TextBtn(
                    pal, if (ov == null) "调休" else "调休中",
                    color = if (ov == null) pal.muted else pal.warn, onClick = onOverride
                )
            }
        }

        if (ov != null) {
            item {
                Column {
                    MsgBox(
                        pal,
                        when (ov.kind) {
                            OverrideKind.HOLIDAY -> "这天放假，原本的课不上。"
                            OverrideKind.FOLLOW -> {
                                val src = ov.followEpochDay?.let { LocalDate.ofEpochDay(it) }
                                "这天调休，上 ${src?.md() ?: ""} ${src?.abbr() ?: ""} 的课。"
                            }
                        }
                    )
                    Spacer(Modifier.height(Dim.m))
                }
            }
        }

        // 今天的课都上完了（或者今天本来就没课）：直接告诉下一节是什么时候
        if (isToday && items.none { it.end > now }) {
            item {
                Column {
                    RestOfDayCard(pal, hues, hadClasses = items.isNotEmpty(), next = next, now = now)
                    Spacer(Modifier.height(Dim.l))
                }
            }
        } else if (items.isEmpty()) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(top = 72.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("这天没课", color = pal.ink2, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text("左右滑动可以看前后几天", color = pal.muted, fontSize = 13.sp)
                }
            }
        }

        itemsIndexed(items) { i, s ->
            Column {
                if (i > 0) {
                    val gap = (s.start - items[i - 1].end) / 60000
                    if (gap >= 5) {
                        Text(
                            gapText(gap), color = pal.faint, fontSize = Fs.caption,
                            modifier = Modifier.padding(start = TIME_COL + Dim.m, top = Dim.s, bottom = Dim.s)
                        )
                    } else {
                        Spacer(Modifier.height(10.dp))
                    }
                }
                CourseCard(pal, s, hues[s.title] ?: 0f, now) { onPick(s) }
            }
        }
    }
}

@Composable
private fun CourseCard(pal: Palette, s: Session, hue: Float, now: Long, onClick: () -> Unit) {
    val live = now in s.start until s.end
    val past = s.end < now
    val mins = (s.start - now) / 60000
    val soon = !live && s.start > now && mins < 45
    val alpha = if (past) pastAlpha(pal) else 1f
    val shape = RoundedCornerShape(Dim.rCard)

    // 左边那条课程色不再用"撑满父级高度"的子元素画 —— 那要先按固有尺寸量一遍，
    // 列表每滚出一张卡就多一轮测量。直接画在卡片背景上。
    val edge = blockEdge(hue, pal.dark).copy(alpha = alpha)
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.width(TIME_COL).padding(top = 13.dp)) {
            Text(
                s.start.hhmm(), color = pal.ink.copy(alpha = alpha),
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold, style = NumStyle
            )
            Text(
                s.end.hhmm(), color = pal.faint.copy(alpha = alpha),
                fontSize = Fs.caption, style = NumStyle
            )
        }
        Row(
            Modifier
                .weight(1f)
                .clip(shape)
                .background(pal.panel)
                .drawBehind { drawRect(edge, size = Size(5.dp.toPx(), size.height)) }
                .then(if (live) Modifier.border(1.5.dp, pal.signal, shape) else Modifier)
                .clickable(onClick = onClick)
        ) {
            Column(Modifier.weight(1f).padding(start = 17.dp, end = 14.dp, top = 12.dp, bottom = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        s.title, color = pal.ink.copy(alpha = alpha),
                        fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 21.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (soon) { Spacer(Modifier.width(Dim.s)); Tag(pal, "$mins 分钟后", pal.ink) }
                    if (s.manual) { Spacer(Modifier.width(Dim.s)); Tag(pal, "手动", pal.faint) }
                }
                if (s.location.isNotBlank() || s.teacher.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (s.location.isNotBlank()) {
                            MetaItem(pal, Icons.Default.Place, s.location, alpha, Modifier.weight(1f, fill = false))
                        }
                        if (s.location.isNotBlank() && s.teacher.isNotBlank()) Spacer(Modifier.width(Dim.m))
                        if (s.teacher.isNotBlank()) {
                            MetaItem(pal, Icons.Default.Person, s.teacher, alpha, Modifier)
                        }
                    }
                }
                if (live) {
                    val frac = ((now - s.start).toFloat() / (s.end - s.start).coerceAtLeast(1)).coerceIn(0f, 1f)
                    Spacer(Modifier.height(10.dp))
                    Box(
                        Modifier.fillMaxWidth().height(4.dp)
                            .background(pal.panel2, RoundedCornerShape(2.dp))
                    ) {
                        Box(
                            Modifier.fillMaxWidth(frac).height(4.dp)
                                .background(pal.signal, RoundedCornerShape(2.dp))
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "进行中 · 还有 ${(s.end - now) / 60000} 分钟下课",
                        color = pal.signal, fontSize = Fs.caption, fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun MetaItem(pal: Palette, icon: ImageVector, text: String, alpha: Float, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = pal.faint.copy(alpha = alpha), modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(3.dp))
        Text(
            text, color = pal.muted.copy(alpha = alpha), fontSize = 13.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun RestOfDayCard(
    pal: Palette, hues: Map<String, Float>, hadClasses: Boolean, next: Session?, now: Long
) {
    Card(pal) {
        Column(Modifier.padding(Dim.l)) {
            Text(
                if (hadClasses) "今天的课都上完了" else "今天没课",
                color = pal.ink, fontSize = Fs.title, fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(Dim.m))
            if (next == null) {
                Text("接下来没有安排了", color = pal.muted, fontSize = 13.sp)
            } else {
                Text("下一节", color = pal.muted, fontSize = Fs.caption)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.width(4.dp).height(38.dp)
                            .background(blockEdge(hues[next.title] ?: 0f, pal.dark), RoundedCornerShape(2.dp))
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            next.title, color = pal.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            whenText(next, now) + if (next.location.isNotBlank()) " · ${next.location}" else "",
                            color = pal.muted, fontSize = 13.sp, style = NumStyle,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ 空状态 */

@Composable
private fun EmptyState(pal: Palette, onImport: () -> Unit, onAdd: () -> Unit, onDemo: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 40.dp),
        verticalArrangement = Arr.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(72.dp).background(pal.panel, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.DateRange, null, tint = pal.muted, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text("还没有课表", color = pal.ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(Dim.s))
        Text(
            "从教务系统或文件导入，也可以自己一节一节加。",
            color = pal.muted, fontSize = Fs.body, lineHeight = 21.sp, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(28.dp))
        PrimaryButton(pal, "导入课表", modifier = Modifier.fillMaxWidth(), onClick = onImport)
        Spacer(Modifier.height(10.dp))
        OutlineChip(pal, "手动添加", modifier = Modifier.fillMaxWidth().heightIn(min = Dim.touch), onClick = onAdd)
        Spacer(Modifier.height(Dim.xs))
        TextBtn(pal, "先看看示例", color = pal.muted, onClick = onDemo)
    }
}

/* ------------------------------------------------------------------ 导入 */

@Composable
private fun ImportDialog(
    pal: Palette, tt: Timetable, presetNew: Boolean,
    onClose: () -> Unit, onImported: (Timetable) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // 现在有一份真课表时，才有"替换它还是存进历史"的问题
    val canArchive = tt.worthArchiving()
    val curTitle = remember(tt) { tt.termTitle() }
    // 这学期已经上完了，再导入多半是下学期的 —— 默认就选"新学期"
    val ended = remember(tt) { tt.info().last?.let { it < LocalDate.now() } ?: false }
    var asNew by remember { mutableStateOf(canArchive && (presetNew || ended)) }
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

    Sheet(pal, if (asNew) "导入新学期" else "导入课表", onClose) {
        Column {
            if (canArchive) {
                Label(pal, "导入到")
                ChoiceRow(
                    pal, on = asNew, title = "新学期",
                    desc = "「$curTitle」存进历史，随时能切回来"
                ) { asNew = true }
                Spacer(Modifier.height(Dim.s))
                ChoiceRow(
                    pal, on = !asNew, title = "替换当前学期",
                    desc = "重新导入同一学期用，手动加的课和调休保留"
                ) { asNew = false }
                Spacer(Modifier.height(20.dp))
            }

            Card(pal, color = pal.panel2) {
                Column(Modifier.padding(Dim.l)) {
                    Text("从教务系统导入", color = pal.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(Dim.xs))
                    Hint(pal, "在内置浏览器里自己登录，点到课表页就会自动识别。验证码、统一认证都由你本人处理。")
                    Spacer(Modifier.height(Dim.m))
                    OutlinedTextField(
                        value = jwxtUrl, onValueChange = { jwxtUrl = it },
                        placeholder = { Text("jwxt.xxx.edu.cn", fontSize = Fs.body) },
                        singleLine = true, shape = FieldShape, modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))
                    Hint(pal, "填电脑上打开教务系统时地址栏里那一串，不是 App 的名字。")
                    Spacer(Modifier.height(Dim.m))
                    PrimaryButton(
                        pal, "打开并导入", enabled = jwxtUrl.isNotBlank(), modifier = Modifier.fillMaxWidth()
                    ) {
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
                                    // 新学期的开学日期还不知道，得问"今天第几周"
                                    .putExtra(WebImportActivity.EXTRA_HAS_TERM, !asNew && tt.termStartEpochDay != null)
                                    .putExtra(WebImportActivity.EXTRA_NEW_TERM, asNew)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(Dim.m))
            Card(pal, color = pal.panel2) {
                Column(Modifier.padding(Dim.l)) {
                    Text("从文件导入", color = pal.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(Dim.xs))
                    Hint(
                        pal,
                        "学校发的总课表：Excel、CSV、截图或 PDF，要有「星期一…星期五」表头。" +
                            "图片和 PDF 靠识别，导入前务必核对。"
                    )
                    Spacer(Modifier.height(Dim.m))
                    OutlineChip(pal, "选择文件", modifier = Modifier.fillMaxWidth().heightIn(min = Dim.touch)) {
                        picker.launch(
                            arrayOf(
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                "text/csv", "text/comma-separated-values", "text/plain",
                                "application/vnd.ms-excel", "application/pdf", "image/*", "*/*"
                            )
                        )
                    }
                }
            }

            msg?.let {
                Spacer(Modifier.height(Dim.m))
                MsgBox(pal, it, err)
            }
        }
    }

    picked?.let { uri ->
        FileImportDialog(
            pal = pal, tt = tt, uri = uri, newTerm = asNew,
            onClose = { picked = null },
            onImported = { onImported(it) }
        )
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
    val date = s.start.toLocalDate()
    val onBlock = blockText(hue, pal.dark)

    Sheet(
        pal, "课程详情", onClose,
        footer = { PrimaryButton(pal, "编辑这一节", modifier = Modifier.fillMaxWidth(), onClick = onEdit) }
    ) {
        Column {
            // 头部用课程自己的颜色，和课表上那一块对得上
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Dim.rCard))
                    .background(blockFill(hue, pal.dark))
                    .padding(Dim.l)
            ) {
                Text(s.title, color = onBlock, fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 26.sp)
                Spacer(Modifier.height(Dim.s))
                Text(
                    "${date.md()} ${date.abbr()} · ${s.start.hhmm()}–${s.end.hhmm()}",
                    color = onBlock.copy(alpha = 0.85f), fontSize = Fs.body, style = NumStyle
                )
                Text(
                    "第 ${d.weekOf(date)} 周 · ${(s.end - s.start) / 60000} 分钟",
                    color = onBlock.copy(alpha = 0.7f), fontSize = 13.sp, style = NumStyle
                )
            }

            Spacer(Modifier.height(20.dp))
            Label(pal, if (arrangements.size > 1) "上课安排 · ${arrangements.size} 种" else "上课安排")
            if (arrangements.size > 1) {
                Hint(pal, "这门课不止一种安排，时间和地点是一一对应的。")
                Spacer(Modifier.height(Dim.s))
            }
            arrangements.forEach { a ->
                ArrangementCard(pal, d, a, hue, current = a === mine)
                Spacer(Modifier.height(Dim.s))
            }

            Spacer(Modifier.height(Dim.s))
            Card(pal, color = pal.panel2) {
                Column(Modifier.padding(horizontal = Dim.l, vertical = Dim.xs)) {
                    DetailRow(pal, "周次", d.weekRangeOf(s.title).ifBlank { "—" } + " 周")
                    DetailRow(pal, "总节数", "${same.size} 次")
                    if (s.note.isNotBlank()) DetailRow(pal, "备注", s.note.trim())
                }
            }
        }
    }
}

/** 一种上课安排：星期、时段、地点、教师、周次全在一块儿，不拆开。 */
@Composable
private fun ArrangementCard(
    pal: Palette, d: Derived, a: Arrangement, hue: Float, current: Boolean
) {
    val periodText = periodLabel(d.tt.periods, a.startMin, a.endMin)
    val edge = blockEdge(hue, pal.dark)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(pal.panel2)
            .drawBehind { drawRect(edge, size = Size(4.dp.toPx(), size.height)) }
            .then(
                if (current) Modifier.border(1.dp, edge, RoundedCornerShape(12.dp))
                else Modifier
            )
    ) {
        Column(Modifier.weight(1f).padding(start = 18.dp, end = 14.dp, top = Dim.m, bottom = Dim.m)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${DAY_ABBR[a.weekday % 7]} ${a.startMin.hhmm()}–${a.endMin.hhmm()}",
                    color = pal.ink, fontSize = Fs.body, fontWeight = FontWeight.SemiBold,
                    style = NumStyle, maxLines = 1
                )
                if (periodText.isNotBlank()) {
                    Spacer(Modifier.width(Dim.s))
                    Text(periodText, color = pal.muted, fontSize = Fs.caption, maxLines = 1)
                }
                Spacer(Modifier.weight(1f))
                if (current) Tag(pal, "本次", pal.ink)
            }
            Spacer(Modifier.height(Dim.xs))
            Text(
                a.location.ifBlank { "未标注地点" },
                color = if (a.location.isBlank()) pal.faint else pal.ink2,
                fontSize = Fs.body, maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                listOfNotNull(
                    compressWeeks(a.weeks).ifBlank { null }?.let { "第 $it 周" },
                    a.teacher.ifBlank { null },
                    "${a.count} 次"
                ).joinToString(" · "),
                color = pal.muted, fontSize = Fs.caption, maxLines = 2
            )
        }
    }
}
