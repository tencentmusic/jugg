---
title: Recover and Retry
description: Explains how Jugg recovers and retries after device-state mismatches, deployment failures, or compatibility problems.
status: active
tags:
  - capability
  - deploy
  - recover
  - retry
---

# Recover and Retry

Recover and Retry protect the incremental deployment baseline. When device state is unknown, the overlay ID does not match, the device is transiently offline, JVMTI is incompatible, or installation fails, Jugg first restores trustworthy state and then chooses retry, compatible deployment, Hot Fix, or reinstallation.

## Signals that trigger automatic recovery or retry

| Failure or state signal | Current support | Handling |
|---|---|---|
| App is not installed or `pm path` is missing | Supported | Reinstalls the APK |
| History/cache/device overlay does not match | Supported | Recovers or reinstalls after dry deploy fails |
| Transient offline state | Supported | Waits for ADB transport to recover, then retries |
| JVMTI unmodifiable class or redefiner error | Supported | Falls back to Hot Fix and redeploys |
| Agent does not respond or deployment times out | Supported | Checks JVMTI compatibility and uses compatible deployment when needed |
| Dirty Direct Overlay failure | Stops false fallback | Prevents old Apply Changes from continuing in a partially committed state |
| Invalid APK during installation | Recovery supported | Uninstalls the related applicationId and reinstalls |

> [!NOTE]
> Recover is not a simple retry of the same command. It first determines whether the device remains at a trustworthy checkpoint, then reinstalls or changes deployment strategy when it does not.

## How Recover takes effect

```text
Deployment state requires recovery
  -> Optionally clear data
  -> Attempt dry deploy or a Direct Overlay recovery check
  -> State matches: continue incremental deployment
  -> State does not match or app is missing: reinstall
  -> Reset DeployFileManager state after reinstall succeeds
```

Dry deploy verifies whether the device, cache, and history can still accept incremental deployment. Direct Overlay recovery participates when both the setting and caller allow it. If a direct deployment itself fails and enters retry, recovery disables Direct Overlay and uses the legacy start-app + dry-deploy path instead.

## How Retry takes effect

```text
Deployment fails
  -> Classify the failure signal
  -> For an offline state recoverable in place, wait first
  -> Change the payload to Hot Fix or compatible deployment when possible
  -> Recover first when state does not match
  -> Return failure and fallback eligibility to the Run layer when recovery is still impossible
```

The Run layer receives a `DeployTaskResult` for each device. If a failure permits fallback and automatic fallback is enabled, the entire Run switches to Gradle and executes again rather than rerunning only one device.

## Related pages

- [Deployment results](../../guide/deploy.md)
- [Deployment state and recovery](../../concepts/deploy-state-recover.md)
- [Clean Reinstall](./clean-reinstall.md)
- [Direct Overlay](./direct-overlay.md)
- [Deployment self-healing](../../concepts/deploy-self-healing.md)
- [Deployment history and cache](./deploy-history-cache.md)
- [JVMTI Runtime](./jvmti-runtime.md)
