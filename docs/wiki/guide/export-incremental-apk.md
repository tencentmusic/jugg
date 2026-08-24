---
title: Export an incremental APK
description: Export the current incremental APK from Jugg's confirmation dialog and prepare the changes before export.
status: active
tags:
  - guide
  - apk
  - export
---

# Export an incremental APK

Exporting an incremental APK writes the incremental results that have already been compiled into the APK and signs it again. Use it to give the current changes to someone else for installation and verification, or to preserve an installable artifact before falling back to Gradle.

## Where to open it

The entry point is in the Gradle fallback confirmation dialog:

```text
Confirm fallback
  -> Export incremental APK
```

Select an output directory. After the export succeeds, Jugg opens that directory.

## Compile before exporting

Export processes only files that have already been compiled. If uncompiled files remain, Jugg displays:

```text
Not all files are compiled:
```

When this message appears, run Jugg Run or Jugg compile once to compile the current changes, then export again.

## When to use it

- The incremental run succeeded and you want to give the APK to someone else to install.
- You are about to fall back to Gradle but want to preserve the current incremental result first.
- You need to confirm whether the APK content already includes the current resource, Manifest, or `.so` changes.

Export is not a replacement for a normal Run. It does not evaluate device state or launch the app automatically.

## Related pages

- [Fall back to Gradle compilation](./downgrade-gradle.md)
- [Run an app](./run.md)
- [Jugg is slow or stuck](../troubleshooting/jugg-slow-or-stuck.md)
