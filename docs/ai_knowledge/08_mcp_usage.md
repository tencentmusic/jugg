# Jugg MCP 使用说明（Phase 1-3）

> 更新时间: 2026-02-08  
> 传输协议: MCP Streamable HTTP + JSON-RPC 2.0  
> 当前工具: `list_projects`, `restart_app`, `emulator_list`, `start_emulator`, `compile_only`, `compile_and_deploy`, `clean_reinstall_apk`, `force_gradle_compile`, `device_list`, `app_start`, `tap`, `screenshot`, `record`, `layout_dump`

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

### 4.3 `emulator_list`

- 作用：列出本机 Android SDK 下可用 AVD 列表，用于调用 `start_emulator` 前选择机型。
- 参数：
  - `projectDir`（必填）

返回说明：
- `data.avds[]`：AVD 列表
  - `name`：AVD 名称
  - `isRunning`：当前是否检测为运行中
  - `serial`：运行中时可能返回对应 emulator serial

### 4.4 `start_emulator`

- 作用：启动 Android Emulator（AVD 虚拟机），用于无可用设备时拉起测试设备。
- 参数：
  - `projectDir`（必填）
  - `avdName`（可选，缺失时默认使用 `emulator -list-avds` 第一项）
  - `waitForDeviceSec`（可选，默认 `45`，范围 `0..300`）

返回说明：
- `data.avdName`：实际启动的 AVD 名称
- `data.started`：是否已发起启动
- `data.waitedSec`：等待在线设备检测的秒数
- `data.emulatorSerial`：若在等待窗口内检测到新在线模拟器，则返回该 serial

### 4.5 `compile_only`

- 作用：仅执行 Jugg 增量编译，不部署到设备。用于编译检查或无设备场景。
- 参数：
  - `projectDir`（必填）

### 4.6 `compile_and_deploy`

- 作用：先编译，成功后部署到设备。正常迭代的默认路径。
- 参数：
  - `projectDir`（必填）

### 4.7 `clean_reinstall_apk`

- 作用：清除应用数据并重新安装 APK（Jugg 存储在 code_cache 中的增量部署文件会重新部署）。
- 参数：
  - `projectDir`（必填）

### 4.8 `device_list`

- 作用：返回当前 connected device 列表及 selected 标记。
- 参数：
  - `projectDir`（必填）

### 4.9 `screenshot`

- 作用：对目标设备执行截图并回传本地产物路径。
- 参数：
  - `projectDir`（必填）
  - `serial`（可选，缺失/非法时回落 selected device）

### 4.10 `record`

- 作用：对目标设备录屏并回传本地产物路径。
- 参数：
  - `projectDir`（必填）
  - `serial`（可选，缺失/非法时回落 selected device）
  - `durationSec`（可选，默认 `10`，范围 `1..180`）

### 4.11 `layout_dump`

- 作用：导出当前 UI 层级 XML 并回传本地产物路径。
- 参数：
  - `projectDir`（必填）
  - `serial`（可选，缺失/非法时回落 selected device）

### 4.12 `app_start`

- 作用：启动指定 Activity（或默认主 Activity）。
- 参数：
  - `projectDir`（必填）
  - `serial`（可选，缺失/非法时回落 selected device）
  - `packageName`（可选，默认使用当前 Jugg 目标包名）
  - `activity`（可选，支持 `.MainActivity` 或全限定名）

### 4.13 `tap`

- 作用：在目标设备执行坐标点击。
- 参数：
  - `projectDir`（必填）
  - `serial`（可选，缺失/非法时回落 selected device）
  - `x`（必填）
  - `y`（必填）

### 4.14 产物目录

- 所有 fetch 类型工具统一写入：`$PROJECT_DIR/build/jugg/mcp_fetch/<tool>/`
- 当前包含子目录：`screenshot/`、`record/`、`layout_dump/`

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

### 5.6 tools/call - device_list

```bash
curl -s -X POST "http://localhost:12320/mcp" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc":"2.0",
    "id":4,
    "method":"tools/call",
    "params":{
      "name":"device_list",
      "arguments":{
        "projectDir":"/abs/path/to/project"
      }
    }
  }'
```

### 5.7 tools/call - screenshot

```bash
curl -s -X POST "http://localhost:12320/mcp" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc":"2.0",
    "id":5,
    "method":"tools/call",
    "params":{
      "name":"screenshot",
      "arguments":{
        "projectDir":"/abs/path/to/project",
        "serial":"emulator-5554"
      }
    }
  }'
```

### 5.8 tools/call - record

```bash
curl -s -X POST "http://localhost:12320/mcp" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc":"2.0",
    "id":6,
    "method":"tools/call",
    "params":{
      "name":"record",
      "arguments":{
        "projectDir":"/abs/path/to/project",
        "serial":"emulator-5554",
        "durationSec":12
      }
    }
  }'
```

### 5.9 tools/call - layout_dump

```bash
curl -s -X POST "http://localhost:12320/mcp" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc":"2.0",
    "id":7,
    "method":"tools/call",
    "params":{
      "name":"layout_dump",
      "arguments":{
        "projectDir":"/abs/path/to/project",
        "serial":"emulator-5554"
      }
    }
  }'
```

### 5.10 tools/call - app_start

```bash
curl -s -X POST "http://localhost:12320/mcp" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc":"2.0",
    "id":8,
    "method":"tools/call",
    "params":{
      "name":"app_start",
      "arguments":{
        "projectDir":"/abs/path/to/project",
        "serial":"emulator-5554",
        "activity":".MainActivity"
      }
    }
  }'
```

### 5.11 tools/call - tap

```bash
curl -s -X POST "http://localhost:12320/mcp" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc":"2.0",
    "id":9,
    "method":"tools/call",
    "params":{
      "name":"tap",
      "arguments":{
        "projectDir":"/abs/path/to/project",
        "serial":"emulator-5554",
        "x":540,
        "y":530
      }
    }
  }'
```

---

## 六、错误码（Phase 1-3）

- `MCP_INVALID_JSON_RPC`：JSON-RPC 格式错误
- `MCP_METHOD_NOT_SUPPORTED`：不支持的方法
- `MCP_TOOL_NOT_FOUND`：工具不存在
- `MCP_INVALID_PARAMS`：参数错误（如工具缺失 `projectDir`）
- `MCP_PROJECT_NOT_INITIALIZED`：项目未初始化
- `MCP_NO_DEVICE`：无可用设备
- `MCP_INTERNAL_ERROR`：内部错误

推荐：Agent 执行自动化流程时优先使用 MCP 工具链（`app_start`、`tap`、`layout_dump`、`screenshot`、`record`），避免直接调用外部 adb。

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
- `screenshot`/`record`/`layout_dump` 依赖设备在线与 adb 可用，失败时可先检查 `device_list`。
- 自动点击建议：先 `app_start`，再调用 `tap`，必要时增加短暂延时和重复点击（例如 2 次）提升稳定性。
