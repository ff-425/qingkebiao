package com.qingkebiao.timetable

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.text.font.FontFamily
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
            fileName.endsWith(".xls") -> throw Sheets.ParseException(
                "这是老版 .xls 格式，读不了。用 Excel/WPS 打开另存为 .xlsx 再试。"
            )
            fileName.endsWith(".pdf") -> throw Sheets.ParseException(
                "PDF 还不支持。可以先截图或者导出成 Excel。"
            )
            else -> throw Sheets.ParseException(
                "不认识这个文件（$fileName）。目前支持 .xlsx 和 .csv。"
            )
        }
    }

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

    Sheet(pal, "从文件导入", onClose) {
        Column {
            Hint(
                pal,
                "表格里要有「星期一…星期五」这样的表头，每一格写课程名，" +
                    "地点、老师、周次各占一行。合并单元格会自动展开。"
            )
            Spacer(Modifier.height(12.dp))

            msg?.let { MsgBox(pal, it, err) }

            result?.takeIf { !done }?.let { r ->
                Spacer(Modifier.height(12.dp))
                Label(pal, "预览（确认对了再导入）")
                val dow = "一二三四五六日"
                r.blocks.sortedWith(compareBy({ it.weekday }, { it.startPeriod })).take(8).forEach { b ->
                    Text(
                        "周${dow.getOrElse(b.weekday - 1) { '?' }} ${b.startPeriod}-${b.endPeriod}节  " +
                            "${b.title}" +
                            (if (b.location.isNotBlank()) "  ${b.location}" else "") +
                            (if (b.teacher.isNotBlank()) "  ${b.teacher}" else "") +
                            "  ${b.weeksRaw}",
                        color = pal.ink2, fontSize = 11.5.sp,
                        fontFamily = FontFamily.Monospace, maxLines = 2
                    )
                }
                if (r.blocks.size > 8) {
                    Text("…… 还有 ${r.blocks.size - 8} 个", color = pal.faint, fontSize = 11.sp)
                }

                Spacer(Modifier.height(10.dp))
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
                Spacer(Modifier.height(4.dp))
                Hint(pal, "时间按设置里的作息表换算，第 1 节 ${periods.firstOrNull()?.startMin?.hhmm() ?: "—"} 起。")

                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PrimaryButton(pal, "导入这 ${r.blocks.size} 个课程块") {
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
                                    msg = "已导入 ${it.sessions.size} 节课。时间不对就到设置里改「节次时间」。"
                                },
                                onFailure = { e -> err = true; msg = "导入失败：${e.message}" }
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlineChip(pal, "取消", onClick = onClose)
                }
            }

            if (done) {
                Spacer(Modifier.height(14.dp))
                PrimaryButton(pal, "完成", onClick = onClose)
            }
        }
    }
}
