---
title: Android Test
description: Run androidTest with Jugg and understand test APKs, Test Results, CLI instrument, and current limitations.
status: active
tags:
  - guide
  - android-test
---

# Android Test

Jugg supports running `src/androidTest` through incremental compilation. The experience is designed to stay close to Android Studio's built-in Instrumented Tests: run a class or method from the gutter, then Jugg compiles and deploys, runs `am instrument`, and displays the results in Test Results.

## Prerequisites

Complete one AndroidTest baseline build before the first run:

1. Open the Jugg Run Configuration for the corresponding app.
2. Enable Android Test / `enableAndroidTest`.
3. Run Jugg Android Test once or run `jugg gradle-build` once so Jugg can generate the app APK and test APK baseline.
4. After that, changes to app source code or `src/androidTest` source code can use incremental compilation.

If CLI `jugg status` returns `enabledAndroidTest=false`, complete these steps before calling `jugg instrument`.

## Run from the IDE

Click the Jugg gutter icon next to a test class or method under `src/androidTest`. Jugg creates a temporary Run Configuration with one of these scopes:

| Scope | Meaning |
|---|---|
| Class | Run the specified test class |
| Method | Run the specified test method |

Jugg uses the test source path as `sourcePath` to resolve the test class, method, androidTest module, and target Test APK.

## Run from the CLI

Common commands:

```bash
jugg instrument --source-path app/src/androidTest/java/com/example/FooTest.kt
jugg instrument --source-path app/src/androidTest/java/com/example/FooTest.kt --class com.example.FooTest
jugg instrument --source-path app/src/androidTest/java/com/example/FooTest.kt --class com.example.FooTest --method testLogin
```

Optional arguments:

| Argument | Purpose |
|---|---|
| `--source-path` | Required; locates the test source, module, and test APK |
| `--class` | Specifies the test class; optional for a file containing one class |
| `--method` | Specifies the test method |
| `--runner` | Overrides the instrumentation runner |
| `--extras` | Passes additional `-e key value` arguments, such as `foo=bar;debug=true` |

Package and regex test entry points are not currently supported. When multiple test APKs exist, Jugg must locate the target through `sourcePath`.

## Run flow

```text
androidTest gutter / jugg instrument
  -> Resolve sourcePath, test class/method, and test APK
  -> Compile the app and test code for the AndroidTest target
  -> Deploy the app APK and test APK
  -> Run am instrument
  -> Display the Test Results tree and logs
```

Successful deployment and successful tests are separate results. If an instrumentation assertion fails, Jugg still preserves the successful deployment history. The next rerun does not recompile everything because of that test failure.

## Library Android Test

Jugg also supports library-style self-targeting Test APKs. The first time you run androidTest for a library, Jugg asks for a Gradle build if the corresponding Test APK baseline is missing. Jugg then records recently built library Test APKs and automatically replays those records in later Gradle baselines to reduce waiting time.

## Test Results

Android Test uses the Test Results UI:

- A single-device run displays class / method nodes directly.
- A multi-device run groups results by device.
- Failed nodes support source navigation.
- Rerun failed tests preserves the original runner and extras.
- Method-level logcat is attributed to the corresponding test node whenever possible.

## Current limitations

- The androidTest Debug Executor is not supported.
- Incremental compilation of androidTest resources is not covered.
- `androidTestAnnotationProcessor` / `androidTestKapt` is not covered.
- Lazy backfilling of app-style other-targeting test APKs is not a primary path.
- When a run fails, distinguish compilation failure, deployment failure, and test assertion failure.

## Related pages

- [Android Test flow](../concepts/android-test-flow.md)
- [Testing capabilities](../capabilities/test/)
- [Application Android Test](../capabilities/test/application-android-test.md)
- [Library Android Test](../capabilities/test/library-android-test.md)
- [Test Results UI](../capabilities/test/test-results-ui.md)
- [CLI Android Test](../capabilities/tools/cli-android-test.md)
- [Android Test run or test failed](../troubleshooting/android-test-failed.md)
