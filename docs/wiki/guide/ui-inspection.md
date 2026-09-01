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

These tools use the in-process ViewHierarchy channel rather than a uiautomator dump. Public artifacts are primarily trimmed HTML so agents can read and reference them efficiently.

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
jugg layout-dump
jugg layout-dump --root-layout content
jugg layout-dump --include-gone
jugg layout-dump --all-windows
```

Common arguments:

| Argument | Purpose |
|---|---|
| `--root-layout` | Export only the subtree of the specified node |
| `--include-gone` | Include GONE nodes |
| `--all-windows` | Export all windows instead of only the top window |

The output is an HTML artifact. Internal JSON is used only by `view-locate`, `view-inspect`, and other tool implementations.

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

- Run `layout-dump` before locating or tapping an element.
- For buttons with duplicate text, add `resource-id` or `class-name` when possible.
- After a tap, verify the result with `layout-dump`, `activity-stack`, or `wait-logs`.
- Do not tap hidden nodes. They can be inspected for properties but are not suitable touch targets.
- All bounds / padding coordinates use dp, while screenshot pixels use px. Convert them using density.

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
