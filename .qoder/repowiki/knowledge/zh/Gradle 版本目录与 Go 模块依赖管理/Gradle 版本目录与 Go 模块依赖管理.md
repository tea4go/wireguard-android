---
kind: dependency_management
name: Gradle 版本目录与 Go 模块依赖管理
category: dependency_management
scope:
    - '**'
source_files:
    - gradle/libs.versions.toml
    - settings.gradle.kts
    - build.gradle.kts
    - gradle.properties
    - tunnel/build.gradle.kts
    - ui/build.gradle.kts
    - tunnel/tools/libwg-go/go.mod
    - tunnel/tools/libwg-go/go.sum
---

本仓库采用 Gradle 版本目录（Version Catalog）统一管理 Android/Kotlin 依赖，并通过独立的 Go module 管理 WireGuard 核心库的 Go 依赖，形成双栈依赖管理体系。

**Android 依赖管理**
- 集中式版本声明：`gradle/libs.versions.toml` 定义所有第三方库的版本号，包括 AGP、AndroidX、Material、Kotlin Coroutines、ZXing 等，通过 `[versions]` 和 `[libraries]` 两段式结构管理。
- 插件版本集中化：同一 TOML 文件的 `[plugins]` 段统一声明 Android Application/Library、legacy-kapt 插件版本，根 `build.gradle.kts` 通过 `alias(libs.plugins.*) apply false` 声明式引入。
- 仓库源全局管控：`settings.gradle.kts` 中 `dependencyResolutionManagement.repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS` 强制禁止子模块自行声明仓库源，仅允许 google() 和 mavenCentral() 两个官方源。
- 模块依赖引用：各模块通过 `implementation(libs.androidx.xxx)` 形式引用依赖，避免硬编码版本号。

**Go 依赖管理**
- 独立 Go module：`tunnel/tools/libwg-go/go.mod` 声明 Go 1.23.1 工具链，依赖 `golang.zx2c4.com/wireguard`（使用带时间戳的 pseudo-version）及 `golang.org/x/sys`、`golang.org/x/crypto` 等标准扩展库。
- 依赖锁定：`go.sum` 完整记录所有直接/间接依赖的校验和，确保可重现构建。
- 私有依赖策略：Go 依赖来自公共 GOPROXY 源（golang.org/x/*、golang.zx2c4.com/*），未发现 GOPRIVATE 或私有代理配置。

**构建产物发布**
- tunnel 模块通过 `maven-publish` + `signing` 插件生成签名后的 Maven 构件，发布到本地 Sonatype 目录用于分发。
- 版本信息从 `gradle.properties` 中的 `wireguardVersionCode`、`wireguardVersionName`、`wireguardPackageName` 三个属性注入，实现单一来源管理。

**约束与约定**
- 所有 Android 模块必须使用 Java 17 兼容级别（compile/targetCompatibility）。
- 禁止在子模块中声明自定义仓库源，违反将导致构建失败。