package com.qingkebiao.timetable

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate

/**
 * 设置页。
 *
 * 以前是一个弹窗从头排到尾十几段，找个开关要翻好几屏。
 * 现在首页只放一张张分组卡片，每行一句话说清现状；细节点进去在子页面里。
 * 开关、缩放这种一下就改完的直接放在首页那一行上，不用点进去。
 */
internal enum class SPage(val title: String) {
    Root("设置"),
    Terms("学期"),
    Term("开学日期与周数"),
    Overrides("调休"),
    Remind("上课提醒"),
    Sync("查调课与同步"),
    Courses("课程"),
    Data("数据与隐私"),
    Crash("崩溃记录")
}

@Composable
fun SettingsPage(
    pal: Palette, d: Derived, hues: Map<String, Float>, hourDp: Dp,
    onHourDp: (Dp) -> Unit, onClose: () -> Unit,
    onApply: (Timetable) -> Unit, onEditOverride: (LocalDate) -> Unit,
    onOpenUpdate: () -> Unit,
    /** 参数：是否作为新学期导入 */
    onImport: (Boolean) -> Unit,
    /** 切换学期、新建空白学期之后，当前课表整份换掉（已经落盘了，不用再存） */
    onReplace: (Timetable) -> Unit,
    hasUpdate: Boolean,
    initialPage: SPage = SPage.Root
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf(initialPage) }

    // 历史学期列表。切换、改名、删除之后 termsTick++ 重读一遍
    var termsTick by remember { mutableIntStateOf(0) }
    var terms by remember { mutableStateOf<List<Timetable>>(emptyList()) }
    LaunchedEffect(termsTick, d.tt.termId) { terms = Store.listTerms(ctx) }
    var pickedTerm by remember { mutableStateOf<Timetable?>(null) }
    var confirmDeleteTerm by remember { mutableStateOf(false) }
    var confirmBlank by remember { mutableStateOf(false) }
    /** 正在改名的学期：first = 学期 id（null = 当前学期），second = 原名 */
    var renaming by remember { mutableStateOf<Pair<String?, String>?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    var showPeriods by remember { mutableStateOf(false) }
    // 会覆盖/清掉数据的按钮一律两步，误触一下不至于把整张课表没了
    var confirmResync by remember { mutableStateOf(false) }
    var confirmRefetch by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }

    fun back() {
        if (page != SPage.Root) page = SPage.Root else onClose()
    }
    BackHandler { back() }

    // 权限状态是系统里的，Compose 感知不到变化；从系统设置页回来后靠这个刷一下
    var permTick by remember { mutableIntStateOf(0) }
    val notifPerm = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { permTick++ }
    // 点"去开通知"那一刻就 permTick++ 是没用的 —— 那时用户还没去开，
    // 重算出来仍然是"没开"，等开完回来页面也不会再算，红字就一直挂着。
    // 所以每次页面回到前台都重查一遍。
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { permTick++ }

    val backupExport = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) scope.launch {
            runCatching { Store.exportTo(ctx, uri) }.fold(
                onSuccess = { (n, t) ->
                    msg = "已导出备份：当前学期 $n 节课" + (if (t > 0) "，另有 $t 个历史学期" else "") +
                        "。换手机或重装后用「从备份恢复」读回来。"
                },
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

    val crashExport = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.openOutputStream(uri)?.use {
                    it.write(Crash.readAll(ctx).toByteArray())
                }
            }.fold(
                onSuccess = { msg = "已导出崩溃日志，发我就行。" },
                onFailure = { msg = "导出失败：${it.message}" }
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

    val canPost = remember(permTick) { Reminders.canPost(ctx) }
    val canExact = remember(permTick) { Reminders.canExact(ctx) }
    val crashes = remember(permTick) { Crash.list(ctx) }

    fun toggleRemind() {
        val turningOn = !d.tt.remindEnabled
        if (turningOn && android.os.Build.VERSION.SDK_INT >= 33) {
            notifPerm.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        onApply(d.tt.copy(remindEnabled = turningOn))
        permTick++
    }

    fun openResync() {
        ctx.startActivity(
            Intent(ctx, WebImportActivity::class.java)
                .putExtra(WebImportActivity.EXTRA_URL, d.tt.jwxtPage)
                .putExtra(WebImportActivity.EXTRA_HOME, d.tt.jwxtHome)
                .putExtra(WebImportActivity.EXTRA_HAS_TERM, true)
        )
    }

    // 这一层同时承担了首页的背景，点穿不到下面去
    Box(
        Modifier
            .fillMaxSize()
            .background(pal.paper)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {}
    ) {
        Column(
            Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(start = Dim.xs, end = Dim.l, top = Dim.xs, bottom = Dim.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconBtn(pal, Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = pal.ink) { back() }
                Spacer(Modifier.width(Dim.xs))
                Text(page.title, color = pal.ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }

            AnimatedContent(
                targetState = page,
                transitionSpec = {
                    val deeper = targetState != SPage.Root
                    (slideInHorizontally(tween(240)) { w -> if (deeper) w / 4 else -w / 4 } + fadeIn(tween(200)))
                        .togetherWith(
                            slideOutHorizontally(tween(200)) { w -> if (deeper) -w / 4 else w / 4 } +
                                fadeOut(tween(150))
                        )
                },
                label = "settings"
            ) { p ->
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                        .padding(start = Dim.l, end = Dim.l, top = Dim.s, bottom = 40.dp)
                ) {
                    when (p) {
                        SPage.Root -> {
                            SettingsGroup(pal, null) {
                                SettingItem(
                                    pal, "导入课表", "从教务系统网页或文件导入",
                                    onClick = { onImport(false) }
                                )
                            }

                            SettingsGroup(pal, "课表") {
                                SettingItem(
                                    pal, "学期",
                                    d.tt.termTitle() + if (terms.isNotEmpty()) " · 历史 ${terms.size} 个" else "",
                                    onClick = { page = SPage.Terms }
                                )
                                SettingItem(
                                    pal, "开学日期与周数",
                                    "第 1 周从 ${d.termStart.monthValue}月${d.termStart.dayOfMonth}日 开始 · 共 ${d.weeks} 周",
                                    onClick = { page = SPage.Term }
                                )
                                SettingItem(
                                    pal, "作息时间",
                                    "第 1 节 ${d.tt.periods.firstOrNull()?.startMin?.hhmm() ?: "—"} 起 · 共 ${d.tt.periods.size} 节",
                                    onClick = { showPeriods = true }
                                )
                                SettingItem(
                                    pal, "调休",
                                    if (d.tt.overrides.isEmpty()) "放假、补课" else "${d.tt.overrides.size} 条记录",
                                    onClick = { page = SPage.Overrides }
                                )
                                SettingItem(
                                    pal, "课程", "${hues.size} 门",
                                    onClick = { page = SPage.Courses }
                                )
                            }

                            SettingsGroup(pal, "提醒") {
                                val permMissing = d.tt.remindEnabled && (!canPost || !canExact)
                                SettingItem(
                                    pal, "上课提醒",
                                    when {
                                        !d.tt.remindEnabled -> "已关闭"
                                        permMissing -> "权限没给全，可能不响"
                                        else -> "上课前 ${d.tt.remindMinutes} 分钟"
                                    },
                                    badge = permMissing,
                                    onClick = { page = SPage.Remind }
                                ) { QSwitch(pal, d.tt.remindEnabled) { toggleRemind() } }
                            }

                            SettingsGroup(pal, "显示") {
                                SettingItem(pal, "显示周六周日") {
                                    QSwitch(pal, d.tt.showWeekend) {
                                        onApply(d.tt.copy(showWeekend = it))
                                    }
                                }
                                SettingItem(pal, "周视图行高", "每小时 ${hourDp.value.toInt()} dp") {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        StepButton(pal, "−", hourDp > 36.dp) {
                                            onHourDp((hourDp - 10.dp).coerceAtLeast(36.dp))
                                        }
                                        Spacer(Modifier.width(Dim.s))
                                        StepButton(pal, "+", hourDp < 140.dp) {
                                            onHourDp((hourDp + 10.dp).coerceAtMost(140.dp))
                                        }
                                    }
                                }
                            }

                            if (d.tt.jwxtPage.isNotBlank() || d.tt.icsUrl.isNotBlank()) {
                                SettingsGroup(pal, "同步") {
                                    if (d.tt.jwxtPage.isNotBlank()) {
                                        SettingItem(
                                            pal, "查调课",
                                            "上次对过：" + (d.tt.lastSyncEpochDay?.let {
                                                val n = LocalDate.now().toEpochDay() - it
                                                if (n <= 0) "今天" else "$n 天前"
                                            } ?: "还没对过"),
                                            onClick = { page = SPage.Sync }
                                        )
                                    }
                                    if (d.tt.icsUrl.isNotBlank()) {
                                        SettingItem(
                                            pal, "订阅链接", d.tt.icsUrl,
                                            onClick = { page = SPage.Sync }
                                        )
                                    }
                                }
                            }

                            SettingsGroup(pal, "数据") {
                                SettingItem(
                                    pal, "数据与隐私", "备份、恢复、退出教务系统登录、清空",
                                    badge = corrupt.isNotEmpty(),
                                    onClick = { page = SPage.Data }
                                )
                                if (crashes.isNotEmpty()) {
                                    SettingItem(
                                        pal, "崩溃记录", "${crashes.size} 条，导出发给开发者",
                                        badge = true,
                                        onClick = { page = SPage.Crash }
                                    )
                                }
                            }

                            SettingsGroup(pal, "关于") {
                                SettingItem(
                                    pal,
                                    if (appVersion.isNotBlank()) "清课表 v$appVersion" else "清课表",
                                    when {
                                        hasUpdate -> "有新版可以更新"
                                        d.tt.updateUrl.isBlank() -> "已关闭自动检查更新"
                                        else -> "检查更新"
                                    },
                                    badge = hasUpdate,
                                    onClick = onOpenUpdate
                                )
                            }
                        }

                        SPage.Term -> TermSettings(pal, d, applyAndRecompute)

                        SPage.Terms -> {
                            val curTitle = d.tt.termTitle()
                            SettingsGroup(pal, "当前学期") {
                                SettingItem(pal, curTitle, termRange(d.tt.info())) {
                                    TextBtn(pal, "改名") { renaming = null to curTitle }
                                }
                            }

                            PrimaryButton(pal, "导入新学期的课表", modifier = Modifier.fillMaxWidth()) {
                                onImport(true)
                            }
                            Spacer(Modifier.height(Dim.s))
                            Hint(
                                pal,
                                if (d.tt.worthArchiving())
                                    "导入成功后，「$curTitle」会自动存进下面的历史，随时能切回来。"
                                else "现在没有课表，导入的就是当前学期。"
                            )
                            Spacer(Modifier.height(Dim.m))
                            ConfirmAction(
                                pal,
                                confirming = confirmBlank,
                                label = "新建空白学期",
                                confirmText = "「$curTitle」存进历史，换上一份空白课表，之后自己一节一节加。" +
                                    "作息表、提醒这些设置保留。",
                                confirmLabel = "确认新建",
                                primary = false,
                                onAsk = { confirmBlank = true },
                                onCancel = { confirmBlank = false },
                                onConfirm = {
                                    confirmBlank = false
                                    scope.launch {
                                        runCatching { Store.startBlankTerm(ctx) }.fold(
                                            onSuccess = { onReplace(it); termsTick++; msg = "已新建空白学期。" },
                                            onFailure = { msg = "新建失败：${it.message}" }
                                        )
                                    }
                                }
                            )

                            Spacer(Modifier.height(Dim.xl))
                            SettingsGroup(pal, "历史学期") {
                                if (terms.isEmpty()) {
                                    SettingItem(pal, "还没有历史学期", "导入新学期时，当前这份会自动存进来")
                                } else {
                                    terms.forEach { t ->
                                        SettingItem(
                                            pal, t.termTitle(), termRange(t.info()),
                                            onClick = { confirmDeleteTerm = false; pickedTerm = t }
                                        )
                                    }
                                }
                            }
                        }

                        SPage.Overrides -> {
                            Hint(pal, "某天放假，或者某天按另一天的课上。也可以在「今日」视图里直接改当天。")
                            Spacer(Modifier.height(Dim.m))
                            if (d.tt.overrides.isNotEmpty()) {
                                SettingsGroup(pal, null) {
                                    d.tt.overrides.sortedBy { it.dateEpochDay }.forEach { ov ->
                                        val date = LocalDate.ofEpochDay(ov.dateEpochDay)
                                        SettingItem(
                                            pal,
                                            "${date.monthValue}月${date.dayOfMonth}日 ${date.abbr()}",
                                            when (ov.kind) {
                                                OverrideKind.HOLIDAY -> "放假"
                                                OverrideKind.FOLLOW -> {
                                                    val s = ov.followEpochDay?.let { LocalDate.ofEpochDay(it) }
                                                    "上 ${s?.monthValue}月${s?.dayOfMonth}日 ${s?.abbr() ?: ""} 的课"
                                                }
                                            },
                                            onClick = { onEditOverride(date) }
                                        )
                                    }
                                }
                            }
                            PrimaryButton(pal, "添加调休", modifier = Modifier.fillMaxWidth()) {
                                onEditOverride(LocalDate.now())
                            }
                            Spacer(Modifier.height(Dim.s))
                            Hint(pal, "可以选任意一天，不限于今天 —— 调休通知一般提前发。")
                        }

                        SPage.Remind -> {
                            SettingsGroup(pal, null) {
                                SettingItem(pal, "开启提醒", "调休算数：放假那天不响，调过来的课按新日期响") {
                                    QSwitch(pal, d.tt.remindEnabled) { toggleRemind() }
                                }
                                if (d.tt.remindEnabled) {
                                    SettingItem(pal, "提前多久") {
                                        IntStepper(pal, d.tt.remindMinutes, 0, 120, 5, " 分") {
                                            onApply(d.tt.copy(remindMinutes = it))
                                        }
                                    }
                                }
                            }

                            if (d.tt.remindEnabled) {
                                // 权限状态。这两样任何一个没给，提醒就是哑的 —— 必须说出来，
                                // 不然用户开了开关以为好了，等漏了课才发现
                                if (!canPost) {
                                    PermWarn(pal, "系统里这个 App 的通知是关的，提醒发不出来。", "去开通知") {
                                        Reminders.openNotificationSettings(ctx); permTick++
                                    }
                                }
                                if (!canExact) {
                                    PermWarn(
                                        pal,
                                        "没有「闹钟和提醒」权限，系统可能把提醒推迟十几分钟才发。",
                                        "去开精确闹钟"
                                    ) { Reminders.openExactAlarmSettings(ctx); permTick++ }
                                }

                                val next3 = remember(d, d.tt.remindMinutes) {
                                    d.upcoming(System.currentTimeMillis(), 3)
                                }
                                SettingsGroup(pal, "接下来会响的") {
                                    if (next3.isEmpty()) {
                                        SettingItem(pal, "接下来没有课", "暂时没有要提醒的")
                                    } else {
                                        next3.forEach { s ->
                                            SettingItem(
                                                pal,
                                                (s.start - d.tt.remindMinutes * 60_000L).hhmm() + " 提醒",
                                                "${s.title} · ${s.start.toLocalDate().abbr()} ${s.start.hhmm()} 上课"
                                            )
                                        }
                                    }
                                }
                                OutlineChip(pal, "发一条试试", modifier = Modifier.fillMaxWidth()) {
                                    Reminders.testNotify(ctx, d.tt.remindMinutes); permTick++
                                }
                                Spacer(Modifier.height(Dim.m))
                                Hint(pal, "小米等机型还要把本应用加进「自启动」和省电白名单，否则后台被杀，提醒就不响了。")
                            }
                        }

                        SPage.Sync -> {
                            if (d.tt.jwxtPage.isNotBlank()) {
                                SettingsGroup(pal, "查调课") {
                                    SettingItem(
                                        pal, "上次对过",
                                        d.tt.lastSyncEpochDay?.let {
                                            val n = LocalDate.now().toEpochDay() - it
                                            if (n <= 0) "今天" else "$n 天前"
                                        } ?: "还没对过"
                                    )
                                    SettingItem(
                                        pal, "隔几天提醒",
                                        if (d.tt.syncRemindDays <= 0) "不提醒" else "首页会出一条提示"
                                    ) {
                                        IntStepper(pal, d.tt.syncRemindDays, 0, 30, suffix = " 天") {
                                            onApply(d.tt.copy(syncRemindDays = it))
                                        }
                                    }
                                }
                                Hint(
                                    pal,
                                    "重新打开出课表的那一页，和现在这份比一遍，列出学校改过的地方，你确认了才写进去。" +
                                        "作息、开学日期、手动加的课和调休都不会动。"
                                )
                                Spacer(Modifier.height(Dim.m))
                                ConfirmAction(
                                    pal,
                                    confirming = confirmResync,
                                    label = "去查一下有没有调课",
                                    confirmText = "会打开教务系统那一页重新抓一次。抓完先把变动列给你看，" +
                                        "你点了「应用」才会改课表；直接关掉的话什么都不变。",
                                    confirmLabel = "确认，去查",
                                    onAsk = { confirmResync = true },
                                    onCancel = { confirmResync = false },
                                    onConfirm = { confirmResync = false; openResync() }
                                )
                                Spacer(Modifier.height(Dim.xl))
                            }

                            if (d.tt.icsUrl.isNotBlank()) {
                                Label(pal, "订阅链接")
                                Text(
                                    d.tt.icsUrl, color = pal.muted, fontSize = Fs.caption,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(Dim.m))
                                ConfirmAction(
                                    pal,
                                    confirming = confirmRefetch,
                                    label = "重新拉取课表",
                                    confirmText = "会用订阅链接上的内容覆盖现在这份，手动添加的条目保留。",
                                    confirmLabel = "确认拉取",
                                    onAsk = { confirmRefetch = true },
                                    onCancel = { confirmRefetch = false },
                                    onConfirm = {
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
                                )
                            }
                        }

                        SPage.Courses -> {
                            SettingsGroup(pal, null) {
                                hues.keys.forEach { name ->
                                    Row(
                                        Modifier.fillMaxWidth().padding(horizontal = Dim.l, vertical = 14.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            Modifier.size(12.dp).background(
                                                blockEdge(hues[name] ?: 0f, pal.dark), CircleShape
                                            )
                                        )
                                        Spacer(Modifier.width(Dim.m))
                                        Text(
                                            name, color = pal.ink, fontSize = 15.sp,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }

                        SPage.Data -> {
                            Label(pal, "备份")
                            Hint(
                                pal,
                                "课表只存在这台手机上，不上传。换手机、卸载重装之前先导出一份。"
                            )
                            Spacer(Modifier.height(Dim.m))
                            Row {
                                PrimaryButton(pal, "导出备份", modifier = Modifier.weight(1f)) {
                                    backupExport.launch(Store.backupName())
                                }
                                Spacer(Modifier.width(Dim.s))
                                OutlineChip(
                                    pal, "从备份恢复",
                                    modifier = Modifier.weight(1f).height(Dim.touch)
                                ) { backupImport.launch(arrayOf("application/json", "*/*")) }
                            }

                            if (corrupt.isNotEmpty()) {
                                Spacer(Modifier.height(Dim.l))
                                MsgBox(
                                    pal,
                                    "有 ${corrupt.size} 份读不出来的旧数据被留了下来，没有覆盖掉。" +
                                        "导出来发我，多半能把里面手动加的课和调休捞回来。",
                                    error = true
                                )
                                Spacer(Modifier.height(Dim.s))
                                SettingsGroup(pal, null) {
                                    corrupt.forEach { f ->
                                        SettingItem(
                                            pal,
                                            f.name.removePrefix("timetable.corrupt-").removeSuffix(".json"),
                                            "${f.length()} 字节"
                                        ) {
                                            Row {
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
                                }
                            }

                            Spacer(Modifier.height(Dim.xl))
                            Label(pal, "教务系统登录")
                            Hint(
                                pal,
                                "内置浏览器里的登录状态会留着，所以「查调课」不用每次重登。手机要借人或者不放心，就清掉。"
                            )
                            Spacer(Modifier.height(Dim.m))
                            ConfirmAction(
                                pal,
                                confirming = confirmLogout,
                                label = "退出教务系统登录",
                                confirmText = "清掉内置浏览器里的 Cookie 和缓存。已经导入的课表不受影响，只是下次查调课要重新登录。",
                                confirmLabel = "确认退出",
                                primary = false,
                                onAsk = { confirmLogout = true },
                                onCancel = { confirmLogout = false },
                                onConfirm = {
                                    WebSession.clear(ctx)
                                    confirmLogout = false
                                    msg = "已清除登录状态。下次导入或查调课需要重新登录。"
                                }
                            )

                            Spacer(Modifier.height(Dim.xl))
                            Label(pal, "清空")
                            if (!confirmClear) {
                                DangerChip(pal, "清空当前学期", modifier = Modifier.fillMaxWidth().height(Dim.touch)) {
                                    confirmClear = true
                                }
                            } else {
                                MsgBox(
                                    pal,
                                    "确定要清空吗？${d.tt.sessions.size} 节课、" +
                                        "${d.tt.overrides.size} 条调休记录和作息设置都会删掉，删了没法撤销。" +
                                        "历史学期不受影响。只是想换一份课表的话，直接重新导入就行，不用先清空。",
                                    error = true
                                )
                                Spacer(Modifier.height(Dim.m))
                                Row {
                                    OutlineChip(
                                        pal, "取消", modifier = Modifier.weight(1f).height(Dim.touch)
                                    ) { confirmClear = false }
                                    Spacer(Modifier.width(Dim.s))
                                    DangerChip(pal, "确认清空", modifier = Modifier.weight(1f).height(Dim.touch)) {
                                        scope.launch { Store.clear(ctx) }
                                        // 更新地址不是课表数据，清课表不该把它一起清掉
                                        onApply(Timetable(updateUrl = d.tt.updateUrl))
                                        onClose()
                                    }
                                }
                            }
                        }

                        SPage.Crash -> {
                            // 崩溃记录只在真崩过之后才出现
                            MsgBox(
                                pal,
                                "App 崩过 ${crashes.size} 次。" + (Crash.latestSummary(ctx) ?: "") +
                                    "\n导出发我，我照着修。里面只有异常堆栈和机型系统版本，没有你的课表内容。",
                                error = true
                            )
                            Spacer(Modifier.height(Dim.m))
                            Row {
                                PrimaryButton(pal, "导出崩溃日志", modifier = Modifier.weight(1f)) {
                                    crashExport.launch("qingkebiao-crash-" + LocalDate.now() + ".txt")
                                }
                                Spacer(Modifier.width(Dim.s))
                                OutlineChip(pal, "清掉", modifier = Modifier.weight(1f).height(Dim.touch)) {
                                    Crash.clear(ctx); permTick++; page = SPage.Root
                                }
                            }
                        }
                    }
                }
            }
        }

        // 操作结果浮在底部，过几秒自己消失，不占页面位置
        msg?.let { text ->
            LaunchedEffect(text) {
                delay(4500)
                msg = null
            }
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(Dim.l)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(pal.ink)
                    .clickable { msg = null }
                    .padding(horizontal = Dim.l, vertical = 14.dp)
            ) {
                Text(text, color = pal.paper, fontSize = 13.sp, lineHeight = 19.sp)
            }
        }
    }

    pickedTerm?.let { t ->
        val title = t.termTitle()
        Sheet(
            pal, title, onClose = { pickedTerm = null },
            footer = {
                PrimaryButton(pal, "切换到这个学期", modifier = Modifier.fillMaxWidth()) {
                    scope.launch {
                        runCatching { Store.switchTerm(ctx, t.termId) }.fold(
                            onSuccess = {
                                onReplace(it)
                                pickedTerm = null
                                termsTick++
                                msg = "已切换到「${it.termTitle()}」。"
                            },
                            onFailure = { pickedTerm = null; msg = "切换失败：${it.message}" }
                        )
                    }
                }
            }
        ) {
            Column {
                Text(termRange(t.info()), color = pal.ink2, fontSize = Fs.body, style = NumStyle)
                Spacer(Modifier.height(Dim.s))
                Hint(pal, "切过去之后，现在的「${d.tt.termTitle()}」会存进历史，不会丢。")
                Spacer(Modifier.height(Dim.l))
                Row {
                    OutlineChip(pal, "改名") { renaming = t.termId to title }
                    Spacer(Modifier.width(Dim.s))
                    if (!confirmDeleteTerm) {
                        DangerChip(pal, "删除") { confirmDeleteTerm = true }
                    } else {
                        DangerChip(pal, "确认删除") {
                            scope.launch {
                                Store.deleteTerm(ctx, t.termId)
                                pickedTerm = null
                                termsTick++
                                msg = "已删除「$title」。"
                            }
                        }
                    }
                }
                if (confirmDeleteTerm) {
                    Spacer(Modifier.height(Dim.m))
                    MsgBox(pal, "删掉就找不回来了。不确定的话，先到 数据与隐私 里导出一份备份。", error = true)
                }
            }
        }
    }

    renaming?.let { (id, initial) ->
        var text by remember(id, initial) { mutableStateOf(initial) }
        Sheet(
            pal, "学期名称", onClose = { renaming = null },
            footer = {
                PrimaryButton(
                    pal, "保存", enabled = text.isNotBlank(), modifier = Modifier.fillMaxWidth()
                ) {
                    val name = text.trim()
                    if (id == null) {
                        onApply(d.tt.copy(termName = name))
                    } else {
                        scope.launch {
                            runCatching { Store.renameTerm(ctx, id, name) }
                                .onFailure { msg = "改名失败：${it.message}" }
                            termsTick++
                        }
                        pickedTerm = null
                    }
                    renaming = null
                }
            }
        ) {
            Column {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    placeholder = { Text("2025-2026 第一学期") },
                    singleLine = true, shape = FieldShape, modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

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
}

/** 权限没给时的那一块：一句话说后果 + 一个去开的按钮。 */
@Composable
private fun PermWarn(pal: Palette, text: String, action: String, onClick: () -> Unit) {
    Card(pal, color = pal.warn.copy(alpha = 0.1f)) {
        Column(Modifier.padding(Dim.l)) {
            Text(text, color = pal.warn, fontSize = 13.sp, lineHeight = 19.sp)
            Spacer(Modifier.height(Dim.m))
            OutlineChip(pal, action, onClick = onClick)
        }
    }
    Spacer(Modifier.height(Dim.m))
}

/**
 * 两步确认的按钮：先点一下展开说明，再点"确认"才真的做。
 * 会覆盖数据、会退出登录的操作都走这个。
 */
@Composable
private fun ConfirmAction(
    pal: Palette,
    confirming: Boolean,
    label: String,
    confirmText: String,
    confirmLabel: String,
    primary: Boolean = true,
    onAsk: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit
) {
    if (!confirming) {
        if (primary) PrimaryButton(pal, label, modifier = Modifier.fillMaxWidth(), onClick = onAsk)
        else OutlineChip(pal, label, modifier = Modifier.fillMaxWidth().height(Dim.touch), onClick = onAsk)
    } else {
        MsgBox(pal, confirmText)
        Spacer(Modifier.height(Dim.m))
        Row {
            OutlineChip(pal, "取消", modifier = Modifier.weight(1f).height(Dim.touch), onClick = onCancel)
            Spacer(Modifier.width(Dim.s))
            PrimaryButton(pal, confirmLabel, modifier = Modifier.weight(1f), onClick = onConfirm)
        }
    }
}

/** "2025.9.1 – 2026.1.10 · 312 节" */
private fun termRange(i: TermInfo): String {
    if (i.first == null || i.last == null) return "还没有课"
    fun f(d: LocalDate) = "${d.year}.${d.monthValue}.${d.dayOfMonth}"
    return "${f(i.first)} – ${f(i.last)} · ${i.count} 节"
}
