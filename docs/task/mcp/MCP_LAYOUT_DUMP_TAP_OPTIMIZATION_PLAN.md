# MCP layout_dump & tap Tool Optimization Plan

> Created: 2026-03-03
> Status: Pending
> Scope: layout_dump message summary / tap action expansion / description trim / matchedElement structuring

---

## Overview

Based on evaluation of `layout-dump` and `tap` MCP tools (tested against JOOX_Android), four improvements are adopted:

1. **layout_dump message summary** - Replace generic "executed successfully" with node/window stats
2. **tap action expansion** - Add `swipe` and `longPress` via `action` parameter
3. **tool description trim** - Move compression rule details to docs, keep description concise
4. **matchedElement structuring** - Return object instead of concatenated string

---

## User-Confirmed Additions (2026-03-03)

Two additional decisions are confirmed and must be implemented in this plan.

### A. `inlineContent` automatic fallback by payload size

#### Goal

Prevent large `layout-dump` payloads from inflating model context and token usage.

#### Rules

1. Add optional request param: `inlineMaxKb` (number, unit KB).
2. Default threshold: `inlineMaxKb = 16`.
3. If `contentBytes > inlineMaxKb * 1024`, force `inlineContent=false` behavior (do not return full `data.content`).
4. Suggested clamp range for `inlineMaxKb`: `4..128` (values outside range are clamped).
5. Add response telemetry fields:
   - `data.contentBytes`: raw JSON byte size
   - `data.inlineOmitted`: boolean
   - `data.inlineThresholdKb`: effective threshold used

#### Agent / Subagent guidance (for skill and usage docs)

1. Main agent default: `inlineMaxKb=16`
2. Subagent default: `inlineMaxKb=48`
3. If subtree dump is used and content is still large, subagent may increase up to `inlineMaxKb=96` case-by-case.

#### Notes

This keeps the tool backward compatible: clients still receive `data.file`; only `data.content` becomes omitted when oversized.

### B. Unify ID semantics to short ID only

#### Goal

Eliminate cross-tool mismatch where `layout-dump` exposes short IDs but `tap`/`rootLayout` expect full resource IDs.

#### Rules

1. Canonical ID format for all related MCP interactions becomes **short ID** (e.g. `content`, `user_settings`).
2. `layout_dump.root.id` remains short ID (already implemented behavior).
3. `layout_dump(rootLayout=...)` must accept short ID and match by short ID first.
4. `tap(resourceId=...)` must match short ID as primary behavior.
5. Long/full ID input is treated as compatibility fallback only during migration period, then removed in a later version.

#### Migration strategy

1. Phase 1 (compatible): short ID primary, full ID fallback.
2. Phase 2 (strict): remove full ID matching and keep short ID only.
3. Update docs and skill guidance to instruct users/agents to pass short IDs only.

---

## Item 1: layout_dump message summary

### Problem

Current message is always `"layout_dump executed successfully."` which provides zero information value. Agent must parse the full `data.content` JSON to understand page state.

### Solution

Build a summary string from the parsed JSON before returning:

```
"2 windows (top: CustomOperationalActivitiesTipDialog), 187 nodes, not truncated"
```

### Files to modify

| File | Change |
|------|--------|
| `main/.../mcp/actions/LayoutDumpMcpToolAction.kt` | Add `buildSummaryMessage()` after JSON parsing, replace hardcoded message |
| `main/.../mcp/actions/LayoutDumpMcpToolActionTest.kt` | Update assertions to verify summary content |

### Implementation detail

In `LayoutDumpMcpToolAction.layoutDumpAction()`, after `JsonParser.parseString(jsonContent)` at line ~140:

```kotlin
// Count nodes and extract summary from parsed JSON
val jsonElement = JsonParser.parseString(jsonContent)
val summary = buildSummaryMessage(jsonElement)

McpToolResult(
    status = McpToolStatus.OK,
    message = summary,  // was: "layout_dump executed successfully."
    data = mapOf(
        "file" to localJsonFile.absolutePath,
        "content" to jsonElement,
    ),
    ...
)
```

`buildSummaryMessage` logic:
1. Extract `windows` array length
2. Extract first window `title` (top window = the one Agent most likely cares about)
3. Count total nodes recursively (cap traversal at 10000 to avoid perf issue)
4. Read `truncated` boolean
5. Format: `"{windowCount} windows (top: {topTitle}), {nodeCount} nodes, {truncated ? "truncated" : "not truncated"}"`

Error path messages remain unchanged (already descriptive).

### Test plan

- Existing tests: update `Assert.assertEquals("layout_dump executed successfully.", ...)` to `Assert.assertTrue(result.message.contains("windows"))` pattern
- New test: verify summary contains window count, top window title, node count, truncated flag

---

## Item 2: tap action expansion (swipe / longPress)

### Problem

`tap` only supports click. Lists require scrolling, and long-press menus are common in Android. Agent currently has no way to trigger these interactions.

### Solution

Add `action` parameter to `tap` tool: `"tap"` (default) | `"longPress"` | `"swipe"`.

#### Parameter schema changes

```
// New parameters in inputSchema:
"action": { type: "string", enum: ["tap", "longPress", "swipe"], description: "Touch action type. Default: tap." }
"endX": { type: "number", minimum: 0, description: "End X coordinate for swipe (coordinate mode)." }
"endY": { type: "number", minimum: 0, description: "End Y coordinate for swipe (coordinate mode)." }
"endXPercent": { type: "number", minimum: 0, maximum: 100, description: "End X as screen percentage for swipe (percent mode)." }
"endYPercent": { type: "number", minimum: 0, maximum: 100, description: "End Y as screen percentage for swipe (percent mode)." }
"duration": { type: "number", minimum: 50, description: "Duration in ms. For swipe: scroll speed. For longPress: hold time. Default: swipe=300, longPress=500." }
```

### Behavioral rules

- `action` defaults to `"tap"` when omitted (backward compatible)
- `longPress`: same three modes as tap (coordinate/percent/element), uses `input swipe x y x y {duration}` for coordinate/percent, `ViewTapper.longPress(element)` for element mode
- `swipe`: requires start + end points. Modes:
  - coordinate: `(x,y)` -> `(endX,endY)`
  - percent: `(xPercent,yPercent)` -> `(endXPercent,endYPercent)`
  - element mode not supported for swipe (return `MCP_INVALID_PARAMS`)
- `swipe` without end coordinates returns `MCP_INVALID_PARAMS`

### Files to modify

| Layer | File | Change |
|-------|------|--------|
| MCP Tool (IDE side) | `main/.../mcp/actions/TapMcpToolAction.kt` | Add `action` parameter parsing, `swipeByCoordinate()`, `swipeByPercent()`, `longPressByCoordinate()`, `longPressByPercent()`, `longPressByElement()` |
| MCP Tool Schema | `TapMcpToolAction.kt` definition block | Add `action`, `endX`, `endY`, `endXPercent`, `endYPercent`, `duration` to inputSchema |
| App Server | `jvmti_agent/.../ViewHierarchyServer.java` | Add `"long_press"` action in `dispatchRequest()`, delegate to new `doLongPress()` |
| App Tapper | `jvmti_agent/.../ViewTapper.java` | Add `longPress(MatchedElement, duration)` using `ACTION_DOWN` hold + delayed `ACTION_UP` |
| VH Client | `main/.../mcp/viewhierarchy/ViewHierarchyClient.kt` | Add `findAndLongPress()` method |
| VH Protocol | `main/.../mcp/viewhierarchy/ViewHierarchyProtocol.kt` | (Reuse `FindAndTapResult` for long press - same shape) |
| Tests | `main/.../mcp/actions/TapMcpToolActionTest.kt` | Add tests for swipe/longPress modes |
| Docs | `docs/ai_knowledge/08_mcp_usage.md` | Update tap section with new action modes |

### Implementation detail - IDE side (`TapMcpToolAction.kt`)

```kotlin
override fun execute(arguments: Map<String, Any?>, runtime: IMcpRuntime): McpToolResult {
    val action = (arguments["action"] as? String) ?: "tap"
    // ... existing device resolution ...

    return when (action) {
        "tap" -> executeTap(arguments, adb, packageName, logger)       // existing logic extracted
        "longPress" -> executeLongPress(arguments, adb, packageName, logger)
        "swipe" -> executeSwipe(arguments, adb, logger)
        else -> McpToolResult(status = ERROR, errorCode = MCP_INVALID_PARAMS,
            message = "Unsupported action: $action. Use tap, longPress, or swipe.")
    }
}
```

Swipe implementation (coordinate mode):
```kotlin
private fun swipeByCoordinate(adb: IDeviceAdb, x: Int, y: Int, endX: Int, endY: Int, duration: Int): McpToolResult {
    adb.execAdbShellCmd("input swipe $x $y $endX $endY $duration")
    return McpToolResult(status = OK, message = "swipe executed successfully.",
        data = mapOf("startX" to x, "startY" to y, "endX" to endX, "endY" to endY,
            "duration" to duration, "mode" to "coordinate", "action" to "swipe"))
}
```

LongPress implementation (coordinate mode):
```kotlin
private fun longPressByCoordinate(adb: IDeviceAdb, x: Int, y: Int, duration: Int): McpToolResult {
    // adb input swipe with same start/end = long press
    adb.execAdbShellCmd("input swipe $x $y $x $y $duration")
    return McpToolResult(status = OK, message = "longPress executed successfully.",
        data = mapOf("x" to x, "y" to y, "duration" to duration, "mode" to "coordinate", "action" to "longPress"))
}
```

### Implementation detail - App side (`ViewTapper.java`)

```java
/**
 * Long press a matched element by dispatching ACTION_DOWN, holding, then ACTION_UP.
 */
public boolean longPress(MatchedElement element, long durationMs) {
    if (element == null || element.view == null) return false;
    try {
        if (element.view.isLongClickable() && element.view.performLongClick()) {
            return true;
        }
        return dispatchLongPressToRoot(element.window.rootView,
            element.centerX, element.centerY, durationMs);
    } catch (Throwable t) {
        LogUtils.e(TAG, "longPress failed", t);
        return false;
    }
}

private boolean dispatchLongPressToRoot(View root, int x, int y, long durationMs) {
    // Similar to dispatchTapToRoot but with delayed ACTION_UP
    int[] loc = new int[2];
    root.getLocationOnScreen(loc);
    float localX = x - loc[0], localY = y - loc[1];
    long downTime = SystemClock.uptimeMillis();
    MotionEvent down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, localX, localY, 0);
    MotionEvent up = MotionEvent.obtain(downTime, downTime + durationMs, MotionEvent.ACTION_UP, localX, localY, 0);
    try {
        boolean handled = root.dispatchTouchEvent(down);
        SystemClock.sleep(durationMs);
        root.dispatchTouchEvent(up);
        return handled;
    } finally {
        down.recycle();
        up.recycle();
    }
}
```

### Implementation detail - App side (`ViewHierarchyServer.java`)

Add to `dispatchRequest()` switch:
```java
case "long_press":
    JSONObject finalParamsLongPress = params;
    return runOnMainThread(() -> doLongPress(finalParamsLongPress));
```

`doLongPress()` reuses `elementFinder.find()` + `viewTapper.longPress()`.

### Test plan

| Test | Description |
|------|-------------|
| `testSwipeCoordinateMode` | swipe from (540,1800) to (540,400) with duration=300 |
| `testSwipePercentMode` | swipe from (50%,80%) to (50%,20%) |
| `testSwipeWithoutEndCoordinates` | returns MCP_INVALID_PARAMS |
| `testSwipeElementModeReturnsError` | element mode not supported for swipe |
| `testLongPressCoordinateMode` | longPress at (540,960) with default duration |
| `testLongPressPercentMode` | longPress at (50%,50%) |
| `testLongPressElementMode` | longPress by text via ViewHierarchy server |
| `testDefaultActionIsTap` | omitting action param behaves as tap (backward compat) |
| `testUnknownActionReturnsError` | action="fling" returns MCP_INVALID_PARAMS |

---

## Item 3: tool description trim

### Problem

`layout-dump` description is ~1000 tokens, `tap` ~600 tokens. Every API call sends these in context. The compression rule details (which fields are omitted when default, className stripping rules) are useful but belong in documentation, not in tool description.

### Solution

Trim descriptions to core semantics + parameter hints. Move compression details to `08_mcp_usage.md` (already partially there).

### layout_dump description: before vs after

**Before** (~1000 tokens):
```
Dump current UI hierarchy from app-side ViewHierarchy server and export JSON artifact.
Returns inline JSON in `data.content` (no extra file read needed) plus file path in `data.file`.
Optional `rootLayout` parameter: pass a node `id` value from a previous layout_dump
(e.g. "com.example:id/content") to dump only that subtree; omit for full hierarchy.
By default GONE nodes are excluded; set `isIncludeGone=true` to include them for diagnostics.
Includes INVISIBLE views (with visibility field). Server-side pruning: MAX_DEPTH=60, MAX_NODE_COUNT=5000;
if exceeded, root has "truncated":true and truncated nodes carry tag
"truncated:node_limit" or "truncated:depth_limit".
Root JSON: {windows:[{windowType, title, root:<node>}], truncated}.
**Compressed output**: default/empty fields are omitted to reduce payload size.
className uses simple class name only (package stripped).
id strips package prefix before slash (e.g. "com.example:id/btn" -> "btn").
bounds and padding use compact array format [left,top,right,bottom].
Omitted when default: id/text/contentDesc/tag (when ""),
visibility (when "visible"), alpha (when 1.0), clickable (when false),
enabled (when true), padding (when all zero), children/composeNodes (when empty).
Node fields: {className, id?, text?, contentDesc?, tag?,
bounds:[l,t,r,b], visibility?,
alpha?, clickable?, enabled?,
padding?:[l,t,r,b], children?:[], composeNodes?:[]}.
```

**After** (~400 tokens):
```
Dump current UI hierarchy from app-side ViewHierarchy server and export JSON artifact.
Returns inline JSON in `data.content` (no extra file read needed) plus file path in `data.file`.
Optional `rootLayout` parameter: pass a node `id` value from a previous layout_dump
(e.g. "com.example:id/content") to dump only that subtree; omit for full hierarchy.
Includes ALL views regardless of visibility (GONE/INVISIBLE views are present with visibility field).
Server-side pruning: MAX_DEPTH=60, MAX_NODE_COUNT=5000; if exceeded, root has "truncated":true
and truncated nodes carry tag "truncated:node_limit" or "truncated:depth_limit".
Root JSON: {windows:[{windowType, title, root:<node>}], truncated}.
**Compressed output**: default/empty fields are omitted to reduce payload size.
className strips common prefixes (android.widget., android.view., androidx.).
bounds and padding use compact array format [left,top,right,bottom].
Omitted when default: id/text/contentDesc/tag (when ""), visibility (when "visible"),
alpha (when 1.0), clickable (when false), enabled (when true), padding (when all zero),
children/composeNodes (when empty).
Node fields: {className, id?, text?, contentDesc?, tag?, bounds:[l,t,r,b], visibility?,
alpha?, clickable?, enabled?, padding?:[l,t,r,b], children?:[], composeNodes?:[]}.
```

Key changes:
- Remove `isIncludeGone` from description (parameter-level description is sufficient)
- Consolidate className/id stripping into one line
- Remove redundant "By default GONE nodes are excluded" (covered by parameter)

### tap description: before vs after

**Before** (~600 tokens):
```
Tap on target device screen. Supports three modes:
(1) coordinate mode with x+y pixel values,
(2) percent mode with xPercent+yPercent (0-100) auto-resolved to pixels,
(3) element mode with text/resourceId/contentDesc to find UI element and tap its center
(app-side atomic find_and_tap only; no legacy uiautomator fallback),
(exact match only; if multiple elements match, returns all candidates without tapping —
use coordinate or percent mode to tap the intended one).
Parameter parse priority when multiple mode parameters are provided:
coordinate > percent > element.
```

**After** (~500 tokens, including new action info):
```
Touch action on target device screen. Supports action types: tap (default), longPress, swipe.
Supports three position modes: (1) coordinate mode with x+y pixel values,
(2) percent mode with xPercent+yPercent (0-100) auto-resolved to pixels,
(3) element mode with text/resourceId/contentDesc to find UI element
(app-side atomic find_and_tap only; no legacy uiautomator fallback),
(exact match only; if multiple elements match, returns all candidates without tapping --
use coordinate or percent mode to tap the intended one).
Parameter parse priority when multiple mode parameters are provided:
coordinate > percent > element.
```

### Files to modify

| File | Change |
|------|--------|
| `main/.../mcp/actions/LayoutDumpMcpToolAction.kt` | Trim `description` string in `definition` |
| `main/.../mcp/actions/TapMcpToolAction.kt` | Update `description` string to include action types |
| `docs/ai_knowledge/08_mcp_usage.md` | Ensure compression rules are documented here (already present) |

### Test plan

No behavioral change - description is metadata only. Verify via `tools/list` that updated descriptions are returned correctly.

---

## Item 4: matchedElement structuring

### Problem

Tap element mode success returns `matchedElement` as a concatenated string:
```
"text=\"Get now\", class=\"com.tencent.wemusic.ui.widget.JXTextView\", bounds=[221,1627][858,1737]"
```

This requires string parsing if Agent wants to extract specific fields programmatically.

### Solution

Return `matchedElement` as a structured object:

```json
{
  "matchedElement": {
    "text": "Get now",
    "className": "JXTextView",
    "resourceId": "",
    "contentDesc": "",
    "bounds": [221, 1627, 858, 1737],
    "centerX": 539,
    "centerY": 1682
  }
}
```

### Backward compatibility

The `matchedElement` field type changes from `string` to `object`. This is a **breaking change** for any consumer that parses it as string. However:
- MCP tool outputs are consumed by AI agents, not programmatic parsers
- The `outputSchema` already declares `matchedElement` as `string`, which needs updating
- AI agents handle both formats gracefully

Decision: **Accept the breaking change.** Update outputSchema accordingly.

### Files to modify

| Layer | File | Change |
|-------|------|--------|
| App Server | `jvmti_agent/.../ViewHierarchyServer.java` | Change `doFindAndTap()` to put structured `matchedElement` JSON object instead of `target.describe()` string |
| App Model | `jvmti_agent/.../MatchedElement.java` | Add `toMatchedElementJson()` returning JSONObject with text/className/resourceId/contentDesc/bounds/centerX/centerY |
| VH Protocol | `main/.../mcp/viewhierarchy/ViewHierarchyProtocol.kt` | Change `FindAndTapResult.Success.matchedElement` type from `String` to a data class or `Map<String, Any>` |
| VH Client | `main/.../mcp/viewhierarchy/ViewHierarchyClient.kt` | Parse `matchedElement` as JSON object instead of string from server response |
| MCP Tool | `main/.../mcp/actions/TapMcpToolAction.kt` | Update `tapByElement()` success path to put structured object in data |
| MCP Schema | `TapMcpToolAction.kt` definition | Change `matchedElement` schema from `string` to `object` with sub-properties |
| Tests | `main/.../mcp/actions/TapMcpToolActionTest.kt` | Update `testTapElementModeUsesServerSuccess` assertions |

### Implementation detail - App side

In `ViewHierarchyServer.doFindAndTap()`, replace:
```java
data.put("matchedElement", target.describe());
```
with:
```java
data.put("matchedElement", target.toMatchedElementJson());
```

`MatchedElement.toMatchedElementJson()`:
```java
public JSONObject toMatchedElementJson() throws JSONException {
    JSONObject obj = new JSONObject();
    obj.put("text", text);
    obj.put("className", className);
    obj.put("resourceId", resourceId);
    obj.put("contentDesc", contentDesc);
    JSONArray boundsArr = new JSONArray();
    boundsArr.put(boundsLeft);
    boundsArr.put(boundsTop);
    boundsArr.put(boundsRight);
    boundsArr.put(boundsBottom);
    obj.put("bounds", boundsArr);
    obj.put("centerX", centerX);
    obj.put("centerY", centerY);
    return obj;
}
```

### Implementation detail - IDE side

In `ViewHierarchyClient.findAndTap()`, change:
```kotlin
val matchedElement = data.optStringOrNull("matchedElement").orEmpty()
```
to:
```kotlin
val matchedElement = data.optJsonObject("matchedElement")
    ?.let { parseMatchedElement(it) }
    ?: emptyMap()
```

`FindAndTapResult.Success.matchedElement` type changes to `Map<String, Any>`.

In `TapMcpToolAction.tapByElement()`:
```kotlin
"matchedElement" to serverResult.matchedElement,  // now a Map, serialized as JSON object
```

### Test plan

- Update `testTapElementModeUsesServerSuccess`: assert `matchedElement` is a Map with expected keys
- Verify JSON output contains structured object via integration test on real device

---

## Implementation order

| Phase | Item | Dependency |
|-------|------|------------|
| 1 | Item 4: matchedElement structuring | None (touches App + IDE) |
| 2 | Item 1: layout_dump message summary | None (IDE only) |
| 3 | Item 2: tap action expansion | Item 4 ideally done first (same files) |
| 4 | Item 3: description trim | Item 2 done first (tap description includes new actions) |

Phase 1+2 can run in parallel. Phase 3 depends on Phase 1. Phase 4 depends on Phase 3.

---

## Doc sync required

After all items complete, update:
- `docs/ai_knowledge/08_mcp_usage.md` - tap action modes, matchedElement format
- `docs/ai_knowledge/08_mcp_test_case.md` - new test cases for swipe/longPress/structured matchedElement
