# jugg-android-dev-loop — Skill 设计指南

> 本文档面向 Skill 维护者/迭代者。**Agent 运行时不读取此文件**。
> 内容治理标准见 [ADK_RULES.md](ADK_RULES.md)。

---

## 1. 设计目标

让 AI Agent 用 Jugg MCP 工具，以**确定性闭环**完成 Android 应用的"改 → 编译 → 部署 → 验证 → 产出证据"全流程：

- **可控**：每步有 checkpoint，失败有明确回退路径。
- **高效**：参考文档按需加载，SKILL.md ≤ 200 行。
- **可审计**：结构化报告输出，便于回溯。

---

## 2. 设计模式（5 Pattern）

| # | 模式 | 体现 | 核心价值 |
|---|------|------|---------|
| 1 | **Pipeline** | Phase 2 的 5-Step Loop | 确定性、可观测、可回退 |
| 2 | **Inversion** | Phase 0 Context Interview | 信息完备再动手 |
| 3 | **Tool Wrapper** | Phase 1 Read Gate + Catalog | 按需加载，控制上下文 |
| 4 | **Reviewer** | Error Reviewer 评分 | 规则驱动决策 |
| 5 | **Generator** | Report Template | 统一输出格式 |

---

## 3. 核心决策链

| 决策 | 判定 | SKILL.md 位置 |
|------|------|-------------|
| **D1** 是否触发 | APK/AAB 产物？ | Frontmatter + Skip Rule |
| **D2** 编译还是全流程 | 有设备？ | Phase 0 `deviceReady` |
| **D3** Figma 还是手动 | 有 designSource？ | Step 3 action 分支 |
| **D4** 自动修错？ | confidence≥0.8 AND scope=low | Error Reviewer |
| **D5** 重试还是上报 | 重试次数<3？ | Step 4 + Core Rules |

---

## 4. 内容结构

### 4.1 文件树

```
jugg-android-dev-loop/
├── SKILL.md          ← Agent 入口 (≤200行)
├── README.md         ← 本文件（维护者指南）
├── ADK_RULES.md      ← 内容治理标准（行数预算/核心判定/精简规则）
└── references/       ← 按需加载 (每个≤150行, 合计≤600行)
    ├── error_patterns.md
    ├── guide_ui_verify_assertion.md
    ├── policy_incremental_compile_limits.md
    ├── report_template.md          ← 报告模板 + Quick Examples
    ├── tool_cards_build_deploy.md
    ├── tool_cards_runtime_observe.md
    └── tool_cards_troubleshoot.md
```

### 4.2 SKILL.md 内部结构

```
Frontmatter → 触发条件 + 元数据
Phase 0 — Context Interview → 变量表 + auto-resolve
Phase 1 — Read Gate → LoadDecision + Reference Catalog + Skip Rule
Phase 2 — Pipeline → Step 1-5 (entry/action/checkpoint)
Mandatory Rules → 5条 (表格式，一行一规则)
Error Reviewer → 评分 + 决策树 (4行)
Core Rules → 全局参数 (6行)
```

---

## 5. 迭代指南

**所有迭代必须遵守 [ADK_RULES.md](ADK_RULES.md)**，核心规则：

1. **三问法**：新增前问——是控制流/决策/护栏吗？加完超 200 行吗？Reference 超 150 行吗？
2. **零和原则**：SKILL.md 新增必须伴随等量移除/下沉。
3. **Review Checklist**：见 ADK_RULES.md §5.3。

### 常见场景

| 场景 | 操作 |
|------|------|
| 新增错误模式 | 只改 `error_patterns.md`，SKILL.md 无需改动 |
| 新增 MCP 工具 | 改对应 `tool_cards_*.md` + 检查 SKILL.md Reference Catalog |
| 新增 Pipeline 步骤 | 改 SKILL.md Phase 2 + frontmatter steps + report_template.md |
| 修改决策逻辑 | 改 SKILL.md 对应节 + 本 README §3 决策链表 |

---

## 6. 设计原则备忘

| 原则 | 违反信号 |
|------|---------|
| **确定性优先** | 出现模糊跳过 |
| **按需加载** | 出现"先全部读取 references" |
| **信息完备再动手** | 跳过 Phase 0 |
| **失败完整重跑** | 只重跑失败项 |
| **评分驱动决策** | "我觉得可以自动修" |
| **事实核查** | evidence 缺失或改写 |
| **违规自省** | 违规后静默继续 |
| **模板化输出** | 自由格式总结 |
