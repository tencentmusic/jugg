---
title: CLI 命令
description: 汇总 jugg CLI 的全局参数、公开子命令和常用参数。
status: active
tags:
  - reference
  - cli
---

# CLI 命令

本页是 `jugg` CLI 的命令和参数速查。它不说明 CLI 适合哪些场景；能力范围和使用建议见 [Jugg CLI](../capabilities/tools/cli.md)，按步骤使用见 [CLI 使用指南](../guide/cli.md)。

## 命令格式

```bash
jugg [--console=plain|rich|json] [--project-dir <path>] [--if-compiling wait|interrupt] <subcommand> [options]
jugg help <subcommand>
```

## 全局参数

| 参数 | 说明 |
|---|---|
| `--console=plain` | 纯文本输出，不显示 spinner。直接运行 Python 脚本时默认使用。 |
| `--console=rich` | 面向人工终端的 spinner 输出。shell wrapper 默认使用。 |
| `--console=json` | 输出 MCP `structuredContent` JSON，适合脚本和 Agent。 |
| `--project-dir <path>` | 直接指定 MCP `projectDir`，跳过当前目录自动匹配。 |
| `--if-compiling wait` | compile 类命令触发前等待已有编译结束，默认值。 |
| `--if-compiling interrupt` | 不等待旧任务，直接触发新任务并沿用服务端中断语义。 |

`--projectDir`、`--ifCompiling` 这类 camelCase 全局参数会被归一化为 kebab-case。

## 公开子命令

| 子命令 | 用途 |
|---|---|
| `version` | 显示 CLI 版本和插件版本。 |
| `compile` | 仅执行 Jugg 编译，不部署。 |
| `deploy` | 编译并部署。 |
| `gradle-build` | 强制 Gradle 构建，并走后续安装/启动链路。 |
| `clean-reinstall` | 清数据并重装 APK。 |
| `restart` | 重启目标 App。 |
| `instrument` | 从 androidTest 源文件锚点运行测试。 |
| `status` | 查看设备、fallback、未编译文件和 androidTest baseline 状态。 |
| `layout-dump` | 导出 UI 层级 HTML。 |
| `view-locate` | 通过文本、resource id 或 content-desc 查找元素位置。 |
| `view-inspect` | 反射读取 View getter/query 属性。 |
| `tap` | 执行 tap、long-press 或 swipe。 |
| `devices` | 列出已连接设备。 |
| `activity-stack` | 查看 Activity 栈。 |
| `ssh-info` | 申请远端 SSH 排障信息。 |
| `wait-logs` | 等待 App 日志 marker、crash 或 timeout。 |

## 编译和部署

```bash
jugg compile
jugg deploy --always-restart-app false
jugg gradle-build
jugg clean-reinstall
jugg restart
```

| 命令 | 常用参数 | 说明 |
|---|---|---|
| `compile` | 无 | 只编译，不做部署。 |
| `deploy` | `--always-restart-app <true|false>` | `false` 允许满足条件时走 HOT RELOAD。 |
| `gradle-build` | 无 | 强制 Gradle 构建，失败时会输出日志摘要。 |
| `clean-reinstall` | 无 | 用于本地历史、设备安装状态不一致时恢复。 |
| `restart` | 无 | 只重启 App。 |

> [!IMPORTANT]
> `deploy`、`gradle-build` 的终态需要同时看 `isCompileSuccess` 和 `isDeploySuccess`。编译成功不等于部署成功。

## Android Test

```bash
jugg instrument --source-path app/src/androidTest/java/example/FooTest.kt
jugg instrument --source-path app/src/androidTest/java/example/FooTest.kt --class example.FooTest --method testLogin
```

| 参数 | 说明 |
|---|---|
| `--source-path` / `--sourcePath` | 必填，用于解析 module 与 Test APK。 |
| `--class` | 测试类；单 class 文件可省略。 |
| `--method` | 测试方法；需要已唯一确定 class。 |
| `--runner` | instrumentation runner override。 |
| `--extras` | 分号分隔的 `k=v;k2=v2` 参数。 |

不支持 `--package`、`--testsRegex`、`--regex`、`--clazz`、`--instrumentationRunner`、`-e` 等旧入口或别名。

## 状态和设备

```bash
jugg status --refresh-changes true
jugg devices
jugg activity-stack
```

| 命令 | 常用参数 | 说明 |
|---|---|---|
| `status` | `--refresh-changes <true|false>` | 是否先刷新 git-tracked changed files。 |
| `devices` | 无 | 返回设备列表并标记 selected。 |
| `activity-stack` | 无 | 返回 top activity 和 activity 栈。 |

## UI 工具

```bash
jugg layout-dump --include-gone --all-windows
jugg view-locate --resource-id login_button
jugg view-inspect --text 登录 getText() isEnabled()
jugg tap --resource-id login_button
jugg tap --x-percent 50 --y-percent 80
jugg tap --action swipe --x 500 --y 1600 --end-x 500 --end-y 300 --duration 300
```

| 命令 | 常用参数 |
|---|---|
| `layout-dump` | `--root-layout`、`--include-gone`、`--all-windows` |
| `view-locate` | `--text`、`--resource-id`、`--content-desc` |
| `view-inspect` | `--text`、`--resource-id`、`--content-desc`、`--class-name`、getter 表达式 |
| `tap` | `--action`、坐标参数、百分比参数、元素 selector、`--duration` |

`tap` 的模式优先级是 coordinate > percent > element。`swipe` 只支持坐标或百分比模式，不支持元素模式。

## 日志和远端排障

```bash
jugg wait-logs --marker "LoginSuccess" --tags Activity,Repository --timeout-ms 30000
jugg ssh-info --reason "Need to inspect remote Gradle build output"
```

| 命令 | 参数 | 说明 |
|---|---|---|
| `wait-logs` | `--marker`、`--tags`、`--timeout-ms` | 等待日志 marker、crash 或 timeout。 |
| `ssh-info` | `--reason` | 需要用户显式同意的远端排障入口。 |

## 相关页面

- [MCP 工具](./mcp-tools.md)
- [日志文件](./log-files.md)
- [CLI 使用指南](../guide/cli.md)
