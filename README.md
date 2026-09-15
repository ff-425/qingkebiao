# 清课表

无广告的课程表。导入学校的 `.ics` 日历，按周次显示课表。课表只存在浏览器的
localStorage 里，不上传任何服务器。

## 目录结构

```
app.css   app.js        全部样式与逻辑（两个外壳共用，改逻辑只改这两个）
dist/_body.html         页面结构（唯一一份）
build.py                生成两个外壳
dist/index.html         托管用：完整文档，含 manifest / Service Worker / 图标
index.html              Artifact 用：片段，claude.ai 自己套 head/body
dist/manifest.webmanifest  dist/sw.js  dist/icon-*.png
```

改完 `app.css` / `app.js` / `dist/_body.html` 之后跑一次：

```bash
python build.py
```

图标要重画的话，改 `scratchpad/icons.js` 里的坐标再跑
`node icons.js dist`（无依赖，Node 自带 zlib 手写 PNG）。

## 部署成手机 App

要的是"主屏幕上有图标、点开全屏、没网也能看"，就得挂到一个 HTTPS 地址上
（`file://` 和 `http://` 都注册不了 Service Worker）。

**Cloudflare Pages（最省事，免费，不用 Git）**

1. 打开 <https://dash.cloudflare.com> → Workers & Pages → Create → Pages →
   Upload assets
2. 把 `dist/` 文件夹整个拖进去
3. 拿到 `https://xxx.pages.dev`

GitHub Pages / Netlify / Vercel 同理，把 `dist/` 当站点根目录即可。

**加到主屏幕**

- iPhone（必须用 Safari）：打开网址 → 分享 → 添加到主屏幕
- Android Chrome：打开网址 → 右上 ⋮ → 安装应用／添加到主屏幕

装好后是独立窗口，没有地址栏，图标和名字都是自己的。

## 更新时的坑

`dist/sw.js` 用的是**网络优先、失败回落缓存**，不是缓存优先。缓存优先会让你
重新部署后手机上还是旧版本、要刷两次才更新 —— 这个 App 只有 60KB 上下，联网
时多一次请求无感。离线时全部走缓存，功能不受影响。

改动较大时把 `sw.js` 里的 `CACHE = 'qingkebiao-v1'` 版本号 +1，旧缓存会在
`activate` 里清掉。

## ICS 解析支持到哪

- 75 字节折行还原（RFC 5545），长课程名不会被截断
- `RRULE`：`FREQ=WEEKLY` + `INTERVAL`（单双周课靠这个）+ `BYDAY` + `COUNT` /
  `UNTIL` / `WKST`；`DAILY` / `MONTHLY` / `YEARLY` 有兜底
- `EXDATE`：假期停课那一次会被挖掉
- `RECURRENCE-ID`：调课会覆盖母事件那一次，不重复显示
- 时区：`TZID=Asia/Shanghai` 用 `Intl` 反解偏移，`...Z` 按 UTC 转本地，
  无时区的当墙钟时间 —— 没有硬编码 +8
- `DURATION` 在缺 `DTEND` 时兜底；`STATUS:CANCELLED` 丢弃
- 从 `DESCRIPTION` 提取教师名，在下一个「键：值」或双空格处收住

**"第 1 周"是校历定的，ICS 里没有这个信息**，默认取最早一节课所在的周。对不上
就在设置里改「第 1 周的周一」，其余全部跟着重算。

## PWA 做不到的两件事

桌面小组件、上课前推送通知 —— iOS 网页拿不到小组件，Android 网页通知不可靠。
要这两个只能上原生（Kotlin + Compose Glance 做小组件，WorkManager 做提醒）。
`app.js` 里的 ICS 解析是纯逻辑，翻成 Kotlin 很直接。

自动同步学校 ICS 也做不到：浏览器过不了 CORS。要么手动重新导入（一学期几次），
要么架个代理转发。

---

# Android 原生版

PWA 的天花板是没有桌面小组件、没有上课推送、启动有一瞬白屏。原生版解决这些。
ICS 解析逻辑从 `app.js` 移植到 `android/.../Ics.kt`，行为一致。

## 云端构建（本机不用装 JDK / Android SDK）

```
git init && git add -A && git commit -m "init"
git branch -M main
git remote add origin https://github.com/<你的用户名>/<仓库名>.git
git push -u origin main
```

推上去之后 `.github/workflows/android.yml` 自动跑，几分钟出包，并更新一个叫
`latest` 的 Release。之后手机上永远用这个固定直链下载：

```
https://github.com/<你的用户名>/<仓库名>/releases/latest/download/qingkebiao.apk
```

改了 `android/` 下的东西，再 `git push` 一次就有新包。也可以在 GitHub 的
Actions 页面手动点 **Run workflow**。

## 小米上安装

APK 用的是 debug 签名（个人自用不折腾 keystore），所以属于"未知来源"：

1. 手机浏览器打开上面那个直链，下载
2. 点安装，系统会拦一下 → 允许这个来源安装
3. MIUI 可能还会弹"安装前先扫描"，等它扫完点继续

## 加桌面小组件

长按桌面空白处 → 添加小组件 → 找到「清课表」，有两个：

- **今日课表**（4x3）：今天每节课的时间、名称、地点；正在上的那节竖条变信号红
- **下一节**（2x1）：一条窄条，显示下一节课和还有多久，或者"进行中 · 还有 N 分钟"

小组件和 App 读的是同一个 JSON 文件，导入课表后立刻刷新。此外每 15 分钟
由 WorkManager 刷一次（这是系统周期任务的下限），保证"进行中"及时变色。

## 工程结构

```
android/app/src/main/java/com/qingkebiao/timetable/
  Ics.kt          ICS 解析（从网页版 JS 移植）
  Model.kt        Session / Timetable / 周次推算 / 重叠排版 / 时间轴上下界
  Store.kt        JSON 文件读写、系统文件选择器读取、HTTP 拉取订阅链接、示例课表
  Palette.kt      配色与课程色相环（和网页版同一套）
  MainActivity.kt Compose UI：顶栏、下一节条、周视图、今日视图、导入/设置/详情弹窗
  widget/Widgets.kt  两个 Glance 小组件 + 刷新任务
```

## 原生比网页多出来的能力

**可以直接拉订阅链接。** 网页版过不了 CORS，原生没这个限制，所以导入弹窗第 2 条
"直接拉订阅链接"在原生里是真能用的，链接会存下来，设置里有「重新拉取课表」。

## v1 没做的

**上课前推送提醒。** 依赖 WorkManager 排程 + 通知权限，小米还要额外处理省电策略
白名单（否则后台会被杀）。留到 v2。
