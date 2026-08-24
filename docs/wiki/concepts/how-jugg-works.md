---
title: How Jugg works
description: Understand the complete loop of a Jugg Run, from a trusted Gradle baseline through incremental compilation, artifact-specific deployment, and state commit.
status: active
tags:
  - concept
  - run
---

# How Jugg works

A standard Android Run passes through Gradle configuration, dependency resolution, source and resource compilation, APK packaging and signing, installation, and launch. This flow produces a complete and trusted application, but when only a small amount of code changes repeatedly, many of its steps are repeated on every Run.

Jugg uses a full Gradle build as the starting point for incremental work. Later Runs reuse verified project information and build artifacts, process only the current changes and their impact scope, and deploy local artifacts to the device. When project conditions, artifacts, or device state can no longer continue incrementally, the flow updates the APK, recovers device state, or reruns the Gradle build.

```text
trusted Gradle baseline
  ↓
check project changes, build target, and device state
  ├─ incremental work is suitable → compile changes and affected code → deploy by artifact type → commit state after success
  └─ full build is required → run Gradle → refresh the local baseline → install and realign the device
```

## A Gradle build provides the incremental starting point

When Jugg handles a Run for the first time or determines that the current state is no longer suitable for incremental work, it runs a full Gradle build. This build provides the baseline information required by later incremental Runs:

| Baseline information | Later use |
|---|---|
| Current modules, Variant, source directories, and compilation parameters | Determines which files belong to the current build and which compilation environment to use |
| Dependencies, classpath, and generated code | Gives local source compilation the same type information as the Gradle build |
| APKs, classes, resources, and other build artifacts | Provides the starting point for local artifact generation, APK updates, and device deployment |

Together, this information forms a trusted baseline. Jugg reuses Gradle's verified results and continues compilation and deployment around the current changes. See [project context](./project-model.md) for how the project model is synchronized.

## Every Run chooses incremental work or Gradle first

After Run is clicked, Jugg first checks whether the current operation can reuse the existing baseline, including:

- Whether the build target, modules, and Variant still match the baseline.
- Whether Gradle configuration, dependencies, or the compilation environment changed in a way that requires resynchronization.
- Whether current file changes can be handled by the incremental compiler.
- Whether local records and application state on the selected devices can continue from one another.

If the conditions are met, Jugg enters incremental compilation. If project information or complete artifacts must be refreshed, it enters a Gradle build. Some dependency changes can also produce incremental deployment artifacts directly; see [dependency incremental compilation](./incremental-compile/dependency-incremental.md).

This decision happens before compilation, so current project state determines whether a Run enters the incremental or Gradle path. See [compilation orchestration](./compile-pipeline.md) for the complete flow.

## Incremental compilation expands to affected code

After entering the incremental path, Jugg first processes current changes by file type, such as compiling Java, Kotlin, and resource files, while retaining assets, native libraries, and other files that do not require source compilation for the deployment stage. It then compares old and new class structures and uses references to add affected source files.

For example, when a method signature changes, recompiling only the defining file can leave classes that still call the old signature. Jugg adds those callers to the current compilation until the artifacts become consistent again. If the compiler encounters recoverable missing symbols or structural changes, it can also perform one targeted retry using the new impact scope.

This stage outputs local artifacts such as classes, resources, DEX, dependency files, or APK update data. They become trusted state for the next Run only after deployment completes. See [incremental compilation](./incremental-compile/) and [deployment data and impact analysis](./deploy-data-and-impact.md) for how each input type is processed.

## Deployment depends on both artifacts and the device

Jugg selects how changes take effect according to the current artifacts, device capabilities, and existing deployment state. Online replacement is only one available path.

| Current result | Common activation method |
|---|---|
| Code and resources that can be replaced online | Send local artifacts to the app process, then refresh the UI or restart the Activity as needed |
| Code structural changes that require the process to reload | Restart the app process or use a hot-fix path that can carry the structural change |
| Manifest, native libraries, and other content that must be written back to the install package | Update the corresponding entries in the existing APK, re-sign, and install it |
| Local records and device state do not match | Recover the deployment snapshot, reinstall, or return to the full Gradle flow |
| Complete Gradle build artifacts | Install the complete APK and use the new build result as the next baseline |

“What was compiled” and “how it takes effect on the device” are therefore consecutive but independent decisions. Deployment strategy also considers multi-APK ownership, multi-device state, and target Android version. See [deployment strategy](./deploy-strategy.md) and [compatibility deployment](./compat-deploy.md).

## State advances only after deployment succeeds

Continuous incremental work depends on three kinds of state remaining aligned:

- The Gradle baseline describes the complete project and build artifacts.
- Local incremental records describe which changes were compiled and prepared for deployment.
- Device state describes which artifacts the application actually received.

If compilation succeeds but deployment fails and the local state still records those artifacts as effective, the next Run starts its calculation from an incorrect point. Jugg therefore treats compilation results as data pending commit. Incremental history advances only after every selected device completes the current deployment. On failure, Jugg preserves the previous trusted record and uses later recovery, reinstallation, or a Gradle build to realign the states.

```text
compilation succeeds → deployment succeeds → commit current state → next incremental starting point
                     ↘ deployment fails → preserve previous state → recover / reinstall / Gradle
```

See [incremental deployment state recovery](./deploy-state-recover.md) for handling after device restart, app data removal, or deployment cache loss.

When deployment has already failed, see [deployment self-healing](./deploy-self-healing.md) for how Jugg chooses among retry, compatibility deployment, recovery, and reinstallation.

## Returning to Gradle starts a new incremental cycle

Gradle fallback rebuilds a trusted starting point. A full build refreshes the project snapshot, APKs, compilation artifacts, and generated files. After installation succeeds, the device is also realigned with this build result. The next Run can use the new state to decide whether to enter the incremental path.

Common triggers include build target changes, project configuration beyond current incremental capabilities, a missing critical baseline, and incremental compilation or deployment that cannot be recovered reliably. See [Gradle fallback and baseline rebuild](./gradle-fallback-baseline.md) for the specific conditions and user-visible results.

## Continue reading

- [Run an app](../guide/run.md): Complete a Jugg Run from Android Studio.
- [Jugg capabilities](../capabilities/): Check current support across compilation, deployment, testing, and tools.
- [Compilation orchestration](./compile-pipeline.md): Stages, retries, and result commit within a Run.
- [Incremental compilation](./incremental-compile/): Compilation and artifact generation for different file types.
- [Deployment strategy](./deploy-strategy.md): Deployment levels, device capabilities, and activation methods.
- [Deployment self-healing](./deploy-self-healing.md): How deployment artifacts continue through retry, strategy switching, and reinstallation.
- [In-app Jugg Runtime](./jugg-runtime.md): How local code and resources are loaded in the app process.
- [Gradle fallback and baseline rebuild](./gradle-fallback-baseline.md): When the complete build baseline must be refreshed.
