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

| Design Intent | `type` | `axis` | Extra | Notes |
|---------------|--------|--------|-------|-------|
| Gap = 16dp | `spacing` | `y`/`x` | `expected:16, op:"eq"` | ⚠️ tolerance removed |
| Gap ≥ 16dp | `spacing` | `y`/`x` | `expected:16, op:"gte"` | Use op for range |
| Gap in [12,20]dp | `spacing` | `y`/`x` | Two checks: `gte:12` + `lte:20` | ⚠️ Pitfall #1 |
| Horizontally centered | `alignment` | `x` | — | axis=x checks X-center |
| Vertically centered | `alignment` | `y` | — | axis=y checks Y-center |
| A above B | `order` | `y` | — | target=A, target2=B |
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

### #3: axis vs direction (prefer axis)

- `axis:"y"` → vertical axis (checks vertical spacing/alignment/order)
- `axis:"x"` → horizontal axis (checks horizontal spacing/alignment/order)
- `direction` is deprecated but still supported (auto-mapped to axis)

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

### Ex2: Approximate height (range check)

❌ `{ "type":"property", "property":"bounds.height", "value":220, "tolerance":5 }`

✅ `{ "checks": [{ "type":"property", "property":"bounds.height", "op":"gte", "value":215 }, { "type":"property", "property":"bounds.height", "op":"lte", "value":225 }] }`

### Ex3: Spacing (exact match)
```json
{ "checks": [{ "target2":{"resourceId":"btn_first"}, "type":"spacing", "axis":"y", "expected":16, "op":"eq" }] }
```

### Ex4: Spacing (range check)
```json
{ "checks": [
    { "target2":{"resourceId":"btn_first"}, "type":"spacing", "axis":"y", "expected":12, "op":"gte" },
    { "target2":{"resourceId":"btn_first"}, "type":"spacing", "axis":"y", "expected":20, "op":"lte" }
]}
```

### Ex5: Alignment
```json
{ "checks": [{ "target2":{"resourceId":"btn_b"}, "type":"alignment", "axis":"x" }] }
```
`axis:"x"` = checks X-center alignment (horizontal centering).

### Ex6: Color #AARRGGBB
```json
{ "checks": [
    { "type":"property", "property":"textColor", "value":"#FF1976D2" },
    { "type":"property", "property":"textColor", "op":"neq", "value":"#FFFFFFFF" }
]}
```

### Ex7: Mixed property + relation
```json
{ "checks": [
    { "type":"property", "property":"visibility", "value":"visible" },
    { "type":"property", "property":"clickable", "value":true },
    { "target2":{"resourceId":"btn_b"}, "type":"spacing", "axis":"y", "expected":12, "op":"eq" },
    { "target2":{"resourceId":"btn_b"}, "type":"order", "axis":"y" }
]}
```

### Ex8: Overlap with expectOverlap
```json
{ "checks": [
    { "target":{"resourceId":"badge"}, "target2":{"resourceId":"avatar"}, "type":"overlap", "expectOverlap":true }
]}
```
`expectOverlap:true` → PASS when elements DO overlap.

### Ex9: Containment (target=child, target2=parent)
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
