---
title: 快速开始
description: 30 秒了解 Jugg 旁路增量构建机制，完成 Android Studio 插件安装与首次运行。
status: active
tags:
  - onboarding
  - quickstart
---

# 快速开始

Jugg 是 Android Studio 上的 Android 增量构建与即时热更插件，核心目标是大幅减少日常编码调试时的完整 Gradle 构建耗时。它无需修改工程代码或 `build.gradle` 脚本，也无需引入任何 SDK。在 Android Studio 中选择 Jugg 运行配置并点击 Run，少量代码与资源修改通常可在 3 秒内直接生效到测试设备。

Jugg Run Configuration 与原生 App Run Configuration 相互独立共存。需要回到原生 Gradle 构建时，直接在下拉框切回原生 App 配置即可，两者完全解耦。

## 开发效率对比

| 评估维度 | 原生 Android Studio (Gradle Run) | Jugg 旁路增量构建 |
|---|---|---|
| **代码/资源修改生效耗时** | 常见耗时 1 ~ 3 分钟（大工程更久） | **通常 1 ~ 3 秒** |
| **构建机制** | 触发完整 Gradle 任务流水线 | 旁路增量编译，复用已有构建基线 |
| **工程侵入性** | 默认依赖原生 Gradle 脚本 | **零侵入**：不改 Gradle、不加 SDK |
| **失败兜底** | 无 | 超出增量范围时**自动回退 Gradle** 并刷新基线 |

## 两步快速上手

第一次接入 Jugg 仅需两步：

1. **[安装 Jugg 插件](./installation.md)**：下载插件 `.zip` 包并在 Android Studio 本地磁盘安装，确认运行配置下拉列表自动生成 `jugg:app`。
2. **[首次运行与建立基线](./first-run.md)**：选中 Jugg 配置点击 Run，跑通一次以自动捕获 Gradle 产物并建立增量基线。

> [!TIP]
> 如果已接入 Jugg CLI，日常修改后也可以直接在终端触发增量编译并部署：
> ```bash
> jugg deploy
> ```

## 核心能力与打包机制导航

深入了解 Jugg 如何在不同场景下工作：

| 关注主题 | 核心说明 | 入口 |
|---|---|---|
| **日常开发运行** | 日常修改 Java/Kotlin/XML 代码后的运行与热更方式 | [运行 App](../guide/run.md) |
| **支持的打包方式** | AabResGuard、Dynamic Feature、混淆增量等支持情况 | [支持哪些打包方式](../troubleshooting/supported-packaging.md) |
| **增量编译原理** | 深入理解 Class 影响分析与 D8 增量 dex 机制 | [增量编译概览](../concepts/incremental-compile/) |
| **回退边界与限制** | 哪些场景会自动回退至完整 Gradle 构建 | [限制说明](../reference/limits.md) |
| **远端编译加速** | 配合远端独立 Linux 构建机分担编译压力 | [远端 Gradle](../guide/remote-gradle.md) |

## 遇到问题时

按常见现象直接定位排查，无需从头翻阅全文：

- **编译报错**（源码/资源/生成代码异常）：查看 [编译失败排查](../troubleshooting/compile-failed.md)。
- **改动未生效**（运行显示成功但界面是旧代码）：查看 [改动没有生效](../troubleshooting/changes-not-applied.md)。
- **App 无法运行**（无法安装、启动或断点未连接）：查看 [无法安装、启动或进入 Debug](../troubleshooting/app-cannot-run.md)。
- **运行时崩溃**（部署后 App 出现闪退）：查看 [部署后 App 崩溃](../troubleshooting/runtime-crash.md)。
- **日志反馈**：通过 [报告问题](../guide/report-issue.md) 导出完整诊断日志并提交 Issue。

---

## 下一步指引

完成概念了解后，立即开始安装插件：

👉 **[前往安装 Jugg 插件](./installation.md)**
