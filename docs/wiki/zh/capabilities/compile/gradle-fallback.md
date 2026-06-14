---
title: Gradle 回退
description: 说明 Jugg 什么时候会从增量编译回退到 Gradle，以及回退后的用户可见行为。
status: active
tags:
  - capability
  - compile
  - fallback
---

# Gradle 回退

Jugg 会优先尝试增量编译，但在本轮修改不适合旁路处理时，会回退到 Gradle。回退的目标是重新建立可信构建基线，让后续增量继续基于正确产物运行。

## 触发 Gradle 回退的情况

| 场景 | 当前支持情况 | 生效方式 |
|---|---|---|
| 用户强制 Gradle | 支持 | 直接跳过增量检查 |
| 没有文件变化 | 支持提示或自动回退 | 由配置决定是否确认 |
| 文件过多或模块过多 | 支持自动回退 | 避免增量成本高于 Gradle |
| 设备或部署状态不可用 | 支持自动回退 | 重新建立 install/deploy 状态 |
| build target 切换 | 支持自动回退 | App 与 androidTest 切换需要新 APK 基线 |
| 构建文件/依赖变化 | 支持确认后回退或依赖增量 | 用户确认决定是否继续增量 |

## 回退如何发生

```text
开始 Jugg 编译
  -> 检查强制 Gradle、文件变化、设备状态、build target
  -> 检查构建文件和依赖变化
  -> 如果不适合增量，返回可回退结果
  -> 执行本地或远端 Gradle 构建
  -> 拉取 APK、classpath、mapping、资源等新基线
```

回退后，Jugg 会继续使用 Gradle 产物更新编译上下文、部署历史和 classpath。下一次小范围修改仍可继续走增量。

## 什么时候主动 Gradle

- 刚切分支或拉取大量代码。
- 升级 AGP、Kotlin、Gradle、R8 或重要构建插件。
- 修改 source set、variant、Manifest placeholder 来源。
- 修改注解处理器、KSP/KAPT、Compose compiler plugin 配置。
- release 增量后出现运行时异常。

## 关联能力

- [源码编译](./source-compile.md)
- [依赖库增量编译](./dependency-incremental.md)
- [编译阶段说明](../../guide/compile.md)
