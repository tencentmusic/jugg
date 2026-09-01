---
title: Full Swap
description: Explains Jugg's Apply Changes deployment capability when the Activity must restart.
status: active
tags:
  - capability
  - deploy
  - full-swap
---

# Full Swap

Full Swap handles incremental deployments that cannot finish with online Code Swap alone but still do not require a complete reinstall. Jugg sends the incremental artifacts first, then uses Apply Changes and Restart Activity to reload the changes in the current UI.

## Full Swap triggers

| Scenario | Current support | Deployment strategy |
|---|---|---|
| Incremental change requires an Activity restart | Supported | Runs Apply Changes and Restart Activity |
| UI must refresh after a resource overlay update | Supported | Restarts the Activity after sending the overlay |
| Non-empty change does not require an app restart | Supported | Selects Full Swap according to `isNeedRestartActivity` |
| Warm-up / dry deploy | Does not trigger a user-visible Full Swap | Used only for state probing |
| Hot Fix requires an app-process restart | Does not use Full Swap | Switches to [Restart](./restart.md) or install |

## How it takes effect

```text
Incremental compilation succeeds
  -> Generate non-empty deployment data
  -> Determine that the app need not restart but the Activity must restart
  -> Run Apply Changes and Restart Activity
  -> Commit deployment history after success
```

Full Swap remains an incremental deployment. It does not reinstall the APK. Instead, it refreshes the Activity lifecycle after Apply Changes succeeds so that resources, layouts, and some runtime changes reload in the current UI.

The current implementation uses Full Swap for regular, non-empty incremental data that does not require an app restart. A method-body change can therefore be replaced online while the Activity is still usually recreated. The app process and its in-process state remain intact.

## Difference from Code Swap

| Strategy | User-visible behavior | Suitable changes |
|---|---|---|
| Code Swap | The class can be replaced online; the outer action determines whether the Activity is recreated | Code such as method bodies that preserves compatible structure |
| Full Swap | The current Activity restarts | Overlay or code changes that require the UI to reload |
| Restart | The app process restarts | Hot Fix, agent, debugging, or explicit user restart |

## Related pages

- [Code Swap](./code-swap.md)
- [Hot Reload](./hot-reload.md)
- [Restart](./restart.md)
- [Resource compilation](../compile/resource-compile.md)
- [Classes and overlays in Apply Changes](../../concepts/apply-changes.md)
