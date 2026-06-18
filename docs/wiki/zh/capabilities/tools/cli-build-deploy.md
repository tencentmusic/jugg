---
title: 构建与部署 CLI
description: 说明 Jugg CLI 如何触发编译、部署、Gradle 回退、重装和重启。
status: active
tags:
  - capability
  - tools
  - cli
---

# 构建与部署

构建与部署命令用于让 Agent 或终端用户在不离开命令行的情况下触发 Jugg 编译、部署、Gradle 回退、清数据重装和 App 重启。

## 操作场景

| 操作场景 | 当前支持情况 | 命令 |
|---|---|---|
| 只验证代码能否通过 Jugg 编译 | 支持 | `jugg compile` |
| 编译并部署到当前目标设备 | 支持 | `jugg deploy` |
| 强制走 Gradle 构建并进入后续安装 / 启动链路 | 支持 | `jugg gradle-build` |
| 清除 App 数据并重装 APK | 支持 | `jugg clean-reinstall` |
| 重启目标 App | 支持 | `jugg restart` |

## 命令边界

```text
jugg compile
jugg deploy [--always-restart-app <true|false>]
jugg gradle-build
jugg clean-reinstall
jugg restart
```

`compile` 只做编译，不部署。`deploy` 会编译并部署；`--always-restart-app=false` 允许在满足条件时保留运行态进行 hot reload。`gradle-build` 用于显式回退完整 Gradle 构建，并继续进入安装 / 启动链路。

> [!NOTE]
> CLI 当前不暴露 MCP 的 `waitAppReadyAfterSuccess` 参数。命令完成表示编译/部署任务到达终态，不代表额外等待了 App ready。

## 成功如何判断

构建类命令会阻塞到终态，Agent 不需要手工轮询。结果里需要区分编译和部署两个维度：

- **`isCompileSuccess=true`**：编译阶段成功。
- **`isDeploySuccess=true`**：部署或安装启动阶段成功。
- **`detail`**：失败时的诊断摘要，Gradle 长日志会保留头尾预览。
- **`full log` / `logPath`**：完整日志位置。

`gradle-build` 可能出现编译成功但部署失败，例如设备不可用或启动失败；此时不能只看 `isCompileSuccess`。

## 回退与重试

推荐顺序：

```text
compile / deploy 失败
  -> 读取 detail 和日志
  -> 修正代码后重试原命令
  -> 多次失败仍无法恢复时使用 gradle-build
  -> 远端构建仍失败且需要环境信息时申请 ssh-info
```

`clean-reinstall` 只用于确实需要清 App 数据的场景，不是普通部署失败的默认修复动作。

## 关联能力

- [Jugg CLI](./cli.md)
- [远端诊断](./remote-diagnosis.md)
- [Gradle 回退](../compile/gradle-fallback.md)
- [Clean Reinstall](../deploy/clean-reinstall.md)
- [Restart](../deploy/restart.md)
