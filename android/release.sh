#!/usr/bin/env bash
# 打发布包。别直接用 gradle 出来的那个 APK —— 它缺签名轮换证明。
#
# 背景：v22 换了发布签名（旧的是仓库里那个 debug.keystore，口令众所周知）。
# Android 认签名不认版本号，签名一换，老用户的包就更新不上去，只能卸载重装。
# 但 Android 9 起支持"签名轮换"：用旧 key 签一份「我授权新 key」的证明
# （lineage.bin），带上它，老用户就能直接覆盖安装，数据一点不丢。
#
# 所以每个发布包都要：gradle 编译 → apksigner 带 lineage 重签 → 验证。
# 签名方式是 v1/v2 用旧 key（Android 8 及以下认这个）、v3 带轮换证明
# （Android 9+ 认这个，并把应用身份切到新 key）。
#
# 用法：
#   ./release.sh              出 arm64 包（发给用户的）
#   ./release.sh x86_64       出模拟器测试包
set -euo pipefail

cd "$(dirname "$0")"
ABI="${1:-arm64-v8a}"

export JAVA_HOME="C:\\Users\\ASUS\\Android\\jdk17"
export ANDROID_HOME="C:\\Users\\ASUS\\Android\\sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
GRADLE=/c/Users/ASUS/Android/gradle-8.9/bin/gradle
APKSIGNER="C:/Users/ASUS/Android/sdk/build-tools/34.0.0/apksigner.bat"

for f in keystore.properties qingkebiao-release.jks lineage.bin debug.keystore; do
  [ -f "$f" ] || { echo "缺少 $f，没法出发布包"; exit 1; }
done
PW=$(grep '^storePassword=' keystore.properties | cut -d= -f2)
ALIAS=$(grep '^keyAlias=' keystore.properties | cut -d= -f2)

echo "==> 编译（$ABI）"
"$GRADLE" assembleDebug --console=plain -PqkbAbi="$ABI" 2>&1 | grep -E "^e: |BUILD" || true

APK=app/build/outputs/apk/debug/app-debug.apk
[ -f "$APK" ] || { echo "没找到构建产物"; exit 1; }

OUT="build-release-$ABI.apk"
cp "$APK" "$OUT"

echo "==> 带签名轮换证明重签"
"$APKSIGNER" sign \
  --ks debug.keystore --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android \
  --next-signer --ks qingkebiao-release.jks --ks-key-alias "$ALIAS" \
  --ks-pass "pass:$PW" --key-pass "pass:$PW" \
  --lineage lineage.bin --min-sdk-version 26 \
  "$OUT"

echo "==> 验证"
"$APKSIGNER" verify --print-certs --verbose "$OUT" 2>&1 \
  | grep -iE "Verified using v[23]|Signer #[12] certificate DN" || true

SIZE=$(stat -c%s "$OUT")
echo
echo "产物: android/$OUT"
echo "大小: $SIZE 字节 ($(awk "BEGIN{printf \"%.1f\", $SIZE/1048576}") MB)"
echo
echo "记得把 cloudflare-upload/version.json 里的 size 改成 $SIZE"
