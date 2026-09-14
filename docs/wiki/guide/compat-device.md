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

## When compatibility deployment is enabled automatically

When compatibility deployment is allowed globally, Jugg automatically uses it for the following devices or apps:

| Condition | Reason |
|---|---|
| Devices running Android 8 through 10 | The system does not support Jugg's normal resource-overlay switching path |
| Android devices that expose a HarmonyOS version property | Jugg selects the HarmonyOS compatibility path automatically |
| ASUS devices | Jugg avoids known compatibility problems in the normal deployment path |
| A device or specific app previously recorded as compatible | Later runs reuse the corresponding compatibility setting |

You do not need to enable the option manually when one of these conditions matches. If none matches and normal deployment still fails repeatedly, use the entry point below for the current device.

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
