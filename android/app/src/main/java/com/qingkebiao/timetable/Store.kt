package com.qingkebiao.timetable

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 课表就存一个 JSON 文件在 filesDir 里。
 * 用 Room 属于杀鸡用牛刀，而且小组件那边直接读同一个文件最省事 ——
 * 不需要 ContentProvider，也不需要跨进程同步。
 */
object Store {

    private const val FILE_NAME = "timetable.json"
    private const val HTML_NAME = "captured.html"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private fun file(ctx: Context) = File(ctx.filesDir, FILE_NAME)

    suspend fun load(ctx: Context): Timetable = withContext(Dispatchers.IO) {
        val f = file(ctx)
        if (!f.exists()) return@withContext Timetable()
        runCatching { json.decodeFromString<Timetable>(f.readText()) }.getOrElse { Timetable() }
    }

    suspend fun save(ctx: Context, tt: Timetable) = withContext(Dispatchers.IO) {
        // 先写临时文件再改名，避免写一半被杀进程留下坏 JSON
        val tmp = File(ctx.filesDir, "$FILE_NAME.tmp")
        tmp.writeText(json.encodeToString(tt))
        if (!tmp.renameTo(file(ctx))) {
            file(ctx).writeText(tmp.readText())
            tmp.delete()
        }
    }

    suspend fun clear(ctx: Context) = withContext(Dispatchers.IO) {
        file(ctx).delete()
        Unit
    }

    /** 读用户从系统文件选择器挑的 .ics。 */
    suspend fun readUri(ctx: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        ctx.contentResolver.openInputStream(uri)?.use { it.reader().readText() }
            ?: throw IOException("读不出这个文件，换一个试试。")
    }

    /**
     * 直接拉学校的订阅链接。这是原生相对网页版最大的优势 ——
     * 没有 CORS，学校服务器怎么配都能读。
     */
    suspend fun fetchIcs(rawUrl: String): String = withContext(Dispatchers.IO) {
        val normalized = rawUrl.trim().replace(Regex("^webcal://", RegexOption.IGNORE_CASE), "https://")
        val conn = (URL(normalized).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "text/calendar, text/plain, */*")
            setRequestProperty("User-Agent", "qingkebiao/1.0 (Android)")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("服务器返回 $code ${conn.responseMessage ?: ""}".trim())
            conn.inputStream.use { it.reader().readText() }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 把新解析出来的课程并进现有课表。
     *
     * 关键点：手动添加/改过的课（manual = true）一律保留，只替换导入来的部分。
     * 学期起始日、总周数、调休、节次时间这些用户设置也全部保留 ——
     * 重新拉一次课表不应该把这些手工调整清零。
     */
    suspend fun mergeImported(
        ctx: Context,
        parsed: List<Session>,
        sourceLabel: String,
        icsUrl: String = ""
    ): Timetable {
        val old = load(ctx)
        val manual = old.sessions.filter { it.manual }
        val merged = (parsed.map { if (it.id.isBlank()) it.copy(id = newId()) else it } + manual)
            .sortedBy { it.start }
        val firstImport = old.sessions.none { !it.manual }
        val hasWeekend = merged.any { it.start.toLocalDate().dayOfWeek.value >= 6 }

        val tt = old.copy(
            sessions = merged,
            sourceLabel = sourceLabel,
            icsUrl = if (icsUrl.isNotBlank()) icsUrl else old.icsUrl,
            // 只在第一次导入时自动决定要不要显示周末，之后尊重用户的选择
            showWeekend = if (firstImport) hasWeekend else old.showWeekend
        )
        save(ctx, tt)
        return tt
    }

    /** ICS 文本 → 课表。 */
    suspend fun importIcs(
        ctx: Context,
        text: String,
        sourceLabel: String,
        icsUrl: String = ""
    ): Timetable = mergeImported(ctx, Ics.parse(text), sourceLabel, icsUrl)

    /* ---------------- 抓来的教务系统网页 ---------------- */

    /** 存一份抓到的 HTML，用于导出给我调解析器。 */
    suspend fun saveCapturedHtml(ctx: Context, html: String) = withContext(Dispatchers.IO) {
        File(ctx.filesDir, HTML_NAME).writeText(html)
        Unit
    }

    suspend fun loadCapturedHtml(ctx: Context): String? = withContext(Dispatchers.IO) {
        val f = File(ctx.filesDir, HTML_NAME)
        if (f.exists() && f.length() > 0) f.readText() else null
    }

    /** 导出到 cacheDir，供系统分享菜单发出去。 */
    suspend fun exportCapturedHtml(ctx: Context): File? = withContext(Dispatchers.IO) {
        val html = loadCapturedHtml(ctx) ?: return@withContext null
        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        val out = File(dir, "jwxt-page.html")
        out.writeText(html)
        out
    }
}

/** 首次打开时给的示例课表，让人立刻看见小组件和周视图长什么样。 */
val DEMO_ICS: String = listOf(
    "BEGIN:VCALENDAR",
    "VERSION:2.0",
    "PRODID:-//qingkebiao//demo//CN",
    "BEGIN:VEVENT",
    "UID:demo-math@qkb",
    "SUMMARY:高等数学 A(二)",
    "LOCATION:教三-301",
    "DESCRIPTION:教师：李慧敏  学分：5.0",
    "DTSTART;TZID=Asia/Shanghai:20260907T080000",
    "DTEND;TZID=Asia/Shanghai:20260907T094000",
    "RRULE:FREQ=WEEKLY;COUNT=16",
    "END:VEVENT",
    "BEGIN:VEVENT",
    "UID:demo-phys@qkb",
    "SUMMARY:大学物理(上)",
    "LOCATION:教二-208",
    "DESCRIPTION:教师：陈立  学分：4.0",
    "DTSTART;TZID=Asia/Shanghai:20260907T140000",
    "DTEND;TZID=Asia/Shanghai:20260907T154000",
    "RRULE:FREQ=WEEKLY;COUNT=16",
    "END:VEVENT",
    "BEGIN:VEVENT",
    "UID:demo-ds@qkb",
    "SUMMARY:数据结构",
    "LOCATION:逸夫楼-402",
    "DESCRIPTION:教师：王锐  学分：4.0",
    "DTSTART;TZID=Asia/Shanghai:20260908T100000",
    "DTEND;TZID=Asia/Shanghai:20260908T114000",
    "RRULE:FREQ=WEEKLY;COUNT=16",
    "END:VEVENT",
    "BEGIN:VEVENT",
    "UID:demo-la@qkb",
    "SUMMARY:线性代数",
    "LOCATION:教三-105",
    "DESCRIPTION:教师：李慧敏  前八周结课",
    "DTSTART;TZID=Asia/Shanghai:20260909T080000",
    "DTEND;TZID=Asia/Shanghai:20260909T094000",
    "RRULE:FREQ=WEEKLY;COUNT=8",
    "END:VEVENT",
    "BEGIN:VEVENT",
    "UID:demo-marx@qkb",
    "SUMMARY:马克思主义基本原理",
    "LOCATION:教一-201 大教室",
    "DESCRIPTION:教师：周敏",
    "DTSTART;TZID=Asia/Shanghai:20260909T190000",
    "DTEND;TZID=Asia/Shanghai:20260909T204000",
    "RRULE:FREQ=WEEKLY;COUNT=16",
    "END:VEVENT",
    "BEGIN:VEVENT",
    "UID:demo-pe@qkb",
    "SUMMARY:体育(羽毛球)",
    "LOCATION:风雨体育馆",
    "DESCRIPTION:教师：张海",
    "DTSTART;TZID=Asia/Shanghai:20260910T080000",
    "DTEND;TZID=Asia/Shanghai:20260910T094000",
    "RRULE:FREQ=WEEKLY;COUNT=16",
    "END:VEVENT",
    "BEGIN:VEVENT",
    "UID:demo-dslab@qkb",
    "SUMMARY:数据结构实验",
    "LOCATION:计算机楼-机房3",
    "DESCRIPTION:教师：王锐  双周上课",
    "DTSTART;TZID=Asia/Shanghai:20260917T140000",
    "DTEND;TZID=Asia/Shanghai:20260917T163000",
    "RRULE:FREQ=WEEKLY;INTERVAL=2;COUNT=8",
    "END:VEVENT",
    "BEGIN:VEVENT",
    "UID:demo-eng@qkb",
    "SUMMARY:英语视听说",
    "LOCATION:外语楼-语音室2",
    "DESCRIPTION:教师：Sarah Wilson  国庆停课一次",
    "DTSTART;TZID=Asia/Shanghai:20260911T100000",
    "DTEND;TZID=Asia/Shanghai:20260911T114000",
    "RRULE:FREQ=WEEKLY;COUNT=16",
    "EXDATE;TZID=Asia/Shanghai:20261002T100000",
    "END:VEVENT",
    "BEGIN:VEVENT",
    "UID:demo-midterm@qkb",
    "SUMMARY:高等数学 期中考试",
    "LOCATION:教三-301",
    "DESCRIPTION:闭卷  带学生证",
    "DTSTART;TZID=Asia/Shanghai:20261102T190000",
    "DTEND;TZID=Asia/Shanghai:20261102T210000",
    "END:VEVENT",
    "END:VCALENDAR"
).joinToString("\r\n")
