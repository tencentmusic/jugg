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
2. If a physical device is selected, first confirm that `adb device` shows it as online. If not, kill the adb process and try again.

## Q: Deployment fails with `Try recover deploy state failed`, `MISSING_AGENT_RESPONSES`, `AGENT_ATTACH_FAILED`, or `deploy timeout`

This usually means that the device's adb state is abnormal.

1. Confirm that `adb device` shows the device as online. If not, kill the adb process and try again.
2. Close other Android Studio instances or ADB tools that may be using the same device.
3. Test whether `adb install` can install the APK successfully.
4. If Android Studio's built-in `Attach Debugger to Android Process` also fails, restore ADB functionality first.
5. If the problem remains reproducible, [report the issue](../guide/report-issue.md) to the maintainers.

## Q: What should I do when Jugg reports `MISSING_AGENT_RESPONSES` or `AGENT_ATTACH_FAILED`?

This means that the Apply Changes agent did not respond after it was attached. Jugg retries first and switches to compatibility deployment when it detects a JVMTI compatibility problem.

If the same device repeatedly encounters this problem, enable compatibility mode for that device by following [Compatibility deployment for a device](../guide/compat-device.md), then run again.

## Q: What should I do when Jugg reports `Got deploy timeout exception, retry after 5s`?

Jugg performs a limited number of retries after a deployment timeout. If deployment still fails after those retries, uninstall the app from the device and deploy again. You can also reinstall it with [Clean Reinstall](../guide/clean-data.md).

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
