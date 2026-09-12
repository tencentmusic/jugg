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
| Flutter Profile/Release produces `app.so` or native assets | Supported | Runs the Flutter native output task for the current variant, reads native libraries for the target ABI from that task's own output, then updates the APK |
| Flutter Debug produces assets only, without native libraries | Supported | Updates `flutter_assets` only and does not require native output; an empty native directory still lets the compilation succeed |
| Update native libraries for multiple ABIs in the same run | Supported according to target APK ownership | Each target APK receives only its own native libraries |
| Delete an `.so` | Does not produce a removal result | The installed APK continues containing the old native library |
| Change `CMakeLists.txt`, project `*.cmake`, `Android.mk`, or `Application.mk` | Supported | Runs the native task for the current variant and refreshes the project model after it finishes; new `.so` files update the APK through the existing flow |
| Change NDK, ABI, native source sets, or packaging rules | Not treated as a source incremental input | Uses a complete Gradle build to refresh the project model and APK baseline |

## Trigger and result

```text
C/C++ source changes
  -> Run the native Gradle task for the current variant
  -> Collect new .so files from the intermediate output directory

Flutter Dart source changes
  -> Run the Flutter native output task for the current variant
  -> Collect .so files under <abi> from that task's own native output, an archive or a directory

An existing .so in the project changes
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
- Each detected Dart source change runs the Flutter native output task for the current variant. Jugg reads only the native output that task declares and resolves the `.so` files under each ABI from it, whichever form that output takes. It does not recursively guess native output from Flutter intermediate directories, and it does not assemble artifact locations from fixed paths.
- If Jugg recognizes a Flutter source root but cannot find the compile task, the assets output directory, or native output metadata, it falls back to a full Gradle build. If the native output cannot be read, or an archive contains unsafe or duplicate native entries, the current compilation fails. A build mode that legitimately produces no native library, such as Debug, succeeds as long as the assets output is valid.
- If Jugg recognizes a C/C++ source root but cannot find its task or output metadata, it falls back to a full Gradle build. If the external task fails or produces no valid `.so`, the current compilation fails instead of deploying old intermediate output.
- CMake and ndk-build configuration files inside the project (`CMakeLists.txt`, `*.cmake`, `Android.mk`, `Application.mk`) are configuration inputs of the current variant's native build. After you change them, Jugg runs the existing native task and refreshes the project model when the task finishes, so newly added out-of-project shared sources, assembly files, and include roots become recognizable inputs in the next round. Configuration the task cannot cover, such as NDK, ABI, native source sets, or packaging rules, still needs a full Gradle build to refresh the APK baseline.
- When a recognized C/C++ source or native configuration file is deleted, Jugg falls back to a full Gradle build. If a native artifact collected in the previous round is no longer produced after an external build succeeds, the current compilation fails and reports that a full Gradle build is required, so it never leaves an old `.so` behind and still reports success.
- When not all external inputs in one round can be resolved, such as a multi-module project where only one module configures a native build, the whole round falls back to a full Gradle build instead of building only the recognizable part.
- Deleting an `.so` does not produce data that removes the file from the APK and does not fail incremental compilation by itself. The installed APK continues containing the old native library. Run a full Gradle build only when the deletion must actually take effect.
- Multi-APK projects update native libraries according to target APK ownership instead of writing the same native library into every APK by default.
- If signing configuration is missing or invalid, the incremental APK update fails. Use a Gradle build to restore an installable APK baseline.

## Related pages

- [Compilation stages](../../guide/compile.md)
- [Assets and native library internals](../../concepts/incremental-compile/assets-native.md)
- [Multi-APK deployment](../deploy/multi-apk.md)
