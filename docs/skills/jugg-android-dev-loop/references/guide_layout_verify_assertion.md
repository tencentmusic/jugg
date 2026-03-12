# Guide: layout_verify Assertion Reference

> Schema: `tool_cards_runtime_observe.md §layout_verify`. Unsupported properties: use `eval_view` (§5).

## ⚠️ Execution Mode

**Sub-Agent Mode** (recommended if available):
- Spawn sub-agent to execute this document's workflow
- Output structured Report and exit

**Main Agent Mode** (fallback):
- Must strictly follow Step 0~7
- Screenshot inspection MUST NOT replace tool verification

## Core Principle

**Screenshot spots suspicion → layout_verify reveals truth → code defines expectation.**

- **Screenshot**: identifies suspicious areas (misalignment, clipping, color mismatch).
- **layout_verify**: provides runtime ground truth (width/height/x/y/padding/spacing in dp).
- **Code**: derive expected values via formulas (e.g., `colWidth × colCount + gap × (colCount-1) + paddingH×2 = parentWidth`).
- **Verdict**: compare actual vs. expected item by item; any mismatch = bug.

**Sub-Agent Delegation**: when available, split verify (A) and fix (B) into isolated sub-agents. A executes §4 or §6→§7, returns `{report, failedItems, artifacts}`. B receives failedItems, applies fixes, deploys. After B completes, spawn new A for complete re-verification. A and B never share context; main agent bridges via summaries only.

## 1. Capability Quick-Reference

**All numeric values are dp** (auto px→dp). No `tolerance` field — use `gte`+`lte` for ranges.

### 1.1 Property Checks

| property | type | ops | notes |
|----------|------|-----|-------|
| `text` | string | eq/neq/contains/matches | matches=regex |
| `exists` | — | — | no op/value |
| `visibility` | enum | eq/neq | visible/invisible/gone |
| `bounds.width/height` | dp | eq/neq/gt/lt/gte/lte | |
| `textColor` | #AARRGGBB | eq/neq | black may be omitted→live query |
| `backgroundColor` | #AARRGGBB | eq/neq | live-only, solid only |
| `textSizeSp` | sp | eq/neq/gt/lt/gte/lte | live-only |
| `clickable/enabled` | bool | eq | |
| `alpha` | float | eq/neq/gt/lt/gte/lte | epsilon=0.001 |
| `padding.*` | dp | eq/neq/gt/lt/gte/lte | |

### 1.2 Relation Checks

| type | fields | axis | description |
|------|--------|------|-------------|
| `spacing` | target2, expected, op | x/y | gap between elements |
| `alignment` | target2 | x/y | center aligned (x=horizontal, y=vertical) |
| `order` | target2 | x/y | relative position (y=above, x=left of) |
| `containment` | target2 | — | child inside parent |
| `overlap` | target2 | — | default=no overlap; expectOverlap:true to invert |

### 1.3 Selector & Fallback Chain (mandatory)

`target/target2: {resourceId?, text?, contentDesc?, className?}` (AND logic, multi-match picks first)

**Fallback order** (exhaust each before next):
1. `resourceId`
2. `resourceId` + `className`
3. `text` + `className`
4. `contentDesc` + `className`
5. `layout_dump` manual (px→dp via density)

**Prohibition**: skipping check after single selector failure.

### 1.4 Unsupported Properties

Use `eval_view`: maxLines, ellipsize, letterSpacing, lineCount, cornerRadius, tint color, custom getters. Complex backgrounds: `screenshot` visual comparison.

## 2. Component Type Checklist

| Component | Mandatory Checks |
|-----------|-----------------|
| Grid/List | Column-width equation; no trailing whitespace |
| Image/Icon | scaleType matches nature (transparent→fitCenter; cover→centerCrop) |
| Banner/Card | No unexpected margin/padding offset |
| Spacing | Accumulated margin matches design gap |
| Corner radius | Matches container size; verify via eval_view |
| Scrollable | Content fills viewport width; no horizontal clipping |

## 3. Pitfall Rules

1. No `tolerance` — use `gte`+`lte` pair
2. All numeric values are dp; convert: `dp = px / density`
3. Use `axis` not `direction` (deprecated)
4. `textColor` must be `#AARRGGBB` (e.g., `#1976D2` → `"#FF1976D2"`)
5. Black `textColor` (`#FF000000`) may be omitted in dump — use live query
6. `textSizeSp` is live-only (auto-switches)
7. Mix property + relation checks in one `checks[]`
8. Multi-match picks first — prefer `resourceId`
9. **Failure-no-skip**: walk fallback chain (§1.3) until success; no INCONCLUSIVE after single failure
10. **Re-verify-after-fix**: re-execute complete verification (§4 or §6→§7), not partial

## 4. Full-Page Verification SOP

Systematic checklist to derive all checks for a page. Walk each layer in order.

**Step 0: Screenshot — Identify Suspicious Areas**

Take `screenshot` before programmatic checks. Compare against design spec and produce suspicious-area table (mandatory):

| Suspicious Area | Reason | Properties to Verify |
|----------------|--------|---------------------|
| e.g. Tab capsule | Height larger than spec | bounds.height |
| e.g. Banner | Missing margin | bounds.x, bounds.width, spacing |

**Step 1: Inventory Elements**

List all verifiable elements with selector (resourceId preferred) and role.

**Step 1.5: Code-Derive Expected Values**

Read source code to derive expected values. Write formulas (e.g., `itemWidth = (parentWidth - padding - gap×(spanCount-1)) / spanCount`). Never guess — trace to code or spec.

**Step 2: Per-Element Property Sweep**

Walk §1.1 properties: exists → visibility → text → textColor → textSizeSp → backgroundColor → bounds → alpha → clickable/enabled → padding. Skip unconstrained properties. Use `eval_view` for unsupported (§1.4).

**Step 3: Inter-Element Relation Sweep**

Walk §1.2 relations: spacing → alignment → order → containment → overlap.

**Step 4: Organize into layout_verify Calls**

Group by target; mix property + relation checks in one `checks[]`.

**Step 5: Supplement with eval_view**

Scan spec for unsupported properties (maxLines, ellipsize, cornerRadius, etc.).

**Step 6: Cross-Check Component Checklist**

Walk §2 for each component type present. Add missing checks.

**Step 7: Execute & Collect Evidence**

1. Target Page Context Gate (confirm correct page)
2. Run all `layout_verify` + `eval_view` calls
3. Compare actual (data layer) vs. expected (code layer) for each assertion
4. Take final `screenshot` after all checks pass

## 5. eval_view Quick Reference

Purpose: reflective getter call on single View; returns raw value (no PASS/FAIL).

- Selector: same as layout_verify target; must match exactly one.
- Expressions: `methodName()` or chained (max depth 5).
- Safety: getter-only whitelist (`get*/is*/has*/can*/should*` + `toString/length/name/ordinal/size/isEmpty`).
- Result: `data.values[]` with expression/value/type; `data.density` for px→dp.

Use when layout_verify doesn't cover the property. Agent interprets raw values.

## 6. Design-Spec-to-Assertion Conversion

When structured design data exists (Figma JSON, design tokens, annotated spec), convert to assertions **before** runtime verification. Otherwise use §4.

**Steps**:
1. Extract elements: name/role, text, fontSize→textSizeSp, color→textColor/backgroundColor (#AARRGGBB), width/height→bounds, spacing, padding, cornerRadius→eval_view, opacity→alpha. Apply dpr conversion.
2. Map to selectors: match to Android resourceId via code or layout_dump. Use §1.3 fallback chain if no resourceId.
3. Generate assertions: produce layout_verify checks per §4 Step 2+3. Unsupported properties→eval_view.
4. Review: confirm every element has ≥1 assertion, expected values match spec (with dpr), unsupported properties have eval_view fallback.

## 7. Verification Report

After executing all assertions (§6 or §4), **before** code changes, produce structured report.

**Structure**:
```
# UI Verification Report
Page / Design Source / Device / Timestamp

## Summary
| Total | ✅ PASS | ❌ FAIL | ⚠️ INCONCLUSIVE |

## Detail
| # | Check Type | Property/Relation | Expected | Actual | Verdict |

## Failed Items (Action Required)
| # | Element | Check | Expected | Actual | Suggested Fix |

## Evidence
final screenshot path
```

**Rules**:
1. Mandatory before code changes.
2. Every assertion must appear.
3. Actual values from tool output, not visual estimation.
4. Failed Items: actionable fix suggestions with file/location hints.
5. Incremental: each fix→re-verify produces new report; keep previous for diff.
