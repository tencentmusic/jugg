---
title: Advanced options
description: Understand the less frequently used experience settings, tool entry points, and test diagnostics in More Options.
status: active
tags:
  - guide
  - options
---

# Advanced options

More Options contains less frequently used settings and tool entry points. This page covers only options that are uncommon in everyday workflows; common actions are documented on their scenario pages.

## Run Options

| Option | Purpose |
|---|---|
| Confirm fallback when no file changes | Ask for confirmation before falling back to Gradle when no file changes are detected but the run requires fallback; when disabled, fallback starts immediately. |
| Always restart app after deployment | Restart the app after every deployment so results remain predictable after changes to startup logic, singleton caches, static / companion members, or Kotlin top-level declarations. |
| Auto fallback to gradle when deploy error | Automatically use a Gradle build and installation when deployment fails with an error that permits fallback. |

## Tools

| Option | Purpose |
|---|---|
| Install Jugg Skills | Install the Jugg CLI, agent skill, and hooks. |
| Set custom server URL | Configure a custom Jugg backend URL for internal configuration, updates, or event reporting. |
| Check updates | Check whether an update is available for the current plugin version. |
| Clean and reset Jugg | Delete local Jugg caches and reopen the project; use when cache state is clearly abnormal. |

## Function Switches

| Option | Purpose |
|---|---|
| Enable quick deploy(skip App startup) | Enable the quick-deployment path so some recovery or deployment scenarios can skip waiting for app startup; enabled by default. |
| Enable use project Kotlin compiler | Use the project's own Kotlin compiler for incremental compilation; enabled by default and should be disabled only when diagnosing Kotlin compiler compatibility. |
| Enable backup classpath | Use a backup classpath to improve compilation stability; changing the setting clears deployment history, and the option is hidden on some platforms or environments. |

## Test Mock Events

These entry points are mainly for internal diagnostics and are not recommended for everyday runs.

| Option | Purpose |
|---|---|
| Mark as project synced and re-init compiler | Simulate completion of Gradle Sync and reinitialize the compiler. |
| Mark as gradle compiled and re-init compiler | Simulate completion of a Gradle build and reinitialize the compiler; this can make Jugg state inconsistent with actual build artifacts. |

## Related pages

- [Run an app](./run.md)
- [Restart the app](./restart-app.md)
- [Fall back to Gradle compilation](./downgrade-gradle.md)
- [CLI](./cli.md)
