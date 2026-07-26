---
kind: frontend_style
name: Material 3 主题与 Android 原生样式体系
category: frontend_style
scope:
    - '**'
source_files:
    - ui/src/main/res/values/themes.xml
    - ui/src/main/res/values-night/themes.xml
    - ui/src/main/res/values/colors.xml
    - ui/src/main/res/values/styles.xml
    - ui/src/main/res/values/dimens.xml
    - ui/src/main/res/values/attrs.xml
    - ui/src/main/res/values-v27/styles.xml
    - ui/build.gradle.kts
---

WireGuard Android 的 UI 样式完全基于 Android 原生资源系统，采用 Material Design 3（Material3）作为设计语言，通过 XML 资源文件集中管理主题、颜色、尺寸和样式。

**主题系统**：应用以 `WireGuardTheme` 为核心主题，继承自 `Theme.Material3.Light` / `Theme.Material3.Dark`，在 `values/themes.xml` 和 `values-night/themes.xml` 中分别定义浅色/深色主题的完整色板映射。所有颜色均通过 `md_theme_light_*` / `md_theme_dark_*` 命名空间的颜色资源引用，遵循 Material You 的动态配色规范。

**样式分层**：`styles.xml` 定义了三层样式结构——`AppThemeBase` 继承 `WireGuardTheme` 并覆盖 MaterialCardView、Toolbar、BottomSheetDialog 等组件样式；`AppTheme` 作为最终应用主题，在 `values-v27/styles.xml` 中针对 API 27+ 补充状态栏和导航栏透明配置；`TvTheme` 为 TV 设备提供无 ActionBar 的变体。

**颜色与尺寸**：`colors.xml` 包含完整的 Material3 色板（primary/secondary/tertiary/error/background/surface 及其 on/container/inverse 变体），`dimens.xml` 集中定义间距常量（如 `normal_margin=8dp`、`fab_margin=16dp`）。自定义属性通过 `attrs.xml` 声明，用于 `Multiselected` 和 `TvCardView` 等自定义控件的状态绑定。

**布局与响应式**：布局文件位于 `res/layout/`，使用 CoordinatorLayout + FragmentContainerView 组合；通过 `layout-sw600dp/` 目录为平板设备提供适配布局。支持多语言资源（`values-xx-rXX/strings.xml`）和夜间模式（`values-night/` 目录覆盖 themes 和 colors）。

**构建集成**：`build.gradle.kts` 启用 DataBinding 和 ViewBinding，依赖 `google.material` 库获取 Material3 组件，通过 `androidResources.generateLocaleConfig = true` 自动生成语言配置。