---
title: JVMTI Runtime
description: Explains JVMTI agent preparation, compatibility detection, and runtime boundaries in the Jugg deployment flow.
status: active
tags:
  - capability
  - deploy
  - jvmti
---

# JVMTI Runtime

JVMTI Runtime supports Jugg after deployment. It prepares the Jugg agent on the device and in the app sandbox, checks JVMTI availability after the app restarts, and switches incompatible devices to a compatible deployment strategy.

## Agent preparation and availability detection

| Scenario | Current support | User-visible result |
|---|---|---|
| Prepare the Jugg agent after incremental deployment | Supported | Adds the startup agent for the target app after deployment completes |
| Prepare the Apply Changes startup agent | Supported | The Direct Overlay path can add the agent required for online replacement |
| Detect JVMTI availability | Supported | Reports whether JVMTI is available after the app restarts |
| 32-bit and 64-bit apps | Supported | Automatically selects the agent matching the target process architecture |
| Runtime correction hooks | Supported | Handles matched ClassLoader, resource, and system compatibility differences during app startup |
| Record an incompatible app/device pair | Supported | Later deployments enter the compatible path directly instead of repeatedly attempting unavailable online replacement |

> [!NOTE]
> An install contains no incremental deployment files and normally does not trigger an agent push after deployment. Agent detection depends on the startup agent being loaded by the system after the app restarts.

## How it takes effect

```text
Incremental deployment completes
  -> Prepare the Jugg startup agent when needed
  -> Restart or start the app
  -> Probe JVMTI availability
  -> Record the current app/device pair when unavailable
  -> Retry with compatible deployment or use the compatible path directly in later deployments
```

The agent must be prepared after deployment and checked after the app restarts. For the exact timing and division of responsibility with the Apply Changes Agent, see [Jugg JVMTI Agent](../../concepts/jugg-jvmti-agent.md).

## How compatible deployment is triggered

After deployment fails, the Retry flow checks whether the failure may come from a JVMTI compatibility problem. If the app writes a not-available flag, Jugg records the current app/device pair and enters compatible deployment directly in later runs, avoiding repeated attempts to use unavailable runtime capabilities.

## Related pages

- [Jugg JVMTI Agent](../../concepts/jugg-jvmti-agent.md)
- [In-app Jugg runtime](../../concepts/jugg-runtime.md)
- [Restart](./restart.md)
- [Recover and Retry](./recover-and-retry.md)
- [Direct Overlay](./direct-overlay.md)
- [Hot Reload](./hot-reload.md)
