# Tool Cards: Runtime & Observe

Use when executing runtime interaction or evidence collection.

## Target Page Context Gate

Run before final screenshot/recording. Required inputs: navigation sequence, page anchor (resourceId preferred), optional activity name. If missing, ask user.

**Steps**:
1. `restart_app` (use `tap_actions` for restart + navigation in one call)
2. Verify context: `activity_stack` matches activity OR `layout_dump` contains anchor
3. On fail: retry within budget, then ask user for corrected navigation/anchor
4. Only after pass: continue to screenshot/recording and checks

**No-early-evidence**: no final screenshot/recording before gate passes (one diagnostic screenshot allowed for navigation failure).

## Tool Cards

### `restart_app`
- Purpose: launch/restart app.
- Input: `projectDir`, optional `tap_actions` (element selectors: text/resourceId/contentDesc, optional className).
- `tap_actions`: runs sequentially after app-ready; retries up to 2 times per step; any failure aborts with `data.failedStep`.

### `tap`
Three modes (priority: coordinate > percent > element):
- **Coordinate** (`x`+`y`): exact pixels
- **Percent** (`xPercent`+`yPercent`, 0-100): proportional position
- **Element** (text/resourceId/contentDesc, optional className): exact match, taps center if 1 match; multiple matches returns ERROR with candidates

**Usage order**: `layout_dump + element` → `layout_dump + coordinate` → `screenshot + percent` (last resort).

**Coordinate Derivation** (mandatory for coordinate mode):
1. `layout_dump` (use `rootLayout` to scope)
2. Locate by priority: resource-id → text → content-desc
3. Parse `bounds: [left,top,right,bottom]`
4. Tap center: `x=(left+right)/2`, `y=(top+bottom)/2`

**Element mode auto-derivation**: sends `find_and_tap` to ViewHierarchy server; exact match AND logic; filters actionable elements (VISIBLE + isShown + non-zero size); if server unavailable due to socket failure, run `force_gradle_compile` once and retry.

### `layout_dump`
- Purpose: UI hierarchy + coordinate lookup.
- Input: `projectDir`, optional `rootLayout` (subtree scope).
- Output: `data.file` + `data.content` (inline JSON) + `artifacts`.
- Source: ViewHierarchy server (no uiautomator fallback).
- Compressed: omits defaults; `bounds` is array; `className` strips prefixes.

**Subtree scoping**: for complex pages (deep nesting, RecyclerView with 50+ items), first full dump to get container id, then `rootLayout="id"` for subsequent dumps.

### `layout_verify`
- Purpose: assert properties/relations without JSON parsing.
- Input: `projectDir`, `target`, `checks[]`.
- Multi-match picks first; prefer resourceId.
- Details: see `guide_layout_verify_assertion.md`.
- Key points: textColor may omit black; textSizeSp/backgroundColor are live-only; maxLines/ellipsize use view_inspect.
- Result: `data.result` (PASS/PARTIAL_FAIL/FAIL/ERROR), `data.checkResults[]`, `data.candidates[]` if not found.

### `view_inspect`
- Purpose: reflective getter calls for properties layout_verify cannot query.
- Input: `projectDir`, `target` (must match exactly one), `expressions[]` (1-20, max chain depth 5).
- Safety: getter-only whitelist (get*/is*/has*/can*/should* + toString/length/name/ordinal/size/isEmpty).
- Result: `data.values[]` (expression/value/type), `data.density` for px→dp.
- Use when: maxLines, ellipsize, cornerRadius, custom getters.

### `activity_stack`
- Purpose: verify page/activity context.
- Input: `projectDir`.

### `screenshot`
- Purpose: final visual proof.
- Input: `projectDir`.
- Use after gate passes.
- **Warning**: may be scaled (≤1440px); never derive tap coords from pixels. Use layout_dump + element/coordinate tap, or percent tap as last resort.

### `start_record` / `stop_record`
- Use when: time-based evidence (animation/async/transient UI), action chain ≥2 steps, or user requests video.
- Input: `projectDir`; stop_record needs `sessionId`.
- Recovery: if start_record returns existing sessionId, stop old session first.

## Profiles

**Fast UI Verify** (static single-page checks):
1. Target Page Context Gate
2. `layout_verify` for each check (auto snapshot, all values in dp)
3. Final `screenshot` after all pass
4. Skip recording unless temporal evidence needed

**Interaction Proof** (transient/multi-step):
1. Start record before first action
2. Resolve tap targets (prefer element mode, fallback to coordinate derivation)
3. Execute action chain
4. Stop record + final screenshot
5. Robustness gate: controls visible (not clipped), not obscured, feedback appears after tap

## Evidence Order
Prefer lightweight first: `activity_stack` → `layout_dump` → `screenshot`. Add recording when ≥2 actions or animation/async/transient UI.

## Final Artifact Staging
Clear `${projectDir}/build/mcp_fetch/final`, copy as `final_screenshot.png` / `final_record.mp4`.
