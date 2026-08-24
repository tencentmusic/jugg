---
title: First run
description: Learn what happens on the first Jugg Run, how to read the result, and when to fall back to Gradle manually.
status: active
tags:
  - onboarding
  - run
---

# First run

After installing the plugin and waiting for project Sync to finish, run the app with Jugg once. The first run establishes the Gradle baseline and deployment state; it is not intended to demonstrate incremental-build speed.

## Before clicking Run

Confirm the following before clicking Run:

1. The selected run configuration is `jugg:module-name`, not the native App configuration.
2. A target device is selected and runs Android 8 or later.
3. Project Sync has finished and no Gradle import is in progress.

Then click Run in Android Studio. A Jugg Run can be canceled; stop the run if you selected the wrong device or configuration.

## What happens during the first run

```text
Click Jugg Run
  -> check the project and device state
  -> fall back to a Gradle build because no incremental baseline exists yet
  -> build and install the APK
  -> collect artifacts required by later incremental builds in the background
  -> prefer incremental compilation and deployment for subsequent small changes
```

Jugg also falls back to Gradle when the baseline is no longer trustworthy: on the first run, after a `build.gradle` or dependency change, or after switching branches. The run output explains the reason for the fallback.

## Everyday workflow after making changes

After small changes to code, resources, layouts, or assets, click the same Jugg Run Configuration again. Jugg chooses an execution path based on the changed files:

| Change type | Typical result |
|---|---|
| Java or Kotlin method-body changes and small resource changes | Hot deployment after incremental compilation |
| Code that requires a restart to take effect | Restart the app after compilation |
| Gradle changes, dependency changes, or a missing baseline | Fall back to a Gradle build |
| An explicitly unsupported scenario | Report a failure or recommend a comparison Gradle build |

Use the final result in the Run tool window as the outcome of the run.

## Important limitations

- Jugg ignores delete operations. After deleting a class, resource, or Manifest node, run a full Gradle build or reinstall the app if you need to confirm that the old content is truly gone.
- Reflection can bypass parts of static impact analysis. Do not rely only on an incremental result when validating deletions used through reflection.
- Only supported annotation processors can run incrementally. Existing generated code remains available, but after adding or changing an unsupported annotation, use a Gradle build to verify the result.
- Clearing app data removes the deployment history. Click Run once more and Jugg will restore the deployment state.
- If an incremental result is unexpected, run a Gradle build for comparison first. Upload the logs after confirming that the problem is specific to Jugg.

## Fall back to Gradle manually

Use a manual Gradle fallback first in these situations:

| Scenario | Reason |
|---|---|
| You manually cleaned the `build` directory | Artifacts required by incremental compilation are missing |
| You changed build scripts, plugins, or dependency versions | The complete Gradle pipeline must recalculate the build state |
| Incremental compilation failed and could not recover automatically | Rebuild the baseline before continuing with incremental runs |
| You suspect that the current result is incorrect | Compare it with a Gradle result |

If no files have changed, click Jugg Run again and choose the Gradle fallback. This entry is useful for refreshing the baseline manually.

## Report an issue

When a problem occurs, use [Report an issue](../guide/report-issue.md) to upload logs first. Send the resulting Issue ID and reproduction steps to the maintainer.

The local log is available at `build/jugg/log/compile_latest.log`.

## Next steps

- [Run the app](../guide/run.md)
- [Report an issue](../guide/report-issue.md)
- [Compilation failed](../troubleshooting/compile-failed.md)
- [Changes not applied](../troubleshooting/changes-not-applied.md)
- [App cannot run](../troubleshooting/app-cannot-run.md)
