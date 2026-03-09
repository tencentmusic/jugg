# MCP 设计说明（当前实现视角）

> 最后核对：2026-03-09  
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 文档定位

本页描述 MCP 的实现分层、校验策略与扩展方式，不提供具体工具参数表。

---

## 2. 分层结构

| 层 | 核心类 | 作用 |
|----|--------|------|
| HTTP 服务层 | `McpLocalServer` | 监听端口、处理 HTTP 请求、基础安全校验 |
| 通用协议层 | `McpBaseInvoker` | initialize/ping/resources/prompts/tools-list 等通用方法 |
| 工具执行层 | `McpToolInvoker` | 参数校验、工具路由、结果映射 |
| 校验层 | `McpRequestValidator` | schema 校验、默认值填充、projectDir 授权检查 |
| 注册层 | `McpToolRegistry`, `McpToolActionRegistry` | 工具定义与 action 注册 |
| 设备端桥接层 | `mcp/viewhierarchy/ViewHierarchyClient` + `jvmti_agent/.../viewhierarchy/*` | `layout_dump` / `tap` 元素模式的 App 内 LocalSocket 通道（Server-only，无 uiautomator 回退） |
| 运行时适配层 | `IMcpRuntime`, `IdeaMcpRuntime` | 将工具执行连接到 IDE 真实能力 |

---

## 3. 协议与约束

- 传输：HTTP + JSON-RPC 2.0。  
- 主入口：`/jugg-mcp`。  
- 统一业务返回：`structuredContent` 内含 `status/message/data/artifacts/errorCode`。  
- 工具调用前必须经过 schema 校验与项目初始化校验（除 `list_projects`）。
- 运行态工具在 action 内执行“参数组合校验 -> App ready 校验 -> 业务执行”；其中参数错误优先返回 `MCP_INVALID_PARAMS`，App 未就绪返回 `MCP_INTERNAL_ERROR`，并附带 next action 建议。
- `restart_app` 支持可选 `tap_actions`，用于在重启后串行执行触控导航；单步参数与 `tap` 工具保持一致（`tap/longPress/swipe` + 坐标/百分比/元素模式，`swipe` 不支持元素模式）。步骤失败会短路并返回失败步骤索引。

---

## 4. 错误模型

- 协议级错误：映射为 JSON-RPC error。  
- 业务级失败：通常仍走 tool result，`status=ERROR`，保留 `data/artifacts`。

这一设计可避免客户端把业务失败误判为协议失败。

---

## 5. 异步编译设计

`CompileJobManager` 提供统一异步任务模型：
- 触发接口可能先返回 `isFinal=false`。  
- 客户端通过 `get_compile_status(jobId)` 查询终态。  
- 运行中状态会返回轮询建议字段（`pollIntervalSuggestedMs`），用于避免高频查询。  
- 使用 `compile_latest.log` 作为统一日志出口路径。

---

## 6. ViewHierarchy 可靠性约束

- `ElementFinder` 在 selector 命中前先过滤不可操作节点（可见、已显示、非零尺寸、有效 bounds）。
- `ViewHierarchyServerLoader` 仅在 `ViewHierarchyServer.start(...)` 成功后设置初始化标志，失败可重试。
- `ViewHierarchyClient` 在多进程场景按“主进程优先 -> 其余 PID -> `jugg_vh` 兼容名”尝试 socket，避免首个 PID 误选导致不可用。
- ViewHierarchy socket 响应包含 `version` 字段；客户端当前为 warn-only 策略（版本不匹配仅告警，不拦截请求）。
- **Versioning rule**: 当 `ViewHierarchyServer` 响应结构发生 breaking change（字段删除/类型变更/语义不兼容）时，必须递增服务端协议版本号，并同步客户端常量与文档。
- `layout_verify` 默认走 auto_dump：MCP 侧先抓取最新布局快照再执行断言；传 `dumpFile` 时走 explicit_dump 回放历史文件；live-only 属性（如 `textSizeSp`）自动切到 live query。
- `layout_verify` 的批量入口为 `checks[]`；每条 `check` 必须携带 `target`。支持 `checksFile` 文件输入（优先级低于参数 `checks`）。返回 `PASS | PARTIAL_FAIL | FAIL | ERROR` 聚合结果与 `checkResults[]` 明细；`checks` 为空时返回 `MCP_INVALID_PARAMS`。
- 关系类断言（`spacing/alignment/order`）统一以 `axis=x|y` 表达检查轴；`direction=horizontal|vertical` 仅做兼容映射（建议新调用改用 `axis`）。
- `spacing` 支持数值比较操作符（`eq/neq/gte/lte/gt/lt`）；当使用 `op` 时不允许再传 `tolerance`。
- `LayoutVerifyMcpToolAction` 的 dump 路径依赖 dump JSON 根节点 `deviceInfo.density` 做 dp 换算。

---

## 7. 扩展新工具建议

1. 新增 `McpToolAction` 实现。  
2. 定义 `McpToolDefinition`（含 input/output schema）。  
3. 注册到 `McpToolActionRegistry.defaultActions()`。  
4. 在 `08_mcp_usage.md` 同步用途与参数。  
5. 增加对应测试（参数校验 + 成功/失败路径）。

---

## 8. 关联文档

- 使用说明：`08_mcp_usage.md`
- 代码定位：`98_code_map.md`
