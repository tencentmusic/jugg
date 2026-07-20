# Jugg 架构设计（AI 任务版）

> 最后核对：2026-07-21
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 文档定位

本页只回答三件事：
- 系统分层怎么切
- 运行时主链路怎么走
- 改某类问题时应从哪里切入

---

## 2. 分层架构（当前代码）

| 层级 | 目录 | 核心职责 |
|------|------|----------|
| IDE 入口层 | `idea/src/ide_entry` | 插件加载、初始化、Run Configuration、Sync 事件与稳定 JComponent UI 桥接 |
| IDE 业务层 | `idea/src/main` | 编译/部署任务编排、UI、运行期策略 |
| 核心逻辑层 | `main/src/main/java/com/sickworm/intellij/jugg` | 编译、部署、项目模型、Gradle、MCP、工具能力 |
| 兼容层 | `deploy_compat/*` | Android Studio 版本 API 适配 |
| 平台桩层 | `platform_compat/base_api` | API mock，支撑非 IDE 场景编译/测试 |
| 运行时层 | `jvmti_agent/src/main/cpp` | JVMTI agent 与兼容部署支撑 |

---

## 3. 核心链路

### 3.1 启动与初始化

1. `JuggLoader` / `JuggInitializer` 触发初始化。  
2. `JuggManager` 装配编译、部署、项目、MCP 运行时。  
3. Sync 事件经 `JuggGradleSyncListener` 进入 `JuggManager.onSyncEvent`。

### 3.2 Run 主流程

1. `JuggRunningTask.run` 进入统一执行链。
2. `JuggCompilerHelper.compile` 决策“增量 or Gradle 回退”。
3. 增量路径：`IncrementalCompilerHelper` -> `JuggCompiler`。
4. 部署路径：`JuggDeployerHelper.deploy` -> `JuggDeployTask` -> `JuggDeployer`。
5. 结果写回状态与历史（deploy/history/status managers）。
6. `JuggRunningTask` 把 compile、deploy 关键节点记录到 `JuggControlPanelModel`；`JuggControlPanelController` 记录 Sync/App 事件并持有项目级 Model/Panel，JuggManager 仅负责装配和薄委托。

### 3.3 MCP 主流程

1. `McpLocalServer` 提供 `/jugg-mcp` HTTP 入口。  
2. `McpBaseInvoker` 处理 initialize/ping/tools/list 等通用方法。  
3. `McpToolInvoker` 校验参数并路由到 `ai/mcp/actions/*`。  
4. 业务结果统一映射为 `structuredContent`，生命周期记录为 `MCP request` / `MCP response` 核心事件。
5. `McpToolInvoker` 同时记录开始与唯一终态事件，不解析 raw log。

---

## 4. 关键设计取舍

- **增量优先，失败可回退**：优先走旁路增量，必要时回退 Gradle。
- **main 与 idea 解耦**：`main` 提供核心逻辑，`idea` 注入平台实现。
- **兼容层隔离**：AS 版本差异集中在 `deploy_compat`，减少业务污染。
- **协议内聚**：MCP 在 `main/.../ai/mcp` 独立分层，不与 IDE UI 逻辑强耦合。
- **稳定 UI 桥接**：`ide_entry` 只通过 `IJuggManagerCaller.getJuggControlPanel(page): JComponent` 挂载热更新 Panel，不暴露 Model、Event 或 UI DTO。
- **统一事件模型**：`main/.../event` 保存无 Project/Swing 依赖的 snapshot 与核心事件；leaf compiler/deployer 继续使用日志，上层编排边界记录用户可读事件。

---

## 5. 扩展点

- 自定义编译器：`ICompilerCreator` + `CustomCompilerManager`。  
- 平台能力注入：`PlatformApi`。  
- 新 MCP 工具：新增 `McpToolAction` 并注册至 `McpToolActionRegistry`。  
- 新兼容版本：在 `deploy_compat` 增加对应实现并接入 `AsDeployerCompat`。

---

## 6. 常见排查入口

- “为什么回退 Gradle”：`idea/.../JuggCompileHelper.kt` 中的 `JuggCompilerHelper`。
- “为什么部署失败”：`idea/.../JuggDeployerHelper.kt`。
- “为什么类热更失败”：`idea/.../deploy/run/applychanges/JuggDeployer.kt` + `main/.../runtime/jvmti/*`。
- “为什么 MCP 参数错误”：`main/.../ai/mcp/McpRequestValidator.kt`。

---

## 7. 关联文档

- 编译：`02_compile_core.md`
- 部署：`03_deploy_core.md`, `03_deploy_complete.md`
- IDE：`04_engineering_ide.md`
- MCP：`08_mcp_design.md`, `08_mcp_tools_list.md`
