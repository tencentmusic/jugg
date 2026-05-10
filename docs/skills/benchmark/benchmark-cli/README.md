# Jugg Benchmark - CLI 命令

用途：交给不同 Agent，用同一套步骤自动化测试 `docs/skills/jugg-android-dev-loop` 中提到的全部 Jugg CLI。

本目录是 Agent 行为 benchmark，不是 bash 脚本手册。每条用例用自然语言描述任务，评估 Agent 是否能根据 `jugg-android-dev-loop` skill 正确选择 CLI、传参、处理条件不足并记录证据。

## 真相源

- Skill 入口：`docs/skills/jugg-android-dev-loop/SKILL.md`
- CLI 参数清单：`docs/ai_knowledge/08_cli_tools_list.md`
- Android 测试工程：`android_demo_project`
- 历史草稿：`docs/task/jugg_cli_agent_skill_cli_test_guide.md` 只可参考思路，不作为本 benchmark 的权威来源

## 执行前提

Agent 必须在 `android_demo_project` 或其子目录中执行 CLI。仓库根目录只用于读取 skill 与 benchmark 文档，不是 Android projectDir。

禁止在 benchmark 文档和报告中写入本机绝对路径；路径一律使用相对路径，例如 `android_demo_project`、`docs/skills/jugg-android-dev-loop`。

## 当前公开 CLI 命令

| 类别 | 子命令 |
|------|--------|
| 基础状态 | `version`, `status`, `devices` |
| 构建部署 | `compile`, `deploy`, `gradle-build`, `clean-reinstall` |
| 运行时 | `restart`, `activity-stack`, `wait-logs`, `ssh-info` |
| UI 观察与交互 | `layout-dump`, `view-locate`, `view-inspect`, `tap` |
| androidTest | `instrument` |

`layout-verify`、`screenshot`、`record-start`、`record-stop`、`reinstall` 不是当前公开 CLI；Agent 选择这些命令应判为失败。

## Agent 规则

- 只通过 `jugg-android-dev-loop` skill 提供的 Jugg CLI 完成任务。
- 不直接调用 MCP，不调试 CLI 内部实现，不修改 benchmark 用例。
- 需要结构化证据时，可使用 `--console=json`，且全局参数必须放在子命令前。
- 失败时记录现象和输出，不为通过用例而临时修复代码或改环境。
- `clean-reinstall`、真实点击、长按、滑动等可能改变状态的动作，只有测试环境明确安全时才执行；否则记为 `SKIP`。
- 条件不足必须明确写 `SKIP` 原因，例如 `no MCP port`、`no device`、`no foreground app`、`no stable selector`、`no androidTest source`。

## 评分标准

| 分 | 判定 |
|----|------|
| 5 | CLI 选择、参数、顺序、条件判断和结论完全正确 |
| 4 | CLI 选择正确，非关键证据或表述有小偏差 |
| 3 | 调用了相关 CLI，但顺序、参数或条件判断存在明显瑕疵 |
| 2 | CLI 方向错误但仍得到部分可用信息 |
| 1 | 使用过期命令、历史 alias、错误 projectDir 或跳过关键验证 |
| 0 | 未调用 Jugg CLI、直接调用 MCP、报告缺失，或完全跑偏 |

## 结果模板

每条用例完成后追加：

```markdown
### CASE-ID: 用例标题
- Prompt: 用例中的自然语言任务
- Working dir: `android_demo_project` 或其子目录
- CLI sequence:
  1. `subcommand [args]`
- Evidence: 关键 stdout/stderr 摘要或报告文件相对路径
- Verdict: PASS / FAIL / SKIP
- Score: N / 5
- Notes:
```

完整评测末尾追加：

```markdown
## Summary

| File | Case | Verdict | Score | Notes |
|------|------|---------|-------|-------|

Total: XX / YY
Skipped: Z
Blockers:
```

## 文件分层

| 文件 | 覆盖点 |
|------|--------|
| `l2_ssh_device_connectivity.md` | `version`, `devices`, `status`, `ssh-info` 与项目目录判断 |
| `l2_build_deploy.md` | `compile`, `deploy`, `gradle-build`, `clean-reinstall` |
| `l2_media_observe.md` | `layout-dump`, `view-locate`, `view-inspect`, `activity-stack`, `wait-logs` |
| `l2_app_interaction.md` | `restart`, `tap` 三种模式和安全交互判断 |
| `l3_no_device.md` | 无设备时的可执行项、失败项和 skip 判断 |
| `l4_adversarial_e2e.md` | 过期命令、legacy alias、全局参数位置、端到端组合 |
