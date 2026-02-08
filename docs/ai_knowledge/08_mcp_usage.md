# Jugg MCP 使用说明（Phase 1）

> 更新时间: 2026-02-08  
> 传输协议: MCP Streamable HTTP + JSON-RPC 2.0  
> 当前工具: `list_projects`, `restart_app`

---

## 一、服务信息

- 服务端口范围：`12320..12329`
- HTTP 路径：`/mcp`
- 响应 Content-Type：`application/json`

说明：
- 服务生命周期跟随 `JuggInitializer`（项目初始化时启动，全部释放后停止）。

---

## 二、Streamable HTTP 行为

- `POST /mcp`：发送 JSON-RPC 请求/通知。
- `GET /mcp`：当前版本不提供 SSE 流，返回 `405 Method Not Allowed`（符合 Streamable HTTP 的允许行为）。
- JSON-RPC **request**（带 `id`）：返回 `200` + JSON-RPC 响应。
- JSON-RPC **notification**（不带 `id`）：返回 `202 Accepted`，空响应体。
- 客户端可携带 `MCP-Protocol-Version` 请求头；当前支持：`2025-06-18`、`2025-11-25`。

---

## 三、协议约定

- JSON-RPC 版本：`2.0`
- MCP 生命周期：先调用 `initialize`，再发送 `notifications/initialized`
- 协议层错误：返回 JSON-RPC `error`
- 业务层错误：返回 JSON-RPC `result`，其中 `status=ERROR`

`tools/call` 标准返回（兼容 MCP）：

```json
{
  "content": [{"type":"text","text":"..."}],
  "isError": false,
  "structuredContent": {
    "status": "OK|ERROR",
    "message": "string",
    "data": {},
    "artifacts": [],
    "errorCode": "string|null"
  }
}
```

统一业务结果结构：

```json
{
  "status": "OK|ERROR",
  "message": "string",
  "data": {},
  "artifacts": [],
  "errorCode": "string|null"
}
```

---

## 四、工具列表

### 4.1 `list_projects`

- 作用：返回当前 IDE 已初始化项目列表（来源 `JuggInitializer#instanceSet`）。
- 参数：
  - `projectDir`（可选，当前版本不会用于校验）

### 4.2 `restart_app`

- 作用：重启目标项目 app。
- 参数：
  - `projectDir`（必填）
  - `serial`（可选）

设备选择策略：
- `serial` 缺失：回落 selected device。
- `serial` 非法：回落 selected device。
- 无可用设备：返回 `MCP_NO_DEVICE`。

---

## 五、curl 示例

### 5.1 initialize

```bash
curl -s -X POST "http://localhost:12320/mcp" \
  -H "Content-Type: application/json" \
  -H "MCP-Protocol-Version: 2025-06-18" \
  -d '{
    "jsonrpc":"2.0",
    "id":0,
    "method":"initialize",
    "params":{
      "protocolVersion":"2025-06-18",
      "capabilities":{},
      "clientInfo":{"name":"manual-client","version":"1.0.0"}
    }
  }'
```

### 5.2 notifications/initialized（预期 202）

```bash
curl -i -X POST "http://localhost:12320/mcp" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc":"2.0",
    "method":"notifications/initialized",
    "params":{}
  }'
```

### 5.3 tools/list

```bash
curl -s -X POST "http://localhost:12320/mcp" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc":"2.0",
    "id":1,
    "method":"tools/list",
    "params":{}
  }'
```

### 5.4 tools/call - list_projects

```bash
curl -s -X POST "http://localhost:12320/mcp" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc":"2.0",
    "id":2,
    "method":"tools/call",
    "params":{
      "name":"list_projects",
      "arguments":{
        "projectDir":"/abs/path/to/project"
      }
    }
  }'
```

### 5.5 tools/call - restart_app

```bash
curl -s -X POST "http://localhost:12320/mcp" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc":"2.0",
    "id":3,
    "method":"tools/call",
    "params":{
      "name":"restart_app",
      "arguments":{
        "projectDir":"/abs/path/to/project",
        "serial":"emulator-5554"
      }
    }
  }'
```

---

## 六、错误码（Phase 1）

- `MCP_INVALID_JSON_RPC`：JSON-RPC 格式错误
- `MCP_METHOD_NOT_SUPPORTED`：不支持的方法
- `MCP_TOOL_NOT_FOUND`：工具不存在
- `MCP_INVALID_PARAMS`：参数错误（如 `restart_app` 缺失 `projectDir`）
- `MCP_PROJECT_NOT_INITIALIZED`：项目未初始化
- `MCP_NO_DEVICE`：无可用设备
- `MCP_INTERNAL_ERROR`：内部错误

---

## 七、Claude Code / Codex CLI MCP 配置指引

> 优先使用 **Streamable HTTP 直连**，仅在客户端不支持 HTTP MCP 时再用 `npx mcp-remote`。

### 7.1 前置条件

1. 在 IDE 中打开工程并触发 Jugg 初始化（会自动启动 MCP Server）。
2. 确认本地端口可访问（默认 `12320..12329`，路径 `/mcp`）。

### 7.2 Claude Code（推荐：HTTP 直连）

使用 CLI 添加（官方推荐方式）：

```bash
claude mcp add --transport http jugg http://localhost:12320/mcp
claude mcp list
```

如需项目共享配置，可加 `--scope project` 写入项目 `.mcp.json`。

### 7.3 Codex CLI（推荐：URL 直连）

使用 CLI 添加：

```bash
codex mcp add jugg --url http://localhost:12320/mcp
codex mcp list
```

或直接写入 `~/.codex/config.toml`：

```toml
[mcp_servers.jugg]
url = "http://localhost:12320/mcp"
```

### 7.4 兼容回退：stdio -> HTTP 适配

当客户端仅支持 stdio MCP 时，使用 `mcp-remote`：

```json
{
  "mcpServers": {
    "jugg": {
      "command": "npx",
      "args": ["-y", "mcp-remote", "http://localhost:12320/mcp"]
    }
  }
}
```

```bash
node -v
npx -v
```

---

## 八、连通性排查

- 检查端口：

```bash
for p in {12320..12329}; do curl -s "http://localhost:${p}/mcp" -X POST -H "Content-Type: application/json" -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}' | grep -q '"jsonrpc":"2.0"' && echo "MCP on $p"; done
```

- 若找不到端口：确认 IDE 中 Jugg 已初始化该项目。
- 若返回 `Server not initialized. Call initialize first.`：说明客户端未先发 `initialize`。
- `list_projects` 不校验 `projectDir`，会直接返回当前 IDE 已初始化项目集合。
