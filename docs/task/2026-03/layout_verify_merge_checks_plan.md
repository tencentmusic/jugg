# layout_verify: Merge asserts & relations into checks

> Status: Draft  
> Date: 2026-03-08

## 1. Background & Motivation

Current `layout_verify` MCP tool has two parallel input arrays:
- `asserts[]` — single-element property checks (text, visibility, bounds, etc.)
- `relations[]` — two-element spatial checks (spacing, alignment, overlap, containment, order)

This creates two problems for the calling agent:
1. **Schema cognitive load**: the agent must understand two different array schemas, decide which array a check belongs to, and correctly fill different field sets.
2. **Unnecessary structural complexity**: both arrays ultimately produce `VerifyItem(index, result, message)` and are merged into a single `items[]` result. The separation exists only in the input, not the output.

Since the MCP is **not yet published**, there are no backward-compatibility constraints.

## 2. Target Schema

Replace `asserts` + `relations` with a single `checks` array. Discriminate via `type` field.

### 2.1 Input Schema

```json
layout_verify({
  "projectDir": "<projectDir>",
  "dumpFile": "<optional>",
  "target": { "resourceId": "btn_a" },
  "checks": [
    { "type": "property", "property": "text", "op": "eq", "value": "Login" },
    { "type": "property", "property": "clickable", "op": "eq", "value": true },
    { "type": "property", "property": "bounds.height", "op": "gte", "value": 215, "unit": "dp" },
    {
      "type": "spacing",
      "target2": { "resourceId": "btn_b" },
      "direction": "vertical",
      "expected": 12, "tolerance": 3, "unit": "dp"
    },
    {
      "type": "order",
      "target2": { "resourceId": "btn_b" },
      "direction": "vertical"
    }
  ]
})
```

### 2.2 Type Value Enum

| `type` | Category | Required Extra Fields | Notes |
|--------|----------|----------------------|-------|
| `property` | Single-element | `property`, optional `op`/`value`/`unit` | Replaces current `asserts[i]` |
| `spacing` | Two-element | `target2`, `direction`, `expected`, optional `tolerance`/`unit` | From `relations[i].type=spacing` |
| `alignment` | Two-element | `target2`, `direction` | From `relations[i].type=alignment` |
| `overlap` | Two-element | `target2` | From `relations[i].type=overlap` |
| `containment` | Two-element | `target2` | From `relations[i].type=containment` |
| `order` | Two-element | `target2`, `direction` | From `relations[i].type=order` |

### 2.3 Mapping Rule

- **Property check**: `type` = `"property"`, carry `property`/`op`/`value`/`unit` fields (same as old `asserts[i]` fields).
- **Relation check**: `type` ∈ `{spacing, alignment, overlap, containment, order}`, carry `target2` + relation-specific fields (same as old `relations[i]` minus the `type` which is now top-level).

### 2.4 Output Schema (unchanged)

```json
{
  "status": "OK|ERROR",
  "data": {
    "result": "PASS|PARTIAL_FAIL|FAIL|ERROR",
    "message": "aggregated summary",
    "items": [
      { "index": 1, "result": "PASS", "message": "..." },
      { "index": 2, "result": "FAIL", "message": "..." }
    ]
  }
}
```

Output no longer separates `assertItems` / `relationItems` — just a flat `items[]`.

### 2.5 Validation Rules

1. `checks` must be non-empty.
2. Each check must have a `type`.
3. If `type` = `property`, `property` field is required.
4. If `type` ∈ `{spacing, alignment, overlap, containment, order}`, `target2` is required and must be a valid selector.
5. `tolerance` is allowed only when `type` = `spacing` (existing behavior). `type=property` with `tolerance` → ERROR with guidance message (preserved from current behavior).

## 3. Implementation Scope

### 3.1 Code Changes

#### File 1: `LayoutVerifyMcpToolAction.kt`
Path: `main/src/main/java/com/sickworm/intellij/jugg/mcp/actions/LayoutVerifyMcpToolAction.kt`

| Section | Change |
|---------|--------|
| `definition.inputSchema` | Remove `asserts` and `relations` properties. Add `checks` property with unified item schema. `type` field has enum `[property, spacing, alignment, overlap, containment, order]`. Property-specific fields (`property`, `op`, `value`, `unit`) and relation-specific fields (`target2`, `direction`, `expected`, `tolerance`, `unit`) coexist in the same item schema. |
| `definition.description` | Update to mention `checks` array instead of `asserts`/`relations`. |
| `execute()` | Parse `checks` instead of `asserts`/`relations`. Remove legacy `assert`/`relation`/`target2` single-item compat. Classify each check by `type`: `property` → property check path; others → relation check path. |
| `execute()` validation | Change empty check: `checks.isEmpty()` → error. Validate per-item: `property` type requires `property` field; relation types require valid `target2`. |
| `executeDumpFileMode()` | Receive single `checks` list. Iterate once: dispatch `type=property` items to `assertDumpNode()`, dispatch relation types to `relationDumpNodes()`. Each item gets a sequential `VerifyItem(index)`. |
| `executeLiveQueryMode()` | Same dispatch logic; build live params accordingly. |
| `executeAutoDumpMode()` | Signature: replace `asserts`+`relations` with `checks`. |
| `toAggregatedMcpResult()` | Simplify: accept single `items: List<VerifyItem>`. Remove separate `assertItems`/`relationItems` in output data. Only output `items`. |
| `isLiveOnlyAssert()` → `isLiveOnlyCheck()` | Apply only to `type=property` checks. |
| `buildLiveParams()` | For `type=property`: send `assert` to app. For relation types: send `relation` + `target2` to app. (App-side protocol unchanged.) |

#### File 2: `LayoutVerifierMcpToolActionTest.kt`
Path: `main/src/test/java/com/sickworm/intellij/jugg/mcp/actions/LayoutVerifyMcpToolActionTest.kt`

| Change |
|--------|
| Replace all `"assert" to mapOf(...)` with `"checks" to listOf(mapOf("type" to "property", ...))`. |
| Replace all `"relation" to mapOf(...)` + `"target2" to mapOf(...)` with `"checks" to listOf(mapOf("type" to "spacing", "target2" to mapOf(...), ...))`. |
| Replace all `"asserts" to listOf(...)` with `"checks" to listOf(...)` with `type=property` prepended. |
| Replace all `"relations" to listOf(...)` with `"checks" to listOf(...)` with appropriate relation type. |
| Update mixed assert+relation test: single `checks` list with both types. |
| Remove assertions on `data["assertItems"]` / `data["relationItems"]`; verify only `data["items"]`. |
| Update validation error message assertions: "assert or relation" → new message about `checks`. |

### 3.2 Document Changes

#### Doc 1: `docs/ai_knowledge/08_mcp_usage.md`
- `layout_verify` row in tool table: change "至少提供 `asserts` 或 `relations` 之一" to "至少提供一个 `checks` 条目".
- "补充（layout_verify 语义）" section: rewrite to describe `checks[]` unified structure. Remove `asserts[i]` / `relations[i]` parameter descriptions. Add `checks[i]` parameter table with `type` enum.

#### Doc 2: `docs/skills/jugg-android-dev-loop/references/guide_layout_verify_assertion.md`
- §1.1 title: "Single-Element Properties (`asserts`)" → "Single-Element Properties (`type: property`)"
- §1.2 title: "Two-Element Relations (`relations`)" → "Two-Element Relations (`type: spacing/alignment/...`)"
- All JSON examples: replace `asserts`/`relations` with `checks`.
- §2 Pitfalls: update references (e.g. Pitfall #1 "asserts has NO tolerance" → "`type=property` checks have NO tolerance").
- §4 Conversion Workflow: update step 2 wording.

#### Doc 3: `docs/skills/jugg-android-dev-loop/references/tool_cards_runtime_observe.md`
- `layout_verify` section: update "Required input" to mention `checks` instead of `asserts` and/or `relations`.
- Assert Mode / Relation Mode subsections: merge into a single "Checks" section.

#### Doc 4: `docs/skills/jugg-android-dev-loop/references/error_patterns.md`
- Update any references to `asserts`/`relations` wording.

### 3.3 App-Side: No Changes Required

`LayoutVerifier.java` receives individual `assert` / `relation` JSON objects via socket. The MCP plugin decomposes `checks[]` and sends each item to the app individually (for live mode). The app-side protocol (`target` + `assert` or `target` + `target2` + `relation`) remains unchanged.

## 4. Implementation Order

1. **`LayoutVerifyMcpToolAction.kt`** — core schema + logic change
2. **`LayoutVerifyMcpToolActionTest.kt`** — update all tests to new format
3. **Run tests** — `./gradlew :main:test --tests "*LayoutVerifyMcpToolActionTest*"` ensure all pass
4. **Update docs** — `08_mcp_usage.md`, `guide_layout_verify_assertion.md`, `tool_cards_runtime_observe.md`, `error_patterns.md`

## 5. Before/After Comparison

### Before (mixed asserts + relations)
```json
layout_verify({
  "target": { "resourceId": "btn_a" },
  "asserts": [
    { "property": "visibility", "op": "eq", "value": "visible" },
    { "property": "clickable", "op": "eq", "value": true }
  ],
  "relations": [
    {
      "target2": { "resourceId": "btn_b" },
      "type": "spacing", "direction": "vertical",
      "expected": 12, "tolerance": 3, "unit": "dp"
    },
    {
      "target2": { "resourceId": "btn_b" },
      "type": "order", "direction": "vertical"
    }
  ]
})
```

### After (unified checks)
```json
layout_verify({
  "target": { "resourceId": "btn_a" },
  "checks": [
    { "type": "property", "property": "visibility", "op": "eq", "value": "visible" },
    { "type": "property", "property": "clickable", "op": "eq", "value": true },
    { "type": "spacing", "target2": { "resourceId": "btn_b" }, "direction": "vertical", "expected": 12, "tolerance": 3, "unit": "dp" },
    { "type": "order", "target2": { "resourceId": "btn_b" }, "direction": "vertical" }
  ]
})
```

**Delta**:
- 2 arrays → 1 array
- No structural decision needed by agent (which array?)
- `type` field is always present → unambiguous dispatch
- Output `items[]` is simpler (no `assertItems`/`relationItems` separation)
