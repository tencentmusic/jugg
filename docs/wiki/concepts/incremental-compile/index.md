---
title: Incremental compilation
description: Explains how Jugg detects changed files from a Gradle baseline and generates local deployment artifacts for source code, resources, Manifest, DataBinding, dependencies, and other input types.
status: active
tags:
  - concept
  - compile
---

# Incremental compilation

Jugg incremental compilation is built on a trusted Gradle build. Gradle first generates the APK, classes, resource table, Manifest, DataBinding intermediate artifacts, and dependency information. Jugg then processes only the files changed in the current Run and outputs the local artifacts needed by deployment.

It does not take over Gradle tasks or generate a complete APK. If build scripts, dependencies, annotation processors, or other context cannot be trusted, Jugg returns to Gradle and refreshes the baseline for the next incremental compilation.

## Incremental compilation topics

| Page | Content |
|---|---|
| [Source incremental compilation](./source.md) | Java, Kotlin, DEX, and default method handling when desugaring is disabled. |
| [KMP source incremental compilation](./kmp-source.md) | How the Gradle compilation model supplies KMP `expect` / `actual` and intermediate source set source code. |
| [Recompilation](./recompile-propagation.md) | How class structures and the reference index drive additional source compilation after the first pass. |
| [Constant reference analysis](./const-ref.md) | How users of Java/Kotlin compile-time constants are recompiled after a constant changes. |
| [Resource incremental compilation](./resource.md) | aapt2 compile / link, Jugg's customized `inclink` in-memory cache, resource table loading, and resource overlays. |
| [Compose Multiplatform resources](./compose-multiplatform-resource.md) | Accessor generation, complete resource context, incremental deployment, and runtime restart boundaries. |
| [DataBinding / ViewBinding](./databinding-viewbinding.md) | Layout splitting, base classes, mapper, BR, and two-stage processing. |
| [Android Manifest compilation](./manifest.md) | Merged manifest baseline, incremental patching, and full merge boundaries. |
| [Release incremental compilation](./release-compile.md) | Experimental mapping remapping, inline handling, and removed-member compensation. |
| [Assets and native libraries](./assets-native.md) | Assets overlays and native libraries that must be written back to the APK. |
| [Dependency incremental compilation](./dependency-incremental.md) | Build file confirmation, dependency change comparison, differential compilation of changed libraries, and deployment handling. |
| [Custom compiler](./custom-compiler.md) | Custom compiler loading, extension insertion points, and hook semantics. |

## Main flow

Incremental compilation has two entry paths: a full Gradle build when the baseline must be refreshed, and changed-file processing during everyday development.

```text
first Run or baseline refresh required
  -> run a Gradle build
  -> collect APKs, classes, resource tables, Manifest, DataBinding intermediate artifacts, and dependency information
  -> initialize the project snapshot and indexes required by incremental compilation

later incremental Runs
  -> detect changed files from IDE and Git records
  -> route files to assets, resources, source, Manifest, and other compilation paths by type
  -> output DEX, resources.arsc, resource overlays, assets, Manifest, or files that must be written back to the APK
  -> analyze affected source files and classes requiring DEX conversion, then continue to another compilation round when needed
  -> pass staging artifacts to deployment
```

## Sources of file changes

Jugg uses three change sources that complement one another:

- IDE file events cover real-time edits while the project is open.
- A Git follow-up scan covers edits made while the project was closed, branch switches, rollbacks, and development across multiple repositories.
- Deployment history determines whether a change has already been deployed successfully.

After compilation succeeds, Jugg records a snapshot of file modification times and lengths. A late IDE file event whose snapshot did not change does not put an already compiled file back into the pending set, avoiding redundant compilation.

## Compilation context

Incremental compilation must reuse Gradle results, including module paths, source directories, Manifest paths, variant, module dependencies, library dependencies, Java/Kotlin compilation parameters, APK paths, and DataBinding intermediate artifacts.

Jugg combines this information into a project snapshot and maintains local indexes needed by incremental compilation and deployment under `build/jugg`. One key index comes from parsing the baseline APK / DEX. Later recompilation, default method handling, and deployment data generation read it to reconstruct references.

## Stage order

An incremental compilation proceeds through fixed stages, and an earlier stage can produce input for a later one:

```text
assets / native libraries
  -> resources (including Manifest and resources.arsc)
  -> source (annotation processing and DataBinding generated source -> Kotlin -> Java -> DEX -> minify)
```

The resource stage can generate `R.java` and DataBinding / ViewBinding source code. These files do not stop at the end of the resource stage; they are passed to the source stage for compilation. The source stage also has a fixed order: generated source must precede language compilation, Kotlin must precede Java, and minify must follow DEX. See [source incremental compilation](./source.md) for why.

## When Jugg returns to Gradle

The following cases usually require Gradle:

- The first Run, when no reusable APKs, classes, resource tables, or intermediate artifacts exist.
- The user forces Gradle compilation.
- Build scripts or dependency configuration changed outside a confirmed dependency incremental scenario.
- Device state, file count, module count, or deployment conditions are unsuitable for continued incremental work.
- Annotation processors, instrumentation, generated code, or other Gradle context cannot be confirmed.
- Incremental compilation failed and the retry strategy could not recover it.

After Gradle fallback succeeds, Jugg recollects build artifacts and refreshes the project snapshot and local indexes required by incremental compilation.

## Related pages

- [Compilation stages](../../guide/compile.md)
- [Compilation capabilities](../../capabilities/compile/)
- [Compilation orchestration](../compile-pipeline.md)
- [Deployment data and impact analysis](../deploy-data-and-impact.md)
- [Gradle fallback and baseline rebuild](../gradle-fallback-baseline.md)
- [Source compilation capability](../../capabilities/compile/source-compile.md)
- [Resource compilation capability](../../capabilities/compile/resource-compile.md)
- [DataBinding / ViewBinding capability](../../capabilities/compile/databinding-viewbinding.md)
