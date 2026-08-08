---
title: Jugg 能力概览
description: 按编译、部署、测试和工具入口汇总 Jugg 当前支持范围，用于判断应查看哪类能力页。
status: active
tags:
  - capability
  - overview
---

# Jugg 能力概览

Jugg 的能力页用于回答“这类修改或操作是否支持、会通过什么链路生效、需要什么前置条件”。如果你不确定自己的场景属于哪一类，先从本页进入对应分组，再查看具体能力页。

## 能力分组

| 分组 | 适合查看 | 典型入口 |
|---|---|---|
| [编译能力](./compile/) | 判断源码、资源、Manifest、DataBinding、`.so`、Release、常量引用等修改如何被增量处理 | 源码编译、资源编译、Gradle 回退 |
| [部署能力](./deploy/) | 判断本轮产物会安装、Code Swap、Full Swap、Hot Reload、Restart，还是先恢复部署状态 | Clean Reinstall、Direct Overlay、多 APK、多设备 |
| [测试能力](./test/) | 判断 app / library 的 Android Test 如何运行、结果如何展示、logcat 如何归因 | Application Android Test、Library Android Test、Test Results UI |
| [Jugg CLI 与 Agent Skills](./tools/) | 判断 Agent 或终端如何调用 Jugg 编译、部署、测试、UI 检查和远端诊断能力 | CLI、MCP、UI 自动化、Agent Skills |

## 按用户任务选择入口

| 你想做的事 | 优先阅读 |
|---|---|
| 修改 Java / Kotlin / class 后想知道能否增量编译 | [源码编译](./compile/source-compile.md) |
| 修改资源、layout、assets 或 `R` 相关内容 | [资源编译](./compile/resource-compile.md) |
| 修改 `AndroidManifest.xml` | [AndroidManifest 编译](./compile/manifest.md) |
| 更新已产出的 native `.so` | [so 更新](./compile/so-update.md) |
| 判断为什么需要重新编译调用方、子类或常量引用方 | [重编译/扩散编译](./compile/recompile-propagation.md)、[常量引用分析](./compile/const-ref.md) |
| 判断本轮部署是否能不重启 App | [Code Swap](./deploy/code-swap.md)、[Hot Reload](./deploy/hot-reload.md) |
| 需要清数据重装或重新建立基线 | [Clean Reinstall](./deploy/clean-reinstall.md) |
| 部署失败后想知道是否会自动恢复或重试 | [Recover 与 Retry](./deploy/recover-and-retry.md) |
| 运行 Android instrumentation test | [测试能力](./test/)、[Android Test CLI](./tools/cli-android-test.md) |
| 让 Agent 通过命令行编译、部署或验证 | [Agent Skills](./tools/agent-skills.md)、[Jugg CLI](./tools/cli.md) |
| 检查当前 App UI、定位元素或执行点击 | [UI 自动化](./tools/ui-automation.md)、[UI 布局证据](./tools/layout-verify.md) |

## 核心链路

```text
代码或资源变化
  -> 编译能力判断能否增量处理，必要时回退 Gradle
  -> 部署能力把产物应用到目标设备
  -> 测试能力运行 Android Test 或展示结果
  -> 工具能力让 Agent / CLI / MCP 驱动和验证整个过程
```

这些能力共享同一个 Jugg 项目基线。编译是否可信、部署历史是否一致、设备是否可用、Android Test baseline 是否建立，都会影响后续能力能否直接执行。

## 前置条件与边界

- Jugg 增量能力建立在最近一次可信 Gradle 构建基线之上；不替代完整 Gradle pipeline。
- 涉及 Gradle 脚本、依赖、变体、source set 或复杂插件配置时，可能需要先走 [Gradle 回退](./compile/gradle-fallback.md)。
- 设备侧能力需要目标设备可用，并且部署历史、APK 归属和 overlay checkpoint 一致。
- Android Test 需要先开启 Android Test 目标并完成一次对应 full build baseline。
- Agent / CLI / MCP 能力是访问 Jugg 的工具入口，具体编译、部署、测试事实仍由对应能力页说明。

## 继续阅读

- [使用指南](../guide/)
- [实现原理](../concepts/)
- [Jugg 工作原理](../concepts/how-jugg-works.md)
- [问题排查](../troubleshooting/)
- [参考](../reference/)
