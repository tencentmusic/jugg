# Step 0：figma_layout_verify 接口边界确认

> 方向文档，不含具体实现方案。执行前需与 agent 重新讨论实现细节。

## 目标

彻底隐藏 `layout-dump`，LLM 完全不感知它的存在。

## 方向

采用 **Method A**：`figma_layout_verify` 内部自动调用 `layout-dump`，LLM 不需要传 `androidJsonPath`。

- `androidJsonPath` 参数改为可选或完全移除
- 工具内部按 `projectDir` 自动完成 layout_dump 调用
- LLM 侧接口只需传 `figmaJsonPath`（或 figma JSON 内容）和 `dpr`

## 影响范围

- MCP 工具代码：`figma_layout_verify` 的 MCP action 实现
- `McpToolSchemas.kt` 中的 input schema（移除或标记 `androidJsonPath` 为 optional）
- 文档同步（由 Step 1 处理）

## 验收方向

LLM 调用 `figma_layout_verify` 时无需提前调用 `layout-dump`，工具能正常返回验证结果。
