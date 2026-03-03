# MCP 使用说明（当前注册工具）

> 最后核对：2026-03-03  
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
| `layout_dump` | `projectDir`; 可选 `rootLayout`, `isIncludeGone` | 导出 UI 层级（仅 App 内 ViewHierarchy JSON），`data.content` 内联返回 |
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

补充（layout_dump 语义）：
- 走 App 进程内 `ViewHierarchyServer`（`adb forward` + LocalSocket）获取 JSON 树并落盘为 `.json`。
- 成功时 `data.file` 返回本地绝对路径，`data.content` 内联返回完整 JSON 数据（无需额外读取文件），`artifacts` 里会包含 `type=json` 的产物。
- 可选参数 `rootLayout`：传入节点 `id` 值（如 `"com.example:id/content"`），仅返回该节点及其子树。未传或目标节点不存在时，返回完整层级。
- Server 可能返回内联 JSON 或远端文件路径，`layout_dump` 会统一拉齐为本地 `.json` 文件输出。
- **服务端剪枝**：App 内 ViewHierarchyServer 限制最大深度 `MAX_DEPTH=60`，最大节点数 `MAX_NODE_COUNT=5000`。超限时根节点会包含 `"truncated":true` 字段，被截断的节点自身 `tag` 为 `"truncated:node_limit"` 或 `"truncated:depth_limit"`。
- **不可见节点**：默认排除 `GONE` 节点以减少体积；设置 `isIncludeGone=true` 可包含 GONE 节点用于诊断。`INVISIBLE` 节点始终包含。注意：元素模式 `tap` 会过滤掉不可见节点（仅匹配 VISIBLE + isShown），因此 GONE/INVISIBLE 的 View 无法通过 `tap(text=...)` 定位，需借助 `layout_dump(isIncludeGone=true)` 诊断。
- **根 JSON 结构**：`{windows:[{windowType, title, root:<node>}], truncated}`。
- **节点字段（压缩输出，省略默认/空值）**：`{className, id?, text?, contentDesc?, tag?, bounds:[left,top,right,bottom], visibility?, alpha?, clickable?, enabled?, padding?:[left,top,right,bottom], children?:[], composeNodes?:[]}`。其中 `?` 表示该字段在默认值时省略：`id/text/contentDesc/tag` 为空串时省略；`visibility` 为 `"visible"` 时省略；`alpha` 为 `1.0` 时省略；`clickable` 为 `false` 时省略；`enabled` 为 `true` 时省略；`padding` 全零时省略；`children/composeNodes` 为空数组时省略。`className` 仅保留简单类名（去掉包名），如 `com.tencent.mtt.hippy.views.text.HippyTextView` → `HippyTextView`。`id` 去掉斜杠前的包名前缀，如 `com.tencent.ibg.joox:id/btn_play` → `btn_play`。`bounds` 和 `padding` 使用紧凑数组格式 `[left,top,right,bottom]`。
- 多进程应用下，客户端会优先尝试主进程 socket（`processName == packageName`），再按其余 PID 依次尝试，最后兜底兼容 socket 名 `jugg_vh`。
- 当 ViewHierarchy 路径失败时，返回 `ERROR`（不再回退 `uiautomator dump`），建议按以下场景拆分排查：
  - `packageName` 无法解析：先确认 `projectDir` 对应运行配置、应用包名与前台进程一致。
  - 明确是 socket 不可连（如 `ViewHierarchy server is unavailable` / connect failed / forward failed）：高概率是 App 侧 `ViewHierarchyServer` 未成功集成到当前运行包（未安装最新包、构建链路未带上对应集成）。
  - 其他请求失败（超时、返回体非法）：优先看 `build/jugg/log/compile_latest.log` 与 IDE 日志。
- socket 不可连的推荐动作：`restart_app` 重试一次；若仍失败，执行一次 `force_gradle_compile`（必要时轮询 `get_compile_status` 到终态）-> `compile_and_deploy` -> `restart_app` 后再试 `layout_dump`/元素模式 `tap`。

补充（tap 三模式语义）：
- **坐标模式**（`x` + `y`）：直接传入设备像素坐标，行为与原有逻辑一致。
- **百分比模式**（`xPercent` + `yPercent`，范围 0-100）：自动通过 `adb shell wm size` 获取屏幕尺寸后换算为像素坐标，优先使用 Override size。返回 `data` 中包含 `screenWidth`、`screenHeight`。
- **元素模式**（`text` / `resourceId` / `contentDesc`，可选 `className`）：所有选择器均为**精确匹配**（exact match）。走 App 内 `find_and_tap` 原子执行（查找+点击），避免 IDE 侧 dump 与点击之间的竞态；若 Server 不可用则直接返回 `ERROR`。唯一匹配时点击元素中心点；**多匹配时不执行 tap**，返回 `ERROR` + 所有匹配元素摘要（含 bounds/center）。
- 元素模式未命中时返回 `MCP_INTERNAL_ERROR`，`message` 会包含可点击候选元素摘要，便于快速改 selector。
- 元素模式命中前会过滤不可操作节点（`VISIBLE + isShown + 非零尺寸 + 有效 bounds`），避免隐藏模板节点导致误报多匹配。
- 参数使用优先级（仅在同一次调用里同时传入多种模式参数时生效）：`coordinate > percent > element`。若无匹配任何模式，返回 `MCP_INVALID_PARAMS`。
- 推荐交互顺序（Agent/Skill 指引）：优先 `layout_dump + element tap`；元素模式不适用时使用 `layout_dump + coordinate tap`；仅当 ViewHierarchy 路径明确不可用时，才退回 `screenshot + percent/coordinate tap`。

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
5. `layout_dump`/元素模式 `tap` 返回 `ViewHierarchy server is unavailable` 时，按“先 `restart_app` 一次 -> 再 `force_gradle_compile` 一次 -> 重试”的顺序处理；若仍失败，再退回 `screenshot + percent/coordinate tap`。

---

## 7. 关联文档

- 设计说明：`08_mcp_design.md`
- 测试用例：`08_mcp_test_case.md`
- 路径速查：`98_code_map.md`
