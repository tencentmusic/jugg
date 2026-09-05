---
title: Updating .so files
description: Explains how Jugg handles existing .so files, C/C++ source, and Flutter native artifacts, then makes updates take effect by re-signing the APK.
status: active
tags:
  - capability
  - compile
  - native
  - so
---

# Updating .so files

Jugg can update already generated native library / `.so` files. For C/C++ modules managed by Gradle, a source change first runs the native build task for the current variant. Native libraries produced by a Flutter add-to-app project enter the same APK update flow. Jugg then writes the libraries into the target APK, re-signs it, and installs it.

## Supported scope

| Scenario | Current support | User-visible result |
|---|---|---|
| Update an existing `.so` under an ABI directory in the project | Supported | Updates the target APK, re-signs it, and installs it |
| Change C/C++ source managed by Gradle | Supported | Runs the native build task for the current variant, then updates the generated `.so` |
| Flutter Profile/Release produces `app.so` or native assets | Supported | Converts them into native libraries for the target ABI, then updates the APK |
| Update native libraries for multiple ABIs in the same run | Supported according to target APK ownership | Each target APK receives only its own native libraries |
| Delete an `.so` | Does not produce a removal result | The installed APK continues containing the old native library |
| Change CMake, ndk-build, NDK, ABI, or packaging configuration | Not treated as a source incremental input | Uses a complete Gradle build to refresh the project model and APK baseline |

## Trigger and result

```text
C/C++ source changes
  -> Run the native Gradle task for the current variant
  -> Collect new .so files from the intermediate output directory

An existing .so in the project or a Flutter native artifact changes
  -> Determine the target path from its ABI and APK ownership
  -> Write it to the target APK's lib/<abi> directory
  -> Re-sign the APK
  -> Install the updated APK
```

After installation, the app uses the native library from the updated APK. Gradle, CMake, and NDK remain responsible for producing `.so` files from C/C++ source. Jugg starts the corresponding task after detecting a source change and passes the new artifacts into the existing native library deployment flow.

## Boundaries

- The direct file-change entry point recognizes only existing `.so` files under the project directory whose parent directory is `armeabi`, `armeabi-v7a`, `arm64-v8a`, `x86`, or `x86_64`.
- The C/C++ source entry requires Android Gradle configuration with a CMake or ndk-build file and a discoverable native task for the current variant. Jugg does not watch generated files under `.cxx`, `.externalNativeBuild`, or Gradle `build` directories.
- Each detected C/C++ source change runs the native task. Artifact content checks only avoid writing identical output back to the APK; they do not skip native compilation.
- If Jugg recognizes a C/C++ source root but cannot find its task or output metadata, it falls back to a full Gradle build. If the external task fails or produces no valid `.so`, the current compilation fails instead of deploying old intermediate output.
- After changing CMake, ndk-build, NDK, ABI, source sets, or packaging rules, complete Sync and a full Gradle build to refresh the project model and APK baseline.
- Deleting an `.so` does not produce data that removes the file from the APK and does not fail incremental compilation by itself. The installed APK continues containing the old native library. Run a full Gradle build only when the deletion must actually take effect.
- Multi-APK projects update native libraries according to target APK ownership instead of writing the same native library into every APK by default.
- If signing configuration is missing or invalid, the incremental APK update fails. Use a Gradle build to restore an installable APK baseline.

## Related pages

- [Compilation stages](../../guide/compile.md)
- [Assets and native library internals](../../concepts/incremental-compile/assets-native.md)
- [Multi-APK deployment](../deploy/multi-apk.md)
