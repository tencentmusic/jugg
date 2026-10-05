# Jugg Benchmark — CLI Commands

Purpose: Give different agents the same steps to test every Jugg CLI mentioned by `docs/skills/jugg-android-dev-loop`.

This directory is an agent-behavior benchmark, not a shell-script manual. Each case states a task in natural language. It scores whether the agent uses the `jugg-android-dev-loop` skill to select the right CLI command and arguments, handles missing conditions, and records evidence.

## Sources of Truth

- Skill entry: `docs/skills/jugg-android-dev-loop/SKILL.md`
- CLI argument catalog: `docs/ai_knowledge/08_cli_tools_list.md`
- Android test project: `android_demo_project`
- Historical plans may suggest ideas but are not authoritative for this benchmark.

## Execution Preconditions

Run the CLI inside `android_demo_project` or one of its subdirectories. The repository root is only for reading skill and benchmark files; it is not the Android `projectDir`.

Do not put machine-specific absolute paths in benchmark documents or reports. Use relative paths such as `android_demo_project` and `docs/skills/jugg-android-dev-loop`.

Each case is an independent task by default. It must not depend on the Activity, page state, logs, or temporary files left by a previous case. Each case prepares its own preconditions, verifies the result, and cleans up side effects.

For cases involving `McpTestActivity` or its elements, enter the page with this route:

```bash
jugg restart && sleep 2 && jugg tap --text "MCP Test Page"
```

After routing, confirm the target page with `activity-stack` or `layout-dump`. If routing or confirmation fails, record `SKIP: page route failed`; do not execute a selector for the target page.

## Public CLI Commands in Scope

| Category | Subcommands |
|----------|-------------|
| Basic status | `version`, `status`, `devices` |
| Build and deploy | `compile`, `deploy`, `gradle-build`, `clean-reinstall` |
| Runtime | `restart`, `activity-stack`, `wait-logs`, `ssh-info` |
| UI observation and interaction | `layout-dump`, `view-locate`, `view-inspect`, `tap` |
| androidTest | `instrument` |

Subcommands not listed here are outside this benchmark.

## Agent Rules

- Complete tasks through the Jugg CLI supplied by the `jugg-android-dev-loop` skill.
- Do not call MCP directly, debug CLI internals, or modify benchmark cases.
- Use `--console=json` when structured evidence is needed; put global arguments before the subcommand.
- On failure, record the symptom and output. Do not temporarily fix code or the environment just to pass a case.
- Run state-changing actions such as `clean-reinstall`, real taps, long presses, and swipes only when the test environment is explicitly safe; otherwise record `SKIP`.
- State the reason for an unmet condition, such as `no MCP port`, `no device`, `no foreground app`, `no stable selector`, or `no androidTest source`.
- Exclude environmental `SKIP` cases from the effective-total denominator. For example, exclude the no-device group when a real device is online.
- A case that creates a temporary failure must delete its temporary files and verify that the project recovers. A dirty worktree or unbuildable project earns a low score or zero.
- Discover any selector missing from the prompt through `layout-dump`, `view-locate`, or an equivalent CLI command. Do not invent a resource ID.

## Scoring

| Score | Criterion |
|-------|-----------|
| 5 | Command, arguments, order, condition checks, and conclusion are all correct. |
| 4 | Command is correct, with a small deviation in nonessential evidence or wording. |
| 3 | A relevant command was called, but order, arguments, or conditions have a significant flaw. |
| 2 | Command choice is wrong, but some useful information was obtained. |
| 1 | Wrong `projectDir`, skipped a critical check, left a dirty worktree, or failed to recover a temporary-failure case. |
| 0 | Did not call the Jugg CLI, called MCP directly, omitted the report, or went entirely off task. |

### Score Caps

- Executing a page element selector before routing to its page: at most 2.
- Failing to confirm the target page after routing: at most 4.
- Inventing a resource ID absent from the prompt: at most 3.
- Failing to delete the temporary failure source in a controlled-failure case: at most 2.
- Failing to verify project recovery after cleanup: at most 3.

## Result Template

Append after each case:

```markdown
### CASE-ID: Case title
- Prompt: Natural-language task from the case
- Working dir: `android_demo_project` or a subdirectory
- Precondition: Whether the prerequisite is met, or the SKIP reason
- Route: Page-routing command and result; N/A if no page is involved
- Gate evidence: Activity / layout / selector evidence; N/A if no page is involved
- CLI sequence:
  1. `subcommand [args]`
- Evidence: Key stdout/stderr excerpt or relative report path
- Cleanup: Temporary-file removal and recovery result; N/A if no cleanup was needed
- Verdict: PASS / FAIL / SKIP
- Score: N / 5
- Notes:
```

Append at the end of the full evaluation:

```markdown
## Summary

| File | Case | Verdict | Score | Notes |
|------|------|---------|-------|-------|

Total: XX / YY
Skipped: Z
Effective Total: XX / YY (excluding environmental SKIP cases)
Blockers:
```

## File Groups

| File | Coverage |
|------|----------|
| `l2_ssh_device_connectivity.md` | `version`, `devices`, `status`, `ssh-info`, and project-directory selection |
| `l2_build_deploy.md` | `compile`, `deploy`, `gradle-build`, `clean-reinstall` |
| `l2_media_observe.md` | `layout-dump`, `view-locate`, `view-inspect`, `activity-stack`, `wait-logs` |
| `l2_app_interaction.md` | `restart`, three `tap` modes, and safe interaction decisions |
| `l3_no_device.md` | Executable commands, failures, and skips without a device |
| `l4_adversarial_e2e.md` | Global argument placement and end-to-end combinations |
