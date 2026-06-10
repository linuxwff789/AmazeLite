#!/bin/bash
# setup_env.sh — AmazeLite 开发环境初始化
# 作用：检查/补全所有开发工具和已知文件，避免东拼西凑
# 用法：bash /root/AmazeLite/setup_env.sh
set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

pass()  { echo -e "  ${GREEN}✅${NC} $1"; }
warn()  { echo -e "  ${YELLOW}⚠️${NC} $1"; }
fail()  { echo -e "  ${RED}❌${NC} $1"; }

echo "═══════════════════════════════════════════════"
echo "  AmazeLite 开发环境自检"
echo "═══════════════════════════════════════════════"

errs=0

# ── 1. Android SDK ──
echo ""
echo "▸ Android SDK"
SDK="/opt/android-sdk"
if [ -d "$SDK" ]; then
    pass "SDK: $SDK"
    # platforms
    if ls "$SDK/platforms/" >/dev/null 2>&1; then
        for p in "$SDK/platforms"/*; do
            [ -d "$p" ] && pass "   platform: $(basename $p)"
        done
    else
        warn "   platforms 目录为空"
    fi
    # build-tools
    if [ -f "$SDK/build-tools/35.0.0/d8" ]; then
        pass "   d8: $SDK/build-tools/35.0.0/d8"
    else
        fail "   d8 未找到（需要 build-tools 35.0.0）"; ((errs++))
    fi
    if [ -f "$SDK/build-tools/35.0.0/apksigner" ]; then
        pass "   apksigner: $SDK/build-tools/35.0.0/apksigner"
    else
        fail "   apksigner 未找到"; ((errs++))
    fi
else
    fail "SDK 目录 $SDK 不存在"; ((errs++))
fi

# ── 2. NDK ──
echo ""
echo "▸ NDK"
NDK="$SDK/ndk/27.0.12077973"
if [ -d "$NDK" ]; then
    pass "NDK: $NDK"
else
    fail "NDK 27 未找到"; ((errs++))
fi

# ── 3. 系统工具 ──
echo ""
echo "▸ 系统工具"
for cmd in aapt2 zipalign java javac clang++; do
    if which "$cmd" >/dev/null 2>&1; then
        pass "$cmd: $(which $cmd)"
    else
        fail "$cmd 未安装"; ((errs++))
    fi
done

# ── 4. 签名密钥 ──
echo ""
echo "▸ 签名密钥"
KS="/root/debug.keystore"
if [ -f "$KS" ]; then
    pass "keystore: $KS"
    # 验证密码
    if keytool -list -keystore "$KS" -storepass android -alias operit >/dev/null 2>&1; then
        pass "   别名 operit 可用（密码: android）"
    else
        warn "   别名 operit 验证失败，尝试创建..."
        keytool -genkey -keystore "$KS" -alias operit -keyalg RSA -validity 3650 \
            -storepass android -keypass android \
            -dname "CN=Operit, OU=Dev, O=Operit, L=Unknown, ST=Unknown, C=CN" 2>/dev/null && \
            pass "   keystore 已创建" || fail "   keystore 创建失败"
    fi
else
    warn "keystore 不存在，创建..."
    keytool -genkey -keystore "$KS" -alias operit -keyalg RSA -validity 3650 \
        -storepass android -keypass android \
        -dname "CN=Operit, OU=Dev, O=Operit, L=Unknown, ST=Unknown, C=CN" && \
        pass "   keystore 已创建" || fail "   keystore 创建失败"
fi

# ── 5. 项目源码 ──
echo ""
echo "▸ 项目源码"
PROJ="/root/AmazeLite"
JAVA_SRC="$PROJ/app/src/main/java/com/operit/amazelite/MainActivity.java"
ZPAQ_NATIVE="$PROJ/app/src/main/java/com/operit/zpaq/ZPAQNative.java"
JNI_CPP="$PROJ/app/src/main/cpp/zpaq_official_jni.cpp"
ZPAQCLI_CPP="$PROJ/app/src/main/cpp/zpaqcli/zpaq.cpp"
ANDROID_MANIFEST="$PROJ/app/src/main/AndroidManifest.xml"

for f in "$JAVA_SRC" "$ZPAQ_NATIVE" "$JNI_CPP" "$ZPAQCLI_CPP" "$ANDROID_MANIFEST"; do
    if [ -f "$f" ]; then
        pass "$(basename $f)"
    else
        fail "缺失: $f"; ((errs++))
    fi
done

# ── 6. native .so 产物 ──
echo ""
echo "▸ native 产物"
SO="$PROJ/app/src/main/jniLibs/arm64-v8a/libzpaq715_fixed.so"
LIBCPP="$PROJ/app/src/main/jniLibs/arm64-v8a/libc++_shared.so"
if [ -f "$SO" ]; then
    pass "libzpaq715_fixed.so ($(stat -c '%s bytes' $SO))"
else
    warn "libzpaq715_fixed.so 未编译，需要时执行: bash $PROJ/build_native_android_so.sh"
fi
if [ -f "$LIBCPP" ]; then
    pass "libc++_shared.so ($(stat -c '%s bytes' $LIBCPP))"
else
    warn "libc++_shared.so 缺失，需要从 NDK 复制"
fi

# ── 7. 设备端 zpaq 命令 ──
echo ""
echo "▸ 设备端 zpaq 命令"
if which adb >/dev/null 2>&1; then
    ZPAQ_DEVICE=$(adb shell "ls /data/local/tmp/zpaq 2>/dev/null && /data/local/tmp/zpaq 2>&1 | head -1" 2>/dev/null)
    if echo "$ZPAQ_DEVICE" | grep -q "zpaq v7"; then
        pass "设备端 zpaq: $(echo $ZPAQ_DEVICE | head -1)"
    else
        warn "设备端 /data/local/tmp/zpaq 不存在，如需使用 push 一个"
    fi
else
    warn "adb 不在 PATH 中，跳过设备端检查"
fi

# ── 8. 构建脚本 ──
echo ""
echo "▸ 构建脚本"
for script in build_all.sh build_zpaq_apk.sh build_native_android_so.sh; do
    if [ -f "$PROJ/$script" ] && [ -x "$PROJ/$script" ]; then
        pass "$script"
    else
        warn "$PROJ/$script 缺失或不可执行"
    fi
done

# ── 9. 环境变量 ──
echo ""
echo "▸ 环境变量"
if [ -n "$ANDROID_HOME" ] && [ "$ANDROID_HOME" = "$SDK" ]; then
    pass "ANDROID_HOME=$ANDROID_HOME"
else
    warn "ANDROID_HOME 未设置或未指向 $SDK（当前: ${ANDROID_HOME:-未设置}）"
    echo '  请执行: export ANDROID_HOME=/opt/android-sdk'
fi

# ── 10. Android 平台 JAR ──
echo ""
echo "▸ Android platform jar"
AJAR="$SDK/platforms/android-34/android.jar"
if [ -f "$AJAR" ]; then
    pass "android-34.jar"
else
    fail "缺失: $AJAR"; ((errs++))
fi

# ══ 汇总 ══
echo ""
echo "═══════════════════════════════════════════════"
if [ $errs -eq 0 ]; then
    echo -e "  ${GREEN}✅ 环境检查通过！${NC}"
else
    echo -e "  ${RED}❌ 发现 $errs 个问题，请修复后重试${NC}"
fi
echo "═══════════════════════════════════════════════"

# 打印快速参考
echo ""
echo "📋 快速参考："
echo "  构建 APK (Java 改):   bash $PROJ/build_zpaq_apk.sh"
echo "  构建全部 (C++ 改):    bash $PROJ/build_all.sh"
echo "  只编译 native:        bash $PROJ/build_native_android_so.sh"
echo "  查看项目文档:        cat $PROJ/README.md"
echo "  环境自检:            bash $PROJ/setup_env.sh"
echo ""