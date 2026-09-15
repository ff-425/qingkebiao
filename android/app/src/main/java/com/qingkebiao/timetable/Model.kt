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
    val periods: List<PeriodSlot> = DEFAULT_PERIODS
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
        return (-1L..21L).flatMap { sessionsOn(today.plusDays(it)) }.sortedBy { it.start }
    }

    fun current(now: Long = System.currentTimeMillis()): Session? =
        effectiveWindow(now).firstOrNull { now in it.start until it.end }

    fun next(now: Long = System.currentTimeMillis()): Session? =
        effectiveWindow(now).firstOrNull { it.start > now }
        // 窗口外（比如假期后才开学）兜底扫一遍原始数据
            ?: tt.sessions.filter { it.start > now }.minByOrNull { it.start }

    /** 某门课上哪些周，压成 "1-8,10,12-16" 这种课表写法。 */
    fun weekRangeOf(title: String): String {
        val ws = tt.sessions.filter { it.title == title }
            .map { weekOf(it.start.toLocalDate(zone)) }
            .filter { it >= 1 }
        return compressWeeks(ws)
    }
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
