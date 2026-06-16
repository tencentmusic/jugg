---
title: Advanced Options
description: Explains switches and tool entries in the More Options menu of a Jugg Run Configuration.
status: active
tags:
  - guide
  - options
---

# Advanced Options

More Options in a Jugg Run Configuration groups run behavior, tool entries, and a few internal diagnostics actions. Change these options only when you intentionally want to change the run strategy; otherwise keep the defaults.

## Run Options

| Option | Effect |
|---|---|
| Confirm fallback when no file changes | Shows a confirmation before falling back to Gradle when Jugg detects no file changes. When disabled, Jugg falls back to Gradle directly. |
| Always restart app after deployment | Restarts the app after every deployment. This is useful after changing startup logic, singleton caches, static members, companion objects, or Kotlin top-level declarations. |
| Auto fallback to gradle when deploy error | Falls back to Gradle build and install automatically when deployment fails and the error can be recovered by fallback. |
| Embedded to APK(for Android RemoteViews) | Embeds incremental changes into the APK so APK-backed features such as Android RemoteViews can see the update. Deployment takes longer when enabled. |
| Force use compat deploy for `<device>` | Forces the compatibility deploy path for the selected connected device and makes the next run reinstall. Use it for device JVMTI or Apply Changes compatibility issues. |

## Tools

| Option | Effect |
|---|---|
| Install Jugg Skills | Installs the Jugg CLI, agent skill, and hooks. |
| Set custom server URL | Sets a custom Jugg backend URL for internal configuration, updates, or diagnostics services. |
| Check updates | Checks whether an update is available for the current plugin version. |
| Clean and reset Jugg | Deletes local Jugg caches and reopens the project. Use it when the local cache state is clearly broken. |

## Function Switches

| Option | Effect |
|---|---|
| Enable quick deploy(skip App startup) | Enables a faster deploy path that can skip waiting for app startup in some recover or deploy flows. It is enabled by default. |
| Enable use project Kotlin compiler | Uses the project's Kotlin compiler for incremental compilation. It is enabled by default; disable it only when investigating Kotlin compiler compatibility issues. |
| Enable backup classpath | Uses a backup classpath to improve compilation stability. Toggling it clears deploy history, and it may be hidden on unsupported platforms or environments. |

## Test Mock Events

These entries are mainly for internal diagnostics and are not recommended for daily runs.

| Option | Effect |
|---|---|
| Mark as project synced and re-init compiler | Simulates a successful Gradle Sync and reinitializes the compiler. |
| Mark as gradle compiled and re-init compiler | Simulates a completed Gradle build and reinitializes the compiler. This may make Jugg state inconsistent with real build artifacts. |

## Related Pages

- [Run App](./run.md)
- [Deploy Results](./deploy.md)
- [CLI](./cli.md)
