# [WireGuard](https://www.wireguard.com/) 的 Android 图形界面

**[从 Play 商店下载](https://play.google.com/store/apps/details?id=com.wireguard.android)**

这是 [WireGuard](https://www.wireguard.com/) 的 Android 图形界面。它会[优先使用内核实现](https://git.zx2c4.com/android_kernel_wireguard/about/)，并在不可用时回退到无需 root 权限的[用户态实现](https://git.zx2c4.com/wireguard-go/about/)。

## 构建

```
$ git clone --recurse-submodules https://git.zx2c4.com/wireguard-android
$ cd wireguard-android
$ ./gradlew assembleRelease
```

macOS 用户可能需要 [flock(1)](https://github.com/discoteq/flock)。

## 嵌入集成

tunnel 库已发布到 [Maven Central](https://search.maven.org/artifact/com.wireguard.android/tunnel)，并附有[详尽的类库文档](https://javadoc.io/doc/com.wireguard.android/tunnel)。

```
implementation 'com.wireguard.android:tunnel:$wireguardTunnelVersion'
```

该库使用了 Java 8 特性，因此请确保在 gradle 配置中通过 [desugaring](https://developer.android.com/studio/write/java8-support#library-desugaring) 提供支持：

```
compileOptions {
    sourceCompatibility JavaVersion.VERSION_17
    targetCompatibility JavaVersion.VERSION_17
    coreLibraryDesugaringEnabled = true
}
dependencies {
    coreLibraryDesugaring "com.android.tools:desugar_jdk_libs:2.0.3"
}
```

## 翻译

欢迎在我们的[翻译平台](https://crowdin.com/project/WireGuard)上帮助将应用翻译成多种语言。
