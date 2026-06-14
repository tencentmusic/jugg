---
title: CLI
description: 介绍 Jugg CLI 的安装入口、输出模式、常用命令、Agent 使用建议和排查方法。
status: active
tags:
  - guide
  - cli
---

# CLI

Jugg CLI 让你在终端、脚本或 Agent 中调用 Jugg 插件能力。它通过 Android Studio 中已打开的 Jugg 插件服务工作，因此使用前需要先打开目标工程并完成 Jugg 初始化。

## 安装入口

推荐从 IDE 安装：

1. 双击 Shift 打开 Search Everywhere。
2. 搜索 `Install Jugg Skills`。
3. 在弹窗中选择需要安装的 Agent、CLI 和 hooks。
4. 点击 Install。

也可以从 Jugg 面板的 More Options 进入 Tools -> Install Jugg Skills。

安装选项通常包括：

| 选项 | 作用 |
|---|---|
| 当前已安装的 Agent | 将 `jugg-android-dev-loop` skill 安装到对应 Agent |
| Install CLI to `$PATH` | 安装人可直接使用的 `jugg` 命令 |
| Install agent hooks | 在 Agent 修改 Android 源码但未验证时提醒使用 Jugg |

## 输出模式

CLI 支持三种输出模式：

```bash
jugg --console=rich status
jugg --console=plain status
jugg --console=json status
```

| 模式 | 适合对象 | 特点 |
|---|---|---|
| `rich` | 人工终端 | 带 spinner 和交互展示 |
| `plain` | Agent | 输出稳定，不会用 spinner 污染上下文 |
| `json` | 脚本 | stdout 保持结构化 JSON |

> [!IMPORTANT]
> 给 Agent 使用时优先用 `--console=plain` 或 `--console=json`。不要让 Agent 直接消费 rich spinner 输出。

## 常用命令

```bash
jugg help
jugg help deploy
jugg status
jugg compile
jugg deploy --always-restart-app false
jugg gradle-build
jugg clean-reinstall
jugg restart
```

命令分组：

| 类型 | 命令 | 用途 |
|---|---|---|
| 版本 | `version` | 查看 CLI 和插件版本 |
| 构建部署 | `compile` | 仅编译，不部署 |
| 构建部署 | `deploy` | 编译并部署 |
| 构建部署 | `gradle-build` | 强制 Gradle 构建 |
| 构建部署 | `clean-reinstall` | 清数据并重装 |
| 测试 | `instrument` | 编译部署并运行 androidTest |
| 运行态 | `restart`、`wait-logs`、`activity-stack` | 重启、等待日志、查看 Activity 栈 |
| UI | `layout-dump`、`view-locate`、`view-inspect`、`tap` | 导出布局、定位元素、读取属性、触控 |
| 诊断 | `status`、`devices`、`ssh-info` | 查看状态、设备、申请远端 SSH 信息 |

## project-dir

在工程目录内调用时，CLI 会根据当前路径匹配已打开的 Jugg 项目。跨目录调用时显式传入项目路径：

```bash
jugg --project-dir /path/to/project status
```

flag 同时支持 kebab-case 和 camelCase，例如 `--project-dir` 与 `--projectDir` 等价。

## 编译类命令的等待策略

`compile`、`deploy`、`gradle-build`、`instrument` 可能耗时较长。CLI 会自动轮询直到终态。

如果当前已有编译任务，可以控制行为：

```bash
jugg --if-compiling wait compile
jugg --if-compiling interrupt compile
```

| 策略 | 行为 |
|---|---|
| `wait` | 默认，等待已有任务结束 |
| `interrupt` | 立即触发新任务，由服务端中断旧任务 |

## Agent 使用建议

- 默认让 Agent 使用 `jugg compile` 做编译验证，不要自动部署到设备。
- 需要设备验证时，明确要求 Agent 使用 `jugg deploy`、`jugg instrument` 或 UI 工具。
- 对 Android 源码修改，hooks 可以提醒 Agent 加载 Jugg skill 并执行验证。
- 如果要解析结果，优先使用 `--console=json`。
- 判断 `deploy` 是否成功时，同时看编译结果和部署结果，不要只看 compile success。

## 常见问题

| 现象 | 处理方式 |
|---|---|
| CLI 找不到项目 | 确认 Android Studio 已打开目标工程，或传 `--project-dir` |
| 连接端口失败 | 确认 Jugg 插件已初始化；多个 IDE 会使用端口范围自动递增 |
| 命令一直等待 | 用 `jugg status` 看 `isCompiling`，必要时使用 `--if-compiling interrupt` |
| `instrument` 提示 AndroidTest 未开启 | 先开启 Jugg Run Configuration 的 Android Test 并建立 full-build baseline |
| 输出很乱 | Agent 场景改用 `--console=plain` 或 `--console=json` |

## 相关页面

- [Jugg CLI](../capabilities/tools/cli.md)
- [CLI 命令](../reference/cli-commands.md)
- [Agent Skills](../capabilities/tools/agent-skills.md)
- [MCP 与 CLI](../concepts/mcp-and-cli.md)
- [MCP 与 CLI 问题排查](../troubleshooting/mcp-cli.md)
