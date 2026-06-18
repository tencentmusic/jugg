---
title: 运行时与设备 CLI
description: 说明 Jugg CLI 如何读取状态、设备、Activity 栈和 App 日志。
status: active
tags:
  - capability
  - tools
  - cli
  - runtime
---

# 运行时与设备

运行时与设备命令用于在编译部署之外读取当前 Jugg 状态、设备列表、Activity 栈和 App 日志窗口。它们常用于 Agent 判断下一步应编译、部署、等待日志还是先处理设备问题。

## 可完成的任务

| 用户任务 | 当前支持情况 | 命令 |
|---|---|---|
| 查看部署状态、未编译文件摘要和 AndroidTest baseline | 支持 | `jugg status` |
| 列出已连接设备并标记 selected | 支持 | `jugg devices` |
| 读取当前 Activity 栈 | 支持 | `jugg activity-stack` |
| 等待日志 marker、crash 或 timeout | 支持 | `jugg wait-logs` |

## 状态查询

```text
jugg status
jugg status --refresh-changes true
```

`status` 默认不刷新 changed files。需要让 Jugg 重新读取 git-tracked changed files 时传 `--refresh-changes true`。

关键字段：

| 字段 | 用途 |
|---|---|
| `hasDevice` | 判断是否有可用设备 |
| `needFallback` | 判断当前是否需要 Gradle 全量构建 |
| `pendingModifiedFiles` / `files` | 查看未编译文件摘要 |
| `lastCompileTime` | 判断当前修改是否已经被 Jugg 编译覆盖 |
| `hasBeenFullCompiled` | 判断是否存在完整 Jugg 基线 |
| `enabledAndroidTest` | 判断是否能走 `instrument` |
| `isCompiling` | 判断是否已有 compile/deploy 任务运行中 |

## 设备与 Activity

```text
jugg devices
jugg activity-stack
```

`devices` 用于确认当前 IDE 侧可见设备和 selected 设备。`activity-stack` 用于确认目标 App 当前是否在预期页面，通常在 UI 检查或触控前使用。

## 日志等待

```text
jugg wait-logs --marker '\[JUGG_AR\] DONE'
jugg wait-logs --marker '\[JUGG_AR\] DONE' --tags MyAutoRun,AndroidRuntime --timeout-ms 30000
```

`wait-logs` 会从最近一次 `deploy` 或 `restart` 记录的时间点开始读取目标 App 日志，直到：

| `stopReason` | 含义 | 下一步 |
|---|---|---|
| `marker` | 命中预期 marker | 读取返回的日志窗口判断验证结果 |
| `crash` | 检测到 crash | 按失败处理并查看 crash 日志 |
| `timeout` | 超时未命中 marker | 结果不确定，结合 UI 或完整日志继续判断 |

## 关联能力

- [Jugg CLI](./cli.md)
- [UI 自动化](./ui-automation.md)
- [Android Test](./cli-android-test.md)
