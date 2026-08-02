# Jugg CLI Strict 1:1 To-do

> 更新时间：2026-05-10
> 范围：推广前 CLI/MCP/Skill 参数契约验收。
> 原则：推广期只保留 canonical 参数；CLI 的 kebab-case 仅作为 `--camelCase` 的机械归一化形式，不保留业务 alias。

## 当前已完成

| ID | 项目 | 状态 | 证据 |
|----|------|------|------|
| S1 | `layout-verify` 不再作为 MCP tool 暴露 | Done | `McpToolActionRegistry.defaultActions()` 已移除；`08_mcp_tools_list.md` 标为未注册但存在 |
| S2 | `instrument` MCP schema 只暴露 canonical 参数 | Done | `sourcePath/class/method/runner/extras`；不再暴露 `clazz/instrumentationRunner/e` |
| S3 | `instrument` CLI 只接受 canonical 参数 | Done | `--source-path/--sourcePath`、`--class`、`--method`、`--runner`、`--extras`；拒绝 `--clazz/--instrumentationRunner/-e/--e` |
| S4 | Skill 文档示例同步 `instrument --extras` | Done | `SKILL.md`、`references/cli_manual.md` 已删除 `-e` 示例 |
| S5 | AI 知识库 CLI/MCP 文档同步 | Done | `08_cli_tools_list.md`、`08_mcp_tools_list.md` 已删除 legacy alias |

## P0：继续验收项

| ID | 项目 | 执行方式 | 通过标准 |
|----|------|----------|----------|
| P0-1 | 重新生成 CLI/MCP inventory diff | 运行现有 `build/reports/jugg-cli-acceptance` 生成脚本或等价脚本 | 除明确内部工具外，不再出现 CLI/MCP 参数无法机械映射的 warning |
| P0-2 | `instrument` live androidTest smoke | 按 `jugg_cli_agent_skill_cli_test_guide.md` 的 androidTest 阶段执行 | `--source-path` 可运行；`--class/--method/--extras` 可透传；legacy alias 明确失败 |
| P0-3 | Skill 示例 parser 复验 | 对 `SKILL.md` 与 `references/cli_manual.md` 中的可执行示例逐条 parse | 无示例引用不存在的命令或不支持的参数 |
| P0-4 | CLI 空参数命令 unknown option 严格性审计 | 对 `compile/status/devices/activity-stack/gradle-build/clean-reinstall` 等无参数命令追加伪参数 | 推广口径建议 unknown option 应失败；如果保留忽略行为，需要在文档中说明 |

## P1：设备/UI/androidTest 阻塞项

| ID | 项目 | 阻塞条件 | 恢复方式 |
|----|------|----------|----------|
| P1-1 | 设备命令 smoke | 需要在线 Android 设备或模拟器 | `jugg devices` 返回至少一个可用设备后恢复 |
| P1-2 | runtime 命令 smoke | 需要 app 可启动 | 先跑 `deploy` 或已知 app 已安装后恢复 `restart/activity-stack` |
| P1-3 | UI observe smoke | 需要 app 前台页面稳定 | `layout-dump/view-locate/view-inspect` 使用同一个安全 selector |
| P1-4 | UI interaction smoke | 需要安全可点击目标 | 只在测试页或无副作用控件上跑 `tap/long-press/swipe` |
| P1-5 | `wait-logs` live smoke | 需要已知 marker 或可触发日志 | 优先使用 auto-run marker；否则记录 SKIP |

## P2：文档治理项

| ID | 项目 | 当前状态 | 建议 |
|----|------|----------|------|
| P2-1 | `cli_manual.md` 超 ADK reference 150 行预算 | 用户已决定暂不压缩 | 只在后续文档治理任务处理 |
| P2-2 | `guide_install_cli.md` 超 ADK reference 150 行预算 | 用户已决定暂不压缩 | 只在后续文档治理任务处理 |
| P2-3 | 旧 task 设计稿含 `-e`、`instrumentationRunner` 历史描述 | 历史文档允许保留 | 正式知识库以 `08_cli_tools_list.md`、`08_mcp_tools_list.md` 为准 |

## 恢复入口

1. 先读 `docs/task/2026-05/jugg_cli_release_acceptance_progress.md` 看全局验收状态。
2. 再读本文，看严格 1:1 的剩余项。
3. 给外部 Agent 执行 CLI 兼容性测试时，直接提供 `docs/skills/benchmark/benchmark-cli/README.md`。
