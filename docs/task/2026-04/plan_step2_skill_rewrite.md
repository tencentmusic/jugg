# Step 2：Skill 重写

> 方向文档，不含具体实现方案。执行前需与 agent 重新讨论实现细节。

## 目标

`docs/skills/jugg-android-dev-loop/SKILL.md` 完全对齐新三工具模型，消除 LLM 的软约束漂移。

## 方向

### 核心变更 1：页面导航前置门控（硬约束）

Step 3 Runtime Verify 入口必须有强制 Gate，要求 LLM 输出固定格式后才能继续：

- 先调用 `activity-stack` 确认当前页面
- 输出 Gate Result 行（格式固定，缺少此行则不得进入验证）
- 页面不匹配时通过 `restart_app(tap_actions=...)` 导航，再重新 Gate

### 核心变更 2：工具优先级重写

旧的优先级提及 `layout-dump`，需完全替换为三工具模型：
- 验证优先级：`figma_layout_verify` > `view-locate` > `view-inspect` > `screenshot`
- `layout-dump` 从 Skill 可见工具列表中彻底移除

### 核心变更 3：Phase 1 Read Gate 触发词对齐

- `figma, verify, spacing, alignment` → 加载 `tool_cards_runtime_observe.md` + `guide_ui_verify_assertion.md`
- 移除对 `layout-dump` 的显式触发词

### 核心变更 4：软约束改硬 Gate

Mandatory Rules 中的"FORBIDDEN"类规则需配套强制输出格式，让 LLM 无法跳过：
- 每个 Gate 步骤须有 mandatory output 行
- 缺少 output 行 = 规则未执行，不得向下继续


## 验收方向

按新 Skill 执行的 LLM agent 不会主动调用 `layout-dump`，且在进入验证前必定先确认页面。
