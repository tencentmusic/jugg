---
title: Code Swap
description: Explains Jugg's online replacement of running code and its applicable boundaries.
status: active
tags:
  - capability
  - deploy
  - code-swap
---

# Code Swap

Code Swap means that Apply Changes can apply class changes with compatible structure to a running app. Jugg marks method-body and similar changes that do not alter runtime structure as replaceable online. However, a regular non-empty deployment currently usually recreates the Activity so that code and resource results reload in the current UI.

## Changes suitable for Code Swap

| Change type | Current support | Deployment strategy |
|---|---|---|
| Method-body change | Supported | Enters Apply Changes as a modified class |
| Class change eligible for hot reload | Supported | Enters the `HOT_RELOAD` payload and usually recreates the Activity with Full Swap |
| Empty change or overlay-only update | Skips the redefiner | Does not create a debugger redefiner, avoiding an unintended class swap |
| New class | Supported for delivery, but not as a modified-class redefinition | Enters Apply Changes as a new class |
| Field, method signature, inheritance, or generic-structure change | Not treated as pure Code Swap | Triggers Hot Fix, Full Swap, recompilation, or reinstallation decisions |

> [!TIP]
> When a change alters class structure, Jugg assigns it to a more appropriate deployment strategy instead of forcing an online redefinition.

## How it takes effect

```text
Source compilation produces DEX / class changes
  -> Determine whether class structure allows online replacement
  -> Add eligible changes to the Apply Changes payload as modified classes
  -> Run Apply Changes and Restart Activity for a regular non-empty deployment
  -> Commit deployment history after success
```

The key Code Swap decision occurs while deployment data is generated. Jugg compares the old and new class structures. Only changes suitable for online replacement enter modified classes; other changes enter Hot Fix or APK-update strategies. A regular method-body change is not currently mapped to a separate deployment action that preserves the Activity. It is applied together with Full Swap.

> [!NOTE]
> `HOT_RELOAD` is Jugg's classification for an online incremental deployment result. It does not mean the current Activity necessarily remains unchanged. A regular non-empty Hot Reload currently uses Full Swap to recreate the Activity.

## Relationship to other deployment strategies

- [Hot Reload](./hot-reload.md) is the overall user-visible online incremental deployment capability. Code Swap describes class inputs that can be replaced online within it.
- [Full Swap](./full-swap.md) covers Apply Changes that must restart the Activity.
- [Restart](./restart.md) applies when the user explicitly requests an app restart or the payload requires an app restart to take effect.

## Related pages

- [Hot Reload](./hot-reload.md)
- [Full Swap](./full-swap.md)
- [Restart](./restart.md)
- [Recompilation](../compile/recompile-propagation.md)
- [Classes and overlays in Apply Changes](../../concepts/apply-changes.md)
