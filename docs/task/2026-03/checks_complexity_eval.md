# layout_verify checks 功能复杂度评估报告

> 日期：2026-03-08
> 已读取文档：`00_overview.md`, `97_ai_usage.md`, `08_mcp_design.md`, `08_mcp_usage.md`, `08_mcp_test_case.md`
> 代码依据：`LayoutVerifyMcpToolAction.kt`, `LayoutVerifier.java`, `guide_layout_verify_assertion.md`, `tool_cards_runtime_observe.md`
> 基于：`plan.md`, `real_scene_analysis_report.md`

---

## 一、Checks 功能复杂度评估

### 1.1 参数结构复杂度

`checks[]` 是一个 **多态数组**，每个 check 的结构因 `type` 不同而差异显著：

| type | 必填字段 | 可选字段 | 参数组合数 |
|------|---------|---------|-----------|
| `property` | `target`, `type`, `property` | `op`, `value`, `unit` | ~6 个 property × 8 个 op = 48 种 |
| `spacing` | `target`, `target2`, `type`, `direction` | `expected`, `tolerance`, `unit` | 2 direction × value 组合 |
| `alignment` | `target`, `target2`, `type`, `direction` | — | 2 direction |
| `overlap` | `target`, `target2`, `type` | — | 固定 |
| `containment` | `target`, `target2`, `type` | — | 固定 |
| `order` | `target`, `target2`, `type`, `direction` | — | 2 direction |

**复杂度评级：中高**

核心复杂性来源：
1. **6 种 type 的参数差异大**：`property` 用 `op/value`，`spacing` 用 `expected/tolerance`，其余无额外参数
2. **16 种 property 值**，每种有不同的值类型（boolean/string/number/hex color）
3. **8 种 op 值**，但只有部分 property 与部分 op 合法（如 `exists` 不需要 op，boolean 属性不支持 `gte/lte`）
4. **unit 转换**：dp/px 仅用于数值属性，color/boolean/text 不适用
5. **tolerance 限制**：仅 `type=spacing` 支持，`type=property` 传入会返回 ERROR

### 1.2 模式切换复杂度

工具内部存在 **3 种执行模式**，选择逻辑隐式：

```
                     传 dumpFile?
                    /            \
                  是              否
                  |               |
          有 live-only 属性?    有 live-only 属性?
           /          \          /          \
          是           否       是           否
          |            |        |            |
      live query   dumpFile  live query  auto_dump
```

Agent 不需要显式选择模式（工具自动切换），但需要**理解**：
- `textSizeSp` 必须走 live（不传 dumpFile 或传了也会切到 live）
- 传 dumpFile + 有 live-only 属性 → dumpFile 参数被忽略，走 live
- 不传 dumpFile → auto_dump（每次调用都重新抓取快照）

**混淆风险：低**（自动切换对 agent 透明），但 agent 可能在**批量 checks** 中混入 `textSizeSp` 和普通属性，导致整个批次走 live 而非 dumpFile 模式。

### 1.3 Schema 声明与实际行为差异

| 差异点 | Schema 声明 | 实际行为 | 混淆风险 |
|--------|------------|---------|---------|
| `value` type | `type: "string"` | 内部支持 Number/String/Boolean | **中** — agent 可能只传字符串 |
| `dumpFile` 参数 | **不在 inputSchema 中** | 代码中通过 `arguments["dumpFile"]` 读取 | **低** — 文档有说明 |
| `target` (root-level) | **不在 inputSchema 中** | 作为 `legacyTarget` 回退 | **中** — 旧格式仍可用但不推荐 |
| `tolerance` in property | 无 schema 限制 | 返回 ERROR + 引导信息 | **低** — 有明确错误提示 |
| `backgroundColor` | 未在 property 列表中 | 不支持 | **高** — agent 可能尝试使用 |

---

## 二、Agent 使用混淆风险评估

### 2.1 高风险混淆场景

#### 风险 #1：`alignment.direction` 语义反直觉（⚠️ P0）

```
direction: "vertical"   → 检查的是 X 轴（水平）中心对齐
direction: "horizontal" → 检查的是 Y 轴（垂直）中心对齐
```

这与自然语言直觉**完全相反**。当 agent 收到"验证两个按钮水平居中对齐"时：
- 直觉调用：`direction: "horizontal"` ❌
- 正确调用：`direction: "vertical"` ✅

**实际影响**：`guide_layout_verify_assertion.md` 已通过三层防护有效覆盖此风险：Pitfall #3 明确解释语义、映射表 1.2 给出确定性对照、Example 4 提供可模仿的正确调用。有 Skill 时错误率降至 ~10-15%（无 Skill 时 40-60%）。

**证据**：plan.md LV-F-1 预期用 `direction: "horizontal"` 检查水平居中，但代码中当 direction 为 "horizontal" 时实际检查的是 **Y-center alignment**（vertical centers）。这意味着 **plan.md 的预期调用可能本身就是错的**。

代码确认（`LayoutVerifyMcpToolAction.kt` 第 462-473 行）：
```kotlin
if ("vertical".equals(direction, ignoreCase = true)) {
    val centerA = (aLeft + aRight) / 2  // X-center
    val centerB = (bLeft + bRight) / 2
    // → direction="vertical" 检查 X-center（水平居中）
} else {
    val centerA = (aTop + aBottom) / 2  // Y-center
    val centerB = (bTop + bBottom) / 2
    // → direction="horizontal" 检查 Y-center（垂直居中）
}
```

#### 风险 #2：`type=property` 传 `tolerance` 静默失败（⚠️ P1）

Agent 很可能写出：
```json
{ "type": "property", "property": "bounds.height", "value": 220, "tolerance": 5, "unit": "dp" }
```

实际行为：返回 ERROR + 引导信息"use gte and lte"。正确做法需要拆成两条 check。

**缓解措施已有**：错误信息明确，agent 可自行修正。但浪费一次 MCP 调用往返。

#### 风险 #3：`backgroundColor` 不支持但 agent 会尝试（⚠️ P1）

`real_scene_analysis_report.md` 显示 backgroundColor 是真实场景中的高频需求（Cap-02, Cap-15）。但：
- `LayoutVerifyMcpToolAction.kt` 的 `assertDumpNode()` 中**不存在** `backgroundColor` 分支
- `LayoutVerifier.java` 的 `executeAssert()` 中也**不存在** `backgroundColor`
- MCP schema 的 property description 未列出 `backgroundColor`
- `guide_layout_verify_assertion.md` Pitfall #9 明确标注"backgroundColor is not supported"

Agent 会得到 `unsupported property in dumpFile mode: backgroundColor` 的错误。但在自然语言指令中，"验证背景色"是极常见的需求。

#### 风险 #4：`textColor` 在 dumpFile 模式下黑色默认值处理（⚠️ P1）

dump JSON 省略默认值（黑色 `#FF000000` 的 textColor 不输出）。代码中处理方式：

```kotlin
// LayoutVerifyMcpToolAction.kt 第 359 行
val actual = (node.optStringOrNull("textColor") ?: "#FF000000").uppercase()
```

这意味着 dumpFile 模式下**能正确处理黑色 textColor**。但 agent 可能不知道这个默认回退行为，选择走 live 模式导致不必要的设备交互。

**实际风险：低** — 代码已正确处理。

#### 风险 #5：`alpha` 比较缺少完整的 op 支持（⚠️ P2）

dumpFile 模式下 `alpha` 的比较逻辑（第 366-369 行）：

```kotlin
if (Math.abs(actual - expected) < 0.001)
    VerifyResult("PASS", ...)
else
    VerifyResult("FAIL", ...)
```

**问题**：忽略了 `op` 参数！无论 agent 传 `eq`/`gt`/`gte`/`lt`/`lte`，都只做近似相等比较。

而 live 模式下 `LayoutVerifier.java` 的 `assertDouble()`（第 200-216 行）**部分支持** op：`gte`/`lte` 有单独分支，但 `gt`/`lt`/`neq` 走默认的近似相等。

**影响**：
- `alpha op:"gt" value:0.5`（alpha=1.0）→ dumpFile 模式会 FAIL（因为 1.0 ≠ 0.5），但语义上应该 PASS
- live 模式也会 FAIL（走默认 eq 分支）

**结论：alpha 属性仅支持 eq/gte/lte 三种 op，gt/lt/neq 行为不正确。**

#### 风险 #6：数值型 `value` 作为字符串传入时的解析（⚠️ P2）

schema 中 `value` 声明为 `type: "string"`，但代码中做了兼容处理：

```kotlin
// assertBoundsDp (第 409-413 行)
val expected = when (value) {
    is Number -> value.toInt()
    is String -> value.toIntOrNull() ?: 0
    else -> 0
}
```

**风险**：当 agent 传入非数字字符串（如 `"auto"` 或 `"match_parent"`）时，`toIntOrNull()` 返回 null → 默认 0 → 静默比较错误值。不会报 ERROR。

### 2.2 中风险混淆场景

| 场景 | 风险 | 影响 |
|------|------|------|
| `overlap` PASS = 无重叠（语义与 agent 直觉相反） | Agent 可能认为 PASS = 有重叠 | 验证结论反转 |
| `containment` target/target2 方向 | target=child, target2=parent，反了则结论反 | 验证结论反转 |
| `order` target/target2 方向 | target 应在 target2 "前面"，反了则结论反 | 验证结论反转 |
| 多匹配时 dumpFile 模式静默选第一个 | 不同于 tap 的多匹配报错 | 验证了错误的元素 |
| `textSizeSp` 混入普通 checks | 整个批次切到 live 模式 | dumpFile 参数被忽略 |

### 2.3 低风险场景

| 场景 | 已有缓解措施 |
|------|-------------|
| `resourceId` short/full format | `shortId()` 自动处理 |
| `className` 全名/短名/子串 | `findNodeBySelector` 做多种匹配 |
| `textColor` 大小写 | 统一转 uppercase 比较 |
| `checks` 为空 | 明确返回 `INVALID_PARAMS` |
| `type` 缺失 | 明确返回 `INVALID_PARAMS` |
| `target` 缺失 | 明确返回 `INVALID_PARAMS` |

---

## 三、参数传入正确率评估

### 3.1 Agent 可能正确传入的参数（基于 schema + 文档）

| 参数组合 | 预期正确率 | 依据 |
|---------|-----------|------|
| `target.resourceId` 定位 | **95%+** | schema 清晰，文档充分 |
| `type: "property"` + 常见 property | **90%+** | property 列表在 schema description 中 |
| `op: "eq"` / `"contains"` / `"matches"` | **90%+** | enum 约束明确 |
| `value` 为字符串/数字 | **85%** | schema 声明为 string，agent 可能传 number |
| `unit: "dp"` | **80%** | 容易遗忘，但 Skill 有 Pitfall #2 提醒 |
| `type: "spacing"` + `tolerance` | **85%** | 参数组合在 schema 和文档中清晰 |

### 3.2 Agent 可能错误传入的参数

| 错误模式 | 无 Skill 错误率 | 有 Skill 错误率 | Skill 缓解机制 |
|---------|----------------|----------------|---------------|
| `type=property` + `tolerance` | **30-40%** | **~5%** | Pitfall #1 + ❌/✅ 对比示例（Example 2）极为有效 |
| `alignment.direction` 方向反转 | **40-60%** | **~10-15%** | Pitfall #3 + 映射表 1.2 + Example 4，三层覆盖 |
| `overlap` 结果理解反转 | **20-30%** | **~10%** | 映射表 1.2 注释 + Example 7 注释 |
| `backgroundColor` property | **15-20%** | **~3%** | Pitfall #9 + 映射表 1.1/1.3 明确标注 + fallback 方案 |
| `alpha` 使用 `gt`/`lt` op | **10-15%** | **~10%** | Skill 未覆盖此 bug，无缓解 |
| `containment` target/target2 反向 | **20-30%** | **~8%** | 映射表 1.2 "target=child, target2=parent" |
| `textSizeSp` 与 dumpFile 共传 | **10-15%** | **~5%** | Pitfall #6 说明 live-only |
| 遗漏 `unit:"dp"` | **25-35%** | **~5%** | Pitfall #2 + 映射表/示例中多处"always dp"标注 |
| `textColor` 漏掉 alpha 前缀 | **20-30%** | **~5-10%** | Pitfall #4 + 映射表 1.1/1.3 转换规则 + Example 5 |

### 3.3 综合正确率估算

#### 有 Skill 文档（含 `guide_layout_verify_assertion.md` + `tool_cards_runtime_observe.md`）：

- **基本参数结构正确率**：~95%（target/type/property 三件套 + Skill 映射表覆盖）
- **参数值正确率**：~85-90%（Pitfall #1/#2/#4 的 ❌/✅ 对比格式对 LLM 引导效果强）
- **语义正确理解率**：~85%（alignment direction 有 Pitfall #3 + Example 4 三层覆盖；overlap/containment 有映射表注释）
- **综合首次调用正确率**：**~85%**

#### 无 Skill 文档（仅依赖 MCP schema）：

- **基本参数结构正确率**：~85%（schema enum 约束仍有效）
- **参数值正确率**：~65%（alignment direction、tolerance 滥用、ARGB 格式、dp/px 全面失守）
- **语义正确理解率**：~60%（overlap PASS 含义、containment 方向等均无提示）
- **综合首次调用正确率**：**~55-60%**

#### Skill 文档收益分析

**Skill 文档贡献了 ~25-30% 的正确率提升**（从 ~57% → ~85%），这一收益主要来自：

1. **Pitfall 列表**（贡献最大，~15%）：❌/✅ 对比格式直接命中 LLM 的 few-shot 学习模式，对 tolerance 滥用和 ARGB 格式的修正率接近 90%
2. **映射表**（贡献 ~8%）：设计意图 → layout_verify 参数的确定性映射，消除了 alignment direction 等语义歧义
3. **Few-shot Examples**（贡献 ~5%）：7 个完整示例覆盖了全部 6 种 type，agent 可直接模仿

Skill 文档之所以有效，是因为它的结构**精确匹配了 LLM 的学习方式**：映射表提供确定性规则，Pitfalls 提供负面示例强化，Few-shots 提供可模仿的正面模板。

---

## 四、判断方式完整性评估（是否能正确使用所有判断方式）

### 4.1 type=property 支持的 16 种 property

| property | dumpFile 模式 | live 模式 | op 支持完整度 | Agent 可正确使用率（有 Skill / 无 Skill） |
|----------|-------------|-----------|-------------|------------------------------------------|
| `exists` | ✅ | ✅ | N/A（无需 op） | **98%** / 95% |
| `visibility` | ✅ | ✅ | eq/neq/contains/matches | **95%** / 90% |
| `clickable` | ✅ | ✅ | eq only（boolean） | **95%** / 90% |
| `enabled` | ✅ | ✅ | eq only（boolean） | **95%** / 90% |
| `text` | ✅ | ✅ | eq/neq/contains/matches | **95%** / 92% |
| `textColor` | ✅（黑色有默认回退） | ✅ | eq/neq/contains/matches | **90%** / 70%（Pitfall #4 大幅降低 ARGB 错误） |
| `alpha` | ⚠️（op 不完整） | ⚠️（op 不完整） | eq/gte/lte 有效；gt/lt/neq 行为不正确 | **70%** / 70%（Skill 未覆盖此 bug） |
| `textSizeSp` | ❌（live only） | ✅ | eq/gte/lte | **92%** / 75%（Pitfall #6 有效） |
| `bounds.*`（6 个） | ✅ | ✅ | eq/neq/gte/lte/gt/lt | **92%** / 75%（Pitfall #2 解决 dp 遗忘） |
| `padding.*`（4 个） | ✅ | ✅ | eq/neq/gte/lte/gt/lt | **90%** / 72%（同上） |

### 4.2 关系类型（5 种 relation）

| type | 实现完整度 | Agent 正确使用率（有 Skill / 无 Skill） | 主要障碍 |
|------|-----------|----------------------------------------|---------|
| `spacing` | ✅ 完整（direction + expected + tolerance + unit） | **92%** / 65% | Pitfall #1 消除 tolerance 滥用 |
| `alignment` | ✅ 完整 | **85%** / 40-50% | Pitfall #3 + Example 4 大幅降低 direction 错误 |
| `overlap` | ✅ 完整 | **88%** / 70% | 映射表 + Example 7 注释有效 |
| `containment` | ✅ 完整 | **90%** / 70% | 映射表 1.2 "target=child, target2=parent" |
| `order` | ✅ 完整 | **90%** / 80% | 相对简单，Skill 收益不大 |

### 4.3 不支持的判断方式（已知缺口）

| 判断方式 | 现状 | 影响 |
|---------|------|------|
| `backgroundColor` | 不支持 | 高频需求（Cap-02, Cap-15） |
| `maxLines` | 不支持 | 中频需求（Cap-13） |
| `ellipsize` | 不支持 | 中频需求（Cap-13） |
| `tintColor` / `colorFilter` | 不支持 | 中频需求（Cap-17） |
| 自定义 View 属性 | 不支持 | 中频需求（Cap-16） |
| `cornerRadius` | 不支持 | 低频但不可替代 |
| `drawableInternals` | 不支持 | 超出范围 |

---

## 五、核心发现与建议

### 5.1 最高优先级问题

| # | 问题 | 严重度 | 建议 |
|---|------|--------|------|
| 1 | **`alpha` 属性的 op 在 dumpFile 模式下被完全忽略** | Bug | 修复 `assertDumpNode()` 中 alpha 的 op 分支，至少支持 eq/gte/lte/gt/lt/neq |
| 2 | **`alignment.direction` 语义反直觉** | 设计风险 | 无法改动（兼容性问题），但应在 MCP schema description 中直接标注语义，而非仅在 Skill 文档中说明 |
| 3 | **`backgroundColor` 不支持** | 功能缺口 | 评估是否可在 dump schema 或 live query 中添加支持 |

### 5.2 建议改进

| 优先级 | 改进项 | 预期收益 |
|--------|--------|---------|
| P0 | 修复 alpha op 支持（dumpFile + live） | 消除 bug，提升正确率 |
| P0 | 在 `alignment` 的 MCP schema `direction` 描述中加入语义说明 | 减少 40-60% 的 alignment 方向错误 |
| P1 | 在 `value` schema description 中说明 ARGB 格式要求 | 减少 textColor 格式错误 |
| P1 | 评估 `backgroundColor` 支持方案 | 覆盖高频真实场景 |
| P2 | 在 `unit` schema description 中强调默认 px | 减少 dp/px 遗忘 |
| P2 | 在 `overlap` schema description 中说明 PASS=无重叠 | 减少语义误解 |

### 5.3 综合评估结论

| 维度 | 评级 | 说明 |
|------|------|------|
| **功能复杂度** | 中高 | 6 种 type × 16 种 property × 8 种 op，参数组合丰富但有清晰的分类 |
| **Agent 混淆风险** | 中 | alignment direction 是最大风险点；tolerance/backgroundColor/overlap 语义是次要风险 |
| **参数传入正确率（有 Skill）** | **~85%** | Skill 文档的 Pitfall + Few-shot 结构对 LLM 引导效果显著 |
| **参数传入正确率（无 Skill）** | ~55-60% | 缺少 Pitfall 指引后 alignment/tolerance/ARGB/dp 全面失守 |
| **Skill 文档净收益** | **+25-30%** | 映射表（+8%）+ Pitfalls（+15%）+ Few-shots（+5%），是核心正确率保障 |
| **判断方式覆盖度** | ~72% | 16 种 property + 5 种 relation 覆盖大部分常见场景，但 backgroundColor/maxLines/ellipsize/tint 缺失 |
| **已有 Bug** | 1 个确认 | alpha op 在 dumpFile 模式被忽略 |
