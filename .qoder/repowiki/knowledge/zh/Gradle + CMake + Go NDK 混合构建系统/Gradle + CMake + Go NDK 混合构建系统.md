---
kind: build_system
name: Gradle + CMake + Go NDK 混合构建系统
category: build_system
scope:
    - '**'
source_files:
    - build.gradle.kts
    - settings.gradle.kts
    - gradle.properties
    - gradle/libs.versions.toml
    - tunnel/build.gradle.kts
    - ui/build.gradle.kts
    - tunnel/tools/CMakeLists.txt
    - tunnel/tools/libwg-go/Makefile
---

## 构建系统与工具链

该项目采用 **Gradle (Kotlin DSL) + Android Gradle Plugin (AGP 9.1.0)** 作为主构建编排器，结合 **CMake** 编译原生代码、**Go NDK** 交叉编译 WireGuard-Go 核心库，形成多语言混合构建体系。

### 模块结构
- 根 `build.gradle.kts` 仅声明插件别名（android.application / android.library / legacy.kapt），通过 `settings.gradle.kts` 启用两个子模块：`:tunnel`（AAR 库）和 `:ui`（APK 应用）
- 依赖版本集中管理于 `gradle/libs.versions.toml`，AGP 统一为 9.1.0
- 版本号由 `gradle.properties` 中的 `wireguardVersionCode`、`wireguardVersionName`、`wireguardPackageName` 三处驱动

### 原生层构建（tunnel 模块）
- `tunnel/build.gradle.kts` 通过 `externalNativeBuild.cmake` 指向 `tools/CMakeLists.txt`
- CMake 构建三个目标：`libwg.so`（WireGuard 核心）、`libwg-quick.so`（wg-quick 兼容后端）、`libwg-go.so`（Go 后端）
- `libwg-go.so` 的构建通过自定义 target 调用 `tunnel/tools/libwg-go/Makefile`，使用 Go 1.24.3 配合 CGO 交叉编译为 Android NDK
- Makefile 自动下载并缓存指定版本的 Go 二进制，校验 SHA256 后打补丁 `goruntime-boottime-over-monotonic.diff` 再编译
- CMake 在 post-build 阶段调用 `elf-cleaner` 清理 ELF 段以兼容旧版 Android
- 所有 native 库均传递 `ANDROID_PACKAGE_NAME` 参数用于 IPC socket 路径隔离

### 应用层构建（ui 模块）
- 启用 DataBinding、ViewBinding、BuildConfig 三种 buildFeatures
- Java/Kotlin 兼容级别设为 17，启用 coreLibraryDesugaring 支持新 API
- 发布配置：release 开启 minify/shrinkResources，使用 `proguard-android-optimize.txt`；debug 加 `.debug` 后缀
- 自定义 `googleplay` buildType 继承 release 并设置 matchingFallbacks

### 发布与签名
- tunnel 模块使用 `maven-publish` + `signing`（GPG）生成可发布的 AAR，输出到 `build/sonatype` 目录
- 提供 `zipReleasePublication` 任务将 Maven 仓库布局打包为 zip 分发
- POM 元数据包含 Apache 2.0 许可证、SCM 地址及开发者信息

### 构建优化与约束
- `gradle.properties` 启用 `org.gradle.parallel=true`、`org.gradle.caching=true`、JVM 堆 1536MB
- `kapt.include.compile.classpath=false` 关闭 AP 发现以提升编译速度
- AGP 依赖解析模式设为 `FAIL_ON_PROJECT_REPOS`，强制集中式依赖管理
- Lint 禁用 `LongLogTag`、`NewApi`，对 ui 模块额外警告 `MissingTranslation`、`ImpliedQuantity`
- Java 编译开启 `-Xlint:unchecked` 和 deprecation 警告