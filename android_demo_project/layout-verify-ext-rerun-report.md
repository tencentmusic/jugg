# layout_verify 不一致题复验报告（重跑）

- 项目：`/Users/wormchen/IdeaProjects/jugg/jugg_f1/android_demo_project`
- 时间：2026-03-08
- 目标：复验与答案不一致的 5 题（`C-03 / E-01 / E-02 / E-05 / E-11`）

## 复验步骤

1. `restart_app`
2. 点击文本 `MCP Test Page` 进入 `McpTestActivity`
3. 导出三份快照：
   - 普通：`layout_1772936787488.json`
   - 含 GONE：`layout_1772936787577.json`
   - 多窗口 + 含 GONE：`layout_1772936787689.json`
4. 对不一致题逐项重测

## 快照中实际可见的 MCP 相关 id（节选）

三份 dump 一致可见（多窗口 dump 额外看到 `btn_mcp_test_page`）：
- `btn_mcp_unique_text`
- `btn_mcp_resource_target`
- `btn_mcp_repeat_a`
- `btn_mcp_repeat_b`
- `btn_mcp_visibility_visible`
- `btn_mcp_visibility_hidden`
- `tv_mcp_title`
- `tv_mcp_action_state`
- `layout_mcp_swipe_items`
- `tv_mcp_swipe_section_title`
- `tv_mcp_swipe_section_desc`
- `tv_mcp_case_group_summary`

未出现在三份 dump 的目标 id：
- `tv_mcp_colored_text`
- `layout_mcp_alpha_bg`
- `tv_mcp_long_text`
- `btn_mcp_show_dialog`

## 逐题复验结果

### LV-EVAL-C-03
- 调用：`relation spacing(vertical, 20dp±5)`，target=`tv_mcp_title`，target2=`btn_mcp_unique_text`
- 返回：`data.result=FAIL`
- 证据：`actual=216dp`，`expected=20dp±5dp`
- 附加证据：`target bounds=[42,331,404,402]`，`target2 bounds=[42,968,1038,1094]`

### LV-EVAL-E-01
- 调用：`assert exists`，target=`tv_mcp_colored_text`
- 返回：`data.result=ERROR`
- 证据：`target not found: resourceId=tv_mcp_colored_text`

### LV-EVAL-E-02
- 调用：`assert exists`，target=`layout_mcp_alpha_bg`
- 返回：`data.result=ERROR`
- 证据：`target not found: resourceId=layout_mcp_alpha_bg`

### LV-EVAL-E-05
- 调用：`assert exists`，target=`tv_mcp_long_text`
- 返回：`data.result=ERROR`
- 证据：`target not found: resourceId=tv_mcp_long_text`

### LV-EVAL-E-11
- 调用：`assert exists`，target=`btn_mcp_show_dialog`
- 返回：`data.result=ERROR`
- 证据：`target not found: resourceId=btn_mcp_show_dialog`
- 说明：触发按钮不存在，因此无法进入“点击后出现对话框”的验证路径。

## 结论（供 MCP 开发者）

- 本次重跑结果与上次一致：
  - `C-03` 稳定复现为 `216dp`（非 20dp 级别差异）。
  - `E-01/E-02/E-05/E-11` 对应目标控件/按钮在当前运行页面中均不存在（普通/含 GONE/多窗口均未出现）。
- 建议优先排查：
  1. 当前 APK/分支是否包含 EXT 测试控件；
  2. 是否需要额外入口或开关才能显示 EXT 区域；
  3. `C-03` 的 spacing 计算口径与题目口径是否一致（edge-to-edge vs 设计 margin）。
