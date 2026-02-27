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

- Use when: time-based evidence is required (animation/async/transient UI) or user explicitly asks for video.
- `start_record` required input: `projectDir`.
- `stop_record` required input: `projectDir`, `sessionId`.
- Special recovery: if `start_record` returns `MCP_INVALID_PARAMS` with existing `sessionId`, call `stop_record` on old session, then retry.

## Final Artifact Staging

- Clear `${projectDir}/build/mcp_fetch/final` before copy.
- Keep stable names:
  - `final_screenshot.png`
  - `final_record.mp4`
