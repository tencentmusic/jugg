---
title: Manifest
description: Explains Jugg's incremental AndroidManifest.xml handling and APK re-sign effect path.
status: active
tags:
  - capability
  - compile
  - manifest
---

# Manifest

Jugg supports incremental handling for `AndroidManifest.xml`. It does not rerun the full Gradle Manifest merge. Instead, it patches the change onto the latest merged manifest produced by a build.

## Supported Capabilities

| Scenario | Current support | Effect path |
|---|---|---|
| Regular Manifest node or attribute changes | Incremental patch supported | Patch the merged manifest, write it into the APK, and re-sign the APK |
| Manifest participation in resource link | Supported | Used as an aapt2 link input together with `resources.arsc` |
| Manifest with no real change | Automatically filtered | Does not output root `AndroidManifest.xml`, avoiding unnecessary APK updates |

> [!TIP]
> If the change depends on Gradle placeholder sources, variant merge rules, or build-script generation logic, run the matching Gradle build or Sync first so the new merged manifest becomes the baseline.

## How Manifest Takes Effect

```text
detect AndroidManifest.xml change
  -> read the latest merged manifest
  -> diff / patch the current change
  -> use it as a resource link input
  -> write it into the APK during deployment
  -> re-sign and install the updated APK
```

The key point: Jugg works from the already merged manifest baseline. It does not take over the full Gradle Manifest merge pipeline.

## Related Capabilities

- [Resource Compile](./resource-compile.md)
- [Native Library Update](./so-update.md)
- [Compile Guide](../../guide/compile.md)
