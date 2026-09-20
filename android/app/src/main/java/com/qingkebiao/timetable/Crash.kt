package com.qingkebiao.timetable

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * 崩溃日志。
 *
 * 这个 App 没有服务器，也不打算为了收崩溃就接一个会上报数据的 SDK ——
 * "课表不出手机"是它存在的理由之一。折中做法：崩溃写在本地，
 * 下次打开时在设置里看得到，用户愿意的话导出发给我。
 *
 * 只记异常堆栈和机型系统版本，不碰课表内容，更不碰任何账号信息。
 */
object Crash {

    private const val PREFIX = "crash-"
    private const val KEEP = 5

    fun install(app: Application) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { write(app, thread, e) }
            // 一定要把异常交回给系统：不然进程卡在那儿不死不活，
            // 用户看到的是"点什么都没反应"，比直接闪退还难受
            prev?.uncaughtException(thread, e)
        }
    }

    private fun dir(ctx: Context) = File(ctx.filesDir, "crash").apply { mkdirs() }

    private fun write(ctx: Context, thread: Thread, e: Throwable) {
        val sw = StringWriter()
        PrintWriter(sw).use { e.printStackTrace(it) }
        val stamp = java.time.LocalDateTime.now().toString().take(19).replace(':', '-')
        val text = buildString {
            appendLine("时间: ${java.time.LocalDateTime.now()}")
            appendLine("版本: ${Updater.currentName(ctx)} (${Updater.currentCode(ctx)})")
            appendLine("机型: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("系统: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("线程: ${thread.name}")
            appendLine()
            append(sw.toString())
        }
        File(dir(ctx), "$PREFIX$stamp.log").writeText(text)
        prune(ctx)
    }

    /** 只留最近几条，别让日志无限堆。 */
    private fun prune(ctx: Context) {
        val files = list(ctx)
        if (files.size <= KEEP) return
        files.drop(KEEP).forEach { runCatching { it.delete() } }
    }

    /** 最近的崩溃记录，新的在前。 */
    fun list(ctx: Context): List<File> =
        dir(ctx).listFiles { f: File -> f.name.startsWith(PREFIX) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    fun readAll(ctx: Context): String =
        list(ctx).joinToString("\n\n" + "=".repeat(40) + "\n\n") {
            runCatching { it.readText() }.getOrDefault("(读不出 ${it.name})")
        }

    fun clear(ctx: Context) {
        list(ctx).forEach { runCatching { it.delete() } }
    }

    /** 崩溃后第一次打开时，界面上要说一声 —— 不说的话用户只当是"闪了一下"。 */
    fun latestSummary(ctx: Context): String? {
        val f = list(ctx).firstOrNull() ?: return null
        val text = runCatching { f.readText() }.getOrNull() ?: return null
        val line = text.lineSequence().firstOrNull { it.contains("Exception") || it.contains("Error") }
            ?.trim()?.take(90)
        val time = f.name.removePrefix(PREFIX).removeSuffix(".log").replace('T', ' ')
        return "$time  ${line ?: "崩溃"}"
    }
}

class QkbApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Crash.install(this)
    }
}
