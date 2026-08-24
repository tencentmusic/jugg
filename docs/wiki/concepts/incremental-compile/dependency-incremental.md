---
title: Dependency incremental compilation
description: Explains how Jugg uses user confirmation, two baselines, and file differences to reduce the compilation scope when dependencies are updated repeatedly during independent library integration.
status: active
tags:
  - concept
  - compile
  - dependency
---

# Dependency incremental compilation

When developing an Android library independently, one integration cycle often publishes or replaces Maven or AAR dependencies repeatedly before returning to the application project for validation. Dependency declarations live in build files, so Jugg returns to Gradle by default when it detects such a change and refreshes the complete APK baseline. Even if only a few classes changed in the library, the Run enters a full build.

Dependency incremental compilation shortens this integration loop. After the user confirms that the build file contains only dependency-related changes, Jugg uses Gradle to read old and new dependencies, then sends classes, resources, Manifest, assets, and native libraries from changed libraries through existing incremental compilation paths. This page explains why user confirmation is required, how two dependency baselines handle consecutive upgrades and rollbacks, and which changes still require a full Gradle build.

## How Gradle turns dependencies into APK content

A full build first resolves the dependency graph for the current variant from repositories, version constraints, and transitive dependencies, then processes different artifacts from each library:

| Dependency content | Standard build handling |
|---|---|
| Classes in JAR or AAR | Added to the compilation classpath and converted to DEX by D8/R8 |
| `res/` in AAR | Compiled and linked with application and other dependency resources |
| AAR Manifest | Participates in the complete Manifest merge |
| Assets in AAR | Merged into `assets/**` in the APK |
| Native libraries in AAR | Enter `lib/<abi>/**` in the APK according to ABI |

A dependency version change can also alter transitive dependencies, version selection, and the compilation classpath. A build file can change tasks, source sets, variants, code generation, and compiler plugin configuration as well, so the default after detecting a build file change remains a full Gradle baseline rebuild.

## User confirmation restricts the change to dependencies

A dependency change is a controlled exception among build file modifications. Jugg uses two confirmations to distinguish “the build script changed” from “only dependencies require incremental processing”:

```text
detect a build file change
  -> display the build file diff
  -> user chooses to find changed dependencies, ignore the change, or fall back to Gradle
  -> when finding changed dependencies, resolve the current dependency graph through Gradle
  -> display detected dependency differences
  -> user confirms incremental compilation or Gradle fallback again
```

“Find changed dependencies” still starts a Gradle task to read the current dependency graph and changed library artifacts; it does not bypass Gradle entirely. The saved work begins after confirmation. Jugg skips a complete assemble and decomposes changed libraries into the existing source, resource, Manifest, assets, and native library incremental paths.

Choosing “ignore changes” means the user confirms that the current build file modifications do not affect the current development result. Jugg does not verify that the old and new scripts are equivalent. If classpath, generated code, or packaging results later become invalid, run a full Gradle build.

## Two dependency baselines handle consecutive upgrades and rollbacks

Dependency diff uses two comparison baselines:

| Comparison baseline | Purpose |
|---|---|
| Dependencies saved by the previous build | Shows dependency differences between adjacent builds |
| Latest full Gradle build | Determines which library files actually require compilation or replacement and which incremental DEX files must be removed from the device |

The second baseline handles consecutive incremental updates and version rollback. For example, suppose the full Gradle baseline uses `1.0`, an incremental deployment updates it to `1.1`, and the declaration is then changed back to `1.0`. Comparing only adjacent results shows an ordinary version change. Comparing with the full baseline confirms that additionally deployed `1.1` artifacts must be removed from the device instead of accumulating another copy of `1.0`.

## How changed libraries enter incremental compilation

After user confirmation, Jugg compares old and new library content and sends only different files to the corresponding stages:

```text
changed dependency library
  -> added or modified classes in JAR enter DEX compilation
  -> res changes enter resource compilation
  -> Manifest changes enter incremental merge
  -> assets changes generate asset overlays
  -> native library changes enter APK update
  -> artifacts enter deployment according to target APK ownership
```

JAR entries are filtered to added and modified classes according to content verification, while resource and assets directories also filter files whose content did not change. This difference reduces inputs processed by later DEX conversion, resource linking, and deployment, but does not promise a fixed duration. Dependency resolution time still depends on Gradle configuration, repository access, and project size.

Deployment must also clear incremental DEX that no longer belongs to the current dependency state:

| Scenario | Handling |
|---|---|
| Normal upgrade with changed files | Compile changed files and deploy incremental artifacts |
| New dependency library | Send new library files through current incremental compilation and deployment decisions |
| Version returns to the full Gradle baseline | Remove library DEX outside the baseline and resume using artifacts in the APK |

## When Jugg must return to Gradle

Dependency incremental compilation handles only changes that the user can confirm and that map to existing incremental stages. Rerun a full Gradle build when:

- The build file changes APK-affecting configuration in addition to dependency declarations.
- Gradle plugins, source sets, variants, annotation processors, or Kotlin compiler plugin configuration changed.
- A complete Gradle baseline is missing, or Gradle cannot produce a reliable dependency diff.
- Rolling back a dependency also requires restoring Manifest, resources, assets, or native libraries. The current rollback supports only removal of incrementally deployed library DEX.
- Symbol resolution fails after a dependency change and one compilation context refresh does not recover it.
- The user cannot confirm that the build file diff or dependency changes are expected.

## Related pages

- [Incremental compilation overview](./index.md)
- [Source incremental compilation](./source.md)
- [Resource incremental compilation](./resource.md)
- [Project context](../project-model.md)
- [Compilation stages](../../guide/compile.md)
- [Dependency incremental compilation capability](../../capabilities/compile/dependency-incremental.md)
- [Gradle fallback](../../capabilities/compile/gradle-fallback.md)
