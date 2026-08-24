---
title: UI layout evidence
description: Explains how an Agent uses public UI tools to produce reviewable layout decisions.
status: active
tags:
  - capability
  - tools
  - ui
  - layout
---

# UI layout evidence

This page explains how an Agent uses the currently public UI tools to produce a reviewable layout decision. The public flow collects actual View information and then has the Agent calculate expected / actual / diff / verdict. It does not call an unregistered batch `layout-verify` tool.

## Public evidence flow

| Evidence type | Current support | Tool |
|---|---|---|
| Current page and window context | Supported | `activity-stack`, `layout-dump --all-windows` |
| Element bounds, position, and size | Supported | `view-locate` |
| View getter properties | Supported | `view-inspect` |
| Page changes after interaction | Supported | Run `activity-stack` / `layout-dump` again after `tap` |
| Log feedback loop | Supported | `wait-logs` |
| Direct `layout-verify` / `figma-layout-verify` call | Not currently public | Action classes exist but are not registered |

## How to make a layout decision

```text
Design / requirement / expected value
  -> Agent explicitly states the source of each expected value
view-locate
  -> Obtain Android actual bounds / size / position
view-inspect
  -> Obtain getter properties such as color, text, visibility, and enabled state
Agent calculation
  -> expected / actual / diff / verdict
```

The Agent calculates spacing and alignment from dp bounds, for example:

```text
horizontalSpacing = rightElement.left - leftElement.right
verticalSpacing   = bottomElement.top - topElement.bottom
centerX           = (left + right) / 2
centerY           = (top + bottom) / 2
```

A recommended report includes the source of the expected value, the source of the actual value, the difference, and the verdict rather than only “pass/fail.”

## Figma scenarios

With a Figma design, the Agent should extract expected values from structured design data and use `view-locate` / `view-inspect` for Android actual values. Do not treat `figma-layout-verify` as a public callable tool at this time.

> [!NOTE]
> A `figmaNode` field exists in some schemas, but public location still focuses on selectors such as text, resourceId, and contentDesc and does not promise automatic IoU matching.

## Boundaries

- `layout-dump` publishes HTML, not the internal JSON.
- Bounds from `view-locate` are already in dp.
- `view-inspect` returns raw getter values. Convert px values using `density`.
- When `matchCount > 1`, disambiguate the matches instead of treating the first candidate as stable evidence.
- The screenshot action is not currently registered and cannot be the default MCP evidence source.

## Related pages

- [UI inspection guide](../../guide/ui-inspection.md)
- [Layout dumps and UI evidence](../../concepts/layout-dump-and-ui-evidence.md)
- [UI automation](./ui-automation.md)
- [MCP for Agents](./mcp.md)
