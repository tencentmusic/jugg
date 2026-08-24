---
title: Project context
description: Explains how Jugg project information evolved from IDE-only reads to merged IDE and Gradle data, and how values are currently selected for the project snapshot.
status: active
tags:
  - concept
  - project
  - context
---

# Project context

The source file alone cannot determine which module it belongs to, which classpath it needs, which output directory it should write to, or which APK ultimately owns it. Jugg initially obtained this base information from the IDE project model. As incremental compilation expanded to more scenarios, it added Gradle reads for information the IDE did not provide. The current project snapshot merges both sources and determines how changes in the current Run are compiled and deployed.

## Project information required by local compilation

Jugg incremental compilation does not rerun the complete Gradle task graph, but it still needs the current module structure, variant, dependencies, compilation parameters, and artifact paths. Together, this information describes how Gradle would compile the project.

The project description must answer at least three questions:

- Which module owns a changed file, and which other modules are affected.
- Which source sets, classpath, compilation parameters, and output paths should be used.
- Which application or test APK owns a new artifact and where it should be deployed.

Without this information, even if a single file compiles successfully, the resulting local artifact cannot be shown to apply to the current project.

## Why project information expanded from IDE to Gradle

Jugg initially read only the IDE project model. It provided modules, source roots, resource directories, dependencies, the current variant, and other basic information. This supported early source and resource incremental compilation without requiring an extra Gradle plugin in the application project.

Later capabilities required information beyond the IDE model. Annotation processor arguments, complete dependency paths, Kotlin compilation parameters, Compose task information, and custom build directories are all closer to the actual Gradle build environment. IDE Sync and Gradle builds are also independent processes. Relying only on IDE data previously led to occasional missing dependency information and an inability to match Sync events one-to-one with dependency changes.

Jugg therefore uses a Gradle init script to read project information inside the build environment without modifying the application's build scripts. In terms of completeness and build semantics, Gradle is the more authoritative source. It can access the real tasks, variants, dependencies, and compilation parameters and is the main information source for extending future incremental capabilities.

## Why Gradle information does not directly replace IDE information

Gradle information is more complete, but Jugg already had a long-standing flow for reading and consuming IDE information when Gradle reads were introduced. Switching every shared field directly to Gradle would change module recognition, path selection, and dependency relationships at the same time, increasing compatibility risk.

The current merge uses a gradual strategy. For existing information available from both IDE and Gradle, Jugg prefers the proven IDE result. Information available only from Gradle is read directly from Gradle. Fields that require confirmation from the actual build environment have dedicated rules: dependency information considers the freshness of both snapshots, the build directory uses the actual path read from Gradle, and a module known to Gradle but not the IDE can be added when doing so does not introduce a dependency cycle.

This priority does not claim that the IDE is more accurate than Gradle. It controls behavior changes while Gradle information gradually takes over the project model. In theory, Gradle can become the sole authority for complete project information. The current preference for IDE data in shared fields is a stability tradeoff that preserves compatibility with existing projects and historical behavior.

## Two information sources form the project snapshot

After IDE Sync, a Gradle project information read, or a full Gradle build completes, Jugg merges project information again:

```text
read IDE project structure
  -> read project information from Gradle and included builds
  -> align module identifiers from both sides
  -> merge source roots, classpath, dependencies, variant, and artifact paths
  -> form the project snapshot
```

The IDE and Gradle can use different names for the same module. The merge must first align module identifiers and update module references in dependencies. Otherwise, one module would be treated as two, and its classpath and APK ownership would also become misaligned.

When a module appears only in Gradle information, Jugg adds the Gradle-confirmed module and dependencies as long as this does not introduce a dependency cycle. If reading an included build fails in the current Run but a previous valid copy exists, Jugg preserves that copy. An included build that has never been read successfully is skipped.

The merge still retains a compatibility path that creates a snapshot from IDE information alone, but current incremental compilation requires valid Gradle project information and a valid record of the latest full build. If those conditions are not met, Jugg first attempts a refresh and switches to a full Gradle build if the information remains unavailable.

## How the project snapshot participates in a Run

The project snapshot connects a file change to the final deployment result:

```text
detect a file change
  -> determine its owning module and affected dependencies
  -> select classpath, compilation parameters, and output paths
  -> generate local compilation artifacts
  -> select deployment targets according to APK ownership
```

After the snapshot is refreshed, later incremental tasks use the new file ownership, classpath, output paths, and module-to-APK mapping. Deployment history must also match the new snapshot so that artifacts from an old project structure are not applied to current device state.

Android Test adds test source, test APK, and app-under-test relationships to this model. Gradle reads the androidTest source set and confirms the corresponding test module only when the build target is `ANDROID_TEST`. See [Android Test flow](./android-test-flow.md) for the complete execution flow.

## When project changes require a snapshot refresh

After switching variant or build target, changing dependencies or build scripts, adjusting project structure, or changing the Gradle compilation command, the old snapshot can no longer demonstrate that current local artifacts are correct. Jugg refreshes project information and uses a full Gradle build to rebuild the baseline if it cannot recover a valid snapshot.

See [project information refresh and recovery](./project-info-refresh.md) for waiting during refresh, composite build recovery, AGP compatibility, and custom build directory handling.

## Related pages

- [Compilation orchestration](./compile-pipeline.md)
- [Incremental compilation](./incremental-compile/)
- [Android Test flow](./android-test-flow.md)
- [Gradle fallback and baseline rebuild](./gradle-fallback-baseline.md)
