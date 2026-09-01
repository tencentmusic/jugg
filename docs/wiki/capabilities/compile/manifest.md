---
title: AndroidManifest compilation
description: Explains incremental AndroidManifest.xml compilation in Jugg and how changes take effect through APK re-signing.
status: active
tags:
  - capability
  - compile
  - manifest
---

# AndroidManifest compilation

Jugg supports incremental compilation of `AndroidManifest.xml`. Instead of rerunning the complete Gradle Manifest merge, it applies the current changes to the merged manifest produced by the latest build. This page covers the supported scope and user-visible results. For the merged manifest patch mechanism, see [Android Manifest compilation](../../concepts/incremental-compile/manifest.md).

## Supported scope

| Scenario | Current support | User-visible result |
|---|---|---|
| Add nodes or update attributes | Incremental patch supported | Takes effect after updating and re-signing the APK |
| Delete nodes or attributes, or use `tools:remove` / `tools:replace` | Does not generate the corresponding removal or complete merge result | The installed APK continues using the existing merged manifest content |
| Manifest and resources change in the same run | Supported | The updated Manifest and related resource artifacts are written to the APK together |
| AndroidManifest has no actual change | Filtered automatically | Does not trigger an unnecessary APK update |

> [!TIP]
> If a change depends on Gradle placeholder sources, variant merge rules, or build-script generation logic, complete Sync when the project model changes, then run the corresponding Gradle build to produce a new merged manifest baseline.

## Trigger and result

```text
AndroidManifest.xml changes
  -> Apply deterministic additions and updates to the latest merged manifest
  -> Update and re-sign the APK when needed
  -> Install the updated APK
```

When no actual patch is produced, Jugg does not update the APK. For the complete merged manifest patch and resource link mechanism, see [Android Manifest compilation](../../concepts/incremental-compile/manifest.md).

## Boundaries

- When changing Gradle placeholder sources, variant merge rules, or build-script generation logic, run the corresponding Gradle build first. Complete Sync first as well when the project model changes.
- Node deletion, attribute deletion, and `tools:*` instructions that require complete merge context are ignored and do not fail the current incremental compilation. The installed APK retains the previous merged manifest content. Run a full Gradle build only when the deletion must actually take effect.
- Incremental patches do not update `uses-sdk`, the manifest `package`, `versionCode`, `versionName`, or application `android:name`.
- Manifest changes enter the APK update path rather than the regular resource overlay path.
- If signing configuration is missing or invalid, the incremental APK update fails. Use a Gradle build to restore an installable APK baseline.

## Related pages

- [Resource compilation](./resource-compile.md)
- [Compilation stages](../../guide/compile.md)
- [Android Manifest compilation](../../concepts/incremental-compile/manifest.md)
