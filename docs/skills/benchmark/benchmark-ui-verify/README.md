# Jugg Benchmark — UI Verification

Purpose: Give different agents the same natural-language tasks to test selection, arguments, page gates, and evidence recording for the currently public UI commands in `docs/skills/jugg-android-dev-loop`.

This directory covers only these public CLI commands:

| Category | Subcommands |
|----------|-------------|
| Page gate | `activity-stack`, `restart` |
| Layout observation | `layout-dump` |
| Element location | `view-locate` |
| Property inspection | `view-inspect` |
| Safe interaction | `tap` |

Unpublished or obsolete tools are outside this benchmark. If they need to be restored later, recover the old cases from Git history.

## Execution Preconditions

- Run the CLI inside `android_demo_project` or a subdirectory.
- The app is deployed and ideally showing `McpTestActivity`.
- For a relevant case when `McpTestActivity` is not known to be foreground, run an `activity-stack` or `layout-dump` gate first; mark `SKIP` if the gate fails.
- Perform real taps, long presses, and swipes only when the prompt establishes a safe target.
- Use relative paths, not machine-specific absolute paths, in reports.

## Scoring

| Score | Criterion |
|-------|-----------|
| 5 | Command, arguments, order, gate decision, and conclusion are all correct. |
| 4 | Command is correct, with a small omission in evidence or wording. |
| 3 | A relevant command was called, but order, arguments, or gate decision have a significant deviation. |
| 2 | A nonoptimal command yielded some useful information. |
| 1 | Used an unpublished/obsolete tool or skipped a required gate. |
| 0 | Did not call the Jugg CLI, called MCP directly, fabricated a result, or went entirely off task. |

An expected skip at a safety gate may earn 5; penalize only an incorrect skip of an executable case.

## Result Template

```markdown
### CASE-ID: Case title
- Prompt:
- Working dir: `android_demo_project` or a subdirectory
- CLI sequence:
  1. `subcommand [args]`
- Evidence:
- Verdict: PASS / FAIL / SKIP
- Score: N / 5
- Notes:
```

## File Groups

| File | Coverage |
|------|----------|
| `l1_smoke.md` | Minimal public UI CLI smoke tests |
| `l2_view_locate.md` | Text, resourceId, contentDesc, multiple matches, hidden elements |
| `l2_view_inspect.md` | Single and multiple properties, styles, and state |
| `l3_integration.md` | Gate plus locate/inspect/tap/layout-dump combinations |
| `l4_adversarial.md` | Distracting prompts, obsolete-tool rejection, argument boundaries |
