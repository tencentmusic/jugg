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

Dart or Flutter asset change
  -> run the Flutter native output task for the current variant every time; that task also depends on the Flutter compile task
  -> convert flutter_assets into assets and the native libraries in the native output that task declares (an archive or a directory) into native libraries

C/C++ change
  -> run the native build/merge task for the current variant
  -> convert new .so files into native library incremental artifacts
```

This process does not run aapt2 or generate `resources.arsc`. A Dart or confirmed Flutter asset change always runs Flutter compilation and the native output task without adding a Jugg-side Flutter cache. Jugg reads Flutter assets from the current output directory and accepts native libraries only from well-formed ABI entries in the native output that task declares; it does not recursively scan Flutter intermediate directories. Gradle, CMake, and NDK still convert C/C++ source into `.so` files. Jugg selects only the external task required for the current variant and collects its new output, so Android Java/Kotlin and resource changes continue through the existing incremental compilation flow.

External builds use a directory-triggered, Gradle-decided strategy. Jugg watches the Flutter root together with parent directories of current Flutter task inputs and local package roots. For Native builds it watches externalNativeBuild configuration roots, source parent directories, and include roots. If one directory already contains another, the project model keeps only the broader directory. Any non-excluded file below these roots may start the corresponding task, so a small number of false triggers are acceptable; Gradle's own up-to-date checks still decide whether work is required. Jugg no longer depends on the previous depfile's exact file list or parses `flutter.assets` declarations from `pubspec.yaml`, so a newly created image, JSON file, or nested file under the Flutter root is not missed merely because it did not exist in the previous build.

The Flutter SDK, global pub cache, `.dart_tool`, `.cxx`, `.externalNativeBuild`, and build output directories are never watched. During each external task invocation, Jugg collects only the latest external build information for the modules and variants involved before that same Gradle invocation finishes. It does not start a separate full project-information refresh after a configuration file changes. Once the targeted information is merged into the project model, the file watcher updates immediately, so a newly added local package, shared C/C++ directory, or include root can trigger from the next file change. If the targeted information is incomplete or cannot be merged, the current incremental compilation fails and preserves the full-Gradle fallback boundary instead of continuing with a known stale watch scope.

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

### The Flutter Debug/JIT extraction cache

Dart code in Debug mode lives in `assets/flutter_assets/kernel_blob.bin`, together with `vm_snapshot_data` and `isolate_snapshot_data`. On first start the Flutter Android embedding extracts these files into the app private directory `app_flutter` and then uses that copy, re-extracting only when `app_flutter/res_timestamp-<versionCode>-<lastUpdateTime>` no longer matches the installed APK.

An overlay update does not change the APK `lastUpdateTime`, so delivering only the asset overlay plus an ordinary restart still makes the app read the old Dart code from `app_flutter`. When the current round really compiles and deploys those Flutter JIT runtime files, Jugg therefore waits until all overlay slices succeed, deletes the `res_timestamp-*` files directly inside the target app's `app_flutter`, and then fully restarts the app so Flutter itself re-extracts from the overlay that already took effect. This path never repackages, re-signs, or installs an APK, and the deployment type users see is Hot Fix.

Flutter Profile/Release use the AOT artifact `libapp.so` and keep using the native library APK update path, without entering this extraction cache invalidation. The invalidation command only deletes regular `res_timestamp-*` files directly inside `app_flutter`; it never touches `flutter_assets`, the kernel, the overlay, or other app data, and a missing timestamp counts as success.

### The AssetManager retained by a Flutter engine

A Flutter engine retains the `AssetManager` captured when the engine is created, while Apply Changes builds a new resource view when it updates application resources. Updating Android `Resources` alone can therefore leave a running Flutter engine reading assets through its old `AssetManager`.

After the overlay takes effect, the Jugg runtime supplies live Flutter engines with an `AssetManager` that includes that overlay. Host package resources used to create a new engine receive the same overlay before Flutter captures them. The overlay directory is added at the highest lookup priority and serves files through Android's native directory loading path, so Flutter worker threads do not depend on a Java asset callback. Subsequent Flutter asset reads then use the current Android overlay.

## When Jugg must return to Gradle

- When a Flutter asset is deleted, Jugg does not generate a removal artifact, fail incremental compilation, or fall back to Gradle. The old asset remains in the installed APK or existing overlay. Run a full Gradle build only when the deletion needs to take effect in the APK baseline.
- When an external build succeeds and its declared output paths remain accessible, a smaller Flutter asset or native library set, including a round with no deployable artifacts, succeeds without producing removal results. Old assets and `.so` files remain in place. Run a full Gradle build only when those deletions must take effect.
- When a recognized Dart, C/C++, or external build configuration input is deleted, Jugg falls back to a full Gradle build.
- When one round contains several external inputs, Jugg requires all of them to resolve. If any input lacks metadata, a task, or an artifact contract, such as a multi-module project where only one module configures an external build, the whole round falls back to a full Gradle build instead of building only the recognizable part.
- If Jugg recognizes a Flutter/C++ source root but cannot find its task, output directory, or Flutter native output metadata, it falls back to a full Gradle build. The current compilation fails if the external task fails, a declared output path is missing or unreadable, or a native archive is corrupt or contains unsafe or duplicate entries. The responsible compiler logs the specific reason and also stores it in the compile error.
- Remote compilation and custom commands from which Jugg cannot safely derive external tasks fall back to a complete Gradle build.
- `pubspec.yaml`, `pubspec.lock`, `CMakeLists.txt`, project `*.cmake`, `Android.mk`, and `Application.mk` are configuration inputs of an external build. Changing them runs the existing external task and refreshes the project model after the task finishes, without triggering a full build on its own. Only configuration that task cannot cover, such as NDK, ABI, native source sets, or packaging rules, needs a full Gradle build to refresh the APK baseline.
- After changing asset source sets, variant, or build configuration that affects APK paths or ownership, refresh the Gradle baseline.
- A native library update requires usable APK signing configuration. If Jugg cannot re-sign the APK, this incremental update path cannot continue.

## Related pages

- [Incremental compilation overview](./index.md)
- [Resource incremental compilation](./resource.md)
- [Resource compilation capability](../../capabilities/compile/resource-compile.md)
- [Native library update capability](../../capabilities/compile/so-update.md)
- [Multi-APK deployment](../../capabilities/deploy/multi-apk.md)
- [Deployment strategy](../deploy-strategy.md)
