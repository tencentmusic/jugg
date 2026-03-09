# eval_view 评估答案表

> ⚠️ 本文件是盲测评估的答案表。被测 agent 绝对不能看到此文件。
> 在 agent 完成 `08_mcp_test_case_eval_view.md` 中的所有任务后，使用本文件进行评分。
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
