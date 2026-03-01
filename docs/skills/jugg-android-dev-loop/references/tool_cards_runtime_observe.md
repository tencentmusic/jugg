# Tool Cards: Runtime & Observe

Use this file when executing runtime interaction or evidence collection (typically inside observe sub-agent, or main agent when delegation is not applicable).

## `restart_app`

- Purpose: launch/restart app process.
- Required input: `projectDir`.
- Use as default runtime entry before interaction.

## `tap`

- Purpose: deterministic UI action. Supports three modes:
  - **Coordinate mode** (`x` + `y`): tap exact pixel coordinates.
  - **Percent mode** (`xPercent` + `yPercent`, 0-100): auto-resolves screen size and taps proportional position.
  - **Element mode** (`text` / `resourceId` / `contentDesc`, optional `className`): finds UI element via uiautomator dump. All selectors use **exact match**. Taps center only when exactly one element matches; **multiple matches returns ERROR** with all candidates' bounds/center, guiding agent to use coordinate/percent mode.
- Required input: `projectDir` + at least one mode's parameters.
- Priority: coordinate > percent > element.
- Never use guessed coordinates; always derive via Coordinate Derivation Protocol below or use element mode.

### Coordinate Derivation Protocol (Mandatory for coordinate mode)

1. Run `layout_dump`.
2. Locate node by priority: `resource-id` -> `text` -> `content-desc`.
3. Parse `bounds` as `[x1,y1][x2,y2]`.
4. Tap center: `x=(x1+x2)/2`, `y=(y1+y2)/2`.
5. If target is moving/transient, refresh `layout_dump` right before tap.

### Element Mode (Automated Coordinate Derivation)

When using element mode, the tool automatically performs the Coordinate Derivation Protocol:
1. Dumps UI hierarchy via `uiautomator dump`.
2. Matches elements using **exact match** AND logic across provided selectors (`text`, `resourceId`, `contentDesc`, `className`).
3. If exactly 1 match: parses `bounds` and taps center point. Returns `matchedElement` description.
4. If multiple matches: returns `ERROR` with `data.matches` array containing each element's bounds, centerX, centerY. Agent should pick the correct element and retry with coordinate or percent mode.
5. If no match: returns `ERROR` with clickable candidates for debugging.

## `layout_dump`

- Purpose: UI hierarchy evidence and coordinate lookup.
- Required input: `projectDir`.
- Locate node by `resource-id` first, then `text`, then `bounds` center.

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

## Evidence Collection Order

Prefer lightweight first: `activity_stack` -> `layout_dump` -> `screenshot`. Add recording when action chain has >=2 user actions or involves animation/async/transient UI.

## Interaction Proof Profile

When task includes transient or multi-step interaction (pause menu, animation, drag, async state changes):

1. Start record before first action.
2. Resolve each tap target: prefer element mode (`tap(projectDir, resourceId=...)` or `tap(projectDir, text=...)`) which auto-resolves bounds. Fallback to Coordinate Derivation Protocol with latest `layout_dump` if element mode is not suitable.
3. Execute action chain.
4. Stop record and take final screenshot.
5. Run Interaction Robustness Gate checks below before claiming PASS.

## Interaction Robustness Gate

Before pass verdict, verify:

- Action controls are fully visible in viewport (not clipped at edges).
- Controls are not obscured by overlap (`z` order issue).
- Action feedback appears after tap (text/state/count change).

If any check fails, iterate code fix first; do not mark PASS.

## Final Artifact Staging

- Clear `${projectDir}/build/mcp_fetch/final` before copy.
- Keep stable names: `final_screenshot.png` / `final_record.mp4`.
