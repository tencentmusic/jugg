# Guide: UI Verification Assertion Reference

> Tools: `view-locate` (element position), `view-inspect` (content details), `layout-dump` (fallback when view-locate insufficient)

## Execution Mode

**Sub-Agent Mode** (preferred): spawn sub-agent, output structured report, exit.
**Main Agent Mode** (fallback): follow §1 below. Screenshot MUST NOT replace tool verification.

## Core Principle

Verify **relative relations** (spacing/alignment), not absolute positions. Fixed tolerance: ±2dp absolute or ±5% relative.

---

## 1. Manual Verification

**Workflow**: `view-locate --text "Element"` per element → calculate spacing from bounds → compare against spec.

**Spacing calc**: horizontal = `B.bounds[0] - A.bounds[2]`, vertical = `B.bounds[1] - A.bounds[3]`.

**Alignment check**: centerY = `(bounds[1]+bounds[3])/2`. Aligned if diff ≤ 2dp.

**Full hierarchy**: use `layout-dump` as fallback when `view-locate` cannot satisfy the need.

---

## 2. Selector & Fallback Chain

`target: {resourceId?, text?, contentDesc?, className?}` (AND logic, multi-match uses IoU).

Fallback order: `text` → `text+className` → `resourceId` → `resourceId+className` → `contentDesc+className`. Exhaust each before next. Prohibition: skipping after single failure.

---

## 3. Unsupported Properties

Use `view-inspect` for: maxLines, ellipsize, letterSpacing, lineCount, cornerRadius, tint color, custom getters.

```
jugg view-inspect --text "Title" "getMaxLines()" "getEllipsize().name()"
```

Complex backgrounds: `screenshot` visual comparison.

---

## 4. Component Checklist

| Component | Focus |
|-----------|-------|
| Grid/List | Column spacing uniformity |
| Image/Icon | Size matches design |
| Banner/Card | Margin/padding |
| Text | Position (use jugg view-locate) |
| Container | Child alignment |

---

## 5. Kuikly Notes

- Text extraction uses `KuiklyViewResolver` with special paths for `KRRichTextView`.
- Prefer `text` selector (Kuikly views may lack stable resource IDs).
- IoU matching handles Kuikly shadow view structure.
