# L2 Unit: view-locate

目标：验证 Agent 能用 `view-locate` 完成元素定位，并正确处理多匹配、不存在、不可见和 contentDescription 场景。

## LOC-1: 文本精确定位

Prompt：在 McpTestActivity 找到文本为 `Unique MCP Target` 的按钮。

期望：
- 选择 `view-locate --text "Unique MCP Target"`。
- 返回唯一元素的 bounds/中心点。

## LOC-2: resourceId 定位

Prompt：找到 resource id 为 `btn_mcp_resource_target` 的按钮。

期望：
- 选择 `view-locate --resource-id btn_mcp_resource_target`。
- 不使用旧参数 `--id`。

## LOC-3: contentDescription 定位

Prompt：找到 content description 为 `mcp-resource-target` 的元素。

期望：
- 选择 `view-locate --content-desc mcp-resource-target`。
- 不把 contentDescription 当成 text。

## LOC-4: 多匹配文本

Prompt：定位文本为 `Repeat Tap Target` 的元素。

期望：
- 选择 `view-locate --text "Repeat Tap Target"`。
- 如果返回多匹配或候选列表，应报告歧义，不随机选一个。

## LOC-5: 不存在元素

Prompt：确认页面上不存在文本为 `NonExistentElementXYZ` 的元素。

期望：
- 选择 `view-locate --text "NonExistentElementXYZ"`。
- 未找到是预期结果；不得改成模糊匹配。

## LOC-6: 可见元素优先

Prompt：定位文本为 `Visibility Tap Target` 的可见按钮。

期望：
- 选择 `view-locate --text "Visibility Tap Target"`。
- 隐藏的 `btn_mcp_visibility_hidden` 不应被当作可点击目标。

## LOC-7: 深层嵌套文本

Prompt：找到文本为 `Nested Label` 的元素。

期望：
- 选择 `view-locate --text "Nested Label"`。
- 记录它在父容器中的实际位置。

## LOC-8: 图标 contentDescription

Prompt：找到 content description 为 `mcp icon` 的图标。

期望：
- 选择 `view-locate --content-desc "mcp icon"`。
- 如果当前滚动位置不可见，应先说明需要页面状态或使用 `layout-dump` 取证。

## LOC-9: 屏幕外元素

Prompt：找到文本为 `Swipe End Marker` 的元素。

期望：
- 先尝试 `view-locate --text "Swipe End Marker"`。
- 如果不可见，应报告当前视口未命中；不能假造坐标。

## LOC-10: selector 缺失

Prompt：验证元素定位命令没有 selector 时不会成功。

期望：
- Agent 应知道 `view-locate` 需要 `--text`、`--resource-id` 或 `--content-desc`。
- 若执行缺参命令，应把参数错误判为预期失败。
