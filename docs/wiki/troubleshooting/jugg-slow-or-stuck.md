---
title: Jugg is slow or stuck
description: Resolve unexpected Gradle builds, launch waits, Sync blocking, slow dependency analysis, and high Android Studio resource usage.
status: active
tags:
  - troubleshooting
  - performance
---

# Jugg is slow or stuck

First identify whether the time is spent in Jugg incremental compilation, a full Gradle build, project Sync, or waiting for the device and app. Each stage requires a different recovery action.


## Q: The current run suddenly uses a full Gradle build

First check the initial output in the Run window or the Jugg Running Panel to confirm that the current run is a Gradle build rather than incremental compilation.

Common cases and their corresponding actions:

- You clicked Run by mistake without any file changes: Cancel the current Gradle build.
- You switched between app and Android Test targets: This is expected. Wait for the current run to establish a new APK baseline.
- You modified too many files or modules: This is expected. Jugg selects a full Gradle build when incremental compilation would cost more.
- The previous incremental compilation failed: This is expected. The current Gradle build restores a trusted baseline.

If none of these cases apply, [report the issue](../guide/report-issue.md).

## Q: Jugg reports “Waiting Jugg initializing finish...” without starting compilation

There are generally two cases:
1. You started two consecutive runs. Wait for the previous run to be interrupted or finish.
2. If Jugg remains stuck for more than 30 seconds, close and reopen the project to reset its state.

If the problem persists, [report the issue](../guide/report-issue.md).
