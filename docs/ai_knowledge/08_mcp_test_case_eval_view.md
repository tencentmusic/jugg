# eval_view 盲测评估题目

> 本文件是用于测试 AI agent 在工程 android_demo_project 使用 `eval_view` 进行 View 属性查询能力的盲测题目集。
> 任务完成前绝对不可以读取预期结果 `08_mcp_test_case_eval_view_answer_key.md`。
> 最后更新：2026-03-09
> 前置文档：`08_mcp_test_case.md`（执行约定和工具使用方式）

---

## 执行指引

### 目的

本评估测试 AI agent 能否正确使用 `eval_view` 查询 View 运行时属性，并准确解读返回值给出结论。任务中包含"符合"（实际值满足需求）和"不符合"（实际值不满足需求）两种场景，混合排列。agent 不知道哪些任务属于哪种类型。

### 执行流程

1. Agent 导航到 `McpTestActivity`（从 MainActivity 点击 "MCP Test Page"）
2. Agent 依次执行每个任务，调用 `eval_view` 查询指定表达式
3. 对于每个任务，agent 必须提供：
   - 明确的**结论**："符合需求" 或 "不符合需求"
   - **证据**：引用 `eval_view` 返回的 `data.values[*].value` 和 `data.values[*].type`
   - **不做额外操作**：如果返回值不符合需求，报告发现即可 — 不要尝试修复
4. 对于需要 px→dp 转换的任务，agent 应使用返回的 `data.density` 进行换算
5. 所有任务完成后，保存测试结果到 `jugg-mcp-test-eval-view-result.md`
6. 对比 `jugg-mcp-test-eval-view-result.md` 和 `08_mcp_test_case_eval_view_answer_key.md`，得出校验结果，汇报给用户。

### 编号规则

所有任务使用前缀 `EV-EVAL-{group}-{seq}`。分组仅用于批次执行，不携带关于预期结果的语义信息。

### 分批策略（防止上下文溢出）

| 批次 | 任务范围 | 任务数量 |
|------|---------|---------|
| 1 | EV-EVAL-A-01 ~ A-10 | 10 |
| 2 | EV-EVAL-B-01 ~ B-10 | 10 |
| 3 | EV-EVAL-C-01 ~ C-10 | 10 |

### 每批次流程

1. **初始化**：`restart_app` → 导航到 McpTestActivity
2. **执行**：按顺序处理每个任务；MCP 调用必须串行（一次一个）
3. **记录**：对每个任务记录：任务 ID、结论（符合 / 不符合）、证据摘要
4. **输出**：将结果写入 `jugg-mcp-test-eval-view-result.md`

### 重要说明

- `eval_view` 返回原始值，agent 需要自行判断是否满足需求
- 数值类属性返回 px 值，需要使用 `data.density` 转换为 dp/sp 进行比较
- 颜色值以 int 返回（如 `-7829368`），agent 需转换为 `#AARRGGBB` 格式比较
- 本题集默认基线页面为 `android_demo_project/app/src/main/res/layout/activity_mcp_test.xml`
- 页面发生任何改动时，必须同步更新本文件与 `08_mcp_test_case_eval_view_answer_key.md`

---

## 批次 1：EV-EVAL-A（10 题）

**EV-EVAL-A-01**
> 设计稿要求：`btn_mcp_unique_text` 的文本必须为 "Unique MCP Target"。
> 请使用 `eval_view` 的 `getText()` 表达式查询并验证。

**EV-EVAL-A-02**
> 设计稿要求：`tv_mcp_title` 的文本必须为 "MCP Test Page"。
> 请使用 `eval_view` 的 `getText()` 表达式查询并验证。

**EV-EVAL-A-03**
> 设计稿要求：`tv_mcp_action_state` 的文本必须为 "Ready for action"。
> 请使用 `eval_view` 的 `getText()` 表达式查询并验证。

**EV-EVAL-A-04**
> 设计稿要求：`btn_mcp_unique_text` 必须是可点击的。
> 请使用 `eval_view` 的 `isClickable()` 表达式查询并验证。

**EV-EVAL-A-05**
> 设计稿要求：`btn_mcp_unique_text` 的文本必须为 "Submit Order"。
> 请使用 `eval_view` 的 `getText()` 表达式查询并验证。

**EV-EVAL-A-06**
> 设计稿要求：`tv_mcp_title` 的文字大小必须为 20sp。
> 请使用 `eval_view` 的 `getTextSize()` 查询，结合 `data.density` 转换为 sp 后验证。
> 提示：`sp = px / density`（此处假设系统字体缩放为 1.0）。

**EV-EVAL-A-07**
> 设计稿要求：`tv_mcp_action_state` 的文字大小必须为 20sp。
> 请使用 `eval_view` 的 `getTextSize()` 查询，结合 `data.density` 转换为 sp 后验证。

**EV-EVAL-A-08**
> 设计稿要求：`btn_mcp_unique_text` 的 alpha 值必须为 1.0（完全不透明）。
> 请使用 `eval_view` 的 `getAlpha()` 表达式查询并验证。

**EV-EVAL-A-09**
> 设计稿要求：`tv_mcp_title` 的最大行数（maxLines）必须为 1。
> 请使用 `eval_view` 的 `getMaxLines()` 表达式查询并验证。
> 注意：这是 `layout_verify` 无法查询的属性。

**EV-EVAL-A-10**
> 设计稿要求：`btn_mcp_resource_target` 的文本必须为 "Resource Tap Target"。
> 请使用 `eval_view` 的 `getText()` 表达式查询并验证。

---

## 批次 2：EV-EVAL-B（10 题）

**EV-EVAL-B-01**
> 设计稿要求：`tv_mcp_title` 的文字颜色必须为 `#FF000000`（纯黑色）。
> 请使用 `eval_view` 的 `getCurrentTextColor()` 查询并转换为 `#AARRGGBB` 格式验证。
> 提示：返回值是 int，需转为十六进制。

**EV-EVAL-B-02**
> 设计稿要求：`tv_mcp_title` 的文字颜色必须为 `#FFFF0000`（红色）。
> 请使用 `eval_view` 的 `getCurrentTextColor()` 查询并转换为 `#AARRGGBB` 格式验证。

**EV-EVAL-B-03**
> 设计稿要求：`btn_mcp_unique_text` 必须处于启用（enabled）状态。
> 请使用 `eval_view` 的 `isEnabled()` 表达式查询并验证。

**EV-EVAL-B-04**
> 设计稿要求：`btn_mcp_visibility_hidden` 的可见性必须为 VISIBLE。
> 请使用 `eval_view` 的 `getVisibility()` 查询并验证。
> 提示：`getVisibility()` 返回 int（0=VISIBLE, 4=INVISIBLE, 8=GONE）。

**EV-EVAL-B-05**
> 设计稿要求：`btn_mcp_visibility_visible` 的可见性必须为 VISIBLE。
> 请使用 `eval_view` 的 `getVisibility()` 查询并验证。

**EV-EVAL-B-06**
> 设计稿要求：`btn_mcp_unique_text` 的文本必须包含子串 "MCP"。
> 请使用 `eval_view` 的 `getText()` 查询，然后自行判断返回值是否包含 "MCP"。

**EV-EVAL-B-07**
> 设计稿要求：查询 `tv_mcp_title` 的以下三个属性并验证全部满足：
> 1. 文本为 "MCP Test Page"
> 2. 可见性为 VISIBLE（getVisibility() == 0）
> 3. 可点击为 false
> 请使用一次 `eval_view` 调用批量查询 `getText()`、`getVisibility()`、`isClickable()` 三个表达式。

**EV-EVAL-B-08**
> 设计稿要求：`btn_mcp_unique_text` 的宽度必须恰好为 50dp。
> 请使用 `eval_view` 的 `getWidth()` 查询（返回 px），结合 `data.density` 转换为 dp 后验证。

**EV-EVAL-B-09**
> 设计稿要求：`tv_mcp_case_group_summary` 的文字大小必须在 13sp 到 15sp 之间（含边界）。
> 请使用 `eval_view` 的 `getTextSize()` 查询，结合 `data.density` 转换为 sp 后验证。

**EV-EVAL-B-10**
> 设计稿要求：查询不存在的元素 `btn_nonexistent_magic_element` 的文本。
> 请使用 `eval_view` 查询 `getText()`，验证工具返回元素未找到的错误。

---

## 批次 3：EV-EVAL-C（10 题）

**EV-EVAL-C-01**
> 设计稿要求：`tv_mcp_title` 的行数（lineCount）必须为 1。
> 请使用 `eval_view` 的 `getLineCount()` 表达式查询并验证。

**EV-EVAL-C-02**
> 设计稿要求：`tv_mcp_action_state` 的文本必须匹配 "Waiting" 前缀。
> 请使用 `eval_view` 的 `getText()` 查询，然后自行判断返回值是否以 "Waiting" 开头。

**EV-EVAL-C-03**
> 设计稿要求：`btn_mcp_unique_text` 的 paddingLeft 必须为 0dp。
> 请使用 `eval_view` 的 `getPaddingLeft()` 查询（返回 px），结合 `data.density` 转换为 dp 后验证。
> 提示：按钮的 padding 可能不为 0（Material 按钮有默认 padding）。

**EV-EVAL-C-04** `[需要交互]`
> 前置条件：调用 `restart_app` 重置应用状态并导航到 MCP 测试页面。
> 设计稿要求：点击 `btn_mcp_unique_text` 后，`tv_mcp_action_state` 的文本必须为 "Clicked: Unique MCP Target"。
> 请先点击按钮，再使用 `eval_view` 的 `getText()` 查询 `tv_mcp_action_state` 并验证。

**EV-EVAL-C-05**
> 设计稿要求：`btn_mcp_unique_text` 的高度必须大于 0dp。
> 请使用 `eval_view` 的 `getHeight()` 查询（返回 px），结合 `data.density` 转换为 dp 后验证。

**EV-EVAL-C-06**
> 设计稿要求：`tv_mcp_title` 的 ellipsize 模式必须为 "END"。
> 请使用 `eval_view` 的 `getEllipsize()` 查询并验证。
> 注意：如果 ellipsize 未设置，`getEllipsize()` 返回 null。

**EV-EVAL-C-07**
> 设计稿要求：`btn_mcp_unique_text` 的 gravity 值中必须包含居中标志。
> 请使用 `eval_view` 的 `getGravity()` 查询并验证。
> 提示：`getGravity()` 返回 int（Android Gravity 位掩码；CENTER=17，CENTER_HORIZONTAL=1，CENTER_VERTICAL=16）。

**EV-EVAL-C-08**
> 设计稿要求：使用一次调用查询 `btn_mcp_resource_target` 的以下属性：
> 1. getText() 的值为 "Resource Tap Target"
> 2. isClickable() 为 true
> 3. isEnabled() 为 true
> 4. getAlpha() 为 1.0
> 请使用一次 `eval_view` 调用批量查询并验证以上四项。

**EV-EVAL-C-09**
> 设计稿要求：`tv_mcp_action_state` 的文本必须恰好为 "Clicked"。
> 请使用 `eval_view` 的 `getText()` 查询并验证（注意：不要先点击任何按钮）。

**EV-EVAL-C-10** `[需要交互]`
> 前置条件：调用 `restart_app` 重置应用状态并导航到 MCP 测试页面。
> 设计稿要求：点击 `btn_mcp_repeat_a` 后，`tv_mcp_action_state` 的文本必须为 "Clicked: Repeat Tap Target"。
> 请先点击按钮，再使用 `eval_view` 的 `getText()` 查询 `tv_mcp_action_state` 并验证。

---

## 结果记录格式

完成所有任务后，按以下格式输出结果：

```markdown
| 任务 ID | 结论 | 证据摘要 |
|---------|------|---------|
| EV-EVAL-A-01 | 符合 / 不符合 | value="...", type=..., expected=... |
| ... | ... | ... |
```

最终统计：
- 总任务数：30
- 符合：?
- 不符合：?
- 错误（元素未找到等）：?
