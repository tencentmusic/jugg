# ADK_RULES — Agent Dev Kit: Skill 内容治理标准

> **目标**：在 context 膨胀 / 注意力集中 / 高效稳定决策 三者之间取得最佳平衡。
> **适用范围**：jugg-android-dev-loop 目录下的所有文件。

---

## 1. 预算硬约束

| 层级 | 行数上限 | 理由 |
|------|---------|------|
| **SKILL.md** (Agent 入口) | **≤ 200 行** | Agent 首次加载全文，必须在模型单次注意力窗口的高效区内；超 200 行后关键指令遵从率明显下降 |
| **单个 Reference 文件** | **≤ 150 行** | 按需加载，但加载后与 SKILL.md 共存上下文；单文件过长会稀释主指令权重 |
| **References 总量** | **≤ 6 个文件，合计 ≤ 600 行** | 极端场景（全流程 + 错误恢复）可能同时加载 4 个文件，需保证峰值上下文可控 |
| **SKILL.md + 峰值加载** | **≤ 500 行** | 典型峰值 = SKILL.md + 2~3 个 ref；绝对上限 = SKILL.md + 4 个 ref |

> **度量方式**：`wc -l`，空行和注释行计入。Frontmatter 不计入正文行数。

---

## 2. 核心 vs 非核心判定标准

### 2.1 核心内容（必须在 SKILL.md 中）

满足 **任一** 条件即为核心：

| 判定条件 | 举例 |
|---------|------|
| **控制流**：决定 Agent 下一步做什么 | Pipeline 步骤定义、entry gate、checkpoint、失败回退路径 |
| **决策规则**：决定 Agent 是否/如何自动行动 | auto-apply 阈值、重试预算、compile-only 分支判定 |
| **安全护栏**：违反会导致任务失败或用户损失 | 不可跳步、Gate 前置、Deploy 使上下文失效 |
| **触发判定**：决定 Skill 是否被激活 | Frontmatter description + Skip Rule |

### 2.2 非核心内容（必须下沉到 References）

满足 **任一** 条件即为非核心：

| 判定条件 | 举例 |
|---------|------|
| **工具参数细节**：Agent 只在使用该工具时才需要 | MCP 工具的输入/输出字段、返回值结构 |
| **诊断知识库**：Agent 只在出错时才需要 | error_patterns 的具体 pattern 条目 |
| **操作规程**：具体怎么做（步骤性操作指南） | UI 验证断言的 Figma/手动两条路径详细步骤 |
| **示例/模板**：帮助理解但不参与决策 | Quick Example、Report 模板 |
| **策略细节**：边界判定的展开说明 | 增量编译限制的具体 processor 列表 |

### 2.3 灰色地带处理原则

当一段内容同时具备核心和非核心特征时：

1. **拆分**：将决策规则（1-2 行摘要）留在 SKILL.md，将展开说明下沉到 Reference。
2. **指针**：SKILL.md 中用 `→ see reference_file.md §N` 做单向引用。
3. **禁止反向依赖**：Reference 不得反向引用 SKILL.md 的章节号（避免耦合）。

---

## 3. 内容密度标准

### 3.1 SKILL.md 写作规则

| 规则 | 说明 |
|------|------|
| **一句话一个规则** | 每条规则/约束用一行表达，禁止段落式描述 |
| **表格优于段落** | 多维信息用表格，不用列表嵌套 |
| **代码块必须极短** | SKILL.md 中的代码块 ≤ 5 行；长模板/示例下沉到 Reference |
| **去除修辞** | 禁止"请注意"/"务必"/"非常重要"等强调词——结构本身传达重要性 |
| **不重复说**| 同一规则只出现一次；如果 Pipeline Step 已经说了 gate，Rules 章节只引用不复述 |

### 3.2 Reference 写作规则

| 规则 | 说明 |
|------|------|
| **自包含** | 每个 Reference 独立可用，不需要阅读其他 Reference |
| **按使用场景组织** | 按 Agent 的行动时刻组织，不按技术分类组织 |
| **速查优先** | 工具卡片用表格/YAML，不用叙述文字 |
| **示例内联** | 示例紧跟规则，不单独成章 |

---

## 4. 精简决策框架

当 SKILL.md 超出预算时，按以下优先级依次裁剪：

| 优先级 | 裁剪对象 | 操作 |
|--------|---------|------|
| **P0 先裁** | 示例（Quick Examples） | 整体移到独立 Reference 或删除 |
| **P1** | 模板（Report Generator） | 仅保留 1 行摘要 + 指针，模板本体移到 Reference |
| **P2** | 工具使用细节 | 仅保留工具选择决策树，参数/返回值/示例全部移到 Reference |
| **P3** | 规则展开说明 | 规则保留一句话，展开说明移到 Reference |
| **P4 最后裁** | 控制流和安全护栏 | 不可裁剪——这是 Agent 的"操作系统" |

---

## 5. 迭代守则

### 5.1 新增内容前的三问

1. **这是控制流/决策/护栏吗？** → 是：加到 SKILL.md；否：加到 Reference。
2. **加完后 SKILL.md 超 200 行吗？** → 是：必须同时精简等量旧内容（零和原则）。
3. **加完后对应 Reference 超 150 行吗？** → 是：考虑拆分为两个 Reference 或精简旧内容。

### 5.2 零和原则

SKILL.md 的每一次新增，都必须伴随等量或更多的移除/下沉。行数预算是硬约束，不可透支。

### 5.3 Review Checklist

每次迭代后，执行以下检查：

```
- [ ] SKILL.md 正文 ≤ 200 行
- [ ] 每个 Reference ≤ 150 行
- [ ] References 合计 ≤ 600 行且文件数 ≤ 6
- [ ] SKILL.md 中无工具参数细节（应在 Reference）
- [ ] SKILL.md 中无完整代码示例（应在 Reference）
- [ ] SKILL.md 中无完整报告模板（应在 Reference）
- [ ] 同一规则只在一处表述（无重复）
- [ ] 所有下沉内容有指针引用
```

---

## 6. 当前体量快照

> 用于对比迭代前后的变化，每次重大迭代后更新此节。

| 文件 | 重构前 | 重构后 | 状态 |
|------|--------|--------|------|
| SKILL.md | 145 | 124 | ✅ (≤200) |
| error_patterns.md | 144 | 144 | ✅ (≤150) |
| policy_incremental_compile_limits.md | 42 | 42 | ✅ (≤150) |
| ref_cli_manual.md | — | 90 | ✅ (新增) |
| ref_flow_no_playground.md | — | 79 | ✅ (新增) |
| ref_flow_with_playground.md | — | 108 | ✅ (新增) |
| ref_guide_playground.md | — | 134 | ✅ (新增) |
| ~~guide_ui_verify_assertion.md~~ | 90 | — | 🗑️ 融入流程文档 |
| ~~report_template.md~~ | 70 | — | 🗑️ 融入 SKILL.md |
| ~~tool_cards_build_deploy.md~~ | 69 | — | 🗑️ 融入 ref_cli_manual |
| ~~tool_cards_runtime_observe.md~~ | 98 | — | 🗑️ 融入 ref_cli_manual |
| ~~tool_cards_troubleshoot.md~~ | 50 | — | 🗑️ 融入 ref_cli_manual |
| **合计 References** | **563** | **597** | ✅ (≤600) |
| **SKILL + 峰值(3 ref)** | 546 | ~456 | ✅ (常态 SKILL+2ref ≈ 330) |

> 快照日期：2026-04-11
