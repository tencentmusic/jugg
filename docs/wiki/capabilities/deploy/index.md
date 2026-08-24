---
title: Deployment capabilities
description: Summarizes Jugg deployment capabilities to show whether the current changes will install, hot-update, restart, or recover deployment state.
status: active
tags:
  - capability
  - deploy
---

# Deployment capabilities

Jugg deployment capabilities apply APKs, DEX, resource overlays, Manifest changes, `.so` files, runtime agents, and other compilation artifacts to target devices. Jugg selects install, Apply Changes, restart, Direct Overlay, or recover/retry according to artifact type, device state, and historical checkpoints.

## Capability overview

### Deployment strategies

| Capability | Current support | Typical result |
|---|---|---|
| [Clean Reinstall](./clean-reinstall.md) | Supports reinstallation and optional app-data cleanup | Re-establishes the APK, deployment history, and overlay baseline |
| [Code Swap](./code-swap.md) | Supports method-body-level class updates that can be replaced online | Keeps the app process running; regular non-empty deployments usually recreate the Activity |
| [Full Swap](./full-swap.md) | Supports Apply Changes that must restart the Activity | Restarts the current Activity after updating code or overlays |
| [Hot Reload](./hot-reload.md) | Supports online incremental overlay and class updates | Keeps the app running and restarts only the Activity when needed |
| [Restart](./restart.md) | Supports restarting the app based on deployment results or user choice | Lets hot fixes, agents, or debugging changes take effect |

### State recovery and complex targets

| Capability | Current support | Typical result |
|---|---|---|
| [Direct Overlay](./direct-overlay.md) | Supports writing overlays directly when the device is not ready | Completes overlay updates without the online Apply Changes transport |
| [Recover and Retry](./recover-and-retry.md) | Supports state recovery, compatible deployment, and failure retries | Avoids continuing hot updates from an inconsistent baseline |
| [Multiple APKs](./multi-apk.md) | Supports routing ownership for base, split, test, and other APKs | Delivers artifacts to the correct APK/applicationId in the same run |
| [Multiple devices](./multi-device.md) | Supports deployment to each selected device | Aggregates success state and failure fallback eligibility |
| [Deployment history and cache](./deploy-history-cache.md) | Maintains Jugg history and the Android Studio deployment cache | Determines whether an overlay checkpoint is trustworthy |
| [JVMTI Runtime](./jvmti-runtime.md) | Prepares the Jugg agent after deployment and detects compatibility | Supports compatible deployment, runtime instrumentation, and later tool capabilities |

> [!IMPORTANT]
> The deployment strategy depends on both the current compilation result and device state. A successful Gradle compilation proceeds to install. A successful Jugg incremental compilation proceeds to incremental deployment; failures enter recover, retry, or Gradle fallback according to failure type.

## How the deployment flow fits together

```text
Run starts
  -> Collect artifacts for the current deployment
  -> Generate deployment data
  -> Choose install / Hot Reload / Hot Fix / Full Swap
  -> Route by device and APK ownership
  -> Run install, Apply Changes, or Direct Overlay
  -> Commit deployment history and the overlay checkpoint after success
```

Users do not need to select a deployment type manually. Jugg chooses the strategy from class-structure changes, resource and APK updates, device readiness, overlay checkpoint consistency, and related information.

## Related pages

- [Deployment results](../../guide/deploy.md)
- [Deployment strategy](../../concepts/deploy-strategy.md)
- [Deployment data and impact analysis](../../concepts/deploy-data-and-impact.md)
- [Classes and overlays in Apply Changes](../../concepts/apply-changes.md)
- [APK updates and installation](../../concepts/apk-update-and-install.md)
- [Direct Overlay deployment](../../concepts/direct-overlay.md)
- [App cannot be installed, started, or debugged](../../troubleshooting/app-cannot-run.md)
- [App crashes after deployment](../../troubleshooting/runtime-crash.md)
- [Limits](../../reference/limits.md)
