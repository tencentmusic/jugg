# Fixtures 说明

本目录存放 `figma-layout-verify` 工具所需的 Figma 设计稿 JSON 文件。

## 文件列表

| 文件 | 说明 | 状态 |
|------|------|------|
| `mcp_test_main.json` | McpTestActivity 正确设计稿，坐标与 §4.2 spec 对齐 | **待人工导出** |
| `mcp_test_wrong_spacing.json` | 故意偏差版本（调整 2~3 个 spacing/margin 值），用于 L2/L4 负例 | **待人工导出** |

## 导出步骤

1. 在 Figma 中按 `docs/task/plan_step3_benchmark_rebuild_detail.md` §4.2 坐标表手绘 `McpTestActivity` Frame
2. 获取节点的 Figma URL（含 `node-id` 参数）
3. 调用 `get_design_context` 工具，传入该 URL
4. 将输出 JSON 保存至本目录 `mcp_test_main.json`
5. 复制一份，手动调整 2~3 个节点的 spacing/margin 值，保存为 `mcp_test_wrong_spacing.json`

## Figma 节点命名规范

Figma layer name 必须与 Android `android:id`（去掉 `@+id/` 前缀）完全一致，否则 `figma-layout-verify` 无法匹配节点。

无 resourceId 的节点（如 Repeat Tap Target）使用 `android:text` 的完整文本作为 layer name。
