---
title: Supported packaging methods
description: Explains which debug packaging methods Jugg supports, and why it cannot be used for official release packaging.
status: active
tags:
  - troubleshooting
  - faq
  - apk
---

# Supported packaging methods

Jugg reduces how often you run a full Gradle build during daily debug work. It can deploy the current debug changes to a device or export an incremental debug APK. It does not replace Gradle packaging in GitHub Actions, official CI package builds, or app-store releases.

## Does Jugg support release packaging?

No. Do not use Jugg to produce an official release APK. App-store listing, channel packages, and official releases must keep using the full Gradle / Android Studio packaging flow. Jugg was not designed for this scenario and cannot speed up `assembleRelease`.

| Packaging method | Use Jugg? | What you see |
|---|---|---|
| Daily debugging | Yes | The installed APK is usually not rebuilt as a whole; changes take effect through incremental compilation and deployment |
| Export an incremental debug APK for testers | Yes | Click `Export incremental APK` in the fallback confirmation dialog to write compiled incremental results into an APK and export it |
| Daily pipeline packaging of a debug APK | Yes | Produce a debug incremental APK through the two-step `cmd_line` commands; see Pipeline debug incremental APK below |
| App-store listing, channel packages, or official release APKs | No | Continue using full Gradle / Android Studio packaging |

> [!IMPORTANT]
> Official packaging must use the existing Gradle flow. Jugg cannot speed up release package builds, and it should not replace the signing, shrinking, or full packaging steps used for store listing.

Experimental [Release compilation](../capabilities/compile/release-compile.md) is only for continuing daily debugging on an already installed minified or release APK. It is not a way to produce an APK for store listing.

## Pipeline debug incremental APK

CI pipelines do not use the IDE [Export an incremental APK](../guide/export-incremental-apk.md) button. They produce a debug incremental APK through two commands in the `cmd_line` module:

```text
cmd=buildGradleBase
  -> run a full Gradle build
  -> save the APK, classpath, and Jugg baseline

cmd=buildIncrementalApk
  -> restore the compile context from the saved baseline
  -> compile the changedFiles explicitly provided by the pipeline
  -> write the incremental result back to the APK output directory
```

The pipeline must provide the `changedFiles` list itself. Jugg does not infer the CI diff automatically. Each baseline directory can be consumed only once. If a pipeline needs multiple incremental results, copy an independent baseline for each run.

There is no dedicated Wiki guide yet. Parameter names, validation rules, and usage examples are in the source directory [`cmd_line/src/main/java/com/sickworm/intellij/jugg/cmdline/`](https://github.com/tencentmusic/jugg/tree/main/cmd_line/src/main/java/com/sickworm/intellij/jugg/cmdline).

## Related pages

- [Export an incremental APK](../guide/export-incremental-apk.md)
- [Run an app](../guide/run.md)
- [Limits](../reference/limits.md)
- [Release compilation](../capabilities/compile/release-compile.md)
- [How Jugg works](../concepts/how-jugg-works.md)
