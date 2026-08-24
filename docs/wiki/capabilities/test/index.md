---
title: Testing capabilities
description: Summarizes Jugg support for Application Android Test, Library Android Test, test result display, and logcat attribution.
status: active
tags:
  - capability
  - test
---

# Testing capabilities

Jugg can run Android instrumentation tests while preserving incremental compilation and deployment. Users can determine whether the current scenario is supported from four perspectives: app `androidTest`, self-targeting library `androidTest`, the test results tree, and method-level logcat.

## Capability entry points

| User scenario | Current support | Entry point |
|---|---|---|
| Run instrumentation tests for an app module | Supported | [Application Android Test](./application-android-test.md) |
| Run self-targeting instrumentation tests for a library module | Supported | [Library Android Test](./library-android-test.md) |
| View the test tree, failed nodes, and rerun failed tests in the Run window | Supported | [Test Results UI](./test-results-ui.md) |
| Attribute logcat to a specific test method | Supported | [Logcat attribution](./logcat-attribution.md) |

> [!IMPORTANT]
> Before entering Android Test mode for the first time, enable Android Test in the Jugg App Run Configuration and complete one full Gradle build for the Android Test target. This gives Jugg the app APK, test APK, runner, and test-module information it needs.

## How test execution takes effect

```text
Select a test entry point
  -> Android Test RunSpec records a sourcePath, class, or method
  -> BuildTarget switches to ANDROID_TEST
  -> Compile app and androidTest artifacts
  -> Deploy app APK / test APK according to APK ownership
  -> Run am instrument after deployment succeeds
  -> Display the test tree, failures, and method-level logcat in the Run window
```

`Application Android Test` is the most common entry point and applies to `src/androidTest` in an app module. `Library Android Test` handles libraries with an independent self-targeting Test APK, with emphasis on adding and installing the correct Test APK. Both test types ultimately run through instrumentation and share the Test Results UI and logcat attribution capabilities.

## Current boundaries

| Scenario | Current behavior |
|---|---|
| App source change | Supported by Android Test incremental compilation and deployment |
| `app/src/androidTest` source change | Supported by Android Test incremental compilation and deployment |
| Self-targeting library `src/androidTest` source change | Supported; adds the Test APK when needed |
| Incremental compilation of androidTest resources | Not currently covered |
| `androidTestAnnotationProcessor` / `androidTestKapt` | Not currently covered |
| Debug Executor | Not currently covered |

## Related pages

- [Android Test guide](../../guide/android-test.md)
- [Android Test flow](../../concepts/android-test-flow.md)
- [Application Android Test](./application-android-test.md)
- [Library Android Test](./library-android-test.md)
- [Test Results UI](./test-results-ui.md)
- [Logcat attribution](./logcat-attribution.md)
- [Multiple APKs](../deploy/multi-apk.md)
