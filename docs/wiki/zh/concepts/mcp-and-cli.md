---
title: MCP 与 CLI
description: 解释 Agent、CLI 和 MCP 为什么是工具入口而非另一套能力实现，长任务如何收口，以及哪些是当前公开工具。
status: active
tags:
  - concept
  - mcp
  - cli
---

# MCP 与 CLI

MCP 和 CLI 是 Agent 或终端访问 Jugg 能力的工具入口。它们不重新实现编译、部署、测试或 UI 检查，而是把请求转给 IDE 插件里已经初始化好的 Jugg runtime。

## 自动化需要一个稳定入口，而不是另一套实现

Agent 与脚本要调用 Jugg，最容易踩的坑是“在 IDE 之外重造一套编译部署逻辑”。这会立刻和 IDE 内的真实状态分叉：项目快照、设备状态、部署历史都在 IDE 侧，外部重实现既无法共享这些状态，也会随 Jugg 演进不断失配。自动化调用需要一个稳定、可发现、可校验的边界，而不是直接触碰内部实现细节。

## 统一入口转发到同一个 runtime

MCP 和 CLI 把请求统一转发给 IDE 内已经初始化的 Jugg runtime，自己只负责入口职责：

```text
Agent / 终端
  -> jugg CLI
  -> 本机 Jugg MCP 端点
  -> 已注册的 MCP 工具
  -> Jugg 编译 / 部署 / 测试 / UI runtime
```

CLI 负责发现本机 MCP 端口、解析项目路径、封装命令参数、轮询长任务。直接使用 MCP 时，客户端需要自己处理 JSON-RPC 请求、schema 校验和异步任务收口。

### 只有公开注册的工具才能被调用

MCP 只暴露已注册的工具。代码里存在某个工具实现，不等于它是公开能力；只有进入工具清单的工具才能被客户端调用：

```text
MCP 请求
  -> schema 校验
  -> projectDir 初始化检查
  -> 查找已注册工具
  -> 调用 Jugg runtime
  -> 返回结构化结果与产物
```

除 `version`、`list-projects` 这类工具外，公开工具通常需要传 `projectDir`，并要求 IDE 侧 Jugg 已完成初始化。

### 长任务收口

编译、部署和 instrumentation 可能返回 `jobId`，表示任务在后台运行。客户端需要继续按 `jobId` 查询任务状态，直到拿到编译、部署和日志维度的终态：

```text
deploy / gradle-build / instrument
  -> running + jobId
  -> 按 projectDir 与 jobId 轮询状态
  -> 终态结果与产物
```

CLI 已封装这轮轮询；MCP 客户端直接调用时需要自己实现。

## 能力边界

- MCP/CLI 只是工具入口，编译和部署的真实行为仍以对应能力页为准。
- UI 工具依赖 App 内的视图树通道，不会自动承诺未注册的批量布局校验，详见 [布局 dump 与 UI 证据](./layout-dump-and-ui-evidence.md)。
- 远端诊断和 Agent Skills 是工作流封装，不改变底层 Jugg 的能力边界。

## 相关页面

- [CLI 指南](../guide/cli.md)
- [MCP 指南](../guide/mcp.md)
- [Jugg CLI 与 Agent Skills](../capabilities/tools/)
- [Jugg CLI 能力](../capabilities/tools/cli.md)
- [面向 Agent 的 MCP](../capabilities/tools/mcp.md)
