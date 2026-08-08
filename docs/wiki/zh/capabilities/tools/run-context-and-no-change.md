---
title: 运行上下文与无变化结果
description: 说明 CLI/MCP 如何选择 Jugg 配置，以及 deploy 没有待处理文件时如何解读结果。
status: active
tags:
  - capability
  - cli
  - mcp
  - deploy
---

# 运行上下文与无变化结果

CLI 和 MCP 从 Android Studio 当前工程中复用 Jugg 运行上下文。命令本身不携带完整 Run Configuration，因此多 App、多 variant 或远端/本地混合工程中，先确认配置选择和“无变化”语义很重要。

## 配置选择决定实际构建目标

CLI/MCP 按以下顺序选择 Jugg 配置：

1. Android Studio 当前选中的 Jugg 配置。
2. 与最近一次完整构建的命令和 BuildTarget 匹配的配置。
3. 与最近一次完整构建命令匹配的配置。
4. 第一个可用配置，并在日志中说明回退选择。

选择结果决定 compile command、APK pattern、BuildTarget、远端编译参数和部署目标。多配置工程建议在调用前显式选中目标 Jugg 配置。

## \`executionType\` 表示 Gradle 回退位置

\`status\` 和编译类工具会返回 \`executionType\`：

| 值 | 含义 |
|---|---|
| \`local\` | 需要 Gradle 时在本机执行 |
| \`remote\` | 需要 Gradle 时使用 Run Configuration 的远端环境 |

它描述回退执行环境，不表示当前调用一定已经走了 Gradle。是否发生回退还要结合最终 compile result 和日志判断。

## No pending file changes 是成功状态

\`deploy\` 返回成功但没有 compiled files 时，表示当前 Jugg 检测到的修改已经部署，没有新的待处理文件。它不等同于“命令没有执行”，也不代表重新编译了一遍所有文件。

结果会尽量附带当前 IDE 会话内最近一次“包含文件变化且部署成功”的信息：

- 绝对时间和相对时间。
- 项目相对文件路径。
- 最多展示 20 个文件，更多文件只显示剩余数量。

该记录只保存在当前 IDE 会话。IDE 重启后，命令仍会报告没有待部署变化，但最近一次文件明细可能不可用。

## dry deploy 与 Gradle fallback

IDE Run 在没有文件变化时可以根据设置：

- 回退完整 Gradle 构建。
- 取消本轮运行。
- 执行一次 dry deploy，用现有部署状态继续启动或验证设备端状态。

dry deploy 不应被报告成 Gradle fallback。它没有新增编译产物，主要用于首次运行、切换工程、Debug 或用户选择跳过完整构建的场景。

CLI/MCP 返回结果时，应同时看：

- \`isCompileSuccess\`
- \`isDeploySuccess\`
- message / detail
- compiled files

编译成功、无新文件和部署成功可以同时成立。

## 多次部署的判断建议

\`\`\`text
jugg status
  -> 确认 selected device、executionType、pending files
jugg deploy
  -> 查看 compile/deploy 终态
  -> 无 pending files 时读取最近一次变化部署摘要
\`\`\`

如果你确信文件已修改但仍返回 no pending changes，先保存文件并执行带刷新变化的 status，再检查工程目录和当前选中的 Jugg 配置。

## 相关页面

- [运行配置与构建变体](../../guide/run-configuration.md)
- [构建与部署](./cli-build-deploy.md)
- [CLI 命令](../../reference/cli-commands.md)
- [MCP 工具](../../reference/mcp-tools.md)
