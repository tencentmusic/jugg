---
title: Report an issue
description: Upload issue logs through Jugg and understand what the Issue ID represents.
status: active
tags:
  - guide
  - report
  - logs
---

# Report an issue

Report an issue uploads local Jugg logs and device error logs to the team's backend. Use it when incremental compilation or deployment fails, or when runtime results are unexpected.

## Where to open it

Use either of these entry points:

- Press `Shift` twice in Android Studio, then search for and select `Report Jugg Issue`.
- Open `Jugg Running Panel` and click `Report Issue`.

You can also open it from a Jugg Run Configuration:

1. Open `Edit Configurations...`.
2. Select the current Jugg configuration.
3. Click `Report issues`.

After the report window opens:

1. Review and select the diagnostic files.
2. Select `Upload logs`, or select `Save locally without uploading` to create a local diagnostic bundle.
3. After the upload finishes, copy the Report ID. If the upload fails, give the retained ZIP file to the maintainer.

The plugin first generates redacted candidate files, then displays their paths and sizes. Uploads always target the Jugg issue-reporting service and do not switch servers after failure. When saving locally, the system file manager selects the newly generated ZIP file.

## What is uploaded

The uploaded content is intended to diagnose the current Jugg behavior:

- Jugg compilation and deployment logs.
- A structured environment and project summary without the raw project model.
- Cancelable error logcat for the target device.
- Optional hook debug logs.
- A `manifest.json` describing the actual ZIP entries.

Raw `project_infos`, signing passwords, Manifest placeholders, APT/KAPT arguments, source code, and binary dependencies are not included in the diagnostic bundle. Hook debug logs are stored as `diagnostics/cli/hook-debug.log` in the bundle.

> [!NOTE]
> Upload failure does not change the local compilation or deployment result. The temporary ZIP remains under `build/jugg/tmp/diagnostics` and can be uploaded again. It is deleted by a cleanup task after project startup once it reaches 7 days old.

## Local log location

If uploading is temporarily unavailable, inspect the latest log first:

```bash
build/jugg/log/compile_latest.log
```

This file contains the main log for the most recent compilation and deployment. It is usually the first place to check deployment, fallback, and runtime problems.

## Related pages

- [First run](../onboarding/first-run.md)
- [Log files](../reference/log-files.md)
- [Compilation failed](../troubleshooting/compile-failed.md)
- [Changes did not take effect](../troubleshooting/changes-not-applied.md)
- [The app cannot install, launch, or enter Debug](../troubleshooting/app-cannot-run.md)
