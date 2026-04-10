# L2 Unit: 应用控制与交互

> 覆盖 `jugg restart`、`jugg tap` 两个命令的各交互场景，共 ~14 条。
> 执行 INTERACT-4~8 时需先导航到 McpTestActivity（MainActivity → "MCP Test Page"）。

---

## 前置说明：McpTestActivity 控件

- 唯一文本：`Unique MCP Target`
- 资源 ID：`btn_mcp_resource_target`（文本：`Resource Tap Target`）
- 重复文本：`Repeat Tap Target`（两个可见节点）
- 可见/隐藏同文案节点：`Visibility Tap Target`（一个可见、一个 `invisible`）
- swipe 专用可滑动区域：`sv_mcp_swipe_target`
- swipe 起止标记文本：`Swipe Start Marker`（初始可见）、`Swipe End Marker`（初始不可见，滑动后可见）

---

## 五、应用控制与交互

**INTERACT-1: 重启应用 - 默认入口**
通过 jugg-android-dev-loop 执行 `jugg restart`，仅传入 `projectDir`，验证返回 JSON 中 `status` 为 `OK`，应用被成功拉起（可通过后续 `jugg activity-stack` 或 `jugg screenshot` 确认）。

**INTERACT-1A: 重启应用并串行导航（tap_actions）**
通过 jugg-android-dev-loop 执行 `jugg restart`，传入 `projectDir` 与：
`tap_actions=[{"text":"MCP Test Page"},{"resourceId":"btn_some_secondary_entry"}]`，验证返回 JSON 中 `status=OK`，且导航动作按顺序执行（可通过 `jugg activity-stack` 或 `jugg layout-dump` 验证最终页面上下文）。

**INTERACT-1B: tap_actions 元素未命中重试后成功**
通过 jugg-android-dev-loop 执行 `jugg restart`，传入 `projectDir` 与单步 `tap_actions=[{"text":"MCP Test Page"}]`。在目标元素存在但首帧未渲染的场景下，验证命令会进行短暂重试并最终返回 JSON 中 `status=OK`。

**INTERACT-1C: tap_actions 中途失败应返回失败步骤**
通过 jugg-android-dev-loop 执行 `jugg restart`，传入 `projectDir` 与多步 `tap_actions`，让第 2 步故意使用不存在的 selector。验证返回 JSON 中 `status=ERROR`，`errorCode` 与失败原因一致，`message` 含 `tap_actions step 2 failed`，且 `data.failedStep=2`。

**INTERACT-2: 坐标点击**
通过 jugg-android-dev-loop 执行 `jugg tap`，传入 `projectDir`、`x=540`、`y=960`，验证返回 JSON 中 `status` 为 `OK`。可通过前后截图对比确认点击生效。

**INTERACT-3: 百分比点击**
通过 jugg-android-dev-loop 执行 `jugg tap`，传入 `projectDir`、`xPercent=50`、`yPercent=50`，验证返回 JSON 中 `status` 为 `OK`，`data` 中 `mode` 为 `percent`，`screenWidth` 和 `screenHeight` 有值，`x` 和 `y` 为换算后的像素坐标。

**INTERACT-4: swipe 坐标模式**
在 `MCP Test Page` 的可滑动组件 `sv_mcp_swipe_target` 上执行：
1. 先通过 `jugg layout-dump` 确认 `Swipe Start Marker` 可见，`Swipe End Marker` 不可见（或不在当前可见区域）。
2. 通过 jugg-android-dev-loop 执行 `jugg tap`，传入 `projectDir`、`action="swipe"`，并使用 `sv_mcp_swipe_target` 区域内坐标作为起终点（例如从区域下半部分向上滑到上半部分，`duration=300`）。
3. 验证返回 JSON 中 `status` 为 `OK`，`data.action="swipe"`，`mode="coordinate"`，并包含起终点坐标。
4. 再次 `jugg layout-dump`，验证 `Swipe End Marker` 变为可见（且 `Swipe Start Marker` 不再处于初始位置），证明滑动发生在可滑动组件而非普通容器。

**INTERACT-5: longPress 百分比模式**
通过 jugg-android-dev-loop 执行 `jugg tap`，传入 `projectDir`、`action="longPress"`、`xPercent=50`、`yPercent=50`、`duration=800`，验证返回 JSON 中 `status` 为 `OK`，`data.action="longPress"`，`mode="percent"`，`duration=800`。

**INTERACT-6: 元素模式点击 - 按 text 精确匹配**
通过 `jugg layout-dump` 获取当前 UI 层级 JSON，找到一个有**唯一** `text` 属性的可见元素（确认该 text 在当前界面只出现一次），然后通过 jugg-android-dev-loop 执行 `jugg tap`，传入 `projectDir` 和 `text=<该元素的完整 text>`，验证返回 JSON 中 `status` 为 `OK`，`data.mode` 为 `element`，`data.matchedElement` 包含对应元素信息（结构化对象，含 `text/className/resourceId/contentDesc/bounds/centerX/centerY`）。注意：text 为精确匹配，子串不会命中。

**INTERACT-7: 元素模式点击 - 按 resourceId 匹配**
通过 `jugg layout-dump` 获取当前 UI 层级 JSON，找到一个有 `resourceId` 属性的元素，然后通过 jugg-android-dev-loop 执行 `jugg tap`，传入 `projectDir` 和 `resourceId=<该元素的 resourceId>`，验证返回 JSON 中 `status` 为 `OK`，`data.mode` 为 `element`。

**INTERACT-8: 元素模式点击 - 无匹配返回候选**
通过 jugg-android-dev-loop 执行 `jugg tap`，传入 `projectDir` 和 `text="ThisElementDoesNotExist_12345"`，验证返回 JSON 中 `status` 为 `ERROR`，`data.mode` 为 `element`，`data.matchCount` 为 0，且 `message` 中包含 "No matching UI element found" 以及可点击的候选元素列表。

**INTERACT-9: 元素模式点击 - 多匹配返回候选列表**
通过 `jugg layout-dump` 找到一个在当前界面出现多次的 `text`（如列表项的重复文字），通过 jugg-android-dev-loop 执行 `jugg tap`，传入 `projectDir` 和 `text=<该重复 text>`，验证返回 JSON 中 `status` 为 `ERROR`，`data.matchCount` > 1，`data.matches` 为数组且每个元素包含 `bounds`、`centerX`、`centerY`，`message` 中包含引导使用坐标或百分比模式的提示。Agent 应根据返回的坐标信息用 `jugg tap(x, y)` 进行二次精确点击。

补充：若 ViewHierarchy Server 不可用，`jugg layout-dump` / `jugg tap` 元素模式会直接返回 `ERROR`，不再回退到 `uiautomator dump`。

**INTERACT-10: 元素模式点击 - 隐藏重复节点不应计入匹配**
构造同 selector 的两个节点（一个可见、一个 `GONE/INVISIBLE` 或零尺寸），通过 jugg-android-dev-loop 执行 `jugg tap` 元素模式，验证不会返回"多匹配"，仅可见可操作节点会参与命中。

**INTERACT-10A: tap - 缺少必填参数**
通过 jugg-android-dev-loop 执行 `jugg tap`，仅传入 `projectDir`，不传 `x`、`y`、`xPercent`、`yPercent`、`text`、`resourceId`、`contentDesc`，验证返回 JSON 中 `status` 为 `ERROR`，`errorCode` 为 `INVALID_PARAMS`。

**INTERACT-10B: swipe - 缺少终点参数**
通过 jugg-android-dev-loop 执行 `jugg tap`，传入 `projectDir`、`action="swipe"`、`x=100`、`y=200`，不传 `endX/endY`，验证返回 JSON 中 `status` 为 `ERROR`，`errorCode` 为 `INVALID_PARAMS`。

**INTERACT-10C: swipe - 元素模式不支持**
通过 jugg-android-dev-loop 执行 `jugg tap`，传入 `projectDir`、`action="swipe"`、`text="Unique MCP Target"`，验证返回 JSON 中 `status` 为 `ERROR`，`errorCode` 为 `INVALID_PARAMS`。

**INTERACT-11: tap - 坐标模式优先于百分比模式**
通过 jugg-android-dev-loop 执行 `jugg tap`，同时传入 `projectDir`、`x=100`、`y=200`、`xPercent=50`、`yPercent=50`，验证返回 JSON 中 `status` 为 `OK`，`data.mode` 为 `coordinate`，`data.x` 为 100，`data.y` 为 200（优先使用坐标模式）。

**INTERACT-12: 重启应用（无 tap_actions）**
通过 jugg-android-dev-loop 执行 `jugg restart`，传入有效 `projectDir`，验证返回 JSON 中 `status` 为 `OK`，`message` 包含 "restart_app executed successfully"。未传 `tap_actions` 时，`data` 为空对象。
