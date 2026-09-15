package com.qingkebiao.timetable

import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import org.json.JSONTokener

/**
 * 教务系统网页导入的"取数据"这一步。
 *
 * 为什么用内置浏览器而不是在后台直接 HTTP 请求：教务系统要登录，可能有验证码、
 * 短信验证、统一身份认证跳转。让人在 WebView 里自己登录、自己点到课表页，
 * 然后抓当前页面的 HTML —— 这样不用为每所学校写一套登录逻辑，
 * 验证码之类的也天然由本人处理。国内课表 App 普遍是这个路子。
 */
class WebImportActivity : ComponentActivity() {

    companion object {
        const val EXTRA_URL = "start_url"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val start = intent.getStringExtra(EXTRA_URL)?.takeIf { it.isNotBlank() }
            ?: "https://www.baidu.com"
        setContent { WebImportScreen(start) { finish() } }
    }
}

/** 抓当前页 + 所有同源 iframe 的 HTML。教务系统很爱把课表塞进 iframe。 */
private const val GRAB_JS = """
(function(){
  function grab(doc){ try { return doc.documentElement.outerHTML; } catch(e){ return ''; } }
  var parts = ['<!-- MAIN ' + location.href + ' -->\n' + grab(document)];
  var fs = document.getElementsByTagName('iframe');
  for (var i = 0; i < fs.length; i++) {
    try {
      var d = fs[i].contentDocument;
      if (d) parts.push('<!-- IFRAME ' + i + ' src=' + fs[i].src + ' -->\n' + grab(d));
    } catch (e) { /* 跨源 iframe 读不到，跳过 */ }
  }
  return parts.join('\n<!-- ======== -->\n');
})()
"""

@Composable
private fun WebImportScreen(startUrl: String, onFinish: () -> Unit) {
    val dark = isSystemInDarkTheme()
    val pal = remember(dark) { if (dark) DarkPalette else LightPalette }
    val scheme = remember(pal) {
        if (pal.dark) darkColorScheme(background = pal.paper, surface = pal.panel, onSurface = pal.ink)
        else lightColorScheme(background = pal.paper, surface = pal.panel, onSurface = pal.ink)
    }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var webView by remember { mutableStateOf<WebView?>(null) }
    var currentUrl by remember { mutableStateOf(startUrl) }
    var desktopUa by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var captured by remember { mutableStateOf<String?>(null) }

    val saver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/html")
    ) { uri ->
        val html = captured
        if (uri != null && html != null) {
            runCatching {
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(html.toByteArray()) }
            }.onSuccess { msg = "已保存。把这个文件发给我，我按你学校的页面结构写解析器。" }
                .onFailure { msg = "保存失败：${it.message}" }
        }
    }

    fun capture() {
        val wv = webView ?: return
        msg = "抓取中…"
        wv.evaluateJavascript(GRAB_JS) { raw ->
            // evaluateJavascript 回来的是 JSON 字面量，要先解引号和反转义
            val html = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
            if (html.isNullOrBlank() || html.length < 200) {
                msg = "没抓到有效内容。确认页面已经显示出课表了再抓。"
                return@evaluateJavascript
            }
            captured = html
            scope.launch { Store.saveCapturedHtml(ctx, html) }
            // 粗判一下这页到底像不像课表，省得白抓一张登录页
            val looksLikeTimetable = listOf("星期", "周一", "节次", "课程表", "第1节", "第一节")
                .count { html.contains(it) }
            msg = buildString {
                append("抓到 ${html.length / 1024} KB。")
                append(if (looksLikeTimetable >= 2) "看着像课表页 ✓" else "没看到「星期 / 节次」这类字样，可能不是课表页 —— 先在页面里点到课表再抓。")
            }
        }
    }

    MaterialTheme(colorScheme = scheme) {
        Surface(color = pal.paper, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {

                Row(
                    Modifier.fillMaxWidth().background(pal.panel).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("教务系统导入", color = pal.ink, fontSize = 14.sp)
                        Text(
                            currentUrl,
                            color = pal.faint,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1
                        )
                    }
                    OutlineChip(pal, if (desktopUa) "手机版" else "电脑版") {
                        desktopUa = !desktopUa
                        webView?.let { wv ->
                            wv.settings.userAgentString = if (desktopUa) DESKTOP_UA else MOBILE_UA
                            wv.reload()
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                    OutlineChip(pal, "关闭", onClick = onFinish)
                }
                HorizontalDivider(thickness = 1.dp, color = pal.rule)

                Box(Modifier.weight(1f)) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { c ->
                            WebView(c).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.useWideViewPort = true
                                settings.loadWithOverviewMode = true
                                settings.builtInZoomControls = true
                                settings.displayZoomControls = false
                                CookieManager.getInstance().setAcceptCookie(true)
                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                webViewClient = object : WebViewClient() {
                                    override fun doUpdateVisitedHistory(
                                        view: WebView?, url: String?, isReload: Boolean
                                    ) {
                                        if (url != null) currentUrl = url
                                    }
                                }
                                loadUrl(startUrl)
                                webView = this
                            }
                        }
                    )
                }

                HorizontalDivider(thickness = 1.dp, color = pal.rule)
                Column(Modifier.fillMaxWidth().background(pal.panel).padding(10.dp)) {
                    msg?.let {
                        MsgBox(pal, it)
                        Spacer(Modifier.height(8.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PrimaryButton(pal, "抓取本页课表") { capture() }
                        Spacer(Modifier.width(8.dp))
                        OutlineChip(pal, "保存 HTML", enabled = captured != null) {
                            saver.launch("jwxt-page.html")
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Hint(
                        pal,
                        "先在上面自己登录、点到课表页面，再点「抓取本页课表」。" +
                            "抓到之后点「保存 HTML」存成文件发我 —— 每所学校页面结构都不一样，" +
                            "我看到真实页面才能写对解析器。"
                    )
                }
            }
        }
    }

    BackHandler(enabled = true) {
        val wv = webView
        if (wv != null && wv.canGoBack()) wv.goBack() else onFinish()
    }
}

/** 有些教务系统只在桌面 UA 下才渲染完整的课表表格。 */
private const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"

private const val MOBILE_UA =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
