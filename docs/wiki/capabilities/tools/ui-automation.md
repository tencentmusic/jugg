---
title: UI automation
description: Explains how an Agent reads page structure, locates elements, queries View properties, and performs basic input.
status: active
tags:
  - capability
  - tools
  - ui
---

# UI automation

UI automation commands let an Agent read page structure, locate elements, query View properties, and perform basic input on a device. The current public capabilities use the in-app ViewHierarchy channel and do not rely on a uiautomator fallback.

## Supported tasks

| User task | Current support | Command |
|---|---|---|
| Export the current UI hierarchy | Supported | `layout-dump` |
| Locate an element by text / resource ID / content-desc | Supported | `view-locate` |
| Query read-only View properties | Supported | `view-inspect` |
| Tap / long-press / swipe | Supported | `tap` |
| Screenshot | Not currently public through MCP/CLI | The `screenshot` action is not registered |

## Recommended order

```text
activity-stack
  -> layout-dump
  -> view-locate / view-inspect
  -> tap (when interaction is needed)
  -> activity-stack / layout-dump / wait-logs to verify the result
```

Confirm the page before collecting structure and properties. For interaction, prefer an element selector. Disambiguate repeated matches before considering coordinate- or percentage-based input.

## UI hierarchy

```text
jugg layout-dump
jugg layout-dump --root-layout content
jugg layout-dump --include-gone
jugg layout-dump --all-windows
```

`layout-dump` outputs a public HTML artifact suitable for Agents and users. Internal JSON is reused only by tool implementations and is not a stable public interface.

## Element location

```text
jugg view-locate --text "Submit"
jugg view-locate --resource-id btn_confirm
jugg view-locate --content-desc "Back"
```

The result includes `bounds`, `position`, `size`, `matchCount`, and a candidate summary, with coordinates in dp. When `matchCount > 1`, the first result is not a stable assertion or safe input target. Add a stronger selector.

## Property inspection

```text
jugg view-inspect --text "Submit" "getText()" "isEnabled()"
jugg view-inspect --resource-id btn_confirm "getCurrentTextColor()" "getTextSize()"
jugg view-inspect --resource-id bubble_container "layoutParams.leftMargin" "getLayoutParams().getMarginStart()"
```

`view-inspect` evaluates read-only expressions through in-app reflection and returns raw values with `density`. Expressions can be getters, Kotlin properties, or public fields. A name without parentheses is resolved as a field first, then as `getXxx()` / `isXxx()`. It can read properties of hidden nodes that remain in the View tree. Hidden nodes can provide state evidence but do not prove that an element is clickable.

## Input

```text
jugg tap --text "Login"
jugg tap --resource-id btn_submit
jugg tap --content-desc "Close"
jugg tap --x 540 --y 960
jugg tap --x-percent 50 --y-percent 80
jugg tap --action swipe --x-percent 50 --y-percent 80 --end-x-percent 50 --end-y-percent 20
```

Before input, Jugg verifies that the app is online, the device is interactive, the target app is in the foreground, and the Activity is stable. When an element selector matches multiple nodes, Jugg does not perform input and instead returns a candidate summary for disambiguation.

## Related pages

- [UI inspection guide](../../guide/ui-inspection.md)
- [Layout dumps and UI evidence](../../concepts/layout-dump-and-ui-evidence.md)
- [UI layout evidence](./layout-verify.md)
- [Runtime and devices](./cli-runtime-device.md)
- [MCP for Agents](./mcp.md)
