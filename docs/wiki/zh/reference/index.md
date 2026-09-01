---
title: 参考
description: Jugg Wiki 的查表型参考入口，汇总兼容性、术语、命令、工具、配置、日志和限制。
status: active
tags:
  - reference
---

# 参考

参考页用于快速确认 Jugg 的稳定名词、命令参数、工具契约、配置含义、日志位置和运行边界。它不是教程，也不解释深层机制；如果你要按步骤完成一次运行、调试或问题排查，优先阅读使用指南和问题排查页。

## 页面索引

| 页面 | 适合查什么 |
|---|---|
| [兼容性](./compatibility.md) | IDE、AGP、Gradle、Kotlin、Android 设备和产品能力支持范围。 |
| [术语表](./glossary.md) | Jugg 文档里常见的 compile、deploy、fallback、code swap 等术语。 |
| [CLI 命令](./cli-commands.md) | `jugg` 命令行子命令、全局参数和常用参数映射。 |
| [MCP 工具](./mcp-tools.md) | 面向 Agent 的 MCP tool 名称、输入输出约定和错误码。 |
| [配置](./configuration.md) | Run Configuration、全局设置、项目缓存与远端配置的含义。 |
| [日志文件](./log-files.md) | Jugg 日志位置、格式、常用检索词和 MCP artifact 位置。 |
| [限制](./limits.md) | 增量编译、部署、Debug、androidTest、MCP/CLI 的能力边界。 |

## 常用入口

- **想确认某个命令怎么写**：看 [CLI 命令](./cli-commands.md)。
- **想让 Agent 调用 Jugg**：看 [MCP 工具](./mcp-tools.md)。
- **想找日志**：看 [日志文件](./log-files.md)。
- **想判断是否需要回退 Gradle**：看 [限制](./limits.md)。

> [!TIP]
> 参考页面会尽量保持简短。涉及完整工作流时，页面会链接到对应指南、能力页或排查页，而不是重复展开所有步骤。
