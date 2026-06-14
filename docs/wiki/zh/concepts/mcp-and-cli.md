---
title: MCP 与 CLI
description: 解释 Agent 和命令行如何通过 Jugg 的本地工具协议执行编译、部署、测试和 UI 操作。
status: active
tags:
  - concept
  - mcp
  - cli
---

# MCP 与 CLI

Jugg 提供本地 MCP 服务和 `jugg` CLI，让 Agent 或脚本可以调用编译、部署、Android Test、设备查询、UI 层级导出等能力。CLI 本质上是 MCP 的命令行封装。

## 两层入口

| 入口 | 面向对象 | 特点 |
|---|---|---|
| MCP | Agent、工具客户端 | 使用本地 JSON-RPC，返回结构化结果。 |
| CLI | 人工终端、脚本、Agent skill | 负责端口发现、项目匹配、参数转换和异步轮询。 |

两者最终调用的是同一套 Jugg 能力。因此编译、部署、测试结果的含义与 IDE Run 保持一致。

## projectDir 为什么重要

大多数工具都必须指定项目目录。Jugg 会用它确认请求属于当前已初始化项目，避免 Agent 在错误项目上执行部署或测试。

CLI 可以根据当前工作目录和已打开项目自动匹配 `projectDir`；MCP 客户端则需要显式传入。只有少数全局工具不需要项目目录，例如版本查询和项目列表。

## 异步任务如何理解

编译、部署、Gradle 构建和 Android Test 可能耗时较长。MCP 可能先返回一个运行中的任务，再通过状态查询收口。

CLI 会自动轮询这些任务，并在终态输出结果。判断是否成功时，需要同时看：

- 编译是否成功。
- 部署是否成功。
- Android Test 是否有失败。
- 业务结果状态，而不仅是 HTTP 或 JSON-RPC 是否成功。

> [!IMPORTANT]
> MCP 协议成功只表示工具调用被正确处理，不代表业务一定成功。业务失败会体现在结构化结果里。

## CLI 与 MCP 的参数关系

CLI 参数尽量机械映射到 MCP 参数：

- kebab-case 会转换为 camelCase。
- 省略的参数通常不发送，让 MCP 使用默认值。
- CLI-only 行为留在 CLI 层，例如触发前等待已有编译任务。
- 子命令不会发明与 MCP 不一致的新语义。

这让 Agent、脚本和人工终端看到的结果更容易对齐。

## 工具能力边界

Jugg 工具主要覆盖：

- 编译、部署、强制 Gradle 构建和清数据重装。
- Android Test class/method 级运行。
- 设备、Activity 栈和运行状态查询。
- UI 层级导出、元素定位、属性检查和点击。
- App 日志等待和远端排障信息申请。

公开工具以运行时返回的工具列表为准。没有注册到公开列表的内部实现，不应当被当作稳定能力使用。

## 日志和产物

编译部署类任务的主日志会落在项目的 Jugg 日志目录。UI 和排查类工具可能生成可下载或可打开的 artifact，例如 HTML 层级文件。

CLI 通常会把关键日志路径打印出来；Agent 应保留这些路径，方便后续排查。

## 相关页面

- [Android Test 流程](./android-test-flow.md)
- [部署策略](./deploy-strategy.md)
- [CLI 指南](../guide/cli.md)
- [MCP 指南](../guide/mcp.md)
