---
title: Jugg Control Panel
description: View live status, recent runs, structured logs, quick actions, and settings in the Jugg Control Panel.
status: active
tags:
  - guide
  - control-panel
  - logs
---

# Jugg Control Panel

The Jugg Control Panel brings the current stage of a run, pending files, recent results, and common recovery actions into one project-level tool window. Use it when Run output is too long, when you need to compare several runs, or when you want to quickly determine whether the project has an incremental baseline.

## One Run output cannot show continuous state

The Run tool window is better for the complete text output of one task, but everyday diagnostics often require answers to several questions at once:

- Is the current task in change detection, compilation, deployment, or launch?
- Which files are still pending?
- Did recent runs use incremental compilation, Gradle, Hot Reload, Hot Fix, or installation?
- Does the current project have a usable incremental baseline?

The Control Panel maintains the current IDE session state from structured events, so recent results remain visible after you switch Run tabs.

## Open the panel

Projects with a runnable Jugg configuration show the `Jugg Running Pannel` tool window on the right side of Android Studio. You can also open it from `Tools > Open Jugg Control Panel`.

Entry points in the Run Configuration that require settings open the Settings page directly, so you do not need to search through two windows.

## Overview

Overview combines the following information:

| Area | Contents |
|---|---|
| Run status | Current task stage, stage progress, and elapsed time |
| Changed files | Pending files, their modules and file types; double-click a file to open it |
| Quick actions | Gradle build, clear Jugg, restart the app, clear data and reinstall, report an issue, check for updates, install CLI/Skill |
| This session | Counts of successful compilations, Hot Reloads, Hot Fixes, and installations in this session |
| Recent runs | Compilation mode, deployment mode, elapsed time, failure reason, and related files for recent runs |

`Hot reload baseline is ready` means the project has a baseline that can continue incremental work. `Full Gradle build required` means you should complete a full build first.

## Logs

The Logs page shows Jugg's structured core events and supports filtering by source, level, current task, and keyword.

- Sources include Deploy, Runtime, and CLI / MCP.
- Levels include Info, Warn, and Error.
- `Current task` keeps only events from the current task.
- `Follow` automatically follows new events.
- Select an event to copy it for an issue report.

Structured logs help identify the stage quickly, but they do not replace full logs. To inspect Gradle output, exception stacks, or lower-level deployment details, open `build/jugg/log/compile_latest.log`.

## Settings

The Settings page lets you adjust common runtime behavior:

- Whether to confirm Gradle fallback when no files have changed.
- Whether to always restart the app after deployment.
- Whether to enable Quick deploy.
- Whether to fall back to Gradle automatically after deployment fails.
- Whether to embed changes in the APK.
- Whether to use the project's Kotlin compiler.
- Whether to back up the classpath.

You can also install the CLI and Agent Skills, check for updates, or run `Clear Jugg Build` to reinitialize the project.

> [!WARNING]
> `Clear Jugg Build` deletes project-level Jugg build data. Use it when the cache is clearly corrupted or the baseline cannot be recovered, not as a routine cleanup action.

## Session boundaries

Recent runs, session counts, and structured events primarily describe the current Android Studio session. After an IDE restart, an empty Recent runs list does not mean Jugg has never run. Project-level caches still retain the persistent compilation context and deployment history.

## Related pages

- [Run an app](./run.md)
- [Run configurations and build variants](./run-configuration.md)
- [Report an issue](./report-issue.md)
- [Log files reference](../reference/log-files.md)
- [Report an issue](./report-issue.md)
