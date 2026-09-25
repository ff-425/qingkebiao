package com.qingkebiao.timetable

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * 图片 / PDF 课表识别。
 *
 * 用 ML Kit 的中文识别，**bundled 版**：模型打包在 APK 里，离线可用，
 * 不依赖 Google Play 服务 —— 国内很多手机根本没装 GMS，用另一个版本
 * 在用户手上就是直接不能用。
 *
 * OCR 只负责"哪块区域是什么字"，还原成课表要靠 [toGrid]：
 * 先找到"星期一…星期日"那一行定出列边界，再用左边那列的节次数字定出行边界，
 * 然后每个文字块按坐标落进格子。落完之后就是一张二维表，
 * 后面交给 [Generic.parseRows] —— 和 Excel 走完全同一条路。
 *
 * 必须说清楚：这条路**没法保证准**。拍歪、反光、字太小都会掉字，
 * 所以结果一律先给用户看预览，改错比重新输入快。
 */
object Ocr {

    class OcrException(message: String) : Exception(message)

    private val WEEKDAY_RE = Regex("""^\s*(?:星期|周)\s*([一二三四五六日天1-7])\s*$""")
    /** 节次列里的一格："3"，或者一行一个大节的 "第1-2节""1~2" —— 取起始数字定行位置 */
    private val NUM_RE = Regex("""^\s*第?\s*(\d{1,2})\s*(?:[-–—~～至到、,，]\s*\d{1,2}\s*)?节?\s*$""")

    private fun weekdayOf(s: String): Int? {
        val m = WEEKDAY_RE.find(s.replace(" ", "")) ?: return null
        val c = m.groupValues[1]
        val idx = "一二三四五六日".indexOf(c)
        if (idx >= 0) return idx + 1
        if (c == "天") return 7
        return c.toIntOrNull()?.takeIf { it in 1..7 }
    }

    /* --------------------------------------------------------------- 取图 */

    /** PDF 渲染成位图。按 ~200dpi 放大，字太小 OCR 会掉。 */
    fun renderPdf(ctx: Context, uri: Uri, maxPages: Int = 3): List<Bitmap> {
        val pfd = ctx.contentResolver.openFileDescriptor(uri, "r")
            ?: throw OcrException("打不开这个 PDF。")
        val out = ArrayList<Bitmap>()
        pfd.use {
            android.graphics.pdf.PdfRenderer(it).use { renderer ->
                val n = minOf(renderer.pageCount, maxPages)
                if (n == 0) throw OcrException("这个 PDF 一页都没有。")
                for (i in 0 until n) {
                    renderer.openPage(i).use { page ->
                        // 1pt = 1/72 inch，放到 200dpi 左右，再限制最大边长防止爆内存
                        var scale = 200f / 72f
                        if (page.width * scale > 3000) scale = 3000f / page.width
                        val w = (page.width * scale).toInt().coerceAtLeast(1)
                        val h = (page.height * scale).toInt().coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        // PDF 透明背景渲染出来是黑的，先铺白
                        android.graphics.Canvas(bmp).drawColor(Color.WHITE)
                        page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        out.add(bmp)
                    }
                }
            }
        }
        return out
    }

    /* --------------------------------------------------------------- 识别 */

    private suspend fun recognize(image: InputImage): Text =
        suspendCancellableCoroutine { cont ->
            val client = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            client.process(image)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
            cont.invokeOnCancellation { runCatching { client.close() } }
        }

    suspend fun readImage(ctx: Context, uri: Uri): List<List<String>> {
        // fromFilePath 会读 EXIF 自动转正，手机横着拍的照片不用自己处理
        val img = runCatching { InputImage.fromFilePath(ctx, uri) }.getOrElse {
            throw OcrException("这个图片读不了：${it.message}")
        }
        return toGrid(recognize(img))
    }

    suspend fun readPdf(ctx: Context, uri: Uri): List<List<String>> {
        val pages = renderPdf(ctx, uri)
        var best: List<List<String>> = emptyList()
        var bestScore = -1
        for (bmp in pages) {
            val grid = runCatching { toGrid(recognize(InputImage.fromBitmap(bmp, 0))) }
                .getOrDefault(emptyList())
            val score = grid.firstOrNull()?.count { weekdayOf(it) != null } ?: 0
            if (score > bestScore) { bestScore = score; best = grid }
            bmp.recycle()
        }
        if (best.isEmpty()) throw OcrException("这个 PDF 里没认出课表。")
        return best
    }

    /* ----------------------------------------------------- 文字块 → 二维表 */

    private class Piece(val text: String, val box: Rect)

    /**
     * 把带坐标的文字块还原成表格。
     *
     * 列：靠"星期一…"那一行的横向位置切。相邻两个表头中点之间是分界线，
     *     最左边那一列（比表头再往左）当作节次列。
     * 行：优先用节次列里的数字当锚点，两个锚点的中点是分界线；
     *     实在找不到节次就退回按纵向间距聚类。
     */
    fun toGrid(text: Text): List<List<String>> {
        val pieces = text.textBlocks
            .flatMap { it.lines }
            .mapNotNull { l -> l.boundingBox?.let { Piece(l.text.trim(), it) } }
            .filter { it.text.isNotEmpty() }
        if (pieces.isEmpty()) throw OcrException("图里一个字都没认出来。")

        // 1. 找星期表头
        val heads = pieces.mapNotNull { p -> weekdayOf(p.text)?.let { it to p } }
            .distinctBy { it.first }
            .sortedBy { it.second.box.centerX() }
        if (heads.size < 3) {
            throw OcrException(
                "没找到「星期一…星期五」那一行。确认整张课表都在画面里、没被裁掉，" +
                    "字也别太小。"
            )
        }

        // 2. 列边界
        val centers = heads.map { it.second.box.centerX() }
        val bounds = ArrayList<Int>()               // bounds[i] = 第 i 列的左边界
        for (i in centers.indices) {
            bounds.add(
                if (i == 0) centers[0] - (centers.getOrElse(1) { centers[0] + 200 } - centers[0]) / 2
                else (centers[i - 1] + centers[i]) / 2
            )
        }
        val rightEdge = centers.last() + (centers.last() - centers[centers.size - 2]) / 2

        fun colOf(p: Piece): Int {
            val x = p.box.centerX()
            if (x < bounds[0]) return -1            // 节次 / 时间那一列
            if (x > rightEdge) return heads.size - 1
            for (i in bounds.indices.reversed()) if (x >= bounds[i]) return i
            return -1
        }

        // 3. 表头下面才是内容
        val headBottom = heads.maxOf { it.second.box.bottom }
        val body = pieces.filter { it.box.centerY() > headBottom }
        if (body.isEmpty()) throw OcrException("只认出了表头，下面的内容没读到。")

        // 4. 行边界：先试节次列里的数字
        val anchors = body.filter { colOf(it) == -1 }
            .mapNotNull { p -> NUM_RE.find(p.text)?.groupValues?.get(1)?.toIntOrNull()
                ?.takeIf { it in 1..20 }?.let { it to p } }
            .sortedBy { it.second.box.centerY() }

        val rowEdges: List<Int>
        val rowLabels: List<String>
        if (anchors.size >= 3) {
            val ys = anchors.map { it.second.box.centerY() }
            rowEdges = (0 until ys.size).map { i ->
                if (i == 0) ys[0] - (ys.getOrElse(1) { ys[0] + 100 } - ys[0]) / 2
                else (ys[i - 1] + ys[i]) / 2
            }
            // 原文照搬："第1-2节" 只留 "1" 的话，后面就不知道这一行是两节了
            rowLabels = anchors.map { it.second.text }
        } else {
            // 没有节次列：按纵向间距切行
            val sorted = body.sortedBy { it.box.top }
            val h = sorted.map { it.box.height() }.sorted()[sorted.size / 2].coerceAtLeast(8)
            val edges = ArrayList<Int>()
            var cur = sorted.first().box.top
            edges.add(cur - h / 2)
            for (p in sorted) {
                if (p.box.top - cur > h * 3 / 2) { edges.add(p.box.top - h / 4); cur = p.box.top }
                else cur = maxOf(cur, p.box.top)
            }
            rowEdges = edges
            rowLabels = List(edges.size) { "" }
        }

        fun rowOf(p: Piece): Int {
            val y = p.box.centerY()
            for (i in rowEdges.indices.reversed()) if (y >= rowEdges[i]) return i
            return 0
        }

        // 5. 落格子
        val cells = HashMap<Long, MutableList<Piece>>()
        for (p in body) {
            val c = colOf(p)
            if (c < 0) continue                     // 节次列本身不当课程内容
            val r = rowOf(p)
            cells.getOrPut((r.toLong() shl 20) or c.toLong()) { ArrayList() }.add(p)
        }

        // 6. 拼成二维表：第 0 列放节次，后面按星期排
        val header = ArrayList<String>()
        header.add("节次")
        for ((wd, _) in heads) header.add("星期" + "一二三四五六日"[wd - 1])

        val out = ArrayList<List<String>>()
        out.add(header)
        for (r in rowEdges.indices) {
            val row = ArrayList<String>()
            row.add(rowLabels.getOrElse(r) { "" })
            for (c in heads.indices) {
                val list = cells[(r.toLong() shl 20) or c.toLong()].orEmpty()
                    .sortedWith(compareBy({ it.box.top }, { it.box.left }))
                row.add(list.joinToString("\n") { it.text })
            }
            out.add(row)
        }
        return out
    }
}
