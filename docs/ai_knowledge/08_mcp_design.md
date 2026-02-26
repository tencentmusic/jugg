# MCP 设计说明（当前实现视角）

> 最后核对：2026-02-23  
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
| 运行时适配层 | `IMcpRuntime`, `IdeaMcpRuntime` | 将工具执行连接到 IDE 真实能力 |

---

## 3. 协议与约束

- 传输：HTTP + JSON-RPC 2.0。  
- 主入口：`/jugg-mcp`。  
- 统一业务返回：`structuredContent` 内含 `status/message/data/artifacts/errorCode`。  
- 工具调用前必须经过 schema 校验与项目初始化校验（除 `list_projects`）。

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

## 6. 扩展新工具建议

1. 新增 `McpToolAction` 实现。  
2. 定义 `McpToolDefinition`（含 input/output schema）。  
3. 注册到 `McpToolActionRegistry.defaultActions()`。  
4. 在 `08_mcp_usage.md` 同步用途与参数。  
5. 增加对应测试（参数校验 + 成功/失败路径）。

---

## 7. 关联文档

- 使用说明：`08_mcp_usage.md`
- 代码定位：`98_code_map.md`
