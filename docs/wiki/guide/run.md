---
title: Run App
description: Explains the Jugg Run entry in Android Studio and how one run handles compile, deploy, launch, and result checks.
status: active
tags:
  - guide
  - run
---

# Run App

For day-to-day development, you usually do not compile first and deploy second as two manual steps. After changing code or resources, select a Jugg Run Configuration and click Run in Android Studio. Jugg saves files, detects changes, compiles, deploys, and launches the app in one run.

## When to Click Run

Use Jugg Run when you:

- Changed common Java / Kotlin, layout, drawable, values, or asset files.
- Want to verify the app on a device immediately.
- Are unsure whether the change can be handled incrementally.
- Want Jugg to fall back to Gradle when incremental handling is not appropriate.

Some cases still need a stronger baseline: branch switches, large pulls, Gradle plugin or dependency changes, clean-data install checks, release builds, annotation processing, bytecode weaving, or full Gradle validation.

## In Android Studio

1. Select a Jugg Run Configuration, not the native App configuration.
2. Select the target device.
3. Click Run.
4. Check the Run tool window for the final result.

Debug uses the same first half of the flow: Jugg compiles and deploys first, then restarts the app in debug mode and hands debugger attach to Android Studio.

## What Happens After Run

```text
Jugg Run
  -> save files and refresh file state
  -> check device, install state, and file changes
  -> try incremental compile first
  -> prompt or fall back to Gradle when needed
  -> deploy to the selected device after compile succeeds
  -> choose Hot Reload, Hot Fix, install, or restart
  -> print the run result
```

Compile and deploy are internal stages in this flow. The user-facing action is still one Run.

## Reading the Result

| Result | Meaning | Next step |
|---|---|---|
| Jugg Hot Reload succeeded | The change was applied online, usually without restarting the app | Verify in the current app state |
| Jugg Hot Fix succeeded | The change was pushed and the app restarts before it takes effect | Wait for launch, then verify |
| Gradle build and install succeeded | This run used a full Gradle build and install | Continue with Jugg Run for smaller changes |
| Clean Reinstall succeeded | App data was cleared, APK was reinstalled, and Jugg deploy state was restored | Navigate back to the target screen |
| Compile succeeded but deploy failed | Code compiled, but device deploy or launch failed | Check device connection, compatibility mode, and deploy logs |
| No file changes detected | Jugg did not find a change it can process | Save files, sync if needed, or compare with Gradle |

Logs start here:

```bash
build/jugg/log/compile_latest.log
```

## Related Pages

- [Compile Stage](./compile.md)
- [Deploy Results](./deploy.md)
- [Debug](./debug.md)
- [Android Test](./android-test.md)
- [Fallback and Limits](../concepts/fallback-and-limits.md)
