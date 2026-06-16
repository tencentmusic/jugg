---
title: 项目模型
description: 说明 Jugg 如何收集模块、依赖、编译参数和构建产物路径，作为增量编译基线。
status: active
tags:
  - concept
  - project
---

# 项目模型

Jugg 要绕过 Gradle task 执行增量编译，必须先拿到 Gradle 编译所需的工程信息。原文中把这部分称为编译上下文管理。

项目模型不是用户可见的功能，而是 Jugg 判断文件如何编译、产物如何部署的基础。

## 项目模型包含什么

原文中列出的信息包括：

| 信息 | 用途 |
|---|---|
| Android SDK 路径 | 提供 `android.jar` 等编译依赖 |
| 工程模块列表 | 判断文件属于哪个模块 |
| 模块路径和源码目录 | 过滤变化文件并定位源码 |
| AndroidManifest 路径 | 处理 Manifest 编译和 APK 更新 |
| Build Variant | 找到当前变体的 classpath 和产物 |
| 模块依赖和库依赖 | 组装 Java / Kotlin / D8 编译参数 |
| Java / Kotlin 版本 | 设置编译器参数 |
| APK 输出路径 | 解析基线 APK，生成部署数据 |

这些信息最初主要通过 IDE API 读取，例如 `ModuleManager`、`ProjectBuildModel` 和 Android 插件模型。

## 为什么还要读取 Gradle 信息

IDE 模型速度快，但可能和 Gradle 运行时事实不完全一致。答辩稿中提到，用户曾反馈编译时小概率找不到依赖。排查后发现，原因是 IDE 同步工程信息后偶尔丢失依赖。

Jugg 后续通过 Gradle init script 读取 Gradle 环境中的信息。脚本在 Gradle 命令执行时注入，在编译完成时读取依赖和构建产物信息，并保存到指定文件。

为了避免插件和脚本维护两套数据处理逻辑，脚本使用 Kotlin 编写，并由插件源码生成。

## 数据存放

原文提到，Jugg 的数据存放在工程根目录的 `build/jugg` 目录中，`build/jugg/database` 用于存放编译上下文相关数据库。

主要数据包括：

| 数据 | 用途 |
|---|---|
| APK 解析数据库 | 保存 dex 解析结果，用于部署数据和调用关系查询 |
| 编译上下文记录 | 保存历史部署文件，用于恢复工程和设备现场 |
| 部署历史 | 记录部署情况和设备切换 |
| 项目信息数据库 | 缓存环境参数，减少重复读取耗时 |
| 源码文件索引 | 根据 dex source file 字段反查源码路径 |

## 与回退的关系

当构建脚本、依赖或变体相关配置发生变化时，旧项目模型可能不再可信。此时 Jugg 需要回到 Gradle，重新读取工程信息和构建产物。

回退 Gradle 的目的之一，就是刷新项目模型，让后续增量编译继续基于可信数据运行。

## 相关页面

- [编译流水线](./compile-pipeline.md)
- [Android Test 流程](./android-test-flow.md)
- [回退与限制](./fallback-and-limits.md)
