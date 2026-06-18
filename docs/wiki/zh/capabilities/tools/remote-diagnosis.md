---
title: 远端诊断
description: 说明何时通过 SSH 诊断远端构建环境或设备相关问题。
status: active
tags:
  - capability
  - tools
  - diagnosis
---

# 远端诊断

远端诊断用于在本地日志和 Jugg CLI 结果不足以定位问题时，向用户申请 SSH 排障信息。它不是默认调试入口，只有在需要访问远端构建环境或设备相关上下文时才使用。

## 适用场景

| 场景 | 当前支持情况 | 生效方式 |
|---|---|---|
| 远端 Gradle 构建失败，CLI 日志仍不足以判断根因 | 支持 | `ssh-info` 申请连接信息 |
| 多次 `compile` / `deploy` 修复重试后仍失败，并且 `gradle-build` 也无法给出足够信息 | 支持 | 说明原因后申请 SSH |
| 普通本地编译错误 | 不作为首选 | 先读 `detail`、`compile_latest.log` 和状态字段 |
| 未经用户同意获取远端信息 | 不支持 | `ssh-info` 需要用户显式同意 |

## 命令格式

```text
jugg ssh-info --reason "deploy fails after retries and gradle-build detail is insufficient"
```

`reason` 必须说明为什么需要远端信息。调用方应先完成本地可见证据收集，例如命令返回的 `detail`、日志路径、`status`、设备状态和失败阶段。

## Agent 升级流程

```text
compile / deploy 失败
  -> 读取 detail 和完整日志
  -> 修正可判断的问题并重试
  -> 仍失败时尝试 gradle-build
  -> 远端构建或环境信息不足
  -> 征得用户同意后调用 ssh-info
```

远端诊断的目标是补齐本地看不到的环境证据，而不是绕过已有的 Jugg 编译、部署和日志判断。

## 输出如何使用

`ssh-info` 返回的信息用于后续人工或 Agent 连接远端环境继续排查。它不代表问题已经被修复，也不替代构建命令的终态判断。

## 关联能力

- [构建与部署](./cli-build-deploy.md)
- [运行时与设备](./cli-runtime-device.md)
- [Jugg CLI](./cli.md)
