package com.qingkebiao.timetable

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 周次计算、调休、布局这些纯逻辑的单测。
 *
 * 时区固定成 Asia/Shanghai —— 课程时间存的是绝对毫秒，
 * 不固定的话换台机器跑结果就不一样。
 */
private val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

/** 2026-08-31 是周一，拿它当第 1 周的起点 */
private val TERM_START = LocalDate.of(2026, 8, 31)

private fun session(
    date: LocalDate, startMin: Int, endMin: Int,
    title: String = "课", manual: Boolean = false, id: String = newId()
) = Session(
    id = id, title = title, location = "", teacher = "", note = "",
    start = date.atMinuteOfDay(startMin, ZONE),
    end = date.atMinuteOfDay(endMin, ZONE),
    manual = manual
)

private fun tt(vararg s: Session) = Timetable(
    sessions = s.toList(),
    termStartEpochDay = TERM_START.toEpochDay(),
    termWeeks = 17
)

class WeekMathTest {

    private val d = Derived(tt(session(TERM_START, 480, 525)), ZONE)

    @Test fun `第一周就是第 1 周`() {
        assertEquals(1, d.weekOf(TERM_START))
        assertEquals(1, d.weekOf(TERM_START.plusDays(6)))
    }

    @Test fun `第二周从第七天开始`() {
        assertEquals(2, d.weekOf(TERM_START.plusDays(7)))
    }

    /** 用 / 而不是 floorDiv 的话，开学前一周会被算成第 1 周 */
    @Test fun `开学之前是第零周和负数周`() {
        assertEquals(0, d.weekOf(TERM_START.minusDays(1)))
        assertEquals(0, d.weekOf(TERM_START.minusDays(7)))
        assertEquals(-1, d.weekOf(TERM_START.minusDays(8)))
    }

    @Test fun `某周的周一`() {
        assertEquals(TERM_START.plusDays(14), d.mondayOfWeek(3))
    }

    @Test fun `周次压缩成课表写法`() {
        assertEquals("1-8", compressWeeks(listOf(1, 2, 3, 4, 5, 6, 7, 8)))
        assertEquals("1-3,5-17", compressWeeks((1..3).toList() + (5..17)))
        assertEquals("2,4,6", compressWeeks(listOf(2, 4, 6)))
        assertEquals("", compressWeeks(emptyList()))
    }
}

class OverrideTest {

    private val mon = TERM_START              // 周一，有课
    private val sat = TERM_START.plusDays(5)  // 周六，没课

    private val base = tt(
        session(mon, 480, 525, "高数"),
        session(mon, 600, 645, "英语")
    )

    @Test fun `放假那天原本的课都不上`() {
        val t = base.copy(overrides = listOf(DayOverride(mon.toEpochDay(), OverrideKind.HOLIDAY)))
        assertTrue(Derived(t, ZONE).sessionsOn(mon).isEmpty())
    }

    @Test fun `放假那天手动加的事件还在`() {
        val t = base.copy(
            sessions = base.sessions + session(mon, 800, 840, "期末考试", manual = true),
            overrides = listOf(DayOverride(mon.toEpochDay(), OverrideKind.HOLIDAY))
        )
        val left = Derived(t, ZONE).sessionsOn(mon)
        assertEquals(1, left.size)
        assertEquals("期末考试", left[0].title)
    }

    @Test fun `调休把另一天的课搬过来且时刻不变`() {
        val t = base.copy(
            overrides = listOf(
                DayOverride(sat.toEpochDay(), OverrideKind.FOLLOW, followEpochDay = mon.toEpochDay())
            )
        )
        val onSat = Derived(t, ZONE).sessionsOn(sat)
        assertEquals(2, onSat.size)
        assertEquals("高数", onSat[0].title)
        assertEquals(480, onSat[0].startMinute(ZONE))
        assertEquals(sat, onSat[0].start.toLocalDate(ZONE))
    }

    /** 调休必须影响"下一节"，不然提醒和小组件都会指错 */
    @Test fun `放假之后下一节不能还指着那天`() {
        val t = base.copy(overrides = listOf(DayOverride(mon.toEpochDay(), OverrideKind.HOLIDAY)))
        val justBefore = mon.atMinuteOfDay(400, ZONE)
        assertNull(Derived(t, ZONE).next(justBefore))
    }

    @Test fun `没调休的时候下一节正常`() {
        val justBefore = mon.atMinuteOfDay(400, ZONE)
        val n = Derived(base, ZONE).next(justBefore)
        assertNotNull(n)
        assertEquals("高数", n!!.title)
    }

    @Test fun `正在上的课算进行中`() {
        val during = mon.atMinuteOfDay(500, ZONE)
        assertEquals("高数", Derived(base, ZONE).current(during)?.title)
    }
}

class LayoutTest {

    private val day = TERM_START

    @Test fun `不重叠的课各占满宽`() {
        val placed = layoutDay(listOf(session(day, 480, 525), session(day, 600, 645)))
        assertEquals(2, placed.size)
        assertTrue(placed.all { it.cols == 1 })
    }

    @Test fun `重叠的课并排分列`() {
        val placed = layoutDay(listOf(session(day, 480, 600), session(day, 500, 620)))
        assertEquals(2, placed.size)
        assertTrue(placed.all { it.cols == 2 })
        assertEquals(setOf(0, 1), placed.map { it.col }.toSet())
    }

    @Test fun `时间轴刻度取课的起止而不是整点`() {
        val marks = timeMarks(listOf(session(day, 490, 585)), 8 * 60, 12 * 60, ZONE)
        val mins = marks.map { it.minute }
        assertTrue(mins.contains(490))
        assertTrue(mins.contains(585))
        assertTrue(marks.first { it.minute == 490 }.isStart)
        assertFalse(marks.first { it.minute == 585 }.isStart)
    }

    @Test fun `没有课时退回整点刻度`() {
        val marks = timeMarks(emptyList(), 8 * 60, 10 * 60, ZONE)
        assertEquals(listOf(480, 540, 600), marks.map { it.minute })
    }
}

class ArrangementTest {

    /** 一门课周一在 A 楼、周四在 B 楼：地点和时间必须成对，不能各列一串 */
    @Test fun `同名课的不同安排要分开`() {
        val mon = TERM_START
        val thu = TERM_START.plusDays(3)
        val t = tt(
            session(mon, 490, 585, "大学物理").copy(location = "翔宇楼503"),
            session(mon.plusDays(7), 490, 585, "大学物理").copy(location = "翔宇楼503"),
            session(thu, 810, 905, "大学物理").copy(location = "翔宇楼401")
        )
        val arr = Derived(t, ZONE).arrangementsOf("大学物理")
        assertEquals(2, arr.size)
        val a1 = arr.first { it.weekday == 1 }
        assertEquals("翔宇楼503", a1.location)
        assertEquals(2, a1.count)
        val a4 = arr.first { it.weekday == 4 }
        assertEquals("翔宇楼401", a4.location)
        assertEquals(1, a4.count)
    }

    @Test fun `节次标注对得上作息表才显示`() {
        val ps = listOf(PeriodSlot(1, 490, 535), PeriodSlot(2, 540, 585))
        assertEquals("第1-2节", periodLabel(ps, 490, 585))
        assertEquals("第1节", periodLabel(ps, 490, 535))
        assertEquals("", periodLabel(ps, 123, 456))
    }
}

class IcsTest {

    @Test fun `按周重复并扣掉 EXDATE`() {
        val ics = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            SUMMARY:英语
            DTSTART:20260831T080000
            DTEND:20260831T094500
            RRULE:FREQ=WEEKLY;COUNT=5
            EXDATE:20260914T080000
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        val list = Ics.parse(ics)
        assertEquals(4, list.size)
        assertEquals("英语", list[0].title)
    }

    @Test fun `隔周重复`() {
        val ics = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            SUMMARY:实验
            DTSTART:20260831T080000
            DTEND:20260831T094500
            RRULE:FREQ=WEEKLY;INTERVAL=2;COUNT=3
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        val list = Ics.parse(ics).sortedBy { it.start }
        assertEquals(3, list.size)
        val d0 = list[0].start.toLocalDate(ZONE)
        val d1 = list[1].start.toLocalDate(ZONE)
        assertEquals(14, d1.toEpochDay() - d0.toEpochDay())
    }
}
