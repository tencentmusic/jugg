# Guide: Converting Design Intent to layout_verify Assertions

Use this file when you need to translate a design spec (screenshot annotation, Figma structure, or natural-language requirement) into `layout_verify` calls. This guide covers:

1. Design intent → property/relation mapping table
2. Critical pitfalls
3. Few-shot examples (input → correct JSON)

---

## 1. Design Intent → layout_verify Mapping Table

### 1.1 Single-Element Properties (`asserts`)

| Design Intent | `property` | `op` | `value` format | `unit` | Notes |
|---------------|-----------|------|----------------|--------|-------|
| Text content is "Login" | `text` | `eq` | `"Login"` | — | Exact match; default op is `eq` |
| Text contains "MCP" | `text` | `contains` | `"MCP"` | — | Substring match |
| Text matches pattern | `text` | `matches` | `"Waiting.*done"` | — | Java regex |
| Element exists | `exists` | — | — | — | PASS if target found; no `value` needed |
| Visible | `visibility` | `eq` | `"visible"` | — | Values: `visible` / `invisible` / `gone` |
| Hidden but occupies space | `visibility` | `eq` | `"invisible"` | — | `invisible` ≠ `gone` |
| Width = 200dp | `bounds.width` | `eq` | `200` | `dp` | **Must** specify `unit:"dp"` |
| Width ≈ 200dp ± 5 | `bounds.width` | `gte` + `lte` | `195` / `205` | `dp` | Two asserts in one call; see §2 Pitfall #1 |
| Height > 0px | `bounds.height` | `gt` | `0` | `px` | `px` is default, can omit `unit` |
| Top position < 500px | `bounds.top` | `lt` | `500` | — | — |
| Clickable | `clickable` | `eq` | `true` | — | Boolean |
| Enabled | `enabled` | `eq` | `true` | — | Boolean |
| Alpha = 1.0 | `alpha` | `eq` | `1.0` | — | Float 0.0–1.0 |
| Text color = blue | `textColor` | `eq` | `"#FF1976D2"` | — | **Must** include alpha prefix `FF` |
| Text color is NOT white | `textColor` | `neq` | `"#FFFFFFFF"` | — | Negative assertion with `neq` |
| Background color (with alpha) | `backgroundColor` | `eq` | `"#1F88939B"` | — | Full `#AARRGGBB` format |
| Font size = 20sp | `textSizeSp` | `eq` | `20` | — | **Live only**; do NOT pass `dumpFile` |
| Padding left = 16dp | `padding.left` | `eq` | `16` | `dp` | Also: `padding.top` / `.right` / `.bottom` |

### 1.2 Two-Element Relations (`relations`)

| Design Intent | `type` | `direction` | Extra fields | Notes |
|---------------|--------|-------------|-------------|-------|
| Vertical gap ≈ 16dp ± 4dp | `spacing` | `vertical` | `expected:16, tolerance:4, unit:"dp"` | `tolerance` **only** works in relations, not asserts |
| Horizontal gap = 8dp | `spacing` | `horizontal` | `expected:8, tolerance:0, unit:"dp"` | — |
| Two elements horizontally centered | `alignment` | `horizontal` | — | Checks X-center alignment (perpendicular axis) |
| Two elements vertically centered | `alignment` | `vertical` | — | Checks Y-center alignment |
| A is above B | `order` | `vertical` | — | target=A, target2=B |
| A is left of B | `order` | `horizontal` | — | target=A, target2=B |
| A is inside B | `containment` | — | — | target=child, target2=parent |
| No overlap between A and B | `overlap` | — | — | PASS = no overlap |

### 1.3 Figma / Screenshot Property → layout_verify

| Figma / Screenshot Concept | layout_verify Expression | Notes |
|---------------------------|------------------------|-------|
| `fontSize: 20` | `textSizeSp`, live only | Do NOT pass `dumpFile` |
| `color: #1976D2` | `textColor: "#FF1976D2"` | Prepend `FF` for full opacity |
| `opacity: 0.5` | `alpha: 0.5` | View-level alpha |
| `background: rgba(136,147,155,0.12)` | `backgroundColor: "#1F88939B"` | Convert rgba → `#AARRGGBB` |
| `gap: 16` (between siblings) | `relation.spacing` | Use tolerance for ±N |
| `width: 200` / `height: 48` | `bounds.width` / `bounds.height` | Specify `unit:"dp"` |
| `padding: 16 12` | `padding.top:16, padding.left:12` etc. | Each side is a separate assert |
| `align: center` (horizontal) | `relation.alignment, direction:"horizontal"` | See §2 Pitfall #3 |
| `display: none` | `visibility: "gone"` | Figma hidden = Android `gone` |
| Element name / layer ID | `target.resourceId` | Requires Figma→Android ID mapping |

---

## 2. Critical Pitfalls

### Pitfall #1: `asserts` has NO `tolerance` field

**Wrong** (will be ignored or cause error):
```json
{ "asserts": [{ "property": "bounds.height", "op": "eq", "value": 220, "tolerance": 5, "unit": "dp" }] }
```

**Correct** — use `gte` + `lte` in a single call:
```json
{
  "asserts": [
    { "property": "bounds.height", "op": "gte", "value": 215, "unit": "dp" },
    { "property": "bounds.height", "op": "lte", "value": 225, "unit": "dp" }
  ]
}
```

`tolerance` is **only** available in `relations[].type="spacing"`.

### Pitfall #2: Omitting `unit: "dp"` when value is in dp

All numeric bounds/padding values default to **px**. If your design spec uses dp, you **must** pass `unit: "dp"`.

**Wrong**: `{ "property": "bounds.width", "op": "eq", "value": 200 }` — compares against px.

**Correct**: `{ "property": "bounds.width", "op": "eq", "value": 200, "unit": "dp" }`.

### Pitfall #3: `alignment.direction` semantics are counter-intuitive

- `direction: "horizontal"` → checks that X-centers are aligned (both elements are **horizontally centered** with each other).
- `direction: "vertical"` → checks that Y-centers are aligned (both elements are **vertically centered** with each other).

The direction names the **axis of alignment**, not the arrangement of elements.

### Pitfall #4: Color values must be full `#AARRGGBB`

- Design spec says `#1976D2` → layout_verify needs `"#FF1976D2"` (prepend `FF` for full opacity).
- Design spec says `rgba(136,147,155,0.12)` → convert to `"#1F88939B"` (alpha `0x1F` ≈ 12%).
- **Never** pass 6-digit hex `#RRGGBB` without the alpha prefix.

### Pitfall #5: `textColor` black may not exist in dumpFile mode

In dump JSON, black (`#FF000000`) textColor is often **omitted** as a default. If you need to assert black textColor, use **live query mode** (omit `dumpFile`).

### Pitfall #6: `textSizeSp` is live-only

`textSizeSp` is **not stored** in dump JSON. You **must not** pass `dumpFile` when asserting `textSizeSp`. The tool auto-switches to live query when `dumpFile` is omitted.

### Pitfall #7: `asserts` and `relations` can coexist in one call

A single `layout_verify` call can contain **both** `asserts` (single-element checks) and `relations` (two-element checks). They are not mutually exclusive. Use this to batch multiple checks in one call for efficiency.

### Pitfall #8: Multi-match in dumpFile mode is silent

When `target` selector matches multiple elements, dumpFile mode silently picks the **first match**. To ensure you verify the right element:
- Prefer `resourceId` (unique).
- If using `text`, add `className` to narrow down.
- If verifying a specific occurrence, use `resourceId` instead of `text`.

---

## 3. Few-Shot Examples

### Example 1: Verify button text and clickability

**Design intent**: "Login" button should display "Login" and be clickable.

```json
layout_verify({
  "projectDir": "<projectDir>",
  "dumpFile": "<dumpFile>",
  "target": { "resourceId": "btn_login" },
  "asserts": [
    { "property": "text", "op": "eq", "value": "Login" },
    { "property": "clickable", "op": "eq", "value": true }
  ]
})
```

### Example 2: Verify element height ≈ 220dp ± 5dp

**Design intent**: Scrollable area height should be approximately 220dp.

```json
layout_verify({
  "projectDir": "<projectDir>",
  "dumpFile": "<dumpFile>",
  "target": { "resourceId": "sv_scroll_area" },
  "asserts": [
    { "property": "bounds.height", "op": "gte", "value": 215, "unit": "dp" },
    { "property": "bounds.height", "op": "lte", "value": 225, "unit": "dp" }
  ]
})
```

### Example 3: Verify vertical spacing between two buttons

**Design intent**: Gap between title and first button is 16dp ± 4dp.

```json
layout_verify({
  "projectDir": "<projectDir>",
  "dumpFile": "<dumpFile>",
  "target": { "resourceId": "tv_title" },
  "relations": [
    {
      "target2": { "resourceId": "btn_first" },
      "type": "spacing",
      "direction": "vertical",
      "expected": 16,
      "tolerance": 4,
      "unit": "dp"
    }
  ]
})
```

### Example 4: Verify horizontal center alignment

**Design intent**: Two buttons should be horizontally centered with each other.

```json
layout_verify({
  "projectDir": "<projectDir>",
  "dumpFile": "<dumpFile>",
  "target": { "resourceId": "btn_a" },
  "relations": [
    {
      "target2": { "resourceId": "btn_b" },
      "type": "alignment",
      "direction": "horizontal"
    }
  ]
})
```

### Example 5: Verify text color (ARGB) and negative assertion

**Design intent**: Title text should be blue (#1976D2), NOT white.

```json
layout_verify({
  "projectDir": "<projectDir>",
  "dumpFile": "<dumpFile>",
  "target": { "resourceId": "tv_title" },
  "asserts": [
    { "property": "textColor", "op": "eq", "value": "#FF1976D2" },
    { "property": "textColor", "op": "neq", "value": "#FFFFFFFF" }
  ]
})
```

### Example 6: Verify font size (live query, no dumpFile)

**Design intent**: Title font size should be 20sp.

```json
layout_verify({
  "projectDir": "<projectDir>",
  "target": { "resourceId": "tv_title" },
  "asserts": [
    { "property": "textSizeSp", "op": "eq", "value": 20 }
  ]
})
```

Note: **No `dumpFile`** — `textSizeSp` requires live query.

### Example 7: Verify order + containment + no overlap

**Design intent**: Icon is inside container, icon is left of label, and they don't overlap.

```json
layout_verify({
  "projectDir": "<projectDir>",
  "dumpFile": "<dumpFile>",
  "target": { "resourceId": "iv_icon" },
  "relations": [
    {
      "target2": { "resourceId": "layout_container" },
      "type": "containment"
    },
    {
      "target2": { "resourceId": "tv_label" },
      "type": "order",
      "direction": "horizontal"
    },
    {
      "target2": { "resourceId": "tv_label" },
      "type": "overlap"
    }
  ]
})
```

### Example 8: Mixed asserts + relations in one call

**Design intent**: Button A should be visible, clickable, AND positioned above Button B with 12dp gap.

```json
layout_verify({
  "projectDir": "<projectDir>",
  "dumpFile": "<dumpFile>",
  "target": { "resourceId": "btn_a" },
  "asserts": [
    { "property": "visibility", "op": "eq", "value": "visible" },
    { "property": "clickable", "op": "eq", "value": true }
  ],
  "relations": [
    {
      "target2": { "resourceId": "btn_b" },
      "type": "spacing",
      "direction": "vertical",
      "expected": 12,
      "tolerance": 3,
      "unit": "dp"
    },
    {
      "target2": { "resourceId": "btn_b" },
      "type": "order",
      "direction": "vertical"
    }
  ]
})
```

---

## 4. Conversion Workflow

When converting a design spec or screenshot annotation to `layout_verify` calls:

1. **Identify target elements** — extract `resourceId` (preferred), `text`, or `contentDesc` for each element. If input is screenshot, you **must** have a `layout_dump` JSON to resolve IDs.
2. **Classify each check** — single-element property (`asserts`) or two-element relation (`relations`)?
3. **Map design values** — use §1 mapping table. Pay attention to unit (dp vs px), color format (#AARRGGBB), and live-only properties.
4. **Check pitfalls** — review §2 before finalizing. Especially: no tolerance in asserts, dp unit required, ARGB format.
5. **Batch where possible** — put multiple `asserts` and `relations` into a single call when they share the same `target`.
6. **Split when targets differ** — separate calls for different `target` elements.
