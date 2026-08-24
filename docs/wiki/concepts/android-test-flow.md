---
title: Android Test flow
description: Explains the dual-APK baseline for Android Test, runtime ownership of incremental artifacts, the instrumentation launch order, and why deployment state and test results are committed separately.
status: active
tags:
  - concept
  - android-test
---

# Android Test flow

An Android instrumentation test depends on the app APK under test, the test APK, and the runner and target package declared in the test APK. A full Gradle build generates these artifacts and establishes their relationships. Jugg incrementally compiles app and androidTest source code on top of this baseline, first makes the changes take effect in the correct runtime environment, and then runs instrumentation.

This means Android Test cannot determine which APK should receive an artifact solely from the source directory, nor can it treat a failed test assertion as a deployment failure. This page explains how Jugg maintains the dual-APK baseline, deploys incremental artifacts according to runtime ownership, and commits deployment state and test results separately.

## Android Test depends on two sets of APKs

A normal App Run only needs to install and launch the app. Android Test must also install the test APK, whose instrumentation runner loads the tests in the process associated with the target package and drives the code under test. A runnable Android Test baseline therefore requires at least:

- The app APK under test and its runtime state on the device.
- The test APK, runner, and target package.
- The modules and runtime packages that own the app and androidTest source code.

Jugg obtains this information through a full Gradle build for the Android Test target. During a full installation, it installs the app APK under test before the test APK so that the target package referenced by the test APK already exists on the device.

## How a trusted baseline enables incremental compilation

After the baseline is established, later Android Test Runs do not need to repeat a full build. Jugg includes app and androidTest source code in the same change-detection pass, compiles only the current changes and affected code, and passes the incremental artifacts to the existing deployment strategy:

```text
trusted app APK and test APK baseline
  -> detect app and androidTest source changes
  -> compile current changes and affected code
  -> split incremental artifacts by runtime ownership
  -> deploy to the corresponding app or test runtime
  -> run am instrument after deployment succeeds
```

If no files changed in the current run, Android Test can proceed directly through an empty deployment and instrumentation without rerunning Kotlin, D8, or a full Gradle build merely to rerun the same test. If the baseline is missing, the build target changes, or the existing artifacts are no longer trusted, Jugg reruns the Gradle build to prevent app and test artifacts from coming from different baselines.

## Incremental artifacts are deployed by runtime ownership

androidTest source code resides in the test source set, but the source directory alone cannot determine whether incremental code ultimately belongs in the test APK. Jugg chooses the deployment target according to the runtime package used by instrumentation:

| Test type | Runtime location | Incremental artifact ownership |
|---|---|---|
| Application Android Test whose test APK targets the app under test | App process under test | Runtime overlay for the app under test |
| Self-targeting Library Android Test whose test package equals the target package | Separate test package | Corresponding library test APK |

This routing lets test code in an app-style Android Test follow the app runtime that actually loads it, while preserving the independent installation and deployment state of a self-targeting library test APK. When multiple APKs are deployed, each runtime package receives only its own classes, resource overlays, and APK updates, preventing app and test artifacts from being sent to the wrong target.

## How a missing Library Test APK is supplied

A self-targeting Library Android Test has a separate test package, so the current APK list must contain the corresponding test APK. If the source anchor uniquely identifies the library androidTest module but the test APK is missing, Jugg runs only the Android Test Gradle task for that module and installs the new artifact as a complete APK instead of building every library test APK in the project.

After the APK is supplied successfully, Jugg records the recently used Gradle task and APK output matching information for replay during later full Android Test builds. This record only helps locate the library test APK that needs to be built again. If the artifact has been deleted or is no longer valid, Jugg still returns to the supply flow.

## Instrumentation starts only after deployment completes

Jugg runs `am instrument` with the runner from the test APK and the current test scope only after installation, code replacement, or APK update has completed on the device. It does not start tests after a deployment failure. After deployment succeeds, instrumentation output is sent to the Run window and Test Results.

For a multi-device run, each device completes deployment and instrumentation independently and maintains separate test results. A deployment failure, instrumentation abort, or test failure on any device causes the overall Android Test Run to fail, while results for each device remain available so that the failure scope can be identified.

## Deployment state and test results are committed separately

Test assertions run after deployment has completed. An assertion failure means the current test did not pass, but it does not invalidate the classes, resource overlays, or APK updates already written to the device. Jugg therefore advances deployment history and runtime state for the completed deployment while marking the current Run as a test failure.

This separation lets the next rerun reuse the refreshed app and test runtimes. If no new files changed, Jugg can run instrumentation again directly instead of recompiling or reinstalling every artifact because the previous assertion failed.

## Boundaries

- Android Test still depends on a trusted Gradle baseline. Jugg returns to a Gradle build when the app APK, test APK, or valid test module information is missing.
- Targeted supply of a missing APK applies only to a self-targeting Library Android Test uniquely identified by a source anchor.
- See [Test capabilities](../capabilities/test/) for the specific support scope of androidTest resources, annotation processing, and Debug Executor.
- An instrumentation failure does not roll back a successful deployment, but it still causes the current Android Test Run to fail.

## Related pages

- [Android Test guide](../guide/android-test.md)
- [Test capabilities](../capabilities/test/)
- [Application Android Test](../capabilities/test/application-android-test.md)
- [Library Android Test](../capabilities/test/library-android-test.md)
- [Test Results UI](../capabilities/test/test-results-ui.md)
- [Deployment strategy](./deploy-strategy.md)
