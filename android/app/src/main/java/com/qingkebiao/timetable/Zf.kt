package com.qingkebiao.timetable

import kotlinx.serialization.Serializable
import java.time.LocalDate

/**
 * 正方教务系统（jwglxt 新版）课表页解析。
 *
 * 这份实现是对着中国计量大学 jwxt.cjlu.edu.cn 的真实页面写的，不是凭猜：
 *
 *   <td id="{星期1-7}-{起始节次}" class="td_wrap">
 *     <div class="timetable_con text-left">
 *       <span class="title"><font>大学物理A2★</font></span>
 *       <p><span title="节/周">…</span><font> (1-2节)1-3周,5-17周</font></p>
 *       <p><span title="上课地点">…</span><font> 下沙 翔宇楼 翔宇楼503（智慧教室）</font></p>
 *       <p><span title="教师 ">…</span><font> 尚曼玉</font></p>
 *       <p><span title="教学班名称">…</span><font> (2026-2027-1)-08G0020-02</font></p>
 *       <p><span title="学分">…</span><font> 3.0</font></p>
 *     </div>
 *     ← 一个格子里可能有多个 div：同一时段安排了多门课
 *   </td>
 *
 * 靠 tooltip 的 title 属性取字段，而不是按 <p> 的出现顺序 —— 顺序会随版本变，
 * title 是语义化的，稳得多。
 *
 * 页面里**没有**节次对应的具体时刻，所以时间要靠 Timetable.periods 那张作息表换算。
 */
object Zf {

    class ParseException(message: String) : Exception(message)

    /**
     * 一个"课程块"：某星期某节次段、在某几周上的一门课。
     * 存进 Timetable 里，这样改了作息表就能原地重算，不用重新抓一次网页。
     */
    @Serializable
    data class Block(
        val title: String,
        /** ★讲课 ◇实践 ●上机 ○实验 */
        val kind: String,
        /** 1 = 周一 … 7 = 周日 */
        val weekday: Int,
        val startPeriod: Int,
        val endPeriod: Int,
        val weeks: List<Int>,
        val weeksRaw: String,
        /** 末段，最有信息量：翔宇楼503（智慧教室） */
        val location: String,
        /** 完整：下沙 翔宇楼 翔宇楼503（智慧教室） */
        val locationFull: String,
        val teacher: String,
        val credits: String,
        val classCode: String
    )

    private val TD = Regex(
        """<td[^>]*\bid="(\d+)-(\d+)"[^>]*\bclass="td_wrap"[^>]*>(.*?)</td>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val DIV = Regex(
        """<div class="timetable_con[^"]*">(.*?)</div>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val TITLE = Regex(
        """<span class="title">(.*?)</span>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val PARA = Regex(
        """<p\b[^>]*>(.*?)</p>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val TOOLTIP_SPAN = Regex(
        """<span\b[^>]*data-toggle="tooltip".*?</span>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val TITLE_ATTR = Regex("""title="([^"]*)"""", RegexOption.IGNORE_CASE)
    private val TAG = Regex("""<[^>]+>""")
    private val WS = Regex("""\s+""")
    /** (1-2节)1-3周,5-17周 —— 也接受 (12节) 这种单节写法 */
    private val PERIOD_WEEKS = Regex("""\((\d+)(?:-(\d+))?节\)\s*(.*)""")

    private fun text(x: String): String =
        WS.replace(unescape(TAG.replace(x, "")), " ").trim()

    private fun unescape(s: String): String = s
        .replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&amp;", "&")

    /**
     * 周次字符串 → 具体周次列表。
     * 实测存在这些写法：1-3周,5-17周 / 13-17周 / 12周 / 8周,14周 /（单）（双）
     */
    fun parseWeeks(raw: String): List<Int> {
        val out = sortedSetOf<Int>()
        for (part in raw.split(',', '，')) {
            val p = part.trim()
            if (p.isEmpty()) continue
            val odd = p.contains("单")
            val even = p.contains("双")
            val m = Regex("""(\d+)(?:-(\d+))?""").find(p) ?: continue
            val a = m.groupValues[1].toIntOrNull() ?: continue
            val b = m.groupValues[2].ifEmpty { m.groupValues[1] }.toIntOrNull() ?: a
            if (a < 1 || b < a || b > 60) continue
            for (w in a..b) {
                if (odd && w % 2 == 0) continue
                if (even && w % 2 == 1) continue
                out.add(w)
            }
        }
        return out.toList()
    }

    /** 这页看着像不像正方课表页。 */
    fun looksLikeZf(html: String): Boolean =
        html.contains("td_wrap") && html.contains("timetable_con")

    fun parse(html: String): List<Block> {
        if (!looksLikeZf(html)) {
            throw ParseException(
                "这页里没有正方课表的结构（td_wrap / timetable_con）。" +
                    "确认已经登录并且课表已经显示出来了再抓。"
            )
        }
        val out = ArrayList<Block>()
        for (td in TD.findAll(html)) {
            val weekday = td.groupValues[1].toIntOrNull() ?: continue
            if (weekday !in 1..7) continue
            for (div in DIV.findAll(td.groupValues[3])) {
                val body = div.groupValues[1]

                var name = TITLE.find(body)?.let { text(it.groupValues[1]) } ?: continue
                var kind = ""
                if (name.isNotEmpty() && name.last() in "★◇●○") {
                    kind = name.last().toString()
                    name = name.dropLast(1).trim()
                }
                if (name.isBlank()) continue

                // 按 tooltip 的 title 取字段，而不是按 <p> 顺序
                val fields = HashMap<String, String>()
                for (p in PARA.findAll(body)) {
                    val para = p.groupValues[1]
                    val key = TITLE_ATTR.find(para)?.groupValues?.get(1)?.trim() ?: continue
                    // 把 tooltip 那个 span 整段去掉，剩下的才是值
                    fields[key] = text(TOOLTIP_SPAN.replace(para, ""))
                }

                val m = PERIOD_WEEKS.find(fields["节/周"] ?: "") ?: continue
                val p1 = m.groupValues[1].toIntOrNull() ?: continue
                val p2 = m.groupValues[2].ifEmpty { m.groupValues[1] }.toIntOrNull() ?: p1
                val weeksRaw = m.groupValues[3].trim()
                val weeks = parseWeeks(weeksRaw)
                if (weeks.isEmpty()) continue

                val locFull = fields["上课地点"].orEmpty()
                out.add(
                    Block(
                        title = name,
                        kind = kind,
                        weekday = weekday,
                        startPeriod = p1,
                        endPeriod = maxOf(p1, p2),
                        weeks = weeks,
                        weeksRaw = weeksRaw,
                        location = locFull.split(' ').lastOrNull { it.isNotBlank() }.orEmpty(),
                        locationFull = locFull,
                        // 正方这里的 key 带个尾空格（"教师 "），两种都兜一下
                        teacher = (fields["教师"] ?: fields["教师 "]).orEmpty(),
                        credits = fields["学分"].orEmpty(),
                        classCode = fields["教学班名称"].orEmpty()
                    )
                )
            }
        }
        if (out.isEmpty()) {
            throw ParseException("找到了课表结构，但没解析出任何课程。把抓到的 HTML 发我看看。")
        }
        return out
    }

    fun maxWeek(blocks: List<Block>): Int = blocks.flatMap { it.weeks }.maxOrNull() ?: 0

    fun courseCount(blocks: List<Block>): Int = blocks.map { it.title }.distinct().size

    /**
     * 课程块 → 具体上课记录。
     *
     * @param termStart 第 1 周的周一。页面里没有这个信息，必须由外部给
     *                  （导入流程里问"今天是第几周"反推）。
     * @param periods   节次作息表，页面里同样没有，用 Timetable.periods。
     */
    fun toSessions(
        blocks: List<Block>,
        termStart: LocalDate,
        periods: List<PeriodSlot>
    ): List<Session> {
        val byIndex = periods.associateBy { it.index }
        val out = ArrayList<Session>()
        for (b in blocks) {
            val ps = byIndex[b.startPeriod] ?: continue
            val pe = byIndex[b.endPeriod] ?: ps
            val note = buildString {
                append("第 ${b.startPeriod}-${b.endPeriod} 节 · ${b.weeksRaw}")
                if (b.credits.isNotBlank()) append(" · 学分 ${b.credits}")
                if (b.locationFull.isNotBlank()) append("\n${b.locationFull}")
                if (b.classCode.isNotBlank()) append("\n教学班 ${b.classCode}")
            }
            for (w in b.weeks) {
                val date = termStart.plusWeeks((w - 1).toLong()).plusDays((b.weekday - 1).toLong())
                out.add(
                    Session(
                        id = newId(),
                        title = b.title,
                        location = b.location,
                        teacher = b.teacher,
                        note = note,
                        start = date.atMinuteOfDay(ps.startMin),
                        end = date.atMinuteOfDay(maxOf(pe.endMin, ps.startMin + 5))
                    )
                )
            }
        }
        return out.sortedBy { it.start }
    }
}
