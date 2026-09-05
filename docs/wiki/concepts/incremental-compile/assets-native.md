---
title: Assets and native libraries
description: Explains how Gradle and Jugg handle assets and native libraries, and why the two incremental artifact types take effect through different paths.
status: active
tags:
  - concept
  - compile
  - assets
  - native
---

# Assets and native libraries

An Android APK contains more than resources compiled by aapt2. Files under `assets/` enter the APK with their original directory structure, while native libraries enter as `.so` files grouped by ABI. Jugg reuses the latest Gradle APK. It directly organizes ordinary assets and existing `.so` files as incremental artifacts. For Dart or C/C++ changes, it first runs a narrowly scoped Gradle task and then reuses the same deployment behavior.

## How Gradle places files into an APK

A complete Android build produces different APK content according to input type:

| Input | Standard build process | Artifact in the APK |
|---|---|---|
| `res/` | aapt2 compiles and links resources | Compiled resources and `resources.arsc` |
| `assets/` | AGP merges asset directories and includes them in APK packaging | `assets/**` |
| C/C++ source or prebuilt `.so` | Gradle/NDK generates or collects shared libraries for each ABI and includes them in APK packaging | `lib/<abi>/*.so` |
| Flutter Dart source | The Flutter Gradle plugin produces `flutter_assets`; Profile/Release may also produce `app.so` or native assets | `assets/flutter_assets/**` and `lib/<abi>/*.so` |

Files under `assets/` do not generate resource IDs like `res/` or enter `resources.arsc`. A native library is an already compiled binary and likewise is not part of the Android resource table. A complete build still collects these files and places them at their defined APK paths.

## How Jugg organizes current incremental artifacts

Using the Gradle APK as a baseline, Jugg detects changed files and preserves their relative APK paths:

```text
changed asset file
  -> preserve its relative path under assets
  -> generate an asset incremental artifact owned by the target APK

changed, already generated .so
  -> preserve its relative path under the ABI and lib directory
  -> generate a native library incremental artifact owned by the target APK

Dart change
  -> run the Flutter compile task for the current variant every time
  -> convert flutter_assets into assets and native output into native libraries

C/C++ change
  -> run the native build/merge task for the current variant
  -> convert new .so files into native library incremental artifacts
```

This process does not run aapt2 or generate `resources.arsc`. A Dart change always runs Flutter compilation without adding a Jugg-side Flutter cache. Gradle, CMake, and NDK still convert C/C++ source into `.so` files. Jugg selects only the external task required for the current variant and collects its new output, so Android Java/Kotlin and resource changes continue through the existing incremental compilation flow.

Artifact CRC checks only determine whether new output needs another deployment. They do not skip Flutter or C/C++ compilation, which prevents changed source from being judged against stale intermediate output.

In a multi-APK project, each artifact must also preserve target APK ownership. Jugg does not copy the same asset or native library into every APK by default.

## Loading behavior determines how artifacts take effect

After generating incremental artifacts, Jugg selects a deployment path based on how Android reads each file at runtime:

```text
asset incremental artifact
  -> deliver it as an overlay for the target APK
  -> read the new file through AssetManager at runtime

native library incremental artifact
  -> write it back to lib/<abi> in the target APK
  -> re-sign and install the updated APK
```

An asset overlay preserves its `assets/**` path for the new resource loading path. An ordinary asset or resource overlay does not become an APK native library search directory, so the current `.so` update path modifies the target APK instead of delivering the `.so` as an asset overlay.

## When Jugg must return to Gradle

- When an asset is deleted, Jugg does not generate an overlay that removes the device file. The old asset remains readable through `AssetManager`. Run a full Gradle build only when the deletion must take effect.
- If Jugg recognizes a Flutter/C++ source root but cannot find its task or output metadata, it falls back to a full Gradle build. If the external task fails or produces no valid artifact, the current compilation fails instead of reusing old intermediate output.
- Remote compilation and custom commands from which Jugg cannot safely derive external tasks fall back to a complete Gradle build.
- After changing `pubspec.yaml`, CMake, ndk-build, NDK, ABI, native source sets, or packaging rules, use Sync and a full Gradle build to refresh the project model and APK baseline.
- After changing asset source sets, variant, or build configuration that affects APK paths or ownership, refresh the Gradle baseline.
- A native library update requires usable APK signing configuration. If Jugg cannot re-sign the APK, this incremental update path cannot continue.

## Related pages

- [Incremental compilation overview](./index.md)
- [Resource incremental compilation](./resource.md)
- [Resource compilation capability](../../capabilities/compile/resource-compile.md)
- [Native library update capability](../../capabilities/compile/so-update.md)
- [Multi-APK deployment](../../capabilities/deploy/multi-apk.md)
- [Deployment strategy](../deploy-strategy.md)
