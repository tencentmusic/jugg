---
title: Library Android Test
description: Explains how Jugg adds, deploys, and runs Test APKs for self-targeting Android Tests in library modules.
status: active
tags:
  - capability
  - test
  - library-android-test
---

# Library Android Test

Jugg supports self-targeting Android Tests in library modules. These tests have their own Test APK and runtime package. When sourcePath matches the target library androidTest, Jugg uses the corresponding Test APK for the current deployment and instrumentation run.

## Library test scenarios

| User scenario | Current support | Behavior |
|---|---|---|
| Run a test from library `src/androidTest` | Supported | sourcePath matches the corresponding androidTest module |
| The current APK list lacks the library Test APK | Can be added | Runs the corresponding AndroidTest assemble task only for the matched module |
| Install a self-targeting library Test APK | Supported | Deploys it as an independent runtime package |
| Reuse a recent Test APK build record later | Supported | Replays recent tasks during a full Android Test build |
| Write to the correct target in a multi-APK scenario | Supported | Filters deployment items by target APK ownership |

> [!NOTE]
> Library Android Test here means a self-targeting Test APK, where the test package matches the instrumentation target package. An app-style other-targeting test APK does not use this missing-APK lazy-load path.

## How a Test APK is added

```text
sourcePath points to library src/androidTest
  -> Match exactly one androidTest module
  -> The current APK list lacks that module's Test APK
  -> Confirm that it is a self-targeting Test APK
  -> Run the corresponding assemble<Variant>AndroidTest
  -> Add the new Test APK to the current deployment targets
  -> Update deployment history and overlay ID after installation
```

Jugg adds only the library Test APK precisely matched by the current `sourcePath`, preventing one test run from expanding into a build of every project Test APK. The added Test APK enters the current APK list and participates in deployment as an independent runtime package.

## Difference from Application Android Test

| Item | Application Android Test | Library Android Test |
|---|---|---|
| Common source location | App `src/androidTest` | Library `src/androidTest` |
| Runtime process | Process of the app under test | Self-targeting test package |
| Test APK source | Produced by a full Android Test build | Can be added lazily for the matched library androidTest |
| Deployment strategy | Routes the app APK and app test APK by ownership | Deploys the library Test APK as an independent target |

## Build history

After a library Test APK builds successfully and passes target validation, Jugg records the AndroidTest Gradle task and APK output pattern for the module. Later full Android Test builds can replay recent records, reducing the chance that the Test APK must be added manually again.

History is used only to rediscover recent library Test APK build tasks; it does not mean the Test APK remains valid forever. If an artifact is missing, Jugg skips that optional APK and returns to the add flow when it is needed.

## Related pages

- [Android Test guide](../../guide/android-test.md)
- [Android Test flow](../../concepts/android-test-flow.md)
- [Application Android Test](./application-android-test.md)
- [Test Results UI](./test-results-ui.md)
- [Logcat attribution](./logcat-attribution.md)
- [Multiple APKs](../deploy/multi-apk.md)
