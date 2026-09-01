---
title: Glossary
description: Explains common compilation, deployment, project, and cache terms used in the Jugg Wiki.
status: active
tags:
  - reference
  - glossary
---

# Glossary

This page defines stable terms that appear throughout the Jugg Wiki and logs. Chinese terms are used in Chinese prose, while English terms correspond to code, logs, UI labels, or English documentation. It does not explain the underlying mechanisms in detail. Terms used by only one page should usually be explained where they appear.

## Compilation

| Term | Meaning |
|---|---|
| Incremental compilation | Processes only the source, resources, Manifest, DEX, or other artifacts affected by the current changes. |
| Gradle fallback | Switches to a full Gradle build when Jugg determines that the incremental path is unreliable or when the user explicitly requests it. |
| Full build baseline | The project information, classpath, APK, and deployment history baseline that Jugg establishes through a full build. |
| Build target | The current build target, such as a regular app or Android Test. |
| Source compilation | The compilation stage that processes Java and Kotlin source, annotation processing, and DEX output. |
| Resource compilation | The compilation stage that processes `res/`, `assets/`, the resource table, and the resource APK. |
| Manifest compilation | The stage that processes AndroidManifest merging, differences, and package declarations. |
| Const-ref | Compile-time constant definition and reference impact analysis, used to determine which source files must be recompiled after a constant changes. |
| Recompilation | Adds affected, unchanged source files to the current compilation based on references, inheritance, constants, or generated source. The recompilation set expands while the impact continues to propagate. |

## Deployment

| Term | Meaning |
|---|---|
| Deploy | Installs or updates compilation output on a device, then launches, restarts, or hot-updates the app according to the selected strategy. |
| Incremental deployment | Installs or updates compilation output on a device, then launches, restarts, or hot-updates the app according to the selected strategy. |
| Code Swap | Sends only code changes that can be replaced at runtime, avoiding an app restart when possible. |
| Full Swap | Sends a more complete set of changes than Code Swap and may require an app restart. |
| Hot Reload | The fast deployment path that does not restart the app and requires changes that the runtime can update in place. |
| Hot Fix | A more conservative deployment path that usually forces an app restart. |
| Apply Changes | The Android Studio mechanism for replacing code and updating resources at runtime. |
| Direct Overlay | A fast overlay deployment path that does not require the app process to be running. |
| Compatibility deployment | Deployment through compatibility mode when the device or system is not suitable for the default deployment path. |
| Deployment state recovery | Re-establishes trusted deployment state when device state, deployment history, or caches are inconsistent. |
| Deployment self-healing | Selects a bounded retry, broader recovery, or reinstallation after deployment fails with a known failure condition. |
| Deploy History | Jugg's history of deployed APKs, overlays, DEX files, and device state. |

## Project and cache

| Term | Meaning |
|---|---|
| Project information | A snapshot of modules, source sets, variants, dependencies, and APK information that Jugg reads from the IDE and Gradle. |
| Compile context | The module, classpath, dependency, and build target context used for incremental compilation. |
| Staging | The temporary deployment output directory for the current incremental compilation, located at `build/jugg/build/staging/`. |
| Classpath backup | Jugg's cache of classpath data, APKs, library backups, and embedded APKs. |
| Included build | An external build incorporated into the main project model through a Gradle composite build. |
