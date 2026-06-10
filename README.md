# AmazeLite

轻量级 Android 文件管理器，内置 [ZPAQ](http://mattmahoney.net/dc/zpaq.html) 压缩归档支持。

基于 Android 原生 API 构建，无 Gradle 依赖，适合嵌入式/轻量级场景。

## 功能

- 📂 **文件管理**：浏览、复制、移动、删除、重命名
- 📦 **ZPAQ 压缩**：创建 ZPAQ 归档（5 级压缩）
- 📖 **ZPAQ 预览**：在应用内浏览归档内容、预览版本历史
- 📤 **ZPAQ 提取**：单文件/批量提取
- 🗑️ **ZPAQ 版本管理**：删除指定版本（`-until` 回滚）
- 📋 **进度条支持**：文件操作/压缩/解压均带进度提示
- 📎 **FileProvider**：文件关联打开（外部应用）

## 构建

### 环境要求

- Android SDK（`/opt/android-sdk`）
- Android NDK r27（`/opt/android-sdk/ndk/27.0.12077973`）
- 签名密钥（`/root/debug.keystore`，密码 `android`，别名 `operit`）
- 系统工具：`aapt2`、`zipalign`、`adb`

### 一键构建

```bash
bash build_all.sh
```

自动检测 C++ 源码变更决定是否重编 native，输出 APK 到 `/sdcard/Download/AmazeLite.apk`。

### 分步构建

```bash
# 仅编译 native .so
bash build_native_android_so.sh

# 仅打包 APK（使用已有的 .so）
bash build_zpaq_apk.sh
```

### 环境自检

```bash
bash setup_env.sh
```

检查 SDK/NDK/系统工具/签名密钥/源码/产物等 10 个项目。

## 架构

```
app/src/main/
├── java/com/operit/
│   ├── amazelite/
│   │   └── MainActivity.java       # 主界面 + 业务逻辑
│   │   └── DialogHelper.java        # 对话框/进度条工具
│   │   └── FileOperations.java      # 文件操作工具
│   └── zpaq/
│       └── ZPAQNative.java          # ZPAQ JNI 绑定
├── cpp/
│   ├── zpaq_official_jni.cpp        # JNI 桥接（内嵌 zpaq CLI）
│   └── zpaqcli/zpaq.cpp             # zpaq 7.15 官方源码
└── res/                             # UI 资源
```

### ZPAQ 集成方式

与 [PowerExplorer/P7Zip16.02-ZPAQ7.15](https://github.com/PowerExplorer/P7Zip16.02-ZPAQ7.15) 类似，本项目将 **zpaq 官方 C++ 源码编译为 `.so`** 通过 JNI 直接调用 `Jidac::doCommand()`。

区别在于：
- **管道重定向方案**（P7Zip）：zpaq 作为独立进程运行，通过命名管道通信
- **内嵌调用方案**（本项目）：同进程直接调用 `doCommand()`，性能更好

## 技术栈

| 层 | 技术 |
|---|---|
| UI | Android View + AlertDialog |
| 压缩 | ZPAQ 7.15（JNI + C++） |
| 构建 | shell 脚本 + aapt2 + javac + dx |
| 签名 | debug.keystore |

## License

GPL-3.0
