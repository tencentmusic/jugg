# layout_verify 盲测评估题目

> 本文件是用于测试 AI agent 在工程 android_demo_project 使用 `layout_verify` 进行 UI 验证能力的盲测题目集。
> 任务完成前绝对不可以读取预期结果 `08_mcp_test_case_layout_verify_answer_key.md`。
> 最后更新：2026-03-07
> 前置文档：`08_mcp_test_case.md`（执行约定和工具使用方式）

---

## 执行指引

### 目的

本评估测试 AI agent 能否正确使用 `layout_verify` 验证 UI 需求，并准确报告 PASS/FAIL 结论。任务中包含"符合"（UI 满足需求）和"不符合"（UI 不满足需求）两种场景，混合排列。agent 不知道哪些任务属于哪种类型。

### 执行流程

1. Agent 导航到 `McpTestActivity`（从 MainActivity 点击 "MCP Test Page"）
2. Agent 调用 `layout_dump` 获取 `dumpFile` 路径
3. Agent 依次执行每个任务，使用 `layout_verify` 验证所述需求
4. 对于每个任务，agent 必须提供：
   - 明确的**结论**："UI 符合需求" 或 "UI 不符合需求"
   - **证据**：引用 `layout_verify` 响应中的 `data.actual` 和 `data.expected` 值
   - **不做额外操作**：如果 `layout_verify` 返回 FAIL，报告发现即可 — 不要尝试修复或重试
5. 所有任务完成后，保存测试结果到 `jugg-mcp-test-layout-verify-result.md`
6. 对比 `jugg-mcp-test-layout-verify-result.md` 和 `08_mcp_test_case_layout_verify_answer_key.md`，得出真正的校验结果，汇报给用户。

### 编号规则

所有任务使用前缀 `LV-EVAL-{group}-{seq}`。分组仅用于批次执行，不携带关于预期结果的语义信息。

### 分批策略（防止上下文溢出）

| 批次 | 任务范围 | 任务数量 |
|------|---------|---------|
| 1 | LV-EVAL-A-01 ~ A-12 | 12 |
| 2 | LV-EVAL-B-01 ~ B-12 | 12 |
| 3 | LV-EVAL-C-01 ~ C-12 | 12 |
| 4 | LV-EVAL-D-01 ~ D-12 | 12 |
| 5 | LV-EVAL-E-01 ~ E-12 | 12 |

### 每批次流程

1. **初始化**：`restart_app` → 导航到 McpTestActivity → `layout_dump` 获取 `dumpFile`
2. **执行**：按顺序处理每个任务；MCP 调用必须串行（一次一个）
3. **记录**：对每个任务记录：任务 ID、结论（符合 / 不符合）、证据摘要
4. **输出**：将结果写入 `layout-verify-eval-result.md`

### 重要说明

- 部分任务在验证前需要交互（点击）— 任务会明确说明
- 任何交互之后，必须重新调用 `layout_dump` 获取最新布局快照再进行验证
- 部分任务需要实时查询模式（不传 `dumpFile`）— 任务会明确说明
- 需要扩展测试页控件（`tv_mcp_colored_text`、`layout_mcp_alpha_bg` 等）的任务标记为 `[EXT]`

---

## 批次 1：LV-EVAL-A（12 题）

**LV-EVAL-A-01**
> 设计稿要求：MCP 测试页面上必须存在资源 ID 为 `btn_mcp_unique_text` 的按钮。
> 请验证该需求是否满足。

**LV-EVAL-A-02**
> 设计稿要求：`btn_mcp_unique_text` 的文本必须为 "Unique MCP Target"。
> 请验证该需求是否满足。

**LV-EVAL-A-03**
> 设计稿要求：`tv_mcp_action_state` 的文本必须为 "Ready for action"。
> 请验证该需求是否满足。

**LV-EVAL-A-04**
> 设计稿要求：`btn_mcp_visibility_visible` 的可见性必须为 visible。
> 请验证该需求是否满足。

**LV-EVAL-A-05**
> 设计稿要求：`btn_mcp_visibility_hidden` 的可见性必须为 invisible。
> 请验证该需求是否满足。

**LV-EVAL-A-06**
> 设计稿要求：页面上必须存在资源 ID 为 `btn_nonexistent_magic_element` 的按钮。
> 请验证该需求是否满足。

**LV-EVAL-A-07**
> 设计稿要求：`btn_mcp_unique_text` 的文本必须包含子串 "MCP"。
> 请验证该需求是否满足。

**LV-EVAL-A-08**
> 设计稿要求：`tv_mcp_action_state` 的文本必须匹配正则表达式 `Waiting.*interaction`。
> 请验证该需求是否满足。

**LV-EVAL-A-09**
> 设计稿要求：`btn_mcp_unique_text` 的文本必须为 "Submit Order"。
> 请验证该需求是否满足。

**LV-EVAL-A-10**
> 设计稿要求：页面上必须存在文本为 "MCP Test Page" 的元素。
> 请验证该需求是否满足。

**LV-EVAL-A-11**
> 设计稿要求：页面上必须存在 contentDescription 为 "mcp-resource-target" 的元素。
> 请验证该需求是否满足。

**LV-EVAL-A-12**
> 设计稿要求：`btn_mcp_resource_target` 的文本必须为 "Resource Tap Target"。
> 请验证该需求是否满足。

---

## 批次 2：LV-EVAL-B（12 题）

**LV-EVAL-B-01**
> 设计稿要求：`btn_mcp_unique_text` 必须是可点击的。
> 请验证该需求是否满足。

**LV-EVAL-B-02**
> 设计稿要求：`btn_mcp_unique_text` 必须处于启用（enabled）状态。
> 请验证该需求是否满足。

**LV-EVAL-B-03**
> 设计稿要求：`btn_mcp_unique_text` 的 alpha 必须为 1.0（完全不透明）。
> 请验证该需求是否满足。

**LV-EVAL-B-04**
> 设计稿要求：`btn_mcp_unique_text` 的 alpha 必须大于 0.5。
> 请验证该需求是否满足。

**LV-EVAL-B-05**
> 设计稿要求：`btn_mcp_unique_text` 的宽度必须恰好为 50dp。
> 请验证该需求是否满足。

**LV-EVAL-B-06**
> 设计稿要求：`btn_mcp_unique_text` 的宽度必须大于 0 像素。
> 请验证该需求是否满足。

**LV-EVAL-B-07**
> 设计稿要求：`btn_mcp_unique_text` 的宽度必须 >= 100dp。
> 请验证该需求是否满足。

**LV-EVAL-B-08**
> 设计稿要求：`sv_mcp_swipe_target` 的高度必须约为 220dp（容差 ±5dp）。
> 请验证该需求是否满足。

**LV-EVAL-B-09**
> 设计稿要求：`btn_mcp_unique_text` 的左边界（bounds.left）必须 >= 0。
> 请验证该需求是否满足。

**LV-EVAL-B-10**
> 设计稿要求：`tv_mcp_title` 必须位于屏幕上方区域（bounds.top < 500px）。
> 请验证该需求是否满足。

**LV-EVAL-B-11**
> 设计稿要求：`btn_mcp_unique_text` 的右边界（bounds.right）必须 > 300dp。
> 请验证该需求是否满足。

**LV-EVAL-B-12**
> 设计稿要求：`tv_mcp_action_state` 的 padding.left 必须 >= 0。
> 请验证该需求是否满足。

---

## 批次 3：LV-EVAL-C（12 题）

**LV-EVAL-C-01**
> 设计稿要求：`btn_mcp_unique_text` 和 `btn_mcp_resource_target` 之间的垂直间距必须约为 12dp（容差 ±3dp）。
> 请验证该需求是否满足。

**LV-EVAL-C-02**
> 设计稿要求：`btn_mcp_unique_text` 和 `btn_mcp_resource_target` 之间的垂直间距必须恰好为 100dp（无容差）。
> 请验证该需求是否满足。

**LV-EVAL-C-03**
> 设计稿要求：`tv_mcp_title` 和 `btn_mcp_unique_text` 之间的垂直间距必须约为 20dp（容差 ±5dp）。
> 请验证该需求是否满足。

**LV-EVAL-C-04**
> 设计稿要求：`btn_mcp_unique_text` 和 `btn_mcp_resource_target` 必须水平居中对齐。
> 请验证该需求是否满足。

**LV-EVAL-C-05**
> 设计稿要求：`btn_mcp_repeat_a` 和 `btn_mcp_repeat_b` 必须水平居中对齐。
> 请验证该需求是否满足。

**LV-EVAL-C-06**
> 设计稿要求：`tv_mcp_title` 必须位于 `btn_mcp_unique_text` 的上方（垂直顺序：标题在前）。
> 请验证该需求是否满足。

**LV-EVAL-C-07**
> 设计稿要求：`btn_mcp_unique_text` 必须位于 `tv_mcp_title` 的上方（垂直顺序：按钮在前）。
> 请验证该需求是否满足。

**LV-EVAL-C-08**
> 设计稿要求：`btn_mcp_unique_text` 和 `btn_mcp_resource_target` 不能互相重叠。
> 请验证该需求是否满足。

**LV-EVAL-C-09**
> 设计稿要求：`btn_mcp_unique_text` 必须包含在可滚动视图 `sv_mcp_swipe_target` 内部。
> 请验证该需求是否满足。

**LV-EVAL-C-10**
> 设计稿要求：`tv_mcp_title` 和 `btn_mcp_unique_text` 之间的垂直间距必须恰好为 100dp（无容差）。
> 请验证该需求是否满足。

**LV-EVAL-C-11**
> 设计稿要求：`btn_mcp_repeat_a` 和 `btn_mcp_repeat_b` 之间的垂直间距必须 > 0px。
> 请验证该需求是否满足。

**LV-EVAL-C-12**
> 设计稿要求：`btn_mcp_resource_target` 必须是可点击的，并且其文本必须为 "Resource Tap Target"。
> 请验证以上两个需求。

---

## 批次 4：LV-EVAL-D（12 题）

**LV-EVAL-D-01** `[实时查询]`
> 设计稿要求：`tv_mcp_title` 的文字大小必须为 20sp。
> 请使用实时查询模式验证（不传 dumpFile）。

**LV-EVAL-D-02** `[实时查询]`
> 设计稿要求：`tv_mcp_action_state` 的文字大小必须为 15sp。
> 请使用实时查询模式验证（不传 dumpFile）。

**LV-EVAL-D-03**
> 设计稿要求：页面上必须存在文本为 "Repeat Tap Target" 的元素。
> 请验证该需求是否满足。注意：可能存在多个具有该文本的元素。

**LV-EVAL-D-04**
> 设计稿要求：必须存在文本为 "MCP Test Page" 且 className 包含 "TextView" 的元素。
> 请验证该需求是否满足。

**LV-EVAL-D-05**
> 设计稿要求：资源 ID 为 `btn_does_not_exist` 的元素的文本必须为 "Hello"。
> 请验证该需求是否满足。

**LV-EVAL-D-06**
> 设计稿要求：以下 3 个按钮必须具有相同的宽度：
> 1. `btn_mcp_unique_text`
> 2. `btn_mcp_resource_target`
> 3. `btn_mcp_repeat_a`
> 请验证该一致性需求是否满足。

**LV-EVAL-D-07** `[需要交互]`
> 设计稿要求：点击 `btn_mcp_unique_text` 后，`tv_mcp_action_state` 的文本必须变为 "Clicked: Unique MCP Target"。
> 请点击按钮，获取最新布局快照，然后验证。

**LV-EVAL-D-08** `[需要交互]`
> 前置条件：调用 `restart_app` 重置应用状态并导航到 MCP 测试页面。
> 设计稿要求：点击 `btn_mcp_resource_target` 后，`tv_mcp_action_state` 的文本必须变为 "Clicked: Resource Tap Target"。
> 请点击按钮，获取最新布局快照，然后验证。

**LV-EVAL-D-09** `[需要交互]`
> 前置条件：调用 `restart_app` 重置应用状态并导航到 MCP 测试页面。
> 设计稿要求：点击 `btn_mcp_unique_text` 后，以下条件必须全部为真：
> 1. `tv_mcp_action_state` 的文本为 "Clicked: Unique MCP Target"
> 2. `btn_mcp_unique_text` 仍然存在且可见
> 3. `btn_mcp_unique_text` 仍然可点击
> 请点击按钮，获取最新布局快照，然后验证以上三项。

**LV-EVAL-D-10** `[需要交互]`
> 前置条件：调用 `restart_app` 重置应用状态并导航到 MCP 测试页面。
> 设计稿要求：`tv_mcp_action_state` 的文本必须恰好是 "Clicked"（就是这个文本，不多不少）。
> 请点击 `btn_mcp_unique_text`，获取最新布局快照，然后验证。

**LV-EVAL-D-11**
> 设计稿要求：`btn_mcp_unique_text` 的可见性必须为 gone。
> 请验证该需求是否满足。

**LV-EVAL-D-12**
> 设计稿要求：`tv_mcp_title` 的文本必须为 "MCP Test Page"。
> 请验证该需求是否满足。

---

## 批次 5：LV-EVAL-E（12 题）

**LV-EVAL-E-01** `[EXT]`
> 设计稿要求：`tv_mcp_colored_text` 的文字颜色必须为 `#FF1976D2`（蓝色）。
> 请验证该需求是否满足。

**LV-EVAL-E-02** `[EXT]`
> 设计稿要求：`layout_mcp_alpha_bg` 的背景颜色必须为 `#1F88939B`（alpha ≈ 12%）。
> 请验证该需求是否满足。

**LV-EVAL-E-03**
> 设计稿要求：`tv_mcp_title` 的文字颜色不能为白色（`#FFFFFFFF`）。
> 请验证该需求是否满足。

**LV-EVAL-E-04** `[EXT]`
> 设计稿要求：`tv_mcp_colored_text` 的文字颜色必须为 `#FFFF0000`（红色）。
> 请验证该需求是否满足。

**LV-EVAL-E-05** `[EXT]` `[实时查询]`
> 设计稿要求：`tv_mcp_long_text` 必须有 maxLines = 1 且 ellipsize = "end"。
> 请使用实时查询模式验证以上两个属性。

**LV-EVAL-E-06**
> 设计稿要求：`btn_mcp_visibility_visible` 和 `btn_mcp_visibility_hidden` 的文本必须相同，都是 "Visibility Tap Target"。
> 请验证该一致性需求是否满足。

**LV-EVAL-E-07**
> 设计稿要求：对 "Unique MCP Target" 按钮进行完整验证：
> 1. 按钮存在
> 2. 文本正确（"Unique MCP Target"）
> 3. 按钮可点击
> 4. 按钮可见（visibility = visible 或存在）
> 5. 按钮宽度 > 200dp
> 请验证以上五个需求。

**LV-EVAL-E-08**
> 设计稿要求：验证 `btn_mcp_unique_text` 和 `btn_mcp_resource_target` 的布局关系：
> 1. 两者水平居中对齐
> 2. 垂直间距约为 12dp（±3dp）
> 3. `btn_mcp_unique_text` 位于 `btn_mcp_resource_target` 上方（垂直顺序）
> 4. 两者不重叠
> 请验证以上四个关系需求。

**LV-EVAL-E-09**
> 设计稿要求："Visibility Tap Target" 元素：
> 1. `btn_mcp_visibility_visible` 可见性 = visible
> 2. `btn_mcp_visibility_hidden` 可见性 = invisible
> 3. 两者文本相同："Visibility Tap Target"
> 请验证以上三项。

**LV-EVAL-E-10** `[实时查询]`
> 设计稿要求：对 `tv_mcp_title` 的综合检查：
> 1. 文本为 "MCP Test Page"
> 2. 字体大小为 20sp（使用实时查询）
> 3. 元素存在且可见
> 请验证以上三项。

**LV-EVAL-E-11** `[EXT]` `[需要交互]`
> 设计稿要求：点击 `btn_mcp_show_dialog` 后应出现对话框。对话框出现后：
> 1. 重新获取布局快照
> 2. 验证对话框标题控件的文本是否符合预期值
> 3. 验证对话框内容控件的文本是否符合预期值
> 请执行交互并验证。

**LV-EVAL-E-12**
> 设计稿要求：`btn_mcp_unique_text` 的文本必须为 "Complete Purchase"。
> 请验证该需求是否满足。

---

## 结果记录格式

完成所有任务后，按以下格式输出结果：

```markdown
| 任务 ID | 结论 | 证据摘要 |
|---------|------|---------|
| LV-EVAL-A-01 | 符合 / 不符合 | data.result=PASS/FAIL, actual=..., expected=... |
| ... | ... | ... |
```

最终统计：
- 总任务数：60
- 符合：?
- 不符合：?
- 错误（元素未找到等）：?
