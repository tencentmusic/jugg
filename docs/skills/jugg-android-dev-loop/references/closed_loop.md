# Closed-loop Policy (Purpose-Driven)

Purpose: define when to run full closed-loop and when to stop early.

## Read Gate (Avoid Blind Loading)

Entry gate is defined in `SKILL.md` (`Read Gate (Single Source of Truth)`).
This file is load-on-demand only after `LoadDecision` selects `references/closed_loop.md`.

## Execution Objective

Complete tasks using deterministic MCP loop with objective evidence:

`build/deploy -> runtime actions (optional) -> evidence -> verdict`

No unbounded retries. No success claim without artifacts.

## Minimal Closed-loop Path

1. Build path:
   - default: `compile_and_deploy`
   - compile-only case: `compile_only`
2. Runtime actions (if needed):
   - `restart_app` then `tap`
3. Evidence:
   - at least one of `screenshot` / `layout_dump` / recording

## Pass Criteria

All required steps for this task are successful, and evidence artifacts exist on disk.

## Stop Criteria

Stop and ask user when any condition is met:

- Unknown failure category.
- High-risk change required but confidence is low.
- Retry budget exceeded.
- Device/project context cannot be confirmed.

## Retry Budget

- Same failure category max retries: `3`.
- After 3 failed `compile_and_deploy`, only then allow `force_gradle_compile`.
- No infinite loop between compile tools.

## Async Compile Policy

For `compile_and_deploy` / `force_gradle_compile`:

- If `isFinal=false`, delegate polling to `awaiter` immediately.
- Poll via `get_compile_status(jobId)` and honor `pollIntervalSuggestedMs`.
- Use terminal status as final source of truth.
- If status is `unknown`, treat as invalid job/context and stop polling.

## Fallback Sequence (Deterministic)

1. `compile_and_deploy`
2. retry `compile_and_deploy` (up to 3)
3. `force_gradle_compile`
4. retry `compile_and_deploy`
5. `clean_reinstall_apk`
6. still failing -> stop and report diagnosis

Special handling:

- `MCP_NO_DEVICE`: ask user to connect/start device, or switch to `compile_only`.
- `MCP_PROJECT_NOT_INITIALIZED`: ask user to open/init IDE project.

## Evidence Policy

- Prefer lightweight evidence first: `activity_stack`, `layout_dump`, `screenshot`.
- For multi-step interaction flows (>=2 user actions), add recording by default.
- Final accepted artifacts should be staged at `${projectDir}/build/mcp_fetch/final` with stable names.

Additional guardrails:

- Taps must be derived from `layout_dump` node bounds center (no guessed coordinates).
- For floating/edge/transient controls, pass verdict requires controls fully visible and feedback observed after action.

## Response Checklist

Each result summary should include:

- `projectDir` used
- build path used
- key steps and statuses
- artifact absolute paths
- final pass/fail verdict
- next action on failure
