---
title: Report an issue
description: Upload issue logs through Jugg and understand what the Report ID represents.
status: active
tags:
  - guide
  - report
  - logs
---

# Report an issue

Report an issue packages the current Jugg logs and device error logs, then uploads them to a fixed issue-reporting service. Use it when incremental compilation or deployment fails, or when runtime results are unexpected. The upload destination does not change whether or not a Jugg backend or Custom Server is configured.

A team backend can also enable automatic failure-log uploads. Automatic uploads use the same fixed service as the manual flow on this page, but contain less data and do not show a confirmation window.

## Where to open it

Use either of these entry points:

- Press `Shift` twice in Android Studio, then search for and select `Report Jugg Issue`.
- Open `Jugg Running Panel` and click `Report Issue`.

You can also open it from a Jugg Run Configuration:

1. Open `Edit Configurations...`.
2. Select the current Jugg configuration.
3. Click `Report issues`.

After the report window opens:

1. Review and select the diagnostic files. Jugg logs are selected by default and cannot be cleared. Project snapshots are selected by default but can be cleared.
2. Select `Upload logs`, or select `Save locally without uploading` to create a local diagnostic bundle.
3. After the upload finishes, copy the Report ID. If the upload fails, click `Retry Upload`, or give the retained ZIP file to the maintainer.

The confirmation window shows the fixed upload address `https://jugg.sickworm.com/report_issue`. The plugin sends the diagnostic bundle only to that HTTPS address. It does not read the Custom Server setting and does not switch servers after failure. When saving locally, the system file manager selects the newly generated ZIP file.

After a successful upload, the result window shows an 8-character lowercase hexadecimal Report ID. Send it to the maintainer together with the reproduction steps.

## What is uploaded

The uploaded content is intended to diagnose the current Jugg behavior:

- Jugg compilation and deployment logs.
- A structured environment and project summary.
- Cancelable IDE, Gradle, and included-build project snapshots that are selected by default.
- Cancelable error logcat for the target device.
- Optional hook debug logs.
- A `manifest.json` describing the actual ZIP entries.

Project snapshots include the existing `project_infos.json` and `gradle_project_infos.json` files, plus the `include_build_*_gradle_project_infos.json` files for current included builds. Redacted copies are stored under `diagnostics/project-info/` in the bundle. Diagnostic information such as `applicationId` and whether a field exists is preserved. Signing credentials, keystores, key aliases, Manifest placeholders, APT/KAPT arguments, and common sensitive field values are replaced. Snapshots that cannot be parsed, stale included-build snapshots, other files from `project_infos.db`, source code, and binary dependencies are not included. Hook debug logs are stored as `diagnostics/cli/hook-debug.log`.

> [!NOTE]
> Upload failure does not change the local compilation or deployment result. The temporary ZIP remains under `build/jugg/tmp/diagnostics` and can be uploaded again. It is deleted by a cleanup task after project startup once it reaches 7 days old.

## Automatic failure-log uploads

A team backend can distribute `autoUploadFailureLogs=true` so Jugg automatically uploads the two most recent logs after a final compilation failure or an actual deployment failure. User cancellation, skipped deployment, no device before deployment starts, and a successful final result after a Gradle fallback do not trigger an automatic upload. Each Run uploads at most once.

The automatic bundle contains only the two most recent redacted real Jugg logs and the manifest. It does not contain project snapshots, environment summaries, logcat, or hook logs. The backend can also use `autoUploadFailureLogsExcludeRegex` to exclude known errors. When the regex matches the current Run's final error summary, Jugg does not upload. An empty regex applies no filtering, while an invalid regex skips that upload.

Automatic uploads run asynchronously without a dialog or retry. An upload failure does not change the original compilation or deployment result. The destination remains `https://jugg.sickworm.com/report_issue` and does not use Custom Server.

## Local log location

If uploading is temporarily unavailable, inspect the latest log first:

```bash
~/.jugg/log/<projectName>_<pathHash>/compile_latest.log
```

This file contains the main log for the most recent compilation and deployment. It is usually the first place to check deployment, fallback, and runtime problems.

## Related pages

- [First run](../onboarding/first-run.md)
- [Log files](../reference/log-files.md)
- [Compilation failed](../troubleshooting/compile-failed.md)
- [Changes did not take effect](../troubleshooting/changes-not-applied.md)
- [The app cannot install, launch, or enter Debug](../troubleshooting/app-cannot-run.md)
