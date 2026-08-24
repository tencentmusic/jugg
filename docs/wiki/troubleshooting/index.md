---
title: Troubleshooting
description: Recover Jugg by starting from user-visible symptoms such as compilation failures, changes that did not take effect, runtime crashes, launch failures, and slow runs.
status: active
tags:
  - troubleshooting
---

# Troubleshooting

When you encounter a problem, find the page that matches the symptom or use search. Each page starts with recovery actions you can perform directly. If the problem persists, report it on GitHub.

## Common development problems

### [Compilation failed](./compile-failed.md)

Use this page for compilation errors in Java, Kotlin, resources, assets, Manifest, and similar inputs, especially when Jugg incremental compilation fails but Gradle succeeds.

### [Changes did not take effect](./changes-not-applied.md)

Use this page when Jugg reports no file changes after you modify a file, or when code or resources still show the old result after a successful compilation.

### [App crashed after deployment](./runtime-crash.md)

Use this page when compilation and deployment succeed, but the app then throws a Java exception or native crash, especially when the app crashes after Jugg incremental compilation but works correctly with a Gradle build.

### [Installation, deployment, launch, or Debug failed](./app-cannot-run.md)

Use this page for unavailable devices, APK installation failures, app launch failures, deployment state recovery failures, an unresponsive JVMTI agent, or Debug attach failures.

### [Jugg is slow or stuck](./jugg-slow-or-stuck.md)

Use this page when an unexpected Gradle build makes compilation take a long time, the app hangs during startup before reaching its main screen, or Android Studio remains at high CPU usage or freezes.

## Feature-specific problems

### [Remote compilation failed](./remote-build-failed.md)

Use this page for compilation failures after enabling remote compilation, including an unsynchronized remote project, Gradle Wrapper problems, Windows line endings and encoding, APK transfer, and custom output directory problems.

### [Android Test run or test failed](./android-test-failed.md)

Use this page when test source, the test APK, test classes, or instrumentation cannot be resolved or run.

### [Agent or CLI command failed](./agent-command-failed.md)

Use this page when Jugg CLI or MCP reports parameter or runtime environment errors for UI automation, layout inspection, screenshots, or screen recordings.

## Choosing a common recovery action

- If compilation fails because a referenced symbol does not exist, Gradle Sync will usually resolve the problem.
- If a change does not take effect, check whether it modifies startup code and the deployment used HOT RELOAD. If so, restart the app.
- If the problem occurs only on a specific device, enable compatibility mode and run again to see whether it disappears.
- If none of the cases above apply and your Gradle build is not especially slow, run a Gradle build and check whether the problem disappears. This is the fastest self-healing path.
  > Jugg saves logs from the 10 most recent Gradle builds or project openings. You can still report the issue on GitHub after the problem disappears.

## Still not resolved

Use [Report an issue](../guide/report-issue.md) to upload the diagnostic data and copy the Report ID. To investigate manually, see [Log files](../reference/log-files.md).
