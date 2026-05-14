# jugg-android-dev-loop — Skill 设计指南

> 本文档面向 Skill 维护者/迭代者。**Agent 运行时不读取此文件**。
> 内容治理标准见 [ADK_RULES.md](ADK_RULES.md)。

---

## 1. 设计目标

让 AI Agent 用 Jugg CLI，以**确定性闭环**完成 Android 应用的"改 → 编译 → 部署 → 验证"全流程：

- **可控**：Phase 0 收集上下文变量，Phase 1 路由到对应流程，流程内步步有 checkpoint。
- **高效**：Reference 按需加载，SKILL.md ≤ 200 行，单次峰值上下文 ≤ 500 行。
- **可扩展**：新增场景只需新增 Reference + 更新路由表，SKILL.md 无需大改。

---

## 2. 架构模式：场景路由（Scenario Routing）

SKILL.md 采用"**Context Interview → Scenario Route → Load Reference**"三段式架构：

```
Phase 0: 收集变量（projectDir, hasAutoRunEntry, enabledAndroidTest）
    ↓
Phase 1: 匹配场景 → 加载对应 Primary Reference
    ↓
Reference: 执行具体流程（步骤、checkpoint、错误处理）
```

SKILL.md 只负责**路由决策**和**全局共享内容**（CLI Quick Reference、Build Fallback Chain）；具体执行步骤全部在 Reference 中。

---

## 3. 核心决策

| 决策 | 判定变量 | 位置 |
|------|---------|------|
| **D1** Skill 是否触发 | 用户提到 Jugg / Android 源码被修改 | Frontmatter description |
| **D2** 路由到哪个流程 | `hasAutoRunEntry` + `enabledAndroidTest` + 用户意图 | Phase 1 路由树 |
| **D3** 加载哪些 Reference | 场景 + 当前步骤需求 | Phase 1 Scenario 表 |
| **D4** 编译失败如何处理 | JSON `status`/`message` + 重试次数 | Build Fallback Chain |

---

## 4. 内容结构

### 4.1 文件树

```
jugg-android-dev-loop/
├── SKILL.md                              ← Agent 入口 (≤200行)
├── README.md                             ← 本文件（维护者指南）
├── ADK_RULES.md                          ← 内容治理标准
└── references/                           ← 按需加载（单个≤150行）
    ├── cli_manual.md                     ← UI/高级命令参数详情
    ├── error_patterns.md                 ← 编译/运行时错误诊断
    ├── flow_android_test.md              ← androidTest / instrument 流程
    ├── flow_compile_deploy.md            ← 编译/部署流程
    ├── flow_with_auto_run.md             ← 有 auto-run entry 流程
    ├── guide_auto_run_entry.md           ← auto-run entry 配置指南
    ├── guide_install_cli.md              ← Jugg CLI 安装指南
    └── policy_incremental_compile_limits.md  ← 增量编译限制策略
```

### 4.2 SKILL.md 内部结构

```
Frontmatter        → 触发条件 + 元数据
Phase 0            → Context Interview（变量收集）
Phase 1            → Scenario Route & Load（路由树 + 场景表）
Mandatory Rules    → 全局约束（2条）
CLI Quick Reference → 所有场景必用的 CLI 命令（入口、build/deploy、runtime）
Build Fallback Chain → 编译失败回退路径（两个 flow 共用）
```

---

## 5. 迭代指南

**所有迭代必须遵守 [ADK_RULES.md](ADK_RULES.md)**，核心规则：

1. **三问法**：新增前问——是控制流/决策/护栏吗？加完 SKILL.md 超 200 行吗？Reference 超 150 行吗？峰值上下文超 500 行吗？
2. **零和原则**：SKILL.md 新增必须伴随等量移除/下沉。
3. **Review Checklist**：见 ADK_RULES.md §5.3。

### 常见场景

| 场景 | 操作 |
|------|------|
| 新增错误模式 | 只改 `error_patterns.md`，SKILL.md 无需改动 |
| 新增 CLI 命令（高频/必须了解） | 改 SKILL.md §CLI Quick Reference |
| 新增 CLI 命令（低频/参数复杂） | 改 `cli_manual.md` + 在 SKILL.md §UI Commands 补指针 |
| 新增场景流程 | 新建 `flow_*.md` + 更新 Phase 1 路由树和场景表 |
| 修改路由决策 | 改 SKILL.md Phase 1 + 本 README §3 决策表 |

---

## 6. 设计原则备忘

| 原则 | 违反信号 |
|------|---------|
| **按需加载** | 出现"先全部读取 references" |
| **信息完备再动手** | 跳过 Phase 0 |
| **路由优先** | 直接执行步骤而不经过 Phase 1 路由 |
| **场景不混用** | 同时引用 flow_compile_deploy 和 flow_with_auto_run |
| **SKILL.md 只管路由** | 在 SKILL.md 中写具体执行步骤 |
