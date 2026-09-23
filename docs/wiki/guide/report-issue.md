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

Report an issue packages the current Jugg logs and device error logs, then uploads them to an issue-reporting service. Use it when incremental compilation or deployment fails, or when runtime results are unexpected. Manual reports prefer the currently available Jugg backend and use Jugg's public service when no backend is available.

A team backend can also enable automatic failure-log uploads. Automatic uploads go only to the currently available backend, omit adb logcat, and do not show a confirmation window.

## Where to open it

Use either of these entry points:

- Press `Shift` twice in Android Studio, then search for and select `Report Jugg Issue`.
- Open `Jugg Running Panel` and click `Report Issue`.

You can also open it from a Jugg Run Configuration:

1. Open `Edit Configurations...`.
2. Select the current Jugg configuration.
3. Click `Report issues`.

When no backend service is available, after the report window opens:

1. Review and select the diagnostic files. Jugg logs are selected by default and cannot be cleared. Project snapshots are selected by default but can be cleared.
2. Select `Upload logs`, or select `Save locally without uploading` to create a local diagnostic bundle.
3. After the upload finishes, copy the Report ID. If the upload fails, click `Retry Upload`, or give the retained ZIP file to the maintainer.

The confirmation window shows the public upload address `https://jugg.sickworm.com/report_issue`. When saving locally, the system file manager selects the newly generated ZIP file.

When a backend service is available, clicking the report entry skips the file and destination confirmation window. Jugg uploads the previously default-selected diagnostic content directly to that server's `/report_issue`. You cannot clear project snapshots or choose local-only saving in this flow. Backend uploads allow HTTP or HTTPS; HTTP does not protect the content with TLS. An invalid destination or failed upload is not forwarded to the public service. The result window still lets you retry the same destination.

After a successful upload, the result window shows an 8-character lowercase hexadecimal Report ID. Send it to the maintainer together with the reproduction steps. A copied backend result also has a `Server Url` line identifying the server that was used.

## What is uploaded

The uploaded content is intended to diagnose the current Jugg behavior:

- Jugg compilation and deployment logs.
- A structured environment and project summary.
- Cancelable IDE, Gradle, and included-build project snapshots that are selected by default.
- Cancelable error logcat for the target device.
- Optional hook debug logs.
- A `manifest.json` describing the actual ZIP entries.

Project snapshots include the existing `project_infos.json` and `gradle_project_infos.json` files, plus the `include_build_*_gradle_project_infos.json` files for current included builds. Redacted copies are stored under `diagnostics/project-info/` in the bundle. Diagnostic information such as `applicationId` and whether a field exists is preserved. Signing credentials, keystores, key aliases, Manifest placeholders, APT/KAPT arguments, and common sensitive field values are replaced. Snapshots that cannot be parsed, stale included-build snapshots, other files from `project_infos.db`, source code, and binary dependencies are not included. Hook debug logs are stored as `diagnostics/cli/hook-debug.log`.

Manual uploads send the project name and developer username as separate form fields only to an explicitly configured Custom Server for backend classification. Automatically selected backends and the public service still receive only the bundle; manual reports are not marked as automatic uploads. When uploading to a Custom Server, Jugg logs, device logcat, and hook debug logs keep their original content, including project paths and usernames. Uploads to an automatically selected backend or the public service, and locally saved bundles, still redact these logs. Sensitive fields in project snapshots are always redacted. Point a Custom Server only to a trusted service, especially since HTTP does not encrypt transport.

> [!NOTE]
> Upload failure does not change the local compilation or deployment result. The temporary ZIP remains under `build/jugg/tmp/diagnostics` and can be uploaded again. It is deleted by a cleanup task after project startup once it reaches 7 days old.

## Automatic failure-log uploads

A team backend can distribute `autoUploadFailureLogs=true` so Jugg automatically uploads the two most recent logs after a final compilation failure or an actual deployment failure. User cancellation, skipped deployment, no device before deployment starts, and a successful final result after a Gradle fallback do not trigger an automatic upload. Each Run uploads at most once.

The automatic bundle contains the two most recent real Jugg logs, environment information, a project summary, redacted project snapshots, the hook debug log when present, and the manifest. It does not collect adb logcat. Logs follow the destination-specific rules described above. The backend can also use `autoUploadFailureLogsExcludeRegex` to exclude known errors. When the regex matches the current Run's final error summary, Jugg does not upload. An empty regex applies no filtering, while an invalid regex skips that upload.

An automatic upload includes an automatic-upload marker, a failure summary, and any available detailed error alongside the bundle. It also sends the project name, developer username, plugin version, and Report ID. Error text remains original only for an explicitly configured Custom Server; other backends receive redacted text. Manual reports do not include the automatic-upload marker and remain manual reports.

Automatic uploads run asynchronously without a dialog or retry. An upload failure does not change the original compilation or deployment result. Only an available backend receives them at `/report_issue` (HTTP or HTTPS); without one, the upload is skipped and never sent to the public service.

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
