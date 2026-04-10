# Tool Cards: Runtime & Observe

Use when executing runtime interaction or UI verification. `screenshot` / `record-start` / `record-stop` are OUT OF SCOPE — do NOT call them when this Skill is active.

## Target Page Gate

Run before any UI verification. Required inputs: navigation sequence, target activity name.

**Steps**:
1. `activity-stack` → read current activity
2. Output mandatory gate line: `PageGate: stack=<ActivityName> target=<targetPage> match=<yes|no>`
3. On `match=no`: `restart [--tap "tap:text=<step>" ...]` with `navigationSeq` → repeat from step 1
4. On `match=yes`: proceed to verification

**Missing `PageGate:` line = gate not executed; do NOT proceed.**

## Command Cards

### `activity-stack`
- Purpose: verify page/activity context.
- No arguments required.
- Use as mandatory gate before any UI verification.

### `restart [--tap <step>...]`
- Purpose: launch/restart app.
- Optional `--tap` steps run sequentially after app-ready; each step format: `<action>:<selector>`.
- `tap_actions`: retries up to 2 times per step; any failure aborts with error message.
- Step examples: `tap:text=登录`, `tap:id=btn-confirm`, `swipe:50%,80%,50%,20%`

### `tap [OPTIONS]`
Three modes (priority: coordinate > percent > element):
- **Coordinate** (`--x <px> --y <px>`): exact pixels
- **Percent** (`--xp <0-100> --yp <0-100>`): proportional position
- **Element** (`--text <text>` / `--id <resourceId>` / `--desc <contentDesc>`, optional `--class <className>`): exact match, taps center if 1 match; multiple matches returns ERROR with candidates

**Usage order**: element → coordinate → percent (last resort).

**Coordinate Derivation** (mandatory for coordinate mode):
1. `view-locate --text <text>` to get element bounds
2. Tap center: `x=(left+right)/2`, `y=(top+bottom)/2`

Actions: `--action tap` (default) / `--action long-press` / `--action swipe`
Swipe requires `--end-x/--end-y` (coordinate) or `--end-xp/--end-yp` (percent).

### `layout-dump [--root <id>] [--include-gone] [--all-windows]`
- Purpose: dump full UI hierarchy from App-side ViewHierarchy server to a local HTML artifact.
- Use when: need the raw view tree for manual inspection, custom analysis, or debugging.
- Output: `file: <local HTML path>`, node count summary in `message`.

### `view-locate [--text <text>] [--id <resourceId>] [--desc <contentDesc>]`
- Purpose: find a single UI element and return its position and size.
- At least one selector required.
- Use when: need a specific View's bounds for spacing calculation or position verification.
- Output: `found`, `bounds` (`[left,top,right,bottom]`), `position` (`{x,y}`), `size` (`{width,height}`). All values in dp.

### `view-inspect [--text <text>] [--id <resourceId>] [--desc <contentDesc>] [--class <className>] <expr>...`
- Purpose: query all properties of a single View via reflective getter calls.
- At least one selector required (AND logic); one or more expressions as positional args.
- Use when: checking maxLines, ellipsize, cornerRadius, tintColor, or any custom getter.
- Safety: getter-only whitelist (get*/is*/has*/can*/should* + toString/length/name/ordinal/size/isEmpty).
- Output: expression/value/type pairs + density for px→dp conversion.

## Tool Selection by Scenario

| Scenario | Command | Priority |
|----------|---------|----------|
| Confirm element position in layout | `view-locate` | 1st |
| Confirm displayed content details | `view-inspect` | 1st |
| `view-locate` cannot satisfy the need | `layout-dump` | Fallback |

## Verification Profiles

**Without Figma** (manual verification):
1. Target Page Gate → output `PageGate:` line
2. `view-locate --text <element>` per element → calculate spacing from bounds
3. `view-inspect` for unsupported properties

**Spacing calc**: horizontal = `B.bounds[0] - A.bounds[2]`, vertical = `B.bounds[1] - A.bounds[3]`.
**Alignment check**: centerY = `(bounds[1]+bounds[3])/2`. Aligned if diff ≤ 2dp.
