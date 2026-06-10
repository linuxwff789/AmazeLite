# AmazeLite

轻量级 Android 文件管理器，内置 ZPAQ 压缩/解压支持。

## 项目结构

```
/root/AmazeLite/
├── build_zpaq_apk.sh           # Java 层 APK 构建脚本
├── build_native_android_so.sh  # C++ native library 编译脚本
├── build_all.sh                # 一键构建（native + APK）
├── README.md                   # 本文件
├── app/
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/operit/
│       │   ├── amazelite/
│       │   │   └── MainActivity.java      # 主活动（~2162行，核心业务逻辑）
│       │   │   └── ZpaqLib.java           # ZPAQ 辅助类
│       │   │   └── ZpaqTest.java          # 测试用
│       │   └── zpaq/
│       │       └── ZPAQNative.java        # ZPAQ JNI 绑定（~520行）
│       ├── cpp/
│       │   ├── zpaq_jni.cpp               # ❌ 旧的纯 libzpaq 实现（未使用）
│       │   ├── zpaq_official_jni.cpp      # ✅ 使用的 JNI 实现（zpaq CLI wrapper）
│       │   ├── zpaq/libzpaq.cpp / .h      # libzpaq 核心库（未使用，被 zpaqcli 替代）
│       │   └── zpaqcli/zpaq.cpp           # zpaq 官方 CLI 实现（核心）
│       ├── jniLibs/arm64-v8a/
│       │   ├── libzpaq715_fixed.so        # ✅ 编译产物（build_all 生成）
│       │   └── libc++_shared.so           # C++ 运行时
│       └── res/
│           ├── drawable/  (图标、背景)
│           ├── values/    (颜色、主题)
│           └── layout/    (UI 布局)
└── build/
    └── apk/                              # 构建产物
        ├── final_unaligned.apk
        ├── final_aligned.apk
        └── final.apk          # ✅ 最终 APK（自动复制到 /sdcard/Download/）
```

## 构建流程

### 一键构建（推荐）
```bash
cd /root/AmazeLite && bash build_all.sh
```
自动检测 native 是否需要重编，输出到 `/sdcard/Download/AmazeLite.apk`。

### 分步构建

#### 1️⃣ 只编译 C++ native (.so)
```bash
cd /root/AmazeLite && bash build_native_android_so.sh
```
输入: `zpaq_official_jni.cpp` + `zpaqcli/zpaq.cpp`
输出: `app/src/main/jniLibs/arm64-v8a/libzpaq715_fixed.so`

#### 2️⃣ 只打包 APK（使用已有 .so）
```bash
cd /root/AmazeLite && bash build_zpaq_apk.sh
```
输入: Java 源码 + .so
输出: `build/apk/final.apk`

### 构建工具链

| 工具 | 路径 |
|------|------|
| Android SDK | /opt/android-sdk |
| NDK | /opt/android-sdk/ndk/27.0.12077973 |
| aapt2 | /usr/bin/aapt2 |
| zipalign | /usr/bin/zipalign |
| 签名密钥 | /root/debug.keystore（密码: android） |

## 关键技术说明

### ZPAQ 架构（重要）

项目有两套独立的 ZPAQ 实现：

| 实现 | 文件 | 状态 | 说明 |
|------|------|------|------|
| **官方 CLI** | `zpaq_official_jni.cpp` + `zpaqcli/zpaq.cpp` | ✅ **使用中** | 通过 `runCommand()` 调用，兼容 zpaq v7.15 全部功能 |
| 旧 libzpaq | `zpaq_jni.cpp` + `zpaq/libzpaq.cpp` | ❌ 废弃 | 仅 `compressFiles/decompressFiles/listEntries` 使用此路径 |

**代码入口对应关系：**

- `ZPAQNative.addToArchiveNative()` → `zpaq_official_jni.cpp::addToArchiveNative()` → `runZpaqCommand()` → `Jidac::doCommand()`（zpaqcli）
- `ZPAQNative.runCommand()` → `zpaq_official_jni.cpp::runCommand()` → `runZpaqCommand()`
- `ZPAQNative.compressFiles()` → `zpaq_jni.cpp::compressFiles()`（旧 libzpaq，使用 `libzpaq::Compressor`）
- `ZPAQNative.decompressFiles()` → `zpaq_jni.cpp::decompressFiles()`（旧 libzpaq，使用 `libzpaq::Decompresser`）

### JNI 方法映射

| Java 方法 | C++ 实现文件 |
|-----------|-------------|
| `runCommand(String[])` | zpaq_official_jni.cpp |
| `addToArchiveNative(path, inputs, level, threads)` | zpaq_official_jni.cpp |
| `compressFiles(paths, outputPath, level)` | zpaq_jni.cpp |
| `decompressFiles(archivePath, outputDir)` | zpaq_jni.cpp |
| `listEntries(archivePath)` | zpaq_jni.cpp |
| `getVersion()` | zpaq_jni.cpp |

### 进度回调机制

- **官方 CLI 路径**（`zpaq add`）：C++ 侧从 stdout 输出行中解析百分数 `XX%`，通过 `onNativeProgress(percent, 100, entryText)` 回调
- **旧 libzpaq 路径**（`compressFiles`）：从 `compressEntries()` 循环中回调，按 256KB 步长报进度

### 版本解析（`parseVersionList`）

`ZPAQNative.parseVersionList()` 使用 `zpaq list -all 4 -summary -1` 输出，分三层：
1. 方法1：直接从 stdout 行正则匹配版本目录行（`0001/`）
2. 方法2：从文件路径前缀提取版本号，补齐空骨架版本
3. 方法3：完全回退（从文件前缀去重）

## 常用操作

### 1. 只改 Java 代码（最快）
```bash
cd /root/AmazeLite && bash build_zpaq_apk.sh
cp build/apk/final.apk /sdcard/Download/AmazeLite.apk
```
不需要重编 native。

### 2. 改了 C++ 代码
```bash
cd /root/AmazeLite && bash build_all.sh
```

### 3. 调试 ZPAQ 输出
每次打开 zpaq 归档时，`listArchiveVersion` 的输出会保存到：
```bash
cat /sdcard/Download/zpaq_list_debug.txt
```

### 4. 删除版本调试
删除操作记录到：
```bash
cat /sdcard/Download/zpaq_delete_debug.txt
```

## 已知问题和注意事项

1. **两个 .cpp 都包含 JNI_OnLoad**：`zpaq_jni.cpp` 的 `JNI_OnLoad_DISABLED` 已被重命名（后缀 `_DISABLED`）避免冲突，实际 `JNI_OnLoad` 在 `zpaq_official_jni.cpp` 中
2. **`-all 4` 参数**：`zpaq list` 用 `-all 4` 输出 4 位版本目录前缀，`-summary -1` 可输出版本片段 ID
3. **zpaq 版本号从 1 开始**：`-until N` 保留到版本 N（含 N），删版本 N 传 `N-1`