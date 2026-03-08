# layout_verify 复现与根因分析（不改代码）

> 日期：2026-03-08  
> 范围：复验 `layout-verify-ext-rerun-report.md` 中 5 个不一致题（`C-03 / E-01 / E-02 / E-05 / E-11`）

## 1. 复现结论

在 `android_demo_project` 上按报告流程重跑后，5 题全部稳定复现：

- `LV-EVAL-C-03`：`FAIL`，`actual=216dp`，`expected=20dp±5dp`
- `LV-EVAL-E-01`：`ERROR`，`target not found: resourceId=tv_mcp_colored_text`
- `LV-EVAL-E-02`：`ERROR`，`target not found: resourceId=layout_mcp_alpha_bg`
- `LV-EVAL-E-05`：`ERROR`，`target not found: resourceId=tv_mcp_long_text`
- `LV-EVAL-E-11`：`ERROR`，`target not found: resourceId=btn_mcp_show_dialog`

## 2. 现场证据

### 2.1 dump 证据

导出三份布局快照（普通 / 含 GONE / 多窗口）：

- `layout_1772937035209.json`
- `layout_1772937040478.json`
- `layout_1772937040568.json`

三份 dump 一致包含：

- `tv_mcp_title`
- `tv_mcp_case_group_summary`
- `btn_mcp_unique_text`
- `btn_mcp_resource_target`
- `btn_mcp_repeat_a`
- `btn_mcp_repeat_b`
- `btn_mcp_visibility_visible`
- `btn_mcp_visibility_hidden`

三份 dump 均不包含（EXT 目标）：

- `tv_mcp_colored_text`
- `layout_mcp_alpha_bg`
- `tv_mcp_long_text`
- `btn_mcp_show_dialog`

### 2.2 代码证据

`McpTestActivity` 当前布局确实没有上述 EXT 控件：

- `android_demo_project/app/src/main/res/layout/activity_mcp_test.xml`
- `android_demo_project/app/src/main/java/com/sickworm/jugg/demo/testcase/mcp/McpTestActivity.kt`

且标题与第一个按钮中间存在 `tv_mcp_case_group_summary`，导致 `C-03` 的两元素真实间距远大于 20dp。

### 2.3 能力边界证据（layout_verify）

当前 `layout_verify` 支持属性列表不含 `backgroundColor / maxLines / ellipsize`：

- IDE 侧 dump 模式实现：`main/src/main/java/com/sickworm/intellij/jugg/mcp/actions/LayoutVerifyMcpToolAction.kt`
- App 侧 live 模式实现：`jvmti_agent/src/main/java/com/sickworm/intellij/jugg/viewhierarchy/LayoutVerifier.java`

对现有元素直接验证也会返回：

- `unsupported property in dumpFile mode: backgroundColor`
- `unsupported property: maxLines`
- `unsupported property: ellipsize`

## 3. 根因归纳

### 根因 A：题库/答案与测试页面未对齐

`08_mcp_test_case_layout_verify.md` 与 `08_mcp_test_case_layout_verify_answer_key.md` 假设了 EXT 控件存在，但 `android_demo_project` 页面未实现这些控件。

### 根因 B：`C-03` 评分口径与页面结构冲突

答案按“标题到按钮约 20dp（marginTop=20dp）”评分，但当前页面在两者中间插入了 `tv_mcp_case_group_summary`，`layout_verify relation.spacing` 按真实 bounds gap 计算，结果为 216dp。

### 根因 C：题目要求超出当前工具能力

`E-02`（backgroundColor）与 `E-05`（maxLines/ellipsize）对应的属性当前未实现，无法得到答案表中的 PASS。

## 4. 解决方案（不改代码版本）

## 4.1 短期止血（推荐）

统一“当前分支”的评测基线，修订题库/答案文档：

1. 将 `C-03` 预期改为 `FAIL`（或改题为 `tv_mcp_case_group_summary` 到 `btn_mcp_unique_text` 间距）。
2. 将 `E-01/E-02/E-05/E-11` 标注为“依赖 EXT 页面与扩展属性支持”，在当前基线下记为 `N/A` 或 `ERROR→不符合`。
3. 在 `08_mcp_test_case_layout_verify.md` 增加“运行前 preflight 检查”：
   - 必要控件 ID 是否存在；
   - 必要属性是否受支持；
   - 不满足时跳过 EXT 题并显式标注原因。

## 4.2 长期对齐（保持原答案方向）

若目标是维持答案表的 PASS 设计，则后续代码改造需同时完成两类对齐：

1. 页面能力对齐：在 `McpTestActivity` 增加 EXT 控件与交互（`tv_mcp_colored_text`、`layout_mcp_alpha_bg`、`tv_mcp_long_text`、`btn_mcp_show_dialog` 等）。
2. 工具能力对齐：在 `layout_verify` 增加 `backgroundColor / maxLines / ellipsize` 支持，并补全单测与文档。

只做其一仍会导致评测不稳定或系统性错判。

## 5. 建议执行顺序

1. 先做文档基线修订（短期止血，立即恢复评测一致性）。
2. 再评估是否推进长期能力对齐（涉及跨模块代码与测试补齐）。
