# WireGuard Android → HarmonyOS 迁移技术可行性分析文档

> **版本**: 1.0  
> **日期**: 2026-07-27  
> **适用目标**: HarmonyOS NEXT (5.0) / OpenHarmony 4.x+  

---

## 目录

1. [项目现状总览](#1-项目现状总览)
2. [核心组件迁移难度矩阵](#2-核心组件迁移难度矩阵)
3. [最关键技术障碍深度分析](#3-最关键技术障碍深度分析)
   - [3.1 Go 后端 — 最大技术风险](#31-go-后端--最大技术风险)
   - [3.2 VPN 服务 API 差异](#32-vpn-服务-api-差异)
   - [3.3 UI 层迁移](#33-ui-层迁移)
4. [两种迁移路径对比](#4-两种迁移路径对比)
5. [推荐迁移策略](#5-推荐迁移策略)
6. [详细工作量估算](#6-详细工作量估算)
7. [总体可行性结论](#7-总体可行性结论)
8. [附录](#8-附录)

---

## 1. 项目现状总览

### 1.1 项目结构

当前 WireGuard Android 项目是一个 **Gradle 多模块工程**，由两个核心模块构成：

```
wireguard-android/
├── tunnel/                          # AAR 库模块
│   ├── src/main/java/com/wireguard/
│   │   ├── android/backend/         # Backend 接口 + GoBackend + WgQuickBackend
│   │   ├── android/util/            # RootShell, SharedLibraryLoader, ToolsInstaller
│   │   ├── config/                  # wg-quick 配置解析 (纯 Java)
│   │   └── crypto/                  # Curve25519 加密原语 (纯 Java)
│   └── tools/
│       ├── libwg-go/                # Go 后端 (CGo + JNI)
│       ├── wireguard-tools/         # C 实现的 wg/wg-quick 工具
│       ├── ndk-compat/              # NDK 兼容层
│       ├── elf-cleaner/             # ELF 段清理工具
│       └── CMakeLists.txt           # 原生构建入口
│
├── ui/                              # 应用模块 (APK)
│   └── src/main/java/com/wireguard/android/
│       ├── activity/                # 7 个 Activity (含 TV 版)
│       ├── fragment/                # 7 个 Fragment
│       ├── model/                   # TunnelManager, ObservableTunnel 等
│       ├── viewmodel/               # ConfigProxy, InterfaceProxy, PeerProxy
│       ├── preference/              # 偏好设置页
│       ├── util/                    # 工具类 (Biometric, QR, Updater 等)
│       ├── widget/                  # 自定义 View 组件
│       └── databinding/             # DataBinding 适配器
│
├── gradle/libs.versions.toml        # 版本目录
├── build.gradle.kts                 # 根构建脚本
└── settings.gradle.kts              # 模块声明
```

### 1.2 技术栈总览

| 维度 | 当前技术 | 说明 |
|------|---------|------|
| **构建系统** | Gradle KTS + AGP 9.1.0 + CMake + Go Makefile | 多语言混合构建 |
| **UI 语言** | Kotlin (57 文件, ~4,500 行) | DataBinding + MVVM |
| **库语言** | Java 17 (~2,500 行) | 含 desugaring |
| **原生代码** | C (~100 行) + Go (~300 行 + wireguard-go 库) | JNI/CGo 桥接 |
| **核心依赖** | wireguard-go, golang.org/x/sys, AndroidX, Material 3 | Go module + Maven |
| **最低 SDK** | minSdk 24 (Android 7.0) | compileSdk 36 |
| **目标平台** | Android (手机 + TV) | QuickSettings Tile 支持 |

### 1.3 双后端架构

```
                    ┌──────────────────────────┐
                    │       Backend 接口        │
                    └───────────┬──────────────┘
                                │
            ┌───────────────────┼───────────────────┐
            │                                       │
   ┌────────▼────────┐                    ┌────────▼────────┐
   │   GoBackend     │                    │ WgQuickBackend  │
   │ (userspace模式)  │                    │  (内核模块模式)   │
   └────────┬────────┘                    └────────┬────────┘
            │                                       │
   ┌────────▼────────┐                    ┌────────▼────────┐
   │  android.net.   │                    │    RootShell    │
   │  VpnService     │                    │   (su 进程)      │
   └────────┬────────┘                    └────────┬────────┘
            │                                       │
   ┌────────▼────────┐                    ┌────────▼────────┐
   │   JNI 调用       │                    │  wg-quick 命令   │
   │  libwg-go.so    │                    │  libwg-quick.so  │
   └────────┬────────┘                    └────────┬────────┘
            │
   ┌────────▼────────┐
   │ wireguard-go    │
   │ (Go userspace)  │
   └─────────────────┘
```

- **GoBackend**（默认，无 root 方案）：通过 `android.net.VpnService` API 创建 TUN 设备，交 Go 侧处理 WireGuard 协议
- **WgQuickBackend**（可选，需 root）：依赖 Linux 内核 WireGuard 模块 + `su` 执行 `wg-quick` 脚本

---

## 2. 核心组件迁移难度矩阵

### 🔴 红色 — 必须完全重写（不可复用）

| 组件 | 源文件 | 行数 | 不可复用原因 |
|------|--------|------|-------------|
| **Go 原生后端** | `api-android.go`, `jni.c`, `Makefile` | ~370 | `GOOS=android` 是 Android 专有目标；使用 `android/log.h`；Go 官方无 HarmonyOS GOOS；`golang.org/x/sys/unix` 直接 syscall |
| **VPN 服务** | `GoBackend.java` (VpnService 内部类 L380-428) | ~50 | `android.net.VpnService` + `Builder` API 是 Android 独有，HarmonyOS 用 `@ohos.net.vpn` 完全不同的 API |
| **Root Shell** | `RootShell.java` | ~222 | 依赖 Linux `su` 二进制 + `ProcessBuilder`，HarmonyOS 安全模型不提供标准 root 机制 |
| **命令行后端** | `WgQuickBackend.java` | ~206 | 依赖 root + kernel wireguard 模块 + `su` 执行 wg-quick，HarmonyOS 不适用 |
| **工具安装器** | `ToolsInstaller.java` | ~202 | 写入 `/system/xbin`、Magisk 模块安装逻辑，完全不适用 |
| **原生工具** | `libwg-quick.so`, `libwg.so` (C) | - | 依赖 Android NDK 编译 + root 执行环境 |

### 🟠 橙色 — 需大量适配（可部分复用逻辑）

| 组件 | 源文件数 | 行数 | 适配难点 |
|------|---------|------|---------|
| **UI 界面** | 57 个 Kotlin 文件 | ~4,500 | Activity/Fragment → Ability/Component；DataBinding → ArkUI @State/@Link；AndroidX → HarmonyOS SDK；Material → HarmonyOS Design |
| **共享库加载** | `SharedLibraryLoader.java` | ~95 | APK zip 内提取 .so 逻辑 Android 特有；HarmonyOS 的 HAP 结构和 native 加载机制完全不同 |
| **QuickSettings** | `QuickTileService.kt` | ~203 | `TileService` 是 Android 独有 API；HarmonyOS 快捷设置扩展机制不同 |
| **开机自启** | `BootShutdownReceiver.kt` | ~34 | `BroadcastReceiver` 机制不同；HarmonyOS 用 Ability 生命周期 + 后台任务管理 |
| **TV 界面** | `TvMainActivity.kt` | ~431 | Android TV 框架（Leanback/RecyclerView Grid）与 HarmonyOS 大屏适配方案不同 |
| **指纹认证** | `BiometricAuthenticator.kt` | ~80 | `androidx.biometric` → `@ohos.biometricAuthentication` |
| **应用更新** | `Updater.kt` | ~472 | GitHub Releases 检查逻辑可复用，但下载/安装流程需适配 HAP |
| **QR 扫描** | `QrCodeFromFileScanner.kt` + ZXing | ~84 | `zxing-android-embedded` 需替换为 HarmonyOS 扫码方案 |
| **偏好存储** | `PreferencesPreferenceDataStore.kt` | ~135 | `androidx.datastore` → HarmonyOS `@ohos.data.preferences` |
| **Kotlin Coroutines** | 广泛使用 | - | Kotlin 在 HarmonyOS 上的运行时支持需评估 |

### 🟡 黄色 — 可复用，需适度适配（纯 Java 逻辑）

| 组件 | 源文件 | 行数 | 适配备注 |
|------|--------|------|---------|
| **配置解析** | `Config.java`, `Interface.java`, `Peer.java`, `Attribute.java`, `InetAddresses.java`, `InetEndpoint.java`, `InetNetwork.java` | ~1,360 | 纯 Java，解析 wg-quick 配置文件，无 Android 平台依赖；需移除 `androidx.annotation.Nullable` |
| **加密原语** | `Curve25519.java`, `Key.java`, `KeyPair.java`, `KeyFormatException.java` | ~881 | 纯 Java Curve25519/Base64 实现，零平台依赖 |
| **异常定义** | `BadConfigException.java`, `ParseException.java`, `BackendException.java` | ~229 | 纯 Java 异常类，可直接复用 |
| **接口定义** | `Backend.java` | ~89 | 接口抽象本身可复用，但具体实现需全部重写 |

### 🟢 绿色 — 可直接复用

| 资产 | 说明 |
|------|------|
| **WireGuard 协议规范** | 跨平台标准，协议逻辑不变 |
| **wg-quick 配置格式** | 配置文件格式是 WireGuard 生态标准，语法不变 |
| **密码学算法** | Curve25519, ChaCha20Poly1305, BLAKE2s, HKDF 等算法是数学实现，语言无关 |
| **测试用例与资源** | `working.conf`, `broken.conf`, `invalid-key.conf` 等测试配置文件可直接复用 |
| **多语言资源** | 42 种语言的 `strings.xml` 可提取翻译文本到 HarmonyOS `string.json` |
| **UI 设计规范** | 交互逻辑、信息架构、视觉风格可参考 |

---

## 3. 最关键技术障碍深度分析

### 3.1 Go 后端 — 最大技术风险

#### 3.1.1 当前依赖链

```
GoBackend.java (JNI native 方法)
  │  native int wgTurnOn(String ifName, int tunFd, String settings)
  │  native void wgTurnOff(int handle)
  │  native int wgGetSocketV4(int handle)
  │  native int wgGetSocketV6(int handle)
  │  native String wgGetConfig(int handle)
  │  native String wgVersion()
  │
  ▼
jni.c (JNI 胶水层)
  │  使用 <jni.h>, JNIEXPORT, JNICALL
  │  调用 Go 侧 CGo 导出函数
  │
  ▼
api-android.go (CGo 导出函数)
  │  #cgo LDFLAGS: -llog
  │  #include <android/log.h>
  │  import "C"
  │
  ├── golang.zx2c4.com/wireguard/device   # WireGuard 核心引擎
  ├── golang.zx2c4.com/wireguard/tun      # TUN 设备操作
  ├── golang.zx2c4.com/wireguard/conn     # 网络连接绑定
  ├── golang.zx2c4.com/wireguard/ipc      # UAPI 接口
  └── golang.org/x/sys/unix              # Linux syscall 包装
```

#### 3.1.2 问题逐项分析

| # | 问题 | 严重度 | 说明 |
|---|------|--------|------|
| 1 | **Go 编译目标缺失** | 🔴 致命 | Go 官方支持 `GOOS=android` 但不支持 `GOOS=harmonyos` 或 `GOOS=openharmony`。HarmonyOS 内核基于 Linux，理论上可尝试 `GOOS=linux` + HarmonyOS NDK clang 交叉编译，但**未经任何官方验证** |
| 2 | **CGo 与 HarmonyOS NDK 兼容性** | 🔴 高 | CGo 生成代码依赖 GNU/Linux 特定行为（如 `pthread`、`dlopen` 等）。HarmonyOS NDK 的 musl-libc 兼容性未知 |
| 3 | **TUN 设备创建路径** | 🔴 高 | 当前通过 Android `VpnService.Builder.establish()` 获取 TUN fd 传给 Go；HarmonyOS VPN Extension 获取 TUN fd 的机制完全不同 |
| 4 | **JNI → NAPI 转换** | 🟠 中 | `jni.c` 需全部改写为 `napi.c`，API 风格不同但概念相似 |
| 5 | **Android Logcat → HiLog** | 🟢 低 | `C.__android_log_write` 替换为 HarmonyOS HiLog C API，工作量小 |
| 6 | **Go module 补丁** | 🟡 中 | 当前有 `goruntime-boottime-over-monotonic.diff` 补丁，可能需要额外补丁适配新平台 |

#### 3.1.3 推荐解决方案：C/Rust 重写

考虑到 Go → HarmonyOS 路径的高度不确定性，**推荐用 C 或 Rust 重写 userspace WireGuard 后端**：

| 方案 | 语言 | 优势 | 劣势 |
|------|------|------|------|
| **方案 A** | C + NAPI | 直接复用 `wireguard-tools/src/` 的 C 代码；HarmonyOS NDK 原生支持 C；无运行时依赖 | 需重写 IPC/UAPI 对接层；Go 的 goroutine 并发模型需转为 C 线程模型 |
| **方案 B** | Rust + NAPI | `wireguard-rs` 等现有 crate 可参考；内存安全；现代化工具链 | Rust 在 HarmonyOS 的 target 也需要验证；团队需 Rust 技能 |
| **方案 C** | 保留 Go + 暴力适配 | 代码改动最小 | 极大的未知风险；可能投入数周后发现根本无法编译 |

**推荐方案 A (C + NAPI)**，理由：
- 项目已有 `wireguard-tools/src/` C 代码基础 (`libwg.so` 的 C 源码)
- HarmonyOS NDK 对 C 语言支持最成熟
- 可参考 `wireguard-tools/src/` 中的 `wg.c` 核心逻辑进行移植
- NAPI 是 C API，对接最直接

#### 3.1.4 需暴露的原生接口（保持与现有 Backend 接口一致）

```c
// 对应 wgTurnOn
int wg_turn_on(const char *ifname, int tun_fd, const char *settings);

// 对应 wgTurnOff
void wg_turn_off(int handle);

// 对应 wgGetSocketV4
int wg_get_socket_v4(int handle);

// 对应 wgGetSocketV6
int wg_get_socket_v6(int handle);

// 对应 wgGetConfig
char* wg_get_config(int handle);

// 对应 wgVersion
char* wg_version(void);
```

### 3.2 VPN 服务 API 差异

#### 3.2.1 当前 Android 实现核心代码路径

```java
// GoBackend.java - setStateInternal() 方法
VpnService.Builder builder = service.getBuilder();
builder.setSession(tunnel.getName());
builder.addAddress(addr.getAddress(), addr.getMask());
builder.addDnsServer(addr.getHostAddress());
builder.addSearchDomain(dnsSearchDomain);
builder.addRoute(addr.getAddress(), addr.getMask());
builder.addDisallowedApplication(excludedApplication);
builder.addAllowedApplication(includedApplication);
builder.allowFamily(OsConstants.AF_INET);
builder.allowFamily(OsConstants.AF_INET6);
builder.setMtu(config.getInterface().getMtu().orElse(1280));
builder.setMetered(false);
builder.setBlocking(true);

ParcelFileDescriptor tun = builder.establish();
currentTunnelHandle = wgTurnOn(tunnel.getName(), tun.detachFd(), goConfig);
service.protect(wgGetSocketV4(currentTunnelHandle));
service.protect(wgGetSocketV6(currentTunnelHandle));
```

#### 3.2.2 HarmonyOS VPN Extension 对应

HarmonyOS 使用 `@ohos.net.vpn` 模块，通过 `VpnExtensionAbility` 实现 VPN：

| Android API | HarmonyOS 对应 | 差异程度 |
|-------------|---------------|---------|
| `VpnService.prepare(context)` | 系统 VPN 权限弹窗机制不同 | 🔴 完全重写 |
| `VpnService.Builder` | `VpnExtensionAbility` 配置模型 | 🔴 完全重写 |
| `builder.setSession(name)` | `VpnConfig` 隧道标识 | 🟠 API 不同 |
| `builder.addAddress(addr, mask)` | `vpnConfig.addresses` | 🟠 配置方式不同 |
| `builder.addDnsServer(addr)` | `vpnConfig.dnsAddresses` | 🟠 配置方式不同 |
| `builder.addRoute(addr, mask)` | `vpnConfig.routes` | 🟠 配置方式不同 |
| `builder.addDisallowedApplication(pkg)` | `vpnConfig.blockedApplications` | 🟠 配置方式不同 |
| `builder.addAllowedApplication(pkg)` | `vpnConfig.allowedApplications` | 🟠 配置方式不同 |
| `builder.setMtu(mtu)` | `vpnConfig.mtu` | 🟢 直接对应 |
| `builder.establish()` | `VpnExtensionAbility.onConnect()` 回调 | 🔴 机制完全不同 |
| `ParcelFileDescriptor` | `vpnConnection.createVpnConnection()` 返回 fd | 🔴 封装不同 |
| `service.protect(fd)` | `vpnConnection.protect(fd)` | 🟢 概念一致，API 不同 |
| `builder.allowFamily(AF_INET)` | HarmonyOS 默认支持双栈 | 🟢 可能无需显式设置 |

#### 3.2.3 关键差异总结

1. **VPN 生命周期管理**：Android 用 `Service` + `startService()`；HarmonyOS 用 `VpnExtensionAbility` 生命周期回调（`onCreate/onConnect/onDisconnect/onDestroy`）
2. **TUN fd 获取时机**：Android 在 `builder.establish()` 同步返回；HarmonyOS 在 `onConnect()` 异步回调中通过 `VpnConnection` 创建
3. **分应用代理**：API 结构和语义不同
4. **Always-On VPN**：Android 的 `setAlwaysOn()` 在 HarmonyOS 中对应不同的系统级配置

### 3.3 UI 层迁移

#### 3.3.1 文件清单与迁移工作量

共 **57 个 Kotlin 源文件**，按模块分类：

| 模块 | 文件数 | 迁移难度 | 说明 |
|------|--------|---------|------|
| `activity/` | 7 | 🔴 高 | Activity → Ability，生命周期不同；TV Activity 需适配大屏框架 |
| `fragment/` | 7 | 🔴 高 | Fragment → ArkUI Navigation/自定义组件 |
| `model/` | 4 | 🟡 中 | 数据模型逻辑可复用，Observable → ArkUI @State |
| `viewmodel/` | 3 | 🟡 中 | MVVM 逻辑可复用，适配 ArkUI 状态管理 |
| `preference/` | 7 | 🟠 较高 | PreferenceFragment → ArkUI 设置页组件 |
| `util/` | 10 | 🟡 中 | 工具函数可移植，Android API 调用需替换 |
| `widget/` | 6 | 🟠 较高 | 自定义 View → ArkUI 自定义组件 |
| `databinding/` | 6 | 🔴 高 | DataBinding 机制完全不同，ArkUI 使用声明式绑定 |
| 顶层文件 | 3 | 🟡 中 | Application, BootReceiver, QuickTile |

#### 3.3.2 Android vs HarmonyOS UI 范式对比

| Android 概念 | HarmonyOS 对应 | 迁移思路 |
|-------------|---------------|---------|
| `Activity` | `UIAbility` / `@Entry @Component` | 重写页面入口，适配生命周期回调 |
| `Fragment` | `@Component` + `Navigation` | 重写为自定义组件，管理子页面导航 |
| `RecyclerView` + `Adapter` | `List` / `Grid` + `ForEach` | 声明式列表替换 |
| `DataBinding` + `BR` | `@State` / `@Link` / `@Prop` | 状态驱动 UI 更新，移除双向绑定胶水代码 |
| `Observable` / `BaseObservable` | `@Observed` / `@ObjectLink` | 数据变更通知机制转换 |
| `PreferenceFragment` | 自定义设置页 `@Component` | 无内置偏好框架，需手写设置 UI |
| `MaterialAlertDialogBuilder` | `AlertDialog` / `CustomDialog` | 对话框 API 转换 |
| `Toast` | `promptAction.showToast()` | 一行替换 |
| `Snackbar` | 自定义或使用第三方库 | 无内置对应 |
| `View.onClickListener` | `onClick(() => {...})` | 语法转换 |
| `Intent` + `startActivity()` | `router.pushUrl()` | 页面路由转换 |
| `BroadcastReceiver` | `CommonEvent` / 后台任务 | 事件订阅机制不同 |
| `TileService` (QuickSettings) | 服务卡片 (FormExtensionAbility) | 快捷设置实现方式不同 |
| `Bitmap` / `Canvas` / `Drawable` | `Canvas` / `Image` / `DrawContext` | 绘图 API 转换 |
| RecyclerView + `GridLayoutManager` | `Grid` + `GridItem` | 声明式网格替换 |
| `DividerItemDecoration` | `.divider()` 属性 | 装饰器模式不同 |
| `MenuItem`, ContextMenu | `Menu` / `MenuItem` | 上下文菜单 API 转换 |

---

## 4. 两种迁移路径对比

### 路径 A：HarmonyOS AOSP 兼容层模式

> ⚠️ **注意**: HarmonyOS NEXT (5.0) 已移除 AOSP 兼容层，此路径仅适用于 HarmonyOS 4.x 及更早版本。对于新项目 **不推荐** 此路径。

| 维度 | 评估 |
|------|------|
| **Java/Kotlin 代码** | 大部分可直接运行，仅需少量适配 |
| **AndroidX 库** | AOSP 兼容层提供部分支持，但 API 覆盖不全 |
| **Go native 库** | 仍需重新编译（.so），需适配 NDK 差异 |
| **VpnService** | AOSP 兼容层的 VPN API 支持程度不确定，可能缺失 |
| **长期维护** | ❌ 不可持续，HarmonyOS NEXT 已移除该层 |
| **AppGallery 上架** | AOSP 兼容应用可能受限，推荐原生应用 |
| **预估工作量** | **1-2 人月**（仅 native 重编译 + VPN 适配验证） |

### 路径 B：纯 HarmonyOS 原生（ArkTS + NAPI）

> ✅ **推荐路径**，适用于 HarmonyOS NEXT 及后续版本。

| 维度 | 评估 |
|------|------|
| **原生体验** | ✅ 完全符合 HarmonyOS 设计规范 |
| **AppGallery** | ✅ 可正常上架分发 |
| **长期维护** | ✅ 生态持续演进，无弃用风险 |
| **性能** | ✅ 原生 ArkTS/NAPI 性能最优 |
| **预估工作量** | **8-12 人月**（详见下方分解） |

---

## 5. 推荐迁移策略

### 5.1 总体方针

```
分三阶段推进，每阶段有明确的交付物和验证标准。
在阶段 1 完成可行性原型前，不建议启动全面开发。
```

### 5.2 阶段 1 — 技术原型验证（1-2 人月）

**目标**：验证两个最大风险点（Native 后端 + VPN Extension）在 HarmonyOS 上的可行性。

```
阶段 1（验证期，1-2 人月）
├── 任务 1.1: Native 后端原型
│   ├── 从 wireguard-tools/src/ 提取核心 WireGuard C 代码
│   ├── 通过 NAPI 封装 wg_turn_on/wg_turn_off 等接口
│   ├── 在 HarmonyOS 模拟器/真机上编译运行
│   └── 验证标准：能成功创建 WireGuard 隧道、收发数据包
│
├── 任务 1.2: VPN Extension 原型
│   ├── 实现最小 VpnExtensionAbility
│   ├── 获取 TUN fd 并通过 NAPI 传递给 C 后端
│   ├── 配置路由/DNS/MTU 并验证流量转发
│   └── 验证标准：ping 通对端 Peer 的内网地址
│
├── 任务 1.3: 配置文件解析移植
│   ├── 将 Config.java/Interface.java/Peer.java 逻辑移植到 ArkTS
│   ├── 解析 working.conf 并验证生成配置对象
│   └── 验证标准：100% 通过现有单元测试用例
│
└── 交付物
    ├── 可运行的 Demo HAP
    ├── 技术可行性结论报告
    └── 阶段 2 详细实施计划
```

### 5.3 阶段 2 — 核心功能开发（5-7 人月）

**目标**：完成所有核心功能，达到内部 Alpha 质量。

```
阶段 2（核心开发，5-7 人月）
├── 模块 A: Native 后端完整实现 (2 人月)
│   ├── 完整实现 WireGuard 协议栈（握手、密钥轮换、重连）
│   ├── 实现 UAPI 接口（动态配置修改/查询）
│   ├── 实现 Statistics 统计（rx_bytes/tx_bytes/handshake）
│   ├── 集成 HiLog 日志输出
│   └── 单元测试 + 与 wireguard-go 的兼容性测试
│
├── 模块 B: VPN Extension 完整实现 (1.5 人月)
│   ├── VpnExtensionAbility 完整生命周期管理
│   ├── 分应用代理（allowedApplications/blockedApplications）
│   ├── Always-On VPN 支持
│   ├── Kill-Switch（Lockdown 模式）
│   ├── DNS 搜索域配置
│   └── 错误处理与恢复逻辑
│
├── 模块 C: 配置与加密模块 (1 人月)
│   ├── config 包完整移植到 ArkTS
│   ├── crypto 包移植（Curve25519/Key/KeyPair）
│   ├── 配置文件导入导出（.conf / .zip）
│   └── 输入验证与错误提示
│
├── 模块 D: 核心 UI 开发 (2 人月)
│   ├── 隧道列表页（TunnelListFragment → ArkUI 列表）
│   ├── 隧道详情页（TunnelDetailFragment → 详情组件）
│   ├── 隧道编辑页（TunnelEditorFragment → 表单组件）
│   ├── 添加隧道（QR 扫描 / 文件导入 / 手动输入）
│   ├── 隧道开关控制（含状态指示器）
│   └── 基础导航框架
│
└── 交付物
    ├── 功能完整 Alpha 版本 HAP
    ├── 内部测试报告
    └── 已知问题清单
```

### 5.4 阶段 3 — 完善与发布（2-3 人月）

**目标**：补齐高级功能，达到可发布质量标准。

```
阶段 3（完善与发布，2-3 人月）
├── 模块 E: 高级 UI 功能 (1 人月)
│   ├── 设置页面（偏好存储、主题、语言切换）
│   ├── 日志查看器（HiLog 实时输出到 UI）
│   ├── 大屏/TV 适配（响应式布局）
│   ├── 服务卡片（QuickSettings 替代方案）
│   └── 暗黑模式支持
│
├── 模块 F: 平台特性集成 (0.5 人月)
│   ├── 指纹/面部认证集成
│   ├── 开机自启（通过 Ability 生命周期）
│   ├── 后台任务管理
│   └── 应用更新检查（对接 AppGallery 或自定义更新）
│
├── 模块 G: 测试与调优 (0.5 人月)
│   ├── 功能测试（多种配置场景）
│   ├── 性能测试（吞吐量、延迟、功耗）
│   ├── 兼容性测试（不同 HarmonyOS 版本/设备）
│   ├── 内存泄漏检测
│   └── UI 自动化测试
│
├── 模块 H: 发布准备 (0.5 人月)
│   ├── AppGallery Connect 配置
│   ├── 隐私政策与合规
│   ├── 应用签名与打包
│   ├── 商店素材准备（截图/描述）
│   └── 多语言文案校对（42 种语言）
│
└── 交付物
    ├── 可发布 Release HAP
    ├── 测试报告
    ├── AppGallery 上架材料
    └── 用户文档
```

---

## 6. 详细工作量估算

### 6.1 按模块分解

| 序号 | 模块 | 工作内容 | 预估人月 | 优先级 |
|------|------|---------|---------|--------|
| 1 | Native 后端 (C/NAPI) | WireGuard 协议栈移植、UAPI 接口、统计 | 2.5-3.5 | P0 核心 |
| 2 | VPN Extension | VpnExtensionAbility、TUN 管理、路由配置 | 1.5-2.0 | P0 核心 |
| 3 | 配置解析 (ArkTS) | Config/Interface/Peer 模型移植 | 0.5-1.0 | P0 核心 |
| 4 | 加密模块 (ArkTS) | Curve25519/Key/KeyPair 移植 | 0.3-0.5 | P0 核心 |
| 5 | 隧道管理 | TunnelManager、状态管理、持久化 | 0.5-1.0 | P0 核心 |
| 6 | 隧道列表/详情 UI | 主页列表、详情卡片 | 1.0-1.5 | P1 重要 |
| 7 | 隧道编辑 UI | 配置编辑器、表单验证 | 0.5-1.0 | P1 重要 |
| 8 | 设置页面 | 偏好存储、主题、语言 | 0.5-1.0 | P1 重要 |
| 9 | QR 扫描/文件导入 | 扫码、文件选择器 | 0.3-0.5 | P1 重要 |
| 10 | 大屏/TV 适配 | 响应式布局、网格视图 | 0.3-0.5 | P2 增强 |
| 11 | 服务卡片 | 快捷开关（QuickSettings 替代） | 0.3-0.5 | P2 增强 |
| 12 | 日志查看器 | HiLog 实时输出、过滤、导出 | 0.3-0.5 | P2 增强 |
| 13 | 生物认证 | 指纹/面部识别 | 0.1-0.2 | P2 增强 |
| 14 | 应用更新 | 版本检查、HAP 下载安装 | 0.2-0.3 | P2 增强 |
| 15 | 测试 | 单元测试、集成测试、性能测试 | 1.0-1.5 | P1 重要 |
| 16 | 发布准备 | AppGallery 上架、合规、素材 | 0.3-0.5 | P1 重要 |
| **总计** | | | **8.6-13.5** | |

### 6.2 人力资源配置建议

| 角色 | 人数 | 参与阶段 | 技能要求 |
|------|------|---------|---------|
| 高级 C 开发工程师 | 1 | 阶段 1-3 | 精通 C 语言、网络协议栈、Linux 网络编程；有 WireGuard 经验优先 |
| 高级 ArkTS 开发工程师 | 1 | 阶段 1-3 | 精通 ArkTS/ArkUI、HarmonyOS SDK、VPN 开发经验 |
| ArkTS 开发工程师 | 1 | 阶段 2-3 | 熟悉 ArkUI 组件开发、状态管理 |
| QA 测试工程师 | 1 | 阶段 2-3 | HarmonyOS 应用测试经验、网络协议测试 |
| 项目经理 | 0.5 | 全周期 | 跨平台迁移项目管理经验 |

**理想团队**: 4 人核心团队，**8-10 个月**完成从原型到发布。

---

## 7. 总体可行性结论

### 7.1 结论

| 维度 | 评级 | 说明 |
|------|------|------|
| **技术可行性** | ✅ **75%** | 核心协议可移植，但 Go→C 重写和 VPN API 适配是关键风险点 |
| **经济可行性** | ✅ **可行** | 总投入 8-12 人月，对比从零开发仍节省大量时间 |
| **时间可行性** | ✅ **可行** | 理想 8-10 个月可完成，分阶段交付降低风险 |
| **维护可行性** | ✅ **可行** | 纯 HarmonyOS 原生代码，长期可维护 |

### 7.2 关键风险矩阵

| 风险 | 概率 | 影响 | 等级 | 缓解措施 |
|------|------|------|------|---------|
| C 重写的 WireGuard 协议栈性能不达标 | 低 | 高 | 🟡 中 | 阶段 1 做性能基准测试；参考 wireguard-tools C 代码已生产验证 |
| HarmonyOS VPN Extension API 能力不足 | 中 | 致命 | 🔴 高 | 阶段 1 优先验证全部 VPN API 能力（路由/DNS/分应用代理/Always-On） |
| TUN fd 传递到 NAPI 模块不可行 | 低 | 致命 | 🟡 中 | 阶段 1 先验证 fd 传递路径；备选使用 local socket pair |
| Go 后端无法编译到 HarmonyOS | **已决策绕过** | - | - | 采用方案 A (C 重写)，不再依赖 Go 编译 |
| 未知的 HarmonyOS 系统限制 | 中 | 高 | 🟠 较高 | 保持与华为开发者社区联系；预留缓冲时间应对 |

### 7.3 先决条件检查清单

在启动阶段 2 完整开发前，必须通过以下检查：

- [ ] 阶段 1 原型验证通过（数据包成功转发）
- [ ] VPN Extension TUN fd 传递路径验证通过
- [ ] C 后端 WireGuard 握手协议验证通过
- [ ] 配置文件解析 100% 兼容现有测试用例
- [ ] 目标 HarmonyOS 版本和 API Level 确定
- [ ] 开发环境搭建完成（DevEco Studio + NDK）
- [ ] 团队具备所需的 C 和 ArkTS 技能

### 7.4 不建议迁移的场景

以下情况建议**暂缓或放弃** HarmonyOS 迁移：

1. 如果 HarmonyOS 市场份额不足以覆盖投入成本
2. 如果可以在 HarmonyOS 上通过虚拟机/容器方案运行已有 Linux VPN 方案
3. 如果团队缺乏 C 语言和网络协议栈开发能力（Native 后端是关键）
4. 如果项目时间线非常紧张（<6 个月）

---

## 8. 附录

### 8.1 Android → HarmonyOS API 速查对照表

| Android API | HarmonyOS API | 模块 |
|-------------|--------------|------|
| `android.app.Application` | `@ohos.app.ability.AbilityStage` | 应用入口 |
| `Activity` | `@ohos.app.ability.UIAbility` | 页面容器 |
| `Fragment` | `@Component` + `@ohos.router` | 页面片段 |
| `Intent` | `@ohos.router (router.pushUrl)` | 页面导航 |
| `BroadcastReceiver` | `@ohos.commonEventManager` | 事件广播 |
| `android.net.VpnService` | `@ohos.net.vpn (VpnExtensionAbility)` | VPN 隧道 |
| `ParcelFileDescriptor` | `file.fd` (通过 `@ohos.file.fs`) | 文件描述符 |
| `SharedPreferences` | `@ohos.data.preferences` | 键值存储 |
| `DataStore<Preferences>` | `@ohos.data.preferences` | 数据持久化 |
| `Log` (android.util.Log) | `@ohos.hilog` | 日志输出 |
| `Toast` | `@ohos.promptAction (showToast)` | 轻提示 |
| `AlertDialog` | `@ohos.promptAction (showDialog)` | 对话框 |
| `NotificationManager` | `@ohos.notificationManager` | 通知管理 |
| `BiometricPrompt` | `@ohos.biometricAuthentication` | 生物认证 |
| `Settings.Global` | `@ohos.settings` | 系统设置 |
| `System.loadLibrary()` | NAPI `napi_module_register()` | 原生库加载 |
| `Bitmap / Canvas` | `Canvas` / `Image` / `@ohos.graphics.draw` | 2D 绘图 |
| `TileService` | `@ohos.app.form.FormExtensionAbility` | 快捷开关/服务卡片 |
| `ActivityResultContracts` | `@ohos.app.ability.AbilityResult` | 页面返回结果 |
| `PackageManager` | `@ohos.bundle.bundleManager` | 包管理 |
| `DownloadManager` | `@ohos.request (download)` | 文件下载 |

### 8.2 项目依赖库 HarmonyOS 替代方案

| Android 依赖 | 版本 | HarmonyOS 替代 | 备注 |
|-------------|------|---------------|------|
| `androidx.activity:activity-ktx` | 1.13.0 | ArkUI 声明式框架 | 无需外部依赖 |
| `androidx.appcompat:appcompat` | 1.7.1 | ArkUI 组件体系 | 无需外部依赖 |
| `androidx.fragment:fragment-ktx` | 1.8.9 | Navigation + @Component | 无需外部依赖 |
| `androidx.constraintlayout` | 2.2.1 | ArkUI 弹性布局 | Flex/Grid/Relative |
| `androidx.biometric:biometric` | 1.1.0 | `@ohos.biometricAuthentication` | 系统内置 |
| `androidx.datastore:datastore-preferences` | 1.2.1 | `@ohos.data.preferences` | 系统内置 |
| `androidx.preference:preference-ktx` | 1.2.1 | 自定义 + `@ohos.data.preferences` | 无内置对应 |
| `androidx.lifecycle:*` | 2.10.0 | ArkUI 生命周期 + @State | 无需外部依赖 |
| `com.google.android.material:material` | 1.13.0 | HarmonyOS Design 规范 | 系统设计语言 |
| `com.journeyapps:zxing-android-embedded` | 4.3.0 | `@ohos.multimedia.scan` 或三方库 | 需替换 |
| `kotlinx-coroutines-android` | 1.10.2 | ArkTS 原生 async/await | 语言内置 |
| `com.android.tools:desugar_jdk_libs` | 2.1.5 | ArkTS 语言特性 | 无需 |
| `junit:junit` | 4.13.2 | `@ohos.hypium` (HarmonyOS 测试框架) | 需替换 |
| `com.google.code.findbugs:jsr305` | 3.0.2 | 编译时注解 → ArkTS 类型系统 | 无需 |
| `androidx.annotation:annotation` | 1.9.1 | ArkTS 类型系统 | 无需 |

### 8.3 原生构建对照

| 当前 (Android) | 迁移后 (HarmonyOS) |
|---------------|-------------------|
| 构建工具 | Gradle + CMake + Go Makefile | Hvigor + CMake |
| NDK 版本 | Android NDK (r27+) | HarmonyOS NDK (通过 DevEco Studio) |
| C 编译器 | `aarch64-linux-android-clang` | `aarch64-linux-ohos-clang` |
| 动态库格式 | `.so` (ELF) | `.so` (ELF) |
| 库加载方式 | `System.loadLibrary()` + APK zip 提取 | NAPI 模块自动注册 |
| Go 编译 | `GOOS=android GOARCH=arm64` | ❌ 不可用 → C 重写 |
| ELF 清理 | `elf-cleaner` 工具 | 可能不再需要或等价工具 |

### 8.4 参考资料

- [HarmonyOS VPN Extension 开发指南](https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/net-vpn-extension)
- [HarmonyOS NAPI 开发指南](https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/napi-guidelines)
- [WireGuard 协议白皮书](https://www.wireguard.com/papers/wireguard.pdf)
- [wireguard-tools 源码](https://git.zx2c4.com/wireguard-tools/about/)
- [wireguard-go 源码](https://git.zx2c4.com/wireguard-go/about/)
- [HarmonyOS ArkUI 开发文档](https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/arkui-overview)

---

> **文档维护**: 本文档应随项目进展持续更新。每完成一个阶段后，更新对应章节的实际工作量和技术细节。
