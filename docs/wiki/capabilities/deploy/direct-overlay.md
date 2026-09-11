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
| Android O or later, with compatible Apply Changes prerequisites | Supported | Uses `run-as` to write into the app sandbox |
| Class, resource, or asset change with no `run-as` success marker, an out-of-range UID, or a mismatched SELinux label | Supported when the permission probe succeeds | Uses a fixed ordinary-shell, root-adbd, or device-supported non-interactive-`su` command form against the real data directory |
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

When `run-as`, the UID, or the SELinux label does not meet Android Studio Deployer prerequisites, the same deployment reuses one sandbox executor with a fixed permission mode. This prevents the overlay, startup agent, and JVMTI Hot Reload steps from switching paths after root state changes.

Direct permission modes repair SELinux labels separately for data files and Agent `.so` files: overlay data inherits the app cache directory's dynamic categories, while `.so` files use a type the app process can execute. Additional output from the repair tools does not change the write command's success result.

## Boundaries

Direct Overlay requires all of the following:

- The user or caller allows Direct Overlay.
- The device is not in the regular ready-to-deploy state.
- A deployment cache exists and the overlay checkpoint can be validated.
- Deployment data is non-empty and does not represent an install.
- The device OS version meets the requirement and the app sandbox is writable through the selected `run-as`, ordinary-shell, root-adbd, or non-interactive-`su` mode.

When `run-as`, the UID, or the SELinux label is incompatible, Jugg can deliver classes, resources, and assets directly. Pure method-body changes can be replaced online. On Android 11 or later, resource, asset, and mixed code/resource changes refresh resources in the current process and recreate the Activity, falling back to an app restart if the refresh fails. Android 8–10 and compatible deployment retain resource paths that require a process restart. Manifest and native-library changes still use APK updates and installation. Failed permission probes or a missing deployment cache cause an explicit failure.

## Related pages

- [Deployment state and recovery](../../concepts/deploy-state-recover.md)
- [Hot Reload](./hot-reload.md)
- [Recover and Retry](./recover-and-retry.md)
- [Deployment history and cache](./deploy-history-cache.md)
- [JVMTI Runtime](./jvmti-runtime.md)
- [Direct Overlay deployment](../../concepts/direct-overlay.md)
