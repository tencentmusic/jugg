---
title: Compatible deployment for HarmonyOS (Android-based)
description: Explains how Jugg identifies HarmonyOS devices and automatically uses compatible deployment, including differences from regular Android, HyperOS, and manual compatibility records.
status: active
tags:
  - capability
  - deploy
  - harmonyos
  - compatibility
---

# Compatible deployment for HarmonyOS (Android-based)

Android-based HarmonyOS devices are incompatible with Apply Changes. Jugg identifies HarmonyOS before deployment and selects compatible deployment directly, avoiding an unreliable regular path followed by a failed retry.

## Automatically enabled for every identifiable Android-based HarmonyOS version

Jugg reads the device property `hw_sc.build.platform.version`. Any present, non-empty value identifies a HarmonyOS device; no minimum HarmonyOS version is required.

```text
Read target device properties
  -> The HarmonyOS property is non-empty
  -> Use compatible deployment directly for the current run
  -> After the app restarts, the in-app Jugg runtime loads incremental artifacts
```

## What compatible deployment changes

Regular Hot Reload prioritizes online replacement through Android Studio Apply Changes / JVMTI. Compatible deployment moves classes that would normally be replaceable online into the post-restart Hot Fix path and continues handling resources and other overlays.

As a result, deployments on HarmonyOS more commonly skip hot reload and require an app restart each time.

## Relationship to other compatibility conditions

- Devices below Android 11 also lack the required overlay swap conditions and use compatible deployment.
- Manually selected Force compatible deployment remains effective.
- An app/device combination recorded after an actual JVMTI failure still enters compatible deployment.
- HyperOS can record compatibility problems for a specific app; this does not automatically enable compatibility for every device and app.

Automatic HarmonyOS detection does not clear or rewrite existing manual compatibility records.

## Related pages

- [Compatible device deployment](../../guide/compat-device.md)
- [Compatible deployment](../../concepts/compat-deploy.md)
- [In-app Jugg runtime](../../concepts/jugg-runtime.md)
- [JVMTI Runtime](./jvmti-runtime.md)
