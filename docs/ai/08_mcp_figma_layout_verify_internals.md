# figma-layout-verify Internals

> Last checked: 2026-05-23
> Consistency rule: when documentation conflicts with code, code is authoritative.

---

## 1. Scope

This page explains only the Figma JSON parsing, relationship extraction, IoU matching, and tolerance-verification algorithms in the Kotlin implementation of `figma-layout-verify`.

Current boundary: the `FigmaLayoutVerifyMcpToolAction` class exists but is not registered in `McpToolActionRegistry.defaultActions()`, so it is not currently a public MCP tool. See [`08_mcp_tools_list.md`](08_mcp_tools_list.md) and runtime `tools/list` for public tools.

---

## 2. Core source index

| Class | File | Role |
|-------|------|------|
| `FigmaLayoutVerifyMcpToolAction` | `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/actions/FigmaLayoutVerifyMcpToolAction.kt` | Experimental action: reads Figma JSON, internally dumps Android layout, and calls verifier for a report. |
| `LayoutDumpHelper` | `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/actions/LayoutDumpHelper.kt` | Generates internal Android-layout JSON for matching actual nodes. |
| `FigmaLayoutVerifier` | `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/layout/FigmaLayoutVerifier.kt` | Algorithm orchestration: parse → extract → match → verify. |
| `FigmaJsonParser` | `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/layout/parser/FigmaJsonParser.kt` | Recognizes Figma JSON formats and parses a `FigmaNode` tree. |
| `RelationExtractor` | `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/layout/extractor/RelationExtractor.kt` | Extracts spacing/alignment relationships from the Figma node tree. |
| `ElementMatcher` | `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/layout/matcher/ElementMatcher.kt` | Normalizes Figma and Android nodes to 1000x1000, then matches by IoU. |
| `RelationVerifier` | `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/layout/verifier/RelationVerifier.kt` | Verifies spacing and alignment against fixed tolerances. |
| `FigmaNode` / `AndroidNode` / `Relation` / `VerifyResult` | `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/layout/model/*` | Algorithm data models. |

---

## 3. Core data flow

```text
FigmaLayoutVerifyMcpToolAction.execute()
  -> validate figmaJsonPath and read dpr (default 1.0)
  -> LayoutDumpHelper.dumpInternal()
       produce internal Android-layout JSON; return dump error directly on failure
  -> FigmaJsonParser.validate()
       validate root-node format only; return INVALID_FIGMA_FORMAT if invalid
  -> FigmaLayoutVerifier.verify()
       parse Figma JSON
       extract spacing/alignment relations
       IoU-match endpoints of each relation
       verify actual relations using Android dp bounds
  -> structuredContent.data.results
```

App-side `jvmti_agent/.../LayoutVerifier.java` belongs to the old `layout-verify` / ViewHierarchy-server direction. `figma-layout-verify` relationship extraction and verification run in IDE-side Kotlin.

---

## 4. Figma JSON parsing

`FigmaJsonParser.parse()` accepts three input formats:

| Format | Detection condition | Root node |
|--------|---------------------|-----------|
| Direct node | `json.has("id") && (json.has("layout") || json.has("bounds"))` | JSON itself. |
| Nodes wrapper | `json.has("nodes")` | `nodes.entrySet().first().value`. |
| Document wrapper | `json.has("document")` | `document.children[0]`. |

Bounds rules:

| Field | Input meaning | Parsed result |
|-------|---------------|---------------|
| `layout` | `[x, y, width, height]` | `[x, y, x + width, y + height]`. |
| `bounds` | `[left, top, right, bottom]` | Used unchanged. |

`flattenNodes()` flattens the tree with preorder DFS, retaining container and leaf nodes. Later spacing extraction scans only adjacent indices of this flattened list, so Figma hierarchy order directly affects relationship coverage.

---

## 5. Relationship extraction

`RelationExtractor` determines relationships in Figma pixel space, then divides spacing expected values by `dpr` to obtain dp.

### 5.1 spacing

Only adjacent nodes `(nodes[i], nodes[i + 1])` in the flattened list are checked.

Horizontal adjacency:

```text
tolerance = (20 * dpr).toInt()
abs(node1.top - node2.top) < tolerance
AND node2.left >= node1.right
expected = ((node2.left - node1.right) / dpr).toInt()
axis = "x"
```

Vertical adjacency:

```text
tolerance = (20 * dpr).toInt()
abs(node1.left - node2.left) < tolerance
AND node2.top >= node1.bottom
expected = ((node2.top - node1.bottom) / dpr).toInt()
axis = "y"
```

Implementation uses `toInt()` to truncate fractional values, not rounding.

### 5.2 alignment

Bucket nodes by top / left coordinate; a bucket with at least two nodes produces one alignment relation.

```text
tolerance = (5 * dpr).toInt()
yBucket = (top / tolerance) * tolerance
xBucket = (left / tolerance) * tolerance
```

| Bucket key | axis | Verification meaning |
|------------|------|----------------------|
| `top` | `y` | Are multiple node centerY values aligned? |
| `left` | `x` | Are multiple node centerX values aligned? |

Note that bucketing uses top/left but verification uses centers. This can reduce the effect of simple size differences, but nodes with near-equal tops and substantially different centers can fail in verification.

---

## 6. Element matching

`ElementMatcher` ignores name, text, and resourceId; only relative bounds position and size count.

```text
normalized.left   = bounds.left   / screenWidth  * 1000
normalized.top    = bounds.top    / screenHeight * 1000
normalized.right  = bounds.right  / screenWidth  * 1000
normalized.bottom = bounds.bottom / screenHeight * 1000
```

Screen-size sources:

| Side | Source | Unit |
|------|--------|------|
| Figma | Root `layout[2], layout[3]` or `bounds[2], bounds[3]`. | Figma px. |
| Android | `deviceInfo.screenWidth/screenHeight` in internal `layout-dump` JSON. | dp. |

IoU matching:

```text
iou = intersectArea / (area1 + area2 - intersectArea)
match if iou > 0.7
```

The highest-IoU Android node becomes matched, with at most three alternatives retained.

---

## 7. Relationship verification

`AndroidNode.bounds` are already dp, converted from px by IDE-side `layout-dump`.

### 7.1 spacing

Actual values:

```text
axis=x: actual = element2.left - element1.right
axis=y: actual = element2.top  - element1.bottom
diff = actual - expected
```

Pass condition:

```text
abs(diff) <= 2
OR abs(diff) / expected <= 0.05
```

Counterintuitive implementation details:

- With `expected == 0`, percentage difference is 0, so the percentage condition can pass even when absolute tolerance fails; this is current code behavior.
- With `expected < 0`, percentage difference is negative and also meets `<= 0.05`; overlapping relationships may therefore be too permissive.

### 7.2 alignment

```text
axis=x: centerX = (left + right) / 2
axis=y: centerY = (top + bottom) / 2
maxDiff = max(center) - min(center)
pass if maxDiff <= 2
```

---

## 8. Unit transitions

| Stage | Figma side | Android side |
|-------|------------|--------------|
| After JSON parsing | Figma px | dp |
| spacing expected | Figma px / dpr → dp | — |
| IoU matching | Figma px / canvas size → 1000 space | dp / screen dp → 1000 space |
| Relationship verification | expected dp | actual dp |

---

## 9. Hidden constraints and limitations

| Constraint / limitation | Effect |
|-------------------------|--------|
| Action absent from `defaultActions()`. | Do not promise direct `figma-layout-verify` calls in public MCP/CLI docs. |
| Spacing sees only DFS-flattened adjacent nodes. | Visually related but nonadjacent gaps can be missed. |
| Alignment buckets by top/left, then verifies centers. | It may extract alignment that ultimately fails. |
| Fixed IoU threshold `> 0.7`. | Overlapping containers, FrameLayout, or similarly sized nodes may mismatch. |
| Matching ignores semantics. | Element name, text, and resourceId do not participate. |
| Color, font size, and corner radius are unverified. | Use public `view-inspect` for these properties; use `view-locate` for position and size. |
| Spacing percentage tolerance divides by raw `expected`. | `expected <= 0` produces results unlike ordinary percentage-tolerance intuition. |

---

## 10. Troubleshooting entry points

| Symptom | First place to inspect |
|---------|------------------------|
| Tool cannot be called through MCP. | `McpToolActionRegistry.defaultActions()` to confirm registration. |
| Figma JSON rejected. | `FigmaJsonParser.validate()`. |
| Missing spacing relationship. | `RelationExtractor.extractSpacingRelations()` and Figma flattening order. |
| Too many alignment relationships. | `RelationExtractor.extractAlignmentRelations()`'s `5 * dpr` bucket. |
| Element matched to the wrong Android View. | Normalized bounds and IoU score in `ElementMatcher.match()`. |
| Spacing diff looks unreasonable. | dp actual/expected and `dpr` in `RelationVerifier.verifySpacing()`. |

---

## 11. Related documents

- MCP design: `08_mcp_design.md`.
- MCP tool arguments: `08_mcp_tools_list.md`.
- UI layout-verification design: `08_mcp_layout_verify_design.md`.
- UI verification checklist: `08_mcp_ui_verify_checklist.md`.
- Code paths: `98_code_map.md`.
