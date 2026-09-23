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
| Share one C/C++ source across multiple Native modules | Supported | Runs all distinct native tasks for matching modules once, then updates the `.so` files produced by each module |
| Flutter Profile/Release produces `app.so` or native assets | Supported | Runs the Flutter native output task for the current variant, reads native libraries for the target ABI from that task's own output, then updates the APK |
| Flutter Debug produces assets only, without native libraries | Supported | Updates `flutter_assets` only and does not require native output; an empty native directory still lets the compilation succeed, and no APK is repackaged, re-signed, or installed |
| Update native libraries for multiple ABIs in the same run | Supported according to target APK ownership | Each target APK receives only its own native libraries |
| Update an existing `.so` larger than `Int.MAX_VALUE` (about 2 GiB) | Conditionally supported | Does not load the complete file into the IDE heap; APK updates stream-replace the same-path baseline entry, while SO hot update pushes the source file directly when available |
| Enable “SO hot update” when the round contains only native libraries | Supported on Android 8.0+ when the target ABI and sandbox are available | Writes to the app's `code_cache/.jugg_native/<abi>/`, skips APK re-signing and installation, and loads the library after an app restart |
| Delete an `.so` | Does not produce a removal result | The installed APK continues containing the old native library |
| Change `CMakeLists.txt`, project `*.cmake`, `Android.mk`, or `Application.mk` | Supported | Runs the native task for the current variant and updates that module's external build information before the same Gradle invocation finishes; new `.so` files update the APK through the existing flow |
| Change NDK, ABI, native source sets, or packaging rules | Not treated as a source incremental input | Uses a complete Gradle build to refresh the project model and APK baseline |
| Change `packaging.jniLibs.keepDebugSymbols` | Supported | Keeps debug symbols for those `.so` files according to the app packaging semantics without running the app native build or strip task |

## Trigger and result

```text
C/C++ source changes
  -> Find every Native module that shares the source
  -> Run all distinct native Gradle tasks for the current variant in one invocation
  -> Read the APK owner strip configuration and produce stripped .so files in this invocation directory

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

## How Debug Dart code takes effect

Flutter Debug/JIT Dart code is not a native library but an asset such as `assets/flutter_assets/kernel_blob.bin`. Jugg delivers it as an asset overlay and never writes it back into the APK, so changing Dart in Debug never repackages, re-signs, or installs an APK.

The Flutter Android embedding extracts `flutter_assets` into the app private directory `app_flutter` and uses `app_flutter/res_timestamp-<versionCode>-<lastUpdateTime>` to decide whether it has to extract again. An overlay update does not change the APK `lastUpdateTime`, so after all overlay slices succeed Jugg deletes that timestamp and fully restarts the app, letting Flutter re-extract from the overlay that already took effect. The deployment type for such a run is Hot Fix.

After the overlay takes effect, the Jugg runtime also refreshes the `AssetManager` retained by Flutter engines so subsequent asset reads use the current overlay. See [Assets and native library internals](../../concepts/incremental-compile/assets-native.md) for the mechanism.

Profile/Release use the AOT artifact `libapp.so`, which is a native library and keeps using the APK update, re-sign, and install path described above.

## Boundaries

- The direct file-change entry point recognizes only existing `.so` files under the project directory whose parent directory is `armeabi`, `armeabi-v7a`, `arm64-v8a`, `x86`, or `x86_64`.
- The C/C++ source entry requires Android Gradle configuration with a CMake or ndk-build file and a discoverable native task for the current variant. Jugg does not watch generated files under `.cxx`, `.externalNativeBuild`, or Gradle `build` directories. A project may declare Gradle extra `juggExternalBuildPrerequisites` so matching file changes run a codegen task in the same invocation before native merge; Kotlin/Java files in the declared output directories then enter Jugg's incremental source compile only when their size or timestamp changed during that codegen task. Projects without that extra keep the current behavior.
- Each detected C/C++ source change runs the native task. Artifact content checks only avoid writing identical output back to the APK; they do not skip native compilation.
- What gets deployed is the `.so` stripped with the app packaging semantics, not the unstripped file in the module intermediate directory. Jugg reads the `strip<Variant>DebugSymbols` configuration of the APK owner (base app or dynamic feature) inside the collector process and reproduces the AGP single-file strip behavior without executing that strip task. The invocation runs only the module merge tasks selected by the C/C++ changes; it does not additionally run other native merge tasks from the APK owner. When the strip tool is missing or returns a non-zero exit code, the library is packaged as is, following the AGP contract.
- Only a NativeLib larger than `Int.MAX_VALUE` (2,147,483,647 bytes) uses the file-backed path. Ordinary `.so` files, Dex files, resources, and assets keep the existing in-memory path. Jugg rechecks that a file-backed source still exists and has the same size and timestamp before writing it into an APK or pushing it to a device; a change fails the current round explicitly.
- Updating a large `.so` inside an APK requires an entry at the same path in the baseline APK and inherits that entry's `STORED` or `DEFLATED` compression method; Jugg does not force a large DEFLATED `.so` to become STORED. The round fails and preserves the original APK if an entry reaches the classic ZIP 4 GiB boundary, the baseline entry is missing, disk space is insufficient, or zipalign, signing, verification, or installation rejects the result. CRC calculation, compression, and temporary APKs increase runtime and disk usage for large files.
- File size never enables “SO hot update” automatically. When its existing setting, Android version, ABI, and sandbox requirements are all satisfied, Jugg pushes a large `.so` directly from its source and avoids another same-sized local temporary file. If those conditions are unavailable or this path fails, deployment returns to the APK update flow.
- A native library module can be built without the APK owner being configured, for example when Gradle configuration on demand is enabled, so the owner strip task is not readable in that invocation. A complete Gradle build therefore caches the owner strip configuration and a copy of each strip executable under `build/jugg/classpath/native_strip`, and the collector uses that cache first. The cache is matched by the exact module root and variant, and the collected configuration is only reused when its recorded tool is still executable. A missing, malformed or unusable cache falls back to reading the owner task once; if the owner is not configured and no cache is available, the round fails and asks for a complete Gradle build instead of deploying an unstripped library. Because the tool copies travel with the cache, a CI baseline stays usable on another worker whose NDK is at a different path — keep the whole `native_strip` directory when copying a baseline.
- When one physical source matches multiple Native modules, every matching task must be supported and succeed, and every module output must be collectable. Otherwise that source falls back or fails as a whole instead of marking a partially successful result as compiled.
- Each detected Dart source change runs the Flutter native output task for the current variant. Jugg reads only the native output that task declares and resolves the `.so` files under each ABI from it, whichever form that output takes. It does not recursively guess native output from Flutter intermediate directories, and it does not assemble artifact locations from fixed paths.
- If Jugg recognizes a Flutter source root but cannot find the compile task, the assets output directory, or native output metadata, it falls back to a full Gradle build. If the native output cannot be read, or an archive contains unsafe or duplicate native entries, the current compilation fails. A build mode that legitimately produces no native library, such as Debug, succeeds as long as the assets output is valid.
- If Jugg recognizes a C/C++ source root but cannot find its task or output metadata, it falls back to a full Gradle build. The current compilation fails if the external task fails or the declared output directory is missing or unreadable. If the task succeeds and the output directory is accessible but contains no valid `.so`, the round succeeds without native deployment output.
- CMake and ndk-build configuration files inside the project (`CMakeLists.txt`, `*.cmake`, `Android.mk`, `Application.mk`) are configuration inputs of the current variant's native build. After you change them, Jugg runs the existing native task and collects and merges only that module's latest external build information in the same Gradle invocation instead of asynchronously refreshing the full project model. Newly added out-of-project shared sources, assembly files, and include roots immediately enter the subsequent watch scope. Configuration the task cannot cover, such as NDK, ABI, native source sets, or packaging rules, still needs a full Gradle build to refresh the APK baseline.
- C/C++ inputs trigger recursively by directory. Configuration roots, parent directories of metadata sources, and include roots are merged into the smallest practical set of watched directories. Non-excluded files in those directories may cause a small number of false triggers. A broad shared directory may also trigger multiple modules, including one with a large output; Gradle and Ninja use up-to-date checks and real dependencies to decide whether native work actually runs. `.cxx`, `.externalNativeBuild`, and build outputs remain excluded.
- When a recognized C/C++ source or native configuration file is deleted, Jugg falls back to a full Gradle build. If a native artifact collected in the previous round is no longer produced after an external build succeeds, the round still succeeds without a removal result, and the old `.so` remains until a full Gradle build refreshes the APK baseline.
- When not all external inputs in one round can be resolved, such as a multi-module project where only one module configures a native build, the whole round falls back to a full Gradle build instead of building only the recognizable part.
- Deleting an `.so` does not produce data that removes the file from the APK and does not fail incremental compilation by itself. The installed APK continues containing the old native library. Run a full Gradle build only when the deletion must actually take effect.
- Multi-APK projects update native libraries according to target APK ownership instead of writing the same native library into every APK by default.
- If signing configuration is missing or invalid, the incremental APK update fails. Use a Gradle build to restore an installable APK baseline.

## Related pages

- [Compilation stages](../../guide/compile.md)
- [Assets and native library internals](../../concepts/incremental-compile/assets-native.md)
- [Multi-APK deployment](../deploy/multi-apk.md)
