---
title: Agent Skills
description: 说明 Agent Skills 如何把编辑、编译、部署和验证组织成 Jugg 工作流。
status: active
tags:
  - capability
  - tools
  - agent
---

# Agent Skills

Agent Skills 是面向 AI 代码助手的 Jugg 工作流入口。它把 Android 项目里的“修改代码 -> 编译 -> 部署 -> 验证 -> 继续迭代”固定成可执行流程，并通过 Jugg CLI 调用 IDE 插件里的编译、部署、测试和运行时观察能力。

## 可完成的任务

| 用户任务 | 当前支持情况 | 生效方式 |
|---|---|---|
| 普通源码、资源、Manifest 或 Gradle 相关修改后的验证 | 支持 | Skill 要求先完成本轮编辑，再统一触发 `jugg compile` 或 `jugg deploy` |
| 需要在设备上观察 UI 或运行时状态 | 支持 | 走 `deploy`、`restart`、`layout-dump`、`view-locate`、`view-inspect`、`tap`、`wait-logs` 等 CLI 命令 |
| androidTest / instrumented test | 支持 | 先读取 `status.data.enabledAndroidTest`，满足 baseline 后使用 `instrument` |
| 自动运行入口验证 | 支持 | 用户明确声明入口方法后，Agent 写入验证代码，并用日志或 UI 工具确认结果 |
| Jugg CLI 安装或更新 | 支持 | 走 skill 的安装指南和脚本入口，不依赖用户手工查找脚本路径 |

> [!IMPORTANT]
> 自动运行入口不会自动从代码库推断。用户需要在任务中明确提供完整方法名；没有明确入口时，Agent 应改用普通编译/部署验证或询问用户。

## 工作流如何选择

Skill 会先根据任务场景选择流程，而不是把所有能力混在一起使用：

```text
安装 CLI
  -> 安装指南
androidTest 或 src/androidTest 任务
  -> Android Test 流程
用户明确给出 auto-run entry
  -> 自动运行入口验证流程
其他 Android 代码修改
  -> 编译 / 部署流程
```

这种分流决定了 Agent 何时只需要 `compile`，何时必须 `deploy` 到设备，何时应该通过 `instrument` 运行测试。

## 输入输出边界

- Skill 不直接替代 Jugg 插件能力；实际编译、部署、UI 检查和日志等待都通过 Jugg CLI 或 MCP 完成。
- Skill 会优先使用当前工作目录解析项目，也可以通过 `--project-dir` 明确指定项目。
- 构建类 CLI 命令会阻塞到终态；Agent 不需要自己轮询 MCP job。
- 失败时先读取命令返回的 `detail`、日志路径和状态字段，再决定重试、回退 Gradle 构建或申请远端诊断。

## 关联能力

- [Jugg CLI](./cli.md)
- [构建与部署](./cli-build-deploy.md)
- [Android Test](./cli-android-test.md)
- [UI 自动化](./ui-automation.md)
