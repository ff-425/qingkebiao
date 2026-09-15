package com.qingkebiao.timetable

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/**
 * ICS(RFC 5545) 解析。从网页版的 JS 版本移植，行为保持一致。
 *
 * 支持：75 字节折行还原、DTSTART/DTEND/DURATION、TZID 命名时区、UTC(Z)、
 * 浮动时间、RRULE(WEEKLY/DAILY/MONTHLY/YEARLY + INTERVAL/BYDAY/COUNT/UNTIL/WKST)、
 * EXDATE 排除、RECURRENCE-ID 调课覆盖、STATUS:CANCELLED 丢弃、DESCRIPTION 里的教师名。
 *
 * java.time 直接给了命名时区，比 JS 那边拿 Intl 反解偏移量干净得多。
 */
object Ics {

    class ParseException(message: String) : Exception(message)

    private val ICS_DOW = mapOf(
        "SU" to DayOfWeek.SUNDAY, "MO" to DayOfWeek.MONDAY, "TU" to DayOfWeek.TUESDAY,
        "WE" to DayOfWeek.WEDNESDAY, "TH" to DayOfWeek.THURSDAY,
        "FR" to DayOfWeek.FRIDAY, "SA" to DayOfWeek.SATURDAY
    )

    private val DT_RE = Regex("^(\\d{4})(\\d{2})(\\d{2})(?:T(\\d{2})(\\d{2})(\\d{2})(Z)?)?$")
    private val DUR_RE =
        Regex("^([+-])?P(?:(\\d+)W)?(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?$")
    private val TEACHER_RE = Regex(
        "(?:授课教师|任课教师|教师|老师|教员|讲师|Teacher|Instructor)\\s*[：:]\\s*([^\\n;,，、]{1,24})",
        RegexOption.IGNORE_CASE
    )
    private val BYDAY_PREFIX = Regex("^[+-]?\\d+")

    // ------------------------------------------------------------------ 词法

    private fun unfold(t: String): String = t
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace(Regex("\n[ \t]"), "")

    private fun unescape(v: String): String = v
        .replace(Regex("\\\\[nN]"), "\n")
        .replace(Regex("\\\\([,;\\\\])"), "$1")

    private fun splitOutsideQuotes(s: String, sep: Char): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var quoted = false
        for (c in s) {
            when {
                c == '"' -> { quoted = !quoted; cur.append(c) }
                c == sep && !quoted -> { out.add(cur.toString()); cur.setLength(0) }
                else -> cur.append(c)
            }
        }
        out.add(cur.toString())
        return out
    }

    private class Prop(val name: String, val params: Map<String, String>, val value: String)

    /** 值里可能有冒号（URL、时间），所以在第一个"不在引号内"的冒号处切。 */
    private fun parseProp(line: String): Prop? {
        var i = 0
        var quoted = false
        while (i < line.length) {
            val c = line[i]
            if (c == '"') quoted = !quoted else if (c == ':' && !quoted) break
            i++
        }
        if (i >= line.length) return null
        val parts = splitOutsideQuotes(line.substring(0, i), ';')
        val params = HashMap<String, String>()
        for (p in parts.drop(1)) {
            val eq = p.indexOf('=')
            if (eq > 0) params[p.substring(0, eq).uppercase()] = p.substring(eq + 1).trim('"')
        }
        return Prop(parts[0].uppercase(), params, line.substring(i + 1))
    }

    // ------------------------------------------------------------------ 日期

    private class Dt(val zdt: ZonedDateTime, val allDay: Boolean)

    private fun parseDt(value: String, params: Map<String, String>, zone: ZoneId): Dt? {
        val m = DT_RE.find(value.trim()) ?: return null
        val g = m.groupValues
        val y = g[1].toInt(); val mo = g[2].toInt(); val d = g[3].toInt()

        if (g[4].isEmpty() || params["VALUE"] == "DATE") {
            return Dt(LocalDate.of(y, mo, d).atStartOfDay(zone), true)
        }
        val ldt = LocalDateTime.of(y, mo, d, g[4].toInt(), g[5].toInt(), g[6].toInt())
        return when {
            g[7] == "Z" -> Dt(ldt.atZone(ZoneOffset.UTC).withZoneSameInstant(zone), false)
            params["TZID"] != null -> {
                val z = runCatching { ZoneId.of(params["TZID"]!!) }.getOrDefault(zone)
                Dt(ldt.atZone(z), false)
            }
            // 没写时区的按本地墙钟时间理解，这对课表是正确的
            else -> Dt(ldt.atZone(zone), false)
        }
    }

    private fun parseDuration(v: String): Long? {
        val g = (DUR_RE.find(v.trim()) ?: return null).groupValues
        fun n(i: Int): Long = g[i].ifEmpty { "0" }.toLong()
        val secs = n(2) * 604800 + n(3) * 86400 + n(4) * 3600 + n(5) * 60 + n(6)
        return (if (g[1] == "-") -secs else secs) * 1000
    }

    private fun parseRRule(v: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (part in v.split(';')) {
            val eq = part.indexOf('=')
            if (eq > 0) out[part.substring(0, eq).uppercase()] = part.substring(eq + 1)
        }
        return out
    }

    // ------------------------------------------------------------------ 重复规则

    private class RawEvent {
        var uid: String? = null
        var title: String = ""
        var location: String = ""
        var desc: String = ""
        var start: ZonedDateTime? = null
        var end: ZonedDateTime? = null
        var durationMs: Long? = null
        var rrule: String? = null
        var allDay: Boolean = false
        var status: String? = null
        var organizer: String? = null
        var recurrenceId: Long? = null
        val exdate = ArrayList<Long>()
    }

    private fun expand(ev: RawEvent, horizon: ZonedDateTime, zone: ZoneId): List<ZonedDateTime> {
        val start = ev.start!!
        val rule = ev.rrule ?: return listOf(start)
        val r = parseRRule(rule)
        val freq = r["FREQ"]?.uppercase() ?: return listOf(start)
        val interval = (r["INTERVAL"]?.toIntOrNull() ?: 1).coerceAtLeast(1)
        val count = r["COUNT"]?.toIntOrNull()
        val until = r["UNTIL"]?.let { parseDt(it, emptyMap(), zone)?.zdt }
        val limit = until ?: horizon

        val out = ArrayList<ZonedDateTime>()
        val cap = 500

        // 返回 false 表示已经够了，别再加
        fun add(z: ZonedDateTime): Boolean {
            out.add(z)
            if (count != null && out.size >= count) return false
            return out.size < cap
        }

        when (freq) {
            "WEEKLY" -> {
                val wkst = ICS_DOW[(r["WKST"] ?: "MO").uppercase()] ?: DayOfWeek.MONDAY
                val days = r["BYDAY"]
                    ?.split(',')
                    ?.mapNotNull { ICS_DOW[it.replace(BYDAY_PREFIX, "").trim().uppercase()] }
                    ?.takeIf { it.isNotEmpty() }
                    ?: listOf(start.dayOfWeek)
                // 相对"周起点"的天偏移，排好序保证产出是按时间递增的
                val offsets = days.map { ((it.value - wkst.value) + 7) % 7 }.distinct().sorted()
                val weekStart = start.toLocalDate().with(TemporalAdjusters.previousOrSame(wkst))
                val time = start.toLocalTime()

                outer@ for (w in 0 until 300) {
                    val base = weekStart.plusWeeks(w.toLong() * interval)
                    if (base.atStartOfDay(zone) > limit.plusDays(7)) break
                    for (off in offsets) {
                        val occ = base.plusDays(off.toLong()).atTime(time).atZone(start.zone)
                        if (occ < start) continue
                        if (occ > limit) break@outer
                        if (!add(occ)) break@outer
                    }
                }
            }

            "DAILY" -> {
                var i = 0L
                while (i < 800) {
                    val occ = start.plusDays(i * interval)
                    if (occ > limit) break
                    if (!add(occ)) break
                    i++
                }
            }

            "MONTHLY", "YEARLY" -> {
                val step = if (freq == "MONTHLY") interval.toLong() else interval.toLong() * 12
                var i = 0L
                while (i < 120) {
                    val occ = start.plusMonths(i * step)
                    if (occ > limit) break
                    if (!add(occ)) break
                    i++
                }
            }

            else -> out.add(start)
        }
        return out.sorted()
    }

    // ------------------------------------------------------------------ 入口

    private fun cleanTeacher(v: String): String = v
        .split(Regex("\\s{2,}|\t"))
        .first()
        .replace(Regex("\\s*\\S+\\s*[：:].*$"), "")
        .trim()

    private fun applyProp(ev: RawEvent, line: String, zone: ZoneId) {
        val p = parseProp(line) ?: return
        when (p.name) {
            "SUMMARY" -> ev.title = unescape(p.value)
            "LOCATION" -> ev.location = unescape(p.value)
            "DESCRIPTION" -> ev.desc = unescape(p.value)
            "UID" -> ev.uid = p.value
            "RRULE" -> ev.rrule = p.value
            "STATUS" -> ev.status = p.value.uppercase()
            "ORGANIZER" -> ev.organizer = unescape(p.value).removePrefix("mailto:").removePrefix("MAILTO:")
            "DTSTART" -> parseDt(p.value, p.params, zone)?.let { ev.start = it.zdt; ev.allDay = it.allDay }
            "DTEND" -> parseDt(p.value, p.params, zone)?.let { ev.end = it.zdt }
            "DURATION" -> parseDuration(p.value)?.let { ev.durationMs = it }
            "RECURRENCE-ID" -> parseDt(p.value, p.params, zone)?.let {
                ev.recurrenceId = it.zdt.toInstant().toEpochMilli()
            }
            "EXDATE" -> for (v in p.value.split(',')) {
                parseDt(v, p.params, zone)?.let { ev.exdate.add(it.zdt.toInstant().toEpochMilli()) }
            }
        }
    }

    fun parse(text: String, zone: ZoneId = ZoneId.systemDefault()): List<Session> {
        if (!text.contains("BEGIN:VCALENDAR", ignoreCase = true)) {
            throw ParseException("这不像 ICS 日历文件 —— 开头找不到 BEGIN:VCALENDAR。")
        }

        val events = ArrayList<RawEvent>()
        var cur: RawEvent? = null
        var nested: String? = null

        for (raw in unfold(text).split('\n')) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val up = line.uppercase()
            when {
                up.startsWith("BEGIN:VEVENT") -> { cur = RawEvent(); nested = null }
                up.startsWith("END:VEVENT") -> { cur?.let { events.add(it) }; cur = null }
                cur == null -> Unit
                // 跳过 VEVENT 里嵌的 VALARM 之类，它们也有 DTSTART/DURATION
                up.startsWith("BEGIN:") -> nested = up.removePrefix("BEGIN:")
                up.startsWith("END:") -> nested = null
                nested != null -> Unit
                else -> applyProp(cur, line, zone)
            }
        }

        val usable = events.filter { it.start != null && it.status != "CANCELLED" }
        if (usable.isEmpty()) throw ParseException("文件解析成功，但里面没有任何日程（VEVENT）。")

        // RECURRENCE-ID：被单独改过的那一次，要从母事件展开结果里剔掉
        val overrides = HashMap<String, MutableSet<Long>>()
        for (e in usable) {
            val rid = e.recurrenceId
            val uid = e.uid
            if (rid != null && uid != null) overrides.getOrPut(uid) { HashSet() }.add(rid)
        }

        val maxStart = usable.maxOf { it.start!!.toInstant().toEpochMilli() }
        val horizon = Instant.ofEpochMilli(maxStart).atZone(zone).plusDays(400)

        val out = ArrayList<Session>()
        for (e in usable) {
            val start = e.start!!
            val startMs = start.toInstant().toEpochMilli()
            val durMs = when {
                e.end != null -> e.end!!.toInstant().toEpochMilli() - startMs
                e.durationMs != null -> e.durationMs!!
                e.allDay -> 86_400_000L
                else -> 90 * 60_000L
            }.coerceAtLeast(60_000L)

            val skip = HashSet(e.exdate)
            if (e.recurrenceId == null) e.uid?.let { uid -> overrides[uid]?.let(skip::addAll) }

            val teacher = TEACHER_RE.find(e.desc + "\n" + e.location)
                ?.groupValues?.get(1)?.let(::cleanTeacher)
                ?: e.organizer?.takeIf { it.isNotBlank() && !it.contains('@') }
                ?: ""

            val occs = if (e.rrule != null && e.recurrenceId == null) {
                expand(e, horizon, zone)
            } else {
                listOf(start)
            }

            for (occ in occs) {
                val ms = occ.toInstant().toEpochMilli()
                if (ms in skip) continue
                out.add(
                    Session(
                        id = newId(),
                        title = e.title.trim().ifBlank { "未命名" },
                        location = e.location.trim(),
                        teacher = teacher,
                        note = e.desc,
                        start = ms,
                        end = ms + durMs,
                        allDay = e.allDay
                    )
                )
            }
        }

        if (out.isEmpty()) throw ParseException("日程都被排除规则过滤掉了，没有可显示的课。")
        return out.sortedBy { it.start }
    }
}
