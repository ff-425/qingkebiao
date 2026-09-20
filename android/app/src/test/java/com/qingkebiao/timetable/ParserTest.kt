package com.qingkebiao.timetable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解析器的单测。
 *
 * 这些全是纯逻辑，跑在 JVM 上，不需要模拟器。
 * 覆盖的都是真实踩过的坑，每一条后面都有一次线上翻车：
 *  - 周次里的单双周、逗号分段
 *  - OCR 把"周"字吃掉或认成数字
 *  - 课程名被"教三-201"顶掉
 *  - Excel 里中文是 &#26143; 这种数字实体
 *  - 调休要影响"下一节"
 */
class ZfWeeksTest {

    @Test fun `连续周次`() {
        assertEquals((1..16).toList(), Zf.parseWeeks("1-16周"))
    }

    @Test fun `逗号分段`() {
        assertEquals((1..3) + (5..17), Zf.parseWeeks("1-3周,5-17周"))
    }

    @Test fun `单周`() {
        assertEquals(listOf(3, 5, 7, 9, 11, 13, 15), Zf.parseWeeks("(单)3-15周"))
    }

    @Test fun `双周`() {
        assertEquals(listOf(2, 4, 6, 8), Zf.parseWeeks("(双)1-9周"))
    }

    @Test fun `单个周次`() {
        assertEquals(listOf(12), Zf.parseWeeks("12周"))
    }

    @Test fun `离散周次`() {
        assertEquals(listOf(8, 14), Zf.parseWeeks("8周,14周"))
    }

    @Test fun `离谱的周次直接丢掉`() {
        assertTrue(Zf.parseWeeks("99-200周").isEmpty())
        assertTrue(Zf.parseWeeks("").isEmpty())
    }
}

class GenericGridTest {

    /** 一格里四行：课程名 / 周次 / 地点 / 老师 —— 最常见的排法 */
    private fun cell(vararg lines: String) = lines.joinToString("\n")

    private val rows = listOf(
        listOf("", "节次", "星期一", "星期二", "星期三"),
        listOf("上午", "1", cell("高等数学", "1-16周", "教三-201", "王伟"), "", cell("大学英语", "1-16周", "外语楼-305", "李静")),
        listOf("上午", "2", "", cell("程序设计", "1-16周", "实验楼-机房3", "赵磊"), "")
    )

    @Test fun `能认出星期表头和节次`() {
        val blocks = Generic.parseRows(rows)
        assertEquals(3, blocks.size)
        val mon = blocks.first { it.weekday == 1 }
        assertEquals(1, mon.startPeriod)
        assertEquals(3, blocks.first { it.title == "大学英语" }.weekday)
    }

    /**
     * 课程名曾经取"剩下几行里最长的"，结果选中了 "教三-201"（7 字）
     * 而不是 "高等数学"（4 字）。现在取第一行。
     */
    @Test fun `课程名取第一行而不是最长的一行`() {
        val b = Generic.parseRows(rows).first { it.weekday == 1 }
        assertEquals("高等数学", b.title)
        assertEquals("教三-201", b.location)
        assertEquals("王伟", b.teacher)
    }

    @Test fun `周次能解出来`() {
        val b = Generic.parseRows(rows).first { it.weekday == 1 }
        assertEquals((1..16).toList(), b.weeks)
    }

    @Test fun `没有星期表头就明确报错`() {
        val bad = listOf(listOf("a", "b"), listOf("c", "d"))
        try {
            Generic.parseRows(bad)
            throw AssertionError("应该抛异常")
        } catch (e: Generic.ParseException) {
            assertTrue(e.message!!.contains("星期"))
        }
    }

    /**
     * OCR 常把 "1-16周" 的周吃掉变成 "1-16"，"1-12周" 认成 "1-123"。
     * loose 模式要能救回来；非 loose 模式不该乱猜。
     */
    @Test fun `OCR 掉字的周次在 loose 模式下能还原`() {
        val ocrRows = listOf(
            listOf("节次", "星期一", "星期二", "星期三"),
            listOf("1", cell("高等数学", "1-16", "教三-201", "王伟"), "", cell("马克思主义", "1-123", "教一-501", "孙敏"))
        )
        val loose = Generic.parseRows(ocrRows, loose = true)
        assertEquals((1..16).toList(), loose.first { it.title == "高等数学" }.weeks)
        assertEquals((1..12).toList(), loose.first { it.title == "马克思主义" }.weeks)

        // 非 loose：认不出周次就退回默认全学期，而且不能把 "1-16" 当成教室
        val strict = Generic.parseRows(ocrRows, defaultWeeks = 18, loose = false)
        assertEquals((1..18).toList(), strict.first { it.title == "高等数学" }.weeks)
    }

    @Test fun `数字加横杠不能被当成教室`() {
        val ocrRows = listOf(
            listOf("节次", "星期一", "星期二", "星期三"),
            listOf("1", cell("高等数学", "1-16", "教三-201", "王伟"), "", "")
        )
        val b = Generic.parseRows(ocrRows, loose = true).first()
        assertEquals("教三-201", b.location)
    }
}

class SheetsTest {

    @Test fun `CSV 基本解析`() {
        val rows = Sheets.readCsv("a,b,c\n1,2,3")
        assertEquals(listOf(listOf("a", "b", "c"), listOf("1", "2", "3")), rows)
    }

    @Test fun `CSV 引号里的逗号和换行`() {
        val rows = Sheets.readCsv("\"高数\n1-16周\",教三-201\nx,y")
        assertEquals("高数\n1-16周", rows[0][0])
        assertEquals("教三-201", rows[0][1])
        assertEquals(2, rows.size)
    }

    @Test fun `CSV 双引号转义`() {
        assertEquals(listOf(listOf("说\"这\"")), Sheets.readCsv("\"说\"\"这\"\"\""))
    }
}

class PeriodsTest {

    @Test fun `从表格里嗅探作息`() {
        val html = """
            <table>
              <tr><td>第1节</td><td>08:10</td><td>08:55</td></tr>
              <tr><td>第2节</td><td>09:00</td><td>09:45</td></tr>
              <tr><td>第3节</td><td>10:05</td><td>10:50</td></tr>
            </table>
        """.trimIndent()
        val ps = Periods.sniff(html)
        assertNotNull(ps)
        assertEquals(3, ps!!.size)
        assertEquals(8 * 60 + 10, ps[0].startMin)
        assertEquals(8 * 60 + 55, ps[0].endMin)
    }

    /** 宁可没有，也不要一张半对的表 —— 不足三条、或者节次不从 1 开始都不收 */
    @Test fun `不可信的就返回 null`() {
        assertNull(Periods.sniff("<p>第1节 08:10 08:55</p>"))
        assertNull(Periods.sniff("随便一段没有时间的文字"))
    }

    @Test fun `按上午下午晚上的分组推算`() {
        val ps = Periods.generate(listOf("上午" to 5, "下午" to 4, "晚上" to 3))
        assertEquals(12, ps.size)
        assertEquals(1, ps.first().index)
        assertEquals(12, ps.last().index)
        // 每段的起点各自独立，不是一路顺下来
        assertTrue(ps[5].startMin > ps[4].endMin)
    }
}

class DiffTest {

    private fun block(
        title: String, weekday: Int, p1: Int, p2: Int,
        loc: String, weeks: String = "1-16周", code: String = ""
    ) = Zf.Block(
        title = title, kind = "", weekday = weekday, startPeriod = p1, endPeriod = p2,
        weeks = Zf.parseWeeks(weeks), weeksRaw = weeks,
        location = loc, locationFull = loc, teacher = "王伟", credits = "", classCode = code
    )

    @Test fun `没变就是没变`() {
        val a = listOf(block("高数", 1, 1, 2, "教三-201"))
        assertTrue(Diff.compare(a, a).isEmpty())
    }

    /** 挪节次要报"调整"，不能报成"删一个加一个" —— 后者读起来完全不知道发生了什么 */
    @Test fun `换节次算调整不算增删`() {
        val old = listOf(block("信号与系统", 3, 1, 2, "A604", code = "X-01"))
        val new = listOf(block("信号与系统", 3, 3, 4, "A604", code = "X-01"))
        val cs = Diff.compare(old, new)
        assertEquals(1, cs.size)
        assertEquals(Diff.Kind.MOVED, cs[0].kind)
        assertTrue(cs[0].before.contains("1-2节"))
        assertTrue(cs[0].after.contains("3-4节"))
    }

    @Test fun `换教室也算调整`() {
        val old = listOf(block("大学物理", 1, 1, 2, "翔宇楼503", code = "P-01"))
        val new = listOf(block("大学物理", 1, 1, 2, "翔宇楼999", code = "P-01"))
        val cs = Diff.compare(old, new)
        assertEquals(Diff.Kind.MOVED, cs[0].kind)
        assertTrue(cs[0].after.contains("翔宇楼999"))
    }

    @Test fun `新增和取消`() {
        val old = listOf(block("石窟艺术", 4, 10, 11, "翔宇楼305", code = "S-01"))
        val new = listOf(block("音乐鉴赏", 2, 10, 11, "翔宇楼502", code = "M-01"))
        val cs = Diff.compare(old, new)
        assertEquals(2, cs.size)
        assertEquals(1, cs.count { it.kind == Diff.Kind.ADDED })
        assertEquals(1, cs.count { it.kind == Diff.Kind.REMOVED })
    }

    @Test fun `总结是人话`() {
        assertEquals("和现在的课表一样，没有变动", Diff.summarize(emptyList()))
        val cs = listOf(
            Diff.Change(Diff.Kind.MOVED, "a", "x", "y"),
            Diff.Change(Diff.Kind.ADDED, "b", "", "y")
        )
        assertEquals("1 处调整、新增 1 处", Diff.summarize(cs))
    }
}
