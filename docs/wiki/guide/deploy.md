---
title: Deployment results
description: Understand the deployment stage inside Jugg Run, including Hot Reload, Hot Fix, state recovery, reinstallation, and multi-device results.
status: active
tags:
  - guide
  - deploy
---

# Deployment results

Jugg deployment starts after the compilation stage of Run succeeds. For everyday use, start with [Run an app](./run.md); there is no need to compile and deploy manually as separate steps. This page explains the Hot Reload, Hot Fix, installation, state recovery, reinstallation, and multi-device results shown at the end of a run.

## When to read this page

Use this page when:

- Run completed compilation, but deployment or launch failed.
- You want to understand why the current result is Hot Reload, Hot Fix, Install, or Clean Reinstall.
- You need to understand compatibility mode, multi-device deployment, or Restart behavior.
- You explicitly call `jugg deploy`, `jugg clean-reinstall`, or `jugg restart` through CLI / MCP.

## Which deployment Jugg selects

| Deployment result | What you observe | Common trigger |
|---|---|---|
| Install | Install the APK and launch the app | First run, after a Gradle build, or when deployment state must be rebuilt |
| Hot Reload | Keep the app process running; the current implementation usually recreates the Activity | Small code changes such as method bodies, or resource / asset changes |
| Hot Fix | Restart the app for changes to take effect | Class structure changes, static-initialization-related changes, or compatibility deployment |
| Compat Hot Fix | Use the classic hot-fix path and restart | The user enabled compatibility deployment, or Jugg detected that the current device requires it |
| Clean Reinstall | Clear app data and reinstall the APK | Explicit data cleanup, installation testing, or baseline recovery |

> [!NOTE]
> Hot Reload does not re-execute all code. After changing startup logic, singleton initialization, static / companion members, or Kotlin top-level declarations, an app that was not restarted retains initialized state from the old process.

## When the app restarts

Jugg decides based on the change type:

- Changes to `res` or `assets` use an overlay first.
- Method-body-only changes with unchanged class, method, and field signatures use Hot Reload first.
- Changes to class structure, fields, method signatures, inheritance, or changes requiring a full overlay use Hot Fix and restart the app.
- Compatibility mode uses the classic hot-fix path and restarts the app.
- Jugg Debug always restarts the app in debug mode so the process waits for debugger attach.

If you changed logic that runs only once during process startup, click Restart or use `jugg restart` even when the run reports Hot Reload.

## Compatibility mode

Compatibility mode makes a specified device use the classic hot-fix path instead of preferring online Hot Reload. It is mainly intended for devices that repeatedly fail on the normal deployment path.

Common signals include:

- Jugg explicitly reports that it needs to `fallback to compat deploy`.
- Failures such as `agent no response` or `deploy timeout` remain unrecoverable after retry.
- The same project fails repeatedly on only one device.
- After deploying resources, the app repeatedly has resource-read errors, `AssetManager`-related crashes, or launch failures.
- The app has its own resource-loading, class-loading, or hot-fix hooks, and the result after normal Hot Reload is unexpected.

After connecting the device, enable compatibility deployment for that device in More Options. The setting is persisted by device and applies across projects.

## Clean Reinstall versus Gradle

If you only want one complete build, use the direct fallback action or `jugg gradle-build`.

If you also need to clear app data and reinstall, use Clean Reinstall. It combines clearing data, reinstalling the APK, and restoring Jugg's incremental deployment state in one operation, avoiding the loss of deployment records caused by clearing data manually in system settings.

> [!WARNING]
> Clearing app data directly on the device also removes incremental deployment records. Jugg attempts automatic recovery on the next run, but if the goal is to test with cleared data, use Clean Reinstall.

## Multi-device deployment

Multi-device deployment runs one device at a time. If any device fails, the entire run is reported as failed. When the failure permits fallback and automatic fallback is enabled, Jugg falls the entire Run back to Gradle instead of rerunning only the failed device.

Debug attach currently supports only one device. Select only one target device for Jugg Debug.

## Where to look first when deployment fails

| Symptom | First action |
|---|---|
| The app is not running or is not debuggable | Confirm that the app is a debug build and ADB is not occupied by another Android Studio instance |
| Deployment-state recovery failed | Try running again; if it still fails, use Clean Reinstall |
| `MISSING_AGENT_RESPONSE` or deployment timeout | Check whether the current run already retried; if it repeats on the same device, try compatibility mode |
| No file changes detected | Cancel and run again; Sync once if necessary |
| Code did not take effect after deployment | Restart the app or compare with an explicit Gradle build |

Log entry point:

```bash
build/jugg/log/compile_latest.log
```

## Related pages

- [Run an app](./run.md)
- [Deployment strategies](../concepts/deploy-strategy.md)
- [Classes and overlays in Apply Changes](../concepts/apply-changes.md)
- [APK updates and installation](../concepts/apk-update-and-install.md)
- [How Direct Overlay deployment works](../concepts/direct-overlay.md)
- [Deployment capabilities](../capabilities/deploy/)
- [Deployment history and cache](../capabilities/deploy/deploy-history-cache.md)
- [Hot Reload](../capabilities/deploy/hot-reload.md)
- [Clean Reinstall](../capabilities/deploy/clean-reinstall.md)
- [Recover and Retry](../capabilities/deploy/recover-and-retry.md)
- [The app cannot install, launch, or enter Debug](../troubleshooting/app-cannot-run.md)
- [The app crashes after deployment](../troubleshooting/runtime-crash.md)
