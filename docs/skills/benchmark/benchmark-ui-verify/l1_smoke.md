# L1 Smoke

目标：用最少用例确认 Agent 会使用当前公开 UI CLI，而不是旧内部工具名。

## SMOKE-1: 当前页面门禁

Prompt：确认当前前台页面是否是 McpTestActivity。

期望：
- 选择 `activity-stack`。
- 结论明确写当前 Activity。
- 如果不是 McpTestActivity，后续依赖页面的用例应 `SKIP`。

## SMOKE-2: 导出布局

Prompt：导出当前页面布局，作为 UI 验证证据。

期望：
- 选择 `layout-dump`。
- 可加 `--include-gone`，但不强制。
- 记录输出文件或结构化摘要。

## SMOKE-3: 文本定位

Prompt：找到文本为 `Unique MCP Target` 的按钮，告诉我它的位置和大小。

期望：
- 选择 `view-locate --text "Unique MCP Target"`。
- 返回 bounds/坐标类信息。
- 使用当前公开 CLI 名称。

## SMOKE-4: 属性读取

Prompt：读取 resource id 为 `tv_mcp_style_title` 的文本内容。

期望：
- 选择 `view-inspect --resource-id tv_mcp_style_title getText().toString()`。
- 结论包含实际文本。
- 使用当前公开 CLI 名称。

## SMOKE-5: 安全点击

Prompt：测试页面已确认安全，请点击文本为 `Unique MCP Target` 的按钮。

期望：
- 先确认页面 gate，或使用已有 gate 证据。
- 选择 `tap --text "Unique MCP Target"`。
- 没有安全声明时应跳过点击。
