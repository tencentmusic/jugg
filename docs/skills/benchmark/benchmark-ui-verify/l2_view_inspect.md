# L2 Unit: view-inspect

目标：验证 Agent 能用 `view-inspect` 读取 View 属性，并选择正确 selector 与 expression。

## INSPECT-1: 读取文本

Prompt：读取 `tv_mcp_body_text` 的文本。

期望：
- 选择 `view-inspect --resource-id tv_mcp_body_text getText().toString()`。
- 结论包含 `Body Text Sample` 或实际返回值。

## INSPECT-2: 读取文字颜色

Prompt：读取 `tv_mcp_style_title` 的文字颜色。

期望：
- 选择 `view-inspect --resource-id tv_mcp_style_title getCurrentTextColor()`。
- 不用 `view-locate` 的静态字段替代 getter。

## INSPECT-3: 读取字号

Prompt：读取 `tv_mcp_style_title` 的 textSize。

期望：
- 选择 `view-inspect --resource-id tv_mcp_style_title getTextSize()`。
- 报告实际数值和单位不确定性。

## INSPECT-4: 读取背景

Prompt：读取 `view_mcp_bg_block` 的背景对象。

期望：
- 选择 `view-inspect --resource-id view_mcp_bg_block getBackground()`。
- 记录返回摘要。

## INSPECT-5: 读取尺寸

Prompt：读取 `iv_mcp_icon` 的宽高。

期望：
- 选择 `view-inspect --resource-id iv_mcp_icon getWidth() getHeight()`。
- 一次调用可传多个 expression。

## INSPECT-6: 读取 padding

Prompt：读取 `tv_mcp_label` 的左 padding。

期望：
- 选择 `view-inspect --resource-id tv_mcp_label getPaddingLeft()`。

## INSPECT-7: 批量读取样式属性

Prompt：一次性读取 `tv_mcp_style_title` 的文本、文字颜色和字号。

期望：
- 选择 `view-inspect --resource-id tv_mcp_style_title getText().toString() getCurrentTextColor() getTextSize()`。
- 不拆成多次调用，除非 CLI 返回表达式级失败。

## INSPECT-8: 读取 clickable/enabled/alpha

Prompt：验证 `btn_mcp_resource_target` 的文本、可点击、enabled 和 alpha。

期望：
- 选择 `view-inspect --resource-id btn_mcp_resource_target getText().toString() isClickable() isEnabled() getAlpha()`。

## INSPECT-9: 读取 INVISIBLE 节点 visibility

Prompt：读取 `btn_mcp_visibility_hidden` 的 visibility。

期望：
- 选择 `view-inspect --resource-id btn_mcp_visibility_hidden getVisibility()`。
- 允许 inspect `INVISIBLE` 节点，但结论必须说明它不是可点击目标。

## INSPECT-10: className 辅助过滤

Prompt：读取文本为 `Resource Tap Target` 的 Button 是否 enabled。

期望：
- 选择 `view-inspect --text "Resource Tap Target" --class-name android.widget.Button isEnabled()` 或等价 selector。
- 不把 className 写成独立命令。

## INSPECT-11: expression 缺失

Prompt：验证属性读取没有表达式时不会成功。

期望：
- Agent 应知道 `view-inspect` 需要 selector 加至少一个 expression。
- 若执行缺参命令，应把参数错误判为预期失败。
