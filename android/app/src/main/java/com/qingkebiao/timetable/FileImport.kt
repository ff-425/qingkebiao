package com.qingkebiao.timetable

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 从文件导入课表：Excel / CSV。
 *
 * 和教务系统导入的区别在于数据从哪来，解析之后完全走同一条路 ——
 * 同样出 [Zf.Block]、同样先给用户看预览、同样只在确认后才写进去，
 * 作息表和开学日期同样沿用已有设置。
 */
object FileImport {

    class Result(val blocks: List<Zf.Block>, val kind: String, val note: String)

    private fun name(ctx: Context, uri: Uri): String =
        runCatching {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) c.getString(i) else null
            }
        }.getOrNull().orEmpty()

    private fun bytes(ctx: Context, uri: Uri): ByteArray =
        ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw Sheets.ParseException("读不出这个文件，换一个试试。")

    suspend fun parse(ctx: Context, uri: Uri): Result = withContext(Dispatchers.IO) {
        val fileName = name(ctx, uri).lowercase()
        val data = bytes(ctx, uri)
        if (data.isEmpty()) throw Sheets.ParseException("这个文件是空的。")

        // 靠内容判断而不是靠扩展名：xlsx 是 zip，开头一定是 PK
        val isZip = data.size > 4 && data[0] == 'P'.code.toByte() && data[1] == 'K'.code.toByte()

        when {
            isZip || fileName.endsWith(".xlsx") -> {
                val sheets = Sheets.readXlsx(data)
                val rows = Sheets.pickTimetableSheet(sheets)
                Result(
                    Generic.parseRows(rows), "excel",
                    "Excel：${sheets.size} 张工作表，用了看着最像课表的那张（${rows.size} 行）"
                )
            }
            fileName.endsWith(".csv") || fileName.endsWith(".txt") || looksLikeText(data) -> {
                val text = decodeText(data)
                val rows = Sheets.readCsv(text)
                Result(Generic.parseRows(rows), "csv", "CSV：${rows.size} 行")
            }
            isPdf(data) || fileName.endsWith(".pdf") -> {
                val rows = Ocr.readPdf(ctx, uri)
                Result(Generic.parseRows(rows, loose = true), "pdf", ocrNote("PDF", rows))
            }
            isImage(data) || fileName.matches(Regex(""".*\.(jpe?g|png|webp|heic|heif|bmp)$""")) -> {
                val rows = Ocr.readImage(ctx, uri)
                Result(Generic.parseRows(rows, loose = true), "image", ocrNote("图片", rows))
            }
            fileName.endsWith(".xls") -> throw Sheets.ParseException(
                "这是老版 .xls 格式，读不了。用 Excel/WPS 打开另存为 .xlsx 再试。"
            )
            else -> throw Sheets.ParseException(
                "不认识这个文件（$fileName）。支持 .xlsx、.csv、图片和 PDF。"
            )
        }
    }

    /**
     * OCR 最危险的失败不是认错字，是**整列没认出来**——
     * 预览里少了两门课，不比对原图根本看不出来。实测拍歪 1.8°
     * 的照片就丢了星期一整列。所以认出几天要明说。
     */
    private fun ocrNote(kind: String, rows: List<List<String>>): String {
        val days = (rows.firstOrNull()?.size ?: 1) - 1
        val lines = rows.size - 1
        return buildString {
            append("$kind：认出 $days 天 × $lines 行。")
            if (days < 5) {
                append("\n⚠ 只认出 $days 天，正常课表至少有 5 天 —— " +
                    "很可能有整列没认出来。确认图没被裁、拍正一点再试，" +
                    "有截图就用截图，比拍照准得多。")
            }
            append("\n下面逐条对一遍，错的导入后可以直接改。")
        }
    }

    private fun startsWith(data: ByteArray, vararg b: Int): Boolean =
        data.size >= b.size && b.indices.all { data[it] == b[it].toByte() }

    private fun isPdf(data: ByteArray) = startsWith(data, 0x25, 0x50, 0x44, 0x46)  // %PDF

    private fun isImage(data: ByteArray) =
        startsWith(data, 0xFF, 0xD8, 0xFF) ||                                       // JPEG
            startsWith(data, 0x89, 0x50, 0x4E, 0x47) ||                             // PNG
            startsWith(data, 0x42, 0x4D) ||                                         // BMP
            (data.size > 12 && String(data, 0, 4) == "RIFF" && String(data, 8, 4) == "WEBP") ||
            (data.size > 12 && String(data, 4, 4) == "ftyp")                        // HEIC/HEIF

    /** 前 512 字节里没有 0 且大多是可见字符，就当文本。 */
    private fun looksLikeText(data: ByteArray): Boolean {
        val n = minOf(data.size, 512)
        if (n == 0) return false
        for (i in 0 until n) if (data[i] == 0.toByte()) return false
        return true
    }

    /** 国内的 CSV 十有八九是 GBK，UTF-8 解不出来就换 GBK。 */
    private fun decodeText(data: ByteArray): String {
        val utf8 = String(data, Charsets.UTF_8)
        if (!utf8.contains('�')) return utf8.removePrefix("﻿")
        return runCatching { String(data, charset("GBK")) }.getOrDefault(utf8)
    }
}

@Composable
fun FileImportDialog(
    pal: Palette,
    tt: Timetable,
    uri: Uri,
    onClose: () -> Unit,
    onImported: (Timetable) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var result by remember { mutableStateOf<FileImport.Result?>(null) }
    var msg by remember { mutableStateOf<String?>("解析中…") }
    var err by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var askWeek by remember { mutableStateOf(tt.termStartEpochDay == null) }
    var currentWeek by remember { mutableIntStateOf(1) }

    val periods = tt.periods.ifEmpty { DEFAULT_PERIODS }

    androidx.compose.runtime.LaunchedEffect(uri) {
        runCatching { FileImport.parse(ctx, uri) }.fold(
            onSuccess = { r ->
                result = r; err = false
                msg = r.note + "\n认出 ${Zf.courseCount(r.blocks)} 门课、${r.blocks.size} 个课程块。"
            },
            onFailure = { e -> err = true; result = null; msg = e.message ?: e.toString() }
        )
    }

    fun doImport(r: FileImport.Result) {
        scope.launch {
            runCatching {
                Store.importZf(
                    ctx, r.blocks,
                    currentWeek = if (askWeek) currentWeek else null,
                    parser = r.kind
                )
            }.fold(
                onSuccess = {
                    done = true; err = false
                    onImported(it)
                    msg = "已导入 ${it.sessions.size} 节课。时间不对就到设置里改「作息时间」。"
                },
                onFailure = { e -> err = true; msg = "导入失败：${e.message}" }
            )
        }
    }

    Sheet(
        pal, "从文件导入", onClose,
        footer = {
            val r = result
            when {
                done -> PrimaryButton(pal, "完成", modifier = Modifier.fillMaxWidth(), onClick = onClose)
                r != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlineChip(pal, "取消", modifier = Modifier.weight(1f).height(Dim.touch), onClick = onClose)
                    Spacer(Modifier.width(Dim.s))
                    PrimaryButton(pal, "导入 ${r.blocks.size} 个课程块", modifier = Modifier.weight(2f)) {
                        doImport(r)
                    }
                }
            }
        }
    ) {
        Column {
            Hint(
                pal,
                "表格里要有「星期一…星期五」这样的表头，每一格写课程名，" +
                    "地点、老师、周次各占一行。合并单元格会自动展开。"
            )
            Spacer(Modifier.height(Dim.m))

            msg?.let { MsgBox(pal, it, err) }

            result?.takeIf { !done }?.let { r ->
                Spacer(Modifier.height(20.dp))
                Label(pal, "预览 · 确认对了再导入")
                val dow = "一二三四五六日"
                // OCR 来的必须全部列出来让人核对；Excel 可靠，列几条示意即可
                val showAll = r.kind == "image" || r.kind == "pdf"
                val shown = if (showAll) 40 else 8
                Card(pal, color = pal.panel2) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        r.blocks.sortedWith(compareBy({ it.weekday }, { it.startPeriod })).take(shown).forEach { b ->
                            Row(Modifier.padding(vertical = 5.dp)) {
                                Text(
                                    "周${dow.getOrElse(b.weekday - 1) { '?' }} ${b.startPeriod}-${b.endPeriod}节",
                                    color = pal.muted, fontSize = Fs.caption, style = NumStyle,
                                    modifier = Modifier.width(80.dp)
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(b.title, color = pal.ink, fontSize = 13.sp, maxLines = 1)
                                    Text(
                                        listOf(b.location, b.teacher, b.weeksRaw)
                                            .filter { it.isNotBlank() }.joinToString(" · "),
                                        color = pal.muted, fontSize = Fs.caption, maxLines = 2
                                    )
                                }
                            }
                        }
                        if (r.blocks.size > shown) {
                            Text(
                                "…… 还有 ${r.blocks.size - shown} 个", color = pal.faint, fontSize = Fs.caption,
                                modifier = Modifier.padding(vertical = 5.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(Dim.m))
                if (askWeek) {
                    FieldRow(pal, "今天是第几周", "表格里只有周次，靠这个反推开学日期") {
                        IntStepper(pal, currentWeek, 1, Zf.maxWeek(r.blocks).coerceAtLeast(1), suffix = " 周") {
                            currentWeek = it
                        }
                    }
                } else {
                    FieldRow(pal, "学期起点", "沿用已保存的设置") {
                        OutlineChip(pal, "改一下") { askWeek = true }
                    }
                }
                Spacer(Modifier.height(Dim.xs))
                Hint(pal, "时间按设置里的作息表换算，第 1 节 ${periods.firstOrNull()?.startMin?.hhmm() ?: "—"} 起。")
            }
        }
    }
}
