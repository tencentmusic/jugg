---
title: Jugg CLI
description: 说明 Agent 和终端用户如何通过 Jugg CLI 访问插件能力。
status: active
tags:
  - capability
  - tools
  - cli
---

# Jugg CLI

Jugg CLI 是 Agent 和终端用户访问 Jugg 插件能力的命令行入口。它把 MCP 工具封装成稳定子命令，负责项目解析、端口发现、异步编译轮询和面向人或脚本的输出格式。

本页用于判断 CLI 能完成哪些任务、什么时候适合使用。精确命令、参数和别名不在这里展开，见 [CLI 命令参考](../../reference/cli-commands.md)。

## 可完成的任务

| 用户任务 | 当前支持情况 | 输入输出边界 |
|---|---|---|
| 编译、部署、重装、重启 | 支持 | 使用 `compile`、`deploy`、`gradle-build`、`clean-reinstall`、`restart` |
| 运行 androidTest | 支持 | 使用 `instrument`，必须提供 `--source-path` |
| 查看运行状态和设备信息 | 支持 | 使用 `status`、`devices`、`activity-stack`、`wait-logs` |
| UI 检查和交互 | 支持 | 使用 `layout-dump`、`view-locate`、`view-inspect`、`tap` |
| 申请远端诊断信息 | 支持 | 使用 `ssh-info`，需要用户显式同意 |

## 调用入口

```text
python3 {SKILL_DIR}/scripts/jugg.py [全局参数] <subcommand> [子命令参数]
python3 {SKILL_DIR}/scripts/jugg.py help <subcommand>
```

> [!TIP]
> Agent 场景优先使用 `plain` 或 `json`。`rich` 会刷新终端行，不适合作为模型上下文里的稳定输出。

## CLI 如何找到插件

CLI 会先读取端口缓存；未命中时扫描 Jugg MCP 端口范围 `12320..12329`。项目目录默认由当前工作目录和 IDE 已初始化项目做最长前缀匹配；传入 `--project-dir` 时直接使用该路径。

```text
CLI
  -> 发现本地 MCP 端口
  -> 解析 projectDir
  -> 调用对应 MCP tool
  -> 必要时轮询 get-compile-status
  -> 输出 plain / rich / json 结果
```

## 子命令分组

- **[构建与部署](./cli-build-deploy.md)**：`compile`、`deploy`、`gradle-build`、`clean-reinstall`、`restart`
- **[Android Test](./cli-android-test.md)**：`instrument`
- **[运行时与设备](./cli-runtime-device.md)**：`status`、`devices`、`activity-stack`、`wait-logs`
- **[UI 自动化](./ui-automation.md)**：`layout-dump`、`view-locate`、`view-inspect`、`tap`
- **[远端诊断](./remote-diagnosis.md)**：`ssh-info`

精确参数和输出字段见 [CLI 命令参考](../../reference/cli-commands.md) 与 [MCP 工具参考](../../reference/mcp-tools.md)。

## 相关页面

- [CLI 指南](../../guide/cli.md)
- [MCP 与 CLI 原理](../../concepts/mcp-and-cli.md)
