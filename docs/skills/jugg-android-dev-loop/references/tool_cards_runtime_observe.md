# Tool Cards: Runtime & Observe

Use when executing runtime interaction or UI verification. screenshot / start_record / stop_record are OUT OF SCOPE — do NOT call them when this Skill is active.

## Target Page Gate

Run before any UI verification. Required inputs: navigation sequence, target activity name.

**Steps**:
1. `activity_stack` → read current activity
2. Output mandatory gate line: `PageGate: stack=<ActivityName> target=<targetPage> match=<yes|no>`
3. On `match=no`: `restart_app(projectDir, tap_actions=navigationSeq)` → repeat from step 1
4. On `match=yes`: proceed to verification

**Missing `PageGate:` line = gate not executed; do NOT proceed.**

## Tool Cards

### `activity_stack`
- Purpose: verify page/activity context.
- Input: `projectDir`.
- Use as mandatory gate before any UI verification.

### `restart_app`
- Purpose: launch/restart app.
- Input: `projectDir`, optional `tap_actions` (element selectors: text/resourceId/contentDesc, optional className).
- `tap_actions`: runs sequentially after app-ready; retries up to 2 times per step; any failure aborts with `data.failedStep`.

### `tap`
Three modes (priority: coordinate > percent > element):
- **Coordinate** (`x`+`y`): exact pixels
- **Percent** (`xPercent`+`yPercent`, 0-100): proportional position
- **Element** (text/resourceId/contentDesc, optional className): exact match, taps center if 1 match; multiple matches returns ERROR with candidates

**Usage order**: element → coordinate → percent (last resort).

**Coordinate Derivation** (mandatory for coordinate mode):
1. `view_locate` to get element bounds
2. Tap center: `x=(left+right)/2`, `y=(top+bottom)/2`

### `figma_layout_verify`
- Purpose: batch compare entire screen against a Figma design file.
- Input: `projectDir`, `figmaJsonPath`, optional `dpr`.
- Use when: comparing overall layout/spacing/alignment against design spec.
- Output: `data.results[]` with type/match/expected/actual/diff per relation.

### `view_locate`
- Purpose: find a single UI element and return its position and size.
- Input: `projectDir`, `target` (text/resourceId/contentDesc, at least one required).
- Use when: need a specific View's bounds for spacing calculation or position verification.
- Output: `data.found`, `bounds([left,top,right,bottom])`, `position({x,y})`, `size({width,height})`. All values in dp.

### `view_inspect`
- Purpose: query all properties of a single View via reflective getter calls.
- Input: `projectDir`, `target` (must match exactly one), `expressions[]` (1-20, max chain depth 5).
- Use when: checking maxLines, ellipsize, cornerRadius, tintColor, or any custom getter.
- Safety: getter-only whitelist (get*/is*/has*/can*/should* + toString/length/name/ordinal/size/isEmpty).
- Output: `data.values[]` (expression/value/type), `data.density` for px→dp.

## Tool Selection by Scenario

| Scenario | Tool |
|----------|------|
| Compare entire screen against design | `figma_layout_verify` |
| Locate a specific View's position/bounds | `view_locate` |
| Inspect all properties of a specific View | `view_inspect` |

## Verification Profiles

**With Figma Design** (batch verification):
1. Target Page Gate → output `PageGate:` line
2. `figma_layout_verify(figmaJsonPath, dpr)` → analyze results
3. For any failed item: `view_locate` or `view_inspect` for detail
4. Fix → re-verify

**Without Figma** (manual verification):
1. Target Page Gate → output `PageGate:` line
2. `view_locate` per element → calculate spacing from bounds
3. `view_inspect` for unsupported properties

**Spacing calc**: horizontal = `B.bounds[0] - A.bounds[2]`, vertical = `B.bounds[1] - A.bounds[3]`.
**Alignment check**: centerY = `(bounds[1]+bounds[3])/2`. Aligned if diff ≤ 2dp.
