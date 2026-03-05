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
  - **Element mode** (`text` / `resourceId` / `contentDesc`, optional `className`): uses app-side `find_and_tap` over ViewHierarchy server (no legacy `uiautomator dump` fallback). All selectors use **exact match**. Taps center only when exactly one element matches; **multiple matches returns ERROR** with all candidates' bounds/center, guiding agent to use coordinate/percent mode.
- Required input: `projectDir` + at least one mode's parameters.
- Parameter parse priority (only when multiple mode params are provided in one call): `coordinate > percent > element`.
- Recommended usage order: `layout_dump + element tap` -> `layout_dump + coordinate tap` -> fallback `screenshot + percent/coordinate tap` only when ViewHierarchy path is clearly unavailable.
- Never use guessed coordinates; always derive via Coordinate Derivation Protocol below or use element mode.

### Coordinate Derivation Protocol (Mandatory for coordinate mode)

1. Run `layout_dump` (use `rootLayout` to scope when container id is known).
2. Locate node by priority: `resource-id` -> `text` -> `content-desc`.
3. Parse bounds from JSON array: `bounds: [left, top, right, bottom]`.
4. Tap center: `x=(left+right)/2`, `y=(top+bottom)/2`.
5. If target is moving/transient, refresh `layout_dump` right before tap.

### Element Mode (Automated Coordinate Derivation)

When using element mode, the tool automatically performs the Coordinate Derivation Protocol:
1. Sends atomic `find_and_tap` request to app-side ViewHierarchy server.
2. Matches elements using **exact match** AND logic across provided selectors (`text`, `resourceId`, `contentDesc`, `className`).
3. Filters to actionable elements before matching (`VISIBLE + isShown + non-zero size + valid bounds`).
4. If exactly 1 match: taps center and returns `x`/`y` + `matchedElement`.
5. If multiple matches: returns `MCP_INVALID_PARAMS` with `data.matches` array (bounds/center), then retry with coordinate or percent mode.
6. If no match: returns `MCP_INTERNAL_ERROR` with clickable candidates in message for selector debugging.
7. If server unavailable: returns error directly (no `uiautomator` fallback).
8. If server unavailable is clearly caused by socket connect/forward failure: run one `force_gradle_compile` (with async polling), and retry once.

## `layout_dump`

- Purpose: UI hierarchy evidence and coordinate lookup.
- Required input: `projectDir`.
- Optional input: `rootLayout` (node `id` from a previous dump to scope to that subtree only).
- Optional input: `inlineMaxKb` controls max inline JSON size (`4..128`, default `16`).
- Output: `data.file` absolute `.json` path, `data.content` inline JSON data (no extra file read needed), and `artifacts` entry with `type=json`.
- Source: app-side ViewHierarchy server via `adb forward` + LocalSocket; no `uiautomator` fallback.
- Locate node by `resource-id` first, then `text`, then `bounds` center.
- When `rootLayout` is provided, only the matching subtree is returned (with `windowType: "subtree"`). Falls back to full dump if the id is not found.
- **Compressed output**: default/empty fields are omitted. `bounds` is `[left,top,right,bottom]` array. `className` strips common prefixes (`android.widget.`, `android.view.`, `androidx.`).

## Tool Description Migration (UI Observe Stage Only)

Keep MCP tool schema descriptions concise. Put guidance and strategy in this file instead of MCP schema text.

- `layout_dump` (compact): dump UI hierarchy to local JSON artifact, optional inline `data.content`, supports `rootLayout` / `isIncludeGone` / `inlineMaxKb`.
- `tap` (compact): perform `tap` / `longPress` / `swipe` with coordinate, percent, or element mode; mode priority is `coordinate > percent > element`.

### `inlineMaxKb` Tuning

- Main agent: prefer `inlineMaxKb=8`; use `4` when only artifact path is needed.
- Observe sub-agent: prefer `inlineMaxKb=32` for local analysis.
- Prefer `rootLayout` scoping before increasing `inlineMaxKb`.
- Do not exceed `64` unless user explicitly requests deep inline payload.

### Subtree Scoping Strategy (Mandatory for complex pages)

When interacting with a specific area (e.g., a dialog, a settings section, a list item detail):

1. First `layout_dump` (full) to identify the container `id` of the target area.
2. Subsequent `layout_dump(rootLayout="com.example:id/container")` to dump only that subtree.
3. This dramatically reduces payload size and improves coordinate lookup accuracy.

**When to use `rootLayout`:**
- Page has deep nesting or many sibling views (e.g., RecyclerView with 50+ items).
- You already know the container id from a previous dump.
- Iterating on the same UI area (e.g., tapping multiple items in a list).

**When NOT to use `rootLayout`:**
- First dump on an unknown page.
- Need to verify overall page structure (e.g., which Activity is shown).

## `activity_stack`

- Purpose: verify page/activity context before or after actions.
- Required input: `projectDir`.

## `screenshot`

- Purpose: final visual proof.
- Required input: `projectDir`.
- If screenshot fails, fallback to `layout_dump`; if both fail, verification fails.

### Image Scaling Warning

The returned screenshot image **may be scaled down** (long edge capped to 1440px, JPEG compressed) to reduce upload size. When scaling occurs, `message` reports the original and output dimensions with scale ratio, e.g. `scaled from 2960x1440 to 1440x702, ratio=0.49`.

**Critical**: pixel coordinates in the scaled image do **NOT** correspond to device screen coordinates. Never calculate `tap(x, y)` positions by measuring pixels in a screenshot.

Correct alternatives:
- **Preferred**: `layout_dump` + element mode `tap` (by `resourceId`/`text`/`contentDesc`).
- **Fallback**: `layout_dump` + coordinate mode `tap` (derive from `bounds` in layout JSON).
- **Last resort**: percent mode `tap` (`xPercent`/`yPercent`) which auto-resolves screen size.

### Percent Mode After Screenshot (Last Resort Only)

When ViewHierarchy is unavailable and percent mode tap must be used:

- **Correct**: visually estimate where the target element sits as a fraction of the image dimensions.
  - Example: button appears roughly 30% from the left edge and 60% from the top → `xPercent=30, yPercent=60`.
- **Wrong**: dividing image pixel coordinates by device screen resolution. The image may be scaled (non-integer ratio), so pixel math produces incorrect results.

Estimation approach:
1. Look at the screenshot image as a whole rectangle.
2. Estimate the horizontal distance from the left edge to the element center, divided by total image width → `xPercent`.
3. Estimate the vertical distance from the top edge to the element center, divided by total image height → `yPercent`.
4. These ratios are device-resolution-independent and map correctly regardless of scaling.

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
