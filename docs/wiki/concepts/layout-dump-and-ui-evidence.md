---
title: Layout dump and UI evidence
description: Explains how layout-dump generates dual-mode JSON evidence from an instantaneous in-app view snapshot, and the boundaries of structure, properties, units, and snapshot freshness.
status: active
tags:
  - concept
  - ui
  - layout-dump
---

# Layout dump and UI evidence

A UI judgment must state both what is expected and what the device currently presents. A screenshot records the pixel appearance of a page, while a view tree provides node identity, hierarchy, text, bounds, and selected runtime properties. The two evidence types answer different questions and cannot replace one another.

Jugg `layout-dump` captures the current view hierarchy from a running app and organizes Android View and Compose nodes into a sparse JSON artifact. This artifact represents a page snapshot at the time of one request and documents actual page structure. It does not provide the design expectation or constitute the final pass or fail conclusion by itself.

## Pixel appearance and view structure answer different questions

A screenshot is suitable for checking color, images, shadows, and overall visual result, but node identity, resource IDs, exact hierarchy, and runtime getter values must come from the in-app view structure. Conversely, even a view tree with bounds and text cannot fully represent antialiasing, image content, or final pixel composition.

A reviewable UI judgment therefore usually records these separately:

- Expected values come from a design, product requirement, code formula, or a standard explicitly provided by the user.
- Actual structure comes from the current page view snapshot.
- Actual properties come from runtime queries against target nodes.
- Pass or fail comes from comparing expected and actual values.

`layout-dump` supplies actual page structure. It does not infer expected values or automatically generate the final conclusion.

## How one layout-dump forms a page snapshot

Jugg does not read the page from a system-level uiautomator dump. It connects to the ViewHierarchy service inside the target app. When the app receives a request, it captures Android View and Compose nodes from current windows, and Jugg normalizes node fields and geometry:

```text
running target app
  -> capture Android View and Compose nodes from current windows
  -> organize windows, hierarchy, text, IDs, and bounds
  -> convert node bounds / padding from px to dp using device density
  -> prune an interaction summary or preserve the captured tree in ui mode
  -> write the JSON artifact
```

The public result provides the JSON file path, artifact information, and UTF-8 content size. The file carries mode, units, windows, device density, budget truncated, and optional warnings/rootLayout so its collection scope and diagnostics can be read independently. The summary counts actual output nodes.

Small results also include the complete layout object in the response, saving a separate file read. Large results remain in the file, with newlines between nodes for selective retrieval; node properties and coordinate arrays stay compact. Inline data and the file represent the same snapshot, whose scope is determined by the mode and collection parameters. See [MCP tools](../reference/mcp-tools.md) for the exact size condition.

## Interaction summaries and UI hierarchies use different node scopes

interaction (default) browses a page and discovers interaction candidates. Apart from the offscreen retention rules below, it keeps visible window roots and nodes with real IDs, text, descriptions, clickability, or diagnostics. Other nodes are removed and their children are promoted to retained ancestors. Leaf nodes such as images without semantic content may also be omitted, so the summary cannot establish absence or original parent-child relationships and does not guarantee that all output nodes are clickable.

Real nodes start on new lines, while attributes and coordinates stay compact; synthetic `jugg_details` nodes stay inline. When children include at least one real node, the opening `[` and closing `]}` each start on a new line.

In ordinary windows, interaction keeps the first node fully outside the window bounds, including its className, ID, bounds, and state, and replaces its descendants with a synthetic child containing `jugg_details`: `View is off-screen; descendants omitted in interaction mode.` The placeholder indicates that descendants were omitted from the summary; it is not a real control and is excluded from node and clickable counts. Offscreen nodes that already have no children remain unchanged.

Partially visible nodes, zero-area nodes, and branches with descendants inside the window, unknown descendant geometry, or descendant diagnostics are not folded. When window bounds are invalid or `rootLayout` requests a subtree, the existing semantic pruning continues without inferring screen bounds from the subtree. To expand descendants, use `jugg layout-dump --mode ui --root-layout <id>`. Retained node order, bounds, and className can suggest a direction for swipe exploration; collect a new snapshot afterward to confirm content changes.

ui preserves the currently captured tree for geometry, parent-child structure, decorative nodes, and hidden-state validation. It provides structure and text as reconstruction references, but complete visual or source reconstruction also requires images, fonts, drawing effects, source, and other information. Both modes observe window, subtree, GONE filtering, and collection budgets; intentional pruning does not set truncated.

Both modes share field meanings, with public virtual IDs shortened to `_vid_<hash>`; legacy `_vir_id_` selectors still work. interaction first removes invisible/gone nodes and their entire subtrees without promoting children, even with `--include-gone`, omits `padding` and `textColor`, and retains className. ui retains hidden nodes and visual fields within the requested collection scope; GONE nodes still require `--include-gone`. Empty values and default states are omitted to control information volume.

Different questions use different evidence sources:

| Question | Evidence source |
|---|---|
| Which windows, nodes, and parent-child relationships currently exist | JSON snapshot from `layout-dump --mode ui` |
| Whether an element exists, plus its bounds, position, and size | Geometry result from `view-locate` |
| Runtime properties such as text color, visibility, and enabled | Getter result from `view-inspect` |
| Whether an interaction produced the expected page change | A new snapshot or runtime evidence after the interaction |

`view-locate` returns structural data suitable for calculating spacing and alignment, while `view-inspect` returns raw getter values. They locate live elements and supplement properties not covered by the dump. For a known selector, query or tap the element directly without first reading the entire dump.

## Every request has its own timestamp

`layout-dump`, `view-locate`, `view-inspect`, and element-mode touch share one in-app view data source, but each call captures the page again. They see snapshots from their own request times rather than one fixed view tree shared across tools.

Page animation, list scrolling, window changes, or Compose recomposition can change nodes, bounds, and virtual IDs between requests. After an interaction, an earlier snapshot proves only the pre-interaction state. Capture page structure or runtime properties again to judge the interaction result.

A virtual ID can repeat while window order and UI structure remain unchanged, but can change after layout reordering, node insertion, or recomposition. It is not a long-term stable business identifier.

## Geometry and runtime properties use different unit contracts

The in-app view structure carries device density. Jugg recursively converts `bounds` and `padding` for ordinary View and Compose nodes:

```text
dp = px / density
```

Normally captured node geometry in `layout-dump` and bounds, position, and size from `view-locate` use dp. The dump uses integer dp; without valid density, px is retained and declared in the file's units. An Agent should confirm units before calculating spacing, centers, and alignment.

`view-inspect` returns raw getter values together with density. Whether one getter returns px, sp, a color integer, or a business object depends on the property itself and must be interpreted according to the corresponding Android API. The use of dp for layout bounds does not make every getter result or touch coordinate use dp.

## Evidence boundaries

- If the in-app ViewHierarchy service is unavailable, the public flow does not automatically switch to uiautomator. First confirm that the app is online, in the foreground, and has loaded the corresponding runtime.
- View snapshots have limits for windows, node count, and hierarchy depth. When an artifact is marked truncated, a missing node cannot be concluded not to exist.
- Hidden or GONE nodes can retain queryable properties, but those properties do not prove that a node is currently visible or clickable.
- Android View and Compose nodes use one output structure, but queryable getters and interaction semantics for a Compose node depend on information actually exposed at runtime.
- `layout-verify` and `figma-layout-verify` are not currently public MCP tools. Public UI verification should preserve the expected source, actual evidence, and comparison process rather than depend on an unregistered batch assertion entry point.

## Related pages

- [In-app Jugg Runtime](./jugg-runtime.md)
- [UI inspection guide](../guide/ui-inspection.md)
- [UI automation capability](../capabilities/tools/ui-automation.md)
- [UI layout evidence capability](../capabilities/tools/layout-verify.md)
- [MCP tool reference](../reference/mcp-tools.md)
- [Agent or CLI command failed](../troubleshooting/agent-command-failed.md)
