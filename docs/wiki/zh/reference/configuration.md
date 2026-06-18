---
title: 配置
description: 汇总 Jugg 运行配置、全局设置、项目目录和远端配置的含义。
status: active
tags:
  - reference
  - configuration
---

# 配置

本页是配置含义和落点速查，覆盖 Android Studio Run Configuration、IDE 全局设置、CLI/MCP 一次性参数、项目级目录和用户级目录。它不说明每个按钮在哪里点击；More Options 里的低频操作入口见 [高级选项](../guide/advanced-options.md)。

用户通常只需要通过 Jugg UI 或 CLI 参数修改配置，不建议手动编辑缓存文件。

## Run Configuration 选项

| 选项 | 含义 |
|---|---|
| Always restart app after deployment | 部署后强制重启 App；关闭后，在满足条件时允许 HOT RELOAD。 |
| Confirm fallback when no file changes | 没检测到文件变化却要回退 Gradle 时先确认。 |
| Auto fallback to Gradle when deploy error | 部署异常时自动尝试 Gradle 回退。 |
| Embedded to APK | 将特定产物嵌入 APK 的运行选项。 |
| Android Test / enableAndroidTest | 让本轮基线按 androidTest 目标初始化。 |

不同 Android Studio 版本的 UI 文案可能略有差异，但语义以当前 Run Configuration 展示为准。

## 全局行为开关

| 配置 | 默认口径 | 说明 |
|---|---|---|
| Compile on save | 关闭 | 保存后自动触发编译。 |
| Deploy on save | 关闭 | 保存后自动部署。 |
| Check checksum when file changes | 开启 | 文件变化时校验 checksum，减少无效增量。 |
| Compatible deployment mode | 开启 | 面向 HarmonyOS、HyperOS、低 API 设备等场景启用兼容部署策略。 |
| Direct overlay deploy | 开启 | 启用不要求 App 进程在线的 overlay 部署快捷路径。 |
| Use project Kotlin compiler | 开启 | 优先使用项目 Kotlin compiler。 |
| Backup classpath | 默认关闭 | 保存 classpath 备份；Windows 环境不可用。 |
| Ignore wont compile modules | 关闭 | 忽略不会参与编译的模块。 |
| Const-ref tasks | 开启 | 启用常量引用扫描、分析和影响查询。 |

这些配置最终会持久化到 IDE 的 properties 中，key 使用 `jugg.*` 前缀。直接编辑 IDE 内部 properties 风险较高，优先通过 Jugg UI 修改。

## CLI / MCP 参数

CLI 和 MCP 的调用参数只影响本次调用，不等同于永久修改 IDE 配置。

| 参数 | 适用入口 | 说明 |
|---|---|---|
| `projectDir` | MCP / CLI | 目标项目绝对路径。 |
| `--project-dir` | CLI | 覆盖自动项目匹配。 |
| `alwaysRestartApp` / `--always-restart-app` | `deploy` | 控制本次部署是否强制重启。 |
| `waitAppReadyAfterSuccess` | MCP | 编译/部署成功后是否等待 App ready；CLI 当前不暴露。 |
| `refreshChanges` / `--refresh-changes` | `status` | 查询状态前是否刷新 git-tracked changed files。 |
| `--if-compiling` | CLI | 当前有编译运行时等待或中断。 |

## 项目级目录

所有路径默认相对于项目根目录。

| 路径 | 用途 |
|---|---|
| `build/jugg/log/` | Jugg 主日志。 |
| `build/jugg/build/staging/` | 本轮增量编译 staging 输出。 |
| `build/jugg/database/project_infos.db/` | IDE / Gradle project info 快照。 |
| `build/jugg/database/compile_context.db/` | classpath、模块信息和 full build 信息。 |
| `build/jugg/database/deploy_history.db/` | 部署历史和恢复信息。 |
| `build/jugg/classpath/` | classpath、APK、library backup、embedded APK 缓存。 |
| `build/jugg/config/custom_compilers/` | 自定义编译器配置目录。 |
| `build/jugg/mcp_fetch/` | MCP 工具产物缓存。 |
| `.gradle/jugg/readProjectInfo.gradle.kts` | Jugg 注入 Gradle 读取项目模型的脚本。 |
| `.gradle/jugg/jugg-runtime.jar` | Gradle 侧运行时 jar。 |

## 用户级目录

| 路径 | 用途 |
|---|---|
| `~/.jugg/const_ref/` | 跨项目常量引用缓存。 |
| `~/.jugg/library_test_build_records/` | library androidTest 构建历史。 |
| `~/.cache/jugg/port` | CLI MCP 端口缓存。 |
| `~/.cache/jugg/` | CLI 缓存根目录，可用 `JUGG_CACHE_DIR` 覆盖。 |

## 远端配置

Jugg 远端相关配置包括 server URL、过期时间、远端编译 diff 目录和 SSH 排障信息。远端编译产物和 diff 默认落在：

```text
build/jugg/tmp/diff/
```

如果远端编译结果和本地状态不一致，优先查看 [日志文件](./log-files.md) 中的 full log 和 `tmp/diff` 产物。

## 相关页面

- [CLI 命令](./cli-commands.md)
- [MCP 工具](./mcp-tools.md)
- [日志文件](./log-files.md)
- [限制](./limits.md)
