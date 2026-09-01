---
title: Jugg capability overview
description: Summarizes Jugg's current support across compilation, deployment, testing, and tool entry points to help you choose the right capability page.
status: active
tags:
  - capability
  - overview
---

# Jugg capability overview

Jugg capability pages answer whether a type of change or operation is supported, how it takes effect, and which prerequisites apply. If you are unsure which category covers your scenario, start with the relevant group on this page and then open the specific capability page.

## Capability groups

| Group | When to use it | Typical entry points |
|---|---|---|
| [Compilation capabilities](./compile/) | Determine how source code, resources, Manifest, DataBinding, `.so`, Release, constant references, and other changes are handled incrementally | Source compilation, resource compilation, Gradle fallback |
| [Deployment capabilities](./deploy/) | Determine whether the current artifacts will use install, Code Swap, Full Swap, Hot Reload, Restart, or deployment-state recovery first | Clean Reinstall, Direct Overlay, multiple APKs, multiple devices |
| [Testing capabilities](./test/) | Determine how Android Tests for app and library modules run, how results are displayed, and how logcat is attributed | Application Android Test, Library Android Test, Test Results UI |
| [Jugg CLI and Agent Skills](./tools/) | Determine how an Agent or terminal invokes Jugg compilation, deployment, testing, UI inspection, and remote diagnosis | CLI, MCP, UI automation, Agent Skills |

## Choose an entry point by task

| What you want to do | Read first |
|---|---|
| Determine whether changes to Java, Kotlin, or class files can be compiled incrementally | [Source compilation](./compile/source-compile.md) |
| Change resources, layouts, assets, or `R`-related content | [Resource compilation](./compile/resource-compile.md) |
| Change `AndroidManifest.xml` | [AndroidManifest compilation](./compile/manifest.md) |
| Update an already generated native `.so` | [Updating .so files](./compile/so-update.md) |
| Understand why callers, subclasses, or constant consumers must be recompiled | [Recompilation](./compile/recompile-propagation.md) |
| Determine whether the current deployment can avoid restarting the app | [Code Swap](./deploy/code-swap.md), [Hot Reload](./deploy/hot-reload.md) |
| Reinstall after clearing data or re-establish a baseline | [Clean Reinstall](./deploy/clean-reinstall.md) |
| Determine whether deployment automatically recovers or retries after failure | [Recover and Retry](./deploy/recover-and-retry.md) |
| Run Android instrumentation tests | [Testing capabilities](./test/), [Android Test CLI](./tools/cli-android-test.md) |
| Let an Agent compile, deploy, or verify through the command line | [Agent Skills](./tools/agent-skills.md), [Jugg CLI](./tools/cli.md) |
| Inspect the current app UI, locate elements, or perform taps | [UI automation](./tools/ui-automation.md), [UI layout evidence](./tools/layout-verify.md) |

## Core flow

```text
Code or resource changes
  -> Compilation capabilities determine whether incremental handling is available and fall back to Gradle when needed
  -> Deployment capabilities apply artifacts to target devices
  -> Testing capabilities run Android Tests or display results
  -> Tool capabilities let Agents, the CLI, and MCP drive and verify the entire process
```

These capabilities share the same Jugg project baseline. Whether compilation is trustworthy, deployment history is consistent, devices are available, and an Android Test baseline has been established all affect whether later capabilities can run directly.

## Prerequisites and boundaries

- Jugg's incremental capabilities depend on the latest trustworthy Gradle build baseline and do not replace the complete Gradle pipeline.
- Changes to Gradle scripts, dependencies, variants, source sets, or complex plugin configuration may require [Gradle fallback](./compile/gradle-fallback.md) first.
- Device-side capabilities require an available target device and consistent deployment history, APK ownership, and overlay checkpoints.
- Android Test requires enabling the Android Test target and completing one full build baseline for that target.
- Agent, CLI, and MCP capabilities are tool entry points into Jugg. The corresponding capability pages remain the source for compilation, deployment, and testing behavior.

## Continue reading

- [Guides](../guide/)
- [Concepts](../concepts/)
- [How Jugg works](../concepts/how-jugg-works.md)
- [Troubleshooting](../troubleshooting/)
- [Reference](../reference/)
