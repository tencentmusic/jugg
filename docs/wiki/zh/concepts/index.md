---
title: 实现原理
description: 了解 Jugg 的工作模型：编译、部署、工程上下文、测试、工具和兼容边界。
status: active
tags:
  - concept
---

# 实现原理

这一章解释 Jugg 的运行模型，不复述内部类名，只回答几个用户会直接遇到的问题：

- 为什么一次小改动可以跳过完整 Gradle Run。
- Jugg 如何用 Gradle 基线、增量编译、影响分析和混合部署把结果送到设备。
- 哪些场景必须回到 Gradle，不能当成 Jugg 异常。

如果你只想完成日常操作，请先看[使用指南](../guide/)。如果你遇到具体失败，请看[问题排查](../troubleshooting/)。

## 先看整体模型

Jugg 不替代 Gradle。它依赖最近一次可信 Gradle 构建留下的 APK、class、资源和工程参数，在这份基线之上处理日常小改动：

1. 读取项目快照，知道模块、变体、源码、资源、依赖和 APK 输出在哪里。
2. 监听并归类本轮文件变化。
3. 尝试用增量编译生成 staging 产物。
4. 根据历史索引判断哪些产物可以热更新，哪些需要补编译、重启或重装。
5. 把部署结果提交为新的历史状态，供下一轮增量继续使用。

小范围源码或资源改动通常能很快生效；构建脚本、依赖或运行目标变化则可能回到 Gradle。无法确认的构建步骤，Jugg 不会硬绕过去。

## 页面导读

| 页面 | 回答的问题 |
|---|---|
| [Jugg 工作原理](./how-jugg-works.md) | 点击 Run 后，Jugg 如何串起编译、部署、状态提交和回退。 |
| [增量编译](./incremental-compile/) | 一次增量编译做了什么，什么时候会继续补编译或回退 Gradle。 |
| [编译调度流程](./compile-pipeline.md) | Run 如何进入增量或 Gradle，staging、补编译和失败收口如何推进。 |
| [部署策略](./deploy-strategy.md) | install、hot reload、hot fix、兼容部署和重启策略如何选择。 |
| [回退与限制](./fallback-and-limits.md) | 哪些场景容易回退，哪些能力边界不应误判为异常。 |
| [工程上下文获取](./project-model.md) | IDE、Gradle 和 include build 信息如何合并成统一的项目快照。 |
| [部署数据与影响分析](./deploy-data-and-impact.md) | 为什么改一个类会牵动调用方、子类、资源或 release 补偿。 |
| [部署状态与恢复](./deploy-state-recover.md) | history、deployment cache 和设备 overlay id 如何决定 recover 或 reinstall。 |
| [兼容部署](./compat-deploy.md) | 当设备不适合在线热重载时，Jugg 如何切换到兼容热修复路径。 |
| [Jugg Runtime](./jugg-runtime.md) | App 进程内 runtime 如何支撑热修复、兼容检测和 UI 工具。 |
| [JVMTI Agent](./jvmti-agent.md) | Jugg 为什么需要运行时 agent，以及它如何影响兼容部署。 |
| [Android Test 流程](./android-test-flow.md) | androidTest 如何复用编译部署链路，并在部署后运行 instrumentation。 |
| [布局 dump 与 UI 证据](./layout-dump-and-ui-evidence.md) | layout-dump 如何通过 App 内 ViewHierarchy 服务导出 HTML 证据。 |
| [兼容层](./compatibility-layer.md) | Jugg 如何隔离 Android Studio API 变化和命令行运行环境差异。 |

## 推荐阅读顺序

第一次读，建议按这个顺序：

1. [Jugg 工作原理](./how-jugg-works.md)
2. [增量编译](./incremental-compile/)
3. [部署策略](./deploy-strategy.md)
4. [回退与限制](./fallback-and-limits.md)

排查具体问题时，可以直接跳到对应页面，再去[问题排查](../troubleshooting/)找现象入口。
