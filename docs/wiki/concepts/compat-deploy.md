---
title: Compatibility deployment
description: Explains why Jugg needs compatibility deployment and how it switches from online hot reload to a more conservative hot-fix path.
status: active
tags:
  - concept
  - deploy
  - compat
---

# Compatibility deployment

An everyday Run prefers incremental compilation and Apply Changes. Method-body changes can be replaced online through JVMTI, while resources and assets take effect through overlays. This path requires a working device runtime, correct ClassLoader initialization timing, Apply Changes communication, and a usable resource loading path. When a device does not meet these conditions, Jugg redirects the current incremental artifacts through compatibility deployment: it restarts the app so that the artifacts take effect during the next launch.

## Why online hot reload can fail

Online hot reload requires the running app to receive incremental DEX or overlays and use JVMTI to apply replaceable classes to the current process. This is not always available on real devices.

- Some devices or system versions do not provide usable JVMTI, so classes cannot be replaced online.
- A customized system may initialize the ClassLoader early, before the incremental DEX search path is connected.
- The Apply Changes agent may not respond, communication may time out, or the online redefiner may return an unrecoverable error.
- The app may have its own resource loading, class loading, or hot-fix hooks that make ordinary overlays or online replacement unstable.

These conditions do not mean incremental compilation failed. The artifacts can still be delivered, but the current process cannot be expected to accept them directly. Compatibility deployment makes one focused change: it moves activation from the current process to the next app launch.

## Compatibility deployment compared with ordinary hot reload

| Comparison | Ordinary hot reload | Compatibility deployment |
|---|---|---|
| How classes take effect | Attempts online replacement for classes whose structure did not change | Converts them into hot-fix DEX loaded after restart |
| Resources / assets | Prefer online overlay updates | Use compatibility hot-fix artifacts instead of relying on online overlays |
| Runtime dependencies | Depends on JVMTI, the Apply Changes agent, and a deployable process state | Depends on DEX, resource, and native library loading after app restart |
| User-visible result | Avoids restarting the app when possible and recreates the Activity when needed | Usually requires an app restart |
| Applicable scenarios | The device supports online replacement and the change has a small boundary | JVMTI is unavailable, the agent does not respond, the user forces the mode, or the device behaves unstably |

Compatibility deployment is not a full Gradle reinstall. It still uses Jugg incremental compilation results and deployment history, but changes the activation method of artifacts originally prepared for online application.

## When compatibility deployment is used

Compatibility deployment can be selected automatically by Jugg or enabled manually for a device.

Automatic selection comes from runtime and deployment failure signals. When the app starts, the Jugg agent detects whether JVMTI is available and writes an available or unavailable marker into the app cache directory. After the deployment flow reads an unavailable marker, it records the current app and device combination. Later deployments enter the compatibility path directly instead of retrying unavailable online replacement on every Run.

The retry flow can make the same decision. After the agent stops responding, deployment times out, or Jugg emits a signal such as `fallback to compat deploy`, it first checks whether the failure is a JVMTI compatibility problem. If confirmed, the current Run reorganizes its data for compatibility deployment.

The user can also enable the following option for a specific device under More Options:

```text
Force use compat deploy for <device>
```

The setting applies per device. After it is enabled or disabled, the next Run reinstalls the target app so that device state and local deployment history are realigned.

## How current artifacts switch to the compatibility path

Compatibility deployment does not analyze source changes again. It receives already generated incremental deployment data and changes “apply online” into “take effect after restart.”

```text
incremental compilation artifacts
  -> generate current deployment data
  -> determine that the current device requires compatibility deployment
  -> convert online-replaceable classes to hot-fix DEX
  -> convert resource / assets overlays to compatibility resource artifacts
  -> write the compatibility enable signal
  -> deliver artifacts and restart the app
  -> load current artifacts first during app startup
```

The main difference is activation time. Ordinary hot reload attempts to send changes into the current process. Compatibility deployment lets the next app launch load new DEX, resource, or native library paths. This is more suitable for structurally changed classes, already loaded classes, and devices without usable JVMTI.

## Role of the Jugg agent in compatibility detection

The Jugg agent does not force hot reload to work on every device. It performs a real check: whether JVMTI works for this device, app, and launch.

```text
incremental deployment completes
  -> prepare the Jugg agent in the app sandbox when needed
  -> restart or launch the app
  -> the system loads the startup agent
  -> write the JVMTI available / not-available marker
  -> Jugg reads the marker
  -> record a compatibility device and switch paths when unavailable
```

The agent is pushed after deployment because Android Studio may remove an existing agent from the app directory when Apply Changes prepares its startup agent for the first time. If Jugg pushes its agent too early, a later deployment action may delete it. JVMTI detection must also wait for app restart because the startup agent loads only when the process starts.

One signal can be misleading: on some customized systems, a DEX path correction signal only repairs the loading path and does not mean JVMTI is unavailable. Jugg treats the device as a compatibility device only after an explicit unavailable marker is written or the deployment failure flow confirms a compatibility problem.

## How state recovery and retry contain the flow

Compatibility deployment still obeys deployment state. When device state is unknown, Jugg first checks whether local history, the deployment cache, and the device overlay checkpoint match instead of accumulating another artifact set. It performs recovery when state is untrusted and reinstalls only if recovery fails or the app does not exist.

```text
deployment fails or device state is uncertain
  -> determine whether in-place retry is possible
  -> detect whether a JVMTI compatibility problem exists
  -> switch to a compatibility deployment payload when needed
  -> recover or reinstall when state does not match
  -> commit new deployment history after success
```

For this reason, the next Run reinstalls after compatibility deployment is enabled or disabled for a device. Changing compatibility mode changes how artifacts are applied, so device state and local history must first return to the same baseline.

## Costs and boundaries

The primary cost of compatibility deployment is restart. Page state, in-memory state, and part of the debugging context are interrupted, and it is usually slower than hot reload for an ordinary method-body change on a standard device.

It also cannot replace Gradle fallback. Build script changes, dependency changes, untrusted annotation processor results, or an expired complete APK baseline still require Gradle. Compatibility deployment handles a different problem: incremental artifacts already exist, but the current device cannot receive them online reliably.

Do not keep compatibility deployment enabled for every device. Enable it for a specific device that repeatedly shows an unresponsive agent, unavailable JVMTI, or unstable resource or class loading. Disable it and rebuild the deployment baseline after changing devices, upgrading the system, or restoring the environment.

## Troubleshooting entry points

| Symptom | First step |
|---|---|
| Jugg outputs `fallback to compat deploy` | See [Device compatibility deployment](../guide/compat-device.md) and decide whether to keep it enabled for this device |
| `MISSING_AGENT_RESPONSES` / `AGENT_ATTACH_FAILED` | See [App cannot install, launch, or enter Debug](../troubleshooting/app-cannot-run.md) |
| Deployment still fails after enabling compatibility deployment | Run Clean Reinstall or a Gradle installation first to confirm that the APK baseline and device state are trusted |
| Compatibility deployment is slower | Check whether a standard device was left in compatibility mode unnecessarily |
| Resource or class loading differs from expectations | Compare with the Gradle installation result to separate a device compatibility issue from a code or build result issue |

## Related pages

- [Device compatibility deployment](../guide/compat-device.md)
- [Deployment strategy](./deploy-strategy.md)
- [Classes and overlays in Apply Changes](./apply-changes.md)
- [Direct Overlay deployment](./direct-overlay.md)
- [In-app Jugg Runtime](./jugg-runtime.md)
- [Jugg JVMTI Agent](./jugg-jvmti-agent.md)
- [Deployment state and recovery](./deploy-state-recover.md)
- [HarmonyOS (non-HarmonyOS NEXT) compatibility deployment](../capabilities/deploy/harmonyos-compat.md)
- [Recover and Retry](../capabilities/deploy/recover-and-retry.md)
- [JVMTI Runtime](../capabilities/deploy/jvmti-runtime.md)
