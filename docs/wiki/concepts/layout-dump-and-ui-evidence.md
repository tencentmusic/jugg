---
title: Layout dump and UI evidence
description: Explains how layout-dump generates HTML evidence from an instantaneous in-app view snapshot, and the boundaries of structure, properties, units, and snapshot freshness.
status: active
tags:
  - concept
  - ui
  - layout-dump
---

# Layout dump and UI evidence

A UI judgment must state both what is expected and what the device currently presents. A screenshot records the pixel appearance of a page, while a view tree provides node identity, hierarchy, text, bounds, and selected runtime properties. The two evidence types answer different questions and cannot replace one another.

Jugg `layout-dump` captures the current view hierarchy from a running app and organizes Android View and Compose nodes into a readable HTML artifact. This artifact represents a page snapshot at the time of one request and documents actual page structure. It does not provide the design expectation or constitute the final pass or fail conclusion by itself.

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
  -> prune structural nodes without semantic content
  -> write the HTML artifact
```

The public result provides the HTML file path, artifact information, and content size. Structured data used to generate the HTML is consumed only by internal Jugg tools and is not a stable public interface.

## HTML is a reading artifact, not a complete data interface

HTML helps Agents and developers read page structure, locate candidate nodes, and cite evidence from the current Run. To control information volume, it omits intermediate structural nodes that have no text, ID, description, or other useful semantics. The HTML is therefore not a field-for-field representation of original app view objects.

Different questions use different evidence sources:

| Question | Evidence source |
|---|---|
| Which windows, nodes, and parent-child relationships currently exist | HTML snapshot from `layout-dump` |
| Whether an element exists, plus its bounds, position, and size | Geometry result from `view-locate` |
| Runtime properties such as text color, visibility, and enabled | Getter result from `view-inspect` |
| Whether an interaction produced the expected page change | A new snapshot or runtime evidence after the interaction |

`view-locate` returns structural data suitable for calculating spacing and alignment, while `view-inspect` returns raw getter values. Both supplement exact information not directly represented in HTML instead of exposing internal layout JSON as a public protocol.

## Every request has its own timestamp

`layout-dump`, `view-locate`, `view-inspect`, and element-mode touch share one in-app view data source, but each call captures the page again. They see snapshots from their own request times rather than one fixed view tree shared across tools.

Page animation, list scrolling, window changes, or Compose recomposition can change nodes, bounds, and virtual IDs between requests. After an interaction, an earlier snapshot proves only the pre-interaction state. Capture page structure or runtime properties again to judge the interaction result.

A virtual ID can repeat while window order and UI structure remain unchanged, but can change after layout reordering, node insertion, or recomposition. It is not a long-term stable business identifier.

## Geometry and runtime properties use different unit contracts

The in-app view structure carries device density. Jugg recursively converts `bounds` and `padding` for ordinary View and Compose nodes:

```text
dp = px / density
```

Node geometry in `layout-dump` and bounds, position, and size from `view-locate` use dp. An Agent can use these values directly to calculate element spacing, centers, and alignment.

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
