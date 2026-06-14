---
title: 参考
description: Jugg Wiki 的参考入口，汇总兼容性、命令、工具、配置、日志、模块和限制。
status: active
tags:
  - reference
---

# 参考

参考页用于快速确认 Jugg 的稳定名词、命令参数、工具契约和运行边界。它不是教程；如果你要按步骤完成一次运行、调试或问题排查，优先阅读使用指南和问题排查页。

## 页面索引

| 页面 | 适合查什么 |
|---|---|
| [兼容性](./compatibility.md) | Android Studio 版本、设备环境、Debug attach 和部署兼容边界。 |
| [术语表](./glossary.md) | Jugg 文档里常见的 compile、deploy、fallback、code swap 等术语。 |
| [CLI 命令](./cli-commands.md) | `jugg` 命令行子命令、全局参数和常用参数映射。 |
| [MCP 工具](./mcp-tools.md) | 面向 Agent 的 MCP tool 名称、输入输出约定和错误码。 |
| [配置](./configuration.md) | Run Configuration、全局设置、项目缓存与远端配置的含义。 |
| [日志文件](./log-files.md) | Jugg 日志位置、格式、常用检索词和 MCP artifact 位置。 |
| [模块](./modules.md) | 仓库模块、主要源码目录和各模块职责。 |
| [限制](./limits.md) | 增量编译、部署、Debug、androidTest、MCP/CLI 的能力边界。 |

## 常用入口

- 想确认某个命令怎么写：看 [CLI 命令](./cli-commands.md)。
- 想让 Agent 调用 Jugg：看 [MCP 工具](./mcp-tools.md)。
- 想找日志：看 [日志文件](./log-files.md)。
- 想判断是否需要回退 Gradle：看 [限制](./limits.md)。
- 想了解仓库结构：看 [模块](./modules.md)。

> [!TIP]
> Reference 页面会尽量保持简短。涉及完整工作流时，页面会链接到对应指南、能力页或排查页，而不是重复展开所有步骤。
