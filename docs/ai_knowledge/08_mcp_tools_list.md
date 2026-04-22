# MCP Tools 参数清单

> 最后核对：2026-04-15
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. MCP 服务信息

- 端口范围：`12320..12329`
- 路径：`/jugg-mcp`
- 协议：JSON-RPC `2.0`
- 支持请求头：`MCP-Protocol-Version`（`2025-06-18`、`2025-11-25`）

---

## 2. MCP 返回约定

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

## 3. MCP 注册工具清单（以 `McpToolActionRegistry` 为准）

共 **18 个**注册工具，按注册顺序排列。

### 3.0 `version`

返回所有已初始化项目的 Jugg 插件版本。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| （无） | — | — | — |

**返回 data**：
- `pluginVersion`：所有项目中最高的插件版本（或统一版本）
- `projects`（可选）：当各项目版本不一致时，返回 `projectDir -> version` 的 map

---

### 3.1 `list-projects`

列出当前 IDE 已初始化项目。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| （无） | — | — | — |

---

### 3.2 `restart`

重启目标 App。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `projectDir` | string | **是** | 项目绝对路径（pattern: `^/.+`） |

**行为补充**：成功路径会后置等待 App 在线。

---

### 3.3 `compile`

仅编译不部署。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `projectDir` | string | **是** | 项目绝对路径 |

---

### 3.4 `deploy`

编译并部署（可能异步）。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `projectDir` | string | **是** | 项目绝对路径 |
| `alwaysRestartApp` | boolean | 否 | `true`（默认）时部署后强制重启 App（HOT_FIX 行为）；`false` 时仅在类结构变化时才重启（允许 HOT RELOAD） |

**异步返回**：`isFinal=false` 时返回 `jobId`，需用 `get-compile-status` 轮询。

---

### 3.5 `clean-reinstall`

卸载并重装 APK（清除数据 + 重新部署）。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `projectDir` | string | **是** | 项目绝对路径 |

---

### 3.6 `gradle-build`

强制 Gradle 构建（可能异步）。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `projectDir` | string | **是** | 项目绝对路径 |

**异步返回**：同 `deploy`。

---

### 3.7 `get-compile-status`

查询异步编译任务状态。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `projectDir` | string | **是** | 项目绝对路径 |
| `jobId` | string | **是** | 异步编译工具返回的 job ID |

**返回 data**：`jobId`、`status`（running/success/failed/canceled/unknown）、`executionType`（local/remote）、`message`；running 时附带 `pollIntervalSuggestedMs`。

---

### 3.8 `ssh-info`

申请远端 SSH 排障信息（需用户显式同意）。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `projectDir` | string | **是** | 项目绝对路径 |
| `reason` | string | **是** | 需要 SSH 信息的原因 |
| `requestedBy` | string | 否 | 请求者身份，默认 `mcp_agent` |

---

### 3.9 `devices`

列出已连接设备并标记 selected。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `projectDir` | string | **是** | 项目绝对路径 |

---

### 3.10 `layout-dump`

导出 UI 层级。输出 HTML 格式（`data.file`），同时 `data.jsonFile` 保留原始 JSON。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `projectDir` | string | **是** | 项目绝对路径 |
| `rootLayout` | string | 否 | 节点 id，仅返回该子树（推荐 short id，如 `"content"`） |
| `includeGone` | boolean | 否 | `true` 时包含 GONE 节点（默认 `false`） |
| `allWindows` | boolean | 否 | `true` 时导出所有窗口（默认 `false`，仅 top window） |

**行为要点**：
- 走 App 进程内 `ViewHierarchyServer`（LocalSocket），**不回退 uiautomator**。
- HTML 侧虚拟节点裁剪：无语义内容的结构性节点自动裁剪。
- 服务端剪枝：`MAX_DEPTH=60`，`MAX_NODE_COUNT=5000`。超限时 `truncated:true`。
- 所有 `bounds`/`padding` 单位为 dp（`dp = (int)(px / density)`）。
- 虚拟 ID 格式 `_vir_id_<index>`，可直接用于 `tap` 的 `resourceId`。
- `className` 仅保留简单类名；`id` 去掉包名前缀。
- Kuikly 框架控件（`KRRichTextView` 等）text 通过 `KuiklyViewResolver` 反射提取。
- socket 不可连时：先 `restart` 一次 → 若仍失败 `gradle-build` → `deploy` → `restart` → 重试。

---

### 3.11 `layout-verify`

批量验证 UI 元素属性或关系。自动获取布局快照。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `projectDir` | string | **是** | 项目绝对路径 |
| `checksFile` | string | 否 | checks JSON 文件绝对路径（`checks` 为空时使用） |
| `checks` | array | 否 | 批量检查数组（与 `checksFile` 二选一） |

**checks 数组 item schema**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `target` | object | 元素选择器：`resourceId`/`text`/`contentDesc`/`className` |
| `type` | string | `property`/`spacing`/`alignment`/`overlap`/`containment`/`order` |
| `property` | string | type=property 时使用。枚举：`exists`/`visibility`/`clickable`/`enabled`/`text`/`textColor`/`backgroundColor`/`alpha`/`bounds.width`/`bounds.height`/`bounds.left`/`bounds.top`/`bounds.right`/`bounds.bottom`/`padding.left`/`padding.top`/`padding.right`/`padding.bottom`/`textSizeSp` |
| `op` | string | 比较运算符，默认 `eq`。枚举：`eq`/`neq`/`gte`/`lte`/`gt`/`lt`/`contains`/`matches` |
| `value` | string | 期望值。textColor 格式：`#AARRGGBB` |
| `target2` | object | 关系检查的第二个元素选择器（同 `target`） |
| `axis` | string | `x`=水平/`y`=垂直（用于 spacing/alignment/order） |
| `expected` | number | 期望值 dp（用于 spacing/alignment/order） |
| `expectOverlap` | boolean | type=overlap 时使用。默认 `false`（PASS=无重叠）；`true`（PASS=有重叠） |

**live-only 属性**：`textSizeSp`、`backgroundColor` 自动走 live query。

---

### 3.15 `view-locate`

查找单个 UI 元素，返回位置和尺寸。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `projectDir` | string | **是** | 项目绝对路径 |
| `target` | object | **是** | 元素选择器：`text`/`resourceId`/`contentDesc` |
| `figmaNode` | object | 否 | Figma 节点信息（id/name/bounds），用于模糊匹配 |

**返回 data**（found=true 时）：`bounds`（`[l,t,r,b]`）、`position`（`{x,y}`）、`size`（`{width,height}`）、`className`。所有坐标单位 dp。

---

### 3.13 `view-inspect`

通过反射查询 View getter 方法链，返回原始值。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `projectDir` | string | **是** | 项目绝对路径 |
| `target` | object | **是** | 元素选择器：`resourceId`/`text`/`contentDesc`/`className`（AND 逻辑） |
| `expressions` | array\<string\> | **是** | getter 方法表达式（1~20 个），如 `getText()`、`getCurrentTextColor()`、`getMaxLines()` |

**行为要点**：
- 仅允许 getter/查询方法白名单（`get*`/`is*`/`has*`/`can*`/`should*` + `toString`/`length` 等）。
- 返回 `data.values[]`，每项含 `expression`/`value`/`type`/`error`。
- 返回 `data.density`（设备像素密度），便于 px→dp 换算。
- 与 `view-locate` 分工：坐标计算用 `view-locate`；属性查询用 `view-inspect`。

---

### 3.17 `activity-stack`

读取 Activity 栈。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `projectDir` | string | **是** | 项目绝对路径 |

**返回 data**：`topActivity`、`activities[]`、`dumpFile`、`sourceCommand`。

---

### 3.16 `tap`

屏幕触控（tap/long-press/swipe）。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `projectDir` | string | **是** | 项目绝对路径 |
| `action` | string | 否 | `tap`（默认）/`long-press`/`swipe` |
| `x` | number | 否 | X 坐标（坐标模式，min: 0） |
| `y` | number | 否 | Y 坐标（坐标模式，min: 0） |
| `endX` | number | 否 | swipe 终点 X（坐标模式，min: 0） |
| `endY` | number | 否 | swipe 终点 Y（坐标模式，min: 0） |
| `xPercent` | number | 否 | X 百分比（0-100，百分比模式） |
| `yPercent` | number | 否 | Y 百分比（0-100，百分比模式） |
| `endXPercent` | number | 否 | swipe 终点 X 百分比（0-100） |
| `endYPercent` | number | 否 | swipe 终点 Y 百分比（0-100） |
| `duration` | number | 否 | 持续时间 ms（swipe 默认 300，long-press 默认 500，min: 50） |
| `text` | string | 否 | 元素文本选择器（精确匹配） |
| `resourceId` / `id` | string | 否 | 元素 resource-id（精确匹配，推荐 short id）；`id` 是 `resourceId` 的别名 |
| `contentDesc` / `desc` | string | 否 | 元素 content-desc（精确匹配）；`desc` 是 `contentDesc` 的别名 |
| `className` / `class` | string | 否 | 类名过滤（AND 逻辑）；`class` 是 `className` 的别名 |

**模式优先级**：coordinate > percent > element。

**行为要点**：
- `swipe` 仅支持坐标/百分比模式，不支持元素模式。
- 元素模式多匹配时不执行，返回 `ERROR` + 匹配元素摘要。
- 执行前检查 `topActivity` 稳定性（连续 2 次相同且 onResume，最长等待 5s）。
- 百分比换算结果做边界钳制 `[0, size-1]`。
- 推荐交互顺序：`layout-dump + element tap` → `layout-dump + coordinate tap` → `screenshot + percent/coordinate tap`。

---

### 3.19 `status`

查询当前 Jugg 部署状态与未编译文件摘要。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `projectDir` | string | **是** | 项目绝对路径 |

**返回 data**：
- `hasDevice`：boolean，设备已连接时为 `true`
- `needFallback`：boolean，需要 Gradle 全量构建时为 `true`
- `stateMessage`：当前状态的可读原因
- `fileCounts`：`{ total: number, <Type>: number, ... }`，按 `CompileFile.Type` 分类统计未编译文件数量
- `files`：未编译文件绝对路径列表，**最多 20 个**
- `detail`：未截断时为空字符串；截断时为自然语言描述，如 `"Showing 20 of 25 files. 5 more files are not listed."`

---

### 3.20 `wait-logs`

阻塞式等待 App 日志，直到 marker 命中、发生 crash 或超时，返回过滤后的日志窗口。

| 参数 | 类型 | 必填 | 默认 | 说明 |
|------|------|------|------|------|
| `projectDir` | string | **是** | — | 项目绝对路径（pattern: `^/.+`） |
| `marker` | string | **是** | — | 停止条件正则（Java Pattern 方言），匹配日志 message 部分 |
| `tags` | array[string] | 否 | `[]` | tag 白名单（精确匹配，空 = 不按 tag 过滤） |
| `timeoutMs` | integer | 否 | `30000` | 硬超时毫秒，范围 `[1000, 300000]` |

**返回 data**：
- `stopReason`：`marker` / `crash` / `timeout`
- `startTime`、`endTime`：logcat threadtime 格式 `MM-dd HH:mm:ss.SSS`
- `targetPids`：停止时枚举的目标进程 PID 列表
- `logs`：过滤后最多 100 行（`\n` 分割的 logcat threadtime 原生格式）
- `allLogsPath`：全量原始日志落盘路径
- `truncated`：`logs` 是否被截断

**错误码**：`INVALID_PARAMS`、`INVALID_REGEX`、`NO_DEPLOY_BASELINE`、`NO_DEVICE`、`INTERNAL_ERROR`

---

## 4. 未注册但存在的 MCP Action

以下 Action 在代码中有实现但处于精简考虑，**未注册**到工具列表，外部无法使用：

| Action 文件 | 说明 |
|-------------|------|
| `EmulatorListMcpToolAction.kt` | 模拟器列表 |
| `FigmaLayoutVerifyMcpToolAction.kt` | Figma 布局验证 |
| `ScreenshotMcpToolAction.kt` | 截图（`screenshot`） |
| `StartActivityMcpToolAction.kt` | 启动 Activity |
| `StartAppMcpToolAction.kt` | 启动 App |
| `StartEmulatorMcpToolAction.kt` | 启动模拟器 |
| `StartRecordMcpToolAction.kt` | 开始录屏（`record-start`） |
| `StopRecordMcpToolAction.kt` | 停止录屏（`record-stop`） |

---

## 5. MCP 通用行为

### 5.1 App 在线等待

- `restart`、`deploy`、`gradle-build`、`clean-reinstall` 成功路径后置等待 App 在线。
- `activity-stack`、`tap`、`layout-dump`、`view-locate`、`view-inspect` 执行前等待 App 在线（每 100ms 检查，最长 10s）。
- 若前置等待过程中发生过实际等待且工具调用返回失败，自动重试最多 3 次，间隔 2s。
- 运行态工具执行顺序：参数校验 → `projectDir` 初始化态校验 → App 在线校验 → 业务执行。

### 5.2 异步编译调用

`deploy`、`gradle-build` 可能返回 `isFinal=false` + `jobId`。用 `get-compile-status` 轮询，按 `pollIntervalSuggestedMs` 间隔。

### 5.3 产物清理

MCP 拉取类工具产物落在 `build/jugg/mcp_fetch/<toolName>/`。IDE 启动后后台清理超过 30 天的文件。

---

## 6. 常见错误码

| 错误码 | 说明 |
|--------|------|
| `INVALID_JSON_RPC` | JSON-RPC 格式错误 |
| `METHOD_NOT_SUPPORTED` | 不支持的方法 |
| `TOOL_NOT_FOUND` | 工具未注册 |
| `INVALID_PARAMS` | 参数错误 |
| `PROJECT_NOT_INITIALIZED` | 项目未初始化 |
| `NO_DEVICE` | 无可用设备 |
| `INTERNAL_ERROR` | 内部错误 |

---

## 7. 连通性与排查

> 仅在"连通性/上下文异常排查"场景使用以下步骤；正常使用无需把 `list-projects` / `devices` 作为固定 preflight。

1. 先确认 IDE 已初始化该项目（`list-projects`）。
2. 参数异常先对照 `tools/list` 返回的 `inputSchema`。
3. 设备类工具失败时再执行 `devices`。
4. 编译类异步任务卡住时，用 `get-compile-status` + `compile_latest.log`。
5. `layout-dump`/元素模式 `tap` 返回 `ViewHierarchy server is unavailable` 时，按"先 `restart` → 再 `gradle-build` → 重试"处理。

---

## 8. 关联文档

- CLI 封装层：`08_cli_tools_list.md`
- 设计说明：`08_mcp_design.md`
- figma-layout-verify 算法：`08_mcp_figma-layout-verify_internals.md`
- 路径速查：`98_code_map.md`
