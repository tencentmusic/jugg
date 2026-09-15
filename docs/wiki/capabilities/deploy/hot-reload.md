---
title: Hot Reload
description: Explains Jugg's online incremental deployment capability and how it applies changes without reinstalling the app.
status: active
tags:
  - capability
  - deploy
  - hot-reload
---

# Hot Reload

Hot Reload is Jugg's preferred online incremental deployment capability. It sends code, resource overlays, and other deployment items that can be handled incrementally in the current run, avoiding a complete Gradle build and reinstallation when possible.

## How Hot Reload handles different changes

| Scenario | Current support | Deployment strategy |
|---|---|---|
| Method-body code change | Supported | Apps that satisfy the `run-as`, UID, and SELinux-label prerequisites use Apply Changes; incompatible apps with usable sandbox access persist an overlay and let the Jugg Agent replace the class without recreating the Activity |
| Resource or asset change eligible for an overlay | Supported | Pushes the overlay; ordinary Apply Changes restarts the Activity when needed, while Direct sandbox refreshes resources and recreates the current Activity on Android 11+ and restarts the app on Android 8–10 |
| First resource overlay | Supported | Includes all baseline resources to avoid missing resources on the device |
| New class | Supported online | Apply Changes or Direct sandbox adds the new DEX to the current process's Application ClassLoader; ordinary non-empty deployment still recreates the Activity according to the upper-level lifecycle policy |
| Class with structural changes | Supported for incremental delivery, but requires restart | Enters the Hot Fix path |
| Manifest, `resources.arsc`, or `.so` update | Supported as an APK update | Modifies and re-signs the APK, then installs it or recovers state |
| Device state does not match | Automatic recovery supported | Runs recover/retry first, then determines whether Hot Reload can continue |

> [!NOTE]
> Hot Reload does not promise that every change avoids a restart. Jugg preserves runtime state when possible, but switches strategies when a payload requires an app-process or Activity restart.

## How it takes effect

```text
Incremental compilation succeeds
  -> Collect classes, resources, and APK files from the current run
  -> Choose Hot Reload / Hot Fix / APK update
  -> Use Direct Overlay + Jugg JVMTI Agent for class, resource, and asset changes when run-as, the UID, or the SELinux label is incompatible
  -> Use Apply Changes and Restart Activity when the device is ready
  -> Attempt Direct Overlay when the device is not ready and conditions permit
  -> Commit deployment history after success
```

Hot Reload centers on deployment-data classification. Jugg places classes eligible for online updates in Hot Reload, structural changes or content requiring a process restart in Hot Fix, and Manifest, `resources.arsc`, native libraries, and similar files in the APK-update path.

Jugg checks the Android Studio Apply Changes prerequisites with a reversible write probe. It continues into that channel only when `run-as` returns one unique success marker, the original UID is within `10000..19999`, and newly created files use the same SELinux label as the app's existing cache directory. Other results do not depend on app flags or a particular error message. Jugg instead validates access to the real data directory through the ordinary shell, one root-adbd request, and non-interactive `su`. When access is available, it persists the Dex in the overlay and then attempts online replacement. If that fails, Jugg restarts the app and its startup agent loads the same overlay.

The Direct path makes DEX and request files inherit the app cache directory's dynamic SELinux label and labels JVMTI Agent `.so` files with a type the app process can execute. Root-written files therefore remain usable after restart or dynamic attach despite differences in ownership, MCS categories, or executable file type.

When `run-as`, the UID, or the SELinux label is incompatible, Jugg can deliver classes, resources, and assets directly. New classes are added to the current process's Application ClassLoader as in-memory DEX elements, while method-body changes are replaced online through JVMTI. On Android 11+, ordinary resource, asset, and mixed code/resource changes refresh resources in the main process and recreate the current Activity; Jugg restarts the app if refresh fails. Resource changes on Android 8–10 require a process restart, while compatible deployment retains the resource-APK path. Manifest and native-library changes still use APK updates and installation. Failed permission probes or a missing deployment cache cause an explicit failure.

## Boundaries

Jugg leaves the regular Hot Reload path in these situations:

- Compilation has already fallen back to Gradle, so deployment proceeds to install.
- The device overlay ID, deployment cache, or historical state does not match and requires recovery.
- The payload requires an app or Activity restart.
- JVMTI is unavailable or a deployment failure signal requires compatible deployment.

Direct sandbox is the alternative when the Apply Changes prerequisites are unavailable, with these current scope limits:

- One online request handles one main process. Separate processes load the overlay after those processes restart.
- Jugg recreates all live Activities in the main process, including instances in other tasks and multi-window. Activities in separate processes are refreshed after those processes restart.
- Structural changes, APK-root resources, and Compose resources continue using a path that restarts the process; new classes can take effect online in the current main process.
- Direct deployment submits the current changes as one batch and does not provide official Apply Changes slicing progress or per-slice retry.
- A verifiable deployment cache and overlay state must already exist. Missing or mismatched state enters recovery or reinstallation first.

## Related pages

- [Code Swap](./code-swap.md)
- [Full Swap](./full-swap.md)
- [Direct Overlay](./direct-overlay.md)
- [Recover and Retry](./recover-and-retry.md)
- [Classes and overlays in Apply Changes](../../concepts/apply-changes.md)
- [APK updates and installation](../../concepts/apk-update-and-install.md)
