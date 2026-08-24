---
title: Compilation capabilities
description: Summarizes Jugg compilation capabilities to show which changes can be handled incrementally and which scenarios fall back to Gradle.
status: active
tags:
  - capability
  - compile
---

# Compilation capabilities

Jugg compilation capabilities build on the latest available Gradle build result. They prioritize the files changed in the current run and produce deployable artifacts. When a change is better handled by Gradle, Jugg prompts for or performs a Gradle fallback.

## Capability overview

### Core compilation flow

| Capability | Current support | Typical result |
|---|---|---|
| [Source compilation](./source-compile.md) | Supports Java, Kotlin, and class inputs | Produces class, DEX, or Release re-obfuscation artifacts |
| [Recompilation](./recompile-propagation.md) | Supports continued compilation of affected sources | Finds callers, subclasses, constant consumers, and other affected sources, then adds another round |
| [Resource compilation](./resource-compile.md) | Supports the `res/`, `assets/`, `resources.arsc`, and `R.java` flows | Produces resource overlays or triggers source compilation |
| [AndroidManifest compilation](./manifest.md) | Supports incremental patches based on the merged manifest | Takes effect after writing to the APK and re-signing it |
| [Updating .so files](./so-update.md) | Supports updates to already generated `.so` files | Takes effect after writing to the target APK and re-signing it |

### Generated sources and language extensions

| Capability | Current support | Typical result |
|---|---|---|
| [DataBinding/ViewBinding](./databinding-viewbinding.md) | Supports two-stage handling after layout changes | Produces base/split artifacts in the resource stage and mapper/BR output in the source stage |
| [Kotlin Compose](./kotlin-compose.md) | Supports incremental compilation of common Compose Kotlin sources | Loads the project's Compose compiler plugin and produces class/DEX output |
| [KMP and Compose Multiplatform](./kmp-compose-multiplatform.md) | Supports KMP sources for Android targets and added or modified Compose Multiplatform resources | Completes KMP compilation inputs or generates accessors and deploys resources |
| [Annotation processors](./annotation-processors.md) | Supports explicitly listed annotation entry points | Generated sources continue into source compilation |
| [Custom compilers](./custom-compiler.md) | Supports inserting stages through an SPI | Extends Jugg's built-in compilation flow |

### Dependencies, Release, and fallback

| Capability | Current support | Typical result |
|---|---|---|
| [Incremental dependency compilation](./dependency-incremental.md) | Supports incremental handling of some dependency changes after a diff | New and old library artifacts enter the current compilation and deployment decisions |
| [Release compilation](./release-compile.md) | Experimentally supports mapping consistency, inline handling, and removed-member compensation | Artifacts enter deployment after re-obfuscation |
| [AabResGuard](./aab-resguard.md) | Supports reading `resources-mapping.txt` for incremental resource linking | Keeps obfuscated resource names consistent |
| [Gradle fallback](./gradle-fallback.md) | Supports automatic or user-triggered fallback | Re-establishes a trustworthy build baseline |

> [!IMPORTANT]
> Jugg does not replace the complete Gradle pipeline. Changes to Gradle scripts, dependencies, variants, source sets, complex plugin configuration, or large cross-module code sets may still require a Gradle build.

## How the compilation flow fits together

```text
Detect file changes
  -> Determine whether incremental handling is appropriate
  -> assets / native lib / AndroidManifest
  -> res / R.java / DataBinding/ViewBinding resource stage
  -> Annotation processors / KSP / KAPT / Compose and other source extensions
  -> Kotlin / Java / class
  -> DEX / Release minification
  -> Recompilation finds affected sources
  -> Hand artifacts to deployment
```

Users normally do not need to select stages manually. Jugg decides which stages to run based on changed file types, module ownership, APK ownership, and current deployment state.

## Related pages

- [Compilation stages](../../guide/compile.md)
- [Incremental compilation concepts](../../concepts/incremental-compile/)
- [Compilation failed](../../troubleshooting/compile-failed.md)
- [Changes did not take effect](../../troubleshooting/changes-not-applied.md)
- [Limits](../../reference/limits.md)
