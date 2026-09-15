package com.qingkebiao.timetable

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** 一节课的一次具体上课。时间存 epoch 毫秒，序列化和比较都最省事。 */
@Serializable
data class Session(
    val title: String,
    val location: String = "",
    val teacher: String = "",
    val note: String = "",
    val start: Long,
    val end: Long,
    val allDay: Boolean = false
)

@Serializable
data class Timetable(
    val sessions: List<Session> = emptyList(),
    /** 第 1 周的周一。null 表示自动取最早一节课所在的周。 */
    val termStartEpochDay: Long? = null,
    val showWeekend: Boolean = true,
    val sourceLabel: String = "",
    /** 记下来是为了以后一键重新同步，原生没有 CORS 限制可以直接拉。 */
    val icsUrl: String = ""
)

fun Long.toLocalDateTime(zone: ZoneId = ZoneId.systemDefault()): LocalDateTime =
    Instant.ofEpochMilli(this).atZone(zone).toLocalDateTime()

fun Long.toLocalDate(zone: ZoneId = ZoneId.systemDefault()): LocalDate =
    toLocalDateTime(zone).toLocalDate()

fun LocalDate.mondayOf(): LocalDate = with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

/** 分钟数（当天 0 点起算），用来在时间轴上定位。 */
fun Session.startMinute(zone: ZoneId = ZoneId.systemDefault()): Int =
    start.toLocalDateTime(zone).let { it.hour * 60 + it.minute }

fun Session.endMinute(zone: ZoneId = ZoneId.systemDefault()): Int {
    val e = end.toLocalDateTime(zone)
    val m = e.hour * 60 + e.minute
    return if (m <= startMinute(zone)) 24 * 60 else m
}

/**
 * 从课表派生出来的东西：学期第一周、总周数、某天有哪些课。
 * 每次数据变了就重新构造一个，不做增量维护 —— 课表这个量级完全不值得。
 */
class Derived(val tt: Timetable, val zone: ZoneId = ZoneId.systemDefault()) {

    val termStart: LocalDate = tt.termStartEpochDay?.let { LocalDate.ofEpochDay(it) }
        ?: tt.sessions.minByOrNull { it.start }?.start?.toLocalDate(zone)?.mondayOf()
        ?: LocalDate.now(zone).mondayOf()

    val weeks: Int = tt.sessions.maxByOrNull { it.end }?.end?.let {
        val diff = Math.floorDiv(it.toLocalDate(zone).toEpochDay() - termStart.toEpochDay(), 7L)
        maxOf(1, diff.toInt() + 1)
    } ?: 0

    val isEmpty: Boolean get() = tt.sessions.isEmpty()

    /** 用 floorDiv 而不是 / —— 负数除法向零取整会让开学前一周算成第 1 周。 */
    fun weekOf(d: LocalDate): Int =
        Math.floorDiv(d.toEpochDay() - termStart.toEpochDay(), 7L).toInt() + 1

    fun mondayOfWeek(w: Int): LocalDate = termStart.plusDays((w - 1) * 7L)

    fun clampWeek(w: Int): Int = w.coerceIn(1, maxOf(1, weeks))

    fun sessionsOn(d: LocalDate): List<Session> =
        tt.sessions.filter { it.start.toLocalDate(zone) == d }.sortedBy { it.start }

    fun daysOfWeek(w: Int): List<LocalDate> {
        val mon = mondayOfWeek(w)
        val n = if (tt.showWeekend) 7 else 5
        return (0 until n).map { mon.plusDays(it.toLong()) }
    }

    /** 正在上的那节课。 */
    fun current(now: Long = System.currentTimeMillis()): Session? =
        tt.sessions.firstOrNull { now in it.start until it.end }

    /** 下一节还没开始的课。 */
    fun next(now: Long = System.currentTimeMillis()): Session? =
        tt.sessions.filter { it.start > now }.minByOrNull { it.start }

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
