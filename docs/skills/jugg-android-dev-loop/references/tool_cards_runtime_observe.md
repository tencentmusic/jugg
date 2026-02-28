# Tool Cards: Runtime & Observe

Use this file when intent is runtime interaction or evidence collection.

## `restart_app`

- Purpose: launch/restart app process.
- Required input: `projectDir`.
- Use as default runtime entry before interaction.

## `tap`

- Purpose: deterministic UI action.
- Required input: `projectDir`, `x`, `y`.
- If coordinates are unknown, run `layout_dump` first.
- Never use guessed coordinates; derive from target node `bounds` center.

### Coordinate Derivation Protocol (Mandatory)

1. Run `layout_dump`.
2. Locate node by priority: `resource-id` -> `text` -> `content-desc`.
3. Parse `bounds` as `[x1,y1][x2,y2]`.
4. Tap center: `x=(x1+x2)/2`, `y=(y1+y2)/2`.
5. If target is moving/transient, refresh `layout_dump` right before tap.

## `layout_dump`

- Purpose: UI hierarchy evidence and coordinate lookup.
- Required input: `projectDir`.
- Practical note: locate node by `resource-id` first, then `text`, then `bounds` center.

## `activity_stack`

- Purpose: verify page/activity context before or after actions.
- Required input: `projectDir`.

## `screenshot`

- Purpose: final visual proof.
- Required input: `projectDir`.
- If screenshot fails, fallback to `layout_dump`; if both fail, verification fails.

## `start_record` / `stop_record`

- Use when: time-based evidence is required (animation/async/transient UI), or action chain has >=2 user actions, or user explicitly asks for video.
- `start_record` required input: `projectDir`.
- `stop_record` required input: `projectDir`, `sessionId`.
- Special recovery: if `start_record` returns `MCP_INVALID_PARAMS` with existing `sessionId`, call `stop_record` on old session, then retry.

### Interaction Robustness Gate (Floating/Edge UI)

Before pass verdict, verify:

- action controls are fully visible in viewport (not clipped at edges);
- controls are not obscured by overlap (`z` order issue);
- action feedback appears after tap (text/state/count change).

If any check fails, iterate code fix first; do not mark PASS.

## Final Artifact Staging

- Clear `${projectDir}/build/mcp_fetch/final` before copy.
- Keep stable names:
  - `final_screenshot.png`
  - `final_record.mp4`
