---
kind: configuration_system
name: WireGuard Android 配置系统：wg-quick 配置文件与 DataStore 偏好存储
category: configuration_system
scope:
    - '**'
source_files:
    - tunnel/src/main/java/com/wireguard/config/Config.java
    - tunnel/src/main/java/com/wireguard/config/Interface.java
    - tunnel/src/main/java/com/wireguard/config/Peer.java
    - ui/src/main/java/com/wireguard/android/configStore/ConfigStore.kt
    - ui/src/main/java/com/wireguard/android/configStore/FileConfigStore.kt
    - ui/src/main/java/com/wireguard/android/preference/PreferencesPreferenceDataStore.kt
    - ui/src/main/res/xml/preferences.xml
    - gradle/libs.versions.toml
    - gradle.properties
---

## 配置系统概述

WireGuard Android 项目采用分层配置架构，将 WireGuard 隧道配置（wg-quick 格式）与应用设置（DataStore 偏好）分离管理。

## 核心组件

### 1. wg-quick 配置解析层（tunnel 模块）

**配置文件格式**：遵循标准的 wg-quick 格式，包含 `[Interface]` 和 `[Peer]` 两个主要部分。

**核心类结构**：
- `Config.java`：顶层配置容器，组合 Interface 和多个 Peer
- `Interface.java`：接口配置，包含私钥、地址、DNS、监听端口、MTU等属性
- `Peer.java`：对端配置，包含公钥、允许IP、端点、持久保活等
- `Attribute.java`：键值对解析器
- `BadConfigException.java`：统一的配置错误处理

**解析流程**：
```java
Config.parse(InputStream) → Config.parse(BufferedReader) → 
Builder.parseInterface() + Builder.parsePeer() → 不可变对象构建
```

**验证规则**：
- Interface 必须包含 privatekey
- Peer 必须包含 publickey
- 数值字段范围校验（端口 0-65535，MTU ≥ 0）
- 不允许同时设置 includedapplications 和 excludedapplications

### 2. 配置持久化层（ui 模块）

**ConfigStore 接口设计**：
```kotlin
interface ConfigStore {
    fun create(name: String, config: Config): Config
    fun load(name: String): Config
    fun save(name: String, config: Config): Config
    fun delete(name: String)
    fun rename(name: String, replacement: String)
    fun enumerate(): Set<String>
}
```

**FileConfigStore 实现**：
- 每个隧道对应一个 `.conf` 文件
- 文件名即隧道名称，存储在 `context.filesDir` 下
- 使用 UTF-8 编码读写
- 通过 `toWgQuickString()` 序列化为标准格式

### 3. 应用设置存储（DataStore + Preference）

**PreferencesPreferenceDataStore**：
- 基于 `androidx.datastore.preferences` 的异步偏好存储
- 支持所有基本类型（String、Int、Boolean、Float、Long、Set<String>）
- 通过协程实现异步写入，同步读取使用 `runBlocking`
- 作为 `PreferenceDataStore` 的实现，无缝集成到 Preference UI

**设置项定义**：
- XML 定义在 `res/xml/preferences.xml`
- 包含恢复启动、深色主题、多隧道、日志查看器等开关
- 自定义 Preference 类扩展功能（版本显示、工具安装等）

## 架构特点

### 不可变性设计
所有配置对象都是不可变的，通过 Builder 模式构建，确保线程安全和状态一致性。

### 双格式支持
- `toWgQuickString()`：wg-quick 兼容格式，用于文件存储
- `toWgUserspaceString()`：跨平台用户空间 API 格式，用于 Go 后端通信

### 错误处理统一
使用 `BadConfigException` 提供详细的错误位置、原因和上下文信息，便于调试和用户反馈。

### 模块化分离
- tunnel 模块：纯 Java 配置解析逻辑，无 Android 依赖
- ui 模块：Android 特定的存储和 UI 交互

## 配置来源层次

1. **编译时配置**：`gradle.properties` 中的版本信息
2. **资源配置文件**：`libs.versions.toml` 中的依赖版本
3. **运行时配置**：wg-quick 配置文件（持久化存储）
4. **用户偏好**：DataStore 存储的应用设置

该系统实现了配置解析、验证、持久化的完整生命周期管理，同时保持了良好的模块化和可测试性。