# MCP 使用说明（当前注册工具）

> 最后核对：2026-03-07  
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
| `layout_dump` | `projectDir`; 可选 `rootLayout`, `isIncludeGone`, `isAllWindows` | 导出 UI 层级（仅 App 内 ViewHierarchy JSON），`data.content` 按固定阈值内联返回 |
| `layout_verify` | `projectDir`, `target`; 需提供 `asserts` 或 `relation` 之一；可选 `dumpFile`, `target2` | 验证 UI 元素属性或元素间关系（默认自动快照；可指定历史 dump） |
| `activity_stack` | `projectDir` | 读取 Activity 栈 |
| `crash_report` | `projectDir` | 收集最近崩溃摘要与完整错误日志 artifact |
| `tap` | `projectDir` + `action` + 模式参数 | 屏幕触控（`tap`/`longPress`/`swipe`） |

补充（App 在线等待阻塞）：
- `restart_app`、`compile_and_deploy`、`force_gradle_compile`、`clean_reinstall_apk` 在成功路径会后置等待 App 在线（判断口径：`deployStateManager.updateDeployState().isReadyDeploy`）。
- `compile_and_deploy` / `force_gradle_compile` 若走异步返回，在线等待会体现在最终 `get_compile_status` 结果里（最终可能因为 App 未就绪而失败）。
- `layout_dump`、`layout_verify`（live 模式）、`activity_stack`、`screenshot`、`tap`、`start_record`、`stop_record` 在执行前会先等待 App 在线：每 100ms 检查一次，最长等待 10s。
- 运行态工具执行顺序：参数完整性/组合合法性校验 -> `projectDir` 初始化态校验 -> App 在线校验 -> 业务执行。参数类错误优先返回 `MCP_INVALID_PARAMS`，避免被 `app not ready` 覆盖。
- 若前置等待过程中发生过实际等待（并非首检即 ready），且本次工具调用返回失败，会自动重试最多 3 次，重试间隔 2s（仅用于内部/瞬时失败）。

> 说明：`start_app`、`start_activity`、`emulator_list`、`start_emulator` 在代码中有 action 实现，但当前未注册到默认工具列表。

补充（录屏工具容错语义）：
- `start_record` IDeviceAdb 容易失败，走 ANDROID_HOME 的 `adb shell screenrecord` 进程托管，并由 `stop_record` 回收。
- 主机侧 `adb` 路径解析优先走 `PlatformApi.getAndroidHomePath(logger)`，再回退 `ANDROID_HOME` / `ANDROID_SDK_ROOT`。
- `stop_record` 在 `pull` 前会等待远端 mp4 落盘（最长约 10 秒），失败时返回远端文件状态与启动模式，便于定位问题。

补充（screenshot 体积优化语义）：
- 截图拉取后会在本地做上传优化：超过边长/大小阈值时，自动压缩。
- `data.file` 返回优化后的文件路径，扩展名可能为 `png/jpg/jpeg`。

补充（crash_report 输出语义）：
- 目标进程强过滤：`crashLogs` 仅保留目标包名/进程名与目标 PID 相关日志。
- 采集优先级：先读 `logcat -b crash`，仅当未检测到崩溃信号时再补读 `logcat -b main`。
- `hasCrash=true` 表示检测到崩溃信号（如 `FATAL EXCEPTION` / `Fatal signal`）。
- `hasCrash=false` 时返回 `data.reason`，明确"无崩溃"原因（例如目标进程未运行）。
- `allErrorLogPath` 持续保留原始采集日志（artifact）以供深度排障。

补充（layout_dump 语义）：
- 走 App 进程内 `ViewHierarchyServer`（`adb forward` + LocalSocket）获取 JSON 树并落盘为 `.json`。
- 成功时 `data.file` 返回本地绝对路径，`data.content` 内联返回完整 JSON 数据（无需额外读取文件），`artifacts` 里会包含 `type=json` 的产物。
- 可选参数 `rootLayout`：传入节点 `id` 值（推荐 short id，如 `"content"`），仅返回该节点及其子树。未传或目标节点不存在时，返回完整层级。
- `topWindowOnly=true`（默认）时，服务端优先使用当前 top resumed Activity 对应窗口，避免误选到后台 Activity 窗口。
- `data.content` 的内联阈值固定为 16KB，不再接受外部参数调整。
- Server 可能返回内联 JSON 或远端文件路径，`layout_dump` 会统一拉齐为本地 `.json` 文件输出。
- 返回中新增 `data.contentBytes`、`data.inlineOmitted`、`data.inlineThresholdKb`。当 `contentBytes > inlineThresholdKb * 1024` 时，`data.content` 被省略，但 `data.file` 仍可读取完整 JSON。
- `message` 不再固定为 executed successfully，而是摘要信息：窗口数、顶层窗口标题、节点数、是否截断。
- **服务端剪枝**：App 内 ViewHierarchyServer 限制最大深度 `MAX_DEPTH=60`，最大节点数 `MAX_NODE_COUNT=5000`。超限时根节点会包含 `"truncated":true` 字段，被截断的节点自身 `tag` 为 `"truncated:node_limit"` 或 `"truncated:depth_limit"`。
- **不可见节点**：默认排除 `GONE` 节点以减少体积；设置 `isIncludeGone=true` 可包含 GONE 节点用于诊断。`INVISIBLE` 节点始终包含。注意：元素模式 `tap` 会过滤掉不可见节点（仅匹配 VISIBLE + isShown），因此 GONE/INVISIBLE 的 View 无法通过 `tap(text=...)` 定位，需借助 `layout_dump(isIncludeGone=true)` 诊断。
- **根 JSON 结构**：`{windows:[{windowType, title, root:<node>}], truncated}`。
- **节点字段（压缩输出，省略默认/空值）**：`{className, id?, text?, contentDesc?, tag?, bounds:[left,top,right,bottom], visibility?, alpha?, clickable?, enabled?, padding?:[left,top,right,bottom], children?:[], composeNodes?:[]}`。其中 `?` 表示该字段在默认值时省略：`id/text/contentDesc/tag` 为空串时省略；`visibility` 为 `"visible"` 时省略；`alpha` 为 `1.0` 时省略；`clickable` 为 `false` 时省略；`enabled` 为 `true` 时省略；`padding` 全零时省略；`children/composeNodes` 为空数组时省略。`className` 仅保留简单类名（去掉包名），如 `com.tencent.mtt.hippy.views.text.HippyTextView` → `HippyTextView`。`id` 去掉斜杠前的包名前缀，如 `com.example.application:id/btn_play` → `btn_play`。`bounds` 和 `padding` 使用紧凑数组格式 `[left,top,right,bottom]`。
- 多进程应用下，客户端会优先尝试主进程 socket（`processName == packageName`），再按其余 PID 依次尝试，最后兜底兼容 socket 名 `jugg_vh`。
- 当 ViewHierarchy 路径失败时，返回 `ERROR`（不再回退 `uiautomator dump`），建议按以下场景拆分排查：
  - `packageName` 无法解析：先确认 `projectDir` 对应运行配置、应用包名与前台进程一致。
  - 明确是 socket 不可连（如 `ViewHierarchy server is unavailable` / connect failed / forward failed）：高概率是 App 侧 `ViewHierarchyServer` 未成功集成到当前运行包（未安装最新包、构建链路未带上对应集成）。
  - 其他请求失败（超时、返回体非法）：优先看 `build/jugg/log/compile_latest.log` 与 IDE 日志。
- socket 不可连的推荐动作：`restart_app` 重试一次；若仍失败，执行一次 `force_gradle_compile`（必要时轮询 `get_compile_status` 到终态）-> `compile_and_deploy` -> `restart_app` 后再试 `layout_dump`/元素模式 `tap`。

补充（layout_verify 语义）：
- 支持三种内部模式：
  - **explicit_dump**：传 `dumpFile` 绝对路径，回放历史快照。
  - **auto_dump**：不传 `dumpFile` 时默认模式；工具会先抓取一份最新布局快照再断言。
  - **live query**：当断言属性是 dump 不支持项（当前为 `textSizeSp`）时，自动切换 live。
- 每次调用执行一组 `asserts`（数组，至少 1 项）或一个 `relation`，两者互斥；均不传时返回 `MCP_INVALID_PARAMS`。
- **target / target2 选择器**：至少提供 `resourceId` / `text` / `contentDesc` / `className` 之一。`resourceId` 支持 short id（如 `btn_play`）。`relation` 模式必须同时传 `target2`。
- **asserts[i] 参数**：
  - `property`：`exists` / `visibility` / `clickable` / `enabled` / `text` / `bounds.width` / `bounds.height` / `bounds.left` / `bounds.top` / `bounds.right` / `bounds.bottom` / `alpha` / `textColor` / `textSizeSp`（仅 live）/ `padding.left` / `padding.top` / `padding.right` / `padding.bottom`
  - `op`：`eq`（默认）/ `neq` / `gte` / `lte` / `gt` / `lt` / `contains` / `matches`
  - `value`：期望值（字符串）
  - `unit`：`dp` 或 `px`（坐标/尺寸类属性默认 `px`）
- **relation 参数**：
  - `type`：`spacing` / `alignment` / `overlap` / `containment` / `order`
  - `direction`：`horizontal` 或 `vertical`（用于 spacing / alignment / order）
  - `expected`：期望值（用于 spacing，数值）
  - `tolerance`：容差（用于 spacing，默认 0）
  - `unit`：`dp` 或 `px`
- 返回精简为：`data.result`、`data.message`、`data.items[]`（每项仅 `index/result/message`）。
- 批量聚合 `data.result` 取值：`PASS | PARTIAL_FAIL | FAIL | ERROR`。
- 元素未找到时返回 `data.result=ERROR`，`data.candidates` 列出最多 5 个候选，并带 `score/reason`（用于拼写纠错）。
- dumpFile 模式的 dp 换算依赖 dump JSON 根节点的 `deviceInfo.density`。
- `layout_verify`（auto_dump/live）在执行前同样会先等待 App 在线（同 `layout_dump`）。

补充（tap 三模式语义）：
- `action` 支持：`tap`（默认）、`longPress`、`swipe`。
- **坐标模式**（`x` + `y`）：直接传入设备像素坐标，行为与原有逻辑一致。
- **百分比模式**（`xPercent` + `yPercent`，范围 0-100）：自动通过 `adb shell wm size` 获取屏幕尺寸后换算为像素坐标，优先使用 Override size。返回 `data` 中包含 `screenWidth`、`screenHeight`。
- 百分比换算结果会做边界钳制到 `[0, width-1]` / `[0, height-1]`，避免 100% 落到越界坐标。
- **元素模式**（`text` / `resourceId` / `contentDesc`，可选 `className`）：所有选择器均为**精确匹配**（exact match）。`resourceId` 推荐传 short id（如 `btn_play`）；full id 为兼容回退。走 App 内原子执行（tap: `find_and_tap`，longPress: `find_and_long_press`）。唯一匹配时执行动作；**多匹配时不执行**，返回 `ERROR` + 所有匹配元素摘要（含 bounds/center）。
- `swipe` 仅支持坐标/百分比两种模式，且必须提供起点与终点：坐标模式需要 `x/y/endX/endY`，百分比模式需要 `xPercent/yPercent/endXPercent/endYPercent`。元素模式下 `swipe` 会直接返回 `MCP_INVALID_PARAMS`。
- `duration` 参数：
  - `longPress`：按住时长（默认 500ms）
  - `swipe`：滑动时长（默认 300ms）
  - 最小值 50ms（低于该值会按 50ms 处理）
- 元素模式未命中时返回 `MCP_INTERNAL_ERROR`，`message` 会包含与本次输入 selector 类型一致的候选元素摘要（例如仅传 `resourceId` 时只返回 `resource-id` 候选），便于快速改 selector。
- 元素模式命中前会过滤不可操作节点（`VISIBLE + isShown + 非零尺寸 + 有效 bounds`），避免隐藏模板节点导致误报多匹配。
- 元素模式成功时 `data.matchedElement` 为结构化对象：`{text, className, resourceId, contentDesc, bounds:[l,t,r,b], centerX, centerY}`。
- `tap` 在执行点击前会额外检查前台 `topActivity` 稳定性：要求连续 2 次检查结果均为同一 `topActivity` 且状态为 `onResume/RESUMED`，两次检查间隔固定 1 秒；最多等待 5 秒，超时后继续执行点击。若工具最终失败，`message` 会附带当前 `topActivity` 不稳定提示。
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

> 仅在"连通性/上下文异常排查"场景使用以下步骤；正常使用无需把 `list_projects` / `device_list` 作为固定 preflight。  

1. 先确认 IDE 已初始化该项目（`list_projects`）。  
2. 参数异常先对照 `tools/list` 返回的 `inputSchema`。  
3. 设备类工具失败时再执行 `device_list`。  
4. 编译类异步任务卡住时，用 `get_compile_status` + `compile_latest.log`。
5. `layout_dump`/`layout_verify`（live 模式）/元素模式 `tap` 返回 `ViewHierarchy server is unavailable` 时，按"先 `restart_app` 一次 -> 再 `force_gradle_compile` 一次 -> 重试"的顺序处理；若仍失败，再退回 `screenshot + percent/coordinate tap`。

---

## 7. 关联文档

- 设计说明：`08_mcp_design.md`
- 测试用例：`08_mcp_test_case.md`
- 路径速查：`98_code_map.md`
