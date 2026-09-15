---
title: Installation, launch, or Debug failed
description: Resolve unavailable devices, APK installation failures, app launch failures, deployment recovery failures, and Debug attach failures.
status: active
tags:
  - troubleshooting
  - device
  - debug
---

# Installation, launch, or Debug failed

This page covers cases where the app is not installed, launched, or attached to the debugger after you click Run or Debug. If the app opens but changes do not take effect or it crashes afterward, use the page for that specific symptom.

## Q: Jugg reports No Device

1. If Android Studio has an emulator selected, confirm that the emulator is running. Jugg does not start emulators automatically, which generally provides a better experience when you only need to compile.
2. If a physical device is selected, confirm that `adb devices` shows it in the `device` state. If not, restart ADB and try again.

## App not launched or Recovery failed

This means that Jugg could not find an attachable target app process, or deployment-state recovery did not complete after launch.

1. Confirm that the installed app for the current variant is debuggable, then launch it in the foreground.
2. Confirm that `adb devices` shows the device in the `device` state.
3. Close other Android Studio instances or ADB tools that may be using the same device.
4. Compare with Android Studio's built-in `Attach Debugger to Android Process`. If it also cannot find or attach to the process, restore the app or ADB state first.
5. Run Jugg again. If it still fails, use [Clean Reinstall](../guide/clean-data.md) to rebuild installation and deployment state.

## Try recover deploy state failed

This means that the app installation or data still exists on the device, but Jugg could not restore a state that can continue incremental deployment.

1. Confirm that the device is connected and that the application ID, variant, and current Jugg Run Configuration match.
2. If you recently cleared app data manually, replaced the APK, or switched variants, use [Clean Reinstall](../guide/clean-data.md).
3. Otherwise, run Jugg once more so that it can perform its limited recovery retry.
4. If the problem remains reproducible, keep the current state and [report the issue](../guide/report-issue.md).

## First checks for other deployment failures

1. Confirm that `adb devices` shows the device in the `device` state.
2. Close other Android Studio instances or ADB tools that may be using the same device.
3. Test whether `adb install` can install the APK successfully.
4. If Android Studio's built-in `Attach Debugger to Android Process` also fails, restore ADB functionality first.
5. If the problem remains reproducible, [report the issue](../guide/report-issue.md) to the maintainers.

## Q: What should I do when Jugg reports `MISSING_AGENT_RESPONSES` or `AGENT_ATTACH_FAILED`?

This means that the Apply Changes agent failed to attach or did not respond after attachment. Jugg retries first and switches to compatibility deployment when it detects a JVMTI compatibility problem.

If the same device repeatedly encounters this problem, enable compatibility mode for that device by following [Compatibility deployment for a device](../guide/compat-device.md), then run again.

## Q: What should I do when Jugg reports `Got deploy timeout exception, retry after 5s`?

Jugg tries a smaller resource overlay, waits and retries, and reinstalls the APK on the final retry. If deployment still fails, use [Clean Reinstall](../guide/clean-data.md) to rebuild installation and deployment state.

## Q: APK installation failed

- `INSTALL_FAILED_USER_RESTRICT`: Allow installation from the current source on the device, or remove the enterprise device restriction.
- `INSTALL_FAILED_INVALID_APK`: Run a full Gradle build again, then use Clean Reinstall.
- `The application could not be installed`: First use Android Studio's native Run action to verify whether the same APK can be installed.
- The base APK contains an old Jugg incremental overlay: Generate a new full APK that does not contain old incremental data.

## Related pages

- [Debug guide](../guide/debug.md)
- [Compatibility deployment for a device](../guide/compat-device.md)
- [Clean Reinstall](../guide/clean-data.md)
- [App crashed after deployment](./runtime-crash.md)
