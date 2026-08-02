# Jugg CLI 推广验收执行进度

> 创建时间：2026-05-10
> 主计划：`docs/task/2026-05/jugg_cli_release_acceptance_plan.md`
> 恢复原则：从“当前状态”继续，不重复已通过项；遇到需要人确认/设备/IDE 操作的项标记为 Pending。

## 当前状态

- 阶段：A/B/C/G/H 自动可执行验收已推进
- 当前结论：CLI parser 暴露 16 个公开命令；live MCP `tools/list` 返回 19 个工具。Python CLI/parser/hooks 测试通过；installer 定向测试先失败后已修复并通过；无设备 smoke 通过。`layout-verify` 已从 MCP 默认注册表移除；`instrument` 推广期只保留 canonical 参数，历史 alias 已拒绝。
- 下一步：处理或裁决 A3/A4/G1 的非阻塞审计项；设备/UI/androidTest live 项等待可用设备与明确测试目标。

## 任务清单

| ID | 阶段 | 任务 | 状态 | 证据/备注 |
|----|------|------|------|-----------|
| A1 | 静态清单 | 从 CLI parser 生成子命令、flags、required、choices、默认值 inventory | Done | `build/reports/jugg-cli-acceptance/cli_inventory.json` / `.md`，共 16 个命令 |
| A2 | 静态清单 | 从 MCP schema/registry 生成 tool 参数 inventory | Done | live MCP `tools/list` on port 12320；`schema_inventory.json` / `.md`，共 19 个工具 |
| A3 | 静态清单 | 对比 CLI flag 与 MCP 参数 1:1 映射 | In Progress | `layout-verify` 决策为从 MCP 取消注册，已处理；`instrument` alias 已按严格 1:1 移除；部分 MCP optional 未暴露仍需后续审计 |
| A4 | 静态清单 | 对比 `08_cli_tools_list.md`、`cli_manual.md`、`SKILL.md` 命令/参数示例 | Needs Review | 发现 `SKILL.md` 默认 JSON 描述错误，已修；`cli_manual.md`/`guide_install_cli.md` 超 ADK 行数预算 |
| B1 | 单元测试 | 运行 `python3 -m unittest docs/skills/jugg-android-dev-loop/scripts/py/test_jugglib.py` | Done | 4 tests OK |
| B2 | 单元测试 | 运行 jugg-android-dev-loop parser tests | Done | `test_cmd.py` 29 tests OK；`test_cmd_status.py` 1 test OK；`test_jugglib.py` 37 tests OK。原 discover 命令有同名模块冲突，计划已改逐文件 |
| B3 | 单元测试 | 运行 `python3 -m unittest discover docs/skills/hooks/tests` | Done | 13 tests OK |
| B4 | 单元测试 | 运行 installer 相关 Gradle 定向测试 | Done | 初跑 29 tests/3 failed，修复 `JuggCliAutoUpdater` bundle 路径后 29 tests OK |
| C1 | 无设备 smoke | `version` / `--console=json version` | Done | live MCP port 12320；plain/json 均 OK，CLI 1.0.4，plugin 3.0.7-SNAPSHOT |
| C2 | 无设备 smoke | `devices` / `status` | Done | `devices: []`；`status` 输出 hasDevice=false/needFallback=true/stateMessage=not gradle compile yet |
| D1 | 有设备 smoke | `compile` / `deploy` / `restart` / `activity-stack` | Pending | 需要在线设备和可用 Jugg project runtime |
| D2 | 有设备 smoke | `wait-logs` marker 验证 | Pending | 需要已知 marker 或 auto-run entry |
| E1 | androidTest | `instrument --source-path` 基础用例 | Pending | 需要可运行 androidTest source |
| E2 | androidTest | `instrument --class/--method/--extras` 参数透传 | Pending | 需要可运行 androidTest source |
| F1 | UI observe | `layout-dump` / `view-locate` / `view-inspect` | Pending | 需要已启动 app 和可见 UI |
| F2 | UI interaction | `tap` 元素/百分比/swipe | Pending | 需要已启动 app 和安全页面 |
| G1 | skill 审计 | `SKILL.md` 路由、默认动作、fallback、预算检查 | Needs Review | `SKILL.md` 143 行 OK；`cli_manual.md` 233 行、`guide_install_cli.md` 155 行超 150 行预算 |
| G2 | skill 审计 | references 示例命令可被 parser 接受 | Done | `skill_example_parser_check.md`：`SKILL.md`/`cli_manual.md` 可执行示例均被 parser 接受；仅跳过含 `[...]` 的用法模板 |
| H1 | 分发 | skill zip/installer 输出与仓库内容一致 | Done | 修复 auto updater 读取 `docs/skills/docs-skills.zip`；installer 定向测试通过 |

## 进展日志

- 2026-05-10：创建验收执行进度文件。工作区起始状态干净。
- 2026-05-10：完成 A1/A2/A3。报告产物在 `build/reports/jugg-cli-acceptance/`：`cli_inventory.*`、`schema_inventory.*`、`cli_schema_diff.*`。
- 2026-05-10：完成 B1/B2/B3。`unittest discover docs/skills/jugg-android-dev-loop/tests` 因同名 `test_jugglib.py` 冲突失败，已改为逐文件执行且全部通过。
- 2026-05-10：B4 初跑失败 3 个 `JuggCliAutoUpdaterTest`，根因为 auto updater 仍读取旧 `jugg-android-dev-loop.zip`；已改为当前 `docs-skills.zip` bundle 结构并重跑通过。
- 2026-05-10：完成 C1/C2 无设备 smoke：MCP port 12320 可用，`version`/json version、`devices`、`status` 均可执行。
- 2026-05-10：错误体验探针发现缺值 `IndexError` 与 `wait-logs` 缺 marker 未本地拦截；已补测试并修复，`error_probe.md` 全部 SystemExit + 明确错误。
- 2026-05-10：完成 G2 示例 parser 校验；`SKILL.md` 与 `cli_manual.md` 的可执行示例全部通过，含 `[...]` 的用法模板跳过。
- 2026-05-10：按用户确认，`layout-verify` 不再作为 MCP tool 暴露；从 `McpToolActionRegistry.defaultActions()` 移除，并同步 MCP 工具文档。
- 2026-05-10：按“严格 1:1”处理 `instrument` 推广期参数；CLI/MCP 仅保留 `sourcePath`、`class`、`method`、`runner`、`extras`，`--clazz`、`--instrumentationRunner`、`-e`、`--e` 均拒绝，并同步 skill/CLI/MCP 文档。

## 阻塞/待人工确认

- A3：`instrument` 的 `--clazz`/`--instrumentationRunner`/`--e`/`-e` 等 alias 已按严格 1:1 移除；后续只需在 live androidTest 环境复验 `--extras`。
- A3：`layout-verify` MCP tool 已按决策从 MCP 默认注册表移除，保持与 CLI 命令面一致。
- G1：`cli_manual.md` 233 行、`guide_install_cli.md` 155 行超过 ADK reference 150 行预算；是否现在压缩需要人工确认或另开文档治理任务。
- D/E/F：需要在线设备、已启动 app、可运行 androidTest source 或已知 marker；当前先保持 Pending。
