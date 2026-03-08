# layout_verify 重构开发方案（默认最新快照 + 批量断言）

> 目录：`docs/task/layout_verify_eval`
> 日期：2026-03-08
> 方案类型：非兼容重构（确认当前无外部安装用户）
> 决策来源：`feature_backlog.md` + 本轮讨论结论

---

## 1. 背景与目标

### 1.1 当前核心痛点

1. 断言前必须手动 `layout_dump`，调用链冗长。
2. `dumpFile` 容易复用旧快照导致静默错误。
3. 多属性验证需要多次 `layout_verify`，token 消耗高且中间状态可能漂移。
4. 文档与 skill 当前都默认 `layout_dump -> layout_verify(dumpFile)` 三步，成本高。

### 1.2 本次重构目标

1. `layout_verify` 默认自动获取“最新布局快照”，调用方不再需要先手动 `layout_dump`。
2. 新增 `asserts` 批量断言，单次调用返回多项结果与聚合结论。
3. 保留“指定历史 dump 文件路径”能力用于复现与回放。
4. 同步 MCP 描述、AI 知识文档、测试用例文档、skill 指南，统一新语义。

### 1.3 非目标（本期不做）

1. 不实现 F-02 纯读取模式（`INFO`）。
2. 不实现新的并发执行模型（仅澄清行为）。
3. 不扩展新的 UI 属性（如 `backgroundColor/maxLines/ellipsize`）。

---

## 2. 重构后的协议语义（MCP）

## 2.1 输入参数变更

`layout_verify` 新输入（重点字段）：

- 必填：`projectDir`, `target`
- 互斥组（必须且仅能传一组）：
  - `asserts`（数组，至少 1 项；单断言场景传 1 项即可）
  - `relation`
- 可选：`target2`（`relation` 必填）
- 可选：`dumpFile`

`dumpFile` 语义：

1. 传绝对路径：使用指定历史 dump（回放模式）。
2. 不传：默认自动获取当前最新布局快照（自动快照模式）。

> 备注：本期不引入 `dumpFile="latest"` 关键字，避免与“自动快照模式”语义重叠。

### 2.2 执行模式判定

1. `dumpFile` 传路径：走 `explicit_dump`。
2. 未传 `dumpFile`：走 `auto_dump`（内部先抓一份最新快照，再执行断言）。
3. 若断言属性在 dump 模式不支持（如 `textSizeSp`）：自动切换 `live`（单次请求内统一模式）。

### 2.3 输出字段扩展

`data` 精简为最小返回集：

- `result`: 聚合结果
- `message`: 精简结论描述
- `items`: 明细数组（每项保留 `index/result/message`）

`data.result` 使用：`PASS | PARTIAL_FAIL | FAIL | ERROR`。

### 2.4 批量断言聚合规则

`asserts` 中每项独立计算，整体聚合规则为：

- 任一 `ERROR` -> 整体 `ERROR`
- 全部 `FAIL` -> 整体 `FAIL`
- 同时出现 `PASS` 与 `FAIL` -> 整体 `PARTIAL_FAIL`
- 全部 `PASS` -> 整体 `PASS`

`status` 映射保持现有规则：

- 整体 `PASS` -> `status=OK`
- 整体 `PARTIAL_FAIL/FAIL/ERROR` -> `status=ERROR`

### 2.5 参数校验规则（新增）

1. `asserts` 与 `relation` 互斥，违反时返回 `MCP_INVALID_PARAMS`。
2. `asserts` 必须是非空数组。
3. `relation` 模式要求 `target2`。
4. `target` 选择器至少包含 `resourceId/text/contentDesc/className` 之一。

---

## 3. 代码改造方案

### 3.1 主要改动文件

1. `main/src/main/java/com/sickworm/intellij/jugg/mcp/actions/LayoutVerifyMcpToolAction.kt`
2. `main/src/main/java/com/sickworm/intellij/jugg/mcp/actions/McpToolSchemas.kt`（如需复用 schema 片段）
3. `main/src/main/java/com/sickworm/intellij/jugg/mcp/McpErrorCode.kt`（仅在引入新错误码时）
4. 可选新增：
   - `main/src/main/java/com/sickworm/intellij/jugg/mcp/actions/LayoutDumpFetcher.kt`
   - 目的：复用抓取快照逻辑，避免 `LayoutDumpMcpToolAction` 与 `LayoutVerifyMcpToolAction` 重复实现。

### 3.2 `LayoutVerifyMcpToolAction` 重构点

1. 输入 schema 增加 `asserts`。
2. `execute()` 改为三阶段：
   - 参数互斥校验
   - 模式判定（explicit_dump / auto_dump / live）
   - 统一结果封装
3. 增加“自动快照”路径：
   - 内部调用 ViewHierarchy dump
   - 落盘到 `build/jugg/mcp_fetch/layout_verify/auto_*.json`
   - 复用 dump 解析断言逻辑
4. 增加批量断言执行器：
   - `assertDumpNodesBatch(...)`
   - `assertLiveBatch(...)`
5. 返回中仅保留 `result/message/items`（去除 `sourceUsed/summary/actual/expected/unit`）。
6. `candidates` 输出增加排序依据（见 3.4）。

### 3.3 自动快照实现建议

优先方案：抽公共 helper（推荐）

- 新建 `LayoutDumpFetcher`：
  - 输入：runtime/adb/packageName/rootLayout/isIncludeGone/isAllWindows/outputDir
  - 输出：`FetchResult(filePath, contentBytes)`
- `LayoutDumpMcpToolAction` 与 `LayoutVerifyMcpToolAction` 共用。

备选方案：在 `LayoutVerifyMcpToolAction` 内最小复制 `layout_dump` 逻辑（实现快但重复多）。

### 3.4 candidates 相似度排序（与本次一起落地）

排序策略：

1. 仅对“调用方实际传入的 selector 字段”评分。
2. `resourceId`：前缀匹配优先，编辑距离次之。
3. `text/contentDesc/className`：完全匹配 > contains > 编辑距离。
4. 输出 `data.candidates[]` 保留前 5，并新增每项 `score/reason`（便于 agent 纠错）。

---

## 4. TDD 测试方案（必须先测后改）

> 约束：遵循 TDD；使用 Mockito；先写失败测试，再实现代码。

### 4.1 核心单测文件

- `main/src/test/java/com/sickworm/intellij/jugg/mcp/actions/LayoutVerifyMcpToolActionTest.kt`
- 如引入 helper：新增 `LayoutDumpFetcherTest.kt`

### 4.2 新增测试分组

#### A. 模式判定

1. 未传 `dumpFile` + 普通属性 -> `auto_dump`
2. 传 `dumpFile=/abs/path.json` -> `explicit_dump`
3. 未传 `dumpFile` + `textSizeSp` -> `live`

#### B. 批量断言

1. `asserts` 全 PASS -> 整体 PASS
2. 混合 PASS+FAIL -> 整体 PARTIAL_FAIL
3. 全 FAIL -> 整体 FAIL
4. 含 ERROR -> 整体 ERROR
5. 验证 `items` 字段正确（仅 `index/result/message`）
5. 验证自动快照只抓取一次（Mockito verify 调用次数）

#### C. 参数约束

1. 同时传 `asserts` + `relation` -> `MCP_INVALID_PARAMS`
2. `asserts=[]` -> `MCP_INVALID_PARAMS`
3. `relation` 缺 `target2` -> `MCP_INVALID_PARAMS`

#### D. 自动快照稳定性

1. 自动快照成功，返回 `result/items` 正常
2. 自动快照失败（server unavailable）返回错误
3. 自动快照文件不存在/损坏时错误路径

#### E. candidates 排序

1. resourceId 拼写错误时，最相近候选排第一
2. message/reason 含推荐依据

### 4.3 现有测试改造

1. 原“未传 `dumpFile` 就是 live 模式”的测试全部改新语义。
2. 保留 explicit dump 与 live 路径回归。
3. 更新 output schema 断言（移除 `sourceUsed/summary/actual/expected/unit` 相关预期）。

---

## 5. 文档更新清单

## 5.1 AI 知识库文档（必须）

1. `docs/ai_knowledge/08_mcp_usage.md`
   - 更新 `layout_verify` 模式描述为“默认自动快照”
   - 增加 `asserts` 输入与返回结构
2. `docs/ai_knowledge/08_mcp_design.md`
   - 更新 `layout_verify` 设计说明（auto_dump 模式）
3. `docs/ai_knowledge/08_mcp_test_case.md`
   - 更新 VERIFY 小节前置步骤，不再强依赖先 `layout_dump`
4. `docs/ai_knowledge/08_mcp_test_case_layout_verify.md`
   - 执行流程/重要说明同步新语义

## 5.2 task 文档（本目录）

1. `docs/task/layout_verify_eval/layout-verify-workflow.md`
   - 第六章改为“默认直接 verify，必要时指定历史 dump”
2. `docs/task/layout_verify_eval/feature_backlog.md`
   - 标注 F-01/F-03/F-04 进入实施中

---

## 6. Skill 更新清单

### 6.1 必改文件

1. `docs/skills/jugg-android-dev-loop/SKILL.md`
2. `docs/skills/jugg-android-dev-loop/references/tool_cards_runtime_observe.md`
3. 如涉及安装说明：`docs/skills/install/client_setup.md`

### 6.2 更新要点

1. 删除或降级“必须先 `layout_dump` 再 `layout_verify`”规则。
2. 新默认流程改为：
   - `layout_verify`（自动快照）
   - 仅当要复现历史状态时传 `dumpFile`。
3. 批量验证场景优先使用 `asserts`，减少多次调用。
4. `textSizeSp` 等 live-only 属性说明改为“自动切 live，或显式 live（若后续引入 mode 参数）”。

---

## 7. MCP 对外说明（变更公告建议）

### 7.1 变更摘要

1. Breaking Change：未传 `dumpFile` 不再等价 live 模式。
2. 新能力：`asserts` 批量断言。
3. 新聚合结果：`PARTIAL_FAIL`（混合 PASS/FAIL）。
4. 返回精简：仅保留 `result/message/items`（去除 `sourceUsed/summary/actual/expected/unit`）。

### 7.2 推荐调用示例

#### 单断言（默认自动快照；用 `asserts` 传 1 项）

```json
{
  "projectDir": "/abs/project",
  "target": {"resourceId": "btn_mcp_unique_text"},
  "asserts": [
    {"property": "text", "value": "Unique MCP Target"}
  ]
}
```

#### 批量断言（默认自动快照）

```json
{
  "projectDir": "/abs/project",
  "target": {"resourceId": "btn_mcp_unique_text"},
  "asserts": [
    {"property": "exists"},
    {"property": "clickable", "value": true},
    {"property": "text", "value": "Unique MCP Target"}
  ]
}
```

#### 历史回放（指定旧 dump）

```json
{
  "projectDir": "/abs/project",
  "dumpFile": "/abs/old/layout_1741377000000.json",
  "target": {"resourceId": "btn_mcp_unique_text"},
  "asserts": [
    {"property": "text", "value": "Unique MCP Target"}
  ]
}
```

---

## 8. 实施步骤（按 TDD）

1. 先补/改测试（模式判定、批量断言、自动快照、候选排序）。
2. 改 `LayoutVerifyMcpToolAction` 输入 schema 与参数校验。
3. 实现 auto_dump 路径（优先抽公共 dump fetcher）。
4. 实现 `asserts` 批量执行与聚合输出。
5. 实现 candidates 相似度排序。
6. 全量跑相关单测并修复。
7. 更新 MCP 文档、测试文档、workflow 文档、skill 文档。
8. 输出变更说明（breaking change + 新示例）。

---

## 9. 验收标准

1. 调用 `layout_verify`（不传 `dumpFile`）可直接完成断言。
2. `asserts` 覆盖多属性场景，单调用返回聚合结果。
3. 指定历史 dump 路径仍可准确回放。
4. 相关单测全部通过，且新增测试覆盖核心分支。
5. `ai_knowledge` + `layout_verify_eval` + `skill` 文档全部完成同步。

---

## 10. 风险与规避

1. **风险**：自动快照增加一次设备通信，长链路下耗时上升。  
   **规避**：`asserts` 批量化抵消调用次数；保留 `dumpFile` 回放模式用于高性能场景。

2. **风险**：`auto_dump` 与 live-only 属性混用导致模式复杂。  
   **规避**：模式判定集中实现，并通过单测锁定行为。

3. **风险**：文档/skill未同步导致 agent 继续旧流程。  
   **规避**：将文档更新纳入 DoD（Definition of Done），与代码同 PR 提交。

---

## 11. DoD（完成定义）

1. 代码：核心重构完成，lint/test 通过。
2. 测试：新增/修改测试覆盖通过。
3. 文档：`08_mcp_usage/08_mcp_design/08_mcp_test_case/08_mcp_test_case_layout_verify` 已同步。
4. Skill：`jugg-android-dev-loop` 主文档与 runtime observe 卡片已同步。
5. 产物：本目录新增方案文档 + 提交记录可追踪。
