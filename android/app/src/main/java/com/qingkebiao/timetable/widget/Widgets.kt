package com.qingkebiao.timetable.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.qingkebiao.timetable.Derived
import com.qingkebiao.timetable.MainActivity
import com.qingkebiao.timetable.Session
import com.qingkebiao.timetable.Store
import com.qingkebiao.timetable.Timetable
import com.qingkebiao.timetable.abbr
import com.qingkebiao.timetable.blockEdge
import com.qingkebiao.timetable.courseHues
import com.qingkebiao.timetable.hhmm
import com.qingkebiao.timetable.toLocalDate
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/* ColorProvider 自己处理深浅色，所以这里给每个角色一对颜色就行，
   不用在 Compose 里判断当前是不是暗色模式。 */
private val cBg = ColorProvider(day = Color(0xFFFAFBFA), night = Color(0xFF171B1A))
private val cInk = ColorProvider(day = Color(0xFF111614), night = Color(0xFFE4E8E6))
private val cInk2 = ColorProvider(day = Color(0xFF3C4643), night = Color(0xFFC3CAC7))
private val cMuted = ColorProvider(day = Color(0xFF69736F), night = Color(0xFF8B9490))
private val cFaint = ColorProvider(day = Color(0xFF96A09C), night = Color(0xFF646D6A))
private val cSignal = ColorProvider(day = Color(0xFFE03A2F), night = Color(0xFFFF5A4D))
private val cPaper = ColorProvider(day = Color(0xFFEBEEEC), night = Color(0xFF101312))

private fun hueProvider(hue: Float) =
    ColorProvider(day = blockEdge(hue, false), night = blockEdge(hue, true))

private fun md(d: LocalDate) = "%02d-%02d".format(d.monthValue, d.dayOfMonth)

/* ------------------------------------------------------------ 今日课表 */

class TodayWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // 小组件和 App 读的是同一个 JSON 文件，不需要跨进程同步
        val tt = Store.load(context)
        provideContent { TodayContent(tt) }
    }
}

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()
}

@Composable
private fun TodayContent(tt: Timetable) {
    val d = Derived(tt)
    val today = LocalDate.now()
    val items = d.sessionsOn(today)
    val now = System.currentTimeMillis()
    val hues = courseHues(tt.sessions.map { it.title })

    Column(
        GlanceModifier
            .fillMaxSize()
            .background(cBg)
            .cornerRadius(16.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .clickable(actionStartActivity<MainActivity>())
    ) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
            Text(
                "${today.abbr()} ${md(today)}",
                style = TextStyle(color = cInk, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            )
            Spacer(GlanceModifier.defaultWeight())
            Text(
                if (d.weeks > 0) "第 ${d.weekOf(today)} 周 · ${items.size} 节" else "未导入",
                style = TextStyle(color = cFaint, fontSize = 10.sp)
            )
        }

        Spacer(GlanceModifier.height(7.dp))

        when {
            tt.sessions.isEmpty() -> Text(
                "点一下导入课表",
                style = TextStyle(color = cMuted, fontSize = 13.sp)
            )

            items.isEmpty() -> Text(
                "今天没课",
                style = TextStyle(color = cInk2, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            )

            else -> LazyColumn {
                items(items) { s -> TodayRow(s, hues[s.title] ?: 0f, now) }
            }
        }
    }
}

@Composable
private fun TodayRow(s: Session, hue: Float, now: Long) {
    val live = now in s.start until s.end
    val past = s.end < now

    Row(
        GlanceModifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.Vertical.CenterVertically
    ) {
        // 进行中的那节课用信号红的竖条顶掉课程色，一眼能找到
        Box(
            GlanceModifier
                .width(3.dp)
                .height(28.dp)
                .cornerRadius(2.dp)
                .background(if (live) cSignal else hueProvider(hue))
        ) {}

        Spacer(GlanceModifier.width(8.dp))

        Column(GlanceModifier.defaultWeight()) {
            Text(
                s.title,
                maxLines = 1,
                style = TextStyle(
                    color = if (past) cFaint else cInk,
                    fontSize = 13.sp,
                    fontWeight = if (live) FontWeight.Bold else FontWeight.Medium
                )
            )
            if (s.location.isNotBlank()) {
                Text(
                    s.location,
                    maxLines = 1,
                    style = TextStyle(color = if (past) cFaint else cMuted, fontSize = 10.sp)
                )
            }
        }

        Spacer(GlanceModifier.width(6.dp))

        Column {
            Text(
                s.start.hhmm(),
                style = TextStyle(
                    color = if (live) cSignal else if (past) cFaint else cInk2,
                    fontSize = 11.sp,
                    fontWeight = if (live) FontWeight.Bold else FontWeight.Normal
                )
            )
            Text(
                s.end.hhmm(),
                style = TextStyle(color = cFaint, fontSize = 10.sp)
            )
        }
    }
}

/* ------------------------------------------------------------ 下一节课 */

class NextWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val tt = Store.load(context)
        provideContent { NextContent(tt) }
    }
}

class NextWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NextWidget()
}

@Composable
private fun NextContent(tt: Timetable) {
    val d = Derived(tt)
    val now = System.currentTimeMillis()
    val live = d.current(now)
    val next = if (live == null) d.next(now) else null
    val hues = courseHues(tt.sessions.map { it.title })
    val shown = live ?: next
    val hue = shown?.let { hues[it.title] } ?: 0f

    Row(
        GlanceModifier
            .fillMaxSize()
            .background(cBg)
            .cornerRadius(16.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .clickable(actionStartActivity<MainActivity>()),
        verticalAlignment = Alignment.Vertical.CenterVertically
    ) {
        Box(
            GlanceModifier
                .width(3.dp)
                .height(34.dp)
                .cornerRadius(2.dp)
                .background(if (live != null) cSignal else hueProvider(hue))
        ) {}

        Spacer(GlanceModifier.width(9.dp))

        Column(GlanceModifier.defaultWeight()) {
            when {
                tt.sessions.isEmpty() -> Text(
                    "点一下导入课表",
                    style = TextStyle(color = cMuted, fontSize = 13.sp)
                )

                live != null -> {
                    Text(
                        live.title,
                        maxLines = 1,
                        style = TextStyle(color = cInk, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    )
                    Text(
                        "进行中 · 还有 ${(live.end - now) / 60000} 分钟",
                        maxLines = 1,
                        style = TextStyle(color = cSignal, fontSize = 11.sp)
                    )
                }

                next != null -> {
                    Text(
                        next.title,
                        maxLines = 1,
                        style = TextStyle(color = cInk, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    )
                    val mins = (next.start - now) / 60000
                    val nd = next.start.toLocalDate()
                    val today = LocalDate.now()
                    val whenText = when {
                        mins < 60 -> "$mins 分钟后"
                        nd == today -> "今天 ${next.start.hhmm()}"
                        nd == today.plusDays(1) -> "明天 ${next.start.hhmm()}"
                        else -> "${md(nd)} ${next.start.hhmm()}"
                    }
                    Text(
                        listOf(whenText, next.location).filter { it.isNotBlank() }.joinToString(" · "),
                        maxLines = 1,
                        style = TextStyle(color = cMuted, fontSize = 11.sp)
                    )
                }

                else -> {
                    Text(
                        "课表已结束",
                        maxLines = 1,
                        style = TextStyle(color = cInk2, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    )
                    Text(
                        "没有更多安排",
                        style = TextStyle(color = cFaint, fontSize = 11.sp)
                    )
                }
            }
        }
    }
}

/* ------------------------------------------------------------ 刷新 */

/** 导入课表、改学期设置之后立刻调一次，让桌面跟着变。 */
suspend fun refreshWidgets(context: Context) {
    runCatching {
        TodayWidget().updateAll(context)
        NextWidget().updateAll(context)
    }
}

/**
 * appwidget-provider 里的 updatePeriodMillis 最小只能到 30 分钟，
 * 而"进行中""还有几分钟"需要更勤一点，所以再挂一个 15 分钟的 WorkManager 任务。
 * 15 分钟是 WorkManager 周期任务的下限，再短系统也不会照做。
 */
fun scheduleWidgetRefresh(context: Context) {
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        "qkb-widget-refresh",
        ExistingPeriodicWorkPolicy.KEEP,
        PeriodicWorkRequestBuilder<WidgetRefreshWorker>(15, TimeUnit.MINUTES).build()
    )
}

class WidgetRefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        refreshWidgets(applicationContext)
        return Result.success()
    }
}
