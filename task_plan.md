# MCP 实施计划（规划阶段）

## 目标
- 在 `main/src/main/java/com/sickworm/intellij/jugg/mcp` 建立独立 MCP 能力。
- 所有能力通过新增 `invokeMcp` 调用链接入，不改造历史 `IJuggManagerCaller.call` 语义。
- 分三阶段落地：先打通，再扩展已有能力，最后新增设备能力。

## 当前决策（已确认）
- 传输层：第一版按 HTTP 形态落地。
- 语言：Kotlin。
- 包名：`com.sickworm.intellij.jugg.mcp`。
- 结果结构：`status/message/data/artifacts/errorCode`。
- 多设备默认：自动使用 selected device，并在 message 提示。
- 产物目录：`JuggPathManager.juggRootDir/mcp_fetch`。
- 增补接口：`list_projects`。
- 全局约束：所有 MCP 命令都必须传 `projectDir`（必填）。

## 分阶段计划

### Phase 1（打通链路）
- 定义 MCP 基础协议（请求/响应模型、错误码、工具注册）。
- 新增 `invokeMcp` 入口与分层路由（与 `call` 设计一致但独立实现）。
- 实现 `restart_app` 最小工具并完成端到端打通。
- 新增 `list_projects` 工具并接入已初始化项目来源。

### Phase 2（既有能力桥接）
- 实现 `compile`、`deploy`、`clean_reinstall` 三个工具。
- 统一参数校验、执行上下文、日志与错误转换。
- 明确串行/并发策略与任务互斥边界。

### Phase 3（新增设备能力）
- 实现 `device_list`、`screenshot`、`record`、`layout_dump`。
- 在 `mcp_fetch` 下规范产物命名与索引。
- 建立产物回传（`artifacts[]`）与可观测信息。

## 验收标准（每阶段）
- 有可调用的工具清单与参数约束。
- 统一响应结构稳定返回。
- 错误可诊断（错误码 + message）。
- 同步更新 `docs/ai_knowledge/README.md` MCP 章节。
- 全量命令执行前统一校验 `projectDir` 必填。

## 待确认事项
- MCP over HTTP 的具体协议形态：是否对齐 MCP 标准 JSON-RPC 字段（`tools/list`、`tools/call`）还是 Jugg 自定义 HTTP-RPC。
- `list_projects` 的来源范围（已确认）：仅当前 IDE 已初始化项目（`JuggInitializer#instanceSet`）。

## 状态
- [x] 需求澄清
- [x] 文档与源码调研
- [x] 计划评审确认
- [x] Phase 1 详细设计输出（无代码）
- [x] Phase 1 开发任务清单（文件级拆分）
- [x] Phase 1 MCP 协议链路打通（HTTP + JSON-RPC）
- [x] Phase 1 工具实现（`list_projects`、`restart_app`）
- [x] Phase 1 单元测试通过（含 HTTP 协议测试）
- [x] Phase 2 工具桥接（`compile`、`deploy`、`clean_reinstall`）
- [x] Phase 2 单元测试通过（`McpInvokerTest` / `McpLocalServerTest`）
- [x] Phase 3 工具桥接（`device_list`、`screenshot`、`record`、`layout_dump`）
- [x] Phase 3 单元测试通过（`McpInvokerTest` / `McpLocalServerTest`）

## Phase 1 开发任务清单（文件级拆分）

说明：
- 本清单用于进入编码阶段的执行顺序控制。
- 坚持“先协议骨架，后工具实现，再联调验证”。

### 1. Task A：定义 MCP 协议模型与常量

预计新增文件（`main/src/main/java/com/sickworm/intellij/jugg/mcp`）：
- `McpJsonRpcModels.kt`：JSON-RPC request/response/error 模型。
- `McpToolModels.kt`：tool metadata、call args/result 模型。
- `McpErrorCode.kt`：`MCP_INVALID_JSON_RPC`、`MCP_INVALID_PARAMS`、`MCP_PROJECT_NOT_INITIALIZED`、`MCP_NO_DEVICE` 等。

验收标准：
- 模型能覆盖 `tools/list`、`tools/call`。
- 错误码与 `docs/ai_knowledge/08_mcp_design.md` 第五节保持一致。

### 2. Task B：实现 `invokeMcp` 入口与分发骨架

预计修改文件：
- `idea/src/ide_entry/java/com/sickworm/intellij/jugg/ide/IJuggManagerCaller.kt`：新增 `invokeMcp` 方法签名（不改 `call` 语义）。
- `idea/src/main/java/com/sickworm/intellij/jugg/JuggManager.kt`：实现 `invokeMcp`。

预计新增文件：
- `main/src/main/java/com/sickworm/intellij/jugg/mcp/McpInvoker.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/mcp/McpToolRegistry.kt`

验收标准：
- `invokeMcp` 完成全局 `projectDir` 必填校验。
- 支持 tool 路由与统一异常收敛。

### 3. Task C：实现 JSON-RPC Dispatcher（HTTP 侧）

预计新增文件：
- `main/src/main/java/com/sickworm/intellij/jugg/mcp/McpJsonRpcDispatcher.kt`

预计修改文件（按实际启动点接入）：
- 与现有本地 HTTP 服务初始化相关入口（保持与 `RpcLocalServer` 解耦）。

验收标准：
- 可处理 `tools/list` / `tools/call`。
- JSON-RPC 非法输入返回 `MCP_INVALID_JSON_RPC`。

### 4. Task D：实现通用参数与结果映射

预计新增文件：
- `main/src/main/java/com/sickworm/intellij/jugg/mcp/McpRequestValidator.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/mcp/McpResultMapper.kt`

验收标准：
- 所有工具统一返回 `status/message/data/artifacts/errorCode`。
- 失败场景 `data` 固定对象、`artifacts` 固定数组。

### 5. Task E：实现 DeviceSelectionResolver（Phase 1 可复用）

预计新增文件：
- `main/src/main/java/com/sickworm/intellij/jugg/mcp/DeviceSelectionResolver.kt`

验收标准：
- `serial` 缺失或非法均回落 selected device。
- 无设备或 selected device 不可用时统一返回 `MCP_NO_DEVICE`。
- message detail 输出与 `docs/ai_knowledge/08_mcp_design.md` 第 2.4/2.5 节一致。

### 6. Task F：实现工具 `list_projects`

预计新增文件：
- `main/src/main/java/com/sickworm/intellij/jugg/mcp/tools/ListProjectsTool.kt`

依赖来源：
- `com.sickworm.intellij.jugg.loader.JuggInitializer#instanceSet`

验收标准：
- 仅返回已初始化项目。
- `projectDir` 缺失时报 `MCP_INVALID_PARAMS`。

### 7. Task G：实现工具 `restart_app`

预计新增文件：
- `main/src/main/java/com/sickworm/intellij/jugg/mcp/tools/RestartAppTool.kt`

依赖来源：
- `JuggManager` + `IDeployTargetManager`

验收标准：
- 支持 `serial` optional。
- `serial` 缺失/非法：回落 selected device 并在 message 返回 detail。
- 设备不可执行时返回 `MCP_NO_DEVICE`。

### 8. Task H：联调样例与最小测试

预计新增文件：
- `main/src/test/java/.../mcp/` 下的基础用例（按现有测试结构放置）。

建议覆盖：
- `tools/list` 正常。
- `restart_app` 无 `serial` 回落成功。
- `restart_app` 非法 `serial` 回落成功。
- 缺失 `projectDir`。
- 项目未初始化。
- 无设备返回 `MCP_NO_DEVICE`。

验收标准：
- 与 `docs/ai_knowledge/08_mcp_design.md` 第十一节 JSON-RPC 合约示例对齐。

### 9. 执行顺序（推荐）

- A → B → D → E → F → G → C → H

理由：
- 先稳定核心模型与入口，避免工具开发期间接口反复变更。
- 先把 `invokeMcp` 路由和结果映射固定，再接入具体工具。
- HTTP dispatcher 最后接入，减少早期联调噪音。
