# MCP 使用说明（当前注册工具）

> 最后核对：2026-02-24  
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 服务信息

- 端口范围：`12320..12329`
- 路径：`/jugg-mcp`
- 协议：JSON-RPC `2.0`
- 支持请求头：`MCP-Protocol-Version`（`2025-06-18`、`2025-11-25`）

---

## 2. 返回约定

`tools/call` 的 `structuredContent` 统一字段：

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

## 3. 当前注册工具（以 `McpToolActionRegistry` 为准）

| 工具 | 必填参数 | 说明 |
|------|----------|------|
| `list_projects` | 无 | 列出当前 IDE 已初始化项目 |
| `restart_app` | `projectDir` | 重启目标 App |
| `compile_only` | `projectDir` | 仅编译不部署 |
| `compile_and_deploy` | `projectDir` | 编译并部署（可能异步） |
| `clean_reinstall_apk` | `projectDir` | 卸载并重装 APK |
| `force_gradle_compile` | `projectDir` | 强制 Gradle 构建（可能异步） |
| `get_compile_status` | `projectDir`, `jobId` | 查询编译任务状态 |
| `request_remote_ssh_info` | `projectDir`, `reason`, `userConsent` | 申请远端 SSH 排障信息 |
| `device_list` | `projectDir` | 列设备并标记 selected |
| `screenshot` | `projectDir` | 截图 |
| `start_record` | `projectDir` | 开始录屏（立即返回 `sessionId`） |
| `stop_record` | `projectDir`, `sessionId` | 停止录屏并拉取 mp4 产物 |
| `layout_dump` | `projectDir` | 导出 UI 层级 XML |
| `activity_stack` | `projectDir` | 读取 Activity 栈 |
| `crash_report` | `projectDir` | 收集最近崩溃摘要与完整错误日志 artifact |
| `tap` | `projectDir` + 模式参数 | 屏幕点击（三模式：坐标/百分比/元素） |

> 说明：`start_app`、`start_activity`、`emulator_list`、`start_emulator` 在代码中有 action 实现，但当前未注册到默认工具列表。

补充（录屏工具容错语义）：
- `start_record` IDeviceAdb 容易失败，走 ANDROID_HOME 的 `adb shell screenrecord` 进程托管，并由 `stop_record` 回收。
- 主机侧 `adb` 路径解析优先走 `PlatformApi.getAndroidHomePath(logger)`，再回退 `ANDROID_HOME` / `ANDROID_SDK_ROOT`。
- `stop_record` 在 `pull` 前会等待远端 mp4 落盘（最长约 10 秒），失败时返回远端文件状态与启动模式，便于定位问题。

补充（screenshot 体积优化语义）：
- 截图拉取后会在本地做上传优化：超过边长/大小阈值时，自动压缩。
- `data.file` 返回优化后的文件路径，扩展名可能为 `png/jpg/jpeg`。

补充（crash_report 输出语义）：
- `hasCrash=true` 表示在近期日志中检测到崩溃信号（如 `FATAL EXCEPTION`）。
- `crashLogs` 返回最近一段崩溃关键日志（通常 15~30 行）。
- `allErrorLogPath` 为完整错误日志路径，客户端可按需读取全文。

补充（tap 三模式语义）：
- **坐标模式**（`x` + `y`）：直接传入设备像素坐标，行为与原有逻辑一致。
- **百分比模式**（`xPercent` + `yPercent`，范围 0-100）：自动通过 `adb shell wm size` 获取屏幕尺寸后换算为像素坐标，优先使用 Override size。返回 `data` 中包含 `screenWidth`、`screenHeight`。
- **元素模式**（`text` / `resourceId` / `contentDesc`，可选 `className`）：所有选择器均为**精确匹配**（exact match），自动 `uiautomator dump` 获取 UI 层级 → 按 AND 逻辑匹配元素。唯一匹配时 tap 元素中心点；**多匹配时不执行 tap**，返回 `ERROR` + 所有匹配元素的摘要列表（含 bounds 和 center 坐标），引导 Agent 使用坐标或百分比模式进行精确点击。
- 优先级：coordinate > percent > element。若无匹配任何模式，返回 `MCP_INVALID_PARAMS`。

补充（`mcp_fetch` 清理机制）：
- MCP 拉取类工具产物默认落在 `JuggPathManager.mcpFetchDir/<toolName>/`（当前展开为 `build/jugg/mcp_fetch/<toolName>/`）。
- IDE 启动初始化后会在后台清理 `build/jugg/mcp_fetch` 下最近修改时间超过 30 天的文件，并回收空目录。

---

## 4. 异步编译调用约定

`compile_and_deploy`、`force_gradle_compile` 可能返回：
- `isFinal=false`：任务仍运行中
- `jobId`：后续用 `get_compile_status` 查询
- `logPath`：`build/jugg/log/compile_latest.log`

`get_compile_status` 在 `status=running` 时会返回：
- `pollIntervalSuggestedMs`：建议轮询间隔（毫秒），客户端按该字段轮询

---

## 5. 常见错误码

- `MCP_INVALID_JSON_RPC`
- `MCP_METHOD_NOT_SUPPORTED`
- `MCP_TOOL_NOT_FOUND`
- `MCP_INVALID_PARAMS`
- `MCP_PROJECT_NOT_INITIALIZED`
- `MCP_NO_DEVICE`
- `MCP_INTERNAL_ERROR`

---

## 6. 连通性与排查

1. 先确认 IDE 已初始化该项目（`list_projects`）。  
2. 参数异常先对照 `tools/list` 返回的 `inputSchema`。  
3. 设备类工具失败先执行 `device_list`。  
4. 编译类异步任务卡住时，用 `get_compile_status` + `compile_latest.log`。

---

## 7. 关联文档

- 设计说明：`08_mcp_design.md`
- 测试用例：`08_mcp_test_case.md`
- 路径速查：`98_code_map.md`
