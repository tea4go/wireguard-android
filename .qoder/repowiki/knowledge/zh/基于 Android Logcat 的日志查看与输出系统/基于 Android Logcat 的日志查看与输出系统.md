---
kind: logging_system
name: 基于 Android Logcat 的日志查看与输出系统
category: logging_system
scope:
    - '**'
source_files:
    - ui/src/main/java/com/wireguard/android/activity/LogViewerActivity.kt
    - ui/src/main/res/layout/log_viewer_activity.xml
    - ui/src/main/java/com/wireguard/android/Application.kt
    - tunnel/src/main/java/com/wireguard/android/backend/GoBackend.java
    - tunnel/src/main/java/com/wireguard/android/util/RootShell.java
---

本仓库未引入第三方日志框架（如 Timber、SLF4J、Logback 等），而是直接使用 Android 平台原生的 `android.util.Log` 进行应用内调试输出，并通过内置的 `LogViewerActivity` 实时读取系统 `logcat` 流以提供用户可见的日志查看功能。

**1. 日志输出方式**
- 所有 Kotlin/Java 模块均通过 `import android.util.Log` 直接调用 `Log.d/i/w/e/v` 等方法输出日志，每个类使用自身 TAG 常量作为标签前缀。
- 日志级别覆盖 V/D/I/W/E 全部五级，常见模式为：调试信息用 `Log.d(TAG, ...)`，警告用 `Log.w(TAG, ...)`，错误堆栈统一通过 `Log.e(TAG, Log.getStackTraceString(e))` 或 `Log.e(TAG, message, e)` 输出。
- 典型使用位置包括：`Application.kt`、`BootShutdownReceiver.kt`、`QuickTileService.kt`、`FileConfigStore.kt`、`tunnel` 模块中的 `GoBackend.java`、`WgQuickBackend.java`、`RootShell.java`、`SharedLibraryLoader.java`、`ToolsInstaller.java` 等。

**2. 日志查看与导出**
- `ui` 模块提供 `LogViewerActivity`，通过 `ProcessBuilder().command("logcat", "-b", "all", "-v", "threadtime", "*:V")` 启动 logcat 进程，以 `threadtime` 格式实时解析并展示所有级别的日志。
- UI 层根据日志级别（V/D/I/W）对 tag 和消息进行不同颜色高亮显示，支持单行/多行切换、滚动到底部自动跟随。
- 支持将当前日志缓存（最多 2^16 行）导出为 `wireguard-log.txt` 文件，通过 `ContentProvider`（`ExportedLogContentProvider`）以临时 URI 形式分享给其他应用，或使用系统下载保存。
- 日志解析正则 `THREADTIME_LINE` 匹配标准 `logcat -v threadtime` 输出格式：`MM-dd HH:mm:ss.SSS pid tid LEVEL Tag: message`。

**3. 架构与约定**
- 无集中式 Logger 初始化或配置；日志输出是分散在各业务类中的直接调用。
- 日志仅用于开发调试与问题排查，未实现结构化字段、采样策略、异步写入或远程上报。
- `LogViewerActivity` 是唯一的日志消费入口，运行在 UI 线程侧通过协程 `lifecycleScope.launch(Dispatchers.IO)` 后台读取 logcat 流，再切回主线程更新 RecyclerView。

**4. 约束与限制**
- 日志输出依赖 Android 系统 logcat，需要设备具备相应权限；在部分受限环境下可能无法读取完整日志。
- 内存中仅保留最近 2^16 行原始日志与 2^14 行缓冲行，超出后按 FIFO 丢弃。
- 未定义统一的日志级别开关或过滤规则，所有级别默认开启。