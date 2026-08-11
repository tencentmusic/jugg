---
title: Jugg 运行面板
description: 介绍 Jugg 运行面板中的实时状态、近期运行、结构化日志、快捷操作和设置入口。
status: active
tags:
  - guide
  - control-panel
  - logs
---

# Jugg 运行面板

Jugg 运行面板把一次运行的当前阶段、待处理文件、近期结果和常用恢复动作集中在一个项目级工具窗口中。它适合在 Run 输出过长、需要比较多次运行，或想快速判断当前工程是否具备增量基线时使用。

## 单次 Run 输出难以回答连续状态

Run tool window 更适合查看一轮任务的完整文本，但日常排查经常需要同时回答：

- 当前卡在变化检测、编译、部署还是启动。
- 哪些文件仍待处理。
- 最近几次运行走的是增量、Gradle、Hot Reload、Hot Fix 还是安装。
- 当前工程是否已经具备可用的增量基线。

运行面板使用结构化事件维护当前 IDE 会话的状态，因此切换 Run 标签页后仍能看到近期结果。

## 打开面板

有可运行 Jugg 配置的工程会在 Android Studio 右侧显示 \`Jugg Running Pannel\` 工具窗口。也可以从 \`Tools > Open Jugg Control Panel\` 打开。

Run Configuration 中需要进入设置的入口会直接切到面板的 Settings 页，不需要在两个窗口间重复查找。

## Overview

Overview 聚合以下信息：

| 区域 | 内容 |
|---|---|
| Run status | 当前任务阶段、阶段进度和耗时 |
| Changed files | 待处理文件、所属模块和文件类型；双击可打开文件 |
| Quick actions | Gradle 构建、清理 Jugg、重启 App、清数据重装、报告问题、检查更新、安装 CLI/Skill |
| This session | 当前会话成功编译、Hot Reload、Hot Fix、Install 次数 |
| Recent runs | 最近运行的编译方式、部署方式、耗时、失败原因和相关文件 |

面板显示 \`Hot reload baseline is ready\` 时，说明当前工程具备可继续增量的基线；显示 \`Full Gradle build required\` 时，应先完成一次完整构建。

## Logs

Logs 页展示 Jugg 的结构化核心事件，可以按来源、级别、当前任务和关键词过滤。

- 来源包括 Deploy、Runtime、CLI / MCP。
- 级别包括 Info、Warn、Error。
- \`Current task\` 只保留当前任务事件。
- \`Follow\` 自动跟随新事件。
- 选中事件后可复制，适合附加到问题报告。

结构化日志用于快速定位阶段，不替代完整日志。需要查看 Gradle 输出、异常栈或底层部署细节时，继续打开 \`build/jugg/log/compile_latest.log\`。

## Settings

Settings 页可以直接调整常用运行行为：

- 无文件变化时是否确认 Gradle 回退。
- 部署后是否始终重启 App。
- 是否启用 Quick deploy。
- 部署失败后是否自动回退 Gradle。
- 是否把变化嵌入 APK。
- 是否使用项目 Kotlin compiler。
- 是否备份 classpath。

还可以安装 CLI 和 Agent Skills、检查更新，或执行 \`Clear Jugg Build\` 重新初始化工程。

> [!WARNING]
> \`Clear Jugg Build\` 会删除项目级 Jugg 构建数据。它适合缓存明显损坏或基线无法恢复的场景，不是日常清理动作。

## 会话边界

近期运行、会话计数和结构化事件主要服务当前 Android Studio 会话。IDE 重启后不要把空的 Recent runs 误判为从未运行；持久化的编译上下文和部署历史仍由项目级缓存维护。

## 相关页面

- [运行 App](./run.md)
- [运行配置与构建变体](./run-configuration.md)
- [报告问题](./report-issue.md)
- [日志文件参考](../reference/log-files.md)
- [报告问题](./report-issue.md)
