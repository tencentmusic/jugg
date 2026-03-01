# Recipe: UI Change Verify

## When to use

- UI layout/interaction changed and needs deterministic runtime proof.

## Minimal flow

1. Apply UI/code change.
2. Run `compile_and_deploy(projectDir)`.
3. Run `restart_app(projectDir)`.
4. Run `layout_dump(projectDir)` and locate target node (`resource-id` first).
5. Tap the target:
   - **Preferred**: use element mode directly — `tap(projectDir, resourceId="com.example:id/btn_target")` or `tap(projectDir, text="Submit")`. The tool automatically resolves bounds center.
   - **Fallback**: compute target center from bounds manually, then call `tap(projectDir, x, y)`.
6. If verification needs >=2 user actions or includes animation/transient state: `start_record` before first tap, `stop_record` after last tap.
7. Run `screenshot(projectDir)` for end-state proof.

## Recording decision

- Single static result: `layout_dump + screenshot`.
- Multi-step interaction/time-based behavior: default add recording.
  - `start_record` -> actions -> `stop_record` -> post-record screenshot.

## Edge-safe interaction checklist

- For moving/floating controls, refresh `layout_dump` before each critical tap.
- Validate action controls are fully visible in viewport (no clipping).
- Validate feedback after action (counter/text/state changed).
- If any check fails, verdict must be `FAIL` and return fix direction.

## Stop/Fallback

- If target node not found in `layout_dump`: stop and ask for expected page/state.
- If tap/screenshot both fail: verification fails.

## Evidence

- Required: layout evidence and/or screenshot artifact.
- Optional: video artifact when proving process (`how`) instead of end state (`what`).

## Summary template

- Verdict: `PASS|FAIL`
- Build path: `compile_and_deploy`
- Verification steps: node location method, bounds-center taps, feedback checks
- Artifacts: absolute paths (`layout_dump`, `final_screenshot`, optional `final_record`)
