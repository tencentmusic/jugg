---
title: UI inspection
description: Use Jugg CLI/MCP to export the UI hierarchy, locate elements, read View properties, and perform touch actions.
status: active
tags:
  - guide
  - ui
  - cli
---

# UI inspection

Jugg provides UI tools for agents and scripts to export the current app's View hierarchy, locate elements, read View properties, and perform taps, long presses, swipes, and other actions.

These tools use the in-process ViewHierarchy channel rather than a uiautomator dump. Public artifacts use sparse JSON, with a choice between an interaction summary and the captured UI hierarchy.

## Prerequisites

Before use, confirm that:

1. Android Studio has the target project open and Jugg is initialized.
2. A device is connected and the target app is installed.
3. The app is currently on an interactive screen.
4. If the ViewHierarchy socket is unavailable, try `jugg restart` first, then run `jugg deploy` or `jugg gradle-build` if necessary.

## Recommended flow

```text
layout-dump
  -> view-locate / view-inspect
  -> tap
  -> Verify with wait-logs or another layout-dump
```

Do not guess coordinates without evidence. Prefer an element selector, then coordinates or percentages only when necessary.

## Export a layout

```bash
jugg layout-dump --mode interaction
jugg layout-dump --mode ui
jugg layout-dump --root-layout content
jugg layout-dump --mode ui --include-gone
jugg layout-dump --all-windows
```

Common arguments:

| Argument | Purpose |
|---|---|
| `--mode` | interaction (default) browses interaction candidates; ui supports layout validation, state investigation, and reconstruction references |
| `--root-layout` | Export only the subtree of the specified node |
| `--include-gone` | Include GONE nodes in ui mode; interaction still removes hidden subtrees |
| `--all-windows` | Export all windows instead of only the top window |

The output is a JSON artifact at `data.file`. Unless the offscreen retention rules apply, `interaction` removes non-root nodes without real IDs, text, descriptions, clickability, or diagnostics and promotes their children. `ui` preserves captured parent-child relationships and decorative nodes. Both modes share field meanings, with public virtual IDs shortened to `_vid_<hash>`; legacy `_vir_id_` selectors still work. interaction first removes invisible/gone nodes and their entire subtrees without promoting children, even with `--include-gone`, omits `padding` and `textColor`, and retains className. ui retains hidden nodes and visual fields within the requested collection scope; GONE nodes still require `--include-gone`.

interaction uses a synthetic `jugg_details` child to indicate omitted offscreen descendants; it is not a clickable control. To expand them, use `jugg layout-dump --mode ui --root-layout <id>`. Subtree requests do not fold offscreen branches; partially visible branches and branches with descendants inside the window, unknown descendant geometry, or descendant diagnostics also remain expanded. See [Layout dump and UI evidence](../concepts/layout-dump-and-ui-evidence.md#interaction-summaries-and-ui-hierarchies-use-different-node-scopes) for the rules and boundaries.

The mode does not automatically change window, subtree, GONE filtering, or collection budgets. Use `ui` for layout validation; reconstruction references also require evidence such as screenshots, image assets, and source.

Small results also return the complete `data.layout` object. Use it directly without rereading the same file. Large results keep the complete file, with newlines between nodes and compact properties and coordinate arrays. For a known target, prefer `view-locate` or `--root-layout`. For an unknown target, search the file for text or IDs and read the relevant lines. See [MCP tools](../reference/mcp-tools.md) for the inline size condition.

## Locate an element

```bash
jugg view-locate --text 登录
jugg view-locate --resource-id login_button
jugg view-locate --content-desc 返回
```

`view-locate` returns element bounds, center point, size, className, and match count. If `matchCount > 1`, do not click the first result directly. Add selector conditions or inspect the layout first.

## Read properties

```bash
jugg view-inspect --resource-id title getText() getVisibility()
jugg view-inspect --text 登录 --class-name TextView getText() isEnabled()
jugg view-inspect --resource-id bubble_container layoutParams.leftMargin getLayoutParams().getMarginStart()
```

`view-inspect` reads properties without changing state, including:

- `getText()`
- `getVisibility()`
- `isEnabled()`
- `getContentDescription()`
- `getCurrentTextColor()`
- `layoutParams.leftMargin`

For a name without parentheses, Jugg first reads a public field, then resolves `getXxx()` / `isXxx()`. Use it to verify text, visibility, color, margins, selected state, and similar properties. Continue using `view-locate` for coordinate calculations.

## Touch actions

Element mode:

```bash
jugg tap --text 登录
jugg tap --resource-id login_button
jugg tap --content-desc 返回
```

Coordinate mode:

```bash
jugg tap --x 120 --y 360
jugg tap --action long-press --x 120 --y 360 --duration 800
jugg tap --action swipe --x 500 --y 1200 --end-x 500 --end-y 300
```

Percentage mode:

```bash
jugg tap --x-percent 50 --y-percent 90
```

`swipe` supports only coordinate or percentage mode, not element mode. If element mode matches multiple candidates, the tool returns an error with summaries to prevent an accidental tap.

## Recommendations for agents

- Browse an unfamiliar page with `layout-dump --mode interaction`; for a known selector, locate or tap the element directly.
- Use `--mode ui` for layout validation, hidden-state investigation, or reconstruction references. Missing nodes and promoted hierarchy in interaction mode cannot establish absence or original parent-child relationships.
- For buttons with duplicate text, add `resource-id` or `class-name` when possible.
- After a tap, verify the result with `layout-dump`, `activity-stack`, or `wait-logs`.
- Do not tap hidden nodes. They can be inspected for properties but are not suitable touch targets.
- Normally captured bounds / padding use dp, with units declared in the file's `units`; screenshot pixels use px. Convert them using density.

## Common problems

| Symptom | Action |
|---|---|
| Socket connection fails | Run `restart` first; if it still fails, run `gradle-build`, `deploy`, and `restart`, then try again |
| Element cannot be found | Run `layout-dump` again and confirm the current screen and window |
| Multiple elements match | Add a `resource-id`, `content-desc`, or `class-name` filter |
| Nothing happens after a tap | Confirm that the top Activity is stable and the element is visible and enabled |
| Bounds do not align with the screenshot | Tool output uses dp, while screenshots usually use px |

## Related pages

- [Layout dumps and UI evidence](../concepts/layout-dump-and-ui-evidence.md)
- [UI automation](../capabilities/tools/ui-automation.md)
- [UI layout evidence](../capabilities/tools/layout-verify.md)
- [CLI](./cli.md)
- [Agent or CLI execution failed](../troubleshooting/agent-command-failed.md)
