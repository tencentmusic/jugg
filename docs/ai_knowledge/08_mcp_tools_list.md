# MCP Tools 参数清单

> 最后核对：2026-09-01
> 一致性规则：文档与代码冲突时，以代码为准。

---

## MCP 服务信息

- 端口范围：`12320..12329`
- 路径：`/jugg-mcp`
- 协议：JSON-RPC `2.0`
- 支持请求头：`MCP-Protocol-Version`（`2025-06-18`、`2025-11-25`）

---

## MCP 返回约定

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

## MCP 注册工具清单（以 `McpToolActionRegistry` 为准）

共 **20 个**注册工具，按注册顺序排列。

以下设备相关工具公开可选 `serial: string`：`restart`、`deploy`、`clean-reinstall`、`gradle-build`、`instrument`、`devices`、`layout-dump`、`view-locate`、`view-inspect`、`activity-stack`、`tap`、`status`、`wait-logs`。显式 serial 按大小写敏感的在线设备精确匹配，覆盖 IDEA 选中设备与 standalone `ANDROID_SERIAL`，只影响当前请求；未命中时不得回退其他设备。`devices` 传 serial 时只返回该在线设备，未命中返回 `NO_DEVICE`。

### `version`

返回当前 Jugg Runtime 的版本、类型和 capability，并保留插件版本兼容字段。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| （无） | — | — | — |

**返回 data**：
- `pluginVersion`：所有项目中最高的插件版本（或统一版本）
- `projects`（可选）：当各项目版本不一致时，返回 `projectDir -> version` 的 map
- `runtimeType`：`idea` / `standalone` / `ci` / `unknown`
- `runtimeVersion`：当前进程实际 Runtime 版本
- `capabilities`：当前进程的 `McpToolRegistry` 已声明可用的 MCP capability 名称，并与 `tools/list`、action 分发保持一致；standalone Step 11 包含 `version`、`list-projects`、`init`、`compile`、`deploy`、`gradle-build`、`get-compile-status`、`status`、`report-prepare`、`report-upload`

---

### `list-projects`

列出当前 IDEA 或 standalone Runtime 进程已初始化的项目。该全局工具不会触发 standalone 项目自动注册；未知项目只会在首个合法项目级请求到达时注册。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| （无） | — | — | — |

**返回 data**：
- `projects`：项目数组，每项包含：
  - `projectDir`：项目绝对路径
  - `initialized`：是否已完成 Jugg 初始化（当前列表内项目固定为 `true`）
  - `hasBeenFullCompiled`：是否存在完整 Jugg 全量编译基线（对齐 `DeployHistoryManager.hasBeenFullCompiled` 语义）

---

### `init`

仅 standalone Runtime 注册。根据 Gradle project info 创建并选择当前 CLI Run Configuration；project info 缺失时先执行一次本地 `assembleDebug --dry-run --no-daemon` 生成快照。已存在当前配置时幂等返回该配置，不改写已选中的 remote profile。初始化在项目写锁内执行。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `projectDir` | string | **是** | 项目绝对路径 |

**返回 data**：`configurationId`、`configurationName`、`compileCommand`。

---

### `restart`

重启目标 App。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `projectDir` | string | **是** | 项目绝对路径（pattern: `^/.+`） |
| `serial` | string | 否 | 本次请求的 adb serial |
| `waitAppReadyAfterSuccess` | boolean | 否 | `true` 时重启成功后等待 App ready；默认 `false`，不做后置 ready 等待 |

**行为补充**：成功路径默认只确认 restart 命令执行完成；需要把 App ready 作为工具成功条件时显式传 `waitAppReadyAfterSuccess=true`。

---

### `compile`

仅编译不部署。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `projectDir` | string | **是** | 项目绝对路径 |

**无待编译文件**：编译成功且当前没有文件需要编译时，成功消息会明确显示 `No pending file changes`。该结果表示本轮没有生成新的编译产物，不会触发部署，也不会附带部署历史。首次调用内完成和通过 `get-compile-status` 轮询完成时使用相同的最终消息。

---

### `deploy`

编译并部署（可能异步）。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `projectDir` | string | **是** | 项目绝对路径 |
| `serial` | string | 否 | 本次请求的 adb serial |
| `alwaysRestartApp` | boolean | 否 | `true`（默认）时部署后强制重启 App（HOT_FIX 行为）；`false` 时仅在类结构变化时才重启（允许 HOT RELOAD） |
| `waitAppReadyAfterSuccess` | boolean | 否 | `true` 时部署成功后等待 App ready；默认 `false`，不做后置 ready 等待 |

**异步返回**：`isFinal=false` 时返回 `jobId`，需用 `get-compile-status` 轮询。

**无待部署文件**：成功消息会明确说明当前 Jugg 检测到的修改均已部署，并附带本次 IDE 会话中最后一次“包含文件变更且部署成功”的绝对时间、相对时间和项目相对路径。文件最多展示 20 条，超出部分显示剩余数量；IDE 重启后没有会话内记录时会明确说明详情不可用。首次调用内完成和通过 `get-compile-status` 轮询完成时使用相同的最终消息。

---

### `clean-reinstall`

卸载并重装 APK（清除数据 + 重新部署）。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `projectDir` | string | **是** | 项目绝对路径 |
| `serial` | string | 否 | 本次请求的 adb serial |
| `waitAppReadyAfterSuccess` | boolean | 否 | `true` 时重装成功后等待 App ready；默认 `false`，不做后置 ready 等待 |

---

### `gradle-build`

强制 Gradle 构建（可能异步）。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `projectDir` | string | **是** | 项目绝对路径 |
| `serial` | string | 否 | 本次请求的 adb serial；standalone 仅建立 baseline，不执行设备操作 |
| `waitAppReadyAfterSuccess` | boolean | 否 | `true` 时构建/安装成功后等待 App ready；默认 `false`，不做后置 ready 等待 |

**异步返回**：同 `deploy`。

**行为补充**：IDEA 保持现有 Gradle 构建后的安装/启动链路；standalone `gradle-build` 只建立或刷新 baseline，并将成功结果映射为 `isCompileSuccess=true`、`isDeploySuccess=true` 以保持共享 job 成功契约，实际安装/增量更新由后续 `deploy` 完成。当前配置为 remote 时，Gradle full build/fallback 复用 IDEA 的 SSH/iFT 远程客户端，但本地 project info dry-run、增量编译和设备操作仍在 standalone 所在主机执行。standalone 无交互认证 UI；缺少 SSH 凭据或 iFT 认证时返回 failed 终态和明确操作提示。

**失败详情**：失败终态会在 data 中附带 `detail` / `detailLength` / `detailTruncated`（如有），内容来自本次 Gradle build + 安装/启动日志摘要；异步场景通过 `get-compile-status` 获取同一份详情。长日志 preview 上限为 8KB，采用 4KB 开头 + 4KB 结尾，避免只保留 stack/footer 而丢失根因。

### `instrument`

按 androidTest 源文件锚点运行 class/method 级测试，内部复用 Jugg compile/deploy 流程。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `projectDir` | string | **是** | 项目绝对路径 |
| `serial` | string | 否 | 本次请求的 adb serial |
| `sourcePath` | string | **是** | androidTest 源文件路径，用于解析 module 与 test APK |
| `class` | string | 否 | 文件内测试类，单 class 文件可省略 |
| `method` | string | 否 | 测试方法，需已唯一确定 class |
| `runner` | string | 否 | instrumentation runner override |
| `extras` | object | 否 | 额外 `-e key value` 参数，value 必须是 string |

**行为补充**：
- `package` / `testsRegex` 不再作为 target 入口；多 test APK 场景必须用 `sourcePath` 确定目标。
- MCP 层先做参数归一化、`sourcePath` 解析与 AndroidTest full-build baseline 预检；目标解析在 androidTest source resolver 中完成。
- 内部会以 `BuildTarget.ANDROID_TEST` 运行，并将参数映射到 `AndroidTestRunSpec`。
- 当前项目未建立 AndroidTest full-build baseline 时，返回 `status=ERROR`、`errorCode=INVALID_PARAMS`，`message` 包含 `enabledAndroidTest=false`，并提示打开 Jugg App Run Configuration、开启 Android Test / `enableAndroidTest`、执行一次 full build / `gradle-build` 后重新检查 `status.data.enabledAndroidTest=true`。

---

### `get-compile-status`

查询异步编译任务状态。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `projectDir` | string | **是** | 项目绝对路径 |
| `jobId` | string | **是** | 异步编译工具返回的 job ID |
| `waitTimeoutMs` | integer | 否 | 阻塞等待状态变化的超时时间（毫秒），范围 `[0, 10000]`，默认 `0`（不阻塞） |

**返回 data**：`jobId`、`status`（running/success/failed/canceled/unknown）、`executionType`（local/remote）、`message`；running 时附带 `pollIntervalSuggestedMs`，如果当前 IDE 进度文本非空，还会附带 `indicator.text`，供 CLI/Agent 展示轻量 heartbeat。终态时附带 `isCompileSuccess`（boolean，编译是否成功，unknown 时缺失）、`isDeploySuccess`（boolean，部署是否成功，unknown 时缺失；compile 仅编译时通常为 `false`）。终态为 `failed` / `canceled` 且存在诊断输出时，附带 `detail` / `detailLength` / `detailTruncated`；成功终态不返回 `detail`。

**行为说明**：
- 当 `waitTimeoutMs > 0` 且任务当前为 `running` 时，接口会在服务端阻塞等待状态变化，直到任务终态或超时后返回。
- 该参数用于减少“任务已结束但客户端下一次轮询还没到”的等待窗口。

---

### `ssh-info`

申请远端 SSH 排障信息（需用户显式同意）。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `projectDir` | string | **是** | 项目绝对路径 |
| `reason` | string | **是** | 需要 SSH 信息的原因 |
| `requestedBy` | string | 否 | 请求者身份，默认 `mcp_agent` |

### `report-prepare`

生成最终待上传的脱敏诊断 ZIP，不发起网络请求。IDEA 与 standalone 均注册该工具。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `projectDir` | string | **是** | 项目绝对路径 |

**返回 data**：`reportId`、`filePath`、`size`、`sha256`、固定 `uploadUrl`，以及 `entries`。每个 entry 包含 `path`、`size`、`sensitivity` 和 `redaction`，与最终 ZIP manifest 完全一致。

### `report-upload`

上传用户已经查看并确认的诊断 ZIP。服务端重新从项目 diagnostics 目录读取 bundle，校验 report ID、目录边界、manifest、ZIP 条目和 SHA-256；任何内容变化都会在网络请求前失败。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `projectDir` | string | **是** | 项目绝对路径 |
| `reportId` | string | **是** | `report-prepare` 返回的 8 位十六进制 ID |
| `sha256` | string | **是** | `report-prepare` 返回的 ZIP SHA-256 |

**成功返回**：message 与 IDE 对齐为 `Report uploaded. Jugg Report ID: <reportId>`；data 仅保留 `reportId`，不返回诊断 entries、本地临时路径或 file artifact。

---

### `devices`

列出已连接设备并标记 selected。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `projectDir` | string | **是** | 项目绝对路径 |
| `serial` | string | 否 | 只返回该在线设备；未命中返回 `NO_DEVICE` |

---

### `layout-dump`

导出 UI 层级。公开输出为 HTML 格式（`data.file`）；结构化 JSON 仅保留为 `LayoutDumpHelper.dumpInternal()` 的工具内部实现细节，不暴露给 Agent。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `projectDir` | string | **是** | 项目绝对路径 |
| `serial` | string | 否 | 本次请求的 adb serial |
| `rootLayout` | string | 否 | 节点 id，仅返回该子树（推荐 short id，如 `"content"`）；指定后自动跨窗口查找 |
| `includeGone` | boolean | 否 | `true` 时包含 GONE 节点（默认 `false`） |
| `allWindows` | boolean | 否 | `true` 时导出所有窗口（默认 `false`，仅 top window） |

**行为要点**：
- 走 App 进程内 `ViewHierarchyServer`（LocalSocket），**不回退 uiautomator**。
- App 侧节点数据源为 Dragonfly；传统 Android View 与 Compose 节点统一适配为原有 `windows/root/children` JSON，公开 MCP/HTML 格式不变。Dragonfly 无法枚举窗口时，窗口根列表 Best-effort 降级到旧 `ActivityThread` / `WindowManagerGlobal` 反射路径，根节点仍交给 Dragonfly 提取。
- HTML 侧虚拟节点裁剪：无语义内容的结构性节点自动裁剪。
- Jugg snapshot 剪枝：`MAX_DEPTH=60`，`MAX_NODE_COUNT=5000`。限制作用于 Dragonfly 返回后的标准化阶段，dump、selector、tap、inspect、verify 都只能访问该范围；超限时 `truncated:true`。若 Dragonfly 原始提取阶段先失败，则无法返回 `truncated:true`。
- 所有 `bounds`/`padding` 单位为 dp（`dp = (int)(px / density)`）。
- 虚拟 ID 格式 `_vir_id_<hash>`；Dragonfly window/children 遍历顺序和 UI 结构不变时跨请求一致，可用于后续 selector，但不保证列表重排或页面重组后仍指向同一业务节点。
- `className` 仅保留简单类名；`id` 去掉包名前缀。
- Kuikly 框架控件（`KRRichTextView` 等）text 通过 `KuiklyViewResolver` 反射提取。
- Dragonfly 自带私有化 Kotlin/协程运行时，纯 Java 工程不再因缺少宿主 Kotlin 而返回 `FEATURE_NOT_SUPPORTED`。Compose runtime/tooling 不兼容时由 Dragonfly 局部收口，不切换到旧节点数据源。
- socket 不可连时：先 `restart` 一次 → 若仍失败 `gradle-build` → `deploy` → `restart` → 重试。

---

### `view-locate`

在 App 侧实时 Dragonfly snapshot 中查找 UI 元素。多个非空 selector 使用 AND 逻辑；返回数量受预算控制。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `projectDir` | string | **是** | 项目绝对路径 |
| `serial` | string | 否 | 本次请求的 adb serial；内部 layout dump 使用同一设备 |
| `target` | object | **是** | 元素选择器：`text`/`resourceId`/`contentDesc` |
| `figmaNode` | object | 否 | 保留字段；当前公开实现仍以 `target` 的 text/resourceId/contentDesc 精确匹配为准 |

**返回 data**：`matchCount` 是总命中数，`returnedCount` 是实际返回数，`truncated` 表示是否按预算截断，`matches[]` 为候选摘要。唯一命中时额外返回顶层 `bounds`（`[l,t,r,b]`）、`position`（`{x,y}`）、`size`（`{width,height}`）、`className`、`resourceId`；多命中时不隐式选择第一个节点。所有坐标单位 dp。runtime 能提供时，候选与唯一命中顶层返回 `source: {file?, line?}`。

---

### `view-inspect`

通过反射查询实时 Dragonfly snapshot 中节点的只读属性，返回原始值。Android 节点以原始 View 为查询对象，Compose 节点以 Dragonfly 节点对象为查询对象。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `projectDir` | string | **是** | 项目绝对路径 |
| `serial` | string | 否 | 本次请求的 adb serial |
| `target` | object | **是** | 元素选择器：`resourceId`/`text`/`contentDesc`/`className`（AND 逻辑） |
| `expressions` | array\<string\> | **是** | getter 方法表达式（1~20 个），如 `getText()`、`getCurrentTextColor()`、`getMaxLines()` |

**行为要点**：
- 显式 `foo()` 只走 getter/query 白名单（`get*`/`is*`/`has*`/`can*`/`should*` + `toString`/`length` 等）。
- 无 `()` 的 identifier 先读 public 字段，再按 Kotlin/Java getter 解析：已是 `get*`/`is*` 前缀则直接调用；否则试 `getXxx()` / `isXxx()`。
- 返回 `data.values[]`，每项含 `expression`/`value`/`type`/`error`。
- 返回 `data.density`（设备像素密度），便于 px→dp 换算。
- runtime 能提供时返回 `data.source: {file?, line?}`，用于把验证证据关联到源码位置。
- 可读取仍在 View 树中的隐藏节点属性；隐藏节点不应作为点击目标。
- Compose 节点只支持其运行时对象实际暴露的 getter；Android View 专属 getter 会在对应 expression 返回 error。
- 与 `view-locate` 分工：坐标计算用 `view-locate`；属性查询用 `view-inspect`。

---

### `activity-stack`

读取 Activity 栈。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `projectDir` | string | **是** | 项目绝对路径 |
| `serial` | string | 否 | 本次请求的 adb serial |

**返回 data**：`topActivity`、`activities[]`、`dumpFile`、`sourceCommand`。

---

### `tap`

屏幕触控（tap/long-press/swipe）。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `projectDir` | string | **是** | 项目绝对路径 |
| `serial` | string | 否 | 本次请求的 adb serial |
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
- 元素模式使用实时 Dragonfly snapshot。Android 节点优先 `View.performClick()`；Compose 节点当前向所属 root View 的 bounds 中心派发 MotionEvent，不保证等价于 Semantics action，也不能可靠判断 disabled/stale。
- 执行前检查 `topActivity` 稳定性（连续 2 次相同且 onResume，最长等待 5s）。
- 百分比换算结果做边界钳制 `[0, size-1]`。
- 推荐交互顺序：`layout-dump + element tap` → `layout-dump + coordinate tap` → 外部截图证据（若可用）+ percent/coordinate tap。当前 MCP `screenshot` action 未注册，不能作为默认公开工具。

---

### `status`

查询当前 Jugg 部署状态与未编译文件摘要。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `projectDir` | string | **是** | 项目绝对路径 |
| `serial` | string | 否 | 返回该设备的 deploy state；未命中时 `hasDevice=false` 并在 `stateMessage` 说明原因 |
| `refreshChanges` | boolean | 否 | 是否先刷新 git-tracked changed files；默认 `true`，传 `false` 时跳过刷新 |
| `fullInfo` | boolean | 否 | 是否返回完整状态信息；默认 `false`，传 `true` 时 `files` 返回全部未编译文件路径 |

**返回 data**：
- `hasDevice`：boolean，设备已连接时为 `true`
- `needFallback`：boolean，需要 Gradle 全量构建时为 `true`
- `executionType`：`local` / `remote`，当前 Jugg run configuration 的 Gradle fallback 执行环境；AI command hook 在 `remote` 时会对 raw Gradle 命令强制先 block 一次
- `stateMessage`：当前状态的可读原因
- `pendingModifiedFiles`：`{ total: number, <Type>: number, ... }`，按 `CompileFile.Type` 分类统计未编译文件数量
- `files`：未编译文件绝对路径列表；默认最多 20 个，`fullInfo=true` 时返回全部路径
- `detail`：未截断时为空字符串；截断时为自然语言描述并提示使用 `fullInfo=true`，如 `"Showing 20 of 25 files. Set fullInfo=true to return full status information, including all 25 file paths."`
- `lastFileModifiedTime`：最近未编译文件的本地可读时间戳（`yyyy-MM-dd HH:mm:ss`，无文件时为空字符串）
- `lastCompileTime`：最近一次调用 `compile` / `deploy` / `gradle-build` 的本地可读时间戳（`yyyy-MM-dd HH:mm:ss`，无记录时为空字符串）；AI hooks 用它判断当前 Agent 会话写入是否已被 Jugg 验证覆盖
- `hasBeenFullCompiled`：是否存在完整 Jugg 全量编译基线；AI hooks 仅在该字段为 `true` 时启用 raw Gradle guard 与 stop guard。command hook 对 `executionType=remote` 会跳过会话写入与 pending file 覆盖判断，仍按“一次 block、重复放行”处理
- `enabledAndroidTest`：最近一次 full build 基线是否以 AndroidTest target 初始化（`true` 表示当时开启了 `enableAndroidTest`）
- `isCompiling`：boolean，当前是否有 Jugg compile/deploy 运行任务在执行（对齐 `JuggConfigurationRunner.isCompiling`）

项目空闲且可立即取得项目锁时，`status` 会在锁内完成 Runtime owner 恢复和可选 Git refresh。若同 Runtime 正在编译，或项目锁正由 IDEA/standalone 的其他写事务持有，调用会立即返回当前真实只读快照；此时跳过 refresh 和状态写入，但仍保留实际的部署状态、fallback 原因、待编译文件、baseline、时间戳与 `isCompiling`，不会等待长任务或返回伪造空值。

---

### `wait-logs`

阻塞式等待 App 日志，直到 marker 命中、发生 crash 或超时，返回过滤后的日志窗口。

| 参数 | 类型 | 必填 | 默认 | 说明 |
|------|------|------|------|------|
| `projectDir` | string | **是** | — | 项目绝对路径（pattern: `^/.+`） |
| `serial` | string | 否 | — | 本次请求的 adb serial；优先读取项目 + serial 的 deploy/restart 时间戳 |
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

## 未注册但存在的 MCP Action

以下 Action 在代码中有实现但处于精简考虑，**未注册**到工具列表，外部无法使用：

| Action 文件 | 说明 |
|-------------|------|
| `EmulatorListMcpToolAction.kt` | 模拟器列表 |
| `FigmaLayoutVerifyMcpToolAction.kt` | Figma 布局验证 |
| `LayoutVerifyMcpToolAction.kt` | 旧 UI 批量验证（已从 MCP 注册表移除，外部不可用） |
| `ScreenshotMcpToolAction.kt` | 截图（`screenshot`） |
| `StartActivityMcpToolAction.kt` | 启动 Activity |
| `StartAppMcpToolAction.kt` | 启动 App |
| `StartEmulatorMcpToolAction.kt` | 启动模拟器 |
| `StartRecordMcpToolAction.kt` | 开始录屏（`record-start`） |
| `StopRecordMcpToolAction.kt` | 停止录屏（`record-stop`） |

---

## MCP 通用行为

### App 在线等待

- `restart`、`deploy`、`gradle-build`、`clean-reinstall` 统一使用 `waitAppReadyAfterSuccess` 控制成功后的 App ready 等待；默认 `false`，只有显式传 `true` 才后置等待（每 200ms 检查，最长 10s）。
- `activity-stack`、`tap`、`layout-dump`、`view-locate`、`view-inspect` 执行前等待 App 在线（每 100ms 检查，最长 10s）。
- 运行态工具调用返回 `INTERNAL_ERROR` 或缺省错误码时，按瞬态错误自动重试最多 3 次，间隔 2s；用于覆盖 App 已在线但进程内服务（如 ViewHierarchyServer）尚未接受 LocalSocket 请求的短暂窗口。
- ViewHierarchy 相关工具首次访问失败后会查询设备屏幕状态和前台 Activity；若设备息屏/非交互态，直接返回 `DEVICE_NOT_INTERACTIVE`；若目标 App 不在前台，直接返回 `APP_NOT_FOREGROUND`；这两类错误不再继续重试。
- 运行态工具执行顺序：参数校验 → `projectDir` 初始化态校验 → App 在线校验 → 业务执行。

### 异步编译调用

`deploy`、`gradle-build` 可能返回 `isFinal=false` + `jobId`。用 `get-compile-status` 轮询，按 `pollIntervalSuggestedMs` 间隔。

终态 data 中返回 `isCompileSuccess`（boolean）和 `isDeploySuccess`（boolean）。失败时如有诊断输出，会返回 `detail` / `detailLength` / `detailTruncated`。compile/gradle-build/deploy/instrument 都可配合 `status` 字段做更细粒度的成功/失败判定。

### 产物清理

MCP 拉取类工具产物落在 `build/jugg/mcp_fetch/<toolName>/`。IDE 启动后后台清理超过 30 天的文件。

---

## 常见错误码

| 错误码 | 说明 |
|--------|------|
| `INVALID_JSON_RPC` | JSON-RPC 格式错误 |
| `METHOD_NOT_SUPPORTED` | 不支持的方法 |
| `TOOL_NOT_FOUND` | 工具未注册 |
| `INVALID_PARAMS` | 参数错误 |
| `PROJECT_NOT_INITIALIZED` | IDEA 项目未初始化，或 standalone 项目自动初始化失败 |
| `NO_DEVICE` | 无可用设备 |
| `DEVICE_NOT_INTERACTIVE` | 设备息屏或非交互态，需唤醒/解锁后重试 |
| `APP_NOT_FOREGROUND` | 目标 App 不在前台，需切回目标 App 后重试 |
| `FEATURE_NOT_SUPPORTED` | 当前工程或运行环境不支持该能力 |
| `INTERNAL_ERROR` | 内部错误 |

---

## 连通性与排查

> 仅在"连通性/上下文异常排查"场景使用以下步骤；正常使用无需把 `list-projects` / `devices` 作为固定 preflight。

1. IDEA 场景先确认 IDE 已初始化该项目（`list-projects`）；standalone 场景允许首个合法项目请求自动注册。
2. 参数异常先对照 `tools/list` 返回的 `inputSchema`。
3. 设备类工具失败时再执行 `devices`。
4. 编译类异步任务卡住时，用 `get-compile-status` + `compile_latest.log`。
5. `layout-dump`/元素模式 `tap` 返回 `DEVICE_NOT_INTERACTIVE` 时，先唤醒/解锁设备后重试；返回 `APP_NOT_FOREGROUND` 时，先用 `restart` 或 `start-activity` 切回目标 App 后重试；仍返回 `ViewHierarchy server is unavailable` 时，再按"先 `restart` → 再 `gradle-build` → 重试"处理。

---

## 关联文档

- CLI 封装层：`08_cli_tools_list.md`
- 设计说明：`08_mcp_design.md`
- figma-layout-verify 算法：`08_mcp_figma_layout_verify_internals.md`
- UI 验证检查清单：`08_mcp_ui_verify_checklist.md`
- 路径速查：`98_code_map.md`
