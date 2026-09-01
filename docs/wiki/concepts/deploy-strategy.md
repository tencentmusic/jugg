---
title: Deployment strategy
description: Starting from incremental compilation artifacts, explains how Jugg selects Apply Changes, APK update, compatibility deployment, state recovery, and lifecycle actions.
status: active
tags:
  - concept
  - deploy
---

# Deployment strategy

Incremental compilation produces local artifacts for the current changes, such as DEX, resource overlays, assets, Manifest patches, or already generated native libraries. Deployment must apply these artifacts to the latest trusted APK and device state, then decide whether to keep the process, recreate the Activity, restart the app, or reinstall it.

Jugg calls this process incremental deployment. It does not use one fixed hot-update mechanism; instead, it combines multiple paths according to how each artifact can take effect and the current device state.

## Decisions required by an incremental deployment

```text
current incremental compilation artifacts
  -> which source files still require recompilation
  -> how classes, overlays, and APK files are classified
  -> whether the APK must be updated and installed
  -> whether device state can accept another difference
  -> use Apply Changes, Direct Overlay, or compatibility deployment
  -> recreate Activity / restart app / launch newly installed app
  -> commit deployment history after everything succeeds
```

These decisions have a defined order. Deployment data must be complete and device state must be trusted before the new overlay and history can advance together. A failure at any step must not record the current result as the next baseline prematurely.

## Artifacts determine how changes take effect

| Current artifact | Primary path | User-visible result |
|---|---|---|
| Class with unchanged structure, such as a method-body change | Apply Changes class update | Keeps the app process; the current implementation usually recreates the Activity |
| New class | Apply Changes new class | Delivered with the incremental overlay; usually recreates the Activity |
| Class with structural changes | Hot Fix DEX | Loaded after the app restarts |
| Overlay such as `res/**`, `assets/**`, or `resources.arsc` | Apply Changes or Direct Overlay | Recreates the Activity or restarts the app when required |
| Manifest, associated resource table, or an already generated native library | Written back to the latest Gradle APK and re-signed | Installs the updated APK, then continues the remaining incremental deployment |
| Classes and resources on a compatibility device | Compatibility hot-fix artifacts | Loaded after the app restarts without relying on online replacement in the current process |

See [deployment data and impact analysis](./deploy-data-and-impact.md) for the detailed classification. See [classes and overlays in Apply Changes](./apply-changes.md) for how Apply Changes combines classes and overlays.

## Device state determines whether differences can continue to accumulate

Incremental deployment sends only changes relative to the last successful state, so local deployment history, the Android Studio deployment cache, and the device overlay ID must all point to the same result.

When they match, Jugg can apply the current difference. If state is unknown, the app was externally reinstalled, or overlay IDs do not match, Jugg performs recovery first. If recovery cannot establish a trusted state, it reinstalls the current APK and rebuilds the baseline. This repairs device state and usually does not require project recompilation.

See [deployment state and recovery](./deploy-state-recover.md) for the three checkpoints and their commit order.

## Online deployment, direct writes, and compatibility deployment

After device state is trusted, Jugg selects the transfer and activation method.

| Path | Condition | What changes |
|---|---|---|
| Apply Changes | The app has entered online deployment state | Applies classes and overlays through the Android Studio deployment channel |
| Direct Overlay | The app is not ready, but cache and device checkpoints can be validated | Writes the same overlay directly; lifecycle handling still proceeds through the normal deployment flow |
| Compatibility deployment | JVMTI is unavailable, the agent does not respond, a customized system requires the compatibility path, or the user forces it | Changes online activation into loading after an app restart |

Direct Overlay changes only the file transfer mechanism. Compatibility deployment changes when artifacts take effect. Both continue to use the current incremental compilation result and are not equivalent to a Gradle build or full reinstall.

## Activity recreation and app restart are different boundaries

For ordinary non-empty incremental data that does not require an app restart, Jugg currently uses Apply Changes and Restart Activity. The Activity runs its lifecycle again while the app process remains alive. A `HOT_RELOAD` log entry therefore means the current Run still used online incremental deployment; it does not mean that the Activity was necessarily preserved.

The following content requires restarting the entire app:

- Class structural changes and other Hot Fix DEX files.
- Compatibility deployment artifacts.
- Classpath resources at the APK root.
- Content with process-level caches, such as Compose resources.
- Debug or the “always restart after deployment” setting.

Restarting the app discards in-memory process state but lets the ClassLoader, Resources, and startup agent initialize again in the new process.

## How deployment failure expands the recovery scope

After a deployment failure, Jugg first changes the smallest condition that caused it: it waits for ADB to recover from a brief disconnection, switches to Hot Fix when online class application fails, switches to compatibility deployment when JVMTI is unavailable, and performs recovery when checkpoints do not match. Only if recovery still cannot establish a trusted state does it reinstall the current APK.

If none of these steps succeeds and the failure allows automatic fallback, the Run layer reruns the Gradle build. See [deployment self-healing](./deploy-self-healing.md) for the complete recovery order.

## Incremental deployment topics

| Page | Main question |
|---|---|
| [Deployment data and impact analysis](./deploy-data-and-impact.md) | How compilation artifacts are classified and why some source files require more compilation |
| [Classes and overlays in Apply Changes](./apply-changes.md) | How classes and resources enter an online incremental update and why the Activity is usually recreated |
| [APK update and installation](./apk-update-and-install.md) | Why Manifest and native libraries must be written back to the APK and installed |
| [Direct Overlay deployment](./direct-overlay.md) | How an overlay is written directly when the device is not ready without committing a partial state |
| [Compatibility deployment](./compat-deploy.md) | Why some devices load incremental artifacts only after a restart |
| [Deployment state and recovery](./deploy-state-recover.md) | How history, cache, and overlay ID jointly maintain device state |
| [Deployment self-healing](./deploy-self-healing.md) | How retry, activation-mode switching, recovery, and reinstallation contain failures |

## Related pages

- [Deployment results](../guide/deploy.md)
- [Deployment capabilities](../capabilities/deploy/)
- [In-app Jugg Runtime](./jugg-runtime.md)
- [Jugg JVMTI Agent](./jugg-jvmti-agent.md)
- [Gradle fallback and baseline rebuild](./gradle-fallback-baseline.md)
