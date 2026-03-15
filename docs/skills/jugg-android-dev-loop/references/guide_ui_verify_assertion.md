# Guide: UI Verification Assertion Reference

> Tools: `figma_layout_verify` (batch), `ui_find` (single query), `eval_view` (unsupported properties)
> Design details: `docs/ai_knowledge/08_mcp_design.md §7`

## ⚠️ Execution Mode

**Sub-Agent Mode** (recommended if available):
- Spawn sub-agent to execute this document's workflow
- Output structured Report and exit

**Main Agent Mode** (fallback):
- Follow §2 (with Figma) or §3 (without Figma)
- Screenshot inspection MUST NOT replace tool verification

## 1. Core Principle

**New approach: Verify relative relations, not absolute positions.**

- **Why**: Screen ratios differ; absolute bounds are not comparable. Relative relations (spacing/alignment) are the essence of layout.
- **MCP auto-extracts**: Agent only provides `figmaJson + dpr`; MCP extracts all relations and validates automatically.
- **IoU matching**: Solves Figma-Android structure mismatch by flattening trees and using IoU for element matching.

**Fixed tolerance (not configurable)**:
- Absolute: ±2dp
- Relative: ±5%
- Logic: `absDiff <= 2dp OR percentDiff <= 5%`

## 2. With Figma JSON: Batch Verification

### 2.1 Workflow

```
1. Ensure correct page: activity_stack → confirm page
2. Call: figma_layout_verify(figmaJson="design.json", dpr=<value>)
3. Analyze results: check summary, review failed items and unmatched nodes
4. Fix issues: modify code based on diff details
5. Re-verify: repeat step 2 until all pass
```

### 2.2 dpr Selection

| Figma Canvas Width | dpr | Description |
|-------------------|-----|-------------|
| 750px | 2 | 2x pixel design |
| 375px | 1 | dp unit design |
| 1125px | 3 | 3x pixel design |
| 411px | 1 | Android dp design |

**Rule**: `actual_dp = figma_px / dpr`

### 2.3 Return Structure

```json
{
  "status": "OK",
  "data": {
    "summary": {"total": 15, "passed": 12, "failed": 3},
    "results": [
      {
        "type": "spacing",
        "element1": {"figmaId": "34:12200", "androidSelector": {"text": "Avatar"}},
        "element2": {"figmaId": "34:12202", "androidSelector": {"text": "App"}},
        "axis": "x",
        "expected": "18dp",
        "actual": "14dp",
        "match": false,
        "diff": "-4dp"
      },
      {
        "type": "alignment",
        "elements": [...],
        "axis": "y",
        "expected": "centerY aligned",
        "actual": "centerY: [293, 293, 293, 293]",
        "match": true
      }
    ],
    "unmatched": [
      {"figmaId": "34:12179", "figmaName": "Group 1912055492", "reason": "No similar element found"}
    ]
  }
}
```

### 2.4 Interpreting Results

**summary**: Quick pass/fail overview.

**results[]**: Each extracted relation with:
- `type`: spacing | alignment
- `element1/element2`: Figma ID + matched Android selector
- `axis`: x (horizontal) | y (vertical)
- `expected`: value from Figma
- `actual`: value from Android layout
- `match`: true/false
- `diff`: deviation when failed

**unmatched[]**: Figma nodes that couldn't be matched to Android Views (may be decorative or structural differences).

### 2.5 Partial Verification

To verify only specific nodes:

```json
{
  "figmaJson": "design.json",
  "dpr": 1,
  "targetNodes": ["34:12200", "34:12202"]
}
```

## 3. Without Figma: Manual Verification

### 3.1 Workflow

```
1. Screenshot: identify suspicious areas
2. Query: ui_find(target={text:"Element"}) for each element
3. Calculate: compute spacing/alignment from bounds
4. Compare: check against design spec or code expectations
5. Report: document findings
```

### 3.2 ui_find Usage

**Request**:
```json
{
  "projectDir": "/path/to/project",
  "target": {"text": "Avatar"}
}
```

**Response**:
```json
{
  "status": "OK",
  "data": {
    "matched": {
      "selector": {"text": "Avatar", "className": "KRView"},
      "bounds": [16, 278, 67, 324],
      "position": {"left": "16dp", "top": "278dp", "centerX": "41.5dp", "centerY": "301dp"},
      "size": {"width": "51dp", "height": "46dp"}
    },
    "confidence": 0.92
  }
}
```

### 3.3 Manual Spacing Calculation

Given two elements A and B:

```
Horizontal spacing (x-axis): B.bounds[0] - A.bounds[2]
Vertical spacing (y-axis): B.bounds[1] - A.bounds[3]
```

Example:
```
ui_find(target={text:"Avatar"}) → bounds=[16, 278, 67, 324]
ui_find(target={text:"App"}) → bounds=[85, 278, 120, 324]
Horizontal spacing = 85 - 67 = 18dp
```

### 3.4 Manual Alignment Check

**Horizontal center alignment (y-axis)**:
```
centerY_A = (bounds[1] + bounds[3]) / 2
centerY_B = (bounds[1] + bounds[3]) / 2
Aligned if |centerY_A - centerY_B| <= 2dp
```

**Vertical center alignment (x-axis)**:
```
centerX_A = (bounds[0] + bounds[2]) / 2
centerX_B = (bounds[0] + bounds[2]) / 2
Aligned if |centerX_A - centerX_B| <= 2dp
```

## 4. Selector & Fallback Chain

`target: {resourceId?, text?, contentDesc?, className?}` (AND logic, multi-match uses IoU to pick best)

**Fallback order** (exhaust each before next):
1. `text` (most reliable for Kuikly)
2. `text` + `className`
3. `resourceId`
4. `resourceId` + `className`
5. `contentDesc` + `className`

**Prohibition**: skipping check after single selector failure.

## 5. Unsupported Properties

Use `eval_view` for: maxLines, ellipsize, letterSpacing, lineCount, cornerRadius, tint color, custom getters.

Complex backgrounds: `screenshot` visual comparison.

**eval_view usage**:
```json
{
  "projectDir": "/path/to/project",
  "target": {"text": "Title"},
  "expressions": ["getMaxLines()", "getEllipsize().name()"]
}
```

## 6. Component Type Checklist

| Component | Verification Focus |
|-----------|-------------------|
| Grid/List | Column spacing uniformity |
| Image/Icon | Size matches design |
| Banner/Card | Margin/padding spacing |
| Text | Position, not content (use ui_find) |
| Container | Child alignment |

## 7. Verification Report Format

After executing verification, produce structured report:

```markdown
# UI Verification Report
Page: [Page Name]
Design Source: [Figma URL or file]
Device: [Device info]
Timestamp: [ISO datetime]

## Summary
| Total | ✅ PASS | ❌ FAIL | ⚠️ UNMATCHED |
|-------|---------|---------|--------------|
| 15    | 12      | 3       | 2            |

## Failed Items
| # | Type | Elements | Expected | Actual | Diff |
|---|------|----------|----------|--------|------|
| 1 | spacing | Avatar → App | 18dp | 14dp | -4dp |

## Unmatched Nodes
| Figma ID | Name | Reason |
|----------|------|--------|
| 34:12179 | Group 1912055492 | No similar element |

## Evidence
Screenshot: ${projectDir}/build/mcp_fetch/final/final_screenshot.png
```

## 8. Deprecated Tools

| Tool | Status | Replacement |
|------|--------|-------------|
| `layout_verify` | **DEPRECATED** | `figma_layout_verify` + `ui_find` |

Do NOT use `layout_verify` — it has been replaced by the new tool system.

## 9. Kuikly Framework Notes

For Kuikly apps:
- Text extraction uses `KuiklyViewResolver` with special paths for `KRRichTextView`
- Prefer `text` selector over `resourceId` (Kuikly views may not have stable resource IDs)
- IoU matching handles Kuikly's shadow view structure
