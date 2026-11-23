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
| [Compilation capabilities](/capabilities/compile/) | Determine how source code, resources, Manifest, DataBinding, `.so`, Release, constant references, and other changes are handled incrementally | Source compilation, resource compilation, Gradle fallback |
| [Deployment capabilities](/capabilities/deploy/) | Determine whether the current artifacts will use install, Code Swap, Full Swap, Hot Reload, Restart, or deployment-state recovery first | Clean Reinstall, Direct Overlay, multiple APKs, multiple devices |
| [Testing capabilities](/capabilities/test/) | Determine how Android Tests for app and library modules run, how results are displayed, and how logcat is attributed | Application Android Test, Library Android Test, Test Results UI |
| [Jugg CLI and Agent Skills](/capabilities/tools/) | Determine how an Agent or terminal invokes Jugg compilation, deployment, testing, UI inspection, and remote diagnosis | CLI, MCP, UI automation, Agent Skills |

## Choose an entry point by task

| What you want to do | Read first |
|---|---|
| Determine whether changes to Java, Kotlin, or class files can be compiled incrementally | [Source compilation](/capabilities/compile/source-compile) |
| Change resources, layouts, assets, or `R`-related content | [Resource compilation](/capabilities/compile/resource-compile) |
| Change `AndroidManifest.xml` | [AndroidManifest compilation](/capabilities/compile/manifest) |
| Update an already generated native `.so` | [Updating .so files](/capabilities/compile/so-update) |
| Understand why callers, subclasses, or constant consumers must be recompiled | [Recompilation](/capabilities/compile/recompile-propagation) |
| Determine whether the current deployment can avoid restarting the app | [Code Swap](/capabilities/deploy/code-swap), [Hot Reload](/capabilities/deploy/hot-reload) |
| Reinstall after clearing data or re-establish a baseline | [Clean Reinstall](/capabilities/deploy/clean-reinstall) |
| Determine whether deployment automatically recovers or retries after failure | [Recover and Retry](/capabilities/deploy/recover-and-retry) |
| Run Android instrumentation tests | [Testing capabilities](/capabilities/test/), [Android Test CLI](/capabilities/tools/cli-android-test) |
| Let an Agent compile, deploy, or verify through the command line | [Agent Skills](/capabilities/tools/agent-skills), [Jugg CLI](/capabilities/tools/cli) |
| Inspect the current app UI, locate elements, or perform taps | [UI automation](/capabilities/tools/ui-automation), [UI layout evidence](/capabilities/tools/layout-verify) |

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
- Changes to Gradle scripts, dependencies, variants, source sets, or complex plugin configuration may require [Gradle fallback](/capabilities/compile/gradle-fallback) first.
- Device-side capabilities require an available target device and consistent deployment history, APK ownership, and overlay checkpoints.
- Android Test requires enabling the Android Test target and completing one full build baseline for that target.
- Agent, CLI, and MCP capabilities are tool entry points into Jugg. The corresponding capability pages remain the source for compilation, deployment, and testing behavior.

## Continue reading

- [Guides](/guide/)
- [Concepts](/concepts/)
- [How Jugg works](/concepts/how-jugg-works)
- [FAQ](/troubleshooting/supported-packaging)
- [Reference](/reference/)
