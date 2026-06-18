---
title: MCP
description: 介绍 Jugg MCP 的连接方式、公开工具、返回模型，以及与 CLI 的选择建议。
status: active
tags:
  - guide
  - mcp
---

# MCP

Jugg MCP 是插件暴露给 Agent 的本地 JSON-RPC 服务。它与 CLI 使用同一组底层能力；CLI 是对 MCP 的命令行封装，通常更适合 Agent 日常使用。

> [!TIP]
> 如果你只是想让 Agent 编译、部署、跑测试或操作设备，优先安装 Jugg CLI Skill。只有当你的客户端必须直接配置 MCP server 时，再使用本页。

## 服务信息

| 项 | 值 |
|---|---|
| 端口范围 | `12320..12329` |
| 路径 | `/jugg-mcp` |
| 协议 | HTTP + JSON-RPC `2.0` |
| 全局工具 | `version`、`list-projects` |
| 非全局工具 | 都需要 `projectDir` |

同时打开多个 Android Studio 时，端口会在范围内递增。客户端应先发现端口或使用 CLI 的端口发现能力。

## 调用模型

MCP 工具返回的业务结果位于 `structuredContent`：

```json
{
  "status": "OK",
  "message": "...",
  "data": {},
  "artifacts": [],
  "errorCode": null
}
```

协议成功不等于业务成功。客户端必须读取 `structuredContent.status`，并在编译部署类工具中继续读取 `isCompileSuccess`、`isDeploySuccess` 等字段。

## 公开工具

| 类型 | 工具 |
|---|---|
| 项目和版本 | `version`、`list-projects` |
| 构建部署 | `compile`、`deploy`、`gradle-build`、`clean-reinstall`、`get-compile-status` |
| 测试 | `instrument` |
| 运行态 | `restart`、`wait-logs`、`activity-stack` |
| 设备 | `devices` |
| UI | `layout-dump`、`view-locate`、`view-inspect`、`tap` |
| 远端诊断 | `ssh-info` |
| 状态 | `status` |

`layout-verify` 和 `figma-layout-verify` 不是当前公开工具，除非后续注册到公开工具清单。

## 异步编译

`compile`、`deploy`、`gradle-build`、`instrument` 可能返回 running 状态：

```text
compile/deploy/gradle-build/instrument
  -> 返回 jobId
  -> 调用 get-compile-status(projectDir, jobId)
  -> 直到 success / failed / canceled
```

CLI 已经内置轮询。如果直接使用 MCP，需要自己轮询 `get-compile-status`，也可以传 `waitTimeoutMs` 减少空轮询。

## 为什么通常更推荐 CLI

相对直接配置 MCP，CLI 的优势是：

- CLI 与 skill 打包在一起，Agent 更容易获得正确使用说明。
- CLI 能组合命令、脚本和管道，更适合终端与 Agent 的连续操作。
- CLI 内置异步轮询和 heartbeat，减少 Agent 自己轮询消耗。
- CLI 输出模式可选，适合人工、Agent 和脚本。
- 同时暴露 CLI 和 MCP 时，应在 Agent 说明中固定首选入口，避免同一任务混用两套调用方式。

## 什么时候直接使用 MCP

MCP 适合：

- 你的平台只能配置 MCP server。
- 需要统一从 MCP client 管理所有工具。
- 需要直接读取 MCP `tools/list` schema。

其它场景通常优先使用 CLI：

- 普通终端使用。
- 让 Agent 在代码修改后做编译验证。
- 长耗时任务频繁轮询但客户端没有良好等待机制。

## 相关页面

- [CLI](./cli.md)
- [MCP 与 CLI](../concepts/mcp-and-cli.md)
- [面向 Agent 的 MCP](../capabilities/tools/mcp.md)
- [MCP 工具](../reference/mcp-tools.md)
- [MCP 与 CLI 问题排查](../troubleshooting/mcp-cli.md)
