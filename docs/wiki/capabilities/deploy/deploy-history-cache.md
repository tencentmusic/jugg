---
title: Deployment history and cache
description: Explains the recovery, reinstallation, or fallback behavior users see when deployment history and caches are inconsistent.
status: active
tags:
  - capability
  - deploy
  - cache
---

# Deployment history and cache

Deployment history and caches determine whether a device can continue incremental deployment. Users normally do not operate on this state directly, but it determines whether the current run continues with Hot Reload or Direct Overlay, or first uses Recover, Clean Reinstall, or Gradle fallback.

## User-visible behavior

| Scenario | Current support | User-visible result |
|---|---|---|
| History, cache, and device checkpoint match | Supported | Continues incremental deployment |
| Checkpoint does not match | Recovery supported | Enters Recover and reinstalls the APK when needed |
| The app was manually uninstalled or overwritten | Recovery supported | Reinstalls and rebuilds the device baseline |
| State is untrustworthy before Direct Overlay | Interception supported | Does not write the overlay directly and switches to Recover or Reinstall |
| Deployment succeeds | Commit supported | Later runs continue reusing the new incremental baseline |

> [!IMPORTANT]
> If deployment history or the device checkpoint is inconsistent, Jugg restores a trustworthy state before writing a new overlay to an unknown state.

## When this affects the current run

- Device state may diverge from local history after switching devices, manually installing an APK, clearing app data, or overwriting an installation.
- Direct Overlay checks the deployment cache and overlay ID first. It does not force a write when validation fails.
- Reinstall clears old deployed data, resource APKs, and staging state before establishing a new deployment baseline.

For the state model of deployment history, the deployment cache, and overlay IDs, see [Deployment state and recovery](../../concepts/deploy-state-recover.md).

## Related pages

- [Deployment results](../../guide/deploy.md)
- [Deployment state and recovery](../../concepts/deploy-state-recover.md)
- [Hot Reload](./hot-reload.md)
- [Direct Overlay](./direct-overlay.md)
- [Direct Overlay deployment](../../concepts/direct-overlay.md)
- [Recover and Retry](./recover-and-retry.md)
- [Multiple APKs](./multi-apk.md)
