---
title: Android Test CLI
description: Explains how jugg instrument runs class- or method-level tests from an androidTest source-file anchor.
status: active
tags:
  - capability
  - tools
  - cli
  - android-test
---

# Android Test

`jugg instrument` runs class- or method-level tests from an androidTest source-file anchor. It reuses Jugg's compilation and deployment flow to update the app and test APKs on the device before executing instrumentation.

## Run entry points and supported scope

| Run entry point | Current support | Input boundary |
|---|---|---|
| Run by androidTest source file | Supported | `--source-path` is required |
| Run by test class | Supported | `--source-path` + `--class` |
| Run by test method | Supported | `--source-path` + `--class` + `--method` |
| Specify the instrumentation runner | Supported | `--runner` |
| Pass `-e` extras | Batch arguments supported | `--extras 'k=v;k2=v2'` |
| Use a package / regex as the Jugg target entry point | Not supported | Refresh APKs with `sourcePath` first, then use native `adb shell am instrument` for broader filtering if needed |

## Command format

```text
jugg instrument --source-path app/src/androidTest/kotlin/com/example/FooTest.kt
jugg instrument --source-path app/src/androidTest/kotlin/com/example/FooTest.kt --class com.example.FooTest
jugg instrument --source-path app/src/androidTest/kotlin/com/example/FooTest.kt --class com.example.FooTest --method testSomething
jugg instrument --source-path app/src/androidTest/kotlin/com/example/FooTest.kt --runner androidx.test.runner.AndroidJUnitRunner --extras 'size=large;clearPackageData=true'
```

`sourcePath` identifies the test module, target test APK, test class, and method ownership. Scenarios with multiple test APKs require it to select the target; a package or regex alone is insufficient.

## Prerequisites

The project must already have an AndroidTest full-build baseline. Run:

```text
jugg status --console=json
```

and read `data.enabledAndroidTest`. If it is `false`, enable Android Test / `enableAndroidTest` in the Jugg App Run Configuration, run one full build or `gradle-build`, and then check the status again.

> [!IMPORTANT]
> When `enabledAndroidTest=false`, `instrument` returns `INVALID_PARAMS` and does not infer or build an AndroidTest baseline automatically.

## Run result

`instrument` is a build-related command and blocks until a terminal state. A successful `jugg instrument` means that app source and androidTest source were compiled and deployed to the corresponding APKs before instrumentation ran. If only broader native test filtering is needed afterward, use native `adb shell am instrument` once the APKs have been refreshed.

## Related pages

- [Android Test guide](../../guide/android-test.md)
- [Android Test flow](../../concepts/android-test-flow.md)
- [Application Android Test](../test/application-android-test.md)
- [Library Android Test](../test/library-android-test.md)
- [Test Results UI](../test/test-results-ui.md)
- [Logcat attribution](../test/logcat-attribution.md)
