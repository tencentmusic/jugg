---
title: Incremental dependency compilation
description: Explains how Jugg detects, confirms, and incrementally handles Gradle dependency changes.
status: active
tags:
  - capability
  - compile
  - dependency
---

# Incremental dependency compilation

After build files or dependency declarations change, Jugg can read a Gradle dependency diff and, after two user confirmations, include changed library artifacts in incremental compilation and deployment decisions. This page covers scenarios that can be handled incrementally. For dependency baselines, library diffs, and fallback behavior, see [Incremental dependency compilation internals](../../concepts/incremental-compile/dependency-incremental.md).

## Dependency changes that can be handled incrementally

| Scenario | Current support | User-visible result |
|---|---|---|
| Build-file changes affect only dependency declarations | Supported for inspection | Jugg displays the build-file diff and asks whether to read dependency changes |
| A library dependency is added or updated | Supported through content diffing | Changed classes, resources, Manifest, assets, and native libraries enter the corresponding incremental stages |
| A dependency version returns to the full Gradle baseline | Bytecode rollback only | Removes the previously incrementally deployed library DEX and restores use of baseline bytecode from the APK |
| The user chooses not to handle dependencies incrementally | Fallback supported | The current run switches to a Gradle build and re-establishes the baseline |

> [!NOTE]
> Jugg runs Gradle to read the current dependency graph and library diff, but it does not run the complete assemble, DEX, resource-linking, and APK-packaging flow. Changes to plugins, source sets, variants, or classpath generation rules still require a Gradle build.

## Trigger and result

```text
Build file changes
  -> Display the build-file diff and ask whether to inspect dependency changes
  -> After confirmation, use Gradle to read the dependency diff
  -> Display library changes and ask again whether to handle them incrementally or fall back to Gradle
  -> Include eligible library changes in the current run
  -> Fall back to Gradle when incremental handling is unsuitable
```

If the dependency diff fails or the user chooses fallback, the current run switches to a Gradle build. If the user cancels confirmation, the current run stops and the unhandled build-file changes remain for a later run.

## Boundaries

- Choose incremental dependency compilation only when you can confirm that build-file changes affect dependency declarations alone.
- When build-file changes are ignored, Jugg does not verify that the old and new scripts are equivalent. Use a Gradle build if classpath, generated code, or packaging output later becomes inconsistent.
- Library DEX can be rolled back. If reverting a dependency version must also restore resources, Manifest, assets, or native libraries, rebuild with Gradle.
- Changes to Gradle plugins, source sets, variants, annotation processors, or Kotlin compiler plugin configuration require Gradle directly.
- If source resolution fails after a dependency change, Jugg attempts to update the compilation context and retries once. If it still fails, use a Gradle build.

## Related pages

- [Gradle fallback](./gradle-fallback.md)
- [Source compilation](./source-compile.md)
- [Compilation stages](../../guide/compile.md)
- [Incremental dependency compilation internals](../../concepts/incremental-compile/dependency-incremental.md)
