---
title: 兼容性
description: 汇总 Jugg 支持的 IDE、AGP、Gradle、Kotlin、Android 设备和产品能力范围。
status: active
tags:
  - reference
  - compatibility
---

# 兼容性

本页用于快速确认当前开发环境和目标能力是否在 Jugg 的支持范围内。具体触发条件、处理结果和限制请进入对应能力页查看。

## 环境兼容范围

| 项目 | 支持范围 |
|---|---|
| Android Studio | Bumblebee 至 Quail |
| IntelliJ IDEA | 2021.1.3 至 2025.1 Beta |
| AGP | 3.4 至 9.1 |
| Gradle | 5.4.1 至 9.2.1 |
| Kotlin | 1.3 至 2.2 |
| target API | 21 至 36 |
| Android 设备 | Android 8 至 16 |

- Android Studio 当前兼容实现覆盖 Chipmunk 至 Quail；Bumblebee 使用低版本兼容实现。
- AGP 已验证 3.4.2、3.5.4、4.1.3、4.2.0、7.2.2、8.3.0、8.7.0、8.13.0、9.1.2。
- Gradle 兼容验证覆盖 5.4.1、6.8、7.3.3、9.2.1，Gradle 8.x 也在支持范围内。
- Android 8 至 10 使用兼容部署；Android 11 及以上支持标准增量部署。

> [!NOTE]
> 版本范围表示 Jugg 已覆盖的兼容边界。未列入已验证版本的中间版本通常可以直接使用；遇到兼容问题时，请通过插件的 Report issues 提交报告。

## 能力支持范围

| 范围 | 支持能力 |
|---|---|
| 编译 | [源码编译](../capabilities/compile/source-compile.md)、[重编译/扩散编译](../capabilities/compile/recompile-propagation.md)、[资源编译](../capabilities/compile/resource-compile.md)、[AndroidManifest 编译](../capabilities/compile/manifest.md)、[so 更新](../capabilities/compile/so-update.md)、[DataBinding/ViewBinding](../capabilities/compile/databinding-viewbinding.md)、[Kotlin Compose](../capabilities/compile/kotlin-compose.md)、[KMP 与 Compose Multiplatform](../capabilities/compile/kmp-compose-multiplatform.md)、[注解器](../capabilities/compile/annotation-processors.md)、[自定义编译器](../capabilities/compile/custom-compiler.md)、[依赖库增量编译](../capabilities/compile/dependency-incremental.md)、[AabResGuard](../capabilities/compile/aab-resguard.md) |
| 部署 | [多 APK](../capabilities/deploy/multi-apk.md)、[多设备](../capabilities/deploy/multi-device.md) |
| Android Test | [Application Android Test](../capabilities/test/application-android-test.md)、[Library Android Test](../capabilities/test/library-android-test.md)、[Test Results UI](../capabilities/test/test-results-ui.md)、[Logcat 归因](../capabilities/test/logcat-attribution.md) |
| Agent、CLI 与 MCP | [Agent Skills](../capabilities/tools/agent-skills.md)、[Jugg CLI](../capabilities/tools/cli.md)、[构建与部署](../capabilities/tools/cli-build-deploy.md)、[Android Test CLI](../capabilities/tools/cli-android-test.md)、[运行时与设备](../capabilities/tools/cli-runtime-device.md)、[UI 自动化](../capabilities/tools/ui-automation.md)、[UI 布局证据](../capabilities/tools/layout-verify.md)、[远端诊断](../capabilities/tools/remote-diagnosis.md)、[MCP](../capabilities/tools/mcp.md) |

- [Release 编译](../capabilities/compile/release-compile.md)为实验性支持。
- Gradle 脚本、依赖、变体、source set 或复杂插件配置变化需要 Gradle 构建。
- Android Test 默认关闭，首次使用时会有对应开启提示。
- Agent、CLI 与 MCP 使用前需先 [主动安装](../guide/cli)。

## 相关页面

- [Jugg 能力概览](../capabilities/)
- [限制](./limits.md)
- [Android Studio 版本兼容](../concepts/compatibility-layer.md)
