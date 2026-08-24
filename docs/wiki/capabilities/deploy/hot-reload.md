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
| Method-body code change | Supported | Apply Changes, usually recreating the Activity without restarting the app process |
| Resource or asset change eligible for an overlay | Supported | Pushes the overlay and restarts the Activity when needed |
| First resource overlay | Supported | Includes all baseline resources to avoid missing resources on the device |
| New class | Supported for incremental delivery | Enters Apply Changes as a new class |
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
  -> Use Apply Changes and Restart Activity when the device is ready
  -> Attempt Direct Overlay when the device is not ready and conditions permit
  -> Commit deployment history after success
```

Hot Reload centers on deployment-data classification. Jugg places classes eligible for online updates in Hot Reload, structural changes or content requiring a process restart in Hot Fix, and Manifest, `resources.arsc`, native libraries, and similar files in the APK-update path.

## Boundaries

Jugg leaves the regular Hot Reload path in these situations:

- Compilation has already fallen back to Gradle, so deployment proceeds to install.
- The device overlay ID, deployment cache, or historical state does not match and requires recovery.
- The payload requires an app or Activity restart.
- JVMTI is unavailable or a deployment failure signal requires compatible deployment.

## Related pages

- [Code Swap](./code-swap.md)
- [Full Swap](./full-swap.md)
- [Direct Overlay](./direct-overlay.md)
- [Recover and Retry](./recover-and-retry.md)
- [Classes and overlays in Apply Changes](../../concepts/apply-changes.md)
- [APK updates and installation](../../concepts/apk-update-and-install.md)
