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

/**
 * 在每个页面开始加载时注入：挂钩 XHR 和 fetch，把返回的 JSON 响应记下来。
 *
 * 很多教务系统（正方新版就是）课表不在 HTML 里，而是页面用 XHR 取回一段 JSON
 * 再填进表格。直接拿到那段 JSON 比解析渲染后的 DOM 可靠得多。
 * 这里不写死任何接口地址，纯粹记录"长得像 JSON 的响应"，所以对各家系统通用。
 */
private const val INJECT_JS = """
(function(){
  if (window.__qkb_hooked) return;
  window.__qkb_hooked = true;
  window.__qkb_captures = [];
  function keep(url, body, text){
    try {
      if (!text) return;
      var t = String(text);
      if (t.length < 40) return;
      var c = t.charAt(0);
      if (c !== '{' && c !== '[') return;
      window.__qkb_captures.push({ url: String(url), body: String(body || ''), resp: t.slice(0, 500000) });
      if (window.__qkb_captures.length > 15) window.__qkb_captures.shift();
    } catch (e) {}
  }
  var XO = XMLHttpRequest.prototype.open, XS = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open = function(m, u){ this.__qkb_url = u; return XO.apply(this, arguments); };
  XMLHttpRequest.prototype.send = function(b){
    var self = this;
    try {
      this.addEventListener('load', function(){ keep(self.__qkb_url, b, self.responseText); });
    } catch (e) {}
    return XS.apply(this, arguments);
  };
  var F = window.fetch;
  if (F) {
    window.fetch = function(){
      var a = arguments;
      return F.apply(this, a).then(function(r){
        try { r.clone().text().then(function(t){ keep(a[0], '', t); }); } catch (e) {}
        return r;
      });
    };
  }
})()
"""

/** 抓当前页 + 所有同源 iframe 的 HTML，外加记录到的 JSON 响应。 */
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
  try {
    var caps = window.__qkb_captures || [];
    for (var k = 0; k < caps.length; k++) {
      parts.push('<!-- XHR-JSON ' + caps[k].url + ' | POST-BODY: ' + caps[k].body + ' -->\n' + caps[k].resp);
    }
  } catch (e) {}
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
    var err by remember { mutableStateOf(false) }
    var captured by remember { mutableStateOf<String?>(null) }
    var blocks by remember { mutableStateOf<List<Zf.Block>?>(null) }
    var currentWeek by remember { mutableIntStateOf(1) }
    var imported by remember { mutableStateOf(false) }

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

            // 直接就地解析。解析成功就不用再存文件发给谁了。
            val r = runCatching { Zf.parse(html) }
            r.fold(
                onSuccess = { bs ->
                    blocks = bs
                    err = false
                    msg = "解析成功：${Zf.courseCount(bs)} 门课、${bs.size} 个课程块、" +
                        "共 ${bs.sumOf { b -> b.weeks.size }} 次上课，最大第 ${Zf.maxWeek(bs)} 周。\n" +
                        "下面确认「今天是第几周」就能导入。"
                },
                onFailure = { e ->
                    blocks = null
                    err = true
                    val jsonCount = Regex("<!-- XHR-JSON ").findAll(html).count()
                    msg = "抓到 ${html.length / 1024} KB" +
                        (if (jsonCount > 0) "（含 $jsonCount 段接口 JSON）" else "") +
                        "，但没解析出课表：${e.message}\n" +
                        "可以点「保存 HTML」存下来发我看。"
                }
            )
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
                                    // 越早注入越好：课表那个 XHR 在页面脚本跑起来后很快就发了
                                    override fun onPageStarted(
                                        view: WebView?, url: String?, favicon: android.graphics.Bitmap?
                                    ) {
                                        view?.evaluateJavascript(INJECT_JS, null)
                                        if (url != null) currentUrl = url
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        view?.evaluateJavascript(INJECT_JS, null)
                                    }

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
                        MsgBox(pal, it, err)
                        Spacer(Modifier.height(8.dp))
                    }

                    // 课表页只给"第几周"，不给日期。问一句今天是第几周就能定住整个学期。
                    if (blocks != null && !imported) {
                        FieldRow(
                            pal, "今天是第几周",
                            "教务系统只给周次不给日期，靠这个反推开学日期"
                        ) {
                            IntStepper(pal, currentWeek, 1, Zf.maxWeek(blocks!!).coerceAtLeast(1), suffix = " 周") {
                                currentWeek = it
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (blocks != null && !imported) {
                            PrimaryButton(pal, "导入课表") {
                                scope.launch {
                                    runCatching { Store.importZf(ctx, blocks!!, currentWeek) }.fold(
                                        onSuccess = { tt ->
                                            imported = true
                                            err = false
                                            msg = "已导入 ${tt.sessions.size} 节课。" +
                                                "时间是按内置作息表换算的，和你学校不一样就到设置里改「节次时间」。"
                                        },
                                        onFailure = { e -> err = true; msg = "导入失败：${e.message}" }
                                    )
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                        }
                        PrimaryButton(pal, "抓取本页课表") { capture() }
                        Spacer(Modifier.width(8.dp))
                        OutlineChip(pal, "保存 HTML", enabled = captured != null) {
                            saver.launch("jwxt-page.html")
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Hint(
                        pal,
                        if (imported) "导入完成，可以关掉这个页面了。"
                        else "先在上面自己登录、点到课表页面，等课表显示出来，再点「抓取本页课表」。"
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
