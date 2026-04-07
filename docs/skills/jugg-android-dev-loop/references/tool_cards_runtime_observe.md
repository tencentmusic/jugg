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

### `layout_dump`
- Purpose: dump full UI hierarchy from App-side ViewHierarchy server to a local JSON artifact.
- Input: `projectDir`; optional `rootLayout` (subtree node id), `isIncludeGone` (include GONE nodes), `isAllWindows` (all windows).
- Use when: need the raw view tree for manual inspection, custom analysis, or debugging.
- Output: `data.file` (local JSON path), `data.content` (inline JSON if ≤16KB), `artifacts[]`.

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

| Scenario | Tool | Priority |
|----------|------|----------|
| Confirm element position in layout | `view_locate` | 1st |
| Confirm displayed content details | `view_inspect` | 1st |
| `view_locate` cannot satisfy the need | `layout_dump` | Fallback |

## Verification Profiles

**Without Figma** (manual verification):
1. Target Page Gate → output `PageGate:` line
2. `view_locate` per element → calculate spacing from bounds
3. `view_inspect` for unsupported properties

**Spacing calc**: horizontal = `B.bounds[0] - A.bounds[2]`, vertical = `B.bounds[1] - A.bounds[3]`.
**Alignment check**: centerY = `(bounds[1]+bounds[3])/2`. Aligned if diff ≤ 2dp.
