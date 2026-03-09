# Guide: layout_verify Assertion Reference & Full-Page Check SOP

> Schema source: `tool_cards_runtime_observe.md §layout_verify`.
> For properties not covered here, use `eval_view` (§4).

---

## 1. Capability Quick-Reference

### 1.1 Property Checks (`type: property`)

| `property` | Value type | Supported `op` | Constraints |
|------------|-----------|----------------|-------------|
| `text` | string | `eq`, `neq`, `contains`, `matches` | `matches` = Java regex |
| `exists` | — | — | No `op`/`value` needed |
| `visibility` | `"visible"` / `"invisible"` / `"gone"` | `eq`, `neq` | |
| `bounds.width` | number (dp) | `eq`, `neq`, `gt`, `lt`, `gte`, `lte` | Range: use `gte` + `lte` |
| `bounds.height` | number (dp) | same as above | same |
| `textColor` | `"#AARRGGBB"` | `eq`, `neq` | Must include alpha; black may be omitted in dump → use live query |
| `backgroundColor` | `"#AARRGGBB"` | `eq`, `neq` | Live-only; solid color only |
| `textSizeSp` | number (sp) | `eq`, `neq`, `gt`, `lt`, `gte`, `lte` | Live-only, auto-switches |
| `clickable` | boolean | `eq` | |
| `enabled` | boolean | `eq` | |
| `alpha` | float | `eq`, `neq`, `gt`, `lt`, `gte`, `lte` | epsilon = 0.001 |
| `padding.left` / `.top` / `.right` / `.bottom` | number (dp) | `eq`, `neq`, `gt`, `lt`, `gte`, `lte` | |

**All numeric values are dp** (auto px→dp conversion).

**No `tolerance` field** — use two checks (`gte` + `lte`) for approximate matching.

### 1.2 Relation Checks

| `type` | Required fields | `axis` | Description |
|--------|----------------|--------|-------------|
| `spacing` | `target2`, `expected`, `op` | `x` / `y` | Gap between two elements. Range: two checks (`gte` + `lte`) |
| `alignment` | `target2` | `x` / `y` | `x` = horizontal center aligned; `y` = vertical center aligned |
| `order` | `target2` | `x` / `y` | `y` = target above target2; `x` = target left of target2 |
| `containment` | `target2` | — | target (child) fully inside target2 (parent) |
| `overlap` | `target2` | — | Default PASS = no overlap; set `expectOverlap: true` to invert |

**`axis` semantics**: `axis:"y"` = vertical dimension; `axis:"x"` = horizontal dimension. (`direction` is deprecated.)

### 1.3 Target Selector

```
target / target2: { resourceId?, text?, contentDesc?, className? }
```

- AND logic across all provided fields.
- Multi-match silently picks first. **Always prefer `resourceId`**; add `className` to narrow.

### 1.4 Not Supported by layout_verify

| Property | Workaround |
|----------|-----------|
| `maxLines` | `eval_view`: `getMaxLines()` |
| `ellipsize` | `eval_view`: `getEllipsize().name()` |
| `letterSpacing` | `eval_view`: `getLetterSpacing()` |
| `lineCount` | `eval_view`: `getLineCount()` |
| `cornerRadius` | `eval_view`: `getBackground().getCornerRadius()` |
| `tint color` | `eval_view`: `getBackgroundTintList().getDefaultColor()` |
| Complex background (gradient, image) | `screenshot` visual comparison |
| Any custom View getter | `eval_view`: `getXxx()` |

---

## 2. Pitfall Cheat-Sheet

| # | Rule |
|---|------|
| 1 | No `tolerance` field — use `gte` + `lte` pair |
| 2 | All numeric values are dp; convert from px: `dp = px / density` |
| 3 | Use `axis` not `direction` (deprecated) |
| 4 | `textColor` must be `#AARRGGBB` — e.g. `#1976D2` → `"#FF1976D2"` |
| 5 | Black `textColor` (`#FF000000`) may be omitted in dump — use live query |
| 6 | `textSizeSp` is live-only (auto-switches) |
| 7 | Mix property + relation checks in one `checks[]` |
| 8 | Multi-match picks first silently — prefer `resourceId` |

---

## 3. Full-Page Verification SOP

Use this checklist to systematically derive all `layout_verify` + `eval_view` checks for a page. Walk through each layer in order to guarantee no omission.

### Step 1: Inventory — List All Verifiable Elements

From the design spec / layout dump, enumerate every meaningful element on the page. For each element, record:
- Selector (`resourceId` preferred)
- Element role (title, button, icon, input, container, …)

### Step 2: Per-Element Property Sweep

For **each element**, check every applicable property in this fixed order:

| # | Check | Applies to | `layout_verify` field |
|---|-------|-----------|----------------------|
| 1 | **Exists** | All | `exists` |
| 2 | **Visibility** | All | `visibility` |
| 3 | **Text content** | TextView, Button, EditText | `text` |
| 4 | **Text color** | TextView, Button | `textColor` (#AARRGGBB) |
| 5 | **Text size** | TextView, Button | `textSizeSp` (live-only) |
| 6 | **Background color** | All (solid only) | `backgroundColor` (live-only) |
| 7 | **Bounds (size)** | Size-critical elements | `bounds.width` / `bounds.height` |
| 8 | **Alpha** | Fading / semi-transparent | `alpha` |
| 9 | **Clickable / Enabled** | Buttons, interactive | `clickable`, `enabled` |
| 10 | **Padding** | Containers, items | `padding.left/top/right/bottom` |

Skip properties that the design spec does not constrain. For properties not in the table above, fall back to `eval_view` (§4).

### Step 3: Inter-Element Relation Sweep

For **each pair of related elements**, check all applicable relations:

| # | Check | When to apply | `layout_verify` type |
|---|-------|--------------|---------------------|
| 1 | **Spacing** | Any specified gap between elements | `spacing` (axis + expected + op) |
| 2 | **Alignment** | Elements meant to be center-aligned | `alignment` (axis) |
| 3 | **Order** | Positional sequence matters (A above B, A left of B) | `order` (axis) |
| 4 | **Containment** | Child must be within parent bounds | `containment` |
| 5 | **Overlap** | Badge on avatar, or no-overlap constraint | `overlap` (± `expectOverlap`) |

### Step 4: Organize into layout_verify Calls

- **Group by target**: batch all checks for the same target element in one `checks[]` array.
- Property and relation checks can be mixed in a single call.
- For relation checks, `target` is always the primary / first element; `target2` is the reference.

### Step 5: Supplement with eval_view

Scan design spec for any property not in §1.1 / §1.2. Common ones:
- `maxLines`, `ellipsize`, `letterSpacing`, `lineCount` → `eval_view`
- `cornerRadius`, `tint color` → `eval_view`
- Complex background → `screenshot` visual comparison

### Step 6: Execute & Collect Evidence

Follow the Fast UI Verify Profile from `tool_cards_runtime_observe.md`:
1. **Target Page Context Gate** — confirm correct page.
2. **layout_verify** calls — run all checks.
3. **eval_view** calls — run supplementary checks.
4. **screenshot** — one final visual proof after all checks pass.

---

## 4. eval_view Quick Reference

- **Purpose**: reflective getter call on a single View; returns raw value (no PASS/FAIL).
- **Selector**: same as `layout_verify` target; must match exactly one element.
- **Expressions**: `methodName()` or `methodName().anotherMethod()`, max chain depth 5.
- **Safety**: getter-only whitelist (`get*`/`is*`/`has*`/`can*`/`should*` + `toString`/`length`/`name`/`ordinal`/`size`/`isEmpty`).
- **Result**: `data.values[]` with `expression`/`value`/`type`; also `data.density` for px→dp.

Use `eval_view` when `layout_verify` does not cover the property. Agent must interpret raw values.
