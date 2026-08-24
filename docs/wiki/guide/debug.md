---
title: Debug
description: Use Jugg Debug, understand its run flow and single-device limit, and find the first checks when breakpoints do not work.
status: active
tags:
  - guide
  - debug
---

# Debug

Jugg Debug replaces the workflow of running Jugg first and then attaching the debugger manually. After you click Debug, Jugg reuses the normal compilation and deployment flow, restarts the app in debug mode after deployment succeeds, and requests the native Android Studio Java debugger attach.

## When to use it

Jugg Debug is suitable when:

- You want to enter breakpoint debugging immediately after changing code.
- The app launches quickly and manual Attach is likely to miss the right moment.
- You do not want to enable waiting debugger manually in Developer options.

Current limitations:

- Debugging multiple devices at the same time.
- Debugging androidTest.
- Debugging native / C++ code.

## How to use it

1. Select only one target device.
2. Select a Jugg Run Configuration.
3. Click Debug.
4. Wait for Jugg to compile, deploy, and restart the app.
5. After the Android Studio Debug tool window appears, check whether breakpoints are available.

Jugg Debug saves currently open files and refreshes file state, preventing Debug from incorrectly reporting no file changes when a normal Run would detect them.

## Run flow

```text
Jugg Debug
  -> Save files and refresh the VFS
  -> Run Jugg compilation and deployment
  -> Restart the app with am start -D -S
  -> Wait for the target process to enter debugger WAITING state
  -> Request the native Android Studio attach flow
  -> Let Android Studio create and activate the Debug session
```

Jugg does not take over the Debug tool window or create the final XDebugSession directly. It only starts the app in an attachable state, then hands control to the native Android Studio debugging flow.

## Differences from a normal Jugg Run

| Behavior | Jugg Run | Jugg Debug |
|---|---|---|
| Forced restart after deployment | Depends on the change type and settings | Always restarts in debug mode |
| No file changes | May ask whether to fall back to Gradle | Can perform an empty deployment and proceed to attach |
| Multiple devices | Deploys to devices one by one | Does not support Debug attach |
| Result window | Run tool window | Compilation and deployment output appears in Run; debugging is handled by the Debug tool window |

## First checks when breakpoints do not work

Do not determine breakpoint availability only from a Jugg log saying that the app is waiting for the debugger. Check in this order:

1. Whether `Jugg Debug attach` appears in `build/jugg/log/compile_latest.log`.
2. Whether Android Studio's `idea.log` says the target package entered a debuggable state.
3. Whether the Debug tool window appears.
4. Whether `Connected to the target VM` appears in `idea.log`.
5. Whether native Android Studio Attach can hit the same breakpoint.

If you see only “Debugger is waiting for application to start” but not “Connected to the target VM,” the Android Studio Java debugger has not completed the VM connection, so breakpoints will not work.

## Common problems

| Symptom | Action |
|---|---|
| Multiple devices are selected | Keep one device selected and try again |
| Debug behaves like a normal Run | Confirm that you are using a Jugg Run Configuration, not a native App configuration |
| The app does not wait for the debugger | Check whether deployment succeeded and whether the target package name is correct |
| Run output appears but no Debug window opens | Check whether the Android Studio attach flow succeeded |
| An older Android Studio version reports that the feature is unsupported | Check compatibility between the current Jugg and Android Studio versions |

## Related pages

- [Run an app](./run.md)
- [Deployment results](./deploy.md)
- [The app cannot install, launch, or enter Debug](../troubleshooting/app-cannot-run.md)
- [Limitations](../reference/limits.md)
- [Compatibility](../reference/compatibility.md)
