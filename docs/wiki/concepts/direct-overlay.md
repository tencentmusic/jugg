---
title: Direct Overlay deployment
description: Explains how Jugg writes overlays directly when a device has not reached the Apply Changes ready state, and how it avoids leaving an unrecoverable partial commit after a write failure.
status: active
tags:
  - concept
  - deploy
  - direct-overlay
---

# Direct Overlay deployment

Ordinary Apply Changes requires the target app to enter a communicable deployment state before the Android Studio deployment channel can send classes and overlays. When the device is not ready or the agent temporarily does not respond, waiting for the online channel prevents already generated incremental artifacts from being delivered.

When state can be verified, Direct Overlay writes directly to the overlay directory in the app sandbox. It changes only the file transfer method. Artifact classification, app lifecycle, state commit, androidTest, and failure recovery remain the responsibility of the same incremental deployment flow.

## Why files cannot simply be copied into the overlay directory

The device overlay is a complete result relative to the last successful state. If writing first deletes old files and then copies only some new files, any ADB or shell failure can leave mixed state: some files come from the previous Run, some from the current Run, and the overlay ID cannot describe which deployment the directory actually belongs to.

```text
old overlay state A
  -> delete or overwrite some files
  -> transfer is interrupted
  -> directory content no longer equals A
  -> new overlay ID has not been committed
  -> later deployment cannot determine current device state
```

Direct Overlay therefore verifies that the device is at the expected checkpoint before writing and commits the new overlay ID last. A failure before the directory is modified can return to ordinary Apply Changes. A failure after modification begins must mark the device state as untrusted and enter recovery.

## How Direct Overlay writes files

Direct Overlay requires an existing deployment cache to reconstruct the target APK, current overlay, and expected overlay ID. After the device check passes, Jugg packages the current files, pushes them through ADB to a temporary directory, and updates the sandbox through the permission mode resolved and fixed for this deployment.

```text
device is not ready and the caller allows Direct Overlay
  -> read the deployment cache
  -> validate the device overlay ID
  -> prepare the Apply Changes startup agent
  -> package current class and overlay files
  -> update the overlay in the app sandbox
  -> commit the new overlay ID last
  -> update the deployment cache
  -> let the outer flow launch or restart the app
```

Ordinary Direct Overlay uses the app sandbox and `run-as` capability available on Android 8.0 and later. If `run-as` has no unique success marker, returns a UID outside Android Studio Deployer's accepted `10000..19999` range, or creates files with a different SELinux label from the existing cache directory, incremental deployment probes the real data directory through the ordinary shell, one root-adbd request, and common non-interactive `su` command forms, then fixes the first fully usable mode. Preparing the startup agent does not require the app process to be online, so Direct Overlay can deliver files before ordinary Apply Changes reaches ready state.

Files created through these Direct permission modes may lack the app's dynamic SELinux categories. Jugg makes overlay and request files inherit the existing `code_cache` label, while JVMTI Agent `.so` files use the `apk_data_file` type that Android app domains may execute. Output from SELinux tools remains repair diagnostics and does not enter the write script's success result.

## Relationship to Apply Changes

Direct Overlay and ordinary Apply Changes use the same deployment data, target APK ownership, and overlay state. They differ only at the transfer stage.

Ordinary Direct Overlay and the Direct sandbox used for system apps both deliver the current incremental files as a complete batch. Official Apply Changes retains its existing slicing. Jugg reuses the current deployment’s sandbox capability check before slicing, so resources are not split before being sent through a Direct channel. Direct writes emit a heartbeat but still use ADB calls with timeouts; a transport timeout can still fail deployment.

| Stage | Ordinary Apply Changes | Direct Overlay |
|---|---|---|
| Device prerequisite | The app has entered online deployment state | The app may be not ready, but the sandbox and checkpoint must be accessible |
| Class and resource input | The same overlay update | The same overlay update |
| File write | Android Studio online deployment channel | ADB push plus writes through the probed sandbox mode |
| Lifecycle action | Determined by the outer deployment flow | Still determined by the outer deployment flow |
| State commit | Updates cache and overlay ID after success | Updates the same state after success |

Direct Overlay is not another hot-fix format and does not change whether a class originally belongs to online replacement or Hot Fix. Artifacts that require an app restart still restart after writing, and an ordinary Hot Reload that requires Activity recreation still performs that lifecycle action.

## Recovery boundary after a write failure

Direct Overlay divides failures into two categories:

- **Failure before writing**: Device state validation, agent preparation, or payload construction fails before the overlay directory changes. Ordinary Direct Overlay can still use the online channel when Apply Changes prerequisites remain compatible; a Direct transport with confirmed `run-as`, UID, or SELinux-label incompatibility reports the failure directly.
- **Failure after writing starts**: The script has begun deleting or overwriting files, so the directory may be partially committed and the old checkpoint cannot be trusted.

The second category does not immediately switch back to ordinary Apply Changes. Later recovery disables Direct Overlay and uses normal state validation after launching the app. When required, it reinstalls the APK and clears the overlay to establish a trusted baseline again.

## Boundaries

Direct Overlay participates only when all of the following conditions are met:

- The feature switch and current caller allow it.
- The device has not entered ordinary ready deployment state, or failure recovery explicitly requests this path.
- The current Run is not an install and deployment data is not empty.
- A deployment cache exists.
- The device overlay ID matches the cache record.
- The app sandbox is accessible through the selected `run-as`, ordinary-shell, root-adbd, or non-interactive-`su` mode.

When `run-as`, the UID, or the SELinux label is incompatible, Jugg can deliver classes, resources, and assets directly. Pure method-body changes can be replaced online. On Android 11 or later, resource, asset, and mixed code/resource changes refresh resources in the current process and recreate the Activity while the process keeps running. If the runtime refresh fails, Jugg restarts the app so the committed overlay takes effect in the new process. Android 8–10 and compatible deployment retain resource paths that require a process restart. Manifest and native-library changes continue to use APK updates and installation. Failed permission probes or a missing deployment cache cause an explicit failure.

If state cannot be read, Jugg returns to normal validation. If state clearly does not match, it enters recovery. Direct Overlay never bypasses the checkpoint merely to reduce waiting.

## Related pages

- [Incremental deployment overview](./deploy-strategy.md)
- [Classes and overlays in Apply Changes](./apply-changes.md)
- [Deployment state and recovery](./deploy-state-recover.md)
- [Deployment self-healing](./deploy-self-healing.md)
- [Direct Overlay capability](../capabilities/deploy/direct-overlay.md)
- [Recover and Retry capability](../capabilities/deploy/recover-and-retry.md)
