---
title: Deployment self-healing
description: Explains how Jugg recovers deployment after incremental compilation succeeds through limited retry, activation-mode switching, recovery, and reinstallation of the current APK.
status: active
tags:
  - concept
  - deploy
  - recover
---

# Deployment self-healing

After incremental compilation succeeds, Jugg already has the current local artifacts such as classes, DEX, resources, or assets. Deployment failures such as temporary ADB disconnection, a process that cannot receive classes, or mismatched device checkpoints do not mean these artifacts must be recompiled.

Deployment self-healing preserves generated artifacts and first changes transfer conditions, activation method, or device state. Only when those recovery paths still cannot produce a trusted result does it end the Run or pass Gradle fallback eligibility to the Run layer.

## Recovery scope expands with the failure boundary

```text
deployment fails
  -> transfer condition is recoverable: limited retry
  -> current process cannot apply changes: switch to Hot Fix or compatibility deployment
  -> device state is untrusted: recover
  -> recovery cannot restore state: reinstall the current APK
  -> still fails: end the Run, or let the Run layer decide Gradle fallback
```

Each step must change the condition that caused the failure. Jugg does not retry indefinitely when the same command keeps failing in the same state.

## Transfer failures retry only known recoverable errors

Temporary ADB disconnection, recoverable I/O errors, or deployment timeouts can reuse the original deployment data. Jugg waits for the device connection to recover, reduces the number of overlays per transfer, or expands to reinstallation after a limited number of attempts.

If Direct Overlay fails before modifying the overlay directory, it can return to ordinary Apply Changes. If it fails after writing begins, the device may be partially committed and the original checkpoint cannot be reused. Later recovery disables Direct Overlay and clears state or reinstalls first. See [Direct Overlay deployment](./direct-overlay.md) for the boundary.

## Change the activation method when the current process cannot apply changes

If class redefinition reports an unmodifiable class, online replacement returns an internal error, or the app must restart, Jugg converts classes originally prepared for online application into Hot Fix data and loads them after restarting the app.

If the agent does not respond, JVMTI is unavailable, or the device environment is unsuitable for ordinary Apply Changes, Jugg switches to compatibility deployment. Compatibility deployment reorganizes existing class and resource artifacts without analyzing source code again or rerunning compilation.

## Recover when checkpoints do not match

Incremental data is based on the last successful state. Continuing to write when local deployment history, the Android Studio deployment cache, and the device overlay ID point to different results would create an unverified mixed state.

Recovery first checks whether the device can still accept the expected difference. If state can be realigned, the current deployment continues. If the app is missing, was externally reinstalled, or fails validation, recovery expands to reinstalling the current APK. See [deployment state and recovery](./deploy-state-recover.md) for the meaning of the three checkpoints.

## Reinstallation repairs device state

Reinstallation uses the current trusted APK to return the device to a known starting point. After installation, Jugg rebuilds the deployment cache and overlay ID, then reorganizes already compiled classes, resources, and assets from the current Run.

This reinstall repairs device state and normally does not run Gradle. A user-initiated Clean Reinstall also clears app data and is suitable for explicitly testing a clean installation environment.

## The Run layer decides whether to return to Gradle

When retry, Hot Fix, compatibility deployment, recovery, and reinstall all fail, the deployment result carries the actual failure reason and whether Gradle fallback is allowed.

If automatic fallback is enabled and every failed device permits it, the Run layer reruns a full Gradle build and installation. User cancellation, a lost device, installation blocked by the system, or another failure that cannot be recovered safely ends directly without fabricating success or expanding the scope forcibly.

In a multi-device Run, Gradle fallback applies to the entire Run. Jugg cannot put only the failed device on a new APK baseline while leaving other devices on the old incremental result.

## Related pages

- [Incremental deployment overview](./deploy-strategy.md)
- [Direct Overlay deployment](./direct-overlay.md)
- [Compatibility deployment](./compat-deploy.md)
- [Deployment state and recovery](./deploy-state-recover.md)
- [APK update and installation](./apk-update-and-install.md)
- [Recover and Retry capability](../capabilities/deploy/recover-and-retry.md)
- [Clean Reinstall capability](../capabilities/deploy/clean-reinstall.md)
- [Gradle fallback and baseline rebuild](./gradle-fallback-baseline.md)
