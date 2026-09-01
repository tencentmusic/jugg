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
