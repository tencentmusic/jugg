---
title: Updating .so files
description: Explains incremental native library and .so updates in Jugg and how they take effect through APK re-signing.
status: active
tags:
  - capability
  - compile
  - native
  - so
---

# Updating .so files

Jugg can update already generated native library / `.so` files. It writes them into the APK, re-signs the APK, and installs it for the change to take effect.

## Supported scope

| Scenario | Current support | User-visible result |
|---|---|---|
| Update an existing `.so` under an ABI directory in the project | Supported | Updates the target APK, re-signs it, and installs it |
| Update native libraries for multiple ABIs in the same run | Supported according to target APK ownership | Each target APK receives only its own native libraries |
| Delete an `.so` | Does not produce a removal result | The installed APK continues containing the old native library |
| Change C/C++ source, CMake, NDK, ABI, or packaging configuration | Not compiled incrementally directly | Uses a Gradle/NDK build to refresh the APK baseline |

## Trigger and result

```text
An existing .so in the project directory changes
  -> Determine the target path from its ABI and APK ownership
  -> Write it to the target APK's lib/<abi> directory
  -> Re-sign the APK
  -> Install the updated APK
```

After installation, the app uses the native library from the updated APK. Jugg only organizes and deploys already generated `.so` files. Gradle, CMake, and NDK remain responsible for producing `.so` files from C/C++ source.

## Boundaries

- The direct file-change entry point recognizes only existing `.so` files under the project directory whose parent directory is `armeabi`, `armeabi-v7a`, `arm64-v8a`, `x86`, or `x86_64`.
- The Gradle `build` directory is not treated as direct incremental input. After changing C/C++ source or native build configuration, run the corresponding Gradle build to refresh the APK. Complete Sync first when the project model changes as well.
- Deleting an `.so` does not produce data that removes the file from the APK and does not fail incremental compilation by itself. The installed APK continues containing the old native library. Run a full Gradle build only when the deletion must actually take effect.
- Multi-APK projects update native libraries according to target APK ownership instead of writing the same native library into every APK by default.
- If signing configuration is missing or invalid, the incremental APK update fails. Use a Gradle build to restore an installable APK baseline.

## Related pages

- [Compilation stages](../../guide/compile.md)
- [Assets and native library internals](../../concepts/incremental-compile/assets-native.md)
- [Multi-APK deployment](../deploy/multi-apk.md)
