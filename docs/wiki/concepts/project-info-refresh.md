---
title: Project information refresh and recovery
description: Explains how project information stays aligned with the full-build baseline, when refreshed information can be used by the current Run, and when a full Gradle build is required.
status: active
tags:
  - concept
  - project-info
  - gradle
  - agp
---

# Project information refresh and recovery

A successful full Gradle build leaves two kinds of state: project information such as modules, variant, dependencies, compilation parameters, and output paths; and complete build artifacts such as APKs, classpath, resource tables, and generated code. Jugg combines them as the starting point for later incremental compilation.

Project information maps source files to modules, compilation inputs, output paths, and target APKs. Jugg can read this information separately without regenerating the APK, but a refreshed result can be used for the current incremental compilation only while it still corresponds to the existing complete build artifacts.

On this page, project information becoming “invalid” does not mean that its file timestamp is old. It means that the information can no longer describe the current build configuration or no longer corresponds to the existing complete build artifacts.

## When project information becomes invalid

Project information and complete build artifacts jointly describe a specific build target. After switching the Build Variant, BuildTarget, or Gradle compilation command, both still belong to the previous target.

```text
build target changes from debug to release
  -> existing project information and complete build artifacts still belong to debug
  -> they cannot describe release compilation inputs or the target APK
  -> Jugg runs a full Gradle build
  -> new project information and build artifacts become the next incremental starting point
```

Changes to build scripts, dependencies, version catalogs, source sets, or build plugins can alter the same information. A clearly identified dependency change can enter the dependency incremental decision. If the change cannot be narrowed to a local dependency difference, Gradle must verify the complete build result again.

## How refresh updates incremental inputs

When project information must be updated, Jugg reads the real build environment with the current Gradle command and build target, then merges it with the module and source structure supplied by Android Studio. Gradle provides the actual variant, dependencies, compilation parameters, and build directory, while the IDE supplies current modules and source roots.

```text
project information needs an update
  -> read build information with the current Gradle command
  -> merge project structure from IDE and Gradle
  -> build files, compilation command, and BuildTarget are unchanged
     -> update classpath, output paths, and APK ownership
     -> continue incremental compilation in the current Run
  -> build files, compilation command, or BuildTarget changed
     -> new project information no longer corresponds to existing build artifacts
     -> switch the current Run to a full Gradle build
     -> rebuild the baseline from new project information and build artifacts
```

A build file change that is clearly identified as an added, removed, or changed dependency and confirmed by the user can enter dependency incremental compilation. That flow temporarily adjusts compilation inputs according to the dependency difference; it does not treat an ordinary project information refresh as a new complete-build baseline.

The read occurs in the current project's Gradle environment, so it follows actual changes to the AGP variant API, Kotlin compilation tasks, and custom build directories. Jugg uses matching read methods for known version differences. This step updates only project information; it does not generate APKs, resource tables, generated code, or other Gradle task artifacts.

See [project context](./project-model.md) for the fields in project information and how IDE and Gradle data are merged.

## How missing project information is contained

If the project information file is missing, corrupted, or cannot be deserialized, Jugg starts a background read. A task that still intends to run incremental compilation waits for this rebuild to finish and then checks incremental conditions again.

```text
project information is missing or invalid
  -> start reading Gradle project information
  -> incremental task waits for the current rebuild
  -> read succeeds and still matches the existing baseline: continue current incremental compilation
  -> read fails or cannot match the existing baseline: switch to a full Gradle build
```

A path already known to require a full Gradle build does not depend on this background read and can start the build directly. Project information recovery therefore blocks only the incremental path that needs it rather than turning a local maintenance task into a wait condition for every build flow.

When the remote compilation command changes, Jugg refreshes local project information with the new command while running the remote full build in parallel. After the remote build finishes, initialization for the next incremental Run waits only for the refresh explicitly associated with that build. Other background reads triggered by IDE Sync or dependency recovery do not add extra blocking.

## When refreshed information can be used directly

| Project state change | Jugg handling |
|---|---|
| Build files, compilation command, and BuildTarget are all unchanged | Refresh project information and continue reusing the existing complete-build baseline after success |
| The project information file is missing or invalid, but build configuration is unchanged | Rebuild project information; after success, it can be used by the current incremental Run |
| Android Studio Sync updates module structure while build inputs remain unchanged | Merge new IDE information and read missing Gradle information asynchronously |
| A build file changes | Run full Gradle by default; a clearly confirmed dependency change can enter dependency incremental compilation |
| The remote compilation command changes | Refresh local project information with the current command while running the remote full build |
| Build Variant, BuildTarget, or compilation command changes | Run a full Gradle build to establish the baseline for the new target |
| Plugin, task graph, source set, or toolchain changes | Let a full Gradle build verify inputs and artifacts again |
| Information for an included build is missing in the current Run | Preserve the last valid copy if one exists; otherwise skip it |

Composite build recovery follows the Best-effort principle. If an included build does not produce project information in the current Run, Jugg reuses only a previously successful copy and does not fabricate new modules or dependencies. A later successful read replaces the old copy with the new result.

## Related pages

- [Project context](./project-model.md)
- [Gradle fallback and baseline rebuild](./gradle-fallback-baseline.md)
- [Run Configuration and build variants](../guide/run-configuration.md)
- [Remote build failed](../troubleshooting/remote-build-failed.md)
