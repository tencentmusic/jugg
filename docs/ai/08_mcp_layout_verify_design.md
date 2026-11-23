# MCP UI Layout Verification

> Last checked: 2026-10-07
> If this page conflicts with implementation, follow the code.

## Public boundary and evidence chain

Check the connected Runtime's `tools/list` before planning UI verification. `McpToolActionRegistry.defaultActions()` registers the IDEA UI actions, but `McpToolRegistry` filters both listing and dispatch by Runtime capabilities. The standalone Runtime currently exposes no UI observation or interaction actions. An action class, name constant, or CLI command does not itself establish availability. In particular, `layout-verify`, `figma-layout-verify`, and `screenshot` are absent from default registration and are not public verification tools.

For a Runtime that exposes them, the evidence chain is `activity-stack → layout-dump → view-locate / view-inspect → tap (if interaction is needed) → fresh observation → wait-logs (if log closure is needed)`. All project tools require `projectDir`, and these UI tools require one device: an explicit `serial` selects exactly one online device; multiple implicit targets produce `MULTIPLE_DEVICE`. The current argument and response contract is in `08_mcp_tools_list.md`.

| Question | Public evidence |
|---|---|
| Is this the intended Activity or window? | `activity-stack`, then `layout-dump --mode ui` JSON. |
| Does a node exist, and where is it? | `view-locate` live selector and dp bounds. |
| What property does its runtime object expose? | `view-inspect` read-only getter/field expressions. |
| Did an interaction change the page? | `tap`, followed by a new `activity-stack` or layout query. |
| Did a marker, crash, or timeout occur? | `wait-logs` when runtime-log closure matters. |

Figma or another design source supplies expected values. The Agent records that source, derives size/spacing/alignment, and compares it with current MCP evidence. The old `layout-verify` and `figma-layout-verify` implementations remain internal references; `08_mcp_figma_layout_verify_internals.md` explains their algorithm without making them callable.

## In-app ViewHierarchy boundary

`LayoutDumpHelper` and `UiFindMcpToolAction` / `EvalViewMcpToolAction` / element-mode `TapMcpToolAction` use `ViewHierarchyClient` to reach the app's LocalSocket `ViewHierarchyServer`. Each request captures a fresh `DragonflyHierarchySource` snapshot covering Android View and supported Compose nodes. There is no automatic uiautomator fallback when the socket is unavailable. If Dragonfly window enumeration is empty or fails, reflected `ActivityThread` / `WindowManagerGlobal` roots are a best-effort window-list fallback; Dragonfly still converts nodes. A failure during raw extraction cannot provide a reliable `truncated` flag.

The in-app normalized snapshot caps traversal at 60 levels and 5000 nodes. Dump, selector, tap, and inspect cannot see nodes outside that range. Compose virtual IDs are traversal-dependent: reuse them only while window and child ordering and UI structure are stable. Element-mode Compose taps dispatch a touch at the owning root's bounds center, not a Semantics action; stale or disabled state is not guaranteed to be recognized. Inspect on Compose reflects the Dragonfly node object, so Android-View-only getters may yield per-expression errors. Dragonfly and private Kotlin/coroutine dependencies are relocated into Jugg's namespace; a host app need not provide Kotlin, while Compose runtime/tooling incompatibility can still limit extraction.

`layout-dump` publishes a sparse JSON artifact and summary. Default interaction mode prunes structural/hidden/off-screen descendants; ui mode preserves the collected tree. Internal layout consumers use unpruned `LayoutDumpHelper.dumpInternal()` JSON. Bounds and padding exported by dump, and bounds returned by `view-locate`, are integer dp obtained from pixel coordinates and density. `view-inspect.data.density` supports conversion of raw pixel-valued getters.

## Selector, result, and judgment rules

`view-locate` combines nonempty `text`, `resourceId`, `contentDesc`, and `className` selectors with AND. Text, ID, and description are exact; class accepts an exact full or simple name. `visibleOnly` defaults to true. `maxResults` is 1–100 and defaults to 10. The response separates total `matchCount` from `returnedCount` and `truncated`; top-level bounds/position/size appear only for a unique match. On multiple matches, inspect `matches[]` and narrow the selector. On truncation, omitted candidates are not evidence of absence. Best-effort `source.file` / `source.line` are hints, not guaranteed IDE-local paths.

`view-inspect` requires a selector and 1–20 read-only expressions. It rejects multiple matched nodes rather than picking one. Its `data.values[]` carries each expression's value, type, or error; the whole tool can return OK even when individual expressions fail. Hidden nodes still in the tree may be inspected but are not safe click targets. Getter output is raw: identify the property and unit before comparing it with design values.

Calculate geometric facts from unique dp bounds, for example horizontal gap = right.left − left.right and centerX = (left + right) / 2. Convert design px using its stated DPR, and runtime px using `view-inspect.data.density`. A report may use ≤2 dp absolute or ≤5% relative difference as an explicit Agent convention; no public tool accepts a `tolerance` argument. For colors, record source format and retain alpha when presenting `#AARRGGBB`. When a claim is not observable with public tools, report it as unverified rather than inferring it from a pruned tree or a tap result.

`tap` has coordinate, percent, and element modes. Element mode requires a unique match; several matches fail without a tap. For absolute coordinates from a dump, pass `units.bounds` as the tap unit. Use `dp` for `view-locate` bounds. A single unit covers both endpoints of an absolute swipe. After interaction, take fresh page evidence. App-ready checks and a transient observe retry do not guarantee that an unavailable ViewHierarchy socket will recover; diagnose app foreground/instrumentation and follow the recovery guidance in `08_mcp_tools_list.md`.

## Diagnosis

| Symptom | First boundary |
|---|---|
| `TOOL_NOT_FOUND` or missing UI tool | Current `tools/list`, `McpToolRegistry` capabilities, then action registration. |
| No or several selector matches | `layout-dump --mode ui` JSON, exact selector fields, visibility, `matches[]`, and response budget. |
| Socket unavailable | `McpAppReadyGuard`, `ViewHierarchyFailureDiagnoser`, target app foreground and instrumented server. |
| Wrong spacing or property verdict | dp versus px/DPR, unique node match, and per-expression error. |
| A Figma automation result conflicts with public behavior | Confirm the action remains unregistered; inspect `08_mcp_figma_layout_verify_internals.md` only for its internal algorithm. |

For a field-ready evidence checklist, use `08_mcp_ui_verify_checklist.md`.

### Sparse layout artifact contract

`layout-dump` accepts mode=interaction (default) or ui; other values fail INVALID_PARAMS. JSON at data.file preserves mode/units/windows/deviceInfo/truncated and optional warnings/rootLayout, with truncated/deviceInfo first when present. Files ≤16,384 UTF-8 bytes are also returned completely as structuredContent.data.layout; text content remains summary-only to avoid duplication. Larger files retain complete file only, with no partial inline preview. contentBytes measures file bytes, not envelope/token size. Real tree nodes get newlines without indentation; coordinates/attributes stay compact and jugg_details pseudo-nodes inline. Prefer inline layout, otherwise read relevant file lines or narrow rootLayout/view-locate.

ui retains the collected tree. interaction first removes invisible/gone subtrees even with includeGone, omits padding/textColor, then promotes children of nonroot nodes lacking real ID/text/description/clickable/message. Outside a valid window, preserve the first wholly off-screen root and replace its descendants with a jugg_details omission pseudo-child; do not count it as node/clickable. Do not collapse partial/zero-area/unknown-geometry/diagnostic branches or branches with intersecting descendants. No valid viewport or a rootLayout subtree disables off-screen inference. ui/rootLayout can recover omitted descendants; scroll then observe again. Pruning never changes budget truncated and summary counts actual output.

Both modes omit empty/default fields (visible, alpha=1, clickable=false, enabled=true, zero padding, empty children); missing textColor does not uniquely mean black. Public `_vid_<hash>` maps back to runtime `_vir_id_<hash>` at the selector boundary; both selectors work, and only unchanged hierarchy/order supports identity across requests. Bounds/padding are integer dp when density is valid, otherwise unchanged px with explicit units. Mode does not enlarge capture/windows/budget and cannot reproduce pixels or prove absence/true ancestry from pruned output.

The interaction response compares shown and captured node counts from the same request. Before dump and selector snapshots are built, `ComposeSemantics` joins host merged semantics to Dragonfly nodes by the layout's semantics ID within its owner. This publishes clickability, the first nonempty merged description, and disabled state where the composition group has an identifiable layout. Failed or ambiguous association keeps the Dragonfly result usable. The adapter reads semantics without invoking Compose actions.

Dragonfly extraction warnings/errors remain node message plus optional warnings. Dump keeps valid partial JSON and an incomplete-hierarchy summary; successful locate/inspect/tap preserves valid matches plus warnings. A miss in an incomplete current window returns UI_HIERARCHY_INCOMPLETE rather than ELEMENT_NOT_FOUND; budget truncation remains separate. First Compose enable yields a frame via callback without main-thread blocking; cancel expired requests. A descriptor containing private internal.dragonfly.runtime.kotlin.jvm.functions.Function3 signals relocation error, not proof of a missing host Compose method. Client socket reads allow 12 seconds for the App's ten-second main-thread deadline. Offline tools/layout-dump-viewer.html imports either JSON mode for wireframe/tree/properties; units are not converted twice. Wireframes cannot prove occlusion/Z order/clipping or real font/image rendering.
