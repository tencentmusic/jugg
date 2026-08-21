# Jugg CLI 全局设备 serial 支持方案

## 1. 背景

当前 `jugg` CLI 在多设备环境下没有公开的全局设备参数：

- IDEA Runtime 的部署与设备工具默认读取 IDE 选中设备。
- standalone Runtime 的部署依赖 daemon 启动时继承的 `ANDROID_SERIAL`；daemon 已运行后，新的 CLI 进程无法通过环境变量逐次切换设备。
- `view-locate`、`view-inspect`、`tap`、`wait-logs` 等工具各自在 MCP action 内解析默认设备，没有统一的显式 serial 契约。
- deploy/restart 日志起点只按 `projectDir` 保存，跨设备调用 `wait-logs` 时可能复用另一台设备的时间戳。

## 2. 已批准目标

新增与 `--project-dir` 同级的 CLI 全局参数：

```text
jugg --serial <adbSerial> <subcommand>
jugg --serial=<adbSerial> <subcommand>
```

CLI 将 serial 注入所有消费设备目标的 MCP 工具。MCP 不保存全局“当前设备”，每个相关工具通过公开、可选的 `serial` 参数接收本次请求目标。

显式 serial 的优先级高于 IDEA 当前选择和 standalone `ANDROID_SERIAL`，只影响当前请求，不修改 IDE 选择，不写入 Run Configuration，也不污染后续请求。

## 3. 设备工具范围

以下公开工具接收 `serial`：

- `deploy`
- `gradle-build`
- `clean-reinstall`
- `restart`
- `instrument`
- `status`
- `devices`
- `layout-dump`
- `view-locate`
- `view-inspect`
- `tap`
- `activity-stack`
- `wait-logs`

`version`、`init`、`compile`、`ssh-info` 以及内部 `get-compile-status` 不消费 serial。CLI 允许全局参数与这些命令同时出现，但不会向对应 MCP 请求注入。

`devices` 未传 serial 时保留完整设备列表；传入 serial 时只返回精确匹配的在线设备，未匹配时返回 `NO_DEVICE`。

## 4. 选择与状态语义

### 4.1 设备解析

显式 serial 使用大小写敏感的精确匹配，只允许在线设备；未命中时不得回退其他设备。

未传 serial 时保持现有 Host 行为：

- IDEA deploy 继续使用 IDE 选中的多设备列表。
- IDEA 单设备工具继续使用 selected-first、connected fallback。
- standalone deploy 继续优先使用 `ANDROID_SERIAL`，否则只允许恰好一台在线设备。

### 4.2 编译部署链

serial 作为单次 RPC 请求上下文贯穿：

- 无文件变化时的设备首次运行判断
- incremental / Gradle 编译
- install / incremental deploy
- Gradle fallback
- androidTest
- `hasRun` 终态
- App-ready 检查

无 serial 的 IDE Run、Debug 与多设备行为保持不变。

### 4.3 UI、日志与状态

UI/运行时工具的设备解析和 App-ready 检查必须使用同一个 serial。`view-locate` 通过 `LayoutDumpHelper` 将 serial 传入内部 layout dump，禁止回落到另一台默认设备。

`LastDeployTimestampRegistry` 增加项目 + serial 维度。显式 serial 的 deploy/restart 记录设备级时间戳；`wait-logs` 优先读取设备级时间戳，缺失时仅为兼容旧调用回退项目级时间戳。

`status --serial` 返回指定设备的 deploy state；无 serial 时保留现有聚合状态。

## 5. Standalone 当前缺口

本次 serial 支持只覆盖 standalone 已公开的能力：

- `deploy`：使用请求 serial 选择设备，解决已运行 daemon 无法读取新 `ANDROID_SERIAL` 的问题。
- `gradle-build`：接受统一参数；standalone 只建立/刷新 baseline，没有设备安装阶段，因此 serial 不产生设备操作。
- `status`：读取指定设备状态。

以下能力目前仍未在 standalone capability 中开放，本次不扩展：

- `devices`
- `restart`
- `clean-reinstall`
- `instrument`
- `layout-dump`
- `view-locate`
- `view-inspect`
- `tap`
- `activity-stack`
- `wait-logs`

这些命令仍需 IDEA Runtime。CLI 全局 serial 会在 IDEA Runtime 执行它们时生效，但不会因为本次改动自动为 standalone 增加 UI、ViewHierarchy、日志或运行控制能力。

## 6. 实现范围

### CLI

- `docs/skills/jugg-android-dev-loop/scripts/jugg.py`
- `docs/skills/jugg-android-dev-loop/scripts/py/jugglib.py`
- `docs/skills/jugg-android-dev-loop/scripts/py/help_registry.py`

### MCP 公共边界

- `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/DeviceSelectionResolver.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/actions/McpToolSchemas.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/actions/McpAppReadyGuard.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/util/LastDeployTimestampRegistry.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/deploy/IDeployTargetManager.kt`

### 编译部署链

- `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/actions/CompileAndDeployMcpToolAction.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/actions/CleanReinstallApkMcpToolAction.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/actions/InstrumentMcpToolAction.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/actions/ForceGradleCompileMcpToolAction.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/actions/CompileJobManager.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/compiler/ForceGradleCompileHelper.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/compiler/CompileUiHandler.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/compiler/JuggCompilerHelper.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/ide/logic/IJuggConfigurationRunner.kt`
- `idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/JuggConfigurationRunner.kt`
- `idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/JuggRunningTask.kt`
- `idea/src/main/java/com/sickworm/intellij/jugg/compiler/ForceGradleCompileExecutor.kt`
- `cmd_line/src/main/java/com/sickworm/intellij/jugg/cmdline/standalone/StandaloneConfigurationRunner.kt`
- `cmd_line/src/main/java/com/sickworm/intellij/jugg/cmdline/standalone/StandaloneProjectServices.kt`
- `cmd_line/src/main/java/com/sickworm/intellij/jugg/cmdline/standalone/StandaloneDeployTargetManager.kt`

### 设备工具

- `RestartAppMcpToolAction.kt`
- `DeviceListMcpToolAction.kt`
- `GetStatusMcpToolAction.kt`
- `LayoutDumpMcpToolAction.kt`
- `LayoutDumpHelper.kt`
- `UiFindMcpToolAction.kt`
- `EvalViewMcpToolAction.kt`
- `ActivityStackMcpToolAction.kt`
- `TapMcpToolAction.kt`
- `WaitLogsMcpToolAction.kt`

## 7. 验证

自动化测试保护以下稳定行为：

- CLI 全局参数解析与仅向设备工具注入。
- MCP `tools/list` 对设备工具公开 `serial`。
- serial 精确匹配、离线失败、无回退。
- deploy/fallback/ready/status 使用同一请求 serial。
- UI helper 和 `wait-logs` 不切换到默认设备。
- deploy/restart 时间戳按设备隔离。
- 未传 serial 时的现有行为保持不变。

执行定向 main/idea/cmd_line 测试、Python CLI 测试、`:idea:compileKotlin`，并回归 `TopLevelFlowTest#testInstallAndLaunch`。真实双设备环境执行 IDEA override、standalone 已启动 daemon、离线 serial 与无 serial 兼容矩阵；若本地缺少双设备，明确记录为手工验证缺口，不伪造结果。
