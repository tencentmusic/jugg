---
title: 兼容性
description: 汇总 Jugg 对 Android Studio、设备、Debug、Gradle 和运行环境的兼容口径。
status: active
tags:
  - reference
  - compatibility
---

# 兼容性

Jugg 运行在 Android Studio / IntelliJ 插件环境中，并复用 Android Studio 的项目模型、设备选择、部署器和 Debug 能力。兼容性主要取决于当前 Android Studio API、设备状态、Gradle 项目结构和 Jugg 的本地缓存是否一致。

## Android Studio 版本

Jugg 通过兼容层适配 Android Studio deployer API 的版本差异。

| 兼容层目录 | Android Studio 代号 |
|---|---|
| `deploy_compat/v_quail` | Quail |
| `deploy_compat/v_panda` | Panda |
| `deploy_compat/v_otter` | Otter 2 Feature Drop |
| `deploy_compat/v_narwhal_feature` | Narwhal Feature Drop |
| `deploy_compat/v_narwhal` | Narwhal |
| `deploy_compat/v_meerkat` | Meerkat |
| `deploy_compat/v_iguana` | Iguana |
| `deploy_compat/v_hedgehog` | Hedgehog |
| `deploy_compat/v_giraffe` | Giraffe |
| `deploy_compat/v_chipmunk` | Chipmunk |

当当前 Android Studio 高于已知最高版本时，Jugg 会优先尝试最高版本兼容实现；低于已知最低版本时会退到 Chipmunk 兼容实现。兼容层只能处理 Android Studio API 形态差异，不能掩盖真实部署失败。

## 设备和系统

Jugg 支持常见 Android 设备开发循环，但设备兼容还受以下因素影响：

- 设备必须可被 Android Studio / ADB 正常识别。
- App 被卸载、覆盖安装或设备重启后，可能需要 clean reinstall 或 Gradle 构建重新建立基线。
- HarmonyOS、HyperOS、Android 11 以下设备等场景会走兼容部署策略。
- 设备息屏、未解锁、目标 App 不在前台时，UI 类 MCP 工具会直接返回对应错误。

> [!IMPORTANT]
> Jugg 的增量部署依赖“本地部署历史、设备安装状态、当前 APK 结构”一致。三者不一致时，重新安装比继续增量更可靠。

## Debug 兼容

Jugg Debug 会在 Jugg 编译和部署成功后，调用 Android Studio 的 Java Debugger attach 能力。

| 场景 | 兼容口径 |
|---|---|
| 普通 Run | 不创建 Java debugger session。 |
| Jugg Debug | 部署成功后等待目标进程进入 debugger ready 状态，再交给 Android Studio 创建 session。 |
| 低版本或不支持的 AS API | 可能显示明确的不支持原因，需要使用 Android Studio 原生 Debug 或 Attach。 |
| App 非 debuggable | 断点不会命中，需先确认构建类型和安装包状态。 |

## Gradle / AGP 兼容

Jugg 会读取 Gradle 项目信息、classpath、APK 输出和 androidTest 相关产物，但它不是完整 Gradle pipeline 的替代品。

更容易需要 Gradle 构建的变化包括：

- 修改 Gradle 脚本、插件版本、依赖声明或 version catalog。
- 切换 build variant、source set、target package 或 androidTest 目标。
- 依赖 Gradle task 副作用生成源码、资源、Manifest 或 classpath。
- AGP、Kotlin、R8、资源混淆等构建链路升级。

## 相关页面

- [限制](./limits.md)
- [Jugg 工作原理](../concepts/how-jugg-works.md)
- [Android Studio 版本兼容](../concepts/compatibility-layer.md)
- [Debug](../guide/debug.md)
