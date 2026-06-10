#!/bin/bash
set -e

PROJ="/root/AmazeLite"
OUT_SO="$PROJ/app/src/main/jniLibs/arm64-v8a/libzpaq715_fixed.so"
NDK="/opt/android-sdk/ndk/27.0.12077973"
SYSROOT="$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot"
RESOURCE_DIR="$NDK/toolchains/llvm/prebuilt/linux-x86_64/lib/clang/18"
API=24

mkdir -p "$(dirname "$OUT_SO")"

cd "$PROJ/app/src/main/cpp"
clang++ \
  --target=aarch64-linux-android${API} \
  --sysroot="$SYSROOT" \
  -resource-dir "$RESOURCE_DIR" \
  -rtlib=compiler-rt \
  -stdlib=libc++ \
  -std=c++17 \
  -fPIC \
  -shared \
  -O1 \
  -Dunix \
  -DNOJIT \
  -DZPAQ_NO_MAIN \
  zpaq_jni.cpp \
  zpaq_official_jni.cpp \
  zpaq/libzpaq.cpp \
  -I. \
  -Izpaq \
  -Izpaqcli \
  -Wl,-soname,libzpaq715_fixed.so \
  -llog \
  -lc++_shared \
  -o "$OUT_SO"

file "$OUT_SO"
readelf -h "$OUT_SO" | sed -n '1,20p'
echo "NATIVE_BUILD_SUCCESS"
