---
title: Jugg 工作原理
description: 从一次 Run 的角度理解 Jugg 如何串联项目快照、增量编译、部署和状态提交。
status: active
tags:
  - concept
  - run
---

# Jugg 工作原理

Jugg 的核心目标是让 Android 开发中的“改一点、跑一下”更快。它会尽量复用最近一次 Gradle 构建产物，只处理本轮真正变化的内容，再把增量产物部署到设备。

这套机制依赖一个前提：项目已经有可信的完整构建基线。没有基线、基线过期或状态不可靠时，Jugg 会回到 Gradle。

## 一次 Run 的主流程

一次 Jugg Run 可以理解为四个阶段：

1. **准备上下文**：确认项目快照、运行配置、设备、APK、上次部署历史都可用。
2. **编译决策**：判断本轮能否增量；可以增量则进入 Jugg 编译，不适合则回退 Gradle。
3. **部署决策**：根据编译产物和设备状态选择 install、hot reload、hot fix、兼容部署或重启。
4. **状态提交**：只有部署成功后，才把本轮产物提交为新的部署历史。

其中最关键的是最后一步。Jugg 不会因为“编译成功”就直接推进部署历史；设备真正完成部署后，下一轮增量才会以这次结果为基线。

## Jugg 依赖哪些状态

Jugg 需要同时维护几类状态：

| 状态 | 用途 |
|---|---|
| 项目快照 | 记录模块、变体、源码目录、资源目录、依赖、APK 输出和 androidTest 信息。 |
| 文件变化 | 记录哪些源码、资源、Manifest、native lib 或构建文件还没有被编译/部署。 |
| staging 产物 | 暂存本轮编译出的 class、dex、资源 overlay、Manifest 或 APK 内更新文件。 |
| 部署历史 | 记录设备上已经部署过哪些产物，以及下轮影响分析需要的索引。 |
| 设备状态 | 判断当前设备是否适合增量部署，是否需要 recover、重装或回退。 |

这些状态相互约束。比如：即使源码能编译，如果设备上的 overlay 状态和历史不匹配，也可能先 recover 或重装；即使设备在线，如果项目目标从 App 切到 Android Test，也可能需要重新建立 Gradle 基线。

## 增量优先，但不是强制增量

Jugg 会优先尝试增量，但不会把所有变化都塞进增量路径。常见回退原因包括：

- 构建脚本或依赖变化需要重新读取 Gradle 信息。
- 文件变化规模超过增量收益。
- 上次完整构建失败，缺少可信产物。
- 运行目标切换，例如从普通 App Run 切到 Android Test。
- 设备状态无法证明当前部署历史仍然匹配。

这些回退不是失败，而是为了重新建立可信基线。

## 运行结果如何理解

用户通常会看到三类结果：

| 结果 | 含义 |
|---|---|
| `Gradle BUILD_AND_INSTALL SUCCESSFUL` | 本轮走 Gradle 构建并安装，通常用于建立或刷新基线。 |
| `Jugg HOT_RELOAD SUCCESSFUL` | 本轮变化可以在不重启 App 的情况下应用。 |
| `Jugg HOT_FIX SUCCESSFUL` / `COMPAT_HOT_FIX` | 本轮变化需要更保守的更新方式，通常会重启 App 或使用兼容部署。 |

如果提示没有文件变化，Jugg 可能会直接部署、重跑测试或回退 Gradle，具体取决于是否首次运行、是否 Debug、是否 Android Test，以及当前是否还有未提交的 staging 产物。

## 相关页面

- [增量编译](./incremental-compile.md)
- [部署策略](./deploy-strategy.md)
- [回退与限制](./fallback-and-limits.md)
- [项目模型](./project-model.md)
