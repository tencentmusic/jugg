# MCP 使用说明（当前注册工具）

> 最后核对：2026-04-05
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
| `restart` | `projectDir`; 可选 `tap_actions` | 重启目标 App，并可在启动后串行执行 `tap/longPress/swipe` 导航步骤 |
| `compile` | `projectDir` | 仅编译不部署 |
| `deploy` | `projectDir` | 编译并部署（可能异步） |
| `reinstall` | `projectDir` | 卸载并重装 APK |
| `gradle-build` | `projectDir` | 强制 Gradle 构建（可能异步） |
| `get_compile_status` | `projectDir`, `jobId` | 查询编译任务状态 |
| `ssh-info` | `projectDir`, `reason`, `userConsent` | 申请远端 SSH 排障信息 |
| `devices` | `projectDir` | 列设备并标记 selected |
| `screenshot` | `projectDir` | 截图 |
| `record-start` | `projectDir` | 开始录屏（立即返回 `sessionId`） |
| `record-stop` | `projectDir`, `sessionId` | 停止录屏并拉取 mp4 产物 |
| `layout-dump` | `projectDir`; 可选 `rootLayout`, `isIncludeGone`, `isAllWindows` | 导出 UI 层级 JSON（app 内 ViewHierarchy），`data.content` 按固定阈值内联返回 |
| `view-locate` | `projectDir`, `target` | 查找单个 UI 元素并返回位置和尺寸（支持模糊匹配） |
| `view-inspect` | `projectDir`, `target`, `expressions` | 通过反射调用 View getter 方法链查询属性值（返回原始值，Agent 自行判断） |
| `activity-stack` | `projectDir` | 读取 Activity 栈 |
| `crash-report` | `projectDir` | 收集最近崩溃摘要与完整错误日志 artifact |
| `tap` | `projectDir` + `action` + 模式参数 | 屏幕触控（`tap`/`longPress`/`swipe`） |

补充（App 在线等待阻塞）：
- `restart`、`deploy`、`gradle-build`、`reinstall` 在成功路径会后置等待 App 在线（判断口径：`deployStateManager.updateDeployState().isReadyDeploy`）。
- `deploy` / `gradle-build` 若走异步返回，在线等待会体现在最终 `get_compile_status` 结果里（最终可能因为 App 未就绪而失败）。
- `activity-stack`、`screenshot`、`tap`、`record-start`、`record-stop` 在执行前会先等待 App 在线：每 100ms 检查一次，最长等待 10s。
- 运行态工具执行顺序：参数完整性/组合合法性校验 -> `projectDir` 初始化态校验 -> App 在线校验 -> 业务执行。参数类错误优先返回 `MCP_INVALID_PARAMS`，避免被 `app not ready` 覆盖。
- 若前置等待过程中发生过实际等待（并非首检即 ready），且本次工具调用返回失败，会自动重试最多 3 次，重试间隔 2s（仅用于内部/瞬时失败）。
- `restart` 传入 `tap_actions` 时，会在 App ready 后按顺序执行步骤：单步参数与 `tap` 工具保持一致（`action=tap|longPress|swipe`，模式支持坐标/百分比/元素；其中 `swipe` 仅支持坐标/百分比）。若步骤为元素模式且遇到 `No matching UI element found`，会做最多 2 次短暂重试；任一步失败会整体返回 `ERROR`，并在 `data.failedStep` 标出失败步骤。

> 说明：`start_app`、`start_activity`、`emulator_list`、`start_emulator` 在代码中有 action 实现，但当前未注册到默认工具列表。

补充（录屏工具容错语义）：
- `record-start` IDeviceAdb 容易失败，走 ANDROID_HOME 的 `adb shell screenrecord` 进程托管，并由 `record-stop` 回收。
- 主机侧 `adb` 路径解析优先走 `PlatformApi.getAndroidHomePath(logger)`，再回退 `ANDROID_HOME` / `ANDROID_SDK_ROOT`。
- `record-stop` 在 `pull` 前会等待远端 mp4 落盘（最长约 10 秒），失败时返回远端文件状态与启动模式，便于定位问题。

补充（screenshot 体积优化语义）：
- 截图拉取后会在本地做上传优化：超过边长/大小阈值时，自动压缩。
- `data.file` 返回优化后的文件路径，扩展名可能为 `png/jpg/jpeg`。

补充（crash-report 输出语义）：
- 目标进程强过滤：`crashLogs` 仅保留目标包名/进程名与目标 PID 相关日志。
- 采集优先级：先读 `logcat -b crash`，仅当未检测到崩溃信号时再补读 `logcat -b main`。
- `hasCrash=true` 表示检测到崩溃信号（如 `FATAL EXCEPTION` / `Fatal signal`）。
- `hasCrash=false` 时返回 `data.reason`，明确"无崩溃"原因（例如目标进程未运行）。
- `allErrorLogPath` 持续保留原始采集日志（artifact）以供深度排障。

---

补充（layout-dump 语义）：
- 走 App 进程内 `ViewHierarchyServer`（`adb forward` + LocalSocket）获取 JSON 树。
- **输出格式为 HTML**：成功时 `data.file` 指向本地 `.html` 文件，`artifacts` 包含 `type=html` 产物。HTML 格式信息密度更高，适合 LLM 消费。同时 `data.jsonFile` 保留原始 JSON 文件路径供内部工具使用（如 `figma-layout-verify`）。
- **虚拟节点裁剪（HTML 侧）**：无语义内容的结构性节点（无 `id`/`text`/`contentDesc`、不可点击，且 `alpha=0` 或属于通用容器类如 FrameLayout/LinearLayout 等）在 HTML 生成时自动裁剪，子节点上提。窗口根节点始终保留。`_vir_id_*` 前缀的自动生成 id 不渲染到 HTML 属性中。
- `data.contentBytes` 表示 HTML 内容的字节大小。
- 可选参数 `rootLayout`：传入节点 `id` 值（推荐 short id，如 `"content"`），仅返回该节点及其子树。未传或目标节点不存在时，返回完整层级。
- `topWindowOnly=true`（默认）时，服务端优先使用当前 top resumed Activity 对应窗口，避免误选到后台 Activity 窗口。
- `message` 为摘要信息：窗口数、顶层窗口标题、节点数、可点击节点数、是否截断。
- **服务端剪枝**：App 内 ViewHierarchyServer 限制最大深度 `MAX_DEPTH=60`，最大节点数 `MAX_NODE_COUNT=5000`。超限时根节点会包含 `"truncated":true` 字段，被截断的节点自身 `tag` 为 `"truncated:node_limit"` 或 `"truncated:depth_limit"`。
- **不可见节点**：默认排除 `GONE` 节点以减少体积；设置 `isIncludeGone=true` 可包含 GONE 节点用于诊断。`INVISIBLE` 节点始终包含。注意：元素模式 `tap` 会过滤掉不可见节点（仅匹配 VISIBLE + isShown），因此 GONE/INVISIBLE 的 View 无法通过 `tap(text=...)` 定位，需借助 `layout-dump(isIncludeGone=true)` 诊断。
- **根 JSON 结构**：`{windows:[{windowType, title, root:<node>}], truncated, deviceInfo:{density, scaledDensity}}`。
- **单位统一为 dp**：所有 `bounds` 和 `padding` 字段已在 IDE 端自动转换为 dp 单位（公式：`dp = (int)(px / density)`，取整），与 `tap` 百分比模式保持一致。原始像素值由 App 端采集，转换在 IDE 端完成。
- **虚拟 ID 生成**：对于没有 resource id 的控件，自动生成虚拟 id，格式为 `_vir_id_<index>`（如 `_vir_id_0`, `_vir_id_1`）。虚拟 id 按深度优先遍历顺序分配，每次 dump 重新计数。虚拟 id 可直接用于 `tap` 的 `resourceId` 参数，无需依赖 `text` 或 `contentDesc`。
- **节点字段（压缩输出，省略默认/空值）**：`{className, id?, text?, contentDesc?, tag?, bounds:[left,top,right,bottom], visibility?, alpha?, clickable?, enabled?, padding?:[left,top,right,bottom], message?, children?:[], composeNodes?:[]}`。其中 `?` 表示该字段在默认值时省略：`id/text/contentDesc/tag` 为空串时省略（但虚拟 id 始终输出）；`visibility` 为 `"visible"` 时省略；`alpha` 为 `1.0` 时省略；`clickable` 为 `false` 时省略；`enabled` 为 `true` 时省略；`padding` 全零时省略；`message` 为空串时省略（仅在自定义 View 解析失败时出现，含错误堆栈信息）；`children/composeNodes` 为空数组时省略。`className` 仅保留简单类名（去掉包名），如 `com.tencent.mtt.hippy.views.text.HippyTextView` → `HippyTextView`。`id` 去掉斜杠前的包名前缀，如 `com.example.application:id/btn_play` → `btn_play`。`bounds` 和 `padding` 使用紧凑数组格式 `[left,top,right,bottom]`，单位为 dp。
- **Kuikly 框架支持**：`KRRichTextView`、`KRGradientRichTextView` 等 Kuikly 控件的 text 提取通过 `KuiklyViewResolver`（反射方式）完成。反射链路：`view.textLayout (android.text.Layout) → Layout.getText() → CharSequence`。若反射失败不会 crash，节点 `text` 为空，`message` 字段包含错误详情及堆栈。`ElementFinder` 同样支持通过文本匹配 Kuikly 控件（`tap(text=...)` 可定位 Kuikly RichText 元素）。
- 多进程应用下，客户端会优先尝试主进程 socket（`processName == packageName`），再按其余 PID 依次尝试，最后兜底兼容 socket 名 `jugg_vh`。
- 当 ViewHierarchy 路径失败时，返回 `ERROR`（不再回退 `uiautomator dump`），建议按以下场景拆分排查：
  - `packageName` 无法解析：先确认 `projectDir` 对应运行配置、应用包名与前台进程一致。
  - 明确是 socket 不可连（如 `ViewHierarchy server is unavailable` / connect failed / forward failed）：高概率是 App 侧 `ViewHierarchyServer` 未成功集成到当前运行包（未安装最新包、构建链路未带上对应集成）。
  - 其他请求失败（超时、返回体非法）：优先看 `build/jugg/log/compile_latest.log` 与 IDE 日志。
- socket 不可连的推荐动作：`restart` 重试一次；若仍失败，执行一次 `gradle-build`（必要时轮询 `get_compile_status` 到终态）-> `deploy` -> `restart` 后再试 `layout-dump`/元素模式 `tap`。

补充（view-locate 语义）：
- `view-locate` 用于查找单个 UI 元素并返回其位置和尺寸信息。
- **target**：元素选择器（`text`/`resourceId`/`contentDesc`，至少提供一个）。
- 成功时返回 `data.found=true`，包含 `bounds`（`[left,top,right,bottom]`）、`position`（`{x,y}`）、`size`（`{width,height}`）、`className`。
- 失败时返回 `data.found=false`，`errorCode=ELEMENT_NOT_FOUND`。
- 所有坐标和尺寸单位为 dp。
- 内部自动调用 `layout-dump` 获取最新布局快照。
- 适用场景：需要获取元素位置用于后续计算或验证。

补充（view-inspect 语义）：
- `view-inspect` 通过 App 内 `ViewHierarchyServer` 反射调用 View 上的 getter 方法链，返回原始值。
- **target**：元素选择器（`resourceId`/`text`/`contentDesc`/`className`，AND 逻辑）。必须唯一匹配一个元素。
- **expressions**：getter 方法表达式数组（1~20 个）。语法为 `methodName()` 或 `methodName().anotherMethod()`，最大链深度 5。
- 仅允许 getter/查询方法（`get*`/`is*`/`has*`/`can*`/`should*` + `toString`/`length`/`name`/`ordinal`/`size`/`isEmpty` 等白名单）。有副作用的方法（`set*`/`remove*`/`add*`/`post*`/`dispatch*`/`perform*` 等）被禁止。
- 返回 `data.values[]`，每项含 `expression`/`value`/`type`（`string`/`int`/`long`/`float`/`double`/`boolean`/`null`/`error`）。
- 返回 `data.density`（设备像素密度），便于 Agent 将 px 转换为 dp 而无需额外调用。
- 链中间结果为 null 时返回 `{value: null, type: "null"}` 而非 NPE。
- 方法不存在时对应表达式返回 `{type: "error", error: "NoSuchMethodException: ..."}`，不影响同批其他表达式。
- 适用场景：单个 View 的属性查询（textColor、textSize、maxLines、ellipsize、tintColor、cornerRadius、自定义 View getter 等）。
- 与 `view-locate` 分工：需要两个 View 坐标计算的场景（spacing/alignment/overlap/containment/order）用 `view-locate`；查询单个 View 属性用 `view-inspect`。
- 在执行前同样会先等待 App 在线（同 `layout-dump`）。

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
- 推荐交互顺序（Agent/Skill 指引）：优先 `layout-dump + element tap`；元素模式不适用时使用 `layout-dump + coordinate tap`；仅当 ViewHierarchy 路径明确不可用时，才退回 `screenshot + percent/coordinate tap`。

补充（`mcp_fetch` 清理机制）：
- MCP 拉取类工具产物默认落在 `JuggPathManager.mcpFetchDir/<toolName>/`（当前展开为 `build/jugg/mcp_fetch/<toolName>/`）。
- IDE 启动初始化后会在后台清理 `build/jugg/mcp_fetch` 下最近修改时间超过 30 天的文件，并回收空目录。

---

## 4. 异步编译调用约定

`deploy`、`gradle-build` 可能返回：
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

> 仅在"连通性/上下文异常排查"场景使用以下步骤；正常使用无需把 `list_projects` / `devices` 作为固定 preflight。  

1. 先确认 IDE 已初始化该项目（`list_projects`）。  
2. 参数异常先对照 `tools/list` 返回的 `inputSchema`。  
3. 设备类工具失败时再执行 `devices`。  
4. 编译类异步任务卡住时，用 `get_compile_status` + `compile_latest.log`。
5. `layout-dump`/元素模式 `tap` 返回 `ViewHierarchy server is unavailable` 时，按"先 `restart` 一次 -> 再 `gradle-build` 一次 -> 重试"的顺序处理；若仍失败，再退回 `screenshot + percent/coordinate tap`。

---

## 7. 关联文档

- 设计说明：`08_mcp_design.md`
- 测试用例：`08_mcp_test_case.md`
- 路径速查：`98_code_map.md`
