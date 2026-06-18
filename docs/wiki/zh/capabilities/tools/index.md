---
title: Jugg CLI 与 Agent Skills
description: 汇总 Agent Skills、Jugg CLI、MCP 和 UI 自动化相关工具能力。
status: active
tags:
  - capability
  - tools
  - overview
---

# Jugg CLI 与 Agent Skills

本节介绍 Agent 在 Android 项目中驱动 Jugg 时会使用的自动化入口。目录按 Agent Skill 工作流、Jugg CLI 任务域，以及 Agent 直接访问 MCP 的方式组织。

## 目录

| 页面 | 适合查看 | 底层能力 |
|---|---|---|
| [Agent Skills](./agent-skills.md) | 理解编辑、构建、部署、验证、迭代的 Agent 工作流 | `jugg-android-dev-loop` skill 与引用文档 |
| [Jugg CLI](./cli.md) | 判断什么时候从 Agent 或终端使用命令行入口驱动 Jugg | 对公开 Jugg MCP 工具的 CLI 封装 |
| [构建与部署](./cli-build-deploy.md) | 编译、部署、重装、重启，以及 Gradle 回退 | `compile`、`deploy`、`gradle-build`、`clean-reinstall`、`restart` |
| [Android Test](./cli-android-test.md) | 从测试源文件、class 或 method 锚点运行 androidTest | `instrument` |
| [运行时与设备](./cli-runtime-device.md) | 查看状态、连接设备、Activity 栈和运行时日志 | `status`、`devices`、`activity-stack`、`wait-logs` |
| [UI 自动化](./ui-automation.md) | 检查、定位、点击和读取运行时 UI 状态 | `layout-dump`、`view-locate`、`view-inspect`、`tap` |
| [UI 布局证据](./layout-verify.md) | 基于 UI dump 与 view inspection 形成布局证据，不依赖未注册的批量验证工具 | 公开 UI 证据链 |
| [远端诊断](./remote-diagnosis.md) | 为远端构建或设备问题申请 SSH 诊断信息 | `ssh-info` 与 Agent 升级流程 |
| [面向 Agent 的 MCP](./mcp.md) | Agent 直接通过 MCP 客户端调用 IDE 插件里的 Jugg 能力 | Jugg MCP endpoint 与已注册 action |

精确的 MCP 工具名、参数和输出字段放在参考分类的 [MCP 工具](../../reference/mcp-tools.md)。
