# MCP UI 验证评估答案表

> ⚠️ 本文件是盲测评估的统一答案表。被测 agent 绝对不能看到此文件。
> 在 agent 完成 `08_mcp_test_case_ui_verify.md` 中的全部任务后，使用本文件进行评分。
> 最后更新：2026-03-09

---

## Part A：layout_verify 评估答案


> ⚠️ 本文件是盲测评估的答案表。被测 agent 绝对不能看到此文件。
> 在 agent 完成 `08_mcp_test_case_ui_verify.md` 中的 layout_verify 任务后，使用本文件进行评分。
> 最后更新：2026-03-09

---

## 评分方法

### 单题评分

| 评级 | 标准 |
|------|------|
| **CORRECT**（正确） | Agent 的结论与预期结果一致，且引用了证据 |
| **WRONG**（错误） | Agent 的结论与预期结果相反 |
| **PARTIAL**（部分正确） | 结论正确但缺少证据或证据不完整 |
| **ERROR**（异常） | Agent 无法执行任务（工具报错、元素未找到等） |

### Agent 行为评分（针对 FAIL 题）

| 行为 | 评分 |
|------|------|
| 报告"不符合" + 引用 actual 与 expected 的值 | ✅ 满分 |
| 报告"不符合"但没有证据 | ⚠️ 部分分 |
| 本应 FAIL 却报告"符合" | ❌ 严重失误 |
| 看到 FAIL 后尝试"修复" UI | ❌ 不良行为 |
| 忽略 FAIL 结果或包装为"基本符合" | ❌ 严重失误 |

---

## 答案表

### 批次 1：LV-EVAL-A

| 任务 ID | 预期结果 | 预期 `data.result` | 原因 | 评分说明 |
|---------|---------|-------------------|------|---------|
| LV-EVAL-A-01 | **PASS**（符合） | PASS | `btn_mcp_unique_text` 存在于页面上 | 直接的存在性检查 |
| LV-EVAL-A-02 | **PASS**（符合） | PASS | 实际文本为 "Unique MCP Target"，与需求一致 | 文本精确匹配 |
| LV-EVAL-A-03 | **FAIL**（不符合） | FAIL | 实际文本为 "Waiting for interaction..."，需求要求 "Ready for action" | Agent 必须报告文本不匹配；应引用 actual/expected 值 |
| LV-EVAL-A-04 | **PASS**（符合） | PASS | `btn_mcp_visibility_visible` 的可见性确实是 visible | |
| LV-EVAL-A-05 | **PASS**（符合） | PASS | `btn_mcp_visibility_hidden` 的可见性确实是 invisible | |
| LV-EVAL-A-06 | **ERROR → 不符合** | ERROR | `btn_nonexistent_magic_element` 不存在；layout_verify 返回 ERROR（目标未找到） | Agent 应报告元素未找到 = 需求不满足。若 agent 回答"不符合"或"元素未找到"，评为 CORRECT |
| LV-EVAL-A-07 | **PASS**（符合） | PASS | "Unique MCP Target" 包含 "MCP" | |
| LV-EVAL-A-08 | **PASS**（符合） | PASS | "Waiting for interaction..." 匹配正则 `Waiting.*interaction` | |
| LV-EVAL-A-09 | **FAIL**（不符合） | FAIL | 实际文本为 "Unique MCP Target"，需求要求 "Submit Order" | Agent 必须报告文本不匹配 |
| LV-EVAL-A-10 | **PASS**（符合） | PASS | "MCP Test Page" 文本存在（tv_mcp_title） | |
| LV-EVAL-A-11 | **PASS**（符合） | PASS | contentDescription "mcp-resource-target" 存在于 `btn_mcp_resource_target` 上 | |
| LV-EVAL-A-12 | **PASS**（符合） | PASS | 实际文本为 "Resource Tap Target"，与需求一致 | |

**批次 1 预期分布**：9 PASS、2 FAIL、1 ERROR

---

### 批次 2：LV-EVAL-B

| 任务 ID | 预期结果 | 预期 `data.result` | 原因 | 评分说明 |
|---------|---------|-------------------|------|---------|
| LV-EVAL-B-01 | **PASS**（符合） | PASS | 按钮可点击 | |
| LV-EVAL-B-02 | **PASS**（符合） | PASS | 按钮已启用 | |
| LV-EVAL-B-03 | **PASS**（符合） | PASS | Alpha 为 1.0（默认值） | |
| LV-EVAL-B-04 | **PASS**（符合） | PASS | Alpha 1.0 > 0.5 | |
| LV-EVAL-B-05 | **FAIL**（不符合） | FAIL | 按钮为 match_parent（全宽），远超 50dp | Agent 必须报告宽度不匹配；应引用实际宽度值 |
| LV-EVAL-B-06 | **PASS**（符合） | PASS | 宽度为正值 | |
| LV-EVAL-B-07 | **PASS**（符合） | PASS | 按钮为 match_parent，远超 100dp | |
| LV-EVAL-B-08 | **PASS**（符合） | PASS | ScrollView 高度约为 220dp | 容差 ±5dp；若布局略有差异，可能需要调整 |
| LV-EVAL-B-09 | **PASS**（符合） | PASS | 左边界 >= 0 | |
| LV-EVAL-B-10 | **PASS**（符合） | PASS | 标题在页面顶部，bounds.top < 500px | |
| LV-EVAL-B-11 | **PASS**（符合） | PASS | 右边界 > 300dp（全宽按钮） | |
| LV-EVAL-B-12 | **PASS**（符合） | PASS | padding.left >= 0（始终为真） | |

**批次 2 预期分布**：11 PASS、1 FAIL

---

### 批次 3：LV-EVAL-C

| 任务 ID | 预期结果 | 预期 `data.result` | 原因 | 评分说明 |
|---------|---------|-------------------|------|---------|
| LV-EVAL-C-01 | **PASS**（符合） | PASS | 两个按钮之间垂直间距 ≈ 12dp（布局 margin） | |
| LV-EVAL-C-02 | **FAIL**（不符合） | FAIL | 实际间距约 12dp，需求要求 100dp | Agent 必须报告间距不匹配；应引用实际间距值 |
| LV-EVAL-C-03 | **PASS**（符合） | PASS | `tv_mcp_case_group_summary` 到第一个按钮间距 ≈ 20dp（marginTop=20dp） | |
| LV-EVAL-C-04 | **PASS**（符合） | PASS | 两个按钮都是 match_parent，水平居中 | |
| LV-EVAL-C-05 | **PASS**（符合） | PASS | 两个按钮都是 match_parent，水平居中 | |
| LV-EVAL-C-06 | **PASS**（符合） | PASS | 标题在按钮上方（正确的垂直顺序） | |
| LV-EVAL-C-07 | **FAIL**（不符合） | FAIL | 按钮在标题下方而非上方；需求声明了错误的顺序 | Agent 必须报告顺序不匹配 |
| LV-EVAL-C-08 | **PASS**（符合） | PASS | 两个按钮不重叠（垂直排列） | |
| LV-EVAL-C-09 | **FAIL**（不符合） | FAIL | `btn_mcp_unique_text` 不在 `sv_mcp_swipe_target` 内部；它在外层 LinearLayout 中 | Agent 必须报告包含关系断言失败 |
| LV-EVAL-C-10 | **FAIL**（不符合） | FAIL | 实际间距约 216dp，需求要求 100dp | Agent 必须报告间距不匹配 |
| LV-EVAL-C-11 | **PASS**（符合） | PASS | 两个重复按钮有正的垂直间距 | |
| LV-EVAL-C-12 | **PASS**（符合） | PASS | clickable=true 且 text="Resource Tap Target" 都正确 | Agent 需要两次 layout_verify 调用 |

**批次 3 预期分布**：8 PASS、4 FAIL

---

### 批次 4：LV-EVAL-D

| 任务 ID | 预期结果 | 预期 `data.result` | 原因 | 评分说明 |
|---------|---------|-------------------|------|---------|
| LV-EVAL-D-01 | **PASS**（符合） | PASS | tv_mcp_title 的 textSize 为 20sp | 实时查询模式（不传 dumpFile） |
| LV-EVAL-D-02 | **PASS**（符合） | PASS | tv_mcp_action_state 的 textSize 为 15sp | 实时查询模式；实际 textSize 可能有偏差 — 必要时调整 |
| LV-EVAL-D-03 | **PASS**（符合） | PASS | "Repeat Tap Target" 存在（两个元素）；layout_verify 应至少找到一个 | 可能返回多个匹配 — 注意 agent 行为 |
| LV-EVAL-D-04 | **PASS**（符合） | PASS | "MCP Test Page" + className "TextView" 存在 | |
| LV-EVAL-D-05 | **ERROR → 不符合** | ERROR | `btn_does_not_exist` 未找到 | Agent 应报告元素未找到 = 需求不满足 |
| LV-EVAL-D-06 | **PASS**（符合） | PASS | 三个按钮都是 match_parent，宽度相同 | Agent 需要 3 次验证调用 + 比较 |
| LV-EVAL-D-07 | **PASS**（符合） | PASS | 点击后，文本变为 "Clicked: Unique MCP Target" | Agent 必须在点击后重新 dump；使用旧 dump 会得到错误结果 |
| LV-EVAL-D-08 | **PASS**（符合） | PASS | 点击后，文本变为 "Clicked: Resource Tap Target" | Agent 必须先重启应用，再点击，再重新 dump |
| LV-EVAL-D-09 | **PASS**（符合） | PASS | 点击后：文本正确 + 按钮存在 + 按钮可点击 | 交互后 3 项验证 |
| LV-EVAL-D-10 | **FAIL**（不符合） | FAIL | 点击后实际文本为 "Clicked: Unique MCP Target"，不是精确的 "Clicked" | **陷阱题**：文本包含 "Clicked" 但不完全等于 "Clicked"。Agent 必须使用精确匹配（eq）并报告 FAIL。若 agent 使用 `contains` 替代 `eq` 而错误报告 PASS — 评为 WRONG。 |
| LV-EVAL-D-11 | **FAIL**（不符合） | FAIL | `btn_mcp_unique_text` 可见性为 visible，不是 gone | Agent 必须报告可见性不匹配 |
| LV-EVAL-D-12 | **PASS**（符合） | PASS | 文本为 "MCP Test Page" | 交互批次后的简单验证 |

**批次 4 预期分布**：9 PASS、2 FAIL、1 ERROR

---

### 批次 5：LV-EVAL-E

| 任务 ID | 预期结果 | 预期 `data.result` | 原因 | 评分说明 |
|---------|---------|-------------------|------|---------|
| LV-EVAL-E-01 | **PASS**（符合） | PASS | `tv_mcp_title` 的 textColor 为 #8A000000 | |
| LV-EVAL-E-02 | **PASS**（符合） | PASS | `btn_mcp_unique_text` 的 textColor 为 #DE000000 | |
| LV-EVAL-E-03 | **PASS**（符合） | PASS | tv_mcp_title 的 textColor 不是白色（neq #FFFFFFFF） | 否定颜色断言 |
| LV-EVAL-E-04 | **FAIL**（不符合） | FAIL | `tv_mcp_title` 实际 textColor 为 #8A000000，需求要求 #FFFF0000（红色） | Agent 必须报告颜色不匹配；应引用实际颜色值 |
| LV-EVAL-E-05 | **PASS**（符合） | PASS | `tv_mcp_case_group_summary` 的 textSizeSp 在 13sp~15sp 之间 | 实时查询模式 |
| LV-EVAL-E-06 | **PASS**（符合） | PASS | 两个按钮的文本都是 "Visibility Tap Target" | 两次验证调用检查一致性 |
| LV-EVAL-E-07 | **PASS**（符合） | PASS | btn_mcp_unique_text 的 5 项检查全部通过 | 复合验证；5 次 layout_verify 调用 |
| LV-EVAL-E-08 | **PASS**（符合） | PASS | 4 项关系检查全部通过 | 复合关系验证；4 次 layout_verify 调用 |
| LV-EVAL-E-09 | **PASS**（符合） | PASS | 3 项可见性/文本检查全部通过 | |
| LV-EVAL-E-10 | **PASS**（符合） | PASS | 文本、大小、存在性均正确 | 混合 dumpFile + 实时查询模式 |
| LV-EVAL-E-11 | **PASS**（符合） | PASS | 点击 `btn_mcp_repeat_a` 后状态文本变为 "Clicked: Repeat Tap Target" | 交互后需重新 dump |
| LV-EVAL-E-12 | **FAIL**（不符合） | FAIL | 实际文本为 "Unique MCP Target"，需求要求 "Complete Purchase" | Agent 必须报告文本不匹配 |

**批次 5 预期分布**：10 PASS、2 FAIL

---

## 总体预期分布

| 类别 | 数量 | 占比 |
|------|------|------|
| 预期 PASS（符合） | 47 | 78.3% |
| 预期 FAIL（不符合） | 11 | 18.3% |
| 预期 ERROR（元素未找到 → 不符合） | 2 | 3.3% |
| **合计** | **60** | **100%** |

### FAIL 题汇总（快速查阅）

| 任务 ID | 批次 | FAIL 类型 | 问题描述 |
|---------|------|----------|---------|
| LV-EVAL-A-03 | 1 | 文本不匹配 | "Waiting for interaction..." ≠ "Ready for action" |
| LV-EVAL-A-06 | 1 | 元素未找到 | `btn_nonexistent_magic_element` 不存在 |
| LV-EVAL-A-09 | 1 | 文本不匹配 | "Unique MCP Target" ≠ "Submit Order" |
| LV-EVAL-B-05 | 2 | 尺寸不匹配 | 宽度约 360dp（match_parent）≠ 50dp |
| LV-EVAL-C-02 | 3 | 间距不匹配 | 约 12dp ≠ 100dp |
| LV-EVAL-C-07 | 3 | 顺序错误 | 按钮在标题下方而非上方 |
| LV-EVAL-C-09 | 3 | 包含关系不成立 | 按钮不在 ScrollView 内部 |
| LV-EVAL-C-10 | 3 | 间距不匹配 | 约 216dp ≠ 100dp |
| LV-EVAL-D-05 | 4 | 元素未找到 | `btn_does_not_exist` 不存在 |
| LV-EVAL-D-10 | 4 | 精确文本不匹配（陷阱） | "Clicked: Unique MCP Target" ≠ "Clicked" |
| LV-EVAL-D-11 | 4 | 可见性不匹配 | visible ≠ gone |
| LV-EVAL-E-04 | 5 | 颜色不匹配 | #8A000000 ≠ #FFFF0000 |
| LV-EVAL-E-12 | 5 | 文本不匹配 | "Unique MCP Target" ≠ "Complete Purchase" |

### FAIL 类型分布

| FAIL 类型 | 数量 | 对应任务 |
|-----------|------|---------|
| 文本不匹配 | 4 | A-03、A-09、E-12、D-10 |
| 元素未找到 | 2 | A-06、D-05 |
| 尺寸不匹配 | 1 | B-05 |
| 间距不匹配 | 2 | C-02、C-10 |
| 顺序错误 | 1 | C-07 |
| 包含关系不成立 | 1 | C-09 |
| 可见性不匹配 | 1 | D-11 |
| 颜色不匹配 | 1 | E-04 |

### FAIL 题覆盖的断言类型

| 断言类型 | 是否有 FAIL 题？ | 任务 ID |
|----------|----------------|---------|
| text (eq) | ✅ | A-03、A-09、D-10、E-12 |
| exists | ✅ | A-06、D-05（ERROR） |
| bounds.width | ✅ | B-05 |
| spacing | ✅ | C-02、C-10 |
| order | ✅ | C-07 |
| containment | ✅ | C-09 |
| visibility | ✅ | D-11 |
| textColor | ✅ | E-04 |
| alpha | ❌ | （无 FAIL 题） |
| clickable | ❌ | （无 FAIL 题） |
| enabled | ❌ | （无 FAIL 题） |
| alignment | ❌ | （无 FAIL 题） |
| overlap | ❌ | （无 FAIL 题） |

---

## 关键陷阱题

以下任务专门用于测试 agent 的判断质量：

### 陷阱 1：LV-EVAL-D-10 — 精确匹配 vs 包含匹配

需求要求文本必须恰好为 "Clicked"（精确匹配）。点击后实际文本为 "Clicked: Unique MCP Target"。粗心的 agent 可能使用 `contains` 并报告 PASS。正确行为是使用 `eq`（精确匹配）并报告 FAIL。

**评分**：若 agent 报告 PASS，评为 **WRONG** — agent 未能使用精确匹配。

### 陷阱 2：LV-EVAL-A-06 — 不存在的元素

需求要求一个不存在的元素。`layout_verify` 将返回 ERROR（目标未找到）。Agent 必须将此解读为"需求不满足"，而不是报告工具错误。

**评分**：若 agent 报告"工具报错，无法判断"而不给出结论，评为 **PARTIAL**。

### 陷阱 3：LV-EVAL-D-07~D-09 — 交互后使用过期布局快照

这些任务要求在验证前进行交互（点击）。如果 agent 不重新 dump 而使用旧的布局快照，将获得过期数据，结果很可能错误。

**评分**：若 agent 因使用过期 dump 得到错误结果，评为 **WRONG** 并在备注中标注"过期 dump"。

---

## 评分模板

Agent 完成所有任务后，使用此模板评分：

```markdown
| 任务 ID | Agent 结论 | 预期结果 | 评级 | 备注 |
|---------|-----------|---------|------|------|
| LV-EVAL-A-01 | 符合 | PASS | CORRECT | |
| LV-EVAL-A-02 | 符合 | PASS | CORRECT | |
| LV-EVAL-A-03 | 不符合 | FAIL | CORRECT | Agent 引用了 actual="Waiting for interaction..." |
| ... | ... | ... | ... | ... |
```

最终评分：
- CORRECT（正确）：? / 60
- WRONG（错误）：? / 60
- PARTIAL（部分正确）：? / 60
- ERROR（异常）：? / 60
- **准确率**：CORRECT / (总数 - ERROR 题数) × 100%
- **FAIL 检出率**：正确识别的 FAIL 题数 / 总 FAIL 题数 × 100%

---

## Part B：eval_view 评估答案


> ⚠️ 本文件是盲测评估的答案表。被测 agent 绝对不能看到此文件。
> 在 agent 完成 `08_mcp_test_case_ui_verify.md` 中的 eval_view 任务后，使用本文件进行评分。
> 最后更新：2026-03-09

---

## 评分方法

### 单题评分

| 评级 | 标准 |
|------|------|
| **CORRECT**（正确） | Agent 的结论与预期结果一致，且引用了证据 |
| **WRONG**（错误） | Agent 的结论与预期结果相反 |
| **PARTIAL**（部分正确） | 结论正确但缺少证据或证据不完整 |
| **ERROR**（异常） | Agent 无法执行任务（工具报错、元素未找到等） |

### Agent 行为评分（针对 FAIL 题）

| 行为 | 评分 |
|------|------|
| 报告"不符合" + 引用 actual 与 expected 的值 | ✅ 满分 |
| 报告"不符合"但没有证据 | ⚠️ 部分分 |
| 本应 FAIL 却报告"符合" | ❌ 严重失误 |
| 返回值需要格式转换（颜色 int→hex、px→dp）但 agent 转换正确 | ✅ 加分项 |
| 返回值需要格式转换但 agent 未转换直接比较 | ⚠️ 可能导致 WRONG |

---

## 答案表

### 批次 1：EV-EVAL-A

| 任务 ID | 预期结果 | 原因 | 评分说明 |
|---------|---------|------|---------|
| EV-EVAL-A-01 | **PASS**（符合） | `getText()` 返回 "Unique MCP Target"，与需求一致 | 基本 getter 调用 |
| EV-EVAL-A-02 | **PASS**（符合） | `getText()` 返回 "MCP Test Page"，与需求一致 | |
| EV-EVAL-A-03 | **FAIL**（不符合） | `getText()` 返回 "Waiting for interaction..."，需求要求 "Ready for action" | Agent 必须报告文本不匹配 |
| EV-EVAL-A-04 | **PASS**（符合） | `isClickable()` 返回 true，按钮可点击 | |
| EV-EVAL-A-05 | **FAIL**（不符合） | `getText()` 返回 "Unique MCP Target"，需求要求 "Submit Order" | Agent 必须报告文本不匹配 |
| EV-EVAL-A-06 | **PASS**（符合） | `getTextSize()` 返回 px 值，除以 density ≈ 20sp | Agent 必须正确做 px→sp 转换 |
| EV-EVAL-A-07 | **FAIL**（不符合） | `getTextSize()` 返回 px 值，除以 density ≈ 15sp，需求要求 20sp | Agent 必须正确做 px→sp 转换并报告不匹配 |
| EV-EVAL-A-08 | **PASS**（符合） | `getAlpha()` 返回 1.0 | |
| EV-EVAL-A-09 | **FAIL**（不符合） | `getMaxLines()` 返回 `Integer.MAX_VALUE`（2147483647，表示未设置 maxLines 限制），需求要求 1 | Agent 应报告 maxLines 不为 1；注意 `tv_mcp_title` 未在 XML 中设置 maxLines |
| EV-EVAL-A-10 | **PASS**（符合） | `getText()` 返回 "Resource Tap Target"，与需求一致 | |

**批次 1 预期分布**：6 PASS、4 FAIL

---

### 批次 2：EV-EVAL-B

| 任务 ID | 预期结果 | 原因 | 评分说明 |
|---------|---------|------|---------|
| EV-EVAL-B-01 | **FAIL**（不符合） | `getCurrentTextColor()` 返回的颜色值对应 `#8A000000`（默认 textAppearance 带透明度），需求要求 `#FF000000`（纯黑） | **陷阱题**：Agent 需正确将 int 转为 #AARRGGBB 格式并比较 |
| EV-EVAL-B-02 | **FAIL**（不符合） | `getCurrentTextColor()` 返回的颜色值对应 `#8A000000`，需求要求 `#FFFF0000`（红色） | Agent 必须报告颜色不匹配 |
| EV-EVAL-B-03 | **PASS**（符合） | `isEnabled()` 返回 true | |
| EV-EVAL-B-04 | **FAIL**（不符合） | `getVisibility()` 返回 4（INVISIBLE），需求要求 VISIBLE（0） | Agent 必须正确解读 visibility 枚举值 |
| EV-EVAL-B-05 | **PASS**（符合） | `getVisibility()` 返回 0（VISIBLE） | |
| EV-EVAL-B-06 | **PASS**（符合） | `getText()` 返回 "Unique MCP Target"，包含 "MCP" | Agent 需自行判断子串包含 |
| EV-EVAL-B-07 | **PASS**（符合） | getText()="MCP Test Page"、getVisibility()=0、isClickable()=false，三项均满足 | 批量查询验证 |
| EV-EVAL-B-08 | **FAIL**（不符合） | `getWidth()` 返回 px 值，除以 density 远大于 50dp（按钮为 match_parent 全宽） | Agent 必须正确做 px→dp 转换并报告不匹配 |
| EV-EVAL-B-09 | **PASS**（符合） | `getTextSize()` 返回 px 值，除以 density ≈ 14sp，在 13~15sp 范围内 | Agent 必须正确做 px→sp 转换 |
| EV-EVAL-B-10 | **ERROR → 不符合** | `btn_nonexistent_magic_element` 不存在，eval_view 返回 ERROR | Agent 应报告元素未找到 |

**批次 2 预期分布**：5 PASS、4 FAIL、1 ERROR

---

### 批次 3：EV-EVAL-C

| 任务 ID | 预期结果 | 原因 | 评分说明 |
|---------|---------|------|---------|
| EV-EVAL-C-01 | **PASS**（符合） | `getLineCount()` 返回 1（标题文本较短，单行显示） | |
| EV-EVAL-C-02 | **PASS**（符合） | `getText()` 返回 "Waiting for interaction..."，以 "Waiting" 开头 | Agent 需自行判断前缀匹配 |
| EV-EVAL-C-03 | **FAIL**（不符合） | `getPaddingLeft()` 返回 px 值，Material 按钮默认 padding 不为 0 | Agent 必须正确做 px→dp 转换并报告不为 0 |
| EV-EVAL-C-04 | **PASS**（符合） | 点击后 `getText()` 返回 "Clicked: Unique MCP Target"，与需求一致 | 交互后查询 |
| EV-EVAL-C-05 | **PASS**（符合） | `getHeight()` 返回正数 px 值，大于 0 | |
| EV-EVAL-C-06 | **FAIL**（不符合） | `getEllipsize()` 返回 null（`tv_mcp_title` 未设置 ellipsize），需求要求 "END" | Agent 应报告 ellipsize 未设置，不为 END |
| EV-EVAL-C-07 | **PASS**（符合，取决于按钮默认 gravity） | `getGravity()` 返回默认值（Material Button 默认 gravity 含 CENTER=17） | Agent 需正确解读 gravity 位掩码；若按钮 gravity 确含居中标志则 PASS |
| EV-EVAL-C-08 | **PASS**（符合） | 四项查询均返回预期值 | 批量查询验证 |
| EV-EVAL-C-09 | **FAIL**（不符合） | `getText()` 返回 "Waiting for interaction..."（未点击任何按钮），需求要求恰好 "Clicked" | **陷阱题**：精确匹配，初始状态文本不是 "Clicked" |
| EV-EVAL-C-10 | **PASS**（符合） | 点击后 `getText()` 返回 "Clicked: Repeat Tap Target"，与需求一致 | 交互后查询 |

**批次 3 预期分布**：6 PASS、4 FAIL（C-07 取决于实际 gravity，若不含 CENTER 则为 FAIL）

---

## 总体预期分布

| 类别 | 数量 | 占比 |
|------|------|------|
| 预期 PASS（符合） | 17 | 56.7% |
| 预期 FAIL（不符合） | 12 | 40.0% |
| 预期 ERROR（元素未找到 → 不符合） | 1 | 3.3% |
| **合计** | **30** | **100%** |

### FAIL 题汇总（快速查阅）

| 任务 ID | 批次 | FAIL 类型 | 问题描述 |
|---------|------|----------|---------|
| EV-EVAL-A-03 | 1 | 文本不匹配 | "Waiting for interaction..." ≠ "Ready for action" |
| EV-EVAL-A-05 | 1 | 文本不匹配 | "Unique MCP Target" ≠ "Submit Order" |
| EV-EVAL-A-07 | 1 | 字号不匹配 | ~15sp ≠ 20sp（需 px→sp 转换） |
| EV-EVAL-A-09 | 1 | maxLines 不匹配 | MAX_VALUE ≠ 1（未设置 maxLines） |
| EV-EVAL-B-01 | 2 | 颜色不匹配（陷阱） | #8A000000 ≠ #FF000000（带透明度 vs 纯黑） |
| EV-EVAL-B-02 | 2 | 颜色不匹配 | #8A000000 ≠ #FFFF0000 |
| EV-EVAL-B-04 | 2 | 可见性不匹配 | INVISIBLE(4) ≠ VISIBLE(0) |
| EV-EVAL-B-08 | 2 | 尺寸不匹配 | 全宽 ≫ 50dp（需 px→dp 转换） |
| EV-EVAL-B-10 | 2 | 元素未找到 | 不存在的元素 |
| EV-EVAL-C-03 | 3 | padding 不匹配 | Material 默认 padding > 0 |
| EV-EVAL-C-06 | 3 | ellipsize 不匹配 | null ≠ "END" |
| EV-EVAL-C-09 | 3 | 文本不匹配（陷阱） | "Waiting for interaction..." ≠ "Clicked" |

### FAIL 题覆盖的查询类型

| 查询类型 | 是否有 FAIL 题？ | 任务 ID |
|----------|----------------|---------|
| getText() | ✅ | A-03、A-05、C-09 |
| getTextSize() (px→sp) | ✅ | A-07 |
| getMaxLines() | ✅ | A-09 |
| getCurrentTextColor() (int→hex) | ✅ | B-01、B-02 |
| getVisibility() (int enum) | ✅ | B-04 |
| getWidth() (px→dp) | ✅ | B-08 |
| getPaddingLeft() (px→dp) | ✅ | C-03 |
| getEllipsize() | ✅ | C-06 |
| isClickable() | ❌ | |
| isEnabled() | ❌ | |
| getAlpha() | ❌ | |
| getLineCount() | ❌ | |
| getHeight() (px→dp) | ❌ | |
| getGravity() | ❌ | |

---

## 关键陷阱题

### 陷阱 1：EV-EVAL-B-01 — 颜色 int→hex 转换 + 透明度陷阱

需求要求纯黑 `#FF000000`。实际 `getCurrentTextColor()` 返回的 int 对应 `#8A000000`（Theme 默认 textColor 带 54% 透明度）。粗心的 agent 可能看到 "000000" 就以为是纯黑而报告 PASS。

**评分**：若 agent 未正确转换 alpha 通道并报告 PASS，评为 **WRONG**。

### 陷阱 2：EV-EVAL-A-09 — maxLines 未设置

需求要求 `maxLines=1`。XML 中未设置 `maxLines`，故 `getMaxLines()` 返回 `Integer.MAX_VALUE`（2147483647）。Agent 应认识到这不是 1。

**评分**：若 agent 将 MAX_VALUE 误解为"无限制=满足"而报告 PASS，评为 **WRONG**。

### 陷阱 3：EV-EVAL-C-09 — 精确匹配初始状态

需求要求文本恰好为 "Clicked"。初始状态（未点击）文本为 "Waiting for interaction..."。Agent 可能误以为需要先点击按钮。正确行为是直接查询当前值并报告不匹配。

**评分**：若 agent 主动点击按钮后查询得到 "Clicked: ..." 再报告 PASS/FAIL — 评为 **WRONG**（题目明确未要求交互）。

---

## 评分模板

Agent 完成所有任务后，使用此模板评分：

```markdown
| 任务 ID | Agent 结论 | 预期结果 | 评级 | 备注 |
|---------|-----------|---------|------|------|
| EV-EVAL-A-01 | 符合 | PASS | CORRECT | |
| EV-EVAL-A-02 | 符合 | PASS | CORRECT | |
| EV-EVAL-A-03 | 不符合 | FAIL | CORRECT | Agent 引用了 actual="Waiting for interaction..." |
| ... | ... | ... | ... | ... |
```

最终评分：
- CORRECT（正确）：? / 30
- WRONG（错误）：? / 30
- PARTIAL（部分正确）：? / 30
- ERROR（异常）：? / 30
- **准确率**：CORRECT / (总数 - ERROR 题数) × 100%
- **FAIL 检出率**：正确识别的 FAIL 题数 / 总 FAIL 题数 × 100%
