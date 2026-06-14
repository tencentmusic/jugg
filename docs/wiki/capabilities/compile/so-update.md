---
title: Native Library Update
description: Explains Jugg's native library / .so update path and APK re-sign effect path.
status: active
tags:
  - capability
  - compile
  - native
  - so
---

# Native Library Update

Jugg supports updating already produced native library / `.so` files by writing them into the APK, re-signing it, and installing the updated APK.

## Supported Capabilities

| Scenario | Current support | Effect path |
|---|---|---|
| Updating existing `jniLibs/**/*.so` or `lib/<abi>/*.so` artifacts | Supported | Write into the target APK and re-sign the APK |
| Multi-ABI native library updates | Supported per target APK ownership | Each target APK gets its own native library artifact update |

> [!TIP]
> If the change touches C/C++ source, CMake, NDK, ABI, packaging rules, or native source sets, run the matching Gradle build or Sync first so the new `.so` artifact becomes an input Jugg can update.

## How Native Library Update Takes Effect

```text
detect native library / .so artifact changes
  -> map them to the target APK
  -> write them into the APK during deployment
  -> re-sign the APK
  -> install the updated APK
```

Native library update modifies APK contents and requires re-signing. Jugg places existing `.so` artifacts into the right APK; Gradle/NDK still owns producing `.so` artifacts from C/C++ source.

## Related Capabilities

- [Manifest](./manifest.md)
- [Resource Compile](./resource-compile.md)
- [Compile Guide](../../guide/compile.md)
