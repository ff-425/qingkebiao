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
import java.time.LocalDateTime
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
    private const val CORRUPT_PREFIX = "timetable.corrupt-"

    /**
     * 上次 [load] 撞见读不出来的数据文件时的错误信息，界面读它来提示用户。
     * 只是提示，不影响任何逻辑，所以放个可变字段就够了。
     */
    @Volatile
    var lastRecovery: String? = null

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private fun file(ctx: Context) = File(ctx.filesDir, FILE_NAME)

    /** 先写临时文件再改名，避免写一半被杀进程留下坏 JSON。 */
    private fun writeAtomic(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.writeText(tmp.readText())
            tmp.delete()
        }
    }

    suspend fun load(ctx: Context): Timetable = withContext(Dispatchers.IO) {
        val f = file(ctx)
        if (!f.exists()) return@withContext Timetable()
        val text = runCatching { f.readText() }.getOrNull()
        if (text.isNullOrBlank()) return@withContext Timetable()
        runCatching { json.decodeFromString<Timetable>(text) }.getOrElse { e ->
            // 读不出来就把原文件挪到一边留着。
            // 直接返回空课表是不行的：界面拿到空的，下一次保存就把它盖掉了，
            // 用户手动加的课、调休记录、改过的作息就永久没了，而且全程没有任何提示。
            // 课表本身重新导入一分钟就有，这些东西教务系统里没有。
            quarantine(ctx, f)
            lastRecovery = e.message ?: e.toString()
            Timetable()
        }
    }

    private fun quarantine(ctx: Context, f: File) {
        val stamp = LocalDateTime.now().toString().take(19).replace(':', '-')
        val dest = File(ctx.filesDir, "$CORRUPT_PREFIX$stamp.json")
        if (!f.renameTo(dest)) {
            runCatching {
                dest.writeText(f.readText())
                f.delete()
            }
        }
    }

    /** 隔离起来的坏文件，新的在前。设置里可以导出或删掉。 */
    fun corruptFiles(ctx: Context): List<File> =
        ctx.filesDir.listFiles { it: File -> it.name.startsWith(CORRUPT_PREFIX) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    suspend fun save(ctx: Context, tt: Timetable) = withContext(Dispatchers.IO) {
        writeAtomic(file(ctx), json.encodeToString(tt))
    }

    /* ---------------- 多学期 ----------------
     *
     * 当前学期还是 timetable.json，一个字没变 —— 小组件、上课提醒、查调课
     * 读的都是它，完全不用知道"学期"这回事。
     * 历史学期各自一个文件放在 terms/ 下面，文件内容就是一份完整的 Timetable。
     * 切换学期 = 把当前这份存进 terms/，再把选中的那份写成 timetable.json。
     */

    private const val TERMS_DIR = "terms"

    private fun termsDir(ctx: Context) = File(ctx.filesDir, TERMS_DIR).apply { mkdirs() }

    private fun termFile(ctx: Context, id: String) = File(termsDir(ctx), "$id.json")

    /**
     * 把一份课表存进历史。示例课表、空课表不存。
     * 存的时候把自动起的名字定下来 —— 不然以后改了开学日期，名字会跟着变。
     */
    private fun archive(ctx: Context, tt: Timetable) {
        if (!tt.worthArchiving()) return
        val named = tt.copy(
            termId = tt.termId.ifBlank { newId() },
            termName = tt.termTitle()
        )
        writeAtomic(termFile(ctx, named.termId), json.encodeToString(named))
    }

    /** 历史学期，新的在前。当前学期不在里面。 */
    suspend fun listTerms(ctx: Context): List<Timetable> = withContext(Dispatchers.IO) {
        val activeId = load(ctx).termId
        (termsDir(ctx).listFiles { f: File -> f.name.endsWith(".json") } ?: emptyArray())
            .mapNotNull { f -> runCatching { json.decodeFromString<Timetable>(f.readText()) }.getOrNull() }
            // 切换到一半被杀进程，可能当前和历史里各有一份同 id 的，以当前为准
            .filter { it.termId.isNotBlank() && it.termId != activeId }
            .sortedByDescending { t -> t.sessions.minOfOrNull { it.start } ?: 0L }
    }

    /**
     * 切到历史里的某个学期。当前这份先存进历史，再换上选中的那份。
     * 顺序是故意的：任何一步被打断，两份数据都至少还有一处留着。
     */
    suspend fun switchTerm(ctx: Context, id: String): Timetable = withContext(Dispatchers.IO) {
        val f = termFile(ctx, id)
        val target = runCatching { json.decodeFromString<Timetable>(f.readText()) }.getOrElse {
            throw IOException("这个学期的数据读不出来了（${it.message ?: "格式不对"}）。")
        }
        val cur = load(ctx)
        archive(ctx, cur)
        val next = target.withGlobalsFrom(cur)
        save(ctx, next)
        f.delete()
        next
    }

    /** 当前学期存进历史，换上一份空白的新学期（手动一节一节加的人用）。 */
    suspend fun startBlankTerm(ctx: Context): Timetable = withContext(Dispatchers.IO) {
        val cur = load(ctx)
        archive(ctx, cur)
        val fresh = cur.freshTerm()
        save(ctx, fresh)
        fresh
    }

    suspend fun deleteTerm(ctx: Context, id: String) = withContext(Dispatchers.IO) {
        termFile(ctx, id).delete()
        Unit
    }

    suspend fun renameTerm(ctx: Context, id: String, name: String) = withContext(Dispatchers.IO) {
        val f = termFile(ctx, id)
        val tt = json.decodeFromString<Timetable>(f.readText())
        writeAtomic(f, json.encodeToString(tt.copy(termName = name.trim())))
    }

    suspend fun clear(ctx: Context) = withContext(Dispatchers.IO) {
        file(ctx).delete()
        Unit
    }

    /**
     * 备份 / 恢复。
     *
     * 数据只在这台手机上，所以换手机、卸载重装、系统抽风，都可能一次性全没。
     * 导出的就是内部那个 JSON 原文，没有额外格式 —— 恢复的时候原样写回去，
     * 中间不做任何转换，也就不可能转丢。
     */
    /**
     * 有了多学期之后，备份把历史学期也一起带上。
     * 格式是 { active: 当前课表, terms: [历史学期…] }；老版本导出的是一份裸 Timetable，
     * 恢复时两种都认。
     */
    @kotlinx.serialization.Serializable
    private data class Backup(
        val version: Int = 2,
        val active: Timetable,
        val terms: List<Timetable> = emptyList()
    )

    /** 返回（当前学期几节课，历史学期几个）。 */
    suspend fun exportTo(ctx: Context, uri: Uri): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val tt = load(ctx)
        val terms = listTerms(ctx)
        val text = json.encodeToString(Backup(active = tt, terms = terms))
        ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
            ?: throw IOException("写不了这个位置，换个文件夹试试。")
        tt.sessions.size to terms.size
    }

    suspend fun restoreFrom(ctx: Context, uri: Uri): Timetable = withContext(Dispatchers.IO) {
        val text = ctx.contentResolver.openInputStream(uri)?.use { it.reader().readText() }
            ?: throw IOException("读不出这个文件。")
        // 先解析，解析得动才覆盖 —— 不能拿一个坏文件把好数据盖掉。
        // 新格式带 active 字段；老格式是一份裸课表，按新格式解析会因为缺 active 失败。
        val backup = runCatching { json.decodeFromString<Backup>(text) }.getOrNull()
            ?: runCatching { Backup(active = json.decodeFromString<Timetable>(text)) }.getOrElse {
                throw IOException("这不像清课表的备份文件（${it.message ?: "格式不对"}）。")
            }
        val tt = backup.active
        if (tt.sessions.isEmpty() && tt.zfBlocks.isEmpty() && backup.terms.isEmpty()) {
            throw IOException("这个备份里没有课程，没必要恢复。")
        }
        save(ctx, tt)
        // 历史学期按 id 写回去，同 id 的覆盖，手机上已有的其他学期不动
        backup.terms.filter { it.termId.isNotBlank() && it.termId != tt.termId }.forEach {
            writeAtomic(termFile(ctx, it.termId), json.encodeToString(it))
        }
        tt
    }

    /** 建议的备份文件名，带日期，免得一堆同名文件分不清。 */
    fun backupName(): String =
        "qingkebiao-backup-" + java.time.LocalDate.now() + ".json"

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

    /**
     * 正方课表页 → 课表。
     *
     * 页面里没有"第 1 周是哪天"，所以由调用方传"今天是第几周"反推学期起始。
     * 节次时间同样不在页面上，用现有的 periods 作息表换算。
     * 手动添加的条目照常保留。
     */
    suspend fun importZf(
        ctx: Context,
        blocks: List<Zf.Block>,
        /** null = 沿用已保存的学期起点，不再问"今天是第几周" */
        currentWeek: Int?,
        pageUrl: String = "",
        homeUrl: String = "",
        parser: String = "zf",
        /** 本次从网页读到的真实作息，没读到传 null */
        sniffedPeriods: List<PeriodSlot>? = null,
        /** 课表页的"上午/下午/晚上各几节"，用于读不到真时间时按真实结构推算 */
        groups: List<Pair<String, Int>> = emptyList(),
        /** true = 作为新学期导入：当前这份存进历史，手动加的课和调休不带过来 */
        newTerm: Boolean = false
    ): Timetable {
        val current = load(ctx)
        val old = if (newTerm) current.freshTerm() else current

        // 作息表来源优先级：这次读到的 > 用户已确认过的 > 按页面分组推算 > 通用默认
        val periods: List<PeriodSlot>
        val periodsSource: String
        when {
            sniffedPeriods != null -> { periods = sniffedPeriods; periodsSource = "sniffed" }
            old.periodsSource == "sniffed" || old.periodsSource == "manual" ->
                { periods = old.periods; periodsSource = old.periodsSource }
            groups.isNotEmpty() -> { periods = Periods.generate(groups); periodsSource = "derived" }
            else -> { periods = old.periods.ifEmpty { DEFAULT_PERIODS }; periodsSource = "derived" }
        }
        val termStart = when {
            currentWeek != null ->
                java.time.LocalDate.now().mondayOf()
                    .minusWeeks((currentWeek - 1).coerceAtLeast(0).toLong())
            old.termStartEpochDay != null -> java.time.LocalDate.ofEpochDay(old.termStartEpochDay)
            else -> java.time.LocalDate.now().mondayOf()
        }
        val parsed = Zf.toSessions(blocks, termStart, periods)
        if (parsed.isEmpty()) throw Zf.ParseException("按作息表换算后没有生成任何上课记录。")
        val manual = old.sessions.filter { it.manual }
        val tt = old.copy(
            sessions = (parsed + manual).sortedBy { it.start },
            termStartEpochDay = termStart.toEpochDay(),
            termWeeks = Zf.maxWeek(blocks).takeIf { it > 0 },
            sourceLabel = "教务系统网页",
            showWeekend = parsed.any { it.start.toLocalDate().dayOfWeek.value >= 6 },
            zfBlocks = blocks,
            jwxtHome = homeUrl.ifBlank { old.jwxtHome },
            jwxtPage = pageUrl.ifBlank { old.jwxtPage },
            parserUsed = parser,
            periods = periods,
            periodsSource = periodsSource,
            lastSyncEpochDay = java.time.LocalDate.now().toEpochDay()
        )
        // 到这里才归档旧学期：前面任何一步解析失败，当前课表都原封不动
        if (newTerm) withContext(Dispatchers.IO) { archive(ctx, current) }
        save(ctx, tt)
        return tt
    }

    /**
     * 换了作息表或者改了开学日期之后，用存下来的课程块原地重算上课时间。
     * 手动条目不动。没有课程块（比如课表是从 ICS 导入的）就原样返回。
     */
    /**
     * 记一笔"今天和教务系统对过了"。
     * 抓到并比对过就算，不管最后有没有变动、用户有没有点应用 ——
     * "多久没查了"问的是有没有查，不是有没有变。
     */
    suspend fun markSynced(ctx: Context) = withContext(Dispatchers.IO) {
        val tt = load(ctx)
        save(ctx, tt.copy(lastSyncEpochDay = java.time.LocalDate.now().toEpochDay()))
    }

    fun recomputeFromBlocks(tt: Timetable): Timetable {
        if (tt.zfBlocks.isEmpty()) return tt
        val termStart = tt.termStartEpochDay?.let { java.time.LocalDate.ofEpochDay(it) }
            ?: return tt
        val parsed = Zf.toSessions(tt.zfBlocks, termStart, tt.periods.ifEmpty { DEFAULT_PERIODS })
        val manual = tt.sessions.filter { it.manual }
        return tt.copy(sessions = (parsed + manual).sortedBy { it.start })
    }

    /** 从网页里读到了真实作息，立刻存下来 —— 用户可能先逛到作息页再去课表页。 */
    suspend fun savePeriods(ctx: Context, periods: List<PeriodSlot>) {
        val old = load(ctx)
        save(ctx, recomputeFromBlocks(old.copy(periods = periods, periodsSource = "sniffed")))
    }

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
