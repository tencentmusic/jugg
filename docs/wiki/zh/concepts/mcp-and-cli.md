---
title: MCP 与 CLI
description: 说明指定原文对 MCP 与 CLI 的覆盖范围，以及与 Jugg 核心能力的关系。
status: active
tags:
  - concept
  - mcp
  - cli
---

# MCP 与 CLI

指定原文主要介绍 Jugg 的 IDE 插件方案、增量编译、资源编译、部署和产品化能力，没有展开 MCP 服务或 `jugg` CLI 的设计。因此本页只保留范围说明，不补充原文之外的协议和工具细节。

## 与原文的关系

原文中和命令行相关的内容主要有两点：

- Gradle 编译模块可以通过本地命令执行 Gradle。
- 云开发机编译通过 SSH 执行命令，并同步源码和产物。

这些内容说明 Jugg 的编译能力可以被不同运行环境调用，但不足以推出 MCP 工具列表、JSON-RPC 参数、CLI 子命令或 UI 操作能力。

## 不在本次原文范围内的内容

以下内容不应从指定原文推导：

- MCP server 的协议结构。
- `jugg` CLI 的子命令和参数映射。
- Agent 调用编译、部署、Android Test 或 UI 工具的行为。
- 工具返回结构、异步任务轮询和 artifact 产物。

如果需要维护这些内容，应另行读取当前 MCP / CLI 实现和对应知识库文档。

## 相关页面

- [Android Test 流程](./android-test-flow.md)
- [部署策略](./deploy-strategy.md)
- [CLI 指南](../guide/cli.md)
- [MCP 指南](../guide/mcp.md)
