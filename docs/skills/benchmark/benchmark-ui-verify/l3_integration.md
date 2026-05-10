# L3 Integration

目标：验证 Agent 能把当前公开 UI CLI 组合成可执行检查流程，并在页面不满足前提时正确跳过。

## INT-1: 页面健康检查

Prompt：确认当前在 McpTestActivity，并读取页面标题、样式标题和按钮状态。

期望：
- 先执行 `activity-stack` 作为 gate。
- gate 通过后执行 `view-inspect --resource-id tv_mcp_title getText().toString()`。
- 执行 `view-inspect --resource-id tv_mcp_style_title getText().toString() getCurrentTextColor()`。
- 执行 `view-inspect --resource-id btn_mcp_unique_text isClickable() isEnabled()`。

## INT-2: layout-dump 后选择 selector

Prompt：导出布局，从中选择稳定 selector，再定位 `Resource Tap Target`。

期望：
- 先执行 `layout-dump`。
- 优先选择 resourceId。
- 再执行 `view-locate --resource-id btn_mcp_resource_target`。

## INT-3: 点击后验证状态文本

Prompt：点击 `Unique MCP Target` 后，验证状态文本变为 `Clicked: Unique MCP Target`。

期望：
- gate 确认 McpTestActivity。
- 执行 `tap --text "Unique MCP Target"`。
- 执行 `view-inspect --resource-id tv_mcp_action_state getText().toString()`。
- 精确比较返回文本，不只判断非空。

## INT-4: 两个按钮连续点击验证

Prompt：先点击 `Unique MCP Target`，再点击 `Resource Tap Target`，分别验证状态文本。

期望：
- 第一次点击用 `tap --text "Unique MCP Target"`。
- 第一次验证 `tv_mcp_action_state`。
- 第二次点击用 `tap --resource-id btn_mcp_resource_target`。
- 第二次验证 `tv_mcp_action_state`。

## INT-5: 滑动区域验证

Prompt：确认 `Swipe Verification Area` 存在，然后在滑动区域向上滑动，最后尝试定位 `Swipe End Marker`。

期望：
- 先用 `view-locate --text "Swipe Verification Area"` 或 `layout-dump` 确认区域。
- 执行 `tap --action swipe --x-percent 50 --y-percent 80 --end-x-percent 50 --end-y-percent 20 --duration 300`。
- 再执行 `view-locate --text "Swipe End Marker"`。

## INT-6: deploy 后页面仍可观察

Prompt：执行一次常规验证部署，部署后确认还能观察 McpTestActivity。

期望：
- 默认执行 `deploy`。
- 部署成功后执行 `activity-stack`。
- 若仍在 McpTestActivity，再执行 `layout-dump` 或 `view-locate --text "MCP Test Page"`。

## INT-7: 不在目标页面时跳过

Prompt：验证 `btn_mcp_resource_target` 是否可点击。

期望：
- 先通过 `activity-stack` 或 `layout-dump` 确认页面。
- 如果不在 McpTestActivity，结果为 `SKIP: not on McpTestActivity`。
- 不盲目执行 `tap`。
