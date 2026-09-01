---
title: Select multiple devices
description: Understand compilation, deployment, Debug, and failure results when running Jugg on multiple devices selected in Android Studio.
status: active
tags:
  - guide
  - device
  - multi-device
---

# Select multiple devices

A normal Jugg Run supports multiple devices. Jugg compiles once, then deploys to each device selected in the Android Studio device selector.

## How to select devices

Select one or more devices from the device selector at the top of Android Studio, then click Jugg Run.

Multi-device runs are useful for checking different system versions, screen specifications, or vendor ROMs at the same time. Each device has its own installation state and deployment cache, so devices in the same run can independently enter Hot Reload, recovery, or reinstall paths.

## How the result is determined

| Situation | Result of the run |
|---|---|
| All devices succeed | The run succeeds |
| Any device fails | The run fails |
| Every failure permits a Gradle fallback | The entire run falls back to Gradle and runs again |

A multi-device fallback applies to the whole run, not only to the failed devices.

## Select only one device for Debug

Jugg Debug currently supports attaching to only one device. Keep one target device selected before clicking Debug.

## Related pages

- [Run an app](./run.md)
- [Debug](./debug.md)
- [Multi-device deployment](../capabilities/deploy/multi-device.md)
- [The app cannot install, launch, or enter Debug](../troubleshooting/app-cannot-run.md)
