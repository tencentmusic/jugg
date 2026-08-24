---
title: Gradle fallback and baseline rebuild
description: Explains why Jugg runs Gradle when the build baseline is invalid, the incremental scope grows too large, or a failure cannot be recovered, and how the full build becomes the starting point for the next incremental Run.
status: active
tags:
  - concept
  - compile
  - fallback
---

# Gradle fallback and baseline rebuild

Jugg incremental compilation reuses APKs, classpath, resource tables, and generated code from the latest Gradle build. When the current project can no longer continue from those results, a full Gradle build recalculates the project model and generates a trusted starting point for the next incremental Run.

The user-visible result is that the current Run switches to the native build and a complete APK installation. This choice may happen before incremental compilation starts or as an automatic fallback after incremental compilation or deployment fails.

```text
start Run
  -> build baseline does not apply to the current target: Gradle
  -> current changes exceed incremental scope: Gradle
  -> incremental compilation cannot recover: current or next Run uses Gradle
  -> deployment recovery fails and fallback is allowed: rerun the entire Run with Gradle
  -> Gradle succeeds: refresh the local baseline and install complete APKs
```

## Before incremental work: validate the complete-build baseline

Before each incremental compilation, Jugg checks whether the current Run can continue to reuse the latest Gradle result.

| Trigger | What Gradle must regenerate |
|---|---|
| First Run or missing project information | A complete starting point including APKs, classpath, resource tables, and generated code |
| Build target switches between app and androidTest | Matching APKs and compilation context for the new target |
| Compilation command changes | Build results based on the new tasks and arguments |
| Changed source spans too many files or modules | A full build instead of overly expensive local compilation and impact analysis |
| The user explicitly selects Gradle | Refresh the baseline or compare the full build with incremental results |
| A later Run has no new changes and the user chooses to continue building | Rerun the complete build and installation flow |

These decisions happen before local artifacts are generated. After entering Gradle, the current Run does not also execute a separate incremental compilation.

## Build file changes: distinguish dependencies from the project model

After `build.gradle`, `settings.gradle`, or a version catalog changes, Jugg must determine whether the change affects dependency content or the entire project model.

A clear dependency addition, removal, or update can continue through [dependency incremental compilation](./incremental-compile/dependency-incremental.md), which processes only the changed libraries and their deployment impact. The following changes require Gradle to reread the project:

- Gradle plugin, Variant, or source set changes.
- Compiler or toolchain configuration changes for Kotlin, AGP, R8, Compose compiler, or similar tools.
- Manifest placeholder, resource generation, or custom task logic changes.
- A dependency difference that cannot be mapped to a clear library addition, removal, or update.

These changes affect tasks, compilation parameters, or artifact locations, so the old project snapshot cannot describe the new build result.

## Incremental compilation failure: use Gradle in the current or next Run

If the incremental compiler encounters a missing file or recoverable dependency information, it first updates the inputs and performs a limited retry. If the retry still cannot produce trusted artifacts, the result indicates whether Gradle fallback is allowed.

A failure eligible for immediate fallback runs Gradle in the current Run. Some failures preserve the evidence and end the current Run first, asking the next Run to use Gradle so that one invocation does not execute two compilation flows with different failure causes consecutively.

An explicit stop signal such as user cancellation does not trigger an automatic full build.

## Deployment recovery failure: the entire Run may rebuild

If incremental compilation succeeds but deployment fails, Jugg first retries, switches to Hot Fix or compatibility deployment, recovers, or reinstalls the current APK. The Run layer reruns Gradle only when the existing artifacts cannot complete deployment and the result permits automatic fallback.

This Gradle build is a path switch for the entire Run, not one deployment retry step. A multi-device Run also decides uniformly whether to switch and does not generate a different full build result for only one device.

See [deployment self-healing](./deploy-self-healing.md) for the deployment recovery flow.

## Gradle success refreshes the next incremental starting point

After the full build succeeds, Jugg rereads:

- APKs and their multi-APK, split, and androidTest ownership.
- Classes, classpath, mapping, and compilation parameters.
- Resource tables, Manifest, and resource content.
- Generated code and intermediate artifacts from annotation processors, DataBinding, and related tools.

These results form the new local build baseline. After complete APK installation succeeds, the device is aligned with it as well. The next small change still prefers incremental compilation.

## Releases and complex builds always use Gradle

The Jugg incremental path is intended for everyday development verification. Continue to use the complete Gradle flow for:

- Release APKs, AABs, production signing, minification, and publication artifacts.
- Gradle plugins, complex Variants, source sets, and custom tasks.
- Complete execution of compiler plugins, annotation processors, instrumentation, and nonstandard generated code.
- Building native libraries from C/C++ source and changing ABI, NDK, or packaging configuration.
- Complete Manifest merge, removal of old resource IDs, and rebuilding the complete resource table.

After switching branches, upgrading build tools, or needing to confirm that old artifacts were removed completely, you can run Gradle explicitly. See [Fall back to Gradle compilation](../guide/downgrade-gradle.md) for the operation.

## Related pages

- [Fall back to Gradle compilation](../guide/downgrade-gradle.md)
- [Gradle fallback capability](../capabilities/compile/gradle-fallback.md)
- [Incremental compilation](./incremental-compile/)
- [Compilation orchestration](./compile-pipeline.md)
- [Deployment self-healing](./deploy-self-healing.md)
