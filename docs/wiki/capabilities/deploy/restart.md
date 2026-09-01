---
title: Restart
description: Explains when Jugg restarts the app or Activity after deployment and how those changes take effect.
status: active
tags:
  - capability
  - deploy
  - restart
---

# Restart

Restart makes deployment results that require a lifecycle refresh or process reload take effect. Jugg decides whether to restart the Activity, restart the app, or keep the current process running based on deployment data, Run configuration, and the Debug entry point.

## Restart triggers

| Scenario | Current support | Restart behavior |
|---|---|---|
| Refresh the current UI after Apply Changes | Supported | Restarts the Activity |
| Hot Fix class or push-only overlay | Supported | Restarts the app |
| User selects always restart | Supported | Restarts the app after deployment succeeds |
| Debug executor starts | Supported | Restarts the app after successful deployment, then attaches the debugger |
| Detect JVMTI after the first agent push | Supported | Restarts the app so the system loads the startup agent |
| Start the app after install | Supported | The deployment flow starts the app after installation |

## How it takes effect

```text
Generate deployment data
  -> Determine the deployment action and lifecycle boundary
  -> Push the JVMTI agent when needed
  -> restart app / restart activity / start app according to deployment type and user settings
  -> Attach the Java debugger in Debug scenarios
```

Hot Fix classes, push-only overlays, and some process-level caches require an app restart. Regular non-empty incremental deployments that do not require an app restart recreate the Activity. The Debug entry point restarts the app after deployment by default so that the debugging session starts from the new process state.

## Relationship to deployment strategies

| Strategy | Restart behavior |
|---|---|
| Code Swap | Does not require an app restart; a regular non-empty deployment still usually recreates the Activity |
| Full Swap | Restarts the Activity |
| Hot Fix | Restarts the app |
| Clean Reinstall | Starts the app after reinstallation |
| JVMTI agent update | Usually requires an app restart before detection |

## Related pages

- [Restart the app](../../guide/restart-app.md)
- [Deployment strategy](../../concepts/deploy-strategy.md)
- [Full Swap](./full-swap.md)
- [Hot Reload](./hot-reload.md)
- [JVMTI Runtime](./jvmti-runtime.md)
- [Clean Reinstall](./clean-reinstall.md)
- [Classes and overlays in Apply Changes](../../concepts/apply-changes.md)
