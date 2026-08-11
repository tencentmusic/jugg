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

## Q：为什么本轮突然变成完整 Gradle 构建？

从 Run 输出或 Jugg Running Panel 确认本轮显示的是 Gradle 构建，而不是增量编译。

常见情况及处理方式：

- 没有文件变化但误点 Run：取消本轮 Gradle 构建即可。
- 修改了构建文件、依赖、variant 或 source set：等待 Gradle 完成并更新基线。
- 切换 App 与 Android Test 目标：需要新的 APK 基线，等待本轮完成。
- 修改文件或模块过多：Jugg 会选择完整 Gradle 构建，避免增量成本更高。
- 上一次增量编译失败：本轮 Gradle 用于恢复可信基线。

如果每次小改动都进入 Gradle，先 Sync，再完成一次完整 Gradle 构建，然后用一个小范围修改重新验证。

## Q：Sync 期间 Jugg 一直不能运行怎么办？

等待当前 Gradle Sync 完成。Sync 会更新工程结构、依赖和生成源码路径，Jugg 不应在这些信息仍在变化时开始增量编译。

Sync 已经失败时，先修复 Sync 错误；不要反复点击 Jugg Run 绕过失败的工程模型。

## Q：长时间停在等待 App 启动怎么办？

1. 确认 App 已经在设备上启动，并且没有立即崩溃。
2. 确认安装包是 debuggable。
3. 关闭其他使用相同设备的 Android Studio 或 ADB 工具。
4. 重启 ADB 后重新选择设备。
5. 仍然无法启动时，执行 Clean Reinstall。

## Q：依赖库变化分析耗时较长怎么办？

`Find out changed Libraries` 会运行 Gradle 读取依赖差异，适合只升级或回退依赖库的场景。

- 只改了依赖版本，并希望减少后续构建范围：等待差异分析并核对结果。
- 修改了插件、variant、source set 或多个构建配置：直接选择 `Fallback to Gradle`。
- 不确定 build 文件是否影响当前 APK：选择 Gradle，不要忽略变化。

## Q：Android Studio 持续高 CPU、界面无法操作怎么办？

1. 先确认是否仍有 Gradle Sync、Gradle 构建或 Jugg 任务正在执行。
2. 不再需要当前任务时，使用对应的取消按钮停止任务。
3. 任务已经结束但占用持续不降时，重启 Android Studio。
4. 重启后空闲状态仍能稳定复现时，使用[报告问题](../guide/report-issue.md)，并说明高占用发生前执行的操作。

## 相关页面

- [Jugg 运行面板](../guide/control-panel.md)
- [降级 Gradle 编译](../guide/downgrade-gradle.md)
- [依赖库增量编译](../capabilities/compile/dependency-incremental.md)
- [无法安装、启动或进入 Debug](./app-cannot-run.md)
