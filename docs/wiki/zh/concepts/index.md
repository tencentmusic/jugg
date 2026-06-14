---
title: 概念
description: 了解 Jugg 的核心工作模型：编译、部署、项目模型、测试、工具和兼容边界。
status: active
tags:
  - concept
---

# 概念

本章节解释 Jugg 背后的工作模型，帮助你判断一次 Run 为什么会增量、为什么会回退、为什么有时需要重启 App，以及 Android Test、MCP/CLI 和兼容层如何接入同一套能力。

如果你只想完成日常操作，请先看[使用指南](../guide/)。如果你遇到具体失败，请看[问题排查](../troubleshooting/)。

## 先建立一张心智图

Jugg 不是完整替代 Gradle 的构建系统。它更像是建立在最近一次可信 Gradle 构建产物之上的增量执行层：

1. 读取项目快照，知道模块、变体、源码、资源、依赖和 APK 输出在哪里。
2. 监听并归类本轮文件变化。
3. 尝试用增量编译生成 staging 产物。
4. 根据历史索引判断哪些产物可以热更新，哪些需要补编译、重启或重装。
5. 把部署结果提交为新的历史状态，供下一轮增量继续使用。

这也是为什么一次小改动可能很快生效，而一次构建脚本、依赖或目标切换会回到 Gradle：Jugg 优先追求可复用和可靠，而不是强行绕过所有构建步骤。

## 页面导读

| 页面 | 适合回答的问题 |
|---|---|
| [Jugg 工作原理](./how-jugg-works.md) | 点击 Run 后，Jugg 如何串起编译、部署、状态提交和回退。 |
| [增量编译](./incremental-compile.md) | 一次增量编译做了什么，什么时候会继续补编译或回退 Gradle。 |
| [编译流水线](./compile-pipeline.md) | 资源、Manifest、R、DataBinding/ViewBinding、Java/Kotlin、DEX 如何衔接。 |
| [部署策略](./deploy-strategy.md) | install、hot reload、hot fix、兼容部署和重启策略如何选择。 |
| [回退与限制](./fallback-and-limits.md) | 哪些场景容易回退，哪些能力边界不应误判为异常。 |
| [项目模型](./project-model.md) | Jugg 如何理解 Gradle 项目、模块、变体、依赖和 APK 归属。 |
| [部署数据与影响分析](./deploy-data-and-impact.md) | 为什么改一个类会牵动调用方、子类、资源或 release 补偿。 |
| [JVMTI Agent](./jvmti-agent.md) | Jugg 为什么需要运行时 agent，以及它如何影响兼容部署。 |
| [Android Test 流程](./android-test-flow.md) | androidTest 如何复用编译部署链路，并在部署后运行 instrumentation。 |
| [MCP 与 CLI](./mcp-and-cli.md) | Agent 和命令行如何调用 Jugg，如何理解异步任务、日志和项目参数。 |
| [兼容层](./compatibility-layer.md) | Jugg 如何隔离 Android Studio API 变化和命令行运行环境差异。 |

## 推荐阅读顺序

初次理解 Jugg，建议按下面顺序阅读：

1. [Jugg 工作原理](./how-jugg-works.md)
2. [增量编译](./incremental-compile.md)
3. [部署策略](./deploy-strategy.md)
4. [回退与限制](./fallback-and-limits.md)

如果你在排查具体问题，可以直接跳到对应概念页，再进入问题排查章节。
