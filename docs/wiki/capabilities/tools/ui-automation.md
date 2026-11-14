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
jugg layout-dump --mode interaction
jugg layout-dump --mode ui
jugg layout-dump --root-layout content
jugg layout-dump --mode ui --include-gone
jugg layout-dump --all-windows
```

`layout-dump` outputs a public sparse JSON artifact. `interaction` (default) prunes a page summary for discovering interaction candidates; `ui` preserves captured nodes and parent-child relationships for layout validation, state investigation, and reconstruction references. Both modes share field meanings, with public virtual IDs shortened to `_vid_<hash>`; legacy `_vir_id_` selectors still work. interaction first removes invisible/gone nodes and their entire subtrees without promoting children, even with `--include-gone`, omits `padding` and `textColor`, and retains className. ui retains hidden nodes and visual fields within the requested collection scope; GONE nodes still require `--include-gone`.

The interaction summary removes nodes without semantic content and promotes children. It cannot establish absence or original hierarchy and does not guarantee that every node is clickable. UI mode still observes window, subtree, GONE filtering, and budget limits; complete visual reconstruction requires additional images, fonts, and drawing information.

## When Compose content is missing

If part of the hierarchy cannot be extracted, `layout-dump` preserves the valid JSON and marks the summary with `incomplete hierarchy`. The result's `warnings` and JSON node diagnostics describe the cause. Missing text in this output does not prove that the element is absent from the page.

`view-locate`, `view-inspect`, and element-mode `tap` still return results for valid matches, along with collection diagnostics. If there is no match and the current window's hierarchy is incomplete, they return `UI_HIERARCHY_INCOMPLETE`. `truncated` separately indicates a node or result budget limit.

After upgrading the plugin, if the tree still stops at a Compose host without content, use Jugg's full Gradle build and install the updated APK before collecting again. Restarting an old APK alone does not guarantee an updated in-app runtime. If diagnostics remain, keep the JSON and reported cause, and check the reported Compose runtime/tooling compatibility issue.

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
