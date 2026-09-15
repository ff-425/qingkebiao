package com.qingkebiao.timetable

/**
 * 作息时间的来源问题。
 *
 * 教务系统的课表页通常只给"第几节"，不给时刻（中国计量大学那页整页没有一个 HH:MM）。
 * 所以时间必须另外拿，而不是在代码里写死一张表 —— 写死了换个学校就是错的。
 *
 * 两条腿：
 *  1. [sniff]   —— 扫描用户在内置浏览器里打开的任何页面，只要出现"节次 + 起止时刻"
 *                 就把真实作息读出来。学校只要有作息时间页，顺手打开就自动拿到。
 *  2. [deriveGroups] + [generate]
 *                 —— 读不到真时间时，从课表页自己的"上午/下午/晚上"分组（rowspan）
 *                 推算。分组是页面里的真实数据，所以每所学校的节次结构都是对的，
 *                 只有三个起点和每节时长是估的。
 *
 * 不管走哪条，结果都会在导入前显示给用户核对。
 */
object Periods {

    private fun opts() = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    private val TAG = Regex("""<[^>]+>""")
    /** 换行：真正的行边界。单元格边界不能当换行，否则"第1节|08:10|08:55"会被拆成三行。 */
    private val LINE_SEP = Regex("""<br\s*/?>|</tr>|</p>|</div>|</li>""", opts())
    /** 单元格边界换成分隔符，让一行里保留"节次 时刻 时刻"的相邻关系。 */
    private val CELL_SEP = Regex("""</t[dh]>""", opts())

    private val CN_NUM = mapOf(
        "一" to 1, "二" to 2, "三" to 3, "四" to 4, "五" to 5, "六" to 6,
        "七" to 7, "八" to 8, "九" to 9, "十" to 10, "十一" to 11, "十二" to 12,
        "十三" to 13, "十四" to 14
    )

    /**
     * 一行里同时出现"第N节"和两个时刻就算一条。
     * 覆盖到的写法：第1节 08:00-08:45 / 1 08:00~08:45 / 第一节 8:00—8:45 /
     * 表格里 <td>1</td><td>08:00</td><td>08:45</td>（<td> 被当成换行后仍在同一行）
     */
    private val LINE_RE = Regex(
        """(?:第\s*)?([0-9]{1,2}|[一二三四五六七八九十]{1,3})\s*节?[^0-9]{0,12}?""" +
            """([0-9]{1,2})\s*[:：]\s*([0-9]{2})[^0-9]{0,8}?([0-9]{1,2})\s*[:：]\s*([0-9]{2})"""
    )

    private fun textOf(html: String): String =
        TAG.replace(LINE_SEP.replace(CELL_SEP.replace(html, " | "), "\n"), " ")
            .replace("&nbsp;", " ")
            .replace(Regex("""[ \t ]+"""), " ")

    /**
     * 从任意页面里嗅探作息时间。找不到或不可信就返回 null —— 宁可没有，
     * 也不要把一张半对的表写进去。
     */
    fun sniff(html: String): List<PeriodSlot>? {
        val found = HashMap<Int, PeriodSlot>()
        for (line in textOf(html).split('\n')) {
            val t = line.trim()
            if (t.length > 120) continue
            val m = LINE_RE.find(t) ?: continue
            val g = m.groupValues
            val idx = g[1].toIntOrNull() ?: CN_NUM[g[1]] ?: continue
            if (idx !in 1..14) continue
            val sh = g[2].toInt(); val sm = g[3].toInt()
            val eh = g[4].toInt(); val em = g[5].toInt()
            if (sh > 23 || eh > 23 || sm > 59 || em > 59) continue
            val s = sh * 60 + sm
            val e = eh * 60 + em
            // 一节课 20~180 分钟之外的都当误匹配
            if (e - s !in 20..180) continue
            found.putIfAbsent(idx, PeriodSlot(idx, s, e))
        }
        if (found.size < 3) return null

        val list = found.toSortedMap().values.toList()
        // 节次必须连续从 1 开始，且时间整体递增，否则多半是匹配到别的东西
        if (list.first().index != 1) return null
        if (list.mapIndexed { i, p -> p.index == i + 1 }.any { !it }) return null
        if (list.zipWithNext().any { (a, b) -> b.startMin < a.startMin }) return null
        return list
    }

    /** 课表页"时间段"那一列：上午/下午/晚上各占几节。靠 rowspan 读，是页面真实数据。 */
    fun deriveGroups(html: String): List<Pair<String, Int>> {
        val table = Regex("""<table[^>]*>.*?星期.*?</table>""", opts()).find(html)?.value ?: return emptyList()
        val out = ArrayList<Pair<String, Int>>()
        for (m in Regex("""<td([^>]*)>(.{0,200}?)</td>""", opts()).findAll(table)) {
            val txt = TAG.replace(m.groupValues[2], "").trim()
            if (txt !in listOf("上午", "中午", "下午", "晚上", "夜间")) continue
            val rs = Regex("""rowspan\s*=\s*["']?(\d+)""", RegexOption.IGNORE_CASE)
                .find(m.groupValues[1])?.groupValues?.get(1)?.toIntOrNull() ?: 1
            out.add(txt to rs)
        }
        return out
    }

    /** 各时段的惯用起点。只有这里是估的，结构来自页面。 */
    private val DEFAULT_START = mapOf(
        "上午" to 8 * 60,
        "中午" to 12 * 60 + 30,
        "下午" to 14 * 60,
        "晚上" to 18 * 60 + 30,
        "夜间" to 18 * 60 + 30
    )

    fun generate(
        groups: List<Pair<String, Int>>,
        lenMin: Int = 45,
        gapMin: Int = 5
    ): List<PeriodSlot> {
        if (groups.isEmpty()) return DEFAULT_PERIODS
        val out = ArrayList<PeriodSlot>()
        var idx = 1
        for ((name, count) in groups) {
            var t = DEFAULT_START[name] ?: (8 * 60)
            repeat(count.coerceIn(0, 12)) {
                out.add(PeriodSlot(idx, t, t + lenMin))
                t += lenMin + gapMin
                idx++
            }
        }
        return out.ifEmpty { DEFAULT_PERIODS }
    }

    /** 人话描述这套作息是怎么来的，导入前显示给用户。 */
    fun describe(groups: List<Pair<String, Int>>): String =
        if (groups.isEmpty()) "按通用作息推算"
        else "按课表页的" + groups.joinToString("、") { "${it.first}${it.second}节" } + "推算"
}
