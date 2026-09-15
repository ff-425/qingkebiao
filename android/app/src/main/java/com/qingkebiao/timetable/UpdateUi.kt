package com.qingkebiao.timetable

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.io.File

/**
 * 更新面板：填地址 → 查 → 下 → 装，全程不用出 App。
 *
 * 地址留空就什么都不做，也不会发任何网络请求。
 */
@Composable
fun UpdateSheet(
    pal: Palette,
    tt: Timetable,
    found: Updater.Manifest?,
    onClose: () -> Unit,
    onApply: (Timetable) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var url by remember { mutableStateOf(tt.updateUrl) }
    var latest by remember { mutableStateOf(found) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var err by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(-1f) }
    var apk by remember { mutableStateOf<File?>(null) }

    val myCode = remember { Updater.currentCode(ctx) }
    val myName = remember { Updater.currentName(ctx) }

    fun check() {
        busy = true; err = false; msg = "检查中…"
        scope.launch {
            runCatching { Updater.fetch(url) }.fold(
                onSuccess = { m ->
                    busy = false
                    latest = m
                    if (m.versionCode > myCode) {
                        msg = null
                    } else {
                        err = false
                        msg = "已经是最新版了（v$myName）。"
                    }
                },
                onFailure = { e -> busy = false; err = true; msg = "查不到：${e.message ?: e}" }
            )
        }
    }

    fun download(m: Updater.Manifest) {
        busy = true; err = false; msg = null; progress = 0f
        scope.launch {
            runCatching {
                Updater.download(ctx, Updater.apkUrl(url, m), m.size) { done, total ->
                    progress = if (total > 0) done.toFloat() / total else -1f
                }
            }.fold(
                onSuccess = { f ->
                    busy = false; progress = -1f; apk = f
                    if (Updater.canInstall(ctx)) {
                        Updater.install(ctx, f)
                        msg = "已经唤起安装界面，点「安装」就行。"
                    } else {
                        err = true
                        msg = "下载好了，但系统还没允许本应用安装包。点下面那个按钮去打开，" +
                            "开完回来点「安装」。"
                    }
                },
                onFailure = { e ->
                    busy = false; progress = -1f; err = true
                    msg = "下载失败：${e.message ?: e}"
                }
            )
        }
    }

    Sheet(pal, "更新", onClose) {
        Column {
            FieldRow(pal, "当前版本", "v$myName（内部号 $myCode）") { Spacer(Modifier.width(0.dp)) }

            Spacer(Modifier.height(14.dp))
            Label(pal, "更新地址")
            Hint(
                pal,
                "填你自己放更新包的站点，比如 xxx.pages.dev。" +
                    "App 只会去这个地址问「有没有新版」，留空就完全不联网查。"
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = url, onValueChange = { url = it },
                placeholder = { Text("xxx.pages.dev", fontSize = 12.sp) },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            if (url.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "实际会请求：" + Updater.manifestUrl(url),
                    color = pal.faint, fontSize = 10.sp, fontFamily = FontFamily.Monospace
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PrimaryButton(pal, if (busy) "请稍等…" else "保存并检查", enabled = !busy) {
                    onApply(tt.copy(updateUrl = url.trim()))
                    check()
                }
                if (tt.updateUrl.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    OutlineChip(pal, "清除地址") {
                        url = ""
                        onApply(tt.copy(updateUrl = ""))
                        latest = null
                        msg = "已清除，以后不再检查更新。"
                    }
                }
            }

            val m = latest
            if (m != null && m.versionCode > myCode) {
                Spacer(Modifier.height(18.dp))
                Label(pal, "有新版")
                Box(
                    Modifier.fillMaxWidth()
                        .background(pal.signal.copy(alpha = 0.08f), RoundedCornerShape(2.dp))
                        .padding(11.dp)
                ) {
                    Column {
                        Text(
                            "v${m.versionName.ifBlank { m.versionCode.toString() }}" +
                                if (m.size > 0) "   ${m.size / 1024 / 1024} MB" else "",
                            color = pal.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace
                        )
                        if (m.notes.isNotBlank()) {
                            Spacer(Modifier.height(5.dp))
                            Text(m.notes, color = pal.ink2, fontSize = 12.sp, lineHeight = 17.sp)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))

                if (progress >= 0f) {
                    Box(
                        Modifier.fillMaxWidth().height(5.dp)
                            .background(pal.panel2, RoundedCornerShape(3.dp))
                    ) {
                        Box(
                            Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(5.dp)
                                .background(pal.signal, RoundedCornerShape(3.dp))
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "下载中 ${(progress * 100).toInt()}%",
                        color = pal.muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val ready = apk
                        if (ready != null && ready.exists()) {
                            PrimaryButton(pal, "安装", enabled = !busy) { Updater.install(ctx, ready) }
                            Spacer(Modifier.width(8.dp))
                            OutlineChip(pal, "重新下载", !busy) { download(m) }
                        } else {
                            PrimaryButton(pal, "下载并安装", enabled = !busy) { download(m) }
                        }
                        if (!Updater.canInstall(ctx)) {
                            Spacer(Modifier.width(8.dp))
                            OutlineChip(pal, "去开权限") { Updater.openInstallSettings(ctx) }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Hint(
                    pal,
                    "第一次会让你允许「安装未知应用」，开一次以后就不用了。" +
                        "装的是同一个签名的包，数据不会丢。"
                )
            }

            msg?.let {
                Spacer(Modifier.height(14.dp))
                MsgBox(pal, it, err)
            }
        }
    }
}

/**
 * 启动时静默查一次。查不到就当没这回事 —— 更新提示不该因为没网就弹错误。
 */
@Composable
fun rememberUpdateCheck(updateUrl: String): Updater.Manifest? {
    val ctx = LocalContext.current
    var found by remember(updateUrl) { mutableStateOf<Updater.Manifest?>(null) }
    LaunchedEffect(updateUrl) {
        if (updateUrl.isBlank()) return@LaunchedEffect
        val m = runCatching { Updater.fetch(updateUrl) }.getOrNull() ?: return@LaunchedEffect
        if (m.versionCode > Updater.currentCode(ctx)) found = m
    }
    return found
}
