# Jugg Benchmark - Agent Hooks

用途：交给不同 Agent，用同一套步骤验证 Jugg agent hooks 是否符合预期。

本目录是 Agent hook 行为 benchmark，不是 CLI 命令 benchmark。用例直接验证 `docs/skills/hooks` 中的 hook 脚本，必要时使用 fake `jugg.py status` 隔离 Android Studio、设备和真实 Jugg MCP 服务。

## 真相源

- Hook 脚本：`docs/skills/hooks/start.py`、`edit.py`、`command.py`、`stop.py`、`hook_common.py`
- Hook 安装说明：`docs/skills/install/agent_setup.md`
- 行为说明：`docs/ai_knowledge/04_engineering_ide.md`、`docs/ai_knowledge/08_mcp_tools_list.md`

## 文件分层

| 文件 | 覆盖点 |
|------|--------|
| `l2_agent_hooks.md` | `start/edit/command/stop` hook 的软提醒、硬阻断、二次放行与未全量编译放行 |
