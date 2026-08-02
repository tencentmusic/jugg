# Step 1：MCP 工具文档对齐

> 方向文档，不含具体实现方案。执行前需与 agent 重新讨论实现细节。

## 目标

让 `08_mcp_usage.md` 和 `08_mcp_design.md` 准确反映新三工具模型，消除对 `layout-dump` 的 LLM 直接依赖描述。

## 方向

### 08_mcp_usage.md

- 工具列表新增 `view-locate`、`figma_layout_verify`，并标注 `layout-dump` 为内部工具（LLM 不直接调用）
- `figma_layout_verify` 的参数表移除 `androidJsonPath`（或标注为 internal/auto）
- 推荐工作流改为：`get_design_context` → `figma_layout_verify`（无 layout_dump 步骤）
- `layout_verify` 标注为已废弃，指向新工具
- `view-locate` 补充参数说明与返回格式

### 08_mcp_design.md

- 第 7.2 工具体系表：更新 `figma_layout_verify` 状态，说明 androidJsonPath 内部化
- 工具隐藏边界明确写清：`layout-dump` 仅供内部调用，不出现在 LLM 可见工具列表

## 验收方向

读完文档的 LLM 能正确推断：调用 `figma_layout_verify` 不需要先调用 `layout-dump`。
