package com.qingkebiao.timetable

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.UUID

/** 一节课的一次具体上课。时间存 epoch 毫秒，序列化和比较都最省事。 */
@Serializable
data class Session(
    /** 稳定 id，编辑/删除靠它定位。导入时生成。 */
    val id: String = "",
    val title: String,
    val location: String = "",
    val teacher: String = "",
    val note: String = "",
    val start: Long,
    val end: Long,
    val allDay: Boolean = false,
    /** 手动添加或手动改过的。重新导入课表时这些会被保留，不会被覆盖掉。 */
    val manual: Boolean = false
)

fun newId(): String = UUID.randomUUID().toString()

/** 调休：某一天不按它本来的星期上课。 */
@Serializable
enum class OverrideKind {
    /** 这天放假，原本的课全部不上（手动添加的事件仍然显示）。 */
    HOLIDAY,
    /** 这天按另一个日期的课表上，比如"周六上周一的课"。 */
    FOLLOW
}

@Serializable
data class DayOverride(
    val dateEpochDay: Long,
    val kind: OverrideKind,
    /** kind = FOLLOW 时，照搬哪一天的课。 */
    val followEpochDay: Long? = null,
    val note: String = ""
)

/**
 * 内置的更新地址。
 *
 * 装上就能查更新，不用每个人自己去填一遍 —— 这个 App 是发给同学装的，
 * 让每个人手打一个网址，那"应用内更新"这件事基本就废了。
 * 设置里仍然可以改成别的地址，或者清空彻底关掉检查。
 */
const val DEFAULT_UPDATE_URL = "qingkebiao.pages.dev"

/** 节次 → 时间。教务系统网页只给"第几节"，换算成具体时间要靠这张表。 */
@Serializable
data class PeriodSlot(val index: Int, val startMin: Int, val endMin: Int)

/** 国内高校比较通行的一套作息，导入网页课表时作为默认值，可以在设置里改。 */
val DEFAULT_PERIODS: List<PeriodSlot> = listOf(
    PeriodSlot(1, 8 * 60, 8 * 60 + 45),
    PeriodSlot(2, 8 * 60 + 50, 9 * 60 + 35),
    PeriodSlot(3, 9 * 60 + 55, 10 * 60 + 40),
    PeriodSlot(4, 10 * 60 + 45, 11 * 60 + 30),
    PeriodSlot(5, 11 * 60 + 35, 12 * 60 + 20),
    PeriodSlot(6, 14 * 60, 14 * 60 + 45),
    PeriodSlot(7, 14 * 60 + 50, 15 * 60 + 35),
    PeriodSlot(8, 15 * 60 + 55, 16 * 60 + 40),
    PeriodSlot(9, 16 * 60 + 45, 17 * 60 + 30),
    PeriodSlot(10, 18 * 60 + 30, 19 * 60 + 15),
    PeriodSlot(11, 19 * 60 + 20, 20 * 60 + 5),
    PeriodSlot(12, 20 * 60 + 10, 20 * 60 + 55)
)

@Serializable
data class Timetable(
    val sessions: List<Session> = emptyList(),
    /** 第 1 周的周一。null = 自动取最早一节课所在的周。 */
    val termStartEpochDay: Long? = null,
    /** 总周数。null = 按最后一节课推算。手动设定后即使课表里没课也能翻到那一周。 */
    val termWeeks: Int? = null,
    val showWeekend: Boolean = true,
    val sourceLabel: String = "",
    /** 记下来是为了下拉刷新时重新拉取。原生没有 CORS 限制。 */
    val icsUrl: String = "",
    val overrides: List<DayOverride> = emptyList(),
    val periods: List<PeriodSlot> = DEFAULT_PERIODS,
    /** 从教务系统解析出来的原始课程块。留着它，改作息表就能原地重算。 */
    val zfBlocks: List<Zf.Block> = emptyList(),
    /** 学校教务系统入口，导入框预填用。 */
    val jwxtHome: String = "",
    /** 上次真正解析成功的那一页。重新同步时直接开这一页，省掉再点一遍菜单。 */
    val jwxtPage: String = "",
    /** 上次用的是哪个解析器，显示用：zf / generic */
    val parserUsed: String = "",
    /** 作息表是怎么来的：sniffed(从网页读到) / derived(按分组推算) / manual(用户改过) */
    val periodsSource: String = "",
    /** 检查更新的站点地址。默认就是发包的那个站，清空 = 不检查，一个网络请求都不发。 */
    val updateUrl: String = DEFAULT_UPDATE_URL,
    /** 上次和教务系统对过课表的日期。用来提醒"好久没查调课了"。 */
    val lastSyncEpochDay: Long? = null,
    /** 隔几天提醒一次去查调课。0 = 不提醒。 */
    val syncRemindDays: Int = 7,
    /** 点过"稍后"的那个版本号，和点的那一天。同一版本一天只主动弹一次。 */
    val updateSnoozeCode: Int = 0,
    val updateSnoozeDay: Long = 0,
    /** 上课前提醒。默认关，开了才会申请通知权限、才会排闹钟。 */
    val remindEnabled: Boolean = false,
    /** 提前几分钟提醒。 */
    val remindMinutes: Int = 15,
    /**
     * 这份课表属于哪个学期。老数据里没有这个字段，读出来是空的，
     * 等它第一次被存进历史时再补一个 id。
     */
    val termId: String = "",
    /** 学期名，用户可以改。空 = 按开学日期自动起名，见 [autoTermName]。 */
    val termName: String = ""
)

/* ---------------------------------------------------------------- 多学期 */

/**
 * 按开学日期起个学期名。国内校历：秋季开学（8 月到次年 1 月）是第一学期，
 * 春季开学（2 到 7 月）是第二学期，学年跨两个自然年。
 */
fun autoTermName(start: LocalDate): String {
    val m = start.monthValue
    return if (m in 2..7) {
        "${start.year - 1}-${start.year} 第二学期"
    } else {
        val y = if (m == 1) start.year - 1 else start.year
        "$y-${y + 1} 第一学期"
    }
}

/** 界面上显示的学期名：用户起的名字优先，否则按开学日期自动起。 */
fun Timetable.termTitle(zone: ZoneId = ZoneId.systemDefault()): String =
    termName.ifBlank {
        if (sessions.isEmpty()) "新学期" else autoTermName(Derived(this, zone).termStart)
    }

/** 值不值得存进历史：示例课表和空课表不存。 */
fun Timetable.worthArchiving(): Boolean =
    sessions.isNotEmpty() && sourceLabel != "示例课表"

/**
 * 开一个新学期：课、调休、开学日期、教务系统课程块全部清空，
 * 但"这个人的习惯"留着 —— 同一所学校作息表不会变，教务系统网址不会变，
 * 提醒开没开、提前几分钟、更新地址这些也不该因为换学期就被重置。
 */
fun Timetable.freshTerm(): Timetable = Timetable(
    termId = newId(),
    showWeekend = showWeekend,
    periods = periods,
    periodsSource = periodsSource,
    jwxtHome = jwxtHome,
    jwxtPage = jwxtPage,
    updateUrl = updateUrl,
    syncRemindDays = syncRemindDays,
    updateSnoozeCode = updateSnoozeCode,
    updateSnoozeDay = updateSnoozeDay,
    remindEnabled = remindEnabled,
    remindMinutes = remindMinutes
)

/**
 * 从历史里切回某个学期时，把"跟人走不跟学期走"的设置从当前这份带过去。
 * 否则切一次学期，提醒就被关掉、更新地址变回旧的了。
 */
fun Timetable.withGlobalsFrom(cur: Timetable): Timetable = copy(
    updateUrl = cur.updateUrl,
    updateSnoozeCode = cur.updateSnoozeCode,
    updateSnoozeDay = cur.updateSnoozeDay,
    remindEnabled = cur.remindEnabled,
    remindMinutes = cur.remindMinutes,
    syncRemindDays = cur.syncRemindDays
)

/** 学期列表上显示的那一行：从第一节到最后一节，一共几节。 */
data class TermInfo(val first: LocalDate?, val last: LocalDate?, val count: Int)

fun Timetable.info(zone: ZoneId = ZoneId.systemDefault()): TermInfo = TermInfo(
    first = sessions.minByOrNull { it.start }?.start?.toLocalDate(zone),
    last = sessions.maxByOrNull { it.end }?.end?.toLocalDate(zone),
    count = sessions.size
)

fun Long.toLocalDateTime(zone: ZoneId = ZoneId.systemDefault()): LocalDateTime =
    Instant.ofEpochMilli(this).atZone(zone).toLocalDateTime()

fun Long.toLocalDate(zone: ZoneId = ZoneId.systemDefault()): LocalDate =
    toLocalDateTime(zone).toLocalDate()

fun LocalDate.mondayOf(): LocalDate = with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

fun LocalDate.atMinuteOfDay(min: Int, zone: ZoneId = ZoneId.systemDefault()): Long =
    atStartOfDay(zone).plusMinutes(min.toLong()).toInstant().toEpochMilli()

/** 分钟数（当天 0 点起算），用来在时间轴上定位。 */
fun Session.startMinute(zone: ZoneId = ZoneId.systemDefault()): Int =
    start.toLocalDateTime(zone).let { it.hour * 60 + it.minute }

fun Session.endMinute(zone: ZoneId = ZoneId.systemDefault()): Int {
    val e = end.toLocalDateTime(zone)
    val m = e.hour * 60 + e.minute
    return if (m <= startMinute(zone)) 24 * 60 else m
}

/** 调休用：保持上课时刻不变，把这节课整体挪到另一天。 */
fun Session.shiftToDate(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Session {
    val t = start.toLocalDateTime(zone).toLocalTime()
    val ns = LocalDateTime.of(date, t).atZone(zone).toInstant().toEpochMilli()
    return copy(id = id + "@" + date, start = ns, end = ns + (end - start))
}

/**
 * 从课表派生出来的东西：学期第一周、总周数、某天有哪些课（已应用调休）。
 * 每次数据变了就重新构造一个，不做增量维护 —— 课表这个量级完全不值得。
 */
class Derived(val tt: Timetable, val zone: ZoneId = ZoneId.systemDefault()) {

    val termStart: LocalDate = tt.termStartEpochDay?.let { LocalDate.ofEpochDay(it) }
        ?: tt.sessions.minByOrNull { it.start }?.start?.toLocalDate(zone)?.mondayOf()
        ?: LocalDate.now(zone).mondayOf()

    /** 手动设定优先；否则按最后一节课推算。 */
    val weeks: Int = tt.termWeeks
        ?: tt.sessions.maxByOrNull { it.end }?.end?.let {
            val diff = Math.floorDiv(it.toLocalDate(zone).toEpochDay() - termStart.toEpochDay(), 7L)
            maxOf(1, diff.toInt() + 1)
        } ?: 0

    val isEmpty: Boolean get() = tt.sessions.isEmpty()

    private val overrideByDay: Map<Long, DayOverride> =
        tt.overrides.associateBy { it.dateEpochDay }

    fun overrideFor(d: LocalDate): DayOverride? = overrideByDay[d.toEpochDay()]

    /** 用 floorDiv 而不是 / —— 负数除法向零取整会让开学前一周算成第 1 周。 */
    fun weekOf(d: LocalDate): Int =
        Math.floorDiv(d.toEpochDay() - termStart.toEpochDay(), 7L).toInt() + 1

    fun mondayOfWeek(w: Int): LocalDate = termStart.plusDays((w - 1) * 7L)

    fun clampWeek(w: Int): Int = w.coerceIn(1, maxOf(1, weeks))

    private fun rawOn(d: LocalDate): List<Session> =
        tt.sessions.filter { it.start.toLocalDate(zone) == d }

    /**
     * 某天实际要上的课，已应用调休规则：
     *   HOLIDAY —— 原本的课全不上，但手动加的事件（考试、会议）仍然显示
     *   FOLLOW  —— 照搬来源日的课，时刻不变，外加这天自己手动加的事件
     */
    fun sessionsOn(d: LocalDate): List<Session> {
        val ov = overrideFor(d)
        val own = rawOn(d)
        val list = when {
            ov == null -> own
            ov.kind == OverrideKind.HOLIDAY -> own.filter { it.manual }
            ov.kind == OverrideKind.FOLLOW && ov.followEpochDay != null -> {
                val src = LocalDate.ofEpochDay(ov.followEpochDay)
                rawOn(src).filter { !it.manual }.map { it.shiftToDate(d, zone) } +
                    own.filter { it.manual }
            }
            else -> own
        }
        return list.sortedBy { it.start }
    }

    fun daysOfWeek(w: Int): List<LocalDate> {
        val mon = mondayOfWeek(w)
        val n = if (tt.showWeekend) 7 else 5
        return (0 until n).map { mon.plusDays(it.toLong()) }
    }

    /**
     * "进行中""下一节"必须走 sessionsOn 才能反映调休，
     * 所以在今天前后开一个窗口把有效课程铺出来，而不是直接扫 sessions。
     */
    private fun effectiveWindow(now: Long): List<Session> {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return (-1L..WINDOW_DAYS).flatMap { sessionsOn(today.plusDays(it)) }.sortedBy { it.start }
    }

    private companion object {
        /** "进行中/下一节/接下来几节"都在今天往后这么多天的窗口里算，调休只在窗口内生效。 */
        const val WINDOW_DAYS = 21L
    }

    fun current(now: Long = System.currentTimeMillis()): Session? =
        effectiveWindow(now).firstOrNull { now in it.start until it.end }

    fun next(now: Long = System.currentTimeMillis()): Session? {
        effectiveWindow(now).firstOrNull { it.start > now }?.let { return it }
        // 窗口外（比如假期结束后才开学）兜底扫原始数据。
        //
        // 关键是只扫窗口**之外**：窗口内的已经被 sessionsOn 按调休处理过了，
        // 再无差别扫一遍会把"今天放假"取消掉的课又捞回来 —— 单测抓到的就是这个，
        // 标了放假当天，"下一节"和小组件照样显示那节不上的课。
        val end = windowEnd(now)
        return tt.sessions.filter { it.start >= end }.minByOrNull { it.start }
    }

    private fun windowEnd(now: Long): Long {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return today.plusDays(WINDOW_DAYS + 1).atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /**
     * 接下来要上的几节课，已应用调休。排提醒闹钟用的。
     * 走 sessionsOn 而不是直接扫 sessions —— 放假那天不该响，调过来的课该响。
     */
    fun upcoming(now: Long = System.currentTimeMillis(), limit: Int = 12): List<Session> =
        effectiveWindow(now).filter { it.start > now }.take(limit)

    /**
     * 同一门课有哪几种固定安排。
     *
     * 一门课经常是"周一在 A 楼上理论、周四在 B 楼上实验"，甚至两段时间不同地点。
     * 只列一堆地点、再列一堆时间，是对不上号的 —— 必须成对给出来。
     * 按"星期 + 时段 + 地点 + 教师"分组，这四项一样才算同一种安排。
     */
    fun arrangementsOf(title: String): List<Arrangement> =
        tt.sessions.asSequence()
            .filter { it.title == title && !it.allDay }
            .groupBy {
                ArrKey(
                    it.start.toLocalDate(zone).dayOfWeek.value,
                    it.startMinute(zone), it.endMinute(zone),
                    it.location, it.teacher
                )
            }
            .map { (k, list) ->
                Arrangement(
                    weekday = k.wd, startMin = k.s, endMin = k.e,
                    location = k.loc, teacher = k.tea,
                    weeks = list.map { weekOf(it.start.toLocalDate(zone)) }
                        .filter { it >= 1 }.distinct().sorted(),
                    count = list.size
                )
            }
            .sortedWith(compareBy({ it.weekday }, { it.startMin }))

    /** 某门课上哪些周，压成 "1-8,10,12-16" 这种课表写法。 */
    fun weekRangeOf(title: String): String {
        val ws = tt.sessions.filter { it.title == title }
            .map { weekOf(it.start.toLocalDate(zone)) }
            .filter { it >= 1 }
        return compressWeeks(ws)
    }
}

/** 一门课的一种固定安排：星期几、几点到几点、在哪、谁上、上哪几周。 */
data class Arrangement(
    /** 1 = 周一 … 7 = 周日 */
    val weekday: Int,
    val startMin: Int,
    val endMin: Int,
    val location: String,
    val teacher: String,
    val weeks: List<Int>,
    val count: Int
)

private data class ArrKey(val wd: Int, val s: Int, val e: Int, val loc: String, val tea: String)

/**
 * 这一节课属于哪种安排。
 * 调休搬过来的课星期变了，所以先严格匹配，匹配不上再只看时段和地点。
 */
fun List<Arrangement>.matching(s: Session, zone: ZoneId = ZoneId.systemDefault()): Arrangement? {
    val wd = s.start.toLocalDate(zone).dayOfWeek.value
    val sm = s.startMinute(zone)
    val em = s.endMinute(zone)
    return firstOrNull {
        it.weekday == wd && it.startMin == sm && it.endMin == em &&
            it.location == s.location && it.teacher == s.teacher
    } ?: firstOrNull { it.startMin == sm && it.location == s.location }
}

/** 这个时段对应第几节。对得上才显示，对不上（ICS 导入的）就不显示，不硬凑。 */
fun periodLabel(periods: List<PeriodSlot>, startMin: Int, endMin: Int): String {
    val a = periods.firstOrNull { it.startMin == startMin } ?: return ""
    val b = periods.lastOrNull { it.endMin == endMin } ?: return "第${a.index}节"
    return if (b.index > a.index) "第${a.index}-${b.index}节" else "第${a.index}节"
}

/** 时间轴刻度。取课本身的起止时刻，而不是整点 —— 整点上没有任何事情发生。 */
data class TimeMark(val minute: Int, val isStart: Boolean)

fun timeMarks(
    sessions: List<Session>,
    lo: Int,
    hi: Int,
    zone: ZoneId = ZoneId.systemDefault()
): List<TimeMark> {
    val starts = HashSet<Int>()
    val ends = HashSet<Int>()
    for (s in sessions) {
        if (s.allDay) continue
        starts.add(s.startMinute(zone))
        ends.add(s.endMinute(zone))
    }
    // 一节课都没有时退回整点，至少有个参照
    if (starts.isEmpty()) return generateSequence(lo) { it + 60 }.takeWhile { it <= hi }
        .map { TimeMark(it, true) }.toList()
    return (starts + ends).filter { it in lo..hi }.sorted()
        .map { TimeMark(it, it in starts) }
}

fun compressWeeks(ws: List<Int>): String {
    val a = ws.distinct().sorted()
    if (a.isEmpty()) return ""
    val out = ArrayList<String>()
    var i = 0
    while (i < a.size) {
        var j = i
        while (j + 1 < a.size && a[j + 1] == a[j] + 1) j++
        out.add(if (i == j) "${a[i]}" else "${a[i]}-${a[j]}")
        i = j + 1
    }
    return out.joinToString(",")
}

val DAY_ABBR = listOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")

fun LocalDate.abbr(): String = DAY_ABBR[dayOfWeek.value % 7]

fun Int.hhmm(): String = "%02d:%02d".format(this / 60, this % 60)

fun Long.hhmm(zone: ZoneId = ZoneId.systemDefault()): String =
    toLocalDateTime(zone).let { "%02d:%02d".format(it.hour, it.minute) }

/** 同一天里时间重叠的课并排放：算出每节课占第几列、这一簇一共几列。 */
data class Placed(val session: Session, val col: Int, val cols: Int)

fun layoutDay(items: List<Session>): List<Placed> {
    val sorted = items.sortedWith(compareBy({ it.start }, { -it.end }))
    val result = ArrayList<Placed>()
    var cluster = ArrayList<Session>()
    var clusterEnd = Long.MIN_VALUE

    fun flush() {
        if (cluster.isEmpty()) return
        val colEnds = ArrayList<Long>()
        val assigned = ArrayList<Pair<Session, Int>>()
        for (e in cluster) {
            var c = colEnds.indexOfFirst { it <= e.start }
            if (c < 0) {
                colEnds.add(e.end)
                c = colEnds.size - 1
            } else {
                colEnds[c] = e.end
            }
            assigned.add(e to c)
        }
        assigned.forEach { (s, c) -> result.add(Placed(s, c, colEnds.size)) }
        cluster = ArrayList()
        clusterEnd = Long.MIN_VALUE
    }

    for (e in sorted) {
        if (cluster.isNotEmpty() && e.start >= clusterEnd) flush()
        cluster.add(e)
        clusterEnd = maxOf(clusterEnd, e.end)
    }
    flush()
    return result
}

/** 时间轴上下界，向整小时对齐，至少留 4 小时高度。 */
fun timeBounds(sessions: List<Session>, zone: ZoneId = ZoneId.systemDefault()): Pair<Int, Int> {
    val timed = sessions.filter { !it.allDay }
    if (timed.isEmpty()) return 8 * 60 to 21 * 60
    var lo = Int.MAX_VALUE
    var hi = Int.MIN_VALUE
    for (s in timed) {
        lo = minOf(lo, s.startMinute(zone))
        hi = maxOf(hi, s.endMinute(zone))
    }
    val l = (lo / 60) * 60
    var h = minOf(24 * 60, ((hi + 59) / 60) * 60)
    if (h - l < 240) h = minOf(24 * 60, l + 240)
    return l to h
}
