#!/bin/bash
set -e

PROJ="/root/AmazeLite"
WORK="$PROJ/build/apk_work"
OUT="$PROJ/build/apk"
KEYSTORE="/root/debug.keystore"
SDK="/opt/android-sdk"

echo "=== 1/7 清理构建目录 ==="
rm -rf "$WORK" 2>/dev/null
mkdir -p "$WORK/gen" "$WORK/classes" "$WORK/dex" "$OUT"

echo "=== 2/7 使用已生成 native ==="
echo "提示: 当前 APK 构建直接打包 app/src/main/jniLibs/arm64-v8a/libzpaq715_fixed.so"
echo "如需重编 native，可执行 ./build_native_android_so.sh"
ls -l "$PROJ/app/src/main/jniLibs/arm64-v8a/libzpaq715_fixed.so"

echo "=== 3/7 aapt2 编译资源 ==="
/usr/bin/aapt2 compile --dir "$PROJ/app/src/main/res" -o "$WORK/compiled_res.zip" 2>&1

echo "=== 4/7 aapt2 链接资源 + 生成 R.java ==="
/usr/bin/aapt2 link -o "$WORK/apk-unsigned.zip" \
  -I "$SDK/platforms/android-34/android.jar" \
  --manifest "$PROJ/app/src/main/AndroidManifest.xml" \
  --java "$WORK/gen" \
  "$WORK/compiled_res.zip" 2>&1

echo "=== 5/7 编译 Java + 转 dex ==="
cd "$WORK" && unzip -o apk-unsigned.zip >/dev/null 2>&1 && cd "$PROJ"
find "$PROJ/app/src/main/java" -name "*.java" > "$WORK/sources.txt"
ANDROIDX="$SDK/extras/androidx/core.jar"
if [ -f "$ANDROIDX" ]; then
    javac -d "$WORK/classes" \
      -cp "$WORK/gen:$SDK/platforms/android-34/android.jar:$ANDROIDX" \
      "@$WORK/sources.txt" 2>&1
else
    javac -d "$WORK/classes" \
      -cp "$WORK/gen:$SDK/platforms/android-34/android.jar" \
      "@$WORK/sources.txt" 2>&1
fi
find "$WORK/classes" -name "*.class" > "$WORK/classes_list.txt"
if [ -f "$ANDROIDX" ]; then
    $SDK/build-tools/35.0.0/d8 --release \
      --lib "$SDK/platforms/android-34/android.jar" \
      --output "$WORK/dex" @"$WORK/classes_list.txt" "$ANDROIDX" 2>&1
else
    $SDK/build-tools/35.0.0/d8 --release \
      --lib "$SDK/platforms/android-34/android.jar" \
      --output "$WORK/dex" @"$WORK/classes_list.txt" 2>&1
fi
cp "$WORK/dex/classes.dex" "$WORK/"
ls -la "$WORK/classes.dex"

echo "=== 6/7 打包 APK ==="
mkdir -p "$WORK/lib/arm64-v8a"
cp "$PROJ/app/src/main/jniLibs/arm64-v8a/libzpaq715_fixed.so" "$WORK/lib/arm64-v8a/"
cp "$PROJ/app/src/main/jniLibs/arm64-v8a/libc++_shared.so" "$WORK/lib/arm64-v8a/"
cd "$WORK"
rm -f "$OUT/final_unaligned.apk"
zip -0 -X "$OUT/final_unaligned.apk" AndroidManifest.xml resources.arsc >/dev/null 2>&1
zip -r -X "$OUT/final_unaligned.apk" classes.dex lib/ res/ >/dev/null 2>&1

echo "=== 7/7 对齐 + 签名 ==="
/usr/bin/zipalign -f -v 4 "$OUT/final_unaligned.apk" "$OUT/final_aligned.apk" 2>&1 | tail -3
rm -f "$OUT/final.apk"
$SDK/build-tools/35.0.0/apksigner sign \
  --ks "$KEYSTORE" --ks-pass pass:android --ks-key-alias operit \
  --out "$OUT/final.apk" "$OUT/final_aligned.apk" 2>&1
cp "$OUT/final.apk" /sdcard/Download/AmazeLite.apk
echo "BUILD_SUCCESS"
