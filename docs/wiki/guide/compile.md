---
title: Compilation stages
description: Understand the compilation stages inside Jugg Run, incremental compilation and Gradle fallback, and common compilation results.
status: active
tags:
  - guide
  - compile
---

# Compilation stages

Jugg compilation runs when you start Jugg Run, Jugg Debug, an androidTest gutter action, or a run through CLI / MCP. Most everyday workflows do not require a separate compilation page to decide what to do. Start with [Run an app](./run.md). This page explains the compilation results, incremental decisions, and Gradle fallback shown during a run.

## When to read this page

Use this page when:

- Run output mentions incremental compilation, Gradle fallback, or a dependency diff.
- You want to determine whether a kind of change will use incremental compilation or Gradle.
- `compile` has failed and you need to locate the first compilation error.
- You explicitly call `jugg compile` through CLI / MCP.

> [!TIP]
> If you are unsure whether the current change is suitable for incremental compilation, run it directly. Jugg evaluates it first and falls back to Gradle when necessary.

## What Jugg tries first

By default, Jugg processes a run in this order:

```text
Check devices and file changes
  -> Determine whether incremental compilation is suitable
  -> Compile changed files
  -> Find affected source and recompile it
  -> Start deployment
```

If preliminary checks find higher risk, Jugg skips incremental compilation and starts a Gradle build. After the Gradle build completes, Jugg recollects baseline data such as the APK, classpath, and project information so later incremental runs remain stable.

## Common change types

| Change type | Default behavior | Details |
|---|---|---|
| Small Java / Kotlin change | Source compilation | Structural changes trigger recompilation of affected source |
| Layout / drawable / values change | Incremental resource compilation | Changes involving resource symbols or binding logic generate `R.java` or ViewBinding/DataBinding source |
| Simple `AndroidManifest.xml` change | Incremental Manifest processing | Takes effect by updating and resigning the APK |
| Asset change | Overlay deployment | Does not require a full Gradle build |
| Native library / `.so` artifact change | `.so` update | Writes the artifact into the APK and signs it again |
| Dart source change | Scoped Flutter build | Runs the Flutter task for the current variant every time, then passes assets/`.so` into existing incremental deployment |
| C/C++ source change managed by Gradle | Scoped native build | Runs the native task for the current variant, then writes generated `.so` files into the APK and re-signs it |
| Gradle script or dependency change | Fallback or dependency-diff decision | Depends on the dependency-change analysis and the user's choice |
| Only a library version changed | Optional incremental library compilation | Requires confirmation of the diff and avoids building unrelated modules |
| Large cross-module change | Gradle fallback | Jugg prioritizes stability |
| Release minification-related change | Conservative incremental handling | Compare with Gradle if a runtime exception occurs |
| Deleted class, resource, or Manifest node | Conservative handling | Returns to Gradle when deletion semantics require a complete baseline refresh |

## When Jugg falls back to Gradle

Common reasons include:

- You selected a forced Gradle build.
- No file changes suitable for incremental compilation were detected.
- Too many files or modules changed.
- Device state, installation state, or deployment history does not meet incremental requirements.
- The build target changed, such as switching between the app and androidTest.
- A build script, dependency, or build plugin configuration changed.
- A trusted baseline must be rebuilt after incremental compilation fails.

Fallback does not mean Jugg has stopped working. It means the current changes are better handled by Gradle.

## Incremental library compilation

When Jugg detects a build-file change, it may offer several choices:

| Choice | Meaning |
|---|---|
| Fallback to Gradle | Run a complete Gradle build immediately |
| Find out changed Libraries | Run a dependency diff, then compile only changed libraries after confirmation |
| Ignore build changes | Ignore the build-file change for this run and continue with incremental state |
| Close the dialog | Cancel this run |

Incremental library compilation is suitable when:

- Only a library was upgraded or downgraded.
- The build-file change does not affect the current APK artifact.
- You can confirm that the diff result is correct.

If you are unsure, Gradle is the safer choice.

## Interpret compilation results

Use run output or logs to determine the current state:

| Log / output | Meaning |
|---|---|
| `Compile files:` | Jugg is compiling detected changed files |
| `Detect effected sources` | Compilation succeeded and affected source was found, so Jugg starts recompilation |
| `Compile finished` | Compilation for this run has ended |
| `fallback` / `Fallback` | The run is entering or preparing to enter Gradle fallback |
| `Found incremental compile error` | Incremental compilation failed; inspect the specific error |
| `No file changes` | No processable file changes were found |

Log location:

```bash
build/jugg/log/compile_latest.log
```

## What to do when compilation fails

Follow these steps:

1. Read the first explicit error in the run output.
2. Open `build/jugg/log/compile_latest.log` and search for keywords such as `Found incremental compile error`, `aapt2`, or `unresolved reference`.
3. If the source or resource itself is invalid, fix the code first.
4. For build-script, dependency, resource-table, or release-minification issues, run one Gradle build to rebuild the baseline.
5. Before submitting an issue, back up `build/jugg/log/` and `build/jugg/database/`.

> [!WARNING]
> Do not delete the entire `build/` directory before preserving the diagnostic state. Doing so removes logs and database evidence needed for later investigation.

## Recommended workflow

- **Small application code changes**: Use Jugg Run directly.
- **Resource or layout changes**: Use Jugg Run and check whether source recompilation is triggered when necessary.
- **Gradle, dependency, plugin, or source-set changes**: Be prepared to accept a Gradle fallback.
- **After switching branches or pulling many changes**: Run one Gradle build first.
- **Changes to static / companion / Kotlin top-level declarations, startup initialization, or singleton caches**: Restart the app after compilation.
- **Unexpected results after deleting a class, resource, or Manifest node**: Compare with a Gradle build first.
- **Release issues**: Use a Gradle build to determine whether the difference comes from the incremental flow.

## Direct fallback and cancellation

When you explicitly want Gradle to complete the current build, use the fallback button or `jugg gradle-build`. Common reasons include:

- Part of the `build/` directory was deleted manually, leaving incremental dependencies incomplete.
- The incremental result appears incorrect and needs a complete Gradle comparison.
- You changed annotation processing, instrumentation, or build logic that requires the complete Gradle pipeline.

If Gradle fallback was triggered by mistake, cancel it. Cancellation stops the current Gradle build; the next run still tries incremental compilation first.

## Related pages

- [Run an app](./run.md)
- [Incremental compilation](../concepts/incremental-compile/)
- [How incremental library compilation works](../concepts/incremental-compile/dependency-incremental.md)
- [Compilation capabilities](../capabilities/compile/)
- [Source compilation](../capabilities/compile/source-compile.md)
- [Recompilation](../capabilities/compile/recompile-propagation.md)
- [Incremental library compilation](../capabilities/compile/dependency-incremental.md)
- [Resource compilation](../capabilities/compile/resource-compile.md)
- [AndroidManifest compilation](../capabilities/compile/manifest.md)
- [`.so` updates](../capabilities/compile/so-update.md)
- [Gradle fallback](../capabilities/compile/gradle-fallback.md)
- [Compilation failed](../troubleshooting/compile-failed.md)
- [Changes did not take effect](../troubleshooting/changes-not-applied.md)
- [Limitations](../reference/limits.md)
