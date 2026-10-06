# Compilation System: DataBinding / ViewBinding

> Last verified: 2026-10-07
> Consistency rule: If documentation conflicts with code, code takes precedence.

## 1. Scope

This page covers the resource-to-source handoff for ViewBinding and DataBinding, the incremental setter-store and mapper state, and failure boundaries. General resource linking and language compilation are in `02_compile_resource.md` and `02_compile_source.md`.

## 2. Core Source Index

| Owner | Path | Responsibility |
|---|---|---|
| `DataBindingGenBaseClassesCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/databinding/DataBindingGenBaseClassesCompiler.kt` | Splits changed layouts, writes layout info and stripped XML, then emits ViewBinding classes or a DataBinding processor trigger |
| `DataBindingArgsManager` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/databinding/DataBindingArgsManager.kt` | Resolves the shared DataBinding workspace, Gradle baselines, generated-source paths, and feature detection |
| `SourceDataBindingProcessor` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/SourceDataBindingProcessor.kt` | Starts mapper processing from source compilation; coordinates Kotlin adapter preparation and the one-time Kotlin-class retry |
| `DataBindingGenMapperCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/databinding/DataBindingGenMapperCompiler.kt` | Runs the official processor, generates incremental mapper sources, merges BR, and publishes adapter stores |
| `DataBindingSetterStoreCache` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/databinding/DataBindingSetterStoreCache.kt` | Maintains a module-and-variant merged setter store tied to its Gradle baseline |
| `DataBindingClasspathHelper` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/databinding/DataBindingClasspathHelper.kt` | Selects processor dependencies and setter stores from the current module, direct project dependencies, and AARs |
| `LayoutIncludeAnalyzer` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/databinding/LayoutIncludeAnalyzer.kt` | Expands changed layout info to affected `<include>` referrers |
| `LegacyViewBindingLookup` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/databinding/LegacyViewBindingLookup.kt` | Adapts generated lookup calls when the installed APK lacks `ViewBindings` |

## 3. State and Stage Handoffs

| State or output | Producer and consumer | Contract |
|---|---|---|
| Layout info in `tempDataBindingLayoutXmlDir` | Resource stage → mapper stage | `DataBindingArgsManager` resolves both stages to the shared temporary module workspace. The mapper must not reset it after the resource stage. `<include>` expansion reads layout-info files, including prior Gradle/Jugg artifacts. |
| Generated Java/Kotlin and stripped XML | Resource stage → `JuggCompiler` → source stage or resource overlay | ViewBinding base classes and the DataBinding trigger become source inputs. DataBinding stripped XML becomes `ResXml` and must reach the overlay; Java success alone is incomplete. |
| Gradle layout-info backup | Resource stage → later full Gradle build | `reset()` backs up the Gradle directory; `copyToGradleDir()` adds current layout info to that backup so deleting a newly added layout does not break a later Gradle build. It is not an ordinary deployment output. |
| Setter-store baseline and generation | Gradle build / official processor → `DataBindingSetterStoreCache` → next Mapper APT | A generation is usable only while the Gradle module store has the same content hash. Publication follows successful generation; the next run prefers the valid merged store, then the Gradle store. |
| Mapper and BR baselines | Previous build and deployed mapper dex → generated Java | Mapper numbering counts same-package incremental mapper dex files. BR merges retain existing IDs and append new fields; missing prior Gradle BR files fail explicitly. |

The Gradle paths vary across AGP layouts. `DataBindingArgsManager` selects application/library layout-info candidates and resolves each mapper/BR file from KAPT output first, then Java APT output. A missing file retains the KAPT path for the resulting error; do not diagnose it as proof that KAPT ran.

## 4. Cross-Stage Flow

```text
ResourceCompiler.doModuleCompile() → processViewBinding() → DataBindingGenBaseClassesCompiler.doModuleCompile()
  → back up Gradle layout info; write current layout info, stripped XML, and generated source/trigger
  → JuggCompiler passes source/trigger into SourceCompiler and resource XML into overlays
SourceCompiler.prepareSourceCompile() → SourceDataBindingProcessor.processDataBindingMapper()
  → skip mapper when DataBinding is unavailable or no trigger/adapter declaration exists
  → Kotlin adapter declaration: isolated project KAPT produces a current-module store
     → compile adapter classes → publish merged store only after success
     → with no layouts or other adapter declarations, stop after cache publication
  → DataBindingGenMapperCompiler.doModuleCompile() consumes layout info and selected setter stores
     → runAnnotationProcessor(): official Java APT generates binding implementations and current-module store
     → Java adapter store is merged; a Java adapter-only run stops without mapper/BR output
     → layout run writes incremental mapper/holder and BR; generated Java enters language compilation
  → on Mapper APT failure with Kotlin source: compile Kotlin classes and retry Mapper once
```

Both binding modes enter the resource stage; only DataBinding enters Mapper/BR processing. `isUseDataBinding()` can infer enablement from existing Gradle KAPT output when the project model does not say so; for changed XML, the inference also requires a `<layout` file. A normal ViewBinding layout must not trigger Mapper processing. The generated `DataBindingInfo` file triggers the processor; it is not itself a mapper or BR implementation.

Mapper processing uses Java APT under the current `isFallbackApt = true` policy. Isolated KAPT runs only to produce the official store for changed Kotlin adapter declarations, with an empty layout-info directory so it does not process layouts ahead of the Mapper stage. Java adapter declarations are processed by Mapper APT. Adapter detection excludes comments and similarly named annotations, accepts supported `androidx.databinding`/`android.databinding` names and Kotlin aliases, and does not treat `Bindable` as an adapter declaration.

## 5. Constraints and Diagnostic Boundaries

- Mapper APT takes valid merged stores for the current module and direct project dependencies, falling back independently to each module's Gradle store. It also collects AAR `data-binding/*-setter_store.json` files. The inputs are copied into separate subdirectories because the official processor reads them recursively and same-named files would otherwise overwrite each other.
- The cache replaces declarations for types present in the current official store before merging that store. It cannot infer that a declaring type was removed or renamed, or that all declarations vanished. Adding or changing declarations on an existing type is supported; deletion or rename needs a full Gradle baseline.
- The annotation-processor classpath excludes unrelated processors. A Mapper failure is not evidence that another project's processor ran in this side path; inspect `DataBindingClasspathHelper` and the raw APT output first.
- The bundled base-class generator may emit `ViewBindings.findChildViewById`. Compatibility is determined from `ICompileContext.containsApkClass()` for `Landroidx/viewbinding/ViewBindings;`, not the AGP version: when absent, generated Java is rewritten to `rootView.findViewById`.
- The bundled DataBinding compiler is 7.4.2. Isolated adapter KAPT runs the project's `K2JVMCompiler` in a child JVM selected from the compile environment's `JAVA_HOME` (or the host JVM), with `jdk.compiler` exports/opens on Java 9+. When adapter KAPT fails on an older JBR, inspect that child process and its compiler classpath rather than only the IDE process.
- A Mapper error followed by the Kotlin-class retry does not mean KAPT was used for Mapper. Check `SourceDataBindingProcessor` and `runAnnotationProcessor apt output`; the second failure is final. The old KAPT-to-APT fallback flags remain in code, but the current Mapper policy always starts with APT.

| Observation | What it establishes | Next discriminating evidence |
|---|---|---|
| Mapper stage skipped despite layout info | The current module check may have rejected DataBinding; layout info alone does not prove project-model enablement. | Check `DataBindingArgsManager.isUseDataBinding()` and the serialized `useDataBinding` value. |
| Missing setter or wrong parameter type after an adapter change | Mapper did not obtain a usable declaration; it does not identify which store was missing. | Inspect selected current/dependency/AAR stores, the baseline hash, and whether the current-module store was published after successful adapter compilation. |
| Missing `DataBinderMapperImpl.java` at a KAPT path | That candidate baseline was not found; it does not prove the current processor used KAPT. | Check the matching Java APT `ap_generated_sources` file and the `runAnnotationProcessor apt output` log. |
| Missing BR field or changed ID | The generated and merged BR differ; a processor result alone does not prove which baseline was used. | Compare Gradle BR and incremental BR at `mergeLibraryBr()` / `mergeAppBr()`. |
| An `<include>` referrer was not regenerated | The changed layout's dependency expansion may be incomplete. | Inspect `LayoutIncludeAnalyzer` and the shared layout-info directory, including Gradle backup contents. |

## 6. Related Documents

- Resource compilation: `02_compile_resource.md`
- Source compilation: `02_compile_source.md`
- Project-model paths: `04_engineering_project.md`
- Verification policy: `06_testing.md`
