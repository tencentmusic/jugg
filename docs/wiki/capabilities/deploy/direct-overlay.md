---
title: Direct Overlay
description: Explains how Jugg writes overlays directly when a device is not ready.
status: active
tags:
  - capability
  - deploy
  - direct-overlay
---

# Direct Overlay

Direct Overlay is Jugg's alternate overlay-write path when a device has not yet reached the online Apply Changes ready state. It writes the incremental overlay directly into the app sandbox, while the outer deployment flow remains responsible for startup, restart, test execution, and completion.

## Direct Overlay conditions

| Scenario | Current support | Deployment strategy |
|---|---|---|
| Device is not ready, but history and cache match | Supported | Writes the overlay directly into the app sandbox |
| Android O or later device | Supported | Uses `run-as` to write into the app sandbox |
| Apply Changes startup agent must be prepared in advance | Supported | The Direct Overlay path pushes the Android Studio startup agent |
| Overlay ID does not match the expected value | Does not force a write | Switches to recovery or reinstallation |
| The writer fails after modifying the overlay | Does not fall back to old Apply Changes | Prevents continued deployment from a partially committed state |

> [!IMPORTANT]
> Direct Overlay replaces only the overlay update transport. It does not take over the complete deployment lifecycle. Starting the app, restarting it, running androidTest, and committing history remain responsibilities of the outer deployment flow.

## How it takes effect

```text
The device is not ready and Direct Overlay is allowed
  -> Read the deployment cache and expected overlay ID
  -> Check the device-side overlay ID
  -> Build an overlay ZIP payload
  -> Transfer it to the device through ADB
  -> Atomically update the overlay in the sandbox as the target app
  -> Write the new overlay ID last
  -> Update the deployment cache
```

The Recover stage checks history, cache, and device-side overlay state. Before writing, it validates the device-side overlay ID against at least the cache. The new overlay ID is committed last. If writing fails after the overlay directory has changed, Jugg treats the state as dirty and does not attempt a false fallback to old Apply Changes.

## Boundaries

Direct Overlay requires all of the following:

- The user or caller allows Direct Overlay.
- The device is not in the regular ready-to-deploy state.
- A deployment cache exists and the overlay checkpoint can be validated.
- Deployment data is non-empty and does not represent an install.
- The device OS version meets the requirement and the app sandbox is writable through `run-as`.

## Related pages

- [Deployment state and recovery](../../concepts/deploy-state-recover.md)
- [Hot Reload](./hot-reload.md)
- [Recover and Retry](./recover-and-retry.md)
- [Deployment history and cache](./deploy-history-cache.md)
- [JVMTI Runtime](./jvmti-runtime.md)
- [Direct Overlay deployment](../../concepts/direct-overlay.md)
