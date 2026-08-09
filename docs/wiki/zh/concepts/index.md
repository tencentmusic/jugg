---
title: 实现原理
description: 从一次 Run 的完整流程出发，理解 Jugg 的增量编译、部署、状态恢复和运行时机制。
status: active
tags:
  - concept
---

# 实现原理

这里介绍 Jugg 如何缩短 Android 日常开发中的「修改—运行—验证」循环，以及它如何保证连续多轮增量之后，编译产物、设备状态和源码仍然一致。

如果你正在完成一次具体操作，请从[使用指南](../guide/)开始；如果你想确认某项能力支持哪些场景，请查看[核心能力](../capabilities/)；遇到失败时优先进入[问题排查](../troubleshooting/)。

## 从一次 Run 建立全局认识

Jugg 将最近一次完整 Gradle 构建作为可信起点。后续点击 Run 时，它先判断当前工程和设备是否还能沿用这份基线，再编译本轮变化及其受影响代码，最后根据产物类型选择部署方式。只有部署成功，本轮结果才会成为下一次增量的起点。

第一次了解 Jugg，可以先阅读[《Jugg 工作原理》](./how-jugg-works.md)，再按遇到的问题进入下面的专题。

## 按问题选择页面

| 你想了解什么 | 建议阅读 |
|---|---|
| 一次 Run 如何完成决策、编译、部署和状态提交 | [Jugg 工作原理](./how-jugg-works.md)、[编译流水线](./compile-pipeline.md)、[增量部署](./deploy-strategy.md)、[Gradle 回退与基线重建](./gradle-fallback-baseline.md) |
| 为什么只改一个文件仍可能编译其他文件 | [增量编译](./incremental-compile/)、[工程模型同步](./project-model.md)、[部署数据与影响分析](./deploy-data-and-impact.md) |
| class 和资源怎样进入设备，为什么有时重启或更新 APK | [增量部署](./deploy-strategy.md)、[Apply Changes 中的 class 与 overlay](./apply-changes.md)、[APK 更新与安装](./apk-update-and-install.md) |
| 为什么设备未 ready 仍能部署，状态不一致时怎样恢复 | [Direct Overlay 部署机制](./direct-overlay.md)、[部署状态与恢复](./deploy-state-recover.md)、[部署自愈机制](./deploy-self-healing.md)、[兼容部署](./compat-deploy.md) |
| 代码如何在应用进程中被替换并继续运行 | [App 进程内 Jugg runtime](./jugg-runtime.md)、[Jugg JVMTI Agent](./jugg-jvmti-agent.md) |
| 测试、界面取证和版本兼容如何接入主流程 | [Android Test 流程](./android-test-flow.md)、[布局导出与界面证据](./layout-dump-and-ui-evidence.md)、[Android Studio 版本兼容](./compatibility-layer.md) |

## 推荐阅读路径

如果你准备系统理解 Jugg，建议依次阅读：

1. [Jugg 工作原理](./how-jugg-works.md)：先建立一次 Run 的完整模型。
2. [增量编译](./incremental-compile/)：理解不同输入如何生成局部产物。
3. [增量部署](./deploy-strategy.md)：理解产物如何通过 Apply Changes、APK 更新或兼容部署在设备上生效。
4. [部署自愈机制](./deploy-self-healing.md)：理解已有增量产物怎样通过重试、切换策略和重装继续生效。
5. [Gradle 回退与基线重建](./gradle-fallback-baseline.md)：理解当前构建基线何时必须刷新。

需要查找配置、命令和状态含义时，请使用[参考手册](../reference/)。
