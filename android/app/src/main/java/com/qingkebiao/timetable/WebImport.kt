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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.font.FontWeight
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
        /** 已经知道开学日期就不必再问"今天是第几周" */
        const val EXTRA_HAS_TERM = "has_term"
        const val EXTRA_HOME = "home_url"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val start = intent.getStringExtra(EXTRA_URL)?.takeIf { it.isNotBlank() }
            ?: "https://www.baidu.com"
        val hasTerm = intent.getBooleanExtra(EXTRA_HAS_TERM, false)
        val home = intent.getStringExtra(EXTRA_HOME).orEmpty()
        setContent { WebImportScreen(start, hasTerm, home) { finish() } }
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
private fun WebImportScreen(
    startUrl: String,
    hasTerm: Boolean,
    homeUrl: String,
    onFinish: () -> Unit
) {
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
    var parser by remember { mutableStateOf("") }
    var currentWeek by remember { mutableIntStateOf(1) }
    var askWeek by remember { mutableStateOf(!hasTerm) }
    var imported by remember { mutableStateOf(false) }
    var autoTried by remember { mutableStateOf("") }
    var sniffed by remember { mutableStateOf<List<PeriodSlot>?>(null) }
    var groups by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }
    // 已存下来的设置。预览必须和 Store.importZf 用同一套取值规则，
    // 否则"先看预览再导入"就成了摆设 —— 看到的和导进去的不是一回事。
    var stored by remember { mutableStateOf<Timetable?>(null) }
    LaunchedEffect(Unit) { stored = Store.load(ctx) }

    // 和 Store.importZf 里的优先级严格一致
    val storedIsUserSet = stored?.periodsSource == "sniffed" || stored?.periodsSource == "manual"
    val effPeriods: List<PeriodSlot> = when {
        sniffed != null -> sniffed!!
        storedIsUserSet -> stored!!.periods
        groups.isNotEmpty() -> Periods.generate(groups)
        else -> stored?.periods?.ifEmpty { DEFAULT_PERIODS } ?: DEFAULT_PERIODS
    }
    val periodsNote: String = when {
        sniffed != null -> "已从这次浏览的页面读到真实作息 ✓"
        storedIsUserSet && stored?.periodsSource == "sniffed" -> "用之前从教务系统读到的作息 ✓"
        storedIsUserSet -> "用你自己设过的作息 ✓"
        else -> Periods.describe(groups) + "，不准就在设置里改"
    }

    // 已经有课表时，这次抓到的就不是"导入"而是"对一遍有没有调课"。
    // 差异比整张课表有用得多 —— 学校调一节课，用户要看的是那一节，不是全部 21 个块。
    val oldBlocks = stored?.zfBlocks.orEmpty()
    val isResync = oldBlocks.isNotEmpty()
    val changes: List<Diff.Change> = remember(blocks, oldBlocks, effPeriods) {
        val bs = blocks
        if (bs == null || !isResync) emptyList() else Diff.compare(oldBlocks, bs, effPeriods)
    }

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

    fun capture(auto: Boolean = false) {
        val wv = webView ?: return
        if (!auto) msg = "抓取中…"
        wv.evaluateJavascript(GRAB_JS) { raw ->
            // evaluateJavascript 回来的是 JSON 字面量，要先解引号和反转义
            val html = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
            if (html.isNullOrBlank() || html.length < 200) {
                if (!auto) msg = "没抓到有效内容。确认页面已经显示出课表了再抓。"
                return@evaluateJavascript
            }
            captured = html
            scope.launch { Store.saveCapturedHtml(ctx, html) }

            // 任何页面都嗅探一遍作息时间：用户可能先逛到"作息时间"页再去课表页。
            // 读到了就立刻存下来，不用等他导入。
            Periods.sniff(html)?.let { ps ->
                sniffed = ps
                scope.launch { Store.savePeriods(ctx, ps) }
            }
            groups = Periods.deriveGroups(html).ifEmpty { groups }

            // 先用正方的结构化解析；认不出再退到通用表格兜底。
            // 通用解析是猜的，所以下面会先把结果预览给用户，由他确认再导入。
            val zf = runCatching { Zf.parse(html) }
            val res = if (zf.isSuccess) zf.map { it to "zf" }
            else runCatching { Generic.parse(html) }.map { it to "generic" }

            res.fold(
                onSuccess = { (bs, which) ->
                    blocks = bs
                    parser = which
                    err = false
                    // 抓到并比对过就算查过了，不管有没有变动、用户点不点应用
                    if (oldBlocks.isNotEmpty()) scope.launch { Store.markSynced(ctx) }
                    msg = buildString {
                        append(if (which == "zf") "正方课表解析成功：" else "通用表格解析（尽力而为）：")
                        append("${Zf.courseCount(bs)} 门课、${bs.size} 个课程块、")
                        append("共 ${bs.sumOf { b -> b.weeks.size }} 次上课，最大第 ${Zf.maxWeek(bs)} 周。")
                        if (which == "generic") {
                            append("\n这不是已知的教务系统，结果是猜的 —— 先看下面的预览对不对再导入。")
                        }
                    }
                },
                onFailure = { e ->
                    blocks = null
                    parser = ""
                    if (!auto) {
                        err = true
                        msg = "抓到 ${html.length / 1024} KB，但没认出课表：${e.message}" +
                            "\n如果这页确实是课表，点「保存 HTML」发我，我给这套系统加一个解析器。"
                    }
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
                                        // 页面稳定后自动试解析一次：课表页一加载出来就直接出结果，
                                        // 不用用户再去找"抓取"按钮。同一个 url 只自动试一次。
                                        if (url != null && url != autoTried && blocks == null) {
                                            autoTried = url
                                            view?.postDelayed({ capture(auto = true) }, 1200)
                                        }
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
                        Spacer(Modifier.height(6.dp))
                        // 这行必须实时渲染，不能塞进 msg：msg 是抓取那一刻的快照，
                        // 而作息来源依赖异步读出来的存储，烤进字符串就会永远显示旧值。
                        Text(
                            "时间：" + periodsNote,
                            color = if (sniffed != null || storedIsUserSet) pal.ink2 else pal.muted,
                            fontSize = 11.5.sp, lineHeight = 16.sp
                        )
                        Spacer(Modifier.height(8.dp))
                    }

                    // 解析结果先给用户看。通用解析是猜的，这个预览就是安全阀。
                    blocks?.takeIf { !imported }?.let { bs ->
                        val dow = "一二三四五六日"

                        if (isResync) {
                            // 重新同步：只列变动。没变动就明说没变动，别让人自己去比。
                            if (changes.isEmpty()) {
                                Text(
                                    "和现在的课表一样，没有调课。",
                                    color = pal.ink2, fontSize = 12.5.sp
                                )
                            } else {
                                Text(
                                    "教务系统上的课表变了：${Diff.summarize(changes)}",
                                    color = pal.signal, fontSize = 12.5.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(Modifier.height(6.dp))
                                changes.take(8).forEach { c ->
                                    Text(
                                        "【${Diff.label(c.kind)}】${c.title}",
                                        color = pal.ink, fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold, maxLines = 1
                                    )
                                    if (c.before.isNotBlank()) {
                                        Text(
                                            "  原：${c.before}",
                                            color = pal.faint, fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace, maxLines = 1
                                        )
                                    }
                                    if (c.after.isNotBlank()) {
                                        Text(
                                            "  现：${c.after}",
                                            color = pal.ink2, fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace, maxLines = 1
                                        )
                                    }
                                    Spacer(Modifier.height(4.dp))
                                }
                                if (changes.size > 8) {
                                    Text("…… 还有 ${changes.size - 8} 处", color = pal.faint, fontSize = 11.sp)
                                }
                            }
                        } else {
                            bs.sortedWith(compareBy({ it.weekday }, { it.startPeriod })).take(6).forEach { b ->
                                Text(
                                    "周${dow[b.weekday - 1]} ${b.startPeriod}-${b.endPeriod}节 " +
                                        periodRange(b.startPeriod, b.endPeriod, effPeriods) +
                                        "  ${b.title}" +
                                        (if (b.location.isNotBlank()) "  ${b.location}" else ""),
                                    color = pal.ink2, fontSize = 11.5.sp,
                                    fontFamily = FontFamily.Monospace, maxLines = 1
                                )
                            }
                            if (bs.size > 6) {
                                Text("…… 还有 ${bs.size - 6} 个", color = pal.faint, fontSize = 11.sp)
                            }
                        }
                        Spacer(Modifier.height(8.dp))

                        // 学期起点只在还没定过的时候问一次，之后重新同步不再打扰
                        if (askWeek) {
                            FieldRow(
                                pal, "今天是第几周",
                                "教务系统只给周次不给日期，靠这个反推开学日期"
                            ) {
                                IntStepper(pal, currentWeek, 1, Zf.maxWeek(bs).coerceAtLeast(1), suffix = " 周") {
                                    currentWeek = it
                                }
                            }
                        } else {
                            FieldRow(pal, "学期起点", "沿用已保存的设置，可在设置里改") {
                                OutlineChip(pal, "改一下") { askWeek = true }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (blocks != null && !imported) {
                            val applyLabel = when {
                                !isResync -> "导入课表"
                                changes.isEmpty() -> "没有变动，仍然覆盖"
                                else -> "应用这 ${changes.size} 处变动"
                            }
                            PrimaryButton(pal, applyLabel) {
                                scope.launch {
                                    runCatching {
                                        Store.importZf(
                                            ctx, blocks!!,
                                            currentWeek = if (askWeek) currentWeek else null,
                                            pageUrl = currentUrl,
                                            homeUrl = homeUrl,
                                            parser = parser,
                                            sniffedPeriods = sniffed,
                                            groups = groups
                                        )
                                    }.fold(
                                        onSuccess = { tt ->
                                            imported = true
                                            err = false
                                            // 作息到底是哪来的，下面那行 periodsNote 会实时显示，
                                            // 这里别再写死一句"按内置作息表"跟它打架
                                            msg = if (isResync) {
                                                "已更新：${Diff.summarize(changes)}。" +
                                                    "作息、开学日期、你手动加的课和调休记录都没动。"
                                            } else {
                                                "已导入 ${tt.sessions.size} 节课。" +
                                                    "时间对不上就到设置里改「节次时间」，改完会自动重算。"
                                            }
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
                        if (imported) "完成，可以关掉这个页面了。"
                        else if (blocks != null && isResync && changes.isEmpty())
                            "没有调课，不用做任何事，直接关掉就行。"
                        else if (blocks != null && isResync)
                            "看一下这些变动对不对，确认了再点应用。不想现在改就直接关掉，课表保持原样。"
                        else if (blocks != null) "确认上面的预览没问题就点「导入课表」。"
                        else "在上面登录，点到课表页面 —— 课表一显示出来就会自动解析，" +
                            "不用手动点。没反应再点「抓取本页课表」。"
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

/** 把"第 a-b 节"换算成人能看的时刻段，预览里用。 */
private fun periodRange(a: Int, b: Int, periods: List<PeriodSlot>): String {
    val s = periods.firstOrNull { it.index == a } ?: return ""
    val e = periods.firstOrNull { it.index == b } ?: s
    return s.startMin.hhmm() + "-" + e.endMin.hhmm()
}
