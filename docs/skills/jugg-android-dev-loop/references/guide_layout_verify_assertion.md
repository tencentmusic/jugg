# Guide: Design Intent → layout_verify Assertions

> For schema, supported properties, and result format, see `tool_cards_runtime_observe.md §layout_verify`.

## 1. Mapping Tables

### 1.1 Properties (`type: property`)

| Design Intent | `property` | `op` | `value` | Notes |
|---------------|-----------|------|---------|-------|
| Text is "Login" | `text` | `eq` | `"Login"` | |
| Text contains "MCP" | `text` | `contains` | `"MCP"` | |
| Text matches regex | `text` | `matches` | `"Waiting.*done"` | Java regex |
| Element exists | `exists` | — | — | |
| Visible / invisible / gone | `visibility` | `eq` | `"visible"` | |
| Width ≈ 200dp ± 5 | `bounds.width` | `gte`+`lte` | `195`/`205` | ⚠️ Pitfall #1 |
| Color = blue | `textColor` | `eq` | `"#FF1976D2"` | ⚠️ Must #AARRGGBB |
| Color ≠ white | `textColor` | `neq` | `"#FFFFFFFF"` | |
| backgroundColor (solid) | `backgroundColor` | `eq` | `"#FF1976D2"` | ⚠️ Live-only; solid color only |
| Font size = 20sp | `textSizeSp` | `eq` | `20` | ⚠️ Live-only |
| Clickable / enabled | `clickable` | `eq` | `true` | Boolean |
| Alpha | `alpha` | `eq` | `1.0` | All 6 ops supported (epsilon=0.001) |
| Padding left = 16dp | `padding.left` | `eq` | `16` | |

### 1.2 Relations

| Design Intent | `type` | `direction` | Extra | Notes |
|---------------|--------|-------------|-------|-------|
| Gap ≈ 16dp ± 4 | `spacing` | `vertical`/`horizontal` | `expected:16, tolerance:4` | tolerance only here |
| Horizontally centered | `alignment` | `vertical` | — | ⚠️ vertical → checks **X**-center |
| Vertically centered | `alignment` | `horizontal` | — | ⚠️ horizontal → checks **Y**-center |
| A above B | `order` | `vertical` | — | target=A, target2=B |
| A inside B | `containment` | — | — | ⚠️ target=child, target2=parent |
| No overlap | `overlap` | — | — | ⚠️ PASS = **no** overlap |

### 1.3 Figma → layout_verify

| Figma Concept | Expression | Notes |
|--------------|-----------|-------|
| `color: #1976D2` | `textColor: "#FF1976D2"` | Prepend `FF` |
| `fontSize: 20` | `textSizeSp` | Live-only |
| `background: rgba(...)` | ❌ | Screenshot fallback |
| `gap: 16` | `type=spacing` | |
| `align: center` (horiz) | `alignment, direction:"vertical"` | Pitfall #3 |
| `display: none` | `visibility: "gone"` | |

---

## 2. Pitfalls

### #1: No tolerance in type=property

❌ `{ "type":"property", "property":"bounds.height", "value":220, "tolerance":5 }`
✅ Use two checks: `op:"gte", value:215` + `op:"lte", value:225`

### #2: All numeric values are dp

Auto px→dp conversion. If spec gives px: `dp = px / density`.

### #3: alignment.direction is counter-intuitive

- `direction:"vertical"` → checks **X**-center (horizontal centering)
- `direction:"horizontal"` → checks **Y**-center (vertical centering)

### #4: textColor must be #AARRGGBB

`#1976D2` → `"#FF1976D2"`. `rgba(136,147,155,0.12)` → `"#1F88939B"`.

### #5: Black textColor may be omitted in dump

Dump omits `#FF000000`. Use live query if asserting black.

### #6: textSizeSp is live-only

Auto-switches to live query when used.

### #7: Mix property + relation in one checks[]

Batch both types in a single call.

### #8: Multi-match picks first silently

Prefer `resourceId`. Add `className` to narrow `text` matches.

### #9: backgroundColor not supported

Use `screenshot` + visual comparison as fallback.

### #10: alpha supports all 6 ops

All standard ops (`eq`/`neq`/`gt`/`lt`/`gte`/`lte`) work correctly for `alpha`.
Comparison uses epsilon=0.001 for floating-point tolerance.

---

## 3. Examples

### Ex1: Property checks (text + clickable)
```json
{ "checks": [
    { "type":"property", "property":"text", "value":"Login" },
    { "type":"property", "property":"clickable", "value":true }
]}
```

### Ex2: Approximate height (Pitfall #1)

❌ `{ "type":"property", "property":"bounds.height", "value":220, "tolerance":5 }`

✅ `{ "checks": [{ "type":"property", "property":"bounds.height", "op":"gte", "value":215 }, { "type":"property", "property":"bounds.height", "op":"lte", "value":225 }] }`

### Ex3: Spacing
```json
{ "checks": [{ "target2":{"resourceId":"btn_first"}, "type":"spacing", "direction":"vertical", "expected":16, "tolerance":4 }] }
```

### Ex4: Alignment (Pitfall #3)
```json
{ "checks": [{ "target2":{"resourceId":"btn_b"}, "type":"alignment", "direction":"vertical" }] }
```
`direction:"vertical"` = checks X-center alignment.

### Ex5: Color #AARRGGBB (Pitfall #4)
```json
{ "checks": [
    { "type":"property", "property":"textColor", "value":"#FF1976D2" },
    { "type":"property", "property":"textColor", "op":"neq", "value":"#FFFFFFFF" }
]}
```

### Ex6: Mixed property + relation
```json
{ "checks": [
    { "type":"property", "property":"visibility", "value":"visible" },
    { "type":"property", "property":"clickable", "value":true },
    { "target2":{"resourceId":"btn_b"}, "type":"spacing", "direction":"vertical", "expected":12, "tolerance":3 },
    { "target2":{"resourceId":"btn_b"}, "type":"order", "direction":"vertical" }
]}
```

### Ex7: Overlap with expectOverlap
```json
{ "checks": [
    { "target":{"resourceId":"badge"}, "target2":{"resourceId":"avatar"}, "type":"overlap", "expectOverlap":true }
]}
```
`expectOverlap:true` → PASS when elements DO overlap.

### Ex8: Containment (target=child, target2=parent)
```json
{ "checks": [{
    "target": {"resourceId": "icon_avatar"},
    "target2": {"resourceId": "container_header"},
    "type": "containment"
}]}
```
Verifies `icon_avatar` (child) is fully inside `container_header` (parent).

---

## 4. When to use `eval_view` instead

`layout_verify` covers the most common UI assertions. For properties it does **not** support, use `eval_view` to reflectively query View getters.

| Property | `layout_verify` | `eval_view` expression |
|----------|-----------------|----------------------|
| maxLines | ❌ | `getMaxLines()` |
| ellipsize | ❌ | `getEllipsize().name()` |
| letterSpacing | ❌ | `getLetterSpacing()` |
| lineCount | ❌ | `getLineCount()` |
| cornerRadius | ❌ | `getBackground().getCornerRadius()` |
| tint color | ❌ | `getBackgroundTintList().getDefaultColor()` |
| textSize (px) | ✅ `textSizeSp` (sp) | `getTextSize()` (px, use `data.density` to convert) |
| textColor | ✅ | `getCurrentTextColor()` |
| custom View getter | ❌ | `getCustomProperty()` |

**Key difference**: `layout_verify` returns PASS/FAIL verdict; `eval_view` returns raw values — Agent must interpret.
