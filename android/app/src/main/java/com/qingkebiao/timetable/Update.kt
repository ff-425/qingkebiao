package com.qingkebiao.timetable

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 应用内更新。
 *
 * 不上架应用商店的 App，更新本来要走"下载文件 → 进文件管理器 → 点安装"，
 * 每发一版就折腾一遍。这里把这件事收回到 App 里：启动时问一下有没有新版，
 * 有就下载好、直接唤起安装界面。
 *
 * 安全上靠两件事，不靠我们自己校验：
 *  1. 只接受 https 地址，明文 http 一律拒绝；
 *  2. Android 本身不允许用不同签名的包覆盖已安装的应用。就算更新地址被人换掉，
 *     换上去的包签名对不上，系统会直接拒装，而不是悄悄替换掉你的 App。
 *
 * 地址是用户自己填的，不填就完全不联网查。
 */
object Updater {

    /** 放在网站上的 version.json。字段都给默认值，将来加字段不会让旧版读不了。 */
    @Serializable
    data class Manifest(
        val versionCode: Int = 0,
        val versionName: String = "",
        /** APK 地址。可以写完整 https 地址，也可以写相对 version.json 的相对路径。 */
        val url: String = "",
        val size: Long = 0,
        val notes: String = ""
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun currentCode(ctx: Context): Int {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else pi.versionCode
    }

    fun currentName(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }
            .getOrNull().orEmpty()

    /** 用户填站点根地址就够了，version.json 自动补。填完整路径也认。 */
    fun manifestUrl(base: String): String {
        var u = base.trim()
        if (u.isEmpty()) return ""
        if (!u.startsWith("http://", true) && !u.startsWith("https://", true)) u = "https://$u"
        return if (u.endsWith(".json", true)) u else u.trimEnd('/') + "/version.json"
    }

    private fun requireHttps(u: String): URL {
        val url = URL(u)
        if (!url.protocol.equals("https", true)) {
            throw IOException("更新地址必须是 https，收到的是 ${url.protocol}。")
        }
        return url
    }

    private fun open(u: String): HttpURLConnection =
        (requireHttps(u).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "qingkebiao (Android ${Build.VERSION.SDK_INT})")
        }

    suspend fun fetch(base: String): Manifest = withContext(Dispatchers.IO) {
        val u = manifestUrl(base)
        if (u.isBlank()) throw IOException("没填更新地址。")
        val conn = open(u)
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("服务器返回 $code，检查一下地址对不对。")
            val text = conn.inputStream.use { it.reader().readText() }
            json.decodeFromString<Manifest>(text)
        } finally {
            conn.disconnect()
        }
    }

    /** manifest 里的 url 可能是相对路径，按 version.json 的位置解析。 */
    fun apkUrl(base: String, m: Manifest): String {
        if (m.url.isBlank()) throw IOException("version.json 里没写 APK 地址。")
        return URL(URL(manifestUrl(base)), m.url).toString()
    }

    suspend fun download(
        ctx: Context,
        url: String,
        expectedSize: Long,
        onProgress: (done: Long, total: Long) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "update").apply { mkdirs() }
        // 每次重下，不做断点续传 —— 十来兆的东西，复杂度不值得
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "qingkebiao-update.apk")

        val conn = open(url)
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("下载失败，服务器返回 $code。")
            val total = if (conn.contentLengthLong > 0) conn.contentLengthLong else expectedSize
            conn.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        if (out.length() < 1024) throw IOException("下载下来的文件不对，只有 ${out.length()} 字节。")
        out
    }

    /** 系统是否允许本应用安装包。Android 8 起这是按应用给的权限。 */
    fun canInstall(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 26) ctx.packageManager.canRequestPackageInstalls() else true

    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", apk)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    /** 跳到"允许安装未知应用"的系统设置页，第一次装要开一下。 */
    fun openInstallSettings(ctx: Context) {
        val intent = if (Build.VERSION.SDK_INT >= 26) {
            Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData(android.net.Uri.parse("package:${ctx.packageName}"))
        } else {
            Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)
        }
        runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
