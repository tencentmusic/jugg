# Findings - MCP 规划调研

## 文档与源码结论
- `IJuggManagerCaller.call(rpcRequest)` 是现有通用调用入口（历史能力）。
- 现有 `RpcLocalServer` 已使用 HTTP 本地服务形态，用户倾向 MCP 第一版采用 HTTP 形态。
- `JuggManager.call()` 通过 `RpcCaller` 实现命令路由，目前仅 `ECHO`/`RUN`。
- `JuggPathManager.juggRootDir` 是统一工程级输出根目录，适合承载 MCP 产物目录。
- `IDeployTargetManager` 已具备 selected/connected device 与应用启停能力，可复用设备与 app 操作。

## 用户已确认约束
- 语言：Kotlin。
- 包路径：`main/src/main/java/com/sickworm/intellij/jugg/mcp`。
- 新入口：新增 `invokeMcp`，保持与 `call` 类似分层设计，但不改老 `call`。
- 统一响应：`status/message/data/artifacts/errorCode`。
- 多设备默认策略：未传 serial 时使用 selected device，并在 message 说明。
- 兼容策略补充：`serial` 缺失或非法均不失败，统一回落 selected device，并在 message 返回 detail。
- 产物目录：`juggRootDir/mcp_fetch`。
- 需新增 `list_projects`。
- 全局约束：所有 MCP 命令都必须传 `projectDir`（必填）。
- 阶段目标：
  - Phase1: `restart_app`（链路打通）
  - Phase2: `compile`/`deploy`/`clean_reinstall`
  - Phase3: `device_list`/`screenshot`/`record`/`layout_dump`

## 已拍板结论
- 协议：走标准 MCP JSON-RPC。
- `list_projects` 来源：仅 IDE 已初始化的项目（`JuggInitializer#instanceSet`）。

## Phase 1 设计产出
- 已新增 `docs/ai_knowledge/08_mcp_design.md`，覆盖目标、边界、分层、协议、错误码、工具定义、时序与验收标准。
- 已在 `docs/ai_knowledge/README.md` 增加 MCP 规划入口索引。
