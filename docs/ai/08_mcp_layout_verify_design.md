# MCP UI Layout Verification Design

> Last checked: 2026-08-21
> Consistency rule: when documentation conflicts with code, code is authoritative.

---

## 1. Scope

This page explains the public-tool boundary, core data flow, and easily misunderstood limits of current MCP UI verification. It does not repeat full argument tables; use [`08_mcp_tools_list.md`](08_mcp_tools_list.md), `McpToolActionRegistry.defaultActions()`, and runtime `tools/list`.

The current externally usable UI evidence chain is:

```text
activity-stack
  -> layout-dump / view-locate / view-inspect
  -> tap (when interaction is needed)
  -> wait-logs (when runtime-log closure is needed)
```

The `layout-verify` and `figma-layout-verify` action classes still exist but are not registered in `McpToolActionRegistry.defaultActions()`. They are not currently public MCP tools; do not promise direct calls in Agent flows or public tool lists.

---

## 2. Core source index

| Class/interface | File | Role |
|-----------------|------|------|
| `McpToolActionRegistry` | `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/actions/McpToolActionRegistry.kt` | Public tool registry; first place to check whether an UI tool is really callable through MCP. |
| `LayoutDumpMcpToolAction` | `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/actions/LayoutDumpMcpToolAction.kt` | Public `layout-dump`; exports HTML view-tree artifact. |
| `LayoutDumpHelper` | `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/actions/LayoutDumpHelper.kt` | Shared internal dump capability; generates public HTML and internal JSON. |
| `UiFindMcpToolAction` | `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/actions/UiFindMcpToolAction.kt` | Public `view-locate`; delegates combined selectors, visibility, and result budget to live in-app search. |
| `EvalViewMcpToolAction` | `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/actions/EvalViewMcpToolAction.kt` | Public `view-inspect`; reads getters or public fields via in-app reflection. |
| `TapMcpToolAction` | `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/actions/TapMcpToolAction.kt` | Public `tap`; supports coordinates, percentages, and element selectors. |
| `McpAppReadyGuard` | `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/actions/McpAppReadyGuard.kt` | App-online, foreground, and device-interactivity checks for runtime observe/mutate tools. |
| `ViewHierarchyClient` | `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/viewhierarchy/ViewHierarchyClient.kt` | IDE-side LocalSocket client for the in-app ViewHierarchy server. |
| `ViewHierarchyServer*` | `jvmti_agent/src/main/java/com/sickworm/intellij/jugg/viewhierarchy/` | In-app view-tree, click, and reflection-query service; `DragonflyHierarchySource` supplies a live snapshot per request for dump, selectors, tap, inspect, and verify. |
| `LayoutVerifyMcpToolAction` | `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/actions/LayoutVerifyMcpToolAction.kt` | Unregistered old batch-assertion action; historical or internal reference only. |
| `FigmaLayoutVerifyMcpToolAction` | `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/actions/FigmaLayoutVerifyMcpToolAction.kt` | Unregistered Figma relationship-verification action; see `08_mcp_figma_layout_verify_internals.md` for algorithm details. |

---

## 3. Public-tool boundary

| Tool | Current state | Suitable question | Unsuitable question |
|------|---------------|-------------------|---------------------|
| `activity-stack` | Public MCP | Is the current page in the target Activity? | Specific View properties. |
| `layout-dump` | Public MCP + CLI | Overall view tree, candidate nodes, window/dialog structure. | Direct assertions of color, font size, and other View getter properties. |
| `view-locate` | Public MCP + CLI | Element existence, bounds, size, spacing, alignment. | Internal properties such as maxLines, ellipsize, color, or corner radius. |
| `view-inspect` | Public MCP + CLI | View properties readable through getter/Kotlin property/public field, density, and hidden View properties while still in the tree. | Click coordinates or whether clicking is safe. |
| `tap` | Public MCP + CLI | Execute tap/long-press/swipe. | Replace `view-locate` as a verification tool. |
| `wait-logs` | Public MCP + CLI | App-log marker, crash, auto-run closure. | UI geometry. |
| `layout-verify` | Unregistered | Reference for old batch assertions. | Public MCP/CLI calls. |
| `figma-layout-verify` | Unregistered | Internal algorithm research. | Public MCP/CLI calls. |

---

## 4. Core data flow

### 4.1 UI evidence without Figma

```text
activity-stack
  -> confirm current page to avoid collecting evidence from a wrong Activity
layout-dump
  -> in-app ViewHierarchy LocalSocket calls Dragonfly for Android View + Compose nodes
  -> DragonflyHierarchySource adapts to existing windows/root/children JSON
  -> LayoutDumpHelper emits HTML artifact and retains internal JSON
view-locate
  -> ViewHierarchyClient requests live in-app find_elements
  -> nonempty text/resourceId/contentDesc/className fields use AND; className exactly matches full/simple name
  -> visibleOnly controls visible nodes; maxResults controls candidate budget
  -> returns matchCount/returnedCount/truncated/matches; top-level bounds/position/size only for a unique match
view-inspect
  -> ViewHierarchyClient takes a live in-app Dragonfly snapshot
  -> runs getter chain on original View for Android nodes or Dragonfly node object for Compose nodes
  -> returns expression/value/type/density and best-effort sourceFile/lineNumber
```

The Agent currently computes spacing and alignment from dp bounds returned by `view-locate`:

```text
horizontalSpacing = rightElement.left - leftElement.right
verticalSpacing   = bottomElement.top - topElement.bottom
centerX           = (left + right) / 2
centerY           = (top + bottom) / 2
```

The recommended judgment rule retains the old batch-verification tolerance: absolute difference `<= 2dp` or relative difference `<= 5%`. This is an Agent reporting convention, not a `tolerance` argument on a public tool.

### 4.2 UI evidence with Figma

```text
Structured Figma data
  -> Agent extracts expected values from the design (size, spacing, alignment, color, etc.)
  -> view-locate obtains Android actual bounds
  -> view-inspect obtains actual getter properties
  -> Agent reports expected / actual / diff / verdict
```

Do not call `figma-layout-verify` currently. To understand its experimental automatic relationship-extraction algorithm, read [`08_mcp_figma_layout_verify_internals.md`](08_mcp_figma_layout_verify_internals.md), but the public flow must still have the Agent explicitly identify where expected values came from and how they were calculated.

### 4.3 Closure after interaction

```text
tap
  -> app-ready guard checks device interactivity, target app foreground, and Activity stability
  -> perform touch by coordinate, percentage, or element
  -> activity-stack or layout-dump confirms page change
  -> wait-logs confirms marker/crash/timeout when needed
```

When element mode matches multiple nodes, `tap` does not execute. Disambiguate with a stronger selector or coordinate mode first.

---

## 5. Key models and units

| Data | Source | Unit / meaning |
|------|--------|----------------|
| `layout-dump` HTML | `LayoutDumpHelper` | Public artifact for Agent reading. |
| Internal layout JSON | `LayoutDumpHelper.dumpInternal()` | Consumed only by existing internal layout-verification actions; not a public API. |
| `view-locate.data.bounds` | `UiFindMcpToolAction` | `[left, top, right, bottom]` in dp. |
| `view-locate.data.matchCount` | In-app `find_elements` | Total selector matches; no top-level first-node coordinates when greater than one. |
| `view-locate.data.returnedCount/truncated` | In-app `find_elements` | Returned candidate count and whether `maxResults` truncated it. |
| `view-locate/view-inspect.data.source` | Dragonfly node property | Best-effort `{file?, line?}`; not currently resolved to an IDE-local absolute path. |
| `view-inspect.data.values` | `EvalViewMcpToolAction` | Raw getter values; Agent interprets and converts them. |
| `view-inspect.data.density` | In-app ViewHierarchy response | Basis for px → dp conversion. |
| Figma `dpr` | Design convention | Used only for Agent manual conversion or the unregistered internal Figma algorithm. |

---

## 6. Hidden constraints and common misreadings

| Constraint / risk | Effect |
|-------------------|--------|
| Registry is the only reliable public-capability entry point. | An action class existing does not make it callable; check `defaultActions()` / `tools/list` first. |
| `layout-dump` exposes HTML, not internal JSON. | Agents should not depend on internal JSON file paths as a stable interface. |
| ViewHierarchy is an in-app LocalSocket server-only channel. | Do not assume automatic uiautomator fallback when the socket is unavailable. |
| Dragonfly window enumeration has an old-path fallback. | If Dragonfly returns empty windows or enumeration fails, `ActivityThread` / `WindowManagerGlobal` supply root windows on a best-effort basis while Dragonfly still converts nodes; this is not a fallback to the old ViewTree data source. |
| Dragonfly uses Jugg-private packages. | Source DEX JARs undergo offline dex2jar + Jar Jar preprocessing, relocating Dragonfly API and bundled Kotlin, coroutines, Guava, and dexlib2 dependencies into `com.sickworm.intellij.jugg.internal.dragonfly.**`; both `jugg-instruments.jar` and `jugg-runtime.jar` include them. Release builds do not rename them, avoiding same-name classes in host apps. |
| Dragonfly does not depend on host Kotlin. | Kotlin and coroutine runtimes from `implementation_0.jar` are private, so a pure Java app can dump layouts; artifact validation blocks publication if the private runtime is missing. |
| Snapshot range constrains queries and actions. | The 5000-node/60-level limit applies after raw Dragonfly extraction. Selector, tap, inspect, and verify cannot access truncated nodes; `truncated:true` is unavailable if raw extraction fails first. |
| Compose action still falls back to coordinates. | Element-mode `tap` can match Compose text/virtual IDs, but currently dispatches MotionEvent only at bounds center to the owning root View; it is not equivalent to a Semantics action and cannot reliably judge disabled/stale state. |
| Compose inspect properties are limited. | Only getters exposed by the current Dragonfly node object are reflectable; Android-View-only getters return an error for that expression. |
| Compose layout-verification properties are limited. | Text, bounds, and geometric relationships work; clickable/enabled/padding/alpha/background unavailable in Dragonfly return unavailable. |
| Compose virtual IDs depend on deterministic traversal. | Stable across requests while window/child order and UI structure remain; reorder, insertion, or restructure may change IDs. |
| Dragonfly Compose depends on host Compose runtime/tooling compatibility. | Compose support is included in the new Dragonfly DEX JAR and handled locally on incompatibility; actual version coverage still needs verification in target apps. |
| `view-locate` selector is exact AND. | `className` accepts only an exact full or simple class name; do not rely on substring matching. |
| `view-locate` has a response candidate budget. | `matchCount` can exceed `returnedCount`; narrow the selector on `truncated=true`, rather than treating omitted nodes as nonexistent. |
| Multiple `view-locate` matches have no top-level coordinates. | Inspect `matches[]` and add a selector; the first node is not a stable assertion or click target. |
| `view-inspect` can read hidden nodes. | Hidden/GONE properties can be state evidence, but do not prove clickability. |
| `screenshot` action is unregistered. | Screenshot is not a default evidence source in the current public MCP flow. |
| `layout-verify` is unregistered. | Checklists and reports should use real `view-locate` / `view-inspect` output rather than old `checks[]` batch assertions. |

---

## 7. Troubleshooting entry points

| Symptom | First place to inspect |
|---------|------------------------|
| Agent claims an UI tool is callable but gets `TOOL_NOT_FOUND`. | `McpToolActionRegistry.defaultActions()` and `08_mcp_tools_list.md`. |
| `view-locate` finds no element. | Inspect in-app `find_elements` selectors, then confirm text/id/contentDesc/className and visibility in `layout-dump` HTML. |
| `view-locate` returns multiple matches or truncation. | Inspect `matchCount/returnedCount/truncated/matches[]`; add a more stable selector or raise `maxResults` up to `100`. |
| Coordinates or spacing look wrong. | Check whether bounds are already dp; px values require `view-inspect.data.density` conversion. |
| `view-inspect` getter fails. | `EvalViewMcpToolAction` allowlist and in-app `ViewExpressionEvaluator`. |
| Runtime-observe tool reports unavailable socket. | `McpAppReadyGuard`, `ViewHierarchyFailureDiagnoser`, and target-app foreground state. |
| Automatic Figma verification conflicts with public-tool behavior. | Confirm `figma-layout-verify` is still unregistered, then inspect `08_mcp_figma_layout_verify_internals.md` for algorithm issues. |

---

## 8. Related documents

- MCP tool arguments: `08_mcp_tools_list.md`.
- MCP protocol and extension rules: `08_mcp_design.md`.
- figma-layout-verify internals: `08_mcp_figma_layout_verify_internals.md`.
- UI verification checklist: `08_mcp_ui_verify_checklist.md`.
- CLI wrapper: `08_cli_tools_list.md`.
- Code paths: `98_code_map.md`.
