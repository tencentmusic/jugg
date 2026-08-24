---
title: Runtime and device CLI
description: Explains how Jugg CLI reads status, devices, the Activity stack, and app logs.
status: active
tags:
  - capability
  - tools
  - cli
  - runtime
---

# Runtime and devices

Runtime and device commands inspect the current Jugg status, device list, Activity stack, and app log window outside compilation and deployment. Agents commonly use them to decide whether to compile, deploy, wait for logs, or resolve a device problem first.

## Supported tasks

| User task | Current support | Command |
|---|---|---|
| Inspect deployment state, the summary of uncompiled files, and the AndroidTest baseline | Supported | `jugg status` |
| List connected devices and mark selected devices | Supported | `jugg devices` |
| Read the current Activity stack | Supported | `jugg activity-stack` |
| Wait for a log marker, crash, or timeout | Supported | `jugg wait-logs` |

## Status query

```text
jugg status
jugg status --refresh-changes true
```

By default, `status` does not refresh changed files. Pass `--refresh-changes true` when Jugg should reread git-tracked changed files.

Key fields:

| Field | Purpose |
|---|---|
| `hasDevice` | Determines whether an available device exists |
| `needFallback` | Determines whether a full Gradle build is currently required |
| `pendingModifiedFiles` / `files` | Shows the summary of uncompiled files |
| `lastCompileTime` | Determines whether Jugg compilation already covered the current changes |
| `hasBeenFullCompiled` | Determines whether a complete Jugg baseline exists |
| `enabledAndroidTest` | Determines whether `instrument` is available |
| `isCompiling` | Determines whether a compile/deploy task is already running |

## Devices and Activity

```text
jugg devices
jugg activity-stack
```

Use `devices` to confirm devices visible to the IDE and which ones are selected. Use `activity-stack` to confirm that the target app is on the expected page, usually before UI inspection or input.

## Waiting for logs

```text
jugg wait-logs --marker '\[JUGG_AR\] DONE'
jugg wait-logs --marker '\[JUGG_AR\] DONE' --tags MyAutoRun,AndroidRuntime --timeout-ms 30000
```

`wait-logs` starts reading target app logs from the time recorded by the latest `deploy` or `restart` and stops at:

| `stopReason` | Meaning | Next step |
|---|---|---|
| `marker` | The expected marker was found | Read the returned log window to determine the verification result |
| `crash` | A crash was detected | Treat it as a failure and inspect the crash log |
| `timeout` | The marker was not found before timeout | The result is uncertain; combine UI evidence or complete logs |

## Related pages

- [Jugg CLI](./cli.md)
- [UI automation](./ui-automation.md)
- [Android Test](./cli-android-test.md)
