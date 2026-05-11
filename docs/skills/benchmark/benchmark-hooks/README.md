# Jugg Benchmark - Agent Hooks

用途：交给不同 Agent，用同一套步骤验证 Jugg agent hooks 是否符合预期。

本目录是 Agent hook 集成 benchmark，不是 CLI 命令 benchmark。用例验证 hooks 是否已正确配置，并能被被测 Agent 的文件编辑、命令执行和结束会话动作真实触发。用例不直接调用 hook 脚本，不使用 fake `jugg.py status` 代替真实触发链路。

## 真相源

- Hook 安装说明：`docs/skills/install/agent_setup.md`
- 行为说明：`docs/ai_knowledge/04_engineering_ide.md`、`docs/ai_knowledge/08_mcp_tools_list.md`

## 文件分层

| 文件 | 覆盖点 |
|------|--------|
| `l2_agent_hooks.md` | 通过真实 Agent 编辑和命令动作验证 `edit/command` hook 触发、raw Gradle 阻断/二次放行与 `jugg gradle-build` 不误拦截 |
| `l3_agent_feedback.md` | 被测 Agent 通过真实编辑、命令和结束会话动作触发 hooks，并记录可见反馈原文（edit 提示、raw Gradle 阻断/二次放行、`jugg gradle-build` 不误拦截、stop 阻断/二次放行） |
