# Recipe: Fix Compile Error

## When to use

- `compile_and_deploy` or `compile_only` fails with source compile error.
- Typical signature: `cannot find symbol` / `unresolved reference`.

## Minimal flow

1. Run `compile_and_deploy(projectDir)`.
2. Parse `message/errorCode/data.detail`.
3. Match pattern in `references/error_patterns.md` (`gradle_unresolved_symbol`).
4. Apply local low-risk fix (e.g., missing import/symbol correction).
5. Re-run `compile_and_deploy(projectDir)`.
6. If success and runtime verification is needed: `restart_app` -> `screenshot`.

## Stop/Fallback

- If unknown pattern or confidence `< 0.8`: stop and ask user.
- If same failure repeats 3 times: follow fallback in `references/tool_cards_compile.md`.

## Evidence

- Required: final compile/deploy success status.
- Optional: screenshot artifact if runtime verification is part of the task.

## Summary template

- Verdict: `PASS|FAIL`
- Pattern: `pattern_id`, `confidence`, `auto_applied`
- Retries: count
- Fix: concise description
- Artifacts: absolute paths
