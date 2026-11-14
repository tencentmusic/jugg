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
- You modified too many files or modules: Jugg shows a confirmation dialog and defaults to a full Gradle build. After the countdown you can continue incremental compilation for this run.
- The previous incremental compilation failed: This is expected. The current Gradle build restores a trusted baseline.

If none of these cases apply, [report the issue](../guide/report-issue.md).

## Q: Jugg reports “Waiting Jugg initializing finish...” without starting compilation

There are generally two cases:
1. You started two consecutive runs. Wait for the previous run to be interrupted or finish.
2. If Jugg remains stuck for more than 30 seconds, close and reopen the project to reset its state.

If the problem persists, [report the issue](../guide/report-issue.md).

## Q: Does Jugg use a lot of memory?

Two heap snapshots of the JOOX project, with approximately 3 million lines of code, showed:

| Measured scope | Retained memory |
| --- | ---: |
| Jugg's own objects and the data they retain | 31–36 MiB |
| Isolated Kotlin compiler environment | 9–11 MiB |

Test environment: October 15, 2026; M3 Pro (12 cores, 36 GiB), macOS 26.6.2, Android Studio 2025.3.4 Patch 1, JBR 21.0.11, and Jugg 3.5.4-release, with three projects open. The CPU measurements below use the same environment.

A full local Gradle build may involve multiple concurrent build processes and increase memory pressure. A remote build does not itself add a local Gradle daemon.

## Q: Does Jugg keep using CPU when it is not compiling?

During 90 seconds without user activity, **0 of 2,438 Java stack samples contained Jugg business code**.

A blank line was added to and removed from one Java, Kotlin, and resource file, with four incremental compilation and deployment runs per type. All runs succeeded. Regular hot deployments measured:

| Change type | Compilation and deployment time | IDE CPU time | Separate resource process CPU time |
| --- | ---: | ---: | ---: |
| Java (3 runs) | 2.85–3.24 s | 4.36–8.54 CPU s | 0 |
| Kotlin (4 runs) | 2.96–4.54 s | 6.58–10.78 CPU s | 0 |
| Resources (3 runs) | 2.21–3.15 s | 4.26–12.43 CPU s | 0.07–0.10 CPU s |

CPU seconds sum the processing time across cores. IDE CPU time includes IDE background tasks, and separate resource process CPU time measures `aapt2-inclink`.

If CPU usage remains high after background tasks finish, or memory keeps growing or runs out, [report the issue](../guide/report-issue.md). Include the time of the anomaly, versions, and `idea.log` and Jugg logs from the same period.
