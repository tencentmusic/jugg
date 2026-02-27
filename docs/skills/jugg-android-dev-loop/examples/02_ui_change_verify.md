# Recipe: UI Change Verify

## When to use

- UI layout/interaction changed and needs deterministic runtime proof.

## Minimal flow

1. Apply UI/code change.
2. Run `compile_and_deploy(projectDir)`.
3. Run `restart_app(projectDir)`.
4. Run `layout_dump(projectDir)` and locate target node (`resource-id` first).
5. Compute target center from bounds, then call `tap(projectDir, x, y)`.
6. Run `screenshot(projectDir)` for end-state proof.

## Recording decision

- Default: `layout_dump + screenshot`.
- Use recording only for time-based behavior or explicit user request:
  - `start_record` -> actions -> `stop_record` -> post-record screenshot.

## Stop/Fallback

- If target node not found in `layout_dump`: stop and ask for expected page/state.
- If tap/screenshot both fail: verification fails.

## Evidence

- Required: layout evidence and/or screenshot artifact.
- Optional: video artifact when proving process (`how`) instead of end state (`what`).

## Summary template

- Verdict: `PASS|FAIL`
- Build path: `compile_and_deploy`
- Verification steps: located node, tap result, screenshot result
- Artifacts: absolute paths
