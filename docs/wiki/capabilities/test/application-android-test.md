---
title: Application Android Test
description: Explains Jugg's run entry points, support scope, and behavior for androidTest in app modules.
status: active
tags:
  - capability
  - test
  - android-test
---

# Application Android Test

Jugg supports Android instrumentation tests in app modules. After Android Test is enabled, both app source and `app/src/androidTest` source can enter Jugg's Android Test compilation, deployment, and instrumentation flow.

## Run entry points and supported scope

| Run entry point | Current support | Result |
|---|---|---|
| Run Android Test from a Jugg App Run Configuration | Supported | Runs instrumentation after compilation and deployment |
| Run from a class gutter in `src/androidTest` | Supported | Runs the selected test class |
| Run from a method gutter in `src/androidTest` | Supported | Runs the selected test method |
| Rerun after changing app source | Supported | Deploys compilation artifacts to the app APK |
| Rerun after changing app androidTest source | Supported | Deploys compilation artifacts to the corresponding test target |
| Rerun failed tests | Supported | Reruns only failed leaf tests |

> [!IMPORTANT]
> Jugg does not automatically modify the App Run Configuration when a user clicks a gutter icon. Enable Android Test manually and complete one full Gradle build for the Android Test target first.

## How Android Test runs

```text
Enable Android Test in the App Run Configuration
  -> BuildTarget switches to ANDROID_TEST
  -> A full Gradle build produces the app APK and app test APK
  -> Later app / androidTest source changes enter incremental compilation
  -> Deployment writes artifacts to the app APK or test APK according to APK ownership
  -> Run am instrument after deployment succeeds
```

Jugg uses `sourcePath` as the test-target anchor. A class gutter creates a class-level target, and a method gutter creates a method-level target. Rerun failed tests converts failed nodes into new test filters without writing the scope back to the General page.

## Target and APK ownership

Android Test mode uses `BuildTarget.ANDROID_TEST`. This target means that the current run session must consider both app and androidTest artifacts; it does not turn androidTest into an independent app.

Deployment continues to use the current install, Code Swap, or Full Swap strategy and splits deployment data by APK ownership. App-style androidTest runs in the process of the app under test. For self-targeting library Test APKs, see [Library Android Test](./library-android-test.md).

## Current boundaries

| Scenario | Current behavior |
|---|---|
| `org.junit.Test` | Supported for gutter detection |
| `org.junit.jupiter.api.Test` | Supported for gutter detection |
| Java / Kotlin test files | Supported |
| Custom androidTest source root | Supported through source-root detection |
| Incremental compilation of androidTest resources | Not currently covered |
| `androidTestAnnotationProcessor` / `androidTestKapt` | Not currently covered |
| Persistent test harness or redefinition in the test process | Not currently covered |
| Debug Executor | Not currently covered |

## Related pages

- [Android Test guide](../../guide/android-test.md)
- [Android Test flow](../../concepts/android-test-flow.md)
- [Library Android Test](./library-android-test.md)
- [Test Results UI](./test-results-ui.md)
- [Logcat attribution](./logcat-attribution.md)
- [Multiple APKs](../deploy/multi-apk.md)
