#!/usr/bin/env bash
# HifiCtl 一键构建：javac -> d8 -> aapt -> 加 dex -> zipalign -> apksigner
# 用法：
#   export ANDROID_SDK=/path/to/android-sdk      # 需含 build-tools/<ver>/ 与 platforms/android-28/
#   export BUILD_TOOLS_VERSION=35.0.0            # 可选，默认取 SDK 里最大版本
#   bash build.sh
# 产物：out/HifiCtl.apk
set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
APP="$ROOT/app"
OUT="$ROOT/out"

: "${ANDROID_SDK:?请设置 ANDROID_SDK，指向含 build-tools/ 和 platforms/ 的 Android SDK}"

if [ -z "${BUILD_TOOLS_VERSION:-}" ]; then
  BUILD_TOOLS_VERSION="$(ls -1 "$ANDROID_SDK/build-tools" | sort -V | tail -1)"
fi
BT="$ANDROID_SDK/build-tools/$BUILD_TOOLS_VERSION"
AJAR="$ANDROID_SDK/platforms/android-28/android.jar"

for f in "$BT/d8" "$BT/aapt" "$BT/zipalign" "$BT/apksigner" "$AJAR"; do
  [ -e "$f" ] || { echo "缺少构建工具：$f" >&2; exit 1; }
done

rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/apk"

echo "[1/7] javac"
javac -source 8 -target 8 -encoding UTF-8 -classpath "$AJAR" \
      -d "$OUT/classes" $(find "$APP/src" -name '*.java')

echo "[2/7] d8"
"$BT/d8" --lib "$AJAR" --min-api 27 --output "$OUT/dex" \
         $(find "$OUT/classes" -name '*.class')

echo "[3/7] 组包目录"
cp "$APP/AndroidManifest.xml" "$OUT/apk/"
cp "$OUT/dex/classes.dex" "$OUT/apk/"
[ -d "$APP/res" ]     && cp -r "$APP/res" "$OUT/apk/"
[ -d "$APP/assets" ]  && cp -r "$APP/assets" "$OUT/apk/"

echo "[4/7] aapt 生成 base.apk"
cd "$OUT/apk"
RES_ARGS=""; [ -d res ] && RES_ARGS="-S res"
ASS_ARGS=""; [ -d assets ] && ASS_ARGS="-A assets"
"$BT/aapt" package -f -M AndroidManifest.xml $RES_ARGS $ASS_ARGS -I "$AJAR" -F "$OUT/base.apk"

echo "[5/7] 追加 classes.dex"
cp "$OUT/base.apk" "$OUT/work.apk"
cd "$OUT"
zip -j -q "$OUT/work.apk" "$OUT/apk/classes.dex"

echo "[6/7] zipalign + 签名"
"$BT/zipalign" -f 4 "$OUT/work.apk" "$OUT/aligned.apk"
KS="$APP/debug.keystore"   # 通用 android debug 签名，仅开发用途
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --out "$OUT/HifiCtl.apk" "$OUT/aligned.apk"

echo "[7/7] 验证"
"$BT/apksigner" verify "$OUT/HifiCtl.apk"
ls -lh "$OUT/HifiCtl.apk"
echo "完成：$OUT/HifiCtl.apk"
