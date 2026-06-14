---
title: 项目模型
description: 解释 Jugg 如何理解 Gradle 模块、变体、依赖、输出路径和 APK 归属。
status: active
tags:
  - concept
  - project
---

# 项目模型

Jugg 要做增量编译和部署，必须先知道一个 Android 项目“长什么样”：有哪些模块、当前变体是什么、源码和资源在哪里、依赖和 classpath 是什么、APK 输出在哪里，以及 androidTest 是否参与本轮运行。

这些信息合在一起就是 Jugg 的项目模型。

## 项目模型包含什么

| 信息 | 用途 |
|---|---|
| 模块类型 | 区分 application、library、dynamic feature、Java library 和 androidTest synthetic module。 |
| source / res / assets / manifest | 判断文件变化属于哪个模块和哪个编译阶段。 |
| build variant | 找到当前 variant 的 classpath、R、Manifest、DataBinding 和 mapping 产物。 |
| applicationId / namespace | 解析 APK 归属、部署包名和 androidTest target package。 |
| 模块依赖和三方依赖 | 影响源码编译 classpath、依赖变化检测和增量可行性。 |
| APK 信息 | 判断部署目标、test APK、multi APK 和 instrumentation runner。 |

没有这些信息，Jugg 就无法判断一个文件应该怎么编译，也无法知道产物应该部署到哪个 APK。

## 为什么需要合并 IDE 和 Gradle 信息

Jugg 同时读取 IDE 侧和 Gradle 侧的信息，因为两边各有优势：

- IDE 更容易知道当前打开的模块、source root、运行配置和用户选择。
- Gradle 更接近真实构建，能读取依赖、variant、classpath、插件产物和 include build。

最终项目模型不是简单选一边，而是合并两类快照。这样可以减少 IDE 模型不完整或 Gradle 快照滞后的影响。

> [!IMPORTANT]
> 当构建脚本或依赖发生变化时，Jugg 可能需要重新读取 Gradle 信息。此时回退 Gradle 是为了刷新项目模型，不是单纯为了重新编译源码。

## 输出路径为什么重要

不同 AGP 版本会把 Java class、Kotlin class、R、Manifest、DataBinding、mapping 等产物放在不同目录。Jugg 通过项目模型统一这些路径，避免编译器和部署器到处硬编码 AGP 目录。

这影响很多能力：

- 找到最近一次完整构建的 classpath。
- 读取 release mapping / usage 信息。
- 定位 merged Manifest。
- 找到 `R` 相关产物。
- 同步远端构建产物到本地。

## APK 归属不是只看模块名

一个模块的产物可能影响不止一个 APK：

- app 模块通常归属 base APK。
- dynamic feature 或 split APK 可能有独立归属。
- 普通 library 在某些测试场景下可能同时影响 base APK 和 library Test APK。
- androidTest module 需要根据 test APK 和 target package 判断运行位置。

Jugg 会在编译产物中携带目标 APK 信息，部署阶段再按 applicationId 裁剪，避免把资源或 dex 写错目标。

## Android Test 对项目模型的影响

当运行目标切到 Android Test 时，项目模型会额外纳入 androidTest source set 和 test APK 信息。这样 Jugg 才能做到：

- 从测试源文件定位测试 class / method。
- 找到对应 androidTest module。
- 判断需要哪个 test APK。
- 在部署后执行正确的 instrumentation。

如果 Android Test baseline 尚未建立，Jugg 会要求先执行一次 Gradle full build。

## 相关页面

- [编译流水线](./compile-pipeline.md)
- [Android Test 流程](./android-test-flow.md)
- [回退与限制](./fallback-and-limits.md)
