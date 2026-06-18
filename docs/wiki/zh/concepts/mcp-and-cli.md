---
title: MCP 与 CLI
description: 说明 Agent、CLI 和 MCP 如何接入 Jugg 插件能力。
status: active
tags:
  - concept
  - mcp
  - cli
---

# MCP 与 CLI

MCP 和 CLI 是 Agent 或终端访问 Jugg 插件能力的工具入口。它们不重新实现编译、部署、测试或 UI 检查逻辑，而是把请求转给 IDE 插件内已经初始化的 Jugg runtime。

## 分层关系

```text
Agent / terminal
  -> jugg CLI
  -> local Jugg MCP endpoint
  -> registered MCP tool action
  -> Jugg compile / deploy / test / UI runtime
```

CLI 负责发现本机 MCP 端口、解析项目路径、封装命令参数和轮询长任务。直接使用 MCP 时，客户端需要自己处理 JSON-RPC 请求、schema 校验结果和异步任务收口。

## MCP 执行模型

MCP 只暴露已注册 tool action。代码里存在 action 类不等于公开工具，只有进入 `tools/list` 或注册表的 action 才能被客户端调用。

```text
MCP request
  -> schema 校验
  -> projectDir 初始化检查
  -> 查找已注册 action
  -> 调用 Jugg runtime
  -> 返回 structuredContent 和 artifacts
```

除 `version`、`list-projects` 外，公开工具通常需要 `projectDir`，并要求 IDE 侧 Jugg 已完成初始化。

## 长任务收口

编译、部署和 instrumentation 可能返回 `jobId`。客户端需要继续查询任务状态，直到得到编译、部署和日志维度的终态结果。

```text
deploy / gradle-build / instrument
  -> running + jobId
  -> get-compile-status(projectDir, jobId)
  -> terminal result + artifacts
```

CLI 已封装这轮轮询；MCP 客户端直接调用时需要自己实现。

## 能力边界

- MCP/CLI 只是工具入口，编译和部署事实仍由对应能力页描述。
- UI 工具依赖 App 内 ViewHierarchy 通道，不自动承诺未注册的批量 layout verify。
- 远端诊断和 Agent Skills 是工作流封装，不改变底层 Jugg 能力边界。

## 相关页面

- [Android Test 流程](./android-test-flow.md)
- [部署策略](./deploy-strategy.md)
- [CLI 指南](../guide/cli.md)
- [MCP 指南](../guide/mcp.md)
- [Jugg CLI 与 Agent Skills](../capabilities/tools/)
