---
title: 兼容层
description: 解释 Jugg 如何隔离 Android Studio 版本 API、平台运行环境和命令行差异。
status: active
tags:
  - concept
  - compatibility
---

# 兼容层

Jugg 运行在 Android Studio / IntelliJ 生态里，而 Android Studio 的部署、调试、设备、APK provider 等 API 会随着版本变化。兼容层的作用是把这些版本差异隔离起来，让编译部署主流程尽量保持稳定。

## 为什么需要兼容层

同一个能力在不同 Android Studio 版本里可能存在差异：

- API 包名或方法签名变化。
- 部署器内部类型迁移。
- install、Apply Changes、deployment cache 的实现细节变化。
- Debug attach 的调用方式变化。
- IDE Android 模型字段变化。

如果业务主流程直接依赖这些内部 API，新版 Android Studio 一改动，插件可能在启动或部署时崩溃。兼容层把风险集中在版本适配模块中。

## 兼容层怎么工作

Jugg 会根据当前 IDE 版本选择优先适配实现。调用 Android Studio 能力时，如果发现是典型 API 兼容错误，会尝试其他版本实现；如果是业务错误，则不会吞掉，而是继续向上暴露。

这意味着：

- 版本 API 差异会尽量在兼容层内兜底。
- 真正的安装失败、设备失败、部署失败不会被误当成兼容问题。
- 新版 Android Studio 高于已知版本时，Jugg 会尝试使用最近的高版本适配。

## 主流程和兼容层的边界

Jugg 的编译、部署、项目模型、MCP 和 CLI 不应该直接关心某个 Android Studio 版本的内部类型。主流程只使用稳定的中立数据：

- 安装会话。
- overlay checkpoint。
- deployment cache 快照。
- 设备 ADB 能力。
- IDE 模块信息。
- Debug attach 结果。

具体如何调用某个 Android Studio 版本的 API，由兼容层处理。

## 命令行和平台抽象

Jugg 还有一层平台抽象，用于区分 IDE 插件运行时和命令行运行时：

- IDE 里可以访问 Run Configuration、设备选择、Tool Window 和 Android Studio 服务。
- CLI 里没有这些 UI 和 IDE runtime，只复用核心编译能力。
- 公共逻辑放在核心层，平台相关能力由运行环境注入。

因此，CLI 和 IDE 的能力相似但不完全相同。遇到行为差异时，需要先确认当前入口是否具备对应平台能力。

## 用户会感知到什么

兼容层通常是透明的。你可能只在以下场景感知到它：

- 升级 Android Studio 后，Jugg 提示使用兼容实现。
- 某些部署能力在旧设备或新 IDE 上切到保守路径。
- Debug attach 在某些版本不可用或需要不同等待策略。
- 新 Android Studio 版本刚发布时，需要等待 Jugg 增加更精确适配。

## 相关页面

- [部署策略](./deploy-strategy.md)
- [JVMTI Agent](./jvmti-agent.md)
- [MCP 与 CLI](./mcp-and-cli.md)
- [兼容性参考](../reference/compatibility.md)
