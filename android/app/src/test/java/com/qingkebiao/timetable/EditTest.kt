package com.qingkebiao.timetable

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.TimeZone

/**
 * 删掉 / 改掉导入的课之后，重算课表（改作息、改开学日期、查调课）不能把它们生成回来。
 *
 * 实际踩到的：第 6 周删了一节、改了一节的时间，去设置里点了一下"保存并重算课表"，
 * 删掉的那节回来了，改过的那节旁边又多出一节原时间的。
 */
class EditTest {

    private val start = LocalDate.of(2026, 8, 31)

    private fun block(title: String, wd: Int, p1: Int, p2: Int, weeks: IntRange = 1..16) = Zf.Block(
        title = title, kind = "", weekday = wd, startPeriod = p1, endPeriod = p2,
        weeks = weeks.toList(), weeksRaw = "${weeks.first}-${weeks.last}周",
        location = "教三-201", locationFull = "教三-201", teacher = "王老师", credits = "", classCode = "C$title"
    )

    private val blocks = listOf(block("高等数学", 1, 1, 2), block("大学英语", 3, 3, 4))

    private fun fresh(): Timetable {
        val s = Zf.toSessions(blocks, start, DEFAULT_PERIODS)
        return Timetable(sessions = s, termStartEpochDay = start.toEpochDay(), zfBlocks = blocks, periods = DEFAULT_PERIODS)
    }

    private fun Timetable.on(day: LocalDate, title: String) =
        sessions.filter { it.title == title && it.start.toLocalDate() == day }

    @Before fun tz() { TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai")) }

    @Test fun `导入的每一节都有稳定身份`() {
        val a = fresh().sessions
        val b = fresh().sessions
        assertTrue(a.all { it.origin.isNotBlank() })
        // id 每次都是新的，origin 不变
        assertEquals(a.map { it.origin }.toSet(), b.map { it.origin }.toSet())
        assertEquals(a.size, a.map { it.origin }.toSet().size)
    }

    @Test fun `删掉的课重算后不会回来`() {
        val tt = fresh()
        val wed6 = start.plusWeeks(5).plusDays(2)
        val victim = tt.on(wed6, "大学英语").single()
        val deleted = tt.copy(
            sessions = tt.sessions - victim,
            suppressed = listOf(victim.origin)
        )
        val again = Store.recomputeFromBlocks(deleted)
        assertTrue(again.on(wed6, "大学英语").isEmpty())
        // 其他周的照常在
        assertEquals(1, again.on(wed6.plusWeeks(1), "大学英语").size)
        assertEquals(tt.sessions.size - 1, again.sessions.size)
    }

    @Test fun `改过时间的课重算后不会多出一节原时间的`() {
        val tt = fresh()
        val wed6 = start.plusWeeks(5).plusDays(2)
        val orig = tt.on(wed6, "大学英语").single()
        val moved = orig.copy(start = wed6.atMinuteOfDay(10 * 60 + 35), end = wed6.atMinuteOfDay(12 * 60 + 10), manual = true)
        val edited = tt.copy(
            sessions = tt.sessions.map { if (it.id == orig.id) moved else it },
            suppressed = listOf(orig.origin)
        )
        val again = Store.recomputeFromBlocks(edited)
        val left = again.on(wed6, "大学英语")
        assertEquals(1, left.size)
        assertEquals(10 * 60 + 35, left[0].startMinute())
    }

    /** 老版本（v30 及以前）的数据没有 origin，删过改过也没记。读进来时要补上 */
    @Test fun `老数据补上身份并认出以前删改过的课`() {
        val tt = fresh()
        val mon3 = start.plusWeeks(2)
        val wed5 = start.plusWeeks(4).plusDays(2)
        val gone = tt.on(mon3, "高等数学").single()
        val orig = tt.on(wed5, "大学英语").single()
        val old = tt.copy(
            sessions = tt.sessions.filter { it.id != gone.id }.map {
                if (it.id == orig.id) it.copy(start = it.start + 30 * 60_000, end = it.end + 30 * 60_000, manual = true)
                else it
            }.map { it.copy(origin = "") }
        )
        val fixed = Store.backfillOrigins(old)
        assertTrue(fixed.sessions.filter { !it.manual }.all { it.origin.isNotBlank() })
        assertEquals(setOf(gone.origin, orig.origin), fixed.suppressed.toSet())

        val again = Store.recomputeFromBlocks(fixed)
        assertTrue(again.on(mon3, "高等数学").isEmpty())
        assertEquals(1, again.on(wed5, "大学英语").size)
    }

    /** 现有课表压根对不上课程块（比如作息改过但没重算），这时候宁可不认，也不能乱藏课 */
    @Test fun `对不上的老数据不乱记`() {
        val tt = fresh()
        val shifted = tt.copy(sessions = tt.sessions.map {
            it.copy(start = it.start + 10 * 60_000, end = it.end + 10 * 60_000, origin = "")
        })
        val fixed = Store.backfillOrigins(shifted)
        assertTrue(fixed.suppressed.isEmpty())
        assertEquals(tt.sessions.size, Store.recomputeFromBlocks(fixed).sessions.size)
    }

    @Test fun `已经有身份的数据不重复处理`() {
        val tt = fresh().copy(suppressed = listOf("x"))
        assertEquals(tt, Store.backfillOrigins(tt))
    }

    @Test fun `同一种安排在各周的那几节`() {
        val tt = fresh()
        val d = Derived(tt, ZoneId.of("Asia/Shanghai"))
        val one = tt.on(start.plusWeeks(5), "高等数学").single()
        val sib = d.siblingsOf(one)
        assertEquals(16, sib.size)
        assertTrue(sib.all { it.title == "高等数学" && it.startMinute() == one.startMinute() })
        assertFalse(sib.any { it.title == "大学英语" })
    }

    @Test fun `改过一周的时间后它就不再算同一种安排`() {
        val tt = fresh()
        val mon6 = start.plusWeeks(5)
        val one = tt.on(mon6, "高等数学").single()
        val moved = one.copy(start = one.start + 30 * 60_000, end = one.end + 30 * 60_000, manual = true)
        val d = Derived(tt.copy(sessions = tt.sessions.map { if (it.id == one.id) moved else it }), ZoneId.of("Asia/Shanghai"))
        assertEquals(15, d.siblingsOf(tt.on(mon6.plusWeeks(1), "高等数学").single()).size)
        assertEquals(1, d.siblingsOf(moved).size)
    }
}
