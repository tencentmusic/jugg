# Guide: layout_verify Assertion Reference & Full-Page Check SOP

> Schema source: `tool_cards_runtime_observe.md §layout_verify`.
> For properties not covered here, use `eval_view` (§5).

---

## Core Principle

> **Screenshot spots suspicion; layout_dump reveals truth; code defines expectation. All three are indispensable.**

- **Screenshot (visual layer)**: captures overall appearance — only identifies *suspicious areas* (misalignment, clipping, color mismatch).
- **layout_dump / layout_verify (data layer)**: provides runtime numerical ground truth (`width / height / x / y / padding / spacing`).
- **Code (expectation layer)**: derive the *expected value* from source code and write explicit formulas, e.g.:
  - Grid column: `colWidth × colCount + gap × (colCount - 1) + paddingH × 2 = parentWidth`
  - Banner: `bannerWidth + marginStart + marginEnd = parentWidth`
- **Verdict**: compare data-layer actuals against code-layer expectations item by item; any mismatch = bug.

### Sub-Agent Delegation Policy

When runtime supports MCP-capable sub-agents (see `SKILL.md §Observe Delegation Policy`), the verify→fix cycle **must** be split into two isolated sub-agents:

| Role | Responsibility | Context contains | Context excludes |
|------|---------------|-----------------|-----------------|
| **Sub-agent A (Verify)** | Steps 0–7 of §4 (or §6→§7): screenshot, layout_verify, eval_view, produce verification report | This guide, tool cards, design spec, layout/screenshot data | Source code edits, fix patches |
| **Sub-agent B (Fix)** | Receive FAIL items from A's report → modify source code → trigger build/deploy | Source code, build tools, A's report (summary only) | Raw layout JSON, screenshot images, full verification context |

**Workflow**:
1. Main agent spawns **A** with verification task → A returns `{report, failedItems, artifacts}`.
2. If FAIL items exist, main agent spawns **B** with `failedItems` + file hints → B applies fixes and deploys.
3. After B completes, main agent spawns a **new A** instance for re-verification (complete, not partial).
4. Repeat until PASS or retry budget exhausted.

**Rules**:
- A and B must **never share conversation context**. Main agent bridges them via structured summaries only.
- If sub-agents are unavailable, fall back to main-agent execution but summarize heavy outputs (layout JSON, images) instead of copying raw payloads.

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

### 1.3.1 Selector Fallback Chain (mandatory)

When a `layout_verify` or `eval_view` call fails to match any element, **do not skip the check**. Walk through the following fallback chain in order until one succeeds:

| Priority | Selector Strategy | Example |
|----------|------------------|---------|
| 1 (best) | `resourceId` | `{resourceId: "tv_title"}` |
| 2 | `resourceId` + `className` | `{resourceId: "tv_title", className: "android.widget.TextView"}` |
| 3 | `text` + `className` | `{text: "Song Title", className: "android.widget.TextView"}` |
| 4 | `contentDesc` + `className` | `{contentDesc: "cover image", className: "android.widget.ImageView"}` |
| 5 (last resort) | **`layout_dump` manual extraction** | Dump JSON → locate node by visual position → read `bounds` in px → convert to dp via `dp = px / density` |

**Rules**: exhaust each level before falling to the next. Level 5 is always available as final fallback. **Absolute prohibition**: skipping a check or marking INCONCLUSIVE after a single selector failure.

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

## 2. Mandatory Checklist by UI Component Type

Before diving into per-element sweeps, use this checklist to ensure **no common pitfall is missed** based on the type of UI component on screen.

| Component Type | Mandatory Check Points |
|----------------|----------------------|
| **Grid / List layout** | Column-width equation holds; no unexpected trailing whitespace on the right edge |
| **Image / Icon** | `scaleType` matches image nature (transparent asset → `fitCenter`; cover photo → `centerCrop`) |
| **Banner / Card** | No unexpected margin/padding causing offset from design spec edges |
| **Spacing** | Accumulated `marginTop` + `marginBottom` between adjacent items matches design spec gap |
| **Corner radius** | `cornerRadius` matches container size (avoid oval or ineffective rounding); verify via `eval_view` |
| **Scrollable container** | Content area fully fills viewport width; no horizontal over-scroll clipping |

> These checks supplement — not replace — the per-element property sweep in §4.

---

## 3. Pitfall Cheat-Sheet

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
| 9 | **Failure-no-skip**: any `layout_verify` / `eval_view` call that fails (selector mismatch, timeout, error) must be retried via the fallback chain (§1.3.1). Marking a check INCONCLUSIVE or silently dropping it is prohibited unless all 5 fallback levels are exhausted. |
| 10 | **Re-verify-after-fix**: after modifying code to fix FAIL items, re-execute the **complete** verification (Step 0 → Step 7, or §6 full conversion + §7 report). Partial re-checks are not acceptable. |

---

## 4. Full-Page Verification SOP

Use this checklist to systematically derive all `layout_verify` + `eval_view` checks for a page. Walk through each layer in order to guarantee no omission.

### Step 0: Screenshot — Identify Suspicious Areas

Take a `screenshot` of the target page **before** any programmatic check. Compare it against the design spec (mentally or side-by-side) and produce a **suspicious-area table** (mandatory output — do not skip or use free-form text):

| Suspicious Area | Reason | Properties to Verify |
|----------------|--------|---------------------|
| e.g. Tab capsule | Height appears larger than spec | `bounds.height` |
| e.g. Banner | Missing left/right margin | `bounds.x`, `bounds.width`, spacing to parent |
| e.g. Song title | Color looks lighter | `textColor` |

This table drives all subsequent steps — each row becomes a verification target tracked through to the final report.

### Step 1: Inventory — List All Verifiable Elements

From the design spec / layout dump, enumerate every meaningful element on the page. For each element, record:
- Selector (`resourceId` preferred)
- Element role (title, button, icon, input, container, …)

### Step 1.5: Code-Derive Expected Values

For size-critical and layout-critical elements, **read the source code** to derive expected numerical values. Write explicit formulas:
- e.g. `itemWidth = (parentWidth - paddingStart - paddingEnd - gap * (spanCount - 1)) / spanCount`
- e.g. `bannerHeight = bannerWidth / aspectRatio`

These code-derived values become the `expected` / `value` in subsequent `layout_verify` checks. **Never guess an expected value — always trace it to code or design spec.**

### Step 2: Per-Element Property Sweep

For each element, walk §1.1 properties in table order: `exists → visibility → text → textColor → textSizeSp → backgroundColor → bounds → alpha → clickable/enabled → padding`. Skip properties unconstrained by design spec. For unsupported properties, fall back to `eval_view` (§5).

### Step 3: Inter-Element Relation Sweep

For each pair of related elements, walk §1.2 relation types: `spacing → alignment → order → containment → overlap`.

### Step 4: Organize into layout_verify Calls

- **Group by target**: batch all checks for the same target element in one `checks[]` array.
- Property and relation checks can be mixed in a single call.
- For relation checks, `target` is always the primary / first element; `target2` is the reference.

### Step 5: Supplement with eval_view

Scan design spec for any property not in §1.1 / §1.2. Common ones:
- `maxLines`, `ellipsize`, `letterSpacing`, `lineCount` → `eval_view`
- `cornerRadius`, `tint color` → `eval_view`
- Complex background → `screenshot` visual comparison

### Step 6: Cross-Check Against Component Checklist

Walk through §2 (Mandatory Checklist by UI Component Type). For each component type present on the page, verify that every mandatory check point has been covered by the checks above. Add any missing checks.

### Step 7: Execute & Collect Evidence

Follow the Fast UI Verify Profile from `tool_cards_runtime_observe.md`:
1. **Target Page Context Gate** — confirm correct page.
2. **layout_verify** calls — run all checks.
3. **eval_view** calls — run supplementary checks.
4. **Data vs. Code comparison** — for each size/spacing assertion, explicitly state `actual (data layer) vs. expected (code layer)` and verdict (PASS/FAIL).
5. **screenshot** — one final visual proof after all checks pass.

---

## 5. eval_view Quick Reference

- **Purpose**: reflective getter call on a single View; returns raw value (no PASS/FAIL).
- **Selector**: same as `layout_verify` target; must match exactly one element.
- **Expressions**: `methodName()` or `methodName().anotherMethod()`, max chain depth 5.
- **Safety**: getter-only whitelist (`get*`/`is*`/`has*`/`can*`/`should*` + `toString`/`length`/`name`/`ordinal`/`size`/`isEmpty`).
- **Result**: `data.values[]` with `expression`/`value`/`type`; also `data.density` for px→dp.

Use `eval_view` when `layout_verify` does not cover the property. Agent must interpret raw values.

---

## 6. Design-Spec-to-Assertion Conversion SOP

When structured design data is available (Figma JSON via MCP, design tokens, annotated spec), convert it into executable assertions **before** any runtime verification. When no structured data exists, fall back to §4.

### 6.1 Conversion Steps

1. **Extract elements**: from design data, record each element's `name/role`, `text`, `fontSize`→`textSizeSp`, `color`→`textColor`/`backgroundColor` (#AARRGGBB), `width/height`→`bounds.*`, `spacing`, `padding`, `cornerRadius`→`eval_view`, `opacity`→`alpha`. Apply dpr conversion where needed.
2. **Map to selectors**: match each element to an Android `resourceId` via code or `layout_dump`. Use §1.3.1 fallback chain if no `resourceId`.
3. **Generate assertions**: for each element, produce `layout_verify` checks per §4 Step 2 (properties) and Step 3 (relations). For unsupported properties (§1.4), generate `eval_view` expressions.
4. **Review before execution**: confirm every design element has ≥1 assertion, expected values match spec (with dpr), and unsupported properties have `eval_view` fallback.

---

## 7. Verification Report

After executing all assertions (§6 or §4), and **before** making any code changes, produce a structured verification report.

### 7.1 Report Structure

```
# UI Verification Report
**Page** / **Design Source** / **Device** / **Timestamp**

## Summary — table: Total checks | ✅ PASS | ❌ FAIL | ⚠️ INCONCLUSIVE

## Detail — per element:
| # | Check Type | Property/Relation | Expected | Actual | Verdict |

## Failed Items (Action Required)
| # | Element | Check | Expected | Actual | Suggested Fix |

## Evidence — final screenshot path
```

### 7.2 Report Rules

1. **Mandatory before code changes**: report must be presented before any source modification.
2. **Every assertion must appear**: no check may be silently omitted.
3. **Actual values from tool output**: "Actual" column from `layout_verify` / `eval_view` output, not visual estimation.
4. **Failed Items**: must list actionable fix suggestions with file/location hints.
5. **Incremental**: each fix→re-verify iteration produces a new report; previous reports kept for diff.
