---
title: Multiple devices
description: Explains Jugg's sequential deployment and result aggregation for multiple selected devices.
status: active
tags:
  - capability
  - deploy
  - multi-device
---

# Multiple devices

Jugg can deploy the same compilation result to multiple devices selected in a Run configuration. Deployment runs on each device in selection order and then aggregates success state, the highest-priority deploy type, failure reasons, and Gradle fallback eligibility.

## Multi-device deployment behavior

| Scenario | Current support | Deployment strategy |
|---|---|---|
| Run on multiple devices | Supported | Compiles once and deploys to each device |
| Different devices require different deployment strategies | Supported | Each device independently performs recover, retry, and install |
| Some devices fail | Aggregation supported | Combines failure reasons and determines fallback eligibility for the entire run |
| Automatic Gradle fallback | Supported | Reruns the entire run when every failure permits fallback |
| Display multi-device deploy type | Supported | Uses the highest priority: INSTALL > EMBEDDED > COMPAT_HOT_FIX > HOT_FIX > HOT_RELOAD |

> [!NOTE]
> Multi-device fallback applies to the entire Run. It does not recompile or redeploy only the failed device.

## How it takes effect

```text
Select multiple devices for Run
  -> JuggCompileHelper compiles once
  -> Call deployDevice() in selection order
  -> Each device calls JuggDeployerHelper.deploy()
  -> Aggregate the DeployTaskResult list
  -> All succeed: finish the run
  -> Some fail and fallback is allowed: force Gradle and rerun
  -> Otherwise display failure reasons
```

Each device has its own deployment cache, overlay ID, and ready state. The same Run therefore selects Hot Reload, recovery, or reinstallation separately for each device, while the Run layer produces one final result.

## Relationship to deployment history

Multi-device deployment does not commit temporary scoped data from one device directly as global file history. Jugg advances history and overlay checkpoints through successful deployment paths so that device state does not leak between devices.

## Related pages

- [Select multiple devices](../../guide/multi-device.md)
- [Recover and Retry](./recover-and-retry.md)
- [Deployment history and cache](./deploy-history-cache.md)
- [Clean Reinstall](./clean-reinstall.md)
- [Gradle fallback](../compile/gradle-fallback.md)
