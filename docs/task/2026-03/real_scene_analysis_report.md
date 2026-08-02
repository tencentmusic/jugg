# Real Scene Analysis Report: layout_verify Evaluation Coverage

> Date: 2026-03-07
> Data source: 25 representative UI bug commits from a large-scale Android project (recent 6 months)
> Baseline: `plan.md` 50 test cases
> Docs read: `00_overview.md`, `97_ai_usage.md`, `real_scene_extraction_plan.md`, `plan.md`

---

## Overview

This report abstracts real UI bugs into **generic verification capabilities** required by `layout_verify`, independent of any specific business logic. Each capability is described in terms of what the verification engine needs to support, not what specific app feature triggered the bug.

---

## 1. Extracted Verification Capabilities (from 25 real commits)

### Category A: Color Attribute Verification (5 real cases)

#### Cap-01: textColor runtime value assertion

Real pattern: A View's `textColor` is set via a resource reference (e.g., theme color), and the resolved runtime value must equal an expected ARGB value.

```
assert: { target: "<resourceId>", attribute: "textColor", op: "eq", value: "<#AARRGGBB>" }
```

layout_verify coverage: **YES** — standard assert on textColor.
Gap in plan.md: LV-I-1 covers textColor but only checks "default black". Need more cases with explicit ARGB values.

#### Cap-02: backgroundColor with alpha channel (ARGB exact match)

Real pattern: A container's `backgroundColor` is an ARGB value with non-trivial alpha (e.g., `#1F88939B`), and the assertion must compare all 4 channels precisely.

```
assert: { target: "<resourceId>", attribute: "backgroundColor", op: "eq", value: "#1F88939B" }
```

layout_verify coverage: **PARTIAL** — backgroundColor can be asserted, but dump may output as signed integer. Need ARGB↔integer conversion support.
Gap in plan.md: No ARGB-with-alpha test case.

#### Cap-03: textColor equals expected value under a specific app state

Real pattern: After the app switches to a specific visual state (e.g., light mode, dark mode, or any theme/config change), a control's `textColor` must equal a new expected value, different from its default.

```
# Execute dump in state A, then:
assert: { target: "<resourceId>", attribute: "textColor", op: "eq", value: "<expected_in_state_A>" }
```

layout_verify coverage: **YES** — but requires the test harness to set the desired state before dumping.
Gap in plan.md: No "state-dependent attribute value" test pattern.

#### Cap-04: Multiple controls sharing the same expected textColor

Real pattern: Several TextViews on the same page should all resolve to the same theme color. Verifying consistency across multiple targets.

```
assert: { target: "<id_1>", attribute: "textColor", op: "eq", value: "<expected>" }
assert: { target: "<id_2>", attribute: "textColor", op: "eq", value: "<expected>" }
assert: { target: "<id_3>", attribute: "textColor", op: "eq", value: "<expected>" }
```

layout_verify coverage: **YES** — multiple sequential asserts.
Gap in plan.md: No multi-target same-value consistency check.

#### Cap-05: Asserting a control's color is NOT a specific value (negative color check)

Real pattern: After a fix, a text should no longer be white (`#FFFFFFFF`) on a light background. The assertion verifies "not equal" rather than an exact match.

```
assert: { target: "<resourceId>", attribute: "textColor", op: "neq", value: "#FFFFFFFF" }
```

layout_verify coverage: **YES** — `neq` operator.
Gap in plan.md: No negative-color assertion case.

---

### Category B: Visibility State Verification (4 real cases)

#### Cap-06: visibility == VISIBLE under specific data conditions

Real pattern: A container should be VISIBLE when certain data conditions are met (e.g., a media player bar when content is available).

```
assert: { target: "<resourceId>", attribute: "visibility", op: "eq", value: "visible" }
```

layout_verify coverage: **YES** — LV-A-3 already covers this.

#### Cap-07: Element exists in view tree after lifecycle event

Real pattern: After a configuration change or process recreation, a Fragment's root view must exist in the hierarchy.

```
assert: { target: "<resourceId>", attribute: "exists" }
```

layout_verify coverage: **YES** — LV-A-1 covers this.

#### Cap-08: visibility == VISIBLE but content is stale (data-level issue)

Real pattern: A panel is visible, but its internal data hasn't refreshed. The UI "looks wrong" but visibility is correct. Need to verify inner content, not just visibility.

```
assert: { target: "<container>", attribute: "visibility", op: "eq", value: "visible" }
assert: { target: "<inner_text>", attribute: "text", op: "eq", value: "<expected_refreshed_value>" }
```

layout_verify coverage: **YES** — compose visibility + text assertions.
Gap in plan.md: No "visible-but-content-stale" pattern (visibility + text combo).

#### Cap-09: Icon/entry element visibility conditional on feature flags

Real pattern: An icon entry should be VISIBLE/GONE based on server-side feature flags or user state.

```
assert: { target: "<icon_entry>", attribute: "visibility", op: "eq", value: "visible" }
```

layout_verify coverage: **YES** — same as Cap-06, the conditional setup is external to layout_verify.

---

### Category C: Spacing & Dimension Verification (4 real cases)

#### Cap-10: Margin value change (e.g., marginTop from 10dp to 6dp)

Real pattern: A layout was updated to reduce marginTop. Need to verify the new spacing between two vertically adjacent elements.

```
relation: { source: "<id_above>", target: "<id_below>", type: "spacing", direction: "vertical", expected: 6, tolerance: 2, unit: "dp" }
```

layout_verify coverage: **YES** — LV-E-1~4 cover spacing.
Note: Direct `marginTop` attribute may not be in dump; spacing relation is the correct proxy.

#### Cap-11: minHeight constraint ensuring content is not clipped

Real pattern: A container must have `height >= minHeight` to prevent child content from being clipped.

```
assert: { target: "<resourceId>", attribute: "bounds.height", op: "gte", value: 15, unit: "dp" }
```

layout_verify coverage: **YES** — `bounds.height` with `gte` operator. LV-C-3 is similar.

#### Cap-12: Drawable internal viewport change (beyond layout_verify scope)

Real pattern: A VectorDrawable's internal `viewport` or `pathData` was modified, changing the rendered icon size within the same ImageView bounds.

layout_verify coverage: **NO** — layout_verify operates on View properties, not drawable internals.
Conclusion: Out of scope. This requires screenshot comparison or drawable-level tools.

#### Cap-13: maxLines + ellipsize combination preventing text overflow

Real pattern: A long-text label was constrained to `maxLines=1` + `ellipsize=end` to prevent layout overflow.

```
assert: { target: "<resourceId>", attribute: "maxLines", op: "eq", value: 1 }
assert: { target: "<resourceId>", attribute: "ellipsize", op: "eq", value: "end" }
```

layout_verify coverage: **PARTIAL** — maxLines/ellipsize may not be in standard dump. Need live query support.
Gap in plan.md: No maxLines/ellipsize assertion case.

---

### Category D: Attribute Value Under State Change (5 real cases)

This is the **abstracted form** of what business apps call "skin-switching" or "theme-switching". The generic capability is:
**"After the app transitions to a different visual state, verify that a control's attribute has changed to the expected value."**

#### Cap-14: textColor value differs between two app visual states

Real pattern: A TextView's textColor should resolve to different values depending on which visual state/theme the app is in. Verification requires dumping once per state.

```
# State A dump:
assert: { target: "<resourceId>", attribute: "textColor", op: "eq", value: "<color_in_state_A>" }
# State B dump:
assert: { target: "<resourceId>", attribute: "textColor", op: "eq", value: "<color_in_state_B>" }
```

layout_verify coverage: **YES** — each individual assertion is supported. The "two-state comparison" is a test orchestration concern, not a layout_verify capability gap.
Gap in plan.md: No multi-state comparison pattern.

#### Cap-15: backgroundColor value differs between two app visual states

Same as Cap-14 but for `backgroundColor`.

layout_verify coverage: **YES** — same reasoning.

#### Cap-16: Custom View attribute not in standard dump

Real pattern: A custom Switch widget has a proprietary attribute (e.g., `kswBackColor`) that doesn't appear in standard View dump.

layout_verify coverage: **NO** — custom attributes are out of scope for standard dump/live query.
Conclusion: Requires extending live query to support custom attribute accessors.

#### Cap-17: tintColor / colorFilter not in standard dump

Real pattern: An ImageView's `tint` or `colorFilter` is set programmatically. These properties don't appear in standard View dump.

layout_verify coverage: **NO** — tint/colorFilter are rendering-level properties, not standard View attributes.
Conclusion: Could be added to live query as special-case accessors.

#### Cap-18: Same page, multiple controls should all change after state switch

Real pattern: On a single page, after a state change, verify that title textColor, container backgroundColor, and button textColor all updated correctly.

```
# After state switch:
assert: { target: "<title>", attribute: "textColor", op: "eq", value: "<new_color>" }
assert: { target: "<container>", attribute: "backgroundColor", op: "eq", value: "<new_bg>" }
assert: { target: "<button>", attribute: "textColor", op: "eq", value: "<new_color>" }
```

layout_verify coverage: **YES** — multiple sequential asserts.
Gap in plan.md: No multi-attribute batch assertion after state change.

---

### Category E: Text Content & Internationalization (2 real cases)

#### Cap-19: Text content equals expected localized string

Real pattern: Under a specific locale, a label should display the correct translated text.

```
assert: { target: "<resourceId>", attribute: "text", op: "eq", value: "<expected_localized_string>" }
```

layout_verify coverage: **YES** — LV-B-1 covers text exact match. The locale setup is external.
Gap in plan.md: No explicit i18n text verification case (same capability, but documenting the scenario helps coverage awareness).

#### Cap-20: TabLayout indicator color / tab text color

Real pattern: A TabLayout's selected tab text color and indicator color should match expected values.

```
assert: { target: "<tab_text_view>", attribute: "textColor", op: "eq", value: "<expected>" }
```

layout_verify coverage: **PARTIAL** — Tab text textColor may be assertable if the individual tab TextView is in the dump. TabLayout's `indicatorColor` is a compound widget property, likely not in dump.
Gap in plan.md: No compound widget sub-property assertion case.

---

### Category F: Alignment & RTL (1 real case)

#### Cap-21: marginStart instead of marginLeft (RTL correctness)

Real pattern: Layout uses `marginStart` for RTL compatibility. Under RTL locale, `marginStart` should map to the physical right side.

```
relation: { source: "<parent_start>", target: "<child>", type: "spacing", direction: "horizontal", expected: 12, unit: "dp" }
```

layout_verify coverage: **PARTIAL** — spacing relation works, but verifying RTL physical direction requires running under RTL locale. The assertion itself is the same.
Gap in plan.md: No RTL-specific spacing test.

---

### Category G: Shape & Drawable Properties (1 real case)

#### Cap-22: cornerRadius / drawable shape verification

Real pattern: A label background has an incorrect corner radius due to a drawable wrapping bug. The fix wraps the drawable differently.

layout_verify coverage: **NO** — cornerRadius is a drawable internal property, not a View attribute.
Conclusion: Out of scope. Requires screenshot comparison or drawable introspection.

---

### Category H: Dialog/Toast Internal Layout Verification (3 real cases)

#### Cap-23: Custom Toast internal controls assertion

Real pattern: A custom Toast layout contains multiple controls (icon, title, description, action button). After the Toast appears, verify its internal layout.

```
assert: { target: "<toast_title>", attribute: "textColor", op: "eq", value: "<expected>" }
assert: { target: "<toast_icon>", attribute: "bounds.width", op: "eq", value: 45, unit: "dp" }
relation: { source: "<icon>", target: "<title>", type: "order", direction: "horizontal" }
```

layout_verify coverage: **PARTIAL** — Toast is a separate Window. ViewHierarchy dump may or may not capture Toast's view tree depending on timing and implementation.
Gap in plan.md: No Toast/custom-Window capture test.

#### Cap-24: Toast/custom overlay padding verification

Real pattern: Padding values of a Toast container need to be symmetric (e.g., paddingHorizontal=16dp, paddingVertical=15dp).

```
assert: { target: "<toast_container>", attribute: "padding.left", op: "eq", value: 16, unit: "dp" }
assert: { target: "<toast_container>", attribute: "padding.right", op: "eq", value: 16, unit: "dp" }
assert: { target: "<toast_container>", attribute: "padding.top", op: "eq", value: 15, unit: "dp" }
assert: { target: "<toast_container>", attribute: "padding.bottom", op: "eq", value: 15, unit: "dp" }
```

layout_verify coverage: **PARTIAL** — same Toast Window capture limitation as Cap-23.

#### Cap-25: Dialog created with wrong Context (produces extra overlay)

Real pattern: A Dialog was created with the wrong Context, causing an extra dim/overlay layer. The fix is code-level; no single View attribute captures this.

layout_verify coverage: **NO** — root cause is a code-level error, not a View attribute deviation.
Conclusion: Out of scope. Best detected via integration test or screenshot diff.

---

## 2. Coverage Cross-Reference: plan.md vs. Real Capabilities

### 2.1 Capability → plan.md Coverage Matrix

| Capability Category | Case IDs | layout_verify Coverable | plan.md Coverage | Gap Level |
|---------------------|----------|------------------------|------------------|-----------|
| **Color value assertion** (textColor, backgroundColor) | Cap-01~05 | 4 YES, 1 PARTIAL | LV-I-1 (1 case) | **Weak** — only 1 color case, no ARGB-alpha, no negative check |
| **Visibility / exists** | Cap-06~09 | 4 YES | LV-A-1~4 (4 cases) | **Covered** |
| **Spacing / dimension** | Cap-10~13 | 2 YES, 1 PARTIAL, 1 NO | LV-C-1~4, LV-E-1~4 (8 cases) | **Covered** |
| **Attribute under state change** (multi-state comparison) | Cap-14~18 | 3 YES, 2 NO | None | **Missing** — no multi-state test pattern |
| **Text content (i18n)** | Cap-19~20 | 1 YES, 1 PARTIAL | LV-B-1~5 (5 cases) | **Partial** — text covered, no i18n/compound widget |
| **RTL correctness** | Cap-21 | PARTIAL | LV-F-1~3 (alignment) | **Partial** — alignment covered, no RTL-specific case |
| **Drawable internals** (cornerRadius, vectorPath) | Cap-12, Cap-22 | 2 NO | None | **Out of scope** |
| **Custom attributes** (not in standard dump) | Cap-16, Cap-17 | 2 NO | None | **Out of scope** |
| **Dialog/Toast Window capture** | Cap-23~25 | 2 PARTIAL, 1 NO | None | **Missing** — no Dialog/Toast test |
| **maxLines + ellipsize** | Cap-13 | PARTIAL | None | **Missing** |

### 2.2 Coverage Statistics

| Metric | Value |
|--------|-------|
| Total real capabilities extracted | 25 |
| plan.md fully covers | 10 (40%) |
| plan.md partially covers | 6 (24%) |
| plan.md does not cover | 9 (36%) |
| layout_verify can cover (YES + PARTIAL) | 18 (72%) |
| layout_verify cannot cover (NO) | 7 (28%) |

---

## 3. Gap Analysis

### 3.1 Gaps in plan.md (capabilities missing, within layout_verify's reach)

| # | Missing Capability | Priority | Real Frequency | Suggested Cases | Rationale |
|---|-------------------|----------|----------------|-----------------|-----------|
| 1 | **Color value assertion with explicit ARGB** | P0 | Highest | 2~3 | Most frequent real bug type; plan.md only has 1 vague color case |
| 2 | **Negative color assertion** (neq) | P0 | High | 1 | Common pattern: "color must NOT be X" |
| 3 | **Multi-state attribute comparison** | P0 | High | 2~3 | Need two dumps under different states, assert same control has different values |
| 4 | **Dialog/Toast internal control assertion** | P1 | Medium | 2 | Verify controls inside a Dialog/BottomSheet after it appears |
| 5 | **maxLines + ellipsize** | P1 | Medium | 1~2 | Text overflow prevention; need live query if not in dump |
| 6 | **RTL spacing verification** | P2 | Low-Medium | 1 | marginStart under RTL locale |
| 7 | **Compound widget sub-property** (e.g., TabLayout indicatorColor) | P2 | Low | 1 | Material compound widget internal attributes |

### 3.2 Gaps in layout_verify capability (out of current reach)

| # | Capability Gap | Cases Affected | Impact | Potential Solution |
|---|---------------|----------------|--------|-------------------|
| 1 | **Custom View attributes** | Cap-16, Cap-17 | Medium | Extend live query to support registered custom attribute accessors |
| 2 | **Drawable internals** (vectorPath, cornerRadius) | Cap-12, Cap-22 | Medium | Out of scope; use screenshot comparison |
| 3 | **Toast/Dialog Window dump** | Cap-23, Cap-24 | Medium | Confirm if ViewHierarchy dump supports multi-Window; extend if not |
| 4 | **tintColor / colorFilter** | Cap-17 | Medium | Add ImageView tint/colorFilter to dump schema or live query |
| 5 | **Hippy/cross-framework Views** | (1 real case) | Low | Framework-level bridge needed; out of scope for layout_verify |

---

## 4. Recommended Additions to plan.md

### 4.1 New Test Cases (add 7~10 cases)

These are described as **generic verification capabilities**, not tied to any specific business:

#### LV-Q-1: textColor exact ARGB assertion

> Verify that a TextView's textColor equals a specific ARGB hex value (e.g., `#FF212121`).

Verification type: **Color attribute exact match**

#### LV-Q-2: backgroundColor with alpha channel (ARGB)

> Verify that a container's backgroundColor equals `#1F88939B` (alpha=0x1F, ~12% opacity).

Verification type: **ARGB-with-alpha exact match**

#### LV-Q-3: textColor negative assertion (neq)

> Verify that a TextView's textColor is NOT `#FFFFFFFF` (white), ensuring it's readable on a light background.

Verification type: **Negative color check**

#### LV-Q-4: Multi-state attribute comparison (same control, different states)

> Dump the layout in State A, record `tv_title.textColor`. Switch to State B, dump again, verify `tv_title.textColor` has changed to a different expected value.

Verification type: **State-dependent attribute diff** (test orchestration + assert)

#### LV-Q-5: Dialog/BottomSheet internal control assertion

> After a Dialog/BottomSheet appears, verify its internal title's textColor and description's textColor match expected values.

Verification type: **Dialog Window internal layout assertion**

#### LV-Q-6: maxLines + ellipsize combination

> Verify that a long-text label has `maxLines == 1` and `ellipsize == "end"`.

Verification type: **Text overflow constraint assertion** (may require live query)

#### LV-Q-7: Localized text content assertion

> Under a specific locale, verify that a label's text equals the expected translated string.

Verification type: **i18n text content match** (same as text eq, but documents the scenario)

#### LV-Q-8: RTL layout spacing

> Under an RTL locale, verify that the horizontal spacing between a parent's start edge and a child equals the expected `marginStart` value.

Verification type: **RTL-aware spacing assertion**

#### LV-Q-9: Multiple controls sharing the same attribute value (batch consistency)

> Verify that 3 TextViews on the same page all have the same textColor value, ensuring visual consistency.

Verification type: **Multi-target attribute consistency**

#### LV-Q-10: Visible-but-content-stale detection (visibility + text combo)

> A panel is VISIBLE, but its inner text should have refreshed. Verify both `visibility == VISIBLE` and `text == <expected_new_value>`.

Verification type: **Composite assertion: visibility + text content**

### 4.2 Proposed New Category in plan.md

| New Category | Cases | Capabilities Covered |
|-------------|-------|---------------------|
| Q. Extended Attribute Assertions | LV-Q-1~3 | ARGB color, alpha channel, negative check |
| R. State-Dependent Verification | LV-Q-4 | Multi-state attribute comparison |
| S. Dialog/Toast Scenarios | LV-Q-5 | Dialog/BottomSheet internal layout |
| T. Text Overflow & i18n | LV-Q-6~7 | maxLines, ellipsize, localized text |
| U. RTL & Consistency | LV-Q-8~9 | RTL spacing, multi-target consistency |

Adjusted total: **50 + 10 = 60 cases**

### 4.3 Priority Ranking

```
P0 (must add — largest coverage gap, within layout_verify reach):
  - ARGB color assertions (LV-Q-1, Q-2, Q-3)
  - State-dependent attribute comparison (LV-Q-4)
  - Dialog internal assertion (LV-Q-5)

P1 (should add — improves real-scene relevance):
  - maxLines + ellipsize (LV-Q-6)
  - i18n text (LV-Q-7)
  - Batch consistency (LV-Q-9)
  - Visibility + text combo (LV-Q-10)

P2 (optional — covers long-tail scenarios):
  - RTL spacing (LV-Q-8)
```

---

## 5. Summary

### 5.1 Key Findings

1. **plan.md covers 40% (full) + 24% (partial) = 64% of real-world verification needs**, falling short of the 95% target.
2. **Largest gap: "attribute value under state change"** — the highest-frequency real bug pattern. plan.md has zero multi-state test cases.
3. **Color assertion is under-represented**: plan.md has only 1 color case (LV-I-1), but color bugs are the most frequent in real data. Need ARGB-exact, alpha-aware, and negative assertions.
4. **Dialog/Toast internal verification** is a medium-frequency real need, completely absent from plan.md.
5. **layout_verify capability boundary**: 72% of real capabilities are coverable (YES + PARTIAL), 28% are not. The uncoverable cases are: custom View attributes, drawable internals, cross-framework Views (Hippy), tint/colorFilter.

### 5.2 Recommended Actions

| Action | Owner | Priority |
|--------|-------|----------|
| Add 10 generic capability test cases to plan.md | Evaluation executor | P0 |
| Confirm ViewHierarchy dump supports Dialog/Toast Windows | layout_verify dev | P0 |
| Evaluate live query support for maxLines/ellipsize | layout_verify dev | P1 |
| Consider extending dump schema for ImageView tint/colorFilter | layout_verify design | P2 |
| Add State-A/State-B comparison tests in android_demo_project | Test project maintainer | P1 |
