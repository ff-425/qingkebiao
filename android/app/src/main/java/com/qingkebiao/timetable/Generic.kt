package com.qingkebiao.timetable

/**
 * 通用课表表格解析（兜底）。
 *
 * 国内教务系统有正方、强智、青果、URP 等好几家，页面结构完全不同，没有万能解析器。
 * [Zf] 是针对正方写的、结构化的、可靠的；这里是它认不出时的兜底：
 * 按"表格 + 星期表头 + 节次行"这个几乎所有课表都有的形态去猜。
 *
 * 明确是尽力而为，所以调用方**必须**先把结果给用户看再导入 —— 猜错了用户一眼
 * 能看出来，不会把垃圾写进课表。
 *
 * 同一套逻辑同时服务两种来源：网页里的 <table>，和 Excel/CSV 读出来的二维表
 * （见 [Sheets]）。两边唯一的区别只是"怎么得到格子"，格子拿到以后
 * 判断哪列是星期几、哪行是第几节、格子里哪行是地点哪行是老师，是完全一样的。
 *
 * 唯一不靠猜的部分是表格展开：rowspan/colspan（Excel 里是合并单元格）
 * 按确定语义还原成网格。连堂课通常就是靠合并出来的，不展开就对不上列。
 */
object Generic {

    class ParseException(message: String) : Exception(message)

    private fun opts() = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    private val TABLE = Regex("""<table\b[^>]*>(.*?)</table>""", opts())
    private val ROW = Regex("""<tr\b[^>]*>(.*?)</tr>""", opts())
    private val CELL = Regex("""<t([dh])\b([^>]*)>(.*?)</t\1>""", opts())
    private val SPAN_ATTR = { name: String -> Regex("""$name\s*=\s*["']?(\d+)""", RegexOption.IGNORE_CASE) }
    private val BR = Regex("""<br\s*/?>|</p>|</div>|</li>""", opts())
    private val TAG = Regex("""<[^>]+>""")
    private val WS_LINE = Regex("""[ \t ]+""")

    private val WEEKDAYS = listOf("一", "二", "三", "四", "五", "六", "日", "天")

    private fun unescape(s: String) = s
        .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")

    /** 把单元格内容拆成若干行文本：<br>/</p>/</div> 都当换行。 */
    private fun cellLines(html: String): List<String> =
        BR.replace(html, "\n")
            .let { TAG.replace(it, "") }
            .let { unescape(it) }
            .split('\n')
            .map { WS_LINE.replace(it, " ").trim() }
            .filter { it.isNotEmpty() }

    /** 一个格子，只关心"里面有哪几行字"。HTML 和 Excel 到这一步就没区别了。 */
    private class Cell(val lines: List<String>) {
        val text: String = lines.joinToString(" ")
    }

    /**
     * 把一张 HTML 表展开成规整网格，rowspan/colspan 都落到实际占据的每个位置。
     * 这是 HTML 表格的确定语义，不是启发式。
     */
    private fun toGrid(tableInner: String): List<MutableList<Cell?>> {
        val grid = ArrayList<MutableList<Cell?>>()
        fun rowAt(r: Int): MutableList<Cell?> {
            while (grid.size <= r) grid.add(ArrayList())
            return grid[r]
        }
        for ((r, rowM) in ROW.findAll(tableInner).withIndex()) {
            var c = 0
            for (cm in CELL.findAll(rowM.groupValues[1])) {
                val attrs = cm.groupValues[2]
                val inner = cm.groupValues[3]
                val rs = SPAN_ATTR("rowspan").find(attrs)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val cs = SPAN_ATTR("colspan").find(attrs)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                // 跳过已被上方 rowspan 占掉的位置
                while (c < rowAt(r).size && rowAt(r)[c] != null) c++
                val cell = Cell(cellLines(inner))
                for (dr in 0 until rs.coerceIn(1, 30)) {
                    val rr = rowAt(r + dr)
                    for (dc in 0 until cs.coerceIn(1, 30)) {
                        val cc = c + dc
                        while (rr.size <= cc) rr.add(null)
                        if (rr[cc] == null) rr[cc] = cell
                    }
                }
                c += cs
            }
        }
        return grid
    }

    /** 某一行里，哪几列是星期几。返回 列索引 -> 1..7 */
    private fun weekdayColumns(row: List<Cell?>): Map<Int, Int> {
        val out = HashMap<Int, Int>()
        for ((i, cell) in row.withIndex()) {
            val t = cell?.text?.replace(" ", "") ?: continue
            if (t.length > 8) continue
            if (!t.contains("星期") && !t.contains("周")) continue
            val ch = t.lastOrNull()?.toString() ?: continue
            val idx = WEEKDAYS.indexOf(ch)
            if (idx >= 0) out[i] = if (idx == 7) 7 else idx + 1   // "天" 当周日
        }
        return out
    }

    private val PERIOD_IN_TEXT = Regex("""(\d+)\s*[-–~]\s*(\d+)\s*节""")
    private val SINGLE_PERIOD = Regex("""第?\s*(\d+)\s*节""")
    /**
     * 行标题里的节次范围："第1-2节""1~2""第3、4节"。
     * 行标题里常带着上课时间，数字紧挨冒号的一律不算 ——
     * 否则 "08:10-09:45" 会被读成第 10-9 节。
     */
    private val LABEL_RANGE = Regex("""(?<![\d:：])(\d{1,2})\s*[-–—~～至到、,，]\s*(\d{1,2})(?![\d:：])""")
    private val LABEL_SINGLE = Regex("""(?<![\d:：])(\d{1,2})(?![\d:：])""")

    private const val CN_DIGITS = "零一二三四五六七八九"
    private val CN_NUM = Regex("""[一二三四五六七八九十]+""")

    /** "第三、四节" → "第3、4节"，"十一" → "11"；"一二节" 这种连写当成 1-2 */
    private fun cnToDigits(s: String): String = CN_NUM.replace(s) { m ->
        val t = m.value
        fun d(c: Char) = CN_DIGITS.indexOf(c)
        when {
            t == "十" -> "10"
            t.length == 2 && t[0] == '十' -> (10 + d(t[1])).toString()
            t.length == 2 && t[1] == '十' -> (d(t[0]) * 10).toString()
            t.length == 3 && t[1] == '十' -> (d(t[0]) * 10 + d(t[2])).toString()
            '十' !in t -> t.map { d(it) }.joinToString("-")
            else -> t
        }
    }

    /**
     * 这一行的行标题写的是第几节到第几节，没写返回 null。
     * 只看星期列左边的格子（最多三列：常见的是"上午 | 第1-2节 | 08:10"）。
     * 先在所有标题格里找范围，找不到再找单个数字 —— "上午 | 1" 这种单个的也要认。
     */
    private fun rowLabel(row: List<Cell?>, firstDayCol: Int): IntRange? {
        val texts = (0 until minOf(row.size, firstDayCol, 3))
            .mapNotNull { i -> row[i]?.lines?.joinToString("\n")?.let(::cnToDigits) }
        for (t in texts) {
            val m = LABEL_RANGE.find(t) ?: continue
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            if (a in 1..20 && b in a..20 && b - a <= 5) return a..b
        }
        for (t in texts) {
            val n = LABEL_SINGLE.find(t)?.groupValues?.get(1)?.toInt() ?: continue
            if (n in 1..20) return n..n
        }
        return null
    }
    private val WEEK_TEXT = Regex("""[\d,，\-－~至到单双周()（）]{2,}周""")
    private val PLACE_HINT = Regex("""[楼馆室厅场院区舍]|机房|实验|中心|校区""")
    /**
     * "教三-201""A104""综合楼301" 这类：几个字 + 房间号。
     * 前瞻那一段要求必须有一个既不是数字也不是分隔符的字符，否则 "1-16"
     * 会被当成教室 —— OCR 把 "1-16周" 的周吃掉之后就正好长这样，实测踩到过。
     */
    private val ROOM_LIKE = Regex("""^(?=.*[^\d\-－—_ ])[^\s]{1,12}\d{1,4}[室房]?$""")
    private val CJK_NAME = Regex("""^[一-龥·]{2,4}(?:[,，、][一-龥·]{2,4})*$""")

    /**
     * OCR 出来的周次经常缺字或多字："1-16周"→"1-16"，"1-12周"→"1-123"。
     * 只在图片/PDF 这种不可靠来源上启用，网页和 Excel 不需要放宽。
     */
    private val WEEK_LOOSE = Regex("""^[(（]?([单双])?[)）]?\s*(\d{1,2})\s*[-–—~至到]\s*(\d{1,3})\s*\D{0,2}$""")

    private fun looseWeeks(s: String): String? {
        val m = WEEK_LOOSE.find(s.trim()) ?: return null
        val a = m.groupValues[2].toIntOrNull() ?: return null
        var b = m.groupValues[3].toIntOrNull() ?: return null
        // "1-123"：多出来的那一位多半是被认错的"周"，砍掉再看
        if (b > 30) b /= 10
        if (a < 1 || b < a || b > 30) return null
        val od = m.groupValues[1]
        return if (od.isNotEmpty()) "($od)$a-${b}周" else "$a-${b}周"
    }

    private fun looksLikePlace(s: String): Boolean =
        PLACE_HINT.containsMatchIn(s) || (s.any { it.isDigit() } && ROOM_LIKE.matches(s))

    fun parse(html: String, defaultWeeks: Int = 18): List<Zf.Block> {
        // 选星期表头最多的那张表
        val best = TABLE.findAll(html)
            .map { it.groupValues[1] }
            .maxByOrNull { inner -> WEEKDAYS.count { d -> inner.contains("星期$d") || inner.contains("周$d") } }
            ?: throw ParseException("页面里没有表格。")
        return fromGrid(toGrid(best), defaultWeeks, loose = false)
    }

    /**
     * Excel / CSV 读出来的二维表走这里。合并单元格在 [Sheets] 那边已经展开过了，
     * 到这儿每一格都是独立的字符串。
     */
    fun parseRows(
        rows: List<List<String>>,
        defaultWeeks: Int = 18,
        /** 来源是 OCR 时放宽周次识别，因为"周"字经常掉掉或认错 */
        loose: Boolean = false
    ): List<Zf.Block> {
        val grid: List<MutableList<Cell?>> = rows.map { r ->
            r.map { v ->
                val lines = v.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
                if (lines.isEmpty()) null else Cell(lines)
            }.toMutableList()
        }
        return fromGrid(grid, defaultWeeks, loose)
    }

    private fun fromGrid(
        grid: List<List<Cell?>>,
        defaultWeeks: Int,
        loose: Boolean = false
    ): List<Zf.Block> {
        var headerRow = -1
        var cols: Map<Int, Int> = emptyMap()
        for ((r, row) in grid.withIndex()) {
            val m = weekdayColumns(row)
            if (m.size >= 3) { headerRow = r; cols = m; break }
        }
        if (headerRow < 0) {
            throw ParseException("找不到「星期一…星期日」这样的表头，没法判断哪一列是星期几。")
        }

        val out = ArrayList<Zf.Block>()
        val seen = HashSet<String>()
        /** 靠行推节次的格子：key → (上次出现在第几行, 在 out 里的下标)，合并单元格往下延伸时用 */
        val open = HashMap<String, Pair<Int, Int>>()

        // 每一行代表第几节到第几节。
        // 以前一律"一行一节"按行号往下数，一行一个大节（第1-2节）的总课表
        // 导进来，一节 95 分钟的课只占了 45 分钟。
        val bodyRows = ((headerRow + 1) until grid.size).toList()
        val firstDayCol = cols.keys.min()
        val labels = bodyRows.map { rowLabel(grid[it], firstDayCol) }
        // 图片识别往往只读出每个大节的起始数字：1、3、5、7。
        // 只有每一行都标了单个数字、而且全都等距跳 2~4 的时候才这么推 ——
        // 1、2、3、5（第 4 节那行没课被删了）这种不规则的不能瞎推成大节。
        val step = if (labels.size >= 3 && labels.all { it != null && it.first == it.last }) {
            labels.map { it!!.first }.zipWithNext { a, b -> b - a }.toSet()
                .singleOrNull()?.takeIf { it in 2..4 }
        } else null

        var nextPeriod = 1
        for ((k, r) in bodyRows.withIndex()) {
            val row = grid[r]
            val lab = labels[k]
            val rowRange = when {
                lab == null -> nextPeriod..nextPeriod
                step != null -> lab.first..(lab.first + step - 1)
                else -> lab
            }
            nextPeriod = rowRange.last + 1

            for ((ci, weekday) in cols) {
                val cell = row.getOrNull(ci) ?: continue
                val lines = cell.lines
                if (lines.isEmpty() || cell.text.isBlank()) continue

                val joined = lines.joinToString(" ")

                // 格子里自己写了节次就以格子为准，否则用这一行代表的节次
                val pm = PERIOD_IN_TEXT.find(joined)
                val sp = if (pm == null) SINGLE_PERIOD.find(joined)?.groupValues?.get(1)?.toIntOrNull() else null
                val explicit = pm != null || sp != null
                val p1 = pm?.groupValues?.get(1)?.toInt() ?: sp ?: rowRange.first
                val p2 = pm?.groupValues?.get(2)?.toInt() ?: sp ?: rowRange.last
                if (p1 < 1 || p1 > 30) continue

                // 合并单元格（网页 rowspan、Excel 上下合并）展开后，同一段文字会在连续几行重复出现。
                // 格子写了节次的：重复几次都是同一节课，去重就行。
                // 靠行推节次的：紧挨着上一行出现就是同一节课往下延伸，把结束节拉长；
                // 不挨着的是另一节课 —— 以前按"星期 + 文字"全局去重，同一天上下午
                // 两节一模一样的课会丢一节。
                val key = "$weekday|${cell.text}"
                if (explicit) {
                    if (!seen.add("$key|$p1-$p2")) continue
                } else {
                    val prev = open[key]
                    if (prev != null && prev.first == k - 1) {
                        val i = prev.second
                        out[i] = out[i].copy(endPeriod = maxOf(out[i].endPeriod, p2))
                        open[key] = k to i
                        continue
                    }
                }

                var weeksRaw = WEEK_TEXT.find(joined)?.value ?: ""
                if (weeksRaw.isBlank() && loose) {
                    weeksRaw = lines.firstNotNullOfOrNull { looseWeeks(it) }.orEmpty()
                }
                val weeks = Zf.parseWeeks(weeksRaw).ifEmpty { (1..defaultWeeks).toList() }

                // 课程名取第一行（跳过纯周次/节次那种行）。
                // 曾经是"剩下的行里最长的那个"，结果 "高等数学/1-16周/教三-201/王伟"
                // 里选中了 "教三-201" —— 它更长。第一行是课程名这件事，
                // 在真实课表里比"最长"可靠得多。
                fun isMeta(s: String) =
                    WEEK_TEXT.containsMatchIn(s) || PERIOD_IN_TEXT.containsMatchIn(s) ||
                        SINGLE_PERIOD.matches(s.trim()) || (loose && looseWeeks(s) != null)
                val titleIdx = lines.indexOfFirst { !isMeta(it) }
                if (titleIdx < 0) continue
                val title = lines[titleIdx].take(40).trim()
                if (title.length < 2) continue

                var place = ""
                var teacher = ""
                for ((li, l) in lines.withIndex()) {
                    when {
                        li == titleIdx -> Unit
                        isMeta(l) -> Unit
                        place.isEmpty() && looksLikePlace(l) -> place = l
                        teacher.isEmpty() && CJK_NAME.matches(l) -> teacher = l
                        else -> Unit
                    }
                }

                out.add(
                    Zf.Block(
                        title = title, kind = "", weekday = weekday,
                        startPeriod = p1, endPeriod = maxOf(p1, p2),
                        weeks = weeks, weeksRaw = weeksRaw.ifBlank { "未标注周次，按 1-$defaultWeeks 周" },
                        location = place, locationFull = place,
                        teacher = teacher, credits = "", classCode = ""
                    )
                )
                if (!explicit) open[key] = k to out.lastIndex
            }
        }
        if (out.isEmpty()) throw ParseException("表格找到了，但没认出任何课程。")
        return out
    }
}
