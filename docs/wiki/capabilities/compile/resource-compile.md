---
title: Resource compilation
description: Explains Jugg's incremental handling scope and fallback boundaries for Android res, assets, and resource-generated artifacts.
status: active
tags:
  - capability
  - compile
  - resource
---

# Resource compilation

Jugg incrementally handles Android `res/` and `assets/` and connects them with resource-related capabilities such as AndroidManifest and DataBinding/ViewBinding. Android `res/` passes through `aapt2` compilation and incremental linking, while `assets/` is organized directly as an overlay. `resources.arsc`, `R.java`, and `R.dex` are downstream artifacts that resource changes may produce, not resource inputs that users modify directly.

This page explains whether a resource change is supported and what deployment result to expect. For aapt2 `inclink` and resource-table reuse, see [Incremental resource compilation](../../concepts/incremental-compile/resource.md). Compose Multiplatform resources use a separate generator and deployment path; see [KMP and Compose Multiplatform](./kmp-compose-multiplatform.md).

## Supported scope

| Resource or scenario | Current support | User-visible result |
|---|---|---|
| Regular Android `res/` files | Supported | Produces a resource overlay owned by the target APK |
| `res/values` | Supported | Updates the resource table and may continue by generating and compiling R declarations when resource symbols change |
| `assets/` | Supported | Preserves paths relative to `assets/` and deploys them as an overlay to the target APK |
| `AndroidManifest.xml` | Incremental patch supported | Updates and re-signs the APK; see [AndroidManifest compilation](./manifest.md) for the complete scope |
| ViewBinding/DataBinding layouts | Resource-stage handoff supported | Produces both resource artifacts and binding-related generated sources; see [DataBinding/ViewBinding](./databinding-viewbinding.md) |
| Resource-obfuscated projects with an existing AabResGuard mapping | Supported | Incremental resources attempt to reuse the resource names in the installed APK; see [AabResGuard](./aab-resguard.md) |

## Results produced by resource changes

| Artifact or handling | Source | Subsequent result |
|---|---|---|
| Compiled resources and `resources.arsc` | Incremental link of Android `res/` | Enter deployment as a resource overlay |
| `R.java`, and `R.dex` needed by some R-reference scenarios | Resource IDs or symbols change | `R.java` continues into source compilation, and generated DEX deploys with the resource artifacts |
| ViewBinding/DataBinding generated sources | Binding layout changes | Continue into Java/Kotlin source compilation |
| Asset overlay | `assets/` changes | Bypasses `aapt2` and deploys according to target APK |
| Updated Manifest | The Manifest contains an actual incremental change | Is written to the target APK, re-signed, and installed |

```text
Android res changes
  -> aapt2 compile produces flat files for the current run
  -> Incrementally link against the current APK resource table
  -> Output compiled resources, resources.arsc, and optional R.java

assets changes
  -> Preserve paths relative to assets
  -> Produce an asset overlay

Generated sources
  -> Continue into source compilation
  -> Route all artifacts by target APK before deployment
```

Deployment of a regular resource or asset overlay normally restarts the Activity. An actual Manifest change enters the APK update, re-signing, and installation path. Projects with multiple APKs or dynamic features produce separate resource artifacts for each target instead of copying the same overlay into every APK.

## Boundaries

- When a `res/` or asset file is deleted, Jugg does not produce deployment data that removes the device-side file or resource entry. The old resource remains readable through `Resources` or `AssetManager`, and its resource ID remains unchanged. Run a full Gradle build only when the deletion must actually take effect.
- Manifest node deletion, attribute deletion, or `tools:*` operations that depend on a complete merge do not produce corresponding removal or merge results. The device continues using the previous merged manifest content. See [AndroidManifest compilation](./manifest.md) for details.
- After changing a source set, variant, resource directory, resource generation logic, or resource obfuscation configuration, complete Gradle Sync when the project model changes, then run a full Gradle build for the target variant to establish a new APK and resource-table baseline.
- Added or modified styleables depend on R declarations from the latest build, and resource obfuscation depends on a mapping that matches the current APK. Use a Gradle build to refresh a missing or inconsistent baseline.
- Compose Multiplatform resources do not pass through Android `aapt2` and are not handled by the Android `res/` rules on this page.
- On the first resource overlay deployment, Jugg may include resource files from the baseline, so the number of deployed files can exceed the number changed directly in the current run.

## Related pages

- [Source compilation](./source-compile.md)
- [AndroidManifest compilation](./manifest.md)
- [DataBinding/ViewBinding](./databinding-viewbinding.md)
- [AabResGuard](./aab-resguard.md)
- [KMP and Compose Multiplatform](./kmp-compose-multiplatform.md)
- [Incremental resource compilation](../../concepts/incremental-compile/resource.md)
- [Assets and native library internals](../../concepts/incremental-compile/assets-native.md)
- [Compilation stages](../../guide/compile.md)
- [Compilation failed](../../troubleshooting/compile-failed.md)
- [Changes did not take effect](../../troubleshooting/changes-not-applied.md)
- [Limits](../../reference/limits.md)
