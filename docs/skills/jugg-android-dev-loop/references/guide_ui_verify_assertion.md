# Guide: UI Verification Assertion Reference

> Tools: `figma_layout_verify` (batch), `ui_find` (single query), `eval_view` (unsupported properties)

## Execution Mode

**Sub-Agent Mode** (preferred): spawn sub-agent, output structured report, exit.
**Main Agent Mode** (fallback): follow §1 or §2 below. Screenshot MUST NOT replace tool verification.

## Core Principle

Verify **relative relations** (spacing/alignment), not absolute positions. Fixed tolerance: ±2dp absolute or ±5% relative.

---

## 1. With Figma JSON: Batch Verification

**Workflow**: `activity_stack` → confirm page → `figma_layout_verify(figmaJson, dpr)` → analyze results → fix → re-verify.

**dpr selection**: 750px→dpr=2, 375px→dpr=1, 1125px→dpr=3, 411px→dpr=1. Rule: `actual_dp = figma_px / dpr`.

**Return structure**:

```json
{
  "summary": {"total": 15, "passed": 12, "failed": 3},
  "results": [
    {"type": "spacing", "element1": {...}, "element2": {...}, "axis": "x", "expected": "18dp", "actual": "14dp", "match": false, "diff": "-4dp"}
  ],
  "unmatched": [{"figmaId": "34:12179", "reason": "No similar element found"}]
}
```

**Interpreting**: `results[]` = each relation with type/elements/axis/expected/actual/match/diff. `unmatched[]` = Figma nodes without Android match (may be decorative).

**Partial verify**: pass `targetNodes: ["34:12200"]` to scope specific nodes.

---

## 2. Without Figma: Manual Verification

**Workflow**: `ui_find(target={text:"Element"})` per element → calculate spacing from bounds → compare against spec.

**Spacing calc**: horizontal = `B.bounds[0] - A.bounds[2]`, vertical = `B.bounds[1] - A.bounds[3]`.

**Alignment check**: centerY = `(bounds[1]+bounds[3])/2`. Aligned if diff ≤ 2dp.

---

## 3. Selector & Fallback Chain

`target: {resourceId?, text?, contentDesc?, className?}` (AND logic, multi-match uses IoU).

Fallback order: `text` → `text+className` → `resourceId` → `resourceId+className` → `contentDesc+className`. Exhaust each before next. Prohibition: skipping after single failure.

---

## 4. Unsupported Properties

Use `eval_view` for: maxLines, ellipsize, letterSpacing, lineCount, cornerRadius, tint color, custom getters.

```json
{"target": {"text": "Title"}, "expressions": ["getMaxLines()", "getEllipsize().name()"]}
```

Complex backgrounds: `screenshot` visual comparison.

---

## 5. Component Checklist

| Component | Focus |
|-----------|-------|
| Grid/List | Column spacing uniformity |
| Image/Icon | Size matches design |
| Banner/Card | Margin/padding |
| Text | Position (use ui_find) |
| Container | Child alignment |

---

## 6. Deprecated

`layout_verify` → replaced by `figma_layout_verify` + `ui_find`. Do NOT use.

## 7. Kuikly Notes

- Text extraction uses `KuiklyViewResolver` with special paths for `KRRichTextView`.
- Prefer `text` selector (Kuikly views may lack stable resource IDs).
- IoU matching handles Kuikly shadow view structure.
