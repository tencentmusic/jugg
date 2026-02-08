# Jugg MCP 使用说明（Phase 1）

> 更新时间: 2026-02-08  
> 协议: MCP over HTTP + JSON-RPC 2.0  
> 当前工具: `list_projects`, `restart_app`

---

## 一、服务信息

- 服务端口范围：`12320..12329`
- HTTP 路径：`/mcp`
- Content-Type：`application/json`

说明：
- 服务生命周期跟随 `JuggInitializer`（项目初始化时启动，全部释放后停止）。

---

## 二、协议约定

- JSON-RPC 版本：`2.0`
- MCP 生命周期：必须先调用 `initialize`，再发送 `notifications/initialized`
- 协议层错误：返回 `error`
- 业务层错误：返回 `result`，其中 `status=ERROR`

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

## 三、工具列表

### 3.1 `list_projects`

- 作用：返回当前 IDE 已初始化项目列表（来源 `JuggInitializer#instanceSet`）。
- 参数：
  - `projectDir`（可选，当前版本不会用于校验）

### 3.2 `restart_app`

- 作用：重启目标项目 app。
- 参数：
  - `projectDir`（必填）
  - `serial`（可选）

设备选择策略：
- `serial` 缺失：回落 selected device。
- `serial` 非法：回落 selected device。
- 无可用设备：返回 `MCP_NO_DEVICE`。

---

## 四、curl 示例

### 4.1 tools/list

> 调用前请先执行一次 `initialize`。

```bash
curl -s -X POST "http://localhost:12320/mcp" \
  -H "Content-Type: application/json" \
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

### 4.2 tools/call - list_projects

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

### 4.3 tools/call - restart_app

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

## 五、错误码（Phase 1）

- `MCP_INVALID_JSON_RPC`：JSON-RPC 格式错误
- `MCP_METHOD_NOT_SUPPORTED`：不支持的方法
- `MCP_TOOL_NOT_FOUND`：工具不存在
- `MCP_INVALID_PARAMS`：参数错误（如 `restart_app` 缺失 `projectDir`）
- `MCP_PROJECT_NOT_INITIALIZED`：项目未初始化
- `MCP_NO_DEVICE`：无可用设备
- `MCP_INTERNAL_ERROR`：内部错误

---

## 六、Claude Code / Codex CLI MCP 配置指引

> 建议优先使用 **stdio→HTTP 适配器** 方式接入（Jugg MCP 当前为 HTTP 本地服务）。

### 6.1 前置条件

1. 在 IDE 中打开工程并触发 Jugg 初始化（会自动启动 MCP Server）。
2. 确认本地端口可访问（默认 `12320..12329`，路径 `/mcp`）。
3. 可先用 `tools/mcp_smoke.sh` 做本地自检：

```bash
tools/mcp_smoke.sh --project-dir /abs/path/to/project
```

### 6.2 Claude Code 配置（示例）

方式 A：客户端原生支持 HTTP MCP 时，直接配置 URL（推荐）。

方式 B：如果仅支持 stdio MCP，使用 `mcp-remote` 适配器。

```json
{
  "mcpServers": {
    "jugg": {
      "command": "npx",
      "args": [
        "-y",
        "mcp-remote",
        "http://localhost:12320/mcp"
      ]
    }
  }
}
```

> 提示：Claude Code 不同版本配置文件路径可能不同，请以该版本官方文档/`--help` 为准。

### 6.3 Codex CLI 配置（示例）

在 `~/.codex/config.toml` 中新增 MCP Server：

```toml
[mcp_servers.jugg]
command = "npx"
args = ["-y", "mcp-remote", "http://localhost:12320/mcp"]
```

> 若你使用的 Codex CLI 版本已支持直接 HTTP MCP，可改为原生 HTTP 配置；字段名以当前版本文档为准。

### 6.4 连通性排查

- 检查端口：

```bash
for p in {12320..12329}; do curl -s "http://localhost:${p}/mcp" -X POST -H "Content-Type: application/json" -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}' | grep -q '"jsonrpc":"2.0"' && echo "MCP on $p"; done
```

- 若找不到端口：确认 IDE 中 Jugg 已初始化该项目。
- 若返回 `Server not initialized. Call initialize first.`：说明客户端未按 MCP 生命周期先发 `initialize`。
- `list_projects` 不校验 `projectDir`，会直接返回当前 IDE 已初始化项目集合。
