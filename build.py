# -*- coding: utf-8 -*-
"""
从 dist/_body.html（页面结构）生成两个外壳：

  dist/index.html   托管用的完整文档 —— 有 manifest、Service Worker、
                    viewport-fit=cover、图标，能"添加到主屏幕"
  index.html        Artifact 用的片段 —— claude.ai 自己会套 head/body，
                    所以这里不能有 doctype/html/head/body

app.css 和 app.js 两边共用，改逻辑只改那两个文件，然后跑一次 `python build.py`。
"""
import pathlib

root = pathlib.Path(__file__).parent
dist = root / 'dist'
body = (dist / '_body.html').read_text(encoding='utf-8').strip('\n')

FONTS = (
    '<link rel="preconnect" href="https://fonts.googleapis.com">\n'
    '<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>\n'
    '<link rel="stylesheet" href="https://fonts.googleapis.com/css2?'
    'family=Archivo:wght@400;500;600;700&'
    'family=Archivo+Narrow:wght@400;500;600;700&'
    'family=IBM+Plex+Mono:wght@400;500;600&display=swap">'
)

# ---------------------------------------------------------------- 托管版
hosted = f"""<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<title>清课表</title>
<meta name="description" content="无广告的课程表：导入学校的 .ics 日历，按周次显示课表。数据只存在本机。">

<link rel="manifest" href="manifest.webmanifest">
<meta name="theme-color" content="#EBEEEC" media="(prefers-color-scheme: light)">
<meta name="theme-color" content="#101312" media="(prefers-color-scheme: dark)">

<!-- iOS 加到主屏幕后全屏运行、用自己的图标和名字 -->
<meta name="apple-mobile-web-app-capable" content="yes">
<meta name="mobile-web-app-capable" content="yes">
<meta name="apple-mobile-web-app-status-bar-style" content="default">
<meta name="apple-mobile-web-app-title" content="清课表">
<link rel="apple-touch-icon" href="icon-180.png">
<link rel="icon" href="icon-192.png" type="image/png">

{FONTS}
<link rel="stylesheet" href="app.css">
</head>
<body>
{body}

<script src="app.js"></script>
<script>
/* Service Worker 负责离线打开。只在 https / localhost 下注册：
   直接双击本地文件（file://）时浏览器不允许注册，这里静默跳过，App 照常用。 */
if ('serviceWorker' in navigator &&
    (location.protocol === 'https:' || location.hostname === 'localhost')){{
  addEventListener('load', () => navigator.serviceWorker.register('sw.js').catch(() => {{}}));
}}
</script>
</body>
</html>
"""
(dist / 'index.html').write_text(hosted, encoding='utf-8')

# ---------------------------------------------------------------- Artifact 版
artifact = f"""<title>清课表</title>
{FONTS}
<link rel="stylesheet" href="app.css">

{body}

<script src="app.js"></script>
"""
(root / 'index.html').write_text(artifact, encoding='utf-8')

print('wrote dist/index.html (%d B) and index.html (%d B)' % (
    (dist / 'index.html').stat().st_size, (root / 'index.html').stat().st_size))
