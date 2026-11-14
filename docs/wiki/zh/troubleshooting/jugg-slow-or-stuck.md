---
title: Jugg 运行缓慢或卡住
description: 处理意外 Gradle 构建、等待启动、Sync 阻塞、依赖分析耗时和 Android Studio 高占用。
status: active
tags:
  - troubleshooting
  - performance
---

# Jugg 运行缓慢或卡住

先确认耗时发生在 Jugg 增量编译、完整 Gradle 构建、项目 Sync，还是等待设备和 App。不同阶段需要的恢复动作不同。


## Q：本轮突然变成完整 Gradle 构建

先确认从 Run 的头部输出或 Jugg Running Panel 确认本轮是 Gradle 构建，而不是增量编译。

常见情况及处理方式：

- 没有文件变化但误点 Run：取消本轮 Gradle 构建即可。
- 切换 App 与 Android Test 目标：符合预期，需要新的 APK 基线，等待本轮完成。
- 修改文件或模块过多：会弹出确认框，默认走完整 Gradle；倒计时结束后也可选择本轮继续增量。
- 上一次增量编译失败：符合预期，本轮 Gradle 用于恢复可信基线。

如果不满足上述情况，请 [报告问题](../guide/report-issue.md)。

## Q: 提示 “Waiting Jugg initializing finish...”，没有执行编译

一般存在两种情况：
1. 连续运行了两次，此时需要先等待上一次中断或结束。
2. 如果卡住超过 30s，可以关闭重新打开工程重置状态。

如果仍无法恢复，请 [报告问题](../guide/report-issue.md)。

## Q：Jugg 会占用很多内存吗？

以 JOOX 约 300 万行代码工程为例，两次堆快照的实测结果为：

| 测量对象 | 保留内存 |
| --- | ---: |
| Jugg 自有对象及其保留的数据 | 31–36 MiB |
| Kotlin 编译器隔离环境 | 9–11 MiB |

测试环境：2026-10-15，M3 Pro（12 核、36 GiB）、macOS 26.6.2、Android Studio 2025.3.4 Patch 1、JBR 21.0.11、Jugg 3.5.4-release，同时打开 3 个工程。以下 CPU 数据来自同一环境。

本地完整 Gradle 构建可能同时存在多个构建进程，加重内存压力；远程构建不会因此在本机新增 Gradle daemon。

## Q：没有编译时，Jugg 还会持续占用 CPU 吗？

本次无用户操作的 90 秒采样中，2,438 个 Java 栈样本里有 **0 个包含 Jugg 业务代码**。

分别给一个 Java、Kotlin、资源文件增加空行并恢复，每类执行 4 轮增量编译部署，全部成功。常规热部署结果如下：

| 修改类型 | 编译部署耗时 | IDE CPU 时间 | 独立资源进程 CPU 时间 |
| --- | ---: | ---: | ---: |
| Java（3 轮） | 2.85–3.24 秒 | 4.36–8.54 CPU 秒 | 0 |
| Kotlin（4 轮） | 2.96–4.54 秒 | 6.58–10.78 CPU 秒 | 0 |
| 资源（3 轮） | 2.21–3.15 秒 | 4.26–12.43 CPU 秒 | 0.07–0.10 CPU 秒 |

CPU 秒是各核工作时间之和；IDE CPU 时间包含 IDE 后台任务，独立资源进程 CPU 时间统计 `aapt2-inclink`。

如果后台任务结束后仍持续高 CPU，或内存持续增长、出现内存不足，请 [报告问题](../guide/report-issue.md)，附上异常时间、版本及同一时间段的 `idea.log` 和 Jugg 日志。
