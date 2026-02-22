# Jugg MCP 使用说明（精简版）

> 说明：本文仅保留服务行为、工具用途、关键参数与错误码。
> 如与实现不一致，以代码为准（`main/src/main/java/com/sickworm/intellij/jugg/mcp/`）。

---

## 一、服务信息

- 服务端口范围：`12320..12329`
- HTTP 路径：`/jugg-mcp`
- Content-Type：`application/json`
- 生命周期：跟随 `JuggInitializer`（项目初始化时启动，全部释放后停止）

---

## 二、协议与返回约定

- JSON-RPC 版本：`2.0`
- MCP 生命周期：`initialize` → `notifications/initialized`
- `POST /jugg-mcp`：请求/通知入口
- `GET /jugg-mcp`：返回 `405 Method Not Allowed`
- request（有 `id`）返回 `200`
- notification（无 `id`）返回 `202`
- 支持请求头：`MCP-Protocol-Version`（`2025-06-18`、`2025-11-25`）

统一业务结果（`structuredContent`）结构：

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

## 三、工具清单（当前注册）

> 参数校验以 `tools/list` 返回的 `inputSchema` 为准。

| 工具 | 主要用途 | 必填参数 | 常用可选参数 |
|------|----------|----------|--------------|
| `list_projects` | 列出当前 IDE 已初始化项目 | 无 | 无 |
| `restart_app` | 重启目标项目 App | `projectDir` | `serial` |
| `compile_only` | 仅执行 Jugg 增量编译 | `projectDir` | 无 |
| `compile_and_deploy` | 编译并部署（支持异步返回） | `projectDir` | 无 |
| `clean_reinstall_apk` | 卸载并重装 APK（清空数据） | `projectDir` | 无 |
| `force_gradle_compile` | 触发 Gradle 回退编译（支持异步返回） | `projectDir` | 无 |
| `get_compile_status` | 查询编译任务状态 | `projectDir`, `jobId` | 无 |
| `request_remote_ssh_info` | 获取远端排障 SSH 信息（需 userConsent + IDE 二次确认） | `projectDir`, `reason`, `userConsent` | `requestedBy` |
| `device_list` | 获取设备列表与 selected 标记 | `projectDir` | 无 |
| `screenshot` | 截图并返回产物路径 | `projectDir` | `serial` |
| `record` | 录屏并返回产物路径 | `projectDir` | `serial`, `durationSec` |
| `layout_dump` | 导出 UI 层级 XML | `projectDir` | `serial` |
| `activity_stack` | 获取当前 Activity 栈信息 | `projectDir` | `serial` |
| `start_app` | 启动应用默认入口 | `projectDir` | `serial`, `packageName` |
| `start_activity` | 按 intent 参数启动指定 Activity | `projectDir` | `serial`, `packageName`, `activity`, `action`, `data`, `extras` |
| `tap` | 坐标点击 | `projectDir`, `x`, `y` | `serial` |

### 3.1 异步编译工具约定

`compile_and_deploy` 与 `force_gradle_compile` 可能返回 `isFinal=false`，此时必须继续调用 `get_compile_status(jobId)` 查询终态。

---

## 四、错误码

- `MCP_INVALID_JSON_RPC`：JSON-RPC 格式错误
- `MCP_METHOD_NOT_SUPPORTED`：不支持的方法
- `MCP_TOOL_NOT_FOUND`：工具不存在
- `MCP_INVALID_PARAMS`：参数错误
- `MCP_PROJECT_NOT_INITIALIZED`：项目未初始化
- `MCP_NO_DEVICE`：无可用设备
- `MCP_INTERNAL_ERROR`：内部错误

---

## 五、客户端配置指引

### 5.1 Claude Code（HTTP 直连）

```bash
claude mcp add --transport http jugg-mcp http://localhost:12320/jugg-mcp
claude mcp list
```

### 5.2 Codex CLI（URL 直连）

```bash
codex mcp add jugg-mcp --url http://localhost:12320/jugg-mcp
codex mcp list
```

或配置 `~/.codex/config.toml`：

```toml
[mcp_servers."jugg-mcp"]
url = "http://localhost:12320/jugg-mcp"
```

### 5.3 兼容回退（stdio -> HTTP）

当客户端仅支持 stdio MCP 时，可使用 `mcp-remote`：

```json
{
  "mcpServers": {
    "jugg-mcp": {
      "command": "npx",
      "args": ["-y", "mcp-remote", "http://localhost:12320/jugg-mcp"]
    }
  }
}
```

---

## 六、连通性排查（无 curl 版）

- 若 MCP 工具不可见：先确认 IDE 已初始化该项目。
- 若调用失败且报未初始化：先执行 MCP 初始化流程（`initialize` + `notifications/initialized`）。
- 若截图/录屏/布局导出失败：先检查 `device_list` 是否有在线设备。
- 自动化操作建议：先 `start_activity`，再 `tap`，必要时短延时+重复点击提升稳定性。
