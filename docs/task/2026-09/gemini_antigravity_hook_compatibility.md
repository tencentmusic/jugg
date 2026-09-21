# Gemini 与 anti-gravity Hook 兼容方案

## 背景

Jugg 当前把 Gemini 客户端安装到 `~/.gemini/settings.json`，使用 Gemini CLI 的事件名和工具名。anti-gravity 从 `~/.gemini/config/` 发现 Skill 与 `hooks.json`，使用命名 hook、`PreToolUse` / `PostToolUse` / `PreInvocation` / `Stop` 事件，以及 `run_command` 等工具名。因此选择 Gemini 安装时，anti-gravity 无法触发 Jugg hooks。

## 目标行为

- Gemini 安装选项继续支持 Gemini CLI。
- 当 `~/.gemini/config/` 已存在时，同时安装 anti-gravity Skill 与 hooks。
- anti-gravity 修改 Android 源码后执行 raw Gradle 验证时，Jugg command hook 能按现有策略拦截。
- anti-gravity 停止前仍有未验证 Android 改动时，Jugg stop hook 能要求继续执行。
- 不覆盖或静默修复无法解析的用户 hooks 配置。

## 变更范围

- `GeminiAgentInstaller`：增加 anti-gravity Skill home 与 hook target。
- `AgentHookTarget`、`JuggHookInstaller`：支持 anti-gravity 命名 hook 格式。
- `docs/skills/hooks/*.py`：适配 anti-gravity camelCase payload、工作目录与输出协议。
- `InstallAgentsTest`、`JuggHookInstallerMultiAgentTest` 和 hook Python 测试：覆盖双客户端安装和协议行为。
- `docs/skills/install/agent_setup.md`、`docs/ai_knowledge/98_code_map.md`：同步安装路径和兼容边界。

## 明确不做

- 不新增独立 anti-gravity 安装选项。
- 不修改 anti-gravity 自身实现。
- 不自动覆盖现有格式错误的 `~/.gemini/config/hooks.json`。

## 验证

1. 先增加失败测试，证明当前没有 anti-gravity target 和协议适配。
2. 执行定向 Kotlin 测试与 hook Python 测试。
3. 执行 `./gradlew :main:compileKotlin` 或仓库允许的等价定向编译。
4. 对生成的 anti-gravity `hooks.json` 做 JSON 结构检查，并用真实形状 payload 验证 command/edit/stop 输出。
5. 委托只读实现审查，处理有效问题后重新验证。
