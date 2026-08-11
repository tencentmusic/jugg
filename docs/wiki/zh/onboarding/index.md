---
title: 快速开始
description: 以最小步骤完成 Jugg 安装、首次运行和扩展阅读入口。
status: active
tags:
  - onboarding
---

# 快速开始

Jugg 是 Android Studio 上的 Android 增量构建插件，主要用于减少日常调试里的完整 Gradle 构建次数。它不要求修改工程代码或 Gradle 配置；你仍然在 Android Studio 里选择运行配置、点击 Run，Jugg 会判断本轮走增量编译、热部署，还是回退到 Gradle。

## 先跑通一次

第一次使用按这两步走：

1. [安装](./installation.md)：安装插件，并确认运行配置下拉框里出现 `jugg:模块名`。
2. [首次运行](./first-run.md)：用 Jugg 跑一次 App，让插件建立 Gradle 基线和部署状态。

## 了解更多

| 主题 | 入口 |
|---|---|
| 日常修改后怎么跑 | [运行 App](../guide/run.md) |
| 哪些场景适合回退 Gradle | [限制说明](../reference/limits.md) |
| 有云开发机构建资源时怎么接入 | [云开发机配置](./agent-setup.md) |
| 远端 Gradle 如何工作 | [远端 Gradle](../guide/remote-gradle.md) |

## 快速查找

按 `Command+K` 打开 Search，输入功能名、错误现象或日志关键词。

## 遇到问题时

先按现象选入口，不用从头翻文档：

- **编译出现源码、资源或生成源码错误**：看 [编译失败](../troubleshooting/compile-failed.md)。
- **运行成功但代码或资源仍是旧结果**：看 [改动没有生效](../troubleshooting/changes-not-applied.md)。
- **App 无法安装、启动或进入 Debug**：看 [无法安装、启动或进入 Debug](../troubleshooting/app-cannot-run.md)。
- **部署后 App 崩溃**：看 [部署后 App 崩溃](../troubleshooting/runtime-crash.md)。
- **不确定要提交哪些信息**：用 [报告问题](../guide/report-issue.md) 上传日志并附上 Issue ID。
