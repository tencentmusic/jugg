---
title: 日志文件
description: 汇总 Jugg 日志路径、格式、常用检索词和 MCP artifact 位置。
status: active
tags:
  - reference
  - logs
---

# 日志文件

本页用于在排查或反馈问题时快速定位日志和 artifact。它只列路径、格式和关键词；具体问题的判断步骤见 [问题排查](../troubleshooting/)。

Jugg 的主日志位于项目目录下的 `build/jugg/log/`。排查编译、部署、Debug、MCP 和运行时问题时，通常先看 `compile_latest.log`。

## 日志路径

| 路径 | 说明 |
|---|---|
| `build/jugg/log/compile_latest.log` | 当前主日志的 best-effort 快捷入口。 |
| `build/jugg/log/compile_latest-1.log` | 上一份主日志的 best-effort 快捷入口。 |
| `build/jugg/log/compile_YYYY-MM-DD_HH-mm-ss.0.log` | 实际滚动日志文件。 |
| `build/jugg/mcp_fetch/<toolName>/` | MCP 拉取类工具生成的 artifact。 |
| `build/jugg/tmp/diff/` | 远端编译 diff 结果。 |
| Android Studio `idea.log` | Android Studio 自身日志，用于 Debug attach、IDE freeze、插件加载等问题。 |

`compile_latest.log` 是快捷入口，不是唯一真实文件。如果文件看起来没有更新，同时检查滚动日志和 `compile_latest-1.log`。

## 日志格式

```text
[2026-03-16 16:13:27.109] [INFO   ] [ClassName] message
```

| 字段 | 说明 |
|---|---|
| 时间戳 | 精确到毫秒。 |
| 级别 | `FINE`、`INFO`、`WARNING`、`SEVERE` 等运行时级别。 |
| ClassName | 日志来源标签，通常可作为源码定位入口。 |
| message | 具体事件、耗时、错误或状态说明。 |

## 常用检索词

| 目标 | 关键词 |
|---|---|
| 编译开始 | `Jugg compile started` |
| 增量/全量判断 | `preprocessIncrementalCompile` |
| 无文件变化回退 | `No file changes`、`confirmFallbackWhenNoFileChanges` |
| 回退原因 | `fallback`、`Fallback` |
| 编译失败 | `incremental compile error`、`SEVERE` |
| APK DB 初始化 | `initAfterInstall parsed apk start`、`database all init finish` |
| 部署开始 | `deploy start` |
| 部署恢复 / 重试 | `recover`、`retry` |
| Debug attach | `Jugg Debug attach:`、`waitForClientReadyForDebug`、`Connected to the target VM` |
| Const-ref | `ConstRefEngine`、`full scan progress` |
| UI freeze | `uiFreezeStarted`、`InvocationEvent has timed out` |
| MCP 日志等待 | `wait-logs`、`marker`、`crash` |

## MCP 日志结果

`wait-logs` 会等待日志 marker、crash 或 timeout，并返回：

| 字段 | 说明 |
|---|---|
| `stopReason` | `marker`、`crash` 或 `timeout`。 |
| `startTime` / `endTime` | logcat threadtime 格式时间。 |
| `targetPids` | 停止时枚举到的目标进程 PID。 |
| `logs` | 过滤后的日志窗口，最多 100 行。 |
| `allLogsPath` | 全量原始日志落盘路径。 |
| `truncated` | 返回日志是否被截断。 |

`layout-dump`、`activity-stack` 等工具返回文件路径时，会放在 `artifacts` 或 `data` 中，路径位于 `build/jugg/mcp_fetch/`。

## 排查建议

1. 先定位用户操作或错误发生的准确时间。
2. 在 `compile_latest.log` 中向前后扩展阅读同一时间窗。
3. 用 `[ClassName]` 标签定位相关模块。
4. Debug attach、IDE freeze、插件启动问题需要同时看 Android Studio `idea.log`。
5. 远端编译问题需要同时看 full log 和 `build/jugg/tmp/diff/`。

## 相关页面

- [报告问题](../guide/report-issue.md)
- [编译失败](../troubleshooting/compile-failed.md)
- [无法安装、启动或进入 Debug](../troubleshooting/app-cannot-run.md)
- [MCP 工具](./mcp-tools.md)
