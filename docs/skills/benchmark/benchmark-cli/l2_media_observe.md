# L2 运行时观察 / UI 检查

目标：验证 Agent 能使用当前公开 CLI 做运行时观察、布局导出、元素定位、属性读取和日志等待。截图和录屏不是当前公开 CLI，本文件不再包含相关用例。

## McpTestActivity 路由

涉及 `McpTestActivity` 或该页面内元素的 case，先执行：

```bash
jugg restart && sleep 2 && jugg tap --text "MCP Test Page"
```

路由后必须用 `activity-stack` 或 `layout-dump` 确认已进入 `McpTestActivity`。路由失败或确认失败时记 `SKIP: page route failed`，不得直接执行目标 selector。

## OBS-1: 查看 Activity 栈

Prompt：确认当前前台 Activity 是什么。

期望：
- 选择 `activity-stack`。
- 记录前台 Activity 名称。
- 无设备或 app 未运行时记录实际错误，不改用 adb。

## OBS-2: 导出当前布局

Prompt：导出当前页面布局，包含 GONE 节点，方便后续选择稳定 selector。

期望：
- 选择 `layout-dump`。
- 使用 `--include-gone`。
- 记录 HTML 或结构化输出的相对路径/摘要。

## OBS-3: 导出全部窗口

Prompt：页面可能有弹窗，请导出所有 window 的布局。

期望：
- 选择 `layout-dump --all-windows`。
- 不使用过期 `--root` 参数；子树参数应为 `--root-layout`。

## OBS-4: 通过文本定位元素

Prompt：在 McpTestActivity 页面找到文本为 `Unique MCP Target` 的按钮，并报告位置和大小。

期望：
- 先执行 McpTestActivity 路由命令，并记录 gate 证据。
- 选择 `view-locate --text "Unique MCP Target"`。
- 结果应包含 bounds 或坐标信息。

## OBS-5: 通过 resourceId 定位元素

Prompt：找到 resource id 为 `btn_mcp_resource_target` 的元素。

期望：
- 先执行 McpTestActivity 路由命令，并记录 gate 证据。
- 选择 `view-locate --resource-id btn_mcp_resource_target`。
- 不使用过期 `--id`。

## OBS-6: 读取 View 属性

Prompt：读取 `btn_mcp_resource_target` 的文本、可点击状态和 enabled 状态。

期望：
- 先执行 McpTestActivity 路由命令，并记录 gate 证据。
- 选择 `view-inspect`。
- selector 使用 `--resource-id btn_mcp_resource_target`。
- expressions 至少包含文本、clickable、enabled 相关表达式。

## OBS-7: 定位无匹配元素

Prompt：确认页面上不存在文本为 `NonExistentElementXYZ` 的元素。

期望：
- 选择 `view-locate --text "NonExistentElementXYZ"`。
- 将“未找到”作为预期结果记录，不为了通过而改用模糊 selector。

## LOG-1: 等待日志 marker

Prompt：重启 app 后等待日志中出现 `[JUGG_BENCH] MAIN_ACTIVITY_READY`，最多等 3 秒。

期望：
- 先执行 `restart`。
- 再执行 `wait-logs --marker '\[JUGG_BENCH\] MAIN_ACTIVITY_READY' --timeout-ms 3000`。
- marker、crash、timeout 都是有效结构化结果；命令不能无限等待。

## LOG-2: wait-logs 缺少 marker

Prompt：验证日志等待命令没有 marker 时会被正确拒绝。

期望：
- Agent 应知道 `--marker` 必填。
- 若执行缺参命令，应把本地参数错误判为预期失败。
