---
title: Build and deployment CLI
description: Explains how Jugg CLI triggers compilation, deployment, Gradle fallback, reinstallation, and restart.
status: active
tags:
  - capability
  - tools
  - cli
---

# Build and deployment

Build and deployment commands let an Agent or terminal user trigger Jugg compilation, deployment, Gradle fallback, reinstall after clearing data, and app restart without leaving the command line.

## Scenarios

| Scenario | Current support | Command |
|---|---|---|
| Verify only whether code compiles with Jugg | Supported | `jugg compile` |
| Compile and deploy to the current target device | Supported | `jugg deploy` |
| Force a Gradle build and continue to installation / startup | Supported | `jugg gradle-build` |
| Clear app data and reinstall the APK | Supported | `jugg clean-reinstall` |
| Restart the target app | Supported | `jugg restart` |

## Command boundaries

```text
jugg compile
jugg deploy [--always-restart-app <true|false>]
jugg gradle-build
jugg clean-reinstall
jugg restart
```

`compile` compiles without deploying. `deploy` compiles and deploys; `--always-restart-app=false` preserves runtime state for Hot Reload when conditions permit. `gradle-build` explicitly falls back to a complete Gradle build and continues into the installation / startup flow.

> [!NOTE]
> The CLI does not currently expose the MCP `waitAppReadyAfterSuccess` argument. Command completion means that the compilation/deployment task reached a terminal state, not that it waited additionally for the app to become ready.

## Determining success

Build-related commands block until a terminal state, so an Agent does not need to poll manually. Results distinguish compilation from deployment:

- **`isCompileSuccess=true`**: the compilation stage succeeded.
- **`isDeploySuccess=true`**: deployment or installation/startup succeeded.
- **`detail`**: a diagnostic summary on failure; long Gradle logs retain a head-and-tail preview.
- **`full log` / `logPath`**: the complete log location.

`gradle-build` can compile successfully while deployment fails, for example when the device is unavailable or startup fails. Do not check only `isCompileSuccess`.

## Fallback and retry

Recommended order:

```text
compile / deploy fails
  -> Read detail and the logs
  -> Fix the code and retry the original command
  -> Use gradle-build when repeated failures still cannot recover
  -> Request ssh-info when a remote build still fails and environment information is needed
```

Use `clean-reinstall` only when app data genuinely needs to be cleared. It is not the default remedy for a regular deployment failure.

## Related pages

- [Jugg CLI](./cli.md)
- [Remote diagnosis](./remote-diagnosis.md)
- [Gradle fallback](../compile/gradle-fallback.md)
- [Clean Reinstall](../deploy/clean-reinstall.md)
- [Restart](../deploy/restart.md)
