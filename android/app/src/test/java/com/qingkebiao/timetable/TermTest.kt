package com.qingkebiao.timetable

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 多学期：起名、开新学期时清什么留什么、切换时哪些设置跟着人走。 */
class TermTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun lesson(date: LocalDate, manual: Boolean = false) = Session(
        id = newId(), title = "高等数学",
        start = date.atMinuteOfDay(8 * 60, zone),
        end = date.atMinuteOfDay(9 * 60 + 40, zone),
        manual = manual
    )

    @Test
    fun autoNameFollowsChineseAcademicYear() {
        assertEquals("2025-2026 第一学期", autoTermName(LocalDate.of(2025, 9, 1)))
        assertEquals("2025-2026 第一学期", autoTermName(LocalDate.of(2025, 8, 25)))
        // 一月开学的少见，但仍属于上一年秋季那个学年的第一学期
        assertEquals("2025-2026 第一学期", autoTermName(LocalDate.of(2026, 1, 5)))
        assertEquals("2025-2026 第二学期", autoTermName(LocalDate.of(2026, 2, 23)))
        assertEquals("2025-2026 第二学期", autoTermName(LocalDate.of(2026, 7, 6)))
    }

    @Test
    fun userNameWinsOverAutoName() {
        val tt = Timetable(
            sessions = listOf(lesson(LocalDate.of(2025, 9, 1))),
            termStartEpochDay = LocalDate.of(2025, 9, 1).toEpochDay()
        )
        assertEquals("2025-2026 第一学期", tt.termTitle(zone))
        assertEquals("大二上", tt.copy(termName = "大二上").termTitle(zone))
        assertEquals("新学期", Timetable().termTitle(zone))
    }

    @Test
    fun freshTermDropsCoursesButKeepsHabits() {
        val periods = listOf(PeriodSlot(1, 8 * 60 + 10, 8 * 60 + 55))
        val old = Timetable(
            sessions = listOf(lesson(LocalDate.of(2025, 9, 1)), lesson(LocalDate.of(2025, 9, 2), manual = true)),
            termStartEpochDay = LocalDate.of(2025, 9, 1).toEpochDay(),
            termWeeks = 18,
            overrides = listOf(DayOverride(LocalDate.of(2025, 10, 1).toEpochDay(), OverrideKind.HOLIDAY)),
            periods = periods,
            periodsSource = "manual",
            jwxtHome = "https://jwxt.example.edu.cn",
            remindEnabled = true,
            remindMinutes = 20,
            updateUrl = "example.pages.dev",
            termId = "old",
            termName = "大二上",
            lastSyncEpochDay = 100
        )
        val fresh = old.freshTerm()

        // 学期本身的东西全清：包括手动加的课和调休，那是上学期的
        assertTrue(fresh.sessions.isEmpty())
        assertTrue(fresh.overrides.isEmpty())
        assertTrue(fresh.zfBlocks.isEmpty())
        assertEquals(null, fresh.termStartEpochDay)
        assertEquals(null, fresh.termWeeks)
        assertEquals(null, fresh.lastSyncEpochDay)
        assertEquals("", fresh.termName)
        assertNotEquals("old", fresh.termId)
        assertTrue(fresh.termId.isNotBlank())

        // 人的习惯留着：同一所学校作息不变，提醒和更新地址不该被重置
        assertEquals(periods, fresh.periods)
        assertEquals("manual", fresh.periodsSource)
        assertEquals("https://jwxt.example.edu.cn", fresh.jwxtHome)
        assertTrue(fresh.remindEnabled)
        assertEquals(20, fresh.remindMinutes)
        assertEquals("example.pages.dev", fresh.updateUrl)
    }

    @Test
    fun switchingBackKeepsCurrentGlobalSettings() {
        // 历史里那份是上学期存的，那时候提醒还没开
        val archived = Timetable(
            sessions = listOf(lesson(LocalDate.of(2025, 3, 3))),
            remindEnabled = false, remindMinutes = 15, updateUrl = "old.pages.dev",
            termId = "t1", termName = "大一下"
        )
        val current = Timetable(remindEnabled = true, remindMinutes = 30, updateUrl = "new.pages.dev")
        val next = archived.withGlobalsFrom(current)

        assertTrue(next.remindEnabled)
        assertEquals(30, next.remindMinutes)
        assertEquals("new.pages.dev", next.updateUrl)
        // 学期自己的东西不动
        assertEquals("t1", next.termId)
        assertEquals("大一下", next.termName)
        assertEquals(archived.sessions, next.sessions)
    }

    @Test
    fun demoAndEmptyTimetablesAreNotArchived() {
        assertFalse(Timetable().worthArchiving())
        val demo = Timetable(sessions = listOf(lesson(LocalDate.of(2025, 9, 1))), sourceLabel = "示例课表")
        assertFalse(demo.worthArchiving())
        assertTrue(demo.copy(sourceLabel = "教务系统网页").worthArchiving())
    }

    @Test
    fun infoSpansFirstToLastLesson() {
        val tt = Timetable(
            sessions = listOf(
                lesson(LocalDate.of(2025, 12, 29)),
                lesson(LocalDate.of(2025, 9, 1)),
                lesson(LocalDate.of(2025, 10, 8))
            )
        )
        val i = tt.info(zone)
        assertEquals(LocalDate.of(2025, 9, 1), i.first)
        assertEquals(LocalDate.of(2025, 12, 29), i.last)
        assertEquals(3, i.count)
    }
}
