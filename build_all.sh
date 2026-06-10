#!/bin/bash
# build_all.sh — 一键构建 AmazeLite APK
# 自动检测 C++ 源码变更，决定是否需要重编 native
set -e

PROJ="/root/AmazeLite"
OUT_SO="$PROJ/app/src/main/jniLibs/arm64-v8a/libzpaq715_fixed.so"
JNI_CPP="$PROJ/app/src/main/cpp/zpaq_official_jni.cpp"
ZPAQ_CPP="$PROJ/app/src/main/cpp/zpaqcli/zpaq.cpp"

echo "═══════════════════════════════════════════════"
echo "  AmazeLite 一键构建"
echo "═══════════════════════════════════════════════"

# 检查 C++ 是否需要重编
NEED_NATIVE=false
if [ ! -f "$OUT_SO" ]; then
    echo ">>> native .so 不存在，需编译"
    NEED_NATIVE=true
elif [ "$JNI_CPP" -nt "$OUT_SO" ]; then
    echo ">>> $JNI_CPP 已更新，需重编 native"
    NEED_NATIVE=true
elif [ "$ZPAQ_CPP" -nt "$OUT_SO" ]; then
    echo ">>> $ZPAQ_CPP 已更新，需重编 native"
    NEED_NATIVE=true
else
    # 也检查 zpaq_jni.cpp 和 libzpaq.cpp
    for src in "$PROJ/app/src/main/cpp/zpaq_jni.cpp" "$PROJ/app/src/main/cpp/zpaq/libzpaq.cpp"; do
        if [ -f "$src" ] && [ "$src" -nt "$OUT_SO" ]; then
            echo ">>> $src 已更新，需重编 native"
            NEED_NATIVE=true
            break
        fi
    done
fi

if [ "$NEED_NATIVE" = true ]; then
    echo ""
    echo ">>> [1/2] 编译 native library ..."
    bash "$PROJ/build_native_android_so.sh"
    echo ">>> native library 编译完成"
else
    echo ">>> native library 无变更，跳过编译"
fi

echo ""
echo ">>> [2/2] 打包 APK ..."
bash "$PROJ/build_zpaq_apk.sh"

echo ""
echo "═══════════════════════════════════════════════"
echo "  ✅ 构建完成"
echo "  APK: /sdcard/Download/AmazeLite.apk"
echo "═══════════════════════════════════════════════"