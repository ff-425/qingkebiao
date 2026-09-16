package com.qingkebiao.timetable

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * 从 .xlsx / .csv 里读出一张二维表。
 *
 * 不引第三方库：xlsx 本质是个 zip，里面是 XML，自己解开就行。
 * 引 POI 那种东西进来会让 APK 大一大截，而我们只需要"每个格子里是什么字"。
 *
 * 合并单元格会展开到它覆盖的每一格 —— 和 HTML 表格的 rowspan/colspan 同样处理，
 * 因为连堂课、"上午/下午"那一列基本都是合并出来的，不展开就对不上列。
 */
object Sheets {

    class ParseException(message: String) : Exception(message)

    /* ------------------------------------------------------------------ xlsx */

    private val SHARED_SI = Regex("""<si>(.*?)</si>""", RegexOption.DOT_MATCHES_ALL)
    private val TEXT_T = Regex("""<t[^>]*>(.*?)</t>""", RegexOption.DOT_MATCHES_ALL)
    private val ROW_RE = Regex("""<row[^>]*>(.*?)</row>""", RegexOption.DOT_MATCHES_ALL)
    private val CELL_RE = Regex("""<c([^>]*)/>|<c([^>]*)>(.*?)</c>""", RegexOption.DOT_MATCHES_ALL)
    private val V_RE = Regex("""<v[^>]*>(.*?)</v>""", RegexOption.DOT_MATCHES_ALL)
    private val IS_RE = Regex("""<is>(.*?)</is>""", RegexOption.DOT_MATCHES_ALL)
    private val REF_RE = Regex("""r="([A-Z]+)(\d+)"""")
    private val TYPE_RE = Regex("""t="([^"]+)"""")
    private val MERGE_RE = Regex("""<mergeCell[^>]*ref="([A-Z]+)(\d+):([A-Z]+)(\d+)"""")

    /**
     * `&#26143;` 这种数字实体必须解。
     * 实测：openpyxl 生成的 xlsx 里中文全是数字实体，不解的话
     * "星期一" 到手是字面量 `&#26143;&#26399;&#19968;`，表头永远认不出来。
     */
    private val NUM_ENTITY = Regex("""&#(x?)([0-9a-fA-F]+);""")

    private fun unescape(s: String): String {
        val decoded = NUM_ENTITY.replace(s) { m ->
            val code = m.groupValues[2].toIntOrNull(if (m.groupValues[1].isEmpty()) 10 else 16)
            when {
                code == null || code <= 0 || code > 0x10FFFF -> m.value
                code == 13 -> ""
                else -> String(Character.toChars(code))
            }
        }
        return decoded
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'")
            .replace("&amp;", "&")
    }

    /** A -> 0, B -> 1, …, AA -> 26 */
    private fun colIndex(letters: String): Int {
        var n = 0
        for (c in letters) n = n * 26 + (c - 'A' + 1)
        return n - 1
    }

    private fun readAll(ins: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /**
     * 解开 xlsx，返回每个工作表的二维表（已展开合并单元格）。
     * 一个文件里可能有多张表，交给调用方挑哪张像课表。
     */
    fun readXlsx(bytes: ByteArray): List<List<List<String>>> {
        var shared: List<String> = emptyList()
        val sheets = LinkedHashMap<String, String>()

        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val name = e.name
                when {
                    name == "xl/sharedStrings.xml" -> {
                        val xml = String(readAll(zip), Charsets.UTF_8)
                        shared = SHARED_SI.findAll(xml).map { si ->
                            // 富文本会被拆成多个 <t>，拼起来才是这一格的完整内容
                            TEXT_T.findAll(si.groupValues[1])
                                .joinToString("") { unescape(it.groupValues[1]) }
                        }.toList()
                    }
                    name.startsWith("xl/worksheets/sheet") && name.endsWith(".xml") -> {
                        sheets[name] = String(readAll(zip), Charsets.UTF_8)
                    }
                }
                zip.closeEntry()
            }
        }
        if (sheets.isEmpty()) throw ParseException("这个 xlsx 里没找到工作表。")
        return sheets.entries.sortedBy { it.key }.map { parseSheet(it.value, shared) }
    }

    private fun parseSheet(xml: String, shared: List<String>): List<List<String>> {
        val cells = HashMap<Long, String>()      // (row shl 20) or col
        var maxRow = 0
        var maxCol = 0

        fun put(r: Int, c: Int, v: String) {
            cells[(r.toLong() shl 20) or c.toLong()] = v
            if (r > maxRow) maxRow = r
            if (c > maxCol) maxCol = c
        }

        for (rowM in ROW_RE.findAll(xml)) {
            for (cm in CELL_RE.findAll(rowM.groupValues[1])) {
                val attrs = (cm.groupValues[1] + cm.groupValues[2])
                val body = cm.groupValues[3]
                val ref = REF_RE.find(attrs) ?: continue
                val col = colIndex(ref.groupValues[1])
                val row = ref.groupValues[2].toIntOrNull()?.minus(1) ?: continue
                val type = TYPE_RE.find(attrs)?.groupValues?.get(1)
                val value = when (type) {
                    "s" -> V_RE.find(body)?.groupValues?.get(1)?.toIntOrNull()
                        ?.let { shared.getOrNull(it) }.orEmpty()
                    "inlineStr" -> IS_RE.find(body)?.groupValues?.get(1)
                        ?.let { inner -> TEXT_T.findAll(inner).joinToString("") { unescape(it.groupValues[1]) } }
                        .orEmpty()
                    else -> V_RE.find(body)?.groupValues?.get(1)?.let { unescape(it) }.orEmpty()
                }
                if (value.isNotBlank()) put(row, col, value)
            }
        }

        // 合并单元格：值展开到覆盖的每一格
        for (m in MERGE_RE.findAll(xml)) {
            val c1 = colIndex(m.groupValues[1]); val r1 = m.groupValues[2].toInt() - 1
            val c2 = colIndex(m.groupValues[3]); val r2 = m.groupValues[4].toInt() - 1
            val v = cells[(r1.toLong() shl 20) or c1.toLong()] ?: continue
            for (r in r1..r2) for (c in c1..c2) put(r, c, v)
        }

        if (maxRow > 5000 || maxCol > 200) throw ParseException("这张表太大了，不像课表。")
        return (0..maxRow).map { r ->
            (0..maxCol).map { c -> cells[(r.toLong() shl 20) or c.toLong()].orEmpty() }
        }
    }

    /* ------------------------------------------------------------------- csv */

    /** 逗号分隔，支持引号包裹和引号内换行。 */
    fun readCsv(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cur = StringBuilder()
        var inQuote = false
        var i = 0
        val s = text.replace("\r\n", "\n").replace('\r', '\n')
        while (i < s.length) {
            val ch = s[i]
            when {
                inQuote && ch == '"' && i + 1 < s.length && s[i + 1] == '"' -> { cur.append('"'); i++ }
                ch == '"' -> inQuote = !inQuote
                !inQuote && (ch == ',' || ch == '\t') -> { row.add(cur.toString()); cur.setLength(0) }
                !inQuote && ch == '\n' -> {
                    row.add(cur.toString()); cur.setLength(0)
                    rows.add(row); row = ArrayList()
                }
                else -> cur.append(ch)
            }
            i++
        }
        row.add(cur.toString())
        if (row.any { it.isNotBlank() }) rows.add(row)
        return rows
    }

    /** 一个文件里多张表时，挑"星期"出现最多的那张。 */
    fun pickTimetableSheet(sheets: List<List<List<String>>>): List<List<String>> {
        if (sheets.isEmpty()) throw ParseException("文件里没有可读的表格。")
        return sheets.maxByOrNull { rows ->
            rows.sumOf { r -> r.count { c -> c.contains("星期") || Regex("^周[一二三四五六日天]$").matches(c.trim()) } }
        } ?: sheets.first()
    }
}
