# Progress - MCP 规划会话记录

## 2026-02-08
- 收集用户约束并确认三阶段优先级。
- 阅读知识库：`README.md`、`00_overview.md`、`01_architecture.md`、`98_code_map.md`、`04_engineering_ide.md`、`05_utilities.md`。
- 读取源码接口：`IJuggManagerCaller`、`JuggManager.call`、`RpcCaller`、`RpcRequest`、`RpcLocalServer`、`JuggPathManager`、`IDeployTargetManager`。
- 形成规划文件：`task_plan.md`（规划草案）。
- 同步发现：初次写入 `task_plan.md` 发生 shell 反引号转义噪音，已修复。

## 当前状态
- 需求澄清：完成
- 调研：完成
- 计划输出：完成
- 关键修正：`projectDir` 必填是所有 MCP 命令的全局约束，不是 `list_projects` 特例。
- 决策落地：协议采用标准 MCP JSON-RPC；`list_projects` 来源固定为 `JuggInitializer#instanceSet`。
- Phase 1 详细设计：完成（新增 `docs/ai_knowledge/08_mcp_design.md`，并更新 README 索引）。
- 新增规则：设备相关错误统一收敛为 `MCP_NO_DEVICE`（移除 `MCP_DEVICE_NOT_FOUND`）。
- 输出清单：已补充 Phase 1 文件级开发任务与推荐执行顺序。
- 文档分层调整：已将过程型开发清单从 `docs/ai_knowledge/08_mcp_design.md` 迁移到 `task_plan.md`。
- 代码实施开始：已完成 Task A（新增 MCP 协议模型、工具模型、错误码常量）。
- 验证结果：`./gradlew :main:compileKotlin -q` 通过（仅存在既有模块告警）。
- 已完成 Task B 基础骨架：`invokeMcp` 已接入 `IJuggManagerCaller` 与 `JuggManager`，新增 `McpInvoker` / `McpToolRegistry`。
- 已完成 Task D/E 基础骨架：新增 `McpRequestValidator`、`McpResultMapper`、`DeviceSelectionResolver` 并接入 `McpInvoker`。
- 验证结果：`main` 与 `idea` Kotlin 编译通过（`idea` 编译时排除了本地 `jvmti_agent` 原生构建任务）。
- 结构修正：`list_projects` 确认不在 `JuggManager` 内处理，改为应在 `JuggInitializer` 层处理（当前 `McpInvoker` 已返回占位错误以避免误导）。
- 设备桥接修正：`DeviceSelectionResolver` 已去除反射，改用 `PlatformApi.toDeviceAdb(device)` + `IDeviceAdb` 字段。
- 平台 API 扩展：`IPlatformApi` 新增 `toDeviceAdb(...)` 与 `invokeMcp(...)`，`IdeaPlatformApi/CmdPlatformApi/TestPlatformApi` 已同步实现签名。
- 验证结果：`./gradlew :main:compileKotlin -q` 与 `./gradlew :idea:compileKotlin -q` 均通过。
- 已新增独立 MCP HTTP 服务：`McpLocalServer`（端口 `12320..12329`，路径 `/mcp`），并接入 `JuggInitializer` 生命周期。
- 已实现工具：`list_projects`（分发层/JuggInitializer 来源）与 `restart_app`（JuggManager + DeviceSelectionResolver + 并发锁）。
- 已补单测：`McpLocalServerTest`（协议层）与 `McpInvokerTest`（工具与错误码逻辑）。
- 测试结果：`./gradlew :main:test --tests com.sickworm.intellij.jugg.mcp.McpInvokerTest :idea:test --tests com.sickworm.intellij.jugg.mcp.McpLocalServerTest -q` 通过。
- 已补使用文档：`docs/ai_knowledge/08_mcp_usage.md`，并更新 `README.md` 索引。
- 已完成 Phase 2：新增 `compile`、`deploy`、`clean_reinstall` 三个 MCP 工具并接入 `McpInvoker` 分发。
- 运行时桥接：`IMcpRuntime` 扩展三类接口，`IdeMcpRuntime` 复用既有 `RpcCommand.RUN` 链路执行，并映射 `RunResult` 到 MCP 结果结构。
- 工具注册更新：`McpToolRegistry` 已暴露 phase2 三个工具元数据（均要求 `projectDir`）。
- 单元测试更新：`McpInvokerTest` 增加三工具成功用例，`McpLocalServerTest` 增加工具清单断言。
- 验证结果：`./gradlew :main:test --tests com.sickworm.intellij.jugg.mcp.McpInvokerTest :idea:test --tests com.sickworm.intellij.jugg.mcp.McpLocalServerTest -q` 通过。
- 验证结果：`./gradlew :main:compileKotlin :idea:compileKotlin -q` 通过。
- 已完成 Phase 3：新增 `device_list`、`screenshot`、`record`、`layout_dump` 四个 MCP 工具并接入 `McpInvoker` 分发。
- 设备调用链路：扩展 `IDeviceAdb` 增加 `pull(...)`，`IdeaDeviceAdb/CmdAdb` 已实现；截图/录屏/布局导出均通过 `IDeviceAdb.execAdbShellCmd + IDeviceAdb.pull` 执行。
- 产物落盘：统一输出到 `build/jugg/mcp_fetch/<tool>/`，并通过 `artifacts[]` 回传。
- 运行时实现：`IdeMcpRuntime` 新增 phase3 四工具执行逻辑；`record` 增加串行锁防止并发录屏冲突。
- 测试更新：`McpInvokerTest` 增加 phase3 四工具用例；`McpLocalServerTest` 增加 phase3 工具列表断言。
- 验证结果：`./gradlew :main:compileKotlin :idea:compileKotlin -q` 与 `./gradlew :main:test --tests com.sickworm.intellij.jugg.mcp.McpInvokerTest :idea:test --tests com.sickworm.intellij.jugg.mcp.McpLocalServerTest -q` 均通过。
