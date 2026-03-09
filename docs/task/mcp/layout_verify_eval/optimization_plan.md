# layout_verify 5 大风险点优化方案

> Date: 2026-03-08
> Docs read: `00_overview.md`, `97_ai_usage.md`, `08_mcp_design.md`, `checks_complexity_eval.md`, `real_scene_analysis_report.md`
> Code basis: `LayoutVerifyMcpToolAction.kt`, `LayoutVerifier.java`, `guide_layout_verify_assertion.md`

---

## Overview

This plan addresses the 5 risk points identified in the complexity evaluation:

| # | Risk Point | Severity | Section |
|---|-----------|----------|---------|
| 1 | `alpha` op ignored in dumpFile mode | Bug (P0) | §1 |
| 2 | `alignment.direction` counter-intuitive semantics | Design Risk (P0) | §2 |
| 3 | `backgroundColor` not supported | Feature Gap (P1) | §3 |
| 4 | `overlap` PASS = no overlap (counter-intuitive) | Semantics Risk (P1) | §4 |
| 5 | `containment` target/target2 direction confusion | Semantics Risk (P1) | §5 |

---

## §1. Fix `alpha` op support in dumpFile mode

### 1.1 Problem

`LayoutVerifyMcpToolAction.kt` lines 371-377: the `alpha` branch ignores the `op` parameter entirely, always performing approximate-equality comparison regardless of what `op` the caller passes.

```kotlin
// Current code — op is completely ignored
"alpha" -> {
    val actual = node.get("alpha")?.runCatching { asDouble }?.getOrDefault(1.0) ?: 1.0
    val expected = (value as? Number)?.toDouble() ?: 1.0
    if (Math.abs(actual - expected) < 0.001)
        VerifyResult("PASS", "alpha = $actual", actual, expected)
    else
        VerifyResult("FAIL", "alpha = $actual (expected: $op $expected)", actual, expected)
}
```

Meanwhile `LayoutVerifier.java` `assertDouble()` (lines 197-213) partially supports `gte`/`lte` but falls through to eq for `gt`/`lt`/`neq`.

### 1.2 Solution

Introduce a shared `assertDoubleDp()` helper in `LayoutVerifyMcpToolAction.kt` that mirrors the op-dispatch pattern of `assertBoundsDp()`:

```kotlin
// New method in LayoutVerifyMcpToolAction.kt
private fun assertDouble(actual: Double, op: String, expected: Double, property: String): VerifyResult {
    val pass = when (op) {
        "gte" -> actual >= expected - DOUBLE_EPSILON
        "lte" -> actual <= expected + DOUBLE_EPSILON
        "gt"  -> actual > expected + DOUBLE_EPSILON
        "lt"  -> actual < expected - DOUBLE_EPSILON
        "neq" -> Math.abs(actual - expected) >= DOUBLE_EPSILON
        else  -> Math.abs(actual - expected) < DOUBLE_EPSILON // eq
    }
    val msg = "$property = $actual (expected: $op $expected)"
    return if (pass) VerifyResult("PASS", msg, actual, expected)
    else VerifyResult("FAIL", msg, actual, expected)
}

companion object {
    private const val DOUBLE_EPSILON = 0.001
}
```

Then replace the `alpha` branch:

```kotlin
"alpha" -> {
    val actual = node.get("alpha")?.runCatching { asDouble }?.getOrDefault(1.0) ?: 1.0
    val expected = (value as? Number)?.toDouble() ?: 1.0
    assertDouble(actual, op, expected, "alpha")
}
```

Also fix `LayoutVerifier.java` `assertDouble()` to add `gt`/`lt`/`neq` cases:

```java
private JSONObject assertDouble(double actual, String op, double expected, String property, String unit)
        throws JSONException {
    boolean pass;
    switch (op) {
        case "gte":
            pass = actual >= expected - 0.001;
            break;
        case "lte":
            pass = actual <= expected + 0.001;
            break;
        case "gt":
            pass = actual > expected + 0.001;
            break;
        case "lt":
            pass = actual < expected - 0.001;
            break;
        case "neq":
            pass = Math.abs(actual - expected) >= 0.001;
            break;
        default: // eq
            pass = Math.abs(actual - expected) < 0.001;
            break;
    }
    String message = property + " = " + actual + " (expected: " + op + " " + expected + ")";
    return pass ? buildPassResult(message, actual, expected, unit) : buildFailResult(message, actual, expected, unit);
}
```

### 1.3 Test Cases

| Case | Input | Expected |
|------|-------|----------|
| alpha eq match | `alpha=1.0, op="eq", value=1.0` | PASS |
| alpha eq mismatch | `alpha=0.5, op="eq", value=1.0` | FAIL |
| alpha gt pass | `alpha=1.0, op="gt", value=0.5` | PASS |
| alpha gt fail (equal) | `alpha=0.5, op="gt", value=0.5` | FAIL |
| alpha lt pass | `alpha=0.3, op="lt", value=0.5` | PASS |
| alpha neq pass | `alpha=0.5, op="neq", value=1.0` | PASS |
| alpha neq fail (equal) | `alpha=1.0, op="neq", value=1.0` | FAIL |
| alpha gte boundary | `alpha=0.5, op="gte", value=0.5` | PASS |
| alpha lte boundary | `alpha=0.5, op="lte", value=0.5` | PASS |

### 1.4 Skill Update

Update `guide_layout_verify_assertion.md` Pitfall #10:

Before:
```
### #10: alpha only supports eq/gte/lte
`gt`/`lt`/`neq` silently behave as approximate `eq`. Use `gte`/`lte` for ranges.
```

After:
```
### #10: alpha supports all 6 ops
All standard ops (`eq`/`neq`/`gt`/`lt`/`gte`/`lte`) work correctly for `alpha`.
Comparison uses epsilon=0.001 for floating-point tolerance.
```

### 1.5 Impact

- Estimated effort: **0.5 day**
- Risk: **Low** — purely additive op branches, no breaking changes
- Correctness improvement: alpha `gt`/`lt`/`neq` calls go from **always wrong** to **correct**

---

## §2. Disambiguate `alignment.direction` semantics

### 2.1 Problem

`direction: "vertical"` checks **X-center** (horizontal centering), and `direction: "horizontal"` checks **Y-center** (vertical centering). This is counter-intuitive and causes 40-60% error rate without Skill, ~10-15% with Skill.

### 2.2 Solution: Multi-layer defense

Changing the actual behavior is a **breaking change** (existing Skill docs, tests, and agent workflows depend on current semantics). Instead, we apply a 3-layer defense:

#### Layer 1: Enrich MCP Schema description (zero-cost, highest ROI)

Current `direction` description:
```
"For spacing/alignment/order. alignment: vertical→checks X-center, horizontal→checks Y-center."
```

Proposed — add explicit natural-language mapping:

```kotlin
"direction" to McpJsonSchemaProperty(
    type = "string",
    description = "For spacing/alignment/order. " +
        "ALIGNMENT SEMANTICS (IMPORTANT): " +
        "To check if two elements are horizontally centered (same X-center), use direction='vertical'. " +
        "To check if two elements are vertically centered (same Y-center), use direction='horizontal'. " +
        "SPACING/ORDER: direction indicates the axis of measurement.",
    `enum` = listOf("horizontal", "vertical"),
),
```

This puts the mapping directly in the schema where all agents (with or without Skill) will see it.

#### Layer 2: Add result message clarification

When alignment check returns, include what was actually checked:

Current message: `"alignment (vertical): horizontal centers: 540 vs 540"`

Proposed: `"alignment (direction=vertical → X-center check): centers 540 vs 540 — PASS"`

This helps the agent verify it chose the correct direction by reading the result.

Code change in `LayoutVerifyMcpToolAction.kt` `relationDumpNodes()`:

```kotlin
"alignment" -> {
    val (aLeft, aTop, aRight, aBottom) = getBounds(target)
    val (bLeft, bTop, bRight, bBottom) = getBounds(target2)
    val pass: Boolean
    val desc: String
    if ("vertical".equals(direction, ignoreCase = true)) {
        val centerA = (aLeft + aRight) / 2
        val centerB = (bLeft + bRight) / 2
        pass = Math.abs(centerA - centerB) <= 2
        desc = "direction=vertical → X-center check: $centerA vs $centerB"
    } else {
        val centerA = (aTop + aBottom) / 2
        val centerB = (bTop + bBottom) / 2
        pass = Math.abs(centerA - centerB) <= 2
        desc = "direction=horizontal → Y-center check: $centerA vs $centerB"
    }
    val msg = "alignment ($desc)"
    if (pass) VerifyResult("PASS", msg) else VerifyResult("FAIL", msg)
}
```

Same change in `LayoutVerifier.java` `checkAlignment()`.

#### Layer 3: Skill doc already covers (no change needed)

`guide_layout_verify_assertion.md` Pitfall #3 + Mapping Table 1.2 + Example 4 already provide the three-layer Skill coverage. No update needed.

### 2.3 Test Cases

Existing alignment tests remain valid. Add 1 new test to verify message format:

| Case | Input | Expected Message Contains |
|------|-------|--------------------------|
| alignment vertical msg | `direction="vertical"` | `"direction=vertical → X-center check"` |
| alignment horizontal msg | `direction="horizontal"` | `"direction=horizontal → Y-center check"` |

### 2.4 Impact

- Estimated effort: **0.5 day**
- Risk: **Low** — message format change is non-breaking; schema description is additive
- Expected improvement: **No-Skill error rate from 40-60% → ~20-25%** (schema description alone should halve the confusion)

---

## §3. Add `backgroundColor` support

### 3.1 Problem

`backgroundColor` is the highest-frequency real-world need (Cap-02, Cap-15 in `real_scene_analysis_report.md`) but is completely unsupported. Agent gets `"unsupported property in dumpFile mode: backgroundColor"`.

### 3.2 Solution: Live-query implementation

`backgroundColor` is not available in the standard dump JSON (the dump only captures what `ViewNode` serializes). The most reliable path is **live query via `LayoutVerifier.java`**.

#### Step 1: Add `backgroundColor` to `LayoutVerifier.java` `executeAssert()`

```java
case "backgroundColor": {
    android.graphics.drawable.Drawable bg = target.view.getBackground();
    if (bg instanceof android.graphics.drawable.ColorDrawable) {
        int color = ((android.graphics.drawable.ColorDrawable) bg).getColor();
        String actualHex = ViewNode.colorToHex(color).toUpperCase();
        String normalizedExpected = assertParams.optString("value", "").toUpperCase();
        return assertText(actualHex, op, normalizedExpected, "backgroundColor");
    }
    // Non-ColorDrawable backgrounds cannot be expressed as a single color
    return errorResult(
        "backgroundColor is not a solid color (drawable type: " + (bg != null ? bg.getClass().getSimpleName() : "null") + "). " +
        "Use screenshot comparison for gradient/shape backgrounds.",
        null
    );
}
```

#### Step 2: Mark `backgroundColor` as live-only in `LayoutVerifyMcpToolAction.kt`

```kotlin
companion object {
    private const val PROPERTY_CHECK_TYPE = "property"
    private val LIVE_ONLY_PROPERTIES = setOf("textSizeSp", "backgroundColor")
    private const val MAX_CANDIDATES = 5
}
```

This ensures any check containing `backgroundColor` auto-routes to live query mode.

#### Step 3: Update MCP schema `property` description

```kotlin
"property" to McpJsonSchemaProperty(
    type = "string",
    description = "For type=property. textColor/backgroundColor use #AARRGGBB (e.g. #FF1976D2). " +
        "textSizeSp and backgroundColor are live-only. " +
        "backgroundColor only works for solid-color backgrounds (ColorDrawable).",
),
```

#### Step 4: Update Skill doc

In `guide_layout_verify_assertion.md` mapping table 1.1, change:

Before:
```
| backgroundColor | — | — | — | ❌ Not supported; use screenshot |
```

After:
```
| backgroundColor (solid) | `backgroundColor` | `eq` | `"#FF1976D2"` | ⚠️ Live-only; solid color only |
```

Update Pitfall #9:

Before:
```
### #9: backgroundColor not supported
Use `screenshot` + visual comparison as fallback.
```

After:
```
### #9: backgroundColor — solid color only
Works for `ColorDrawable` (solid color backgrounds). Reports error for gradient/shape drawables; use screenshot fallback for those.
Must use #AARRGGBB format (same as textColor, Pitfall #4).
```

Update Figma mapping table 1.3:

Before:
```
| `background: rgba(...)` | ❌ | Screenshot fallback |
```

After:
```
| `background: rgba(...)` (solid) | `backgroundColor: "#1F88939B"` | Live-only; solid color only |
| `background: gradient/image` | ❌ | Screenshot fallback |
```

### 3.3 Test Cases

| Case | Input | Expected |
|------|-------|----------|
| backgroundColor eq match | `property="backgroundColor", value="#FF1976D2"` on solid-color view | PASS |
| backgroundColor eq mismatch | `property="backgroundColor", value="#FFFF0000"` on blue view | FAIL |
| backgroundColor neq | `property="backgroundColor", op="neq", value="#FFFFFFFF"` on non-white view | PASS |
| backgroundColor non-ColorDrawable | `property="backgroundColor"` on gradient background view | ERROR with message indicating drawable type |
| backgroundColor null | `property="backgroundColor"` on view with no background | ERROR with message "null" |
| backgroundColor with ARGB alpha | `property="backgroundColor", value="#1F88939B"` | PASS if matches |
| backgroundColor auto-live routing | checks with `backgroundColor` + `text` → should route to live mode | Verify live mode is used |

### 3.4 Impact

- Estimated effort: **1-1.5 days** (implementation + test in demo project)
- Risk: **Medium** — `ColorDrawable` detection covers ~70% of real backgroundColor use cases; gradient/nine-patch/layer-list backgrounds will correctly report errors
- Coverage improvement: **Directly resolves the #1 capability gap** from real_scene_analysis_report.md (Cap-02, Cap-15)

### 3.5 Limitations

- Only works for `ColorDrawable` backgrounds. `ShapeDrawable`, `GradientDrawable`, `LayerDrawable`, `BitmapDrawable`, etc. cannot be reduced to a single color value.
- For non-solid backgrounds, the error message guides the agent to use screenshot comparison.

---

## §4. Clarify `overlap` PASS = no overlap semantics

### 4.1 Problem

`overlap` check returns **PASS when elements do NOT overlap**. This is counter-intuitive — an agent verifying "these two elements overlap" would expect PASS to mean "yes, they overlap", but gets the opposite.

Error rate: 20-30% without Skill, ~10% with Skill.

### 4.2 Solution: Multi-layer clarification + optional `expectOverlap` parameter

#### Layer 1: Enhance MCP Schema description

Current `type` description excerpt:
```
"overlap: PASS=no overlap."
```

Proposed:
```
"overlap: checks whether two elements overlap. Default: PASS=no overlap (asserts elements do NOT overlap). " +
"Set expectOverlap=true to reverse: PASS=elements DO overlap."
```

#### Layer 2: Add optional `expectOverlap` parameter

This is the most impactful change — it lets the agent explicitly state intent.

Add to MCP schema:

```kotlin
"expectOverlap" to McpJsonSchemaProperty(
    type = "boolean",
    description = "Only for type=overlap. Default false (PASS=no overlap). Set true to assert elements DO overlap (PASS=overlap exists).",
),
```

Code change in `LayoutVerifyMcpToolAction.kt`:

```kotlin
"overlap" -> {
    val (aLeft, aTop, aRight, aBottom) = getBounds(target)
    val (bLeft, bTop, bRight, bBottom) = getBounds(target2)
    val overlaps = aLeft < bRight && aRight > bLeft && aTop < bBottom && aBottom > bTop
    val expectOverlap = relation["expectOverlap"] as? Boolean ?: false
    val pass = if (expectOverlap) overlaps else !overlaps
    val msg = "overlap (expectOverlap=$expectOverlap): " +
        if (overlaps) "elements overlap" else "no overlap"
    if (pass) VerifyResult("PASS", msg) else VerifyResult("FAIL", msg)
}
```

Same in `LayoutVerifier.java`:

```java
private JSONObject checkOverlap(MatchedElement a, MatchedElement b, boolean expectOverlap) throws JSONException {
    boolean overlaps = a.bounds.left < b.bounds.right
        && a.bounds.right > b.bounds.left
        && a.bounds.top < b.bounds.bottom
        && a.bounds.bottom > b.bounds.top;
    boolean pass = expectOverlap ? overlaps : !overlaps;
    String message = "overlap (expectOverlap=" + expectOverlap + "): "
        + (overlaps ? "elements overlap" : "no overlap");
    return pass ? buildPassResult(message, overlaps, expectOverlap, null)
                : buildFailResult(message, overlaps, expectOverlap, null);
}
```

Update `executeRelation()` call site:
```java
case "overlap":
    boolean expectOverlap = relationParams.optBoolean("expectOverlap", false);
    return checkOverlap(target, target2, expectOverlap);
```

#### Layer 3: Update Skill doc

In `guide_layout_verify_assertion.md` mapping table 1.2:

Before:
```
| No overlap | `overlap` | — | — | ⚠️ PASS = **no** overlap |
```

After:
```
| No overlap | `overlap` | — | — | Default: PASS = no overlap |
| Assert overlap exists | `overlap` | — | `expectOverlap: true` | PASS = elements DO overlap |
```

#### Backward Compatibility

- Default `expectOverlap=false` preserves existing behavior exactly
- No existing calls break

### 4.3 Test Cases

| Case | Input | Expected |
|------|-------|----------|
| overlap default, no overlap | two non-overlapping elements, no `expectOverlap` | PASS |
| overlap default, overlapping | two overlapping elements, no `expectOverlap` | FAIL |
| overlap expectOverlap=true, overlapping | two overlapping elements, `expectOverlap=true` | PASS |
| overlap expectOverlap=true, no overlap | two non-overlapping elements, `expectOverlap=true` | FAIL |
| overlap expectOverlap=false explicit | same as default behavior | PASS/FAIL per default |

### 4.4 Impact

- Estimated effort: **0.5 day**
- Risk: **Very Low** — backward compatible, additive parameter
- Expected improvement: **Error rate from 20-30% → ~5%** (agent can express intent explicitly; ambiguity eliminated)

---

## §5. Clarify `containment` target/target2 direction

### 5.1 Problem

`containment` uses `target=child, target2=parent`. If swapped, the assertion result flips. Error rate: 20-30% without Skill, ~8% with Skill.

### 5.2 Solution: Multi-layer defense

#### Layer 1: Enhance MCP Schema descriptions

Current `target2` description:
```
"Second element. containment: target=child, target2=parent."
```

Proposed — add explicit guidance:
```kotlin
"target2" to McpJsonSchemaProperty(
    type = "object",
    description = "Second element for relation checks. " +
        "containment: target=CHILD (inner element), target2=PARENT (outer container). " +
        "PASS means target is fully inside target2. " +
        "spacing/order: target is the 'from' element, target2 is the 'to' element.",
    // ... properties unchanged
),
```

Also add to `type` description:
```kotlin
"containment: PASS=target(child) is fully inside target2(parent)."
```

#### Layer 2: Add result message with role labels

Current message: `"containment: target is inside container"`

Proposed: `"containment: target(child) is inside target2(parent) — PASS"`

Code change in `LayoutVerifyMcpToolAction.kt`:

```kotlin
"containment" -> {
    val (aLeft, aTop, aRight, aBottom) = getBounds(target)
    val (bLeft, bTop, bRight, bBottom) = getBounds(target2)
    val contained = aLeft >= bLeft && aTop >= bTop && aRight <= bRight && aBottom <= bBottom
    val msg = "containment: target(child) " +
        if (contained) "is inside" else "is NOT inside" +
        " target2(parent)"
    if (contained) VerifyResult("PASS", msg) else VerifyResult("FAIL", msg)
}
```

Same in `LayoutVerifier.java`:

```java
private JSONObject checkContainment(MatchedElement target, MatchedElement container) throws JSONException {
    boolean contained = target.bounds.left >= container.bounds.left
        && target.bounds.top >= container.bounds.top
        && target.bounds.right <= container.bounds.right
        && target.bounds.bottom <= container.bounds.bottom;
    String message = "containment: target(child) " + (contained ? "is inside" : "is NOT inside") + " target2(parent)";
    return contained ? buildPassResult(message, null, null, null) : buildFailResult(message, null, null, null);
}
```

#### Layer 3: Skill doc already covers (minor enhancement)

In `guide_layout_verify_assertion.md`, the mapping table already says `target=child, target2=parent`. Add a concise example:

```
### Ex8: Containment (target=child, target2=parent)
```json
{ "checks": [{
    "target": {"resourceId": "icon_avatar"},
    "target2": {"resourceId": "container_header"},
    "type": "containment"
}]}
```
Verifies `icon_avatar` (child) is fully inside `container_header` (parent).
```

### 5.3 Test Cases

Existing containment tests remain valid. No behavioral change, only message format.

| Case | Input | Expected Message Contains |
|------|-------|--------------------------|
| contained msg | child inside parent | `"target(child) is inside target2(parent)"` |
| not contained msg | child outside parent | `"target(child) is NOT inside target2(parent)"` |

### 5.4 Impact

- Estimated effort: **0.25 day**
- Risk: **Very Low** — only schema description and message format changes
- Expected improvement: **No-Skill error rate from 20-30% → ~10-15%**; with-Skill stays ~8%

---

## Summary: Implementation Priority & Schedule

| Priority | Item | Effort | Risk | Correctness Gain |
|----------|------|--------|------|------------------|
| **P0** | §1. Fix alpha op | 0.5d | Low | Bug fix: alpha gt/lt/neq from wrong → correct |
| **P0** | §2. alignment.direction disambiguation | 0.5d | Low | No-Skill: 40-60% err → ~20-25% |
| **P1** | §3. backgroundColor support | 1-1.5d | Medium | Fills #1 capability gap (Cap-02, Cap-15) |
| **P1** | §4. overlap expectOverlap param | 0.5d | Very Low | 20-30% err → ~5% |
| **P1** | §5. containment message + schema | 0.25d | Very Low | 20-30% err → ~10-15% |
| **Total** | | **~3 days** | | |

### Execution Order

```
Day 1: §1 (alpha fix) + §2 (alignment schema + message)
Day 2: §4 (overlap expectOverlap) + §5 (containment message) + unit tests for §1/§2/§4/§5
Day 3: §3 (backgroundColor) + integration test on demo project
```

### Files to Modify

| File | Changes |
|------|---------|
| `LayoutVerifyMcpToolAction.kt` | §1: alpha op; §2: alignment message; §3: LIVE_ONLY_PROPERTIES + schema; §4: overlap expectOverlap; §5: containment message |
| `LayoutVerifier.java` | §1: assertDouble() full op; §3: backgroundColor case; §4: checkOverlap(expectOverlap); §5: containment message |
| `guide_layout_verify_assertion.md` | §1: Pitfall #10; §3: Pitfall #9 + mapping tables; §4: mapping table overlap row; §5: add Ex8 |
| MCP schema (in `LayoutVerifyMcpToolAction.kt`) | §2: direction desc; §3: property desc; §4: expectOverlap param + type desc; §5: target2 desc + type desc |

### Documentation Updates

| Doc | Section | Action |
|-----|---------|--------|
| `guide_layout_verify_assertion.md` | Pitfall #9, #10, Mapping 1.1/1.2/1.3, Examples | Update per §1-§5 |
| `08_mcp_design.md` | §6 ViewHierarchy | Add note about backgroundColor live-only and expectOverlap param |
| `checks_complexity_eval.md` | §5 Recommendations | Mark completed items |
