---
kind: error_handling
name: WireGuard Android 错误处理体系
category: error_handling
scope:
    - '**'
source_files:
    - tunnel/src/main/java/com/wireguard/android/backend/BackendException.java
    - tunnel/src/main/java/com/wireguard/config/BadConfigException.java
    - tunnel/src/main/java/com/wireguard/crypto/KeyFormatException.java
    - tunnel/src/main/java/com/wireguard/config/ParseException.java
    - tunnel/src/main/java/com/wireguard/android/util/RootShell.java
    - ui/src/main/java/com/wireguard/android/util/ErrorMessages.kt
---

该仓库采用分层、结构化的异常设计，将底层系统错误与上层用户提示解耦，形成「领域异常 → 统一消息映射 → UI 展示」的完整链路。