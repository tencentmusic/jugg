# Jugg Benchmark - Agent Hooks

用途：交给不同 Agent，用同一套步骤验证 Jugg agent hooks 是否符合预期。

本目录是 Agent hook 集成 benchmark，不是 CLI 命令 benchmark。用例验证 hooks 是否已正确配置，并能被被测 Agent 的文件变更、命令运行和结束会话动作真实触发。用例不直接调用 hook 脚本，不使用 fake `jugg.py status` 代替真实触发链路。

需要触发 Jugg pending changes 的源码触发文件必须位于 `app/src/main/java/com/example/myapplication/`，并且只能新增、移动或修改 `Hook*Trigger.kt` 这类隔离文件；不要修改现有业务文件。非 sourceset 误阻断验证用例会明确要求使用 `hook_benchmark_scratch/`。

报告中的路径默认使用相对路径。例外：hook 反馈原文中由客户端输出的绝对脚本路径可以原样保留，用于证明 Agent 实际看到了 hook 反馈。

## 真相源

- Hook 安装说明：`docs/skills/install/agent_setup.md`
- 行为说明：`docs/ai_knowledge/04_engineering_ide.md`、`docs/ai_knowledge/08_mcp_tools_list.md`

## 文件分层

| 文件 | 覆盖点 |
|------|--------|
| `l2_agent_hooks.md` | 通过真实 Agent 文件变更和命令动作验证 `edit/command` hook 触发、sourceset raw Gradle 阻断、raw Gradle 二次放行（Codex/Claude 可见 warning，Cursor/Gemini 可静默放行）、非 sourceset 文件不误阻断、`jugg gradle-build` 不误拦截，以及新增/修改/多文件同轮变更均可被检测 |
| `l3_agent_feedback.md` | 被测 Agent 通过一条新增源码 command 链路和一条 stop 链路验证可见反馈原文（raw Gradle 首次阻断/二次放行、`jugg gradle-build` 不误拦截、stop 阻断/二次放行；Cursor/Gemini stop 二次放行可静默；Codex/Claude stop 二次 warning 由执行人人工确认） |
