---
title: Compatibility deployment
description: Enable compatibility deployment for a device when incremental deployment repeatedly fails on that device.
status: active
tags:
  - guide
  - device
  - compat
---

# Compatibility deployment

Compatibility deployment makes a specific device avoid the online hot-reload path and use a broader hot-fix path that usually restarts the app. Enable it when deployment repeatedly fails on one device or Jugg explicitly says that compat deploy is required.

## When to consider enabling it

Check Jugg output and deployment logs instead of relying on a single keyword. Consider compatibility deployment when:

- Jugg reports that it needs to `fallback to compat deploy`, or recovery still fails after `agent no response` / `deploy timeout` appears in deployment logs.
- The same project works on other devices but repeatedly fails on one device.
- After deploying resources, the app repeatedly has resource-read errors, `AssetManager`-related crashes, or launch failures.
- The app has its own resource-loading, class-loading, or hot-fix hooks, and the result after normal Hot Reload is unexpected.

If only the current code result is unexpected, compare it with Restart or a Gradle build first. Do not classify it as a device compatibility issue solely because `JVMTI`, `Apply Changes`, or `classloader` appears in the logs.

The entry point is in More Options. After connecting a device, an option like this appears:

```text
Force use compat deploy for <device>
```

After you enable or disable it, Jugg reinstalls the target app on the next run instead of reusing the old deployment state.

## The setting applies per device

Compatibility deployment records are bound to devices. Another device does not inherit the setting automatically, while the same device may continue using it across projects.

Do not leave compatibility deployment enabled on every device. It reduces opportunities for online Hot Reload, so normal hot updates are usually slower.

## Related pages

- [How compatibility deployment works](../concepts/compat-deploy.md)
- [Run an app](./run.md)
- [Select multiple devices](./multi-device.md)
- [Clear app data](./clean-data.md)
- [HarmonyOS compatibility deployment](../capabilities/deploy/harmonyos-compat.md)
- [The app cannot install, launch, or enter Debug](../troubleshooting/app-cannot-run.md)
- [The app crashes after deployment](../troubleshooting/runtime-crash.md)
