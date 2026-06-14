---
title: MCP 与 CLI 问题排查
description: 排查 Jugg MCP 工具调用、项目参数、CLI base/incremental 命令和环境变量错误。
status: active
tags:
  - troubleshooting
  - mcp
  - cli
---

# MCP 与 CLI 问题排查

MCP 与 CLI 问题通常可以直接按错误文案定位。不要猜测参数；先对照工具 schema 或 CLI 命令说明。

## MCP 通用错误

| 报错 | 第一判断 | 建议处理 |
|---|---|---|
| `Method not supported: ...` | JSON-RPC method 不受支持 | 使用 `tools/list` 或 `tools/call` |
| `tools/call params is required` | `tools/call` 缺少 params | 补充 MCP params |
| `Tool name is required` | 缺少工具名 | 在 params 中传入 `name` |
| `Tool not found: ...` | 工具未注册 | 先调用 `tools/list` 获取可用工具 |
| `<tool> failed. Reason: projectDir is required.` | 工具需要项目目录 | 传入当前工程 `projectDir` |
| `<tool> failed. Reason: project is not initialized.` | `projectDir` 不是当前已初始化项目 | 使用当前打开的 Jugg 项目目录 |
| `<tool> failed. Reason: Unknown argument(s): ...` | 参数名不在 schema 中 | 删除未知参数或改用正确字段 |
| `<field> is required` | 缺少必填参数 | 按 schema 补齐 |
| `<field> must be ...` | 参数类型不匹配 | 改成 schema 要求的类型 |

## 编译类 MCP 工具

常见错误：

```text
get-compile-status failed. Reason: jobId is required.
get-compile-status failed. Reason: waitTimeoutMs must be an integer in [0, 10000].
get-compile-status failed. Reason: Compile job not found for jobId=...
```

处理方式：

1. 先确认编译触发工具返回的 `jobId`。
2. 查询状态时使用同一个 `projectDir` 和 `jobId`。
3. `waitTimeoutMs` 只能在 `[0, 10000]` 范围内。

## CLI 命令错误

| 报错 | 第一判断 |
|---|---|
| `No cmd specified, exit.` | 没有传入 CLI 子命令 |
| `unknown cmd:...` | 子命令不存在 |
| `Parse params invalid, reason: ...` | 参数解析失败 |
| `JAVA_HOME not found.` | base 构建环境缺少 `JAVA_HOME` |
| `ANDROID_HOME not found.` / `Environment variable ANDROID_HOME is not set.` | Android SDK 环境变量缺失 |
| `Gradle compile failed.` | Gradle base 构建失败 |
| `No apk found.` | Gradle 构建后没有找到 APK |
| `Gradle project info file not exists: ...` | 缺少 Gradle 项目信息文件 |
| `Gradle project info file invalid: ...` | Gradle 项目信息文件无效 |

## incremental CLI 参数错误

常见错误：

```text
Param 'baseBuildJuggRootDir' not found.
Param 'outputApkDir' not found.
Param 'changedFiles' not found.
Param 'changedFiles' is empty.
Changed file not exists: ...
Custom compiler file not exists: ...
Argument 'changedFiles' file is not in source project dir: ...
Argument 'changedFiles' contains build file: ...
Files check failed, not all files are compilable.
Update apk failed, reason: ...
```

处理方式：

1. 确认已经先完成 base 构建。
2. `changedFiles` 只传源码工程内存在的可编译文件。
3. build 文件变更不要走 incremental CLI，改用 Gradle base 构建。
4. `outputApkDir` 指向可写目录。

## 相关页面

- [CLI 指南](../guide/cli.md)
- [MCP 指南](../guide/mcp.md)
- [CLI 命令参考](../reference/cli-commands.md)
- [MCP 工具参考](../reference/mcp-tools.md)
