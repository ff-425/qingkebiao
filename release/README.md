# 更新用的两个文件

App 里填一个站点地址，它就去 `<地址>/version.json` 问有没有新版；有就按里面的
`url` 把 APK 拉下来、直接唤起安装。整个过程不用出 App，也不用碰文件管理器。

## 放到 Cloudflare Pages

1. 建一个文件夹，放两个文件：

   ```
   qingkebiao.apk      ← 新版包
   version.json        ← 下面这个
   ```

2. `version.json` 里的 `url` 写成相对路径，这样换域名不用改：

   ```json
   {
     "versionCode": 10,
     "versionName": "10",
     "url": "qingkebiao.apk",
     "size": 11700000,
     "notes": "这版改了什么，一句话"
   }
   ```

3. <https://dash.cloudflare.com> → Workers & Pages → Create → Pages → Upload assets，
   把这个文件夹拖进去，拿到 `https://xxx.pages.dev`

4. App 里：设置 → 版本与更新 → 检查更新，地址填 `xxx.pages.dev`

以后每发一版，把新的 APK 和改过 `versionCode` 的 `version.json` 重新传一次就行。

## 字段

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `versionCode` | 是 | 整数，比手机上装的那个大才会提示更新 |
| `versionName` | 否 | 显示给人看的版本号 |
| `url` | 是 | APK 地址。相对路径按 `version.json` 所在位置解析 |
| `size` | 否 | 字节数，只用来显示"多少 MB"和校验下载是否完整 |
| `notes` | 否 | 一句话更新说明，会显示在提示条和更新面板上 |

## 几条硬性规定

- **只接受 https**。明文 http 直接拒绝，不管是 `version.json` 还是 APK。
- **签名必须一致**。Android 不允许用不同签名的包覆盖已安装的应用。就算这个地址
  被人换掉，换上去的包也装不进来，而不是悄悄替换掉你的 App。
- **versionCode 只能涨不能降**。降了系统会拒装（`INSTALL_FAILED_VERSION_DOWNGRADE`），
  这是故意的：旧版读不懂新的数据文件。

## 仓库里这份 version.json

`url` 指向 GitHub Release，是 CI 每次推 main 自动更新的那个包。国内手机上
GitHub 基本打不开，所以这份只是个能跑的示例，实际用还是按上面传到 Cloudflare。
