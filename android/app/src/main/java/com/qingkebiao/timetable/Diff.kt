package com.qingkebiao.timetable

/**
 * 两份课表的差异。
 *
 * 教务系统随时会调课：换教室、挪节次、加一次补课、停一周。学校一般只发个通知，
 * 学生自己记，漏掉就走空。这里做的事是：把新抓下来的课表和现在这份比一遍，
 * 把变动列出来，让用户自己决定要不要应用。
 *
 * 只比"课程块"，不比换算出来的上课记录 —— 作息表、开学日期这些是用户自己的设置，
 * 不该因为学校改了个教室就跟着变。应用变动时只换 [Timetable.zfBlocks]，
 * 其余设置原样留着，手动加的课和调休记录也不动。
 */
object Diff {

    enum class Kind {
        /** 新排的课，或者补课 */
        ADDED,

        /** 取消了 */
        REMOVED,

        /** 同一门课，换了时间或地点或周次 */
        MOVED
    }

    data class Change(
        val kind: Kind,
        val title: String,
        /** 变动前长什么样，ADDED 时为空 */
        val before: String,
        /** 变动后长什么样，REMOVED 时为空 */
        val after: String
    )

    private val DOW = "一二三四五六日"

    /** 一个课程块的人话描述，用来给用户看"从什么变成了什么"。 */
    fun describe(b: Zf.Block, periods: List<PeriodSlot> = emptyList()): String = buildString {
        append("周${DOW.getOrElse(b.weekday - 1) { '?' }} ")
        append("${b.startPeriod}-${b.endPeriod}节")
        val t = periodRangeText(b.startPeriod, b.endPeriod, periods)
        if (t.isNotBlank()) append(" $t")
        if (b.location.isNotBlank()) append(" · ${b.location}")
        val w = compressWeeks(b.weeks)
        if (w.isNotBlank()) append(" · ${w}周")
    }

    private fun periodRangeText(p1: Int, p2: Int, periods: List<PeriodSlot>): String {
        if (periods.isEmpty()) return ""
        val a = periods.firstOrNull { it.index == p1} ?: return ""
        val b = periods.firstOrNull { it.index == p2 } ?: a
        return "${a.startMin.hhmm()}-${b.endMin.hhmm()}"
    }

    /** 完全相同才算没变：星期、节次、周次、地点、老师，任何一项不同都是变动。 */
    private fun sig(b: Zf.Block): String =
        listOf(
            b.title, b.weekday, b.startPeriod, b.endPeriod,
            b.weeks.joinToString(","), b.location, b.teacher
        ).joinToString("|")

    /** 同一门课的标识。正方给的教学班号最稳；通用解析没有教学班号，退回课程名。 */
    private fun identity(b: Zf.Block): String =
        if (b.classCode.isNotBlank()) b.classCode else b.title

    fun compare(
        old: List<Zf.Block>,
        new: List<Zf.Block>,
        periods: List<PeriodSlot> = emptyList()
    ): List<Change> {
        val oldRest = old.toMutableList()
        val newRest = new.toMutableList()

        // 一模一样的先配掉，剩下的才是要解释的
        for (n in new.toList()) {
            val i = oldRest.indexOfFirst { sig(it) == sig(n) }
            if (i >= 0) {
                oldRest.removeAt(i)
                newRest.remove(n)
            }
        }

        val out = ArrayList<Change>()

        // 同一门课的剩余块两两配对，算"挪了"，而不是"删一个加一个"——
        // 后者对用户来说读起来完全不知道发生了什么
        for (n in newRest.toList()) {
            val i = oldRest.indexOfFirst { identity(it) == identity(n) }
            if (i >= 0) {
                val o = oldRest.removeAt(i)
                newRest.remove(n)
                out.add(
                    Change(
                        Kind.MOVED, n.title,
                        describe(o, periods), describe(n, periods)
                    )
                )
            }
        }

        newRest.forEach { out.add(Change(Kind.ADDED, it.title, "", describe(it, periods))) }
        oldRest.forEach { out.add(Change(Kind.REMOVED, it.title, describe(it, periods), "")) }

        return out.sortedWith(compareBy({ it.kind.ordinal }, { it.title }))
    }

    /** 一句话总结，放在提示条和通知里。 */
    fun summarize(changes: List<Change>): String {
        if (changes.isEmpty()) return "和现在的课表一样，没有变动"
        val moved = changes.count { it.kind == Kind.MOVED }
        val added = changes.count { it.kind == Kind.ADDED }
        val removed = changes.count { it.kind == Kind.REMOVED }
        val parts = ArrayList<String>()
        if (moved > 0) parts.add("$moved 处调整")
        if (added > 0) parts.add("新增 $added 处")
        if (removed > 0) parts.add("取消 $removed 处")
        return parts.joinToString("、")
    }

    fun label(k: Kind): String = when (k) {
        Kind.MOVED -> "调整"
        Kind.ADDED -> "新增"
        Kind.REMOVED -> "取消"
    }
}
