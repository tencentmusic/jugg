# Compilation System: DataBinding / ViewBinding

> Last verified: 2026-09-11
> Consistency rule: If documentation conflicts with code, code takes precedence.

---

## 1. Purpose of This Document

This page focuses on the two-stage processing of DataBinding/ViewBinding during Jugg incremental compilation:

- How the resource stage generates base classes, stripped XML, and DataBinding trigger sources.
- How the source stage retains the default Mapper APT path, conditionally runs isolated KAPT for Kotlin adapters, maintains the merged setter store, generates the mapper holder, and merges BR.
- Which Gradle intermediate outputs and Jugg temporary directories connect the stages.

For the main resource- and Java/Kotlin-compilation flows, see `02_compile_resource.md` and `02_compile_source.md`.

---

## 2. Core Source Index

| Class | File | Role |
|-------|------|------|
| `ResourceCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/overlay/ResourceCompiler.kt` | Resource-stage entry point that triggers `DataBindingGenBaseClassesCompiler` |
| `SourceCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/SourceCompiler.kt` | Source-stage entry point that triggers `SourceDataBindingProcessor` and mapper generation |
| `SourceDataBindingProcessor` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/SourceDataBindingProcessor.kt` | Coordinates DataBinding mapper generation and failure retry before source compilation |
| `DataBindingArgsManager` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/databinding/DataBindingArgsManager.kt` | Manages DataBinding/ViewBinding temporary directories, Gradle intermediate paths, trigger sources, and mapper/BR paths |
| `DataBindingGenBaseClassesCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/databinding/DataBindingGenBaseClassesCompiler.kt` | Resource stage: splits layout XML and generates ViewBinding base classes or a DataBinding trigger file |
| `LegacyViewBindingLookup` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/databinding/LegacyViewBindingLookup.kt` | Rewrites 7.4.2-generated `findChildViewById` to `findViewById` when the APK baseline lacks `ViewBindings` |
| `DataBindingGenMapperCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/databinding/DataBindingGenMapperCompiler.kt` | Source stage: uses APT for Mapper; on Kotlin adapter changes, first generates the current-module store through isolated KAPT, then commits the merged store cache after adapter classes compile successfully |
| `DataBindingSetterStoreCache` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/databinding/DataBindingSetterStoreCache.kt` | Merges the official processor's current-module store into the Gradle baseline / prior merged store and publishes atomically |
| `LayoutIncludeAnalyzer` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/databinding/LayoutIncludeAnalyzer.kt` | Finds layout info affected through `<include>` by a changed layout |
| `DataBindingClasspathHelper` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/databinding/DataBindingClasspathHelper.kt` | Prepares compiler classpath, plugins, and setter stores for the current module, direct project dependencies, and AARs for the DataBinding annotation processor |
| `DataBindingTemplates` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/databinding/DataBindingTemplates.kt` | Templates for mapper delegate, full mapper, and incremental holder |

---

## 3. Core Data Flow and Directory Model

| Path/state | Maintainer | Key meaning |
|------------|------------|-------------|
| `tempCompileDir/data_binding/<relative module>` | `DataBindingArgsManager` | Jugg's DataBinding workspace, isolated by module-root-relative path |
| `dataBindingSourcesOutputDir` | `DataBindingArgsManager` | Current-run output directory for base classes, APT-generated sources, mapper, and BR |
| `dataBindingStrippedXmlDir` | `DataBindingArgsManager` | Stripped XML after the DataBinding split, later entering the overlay as resource output |
| `tempDataBindingLayoutXmlDir` | `DataBindingArgsManager` | Current-run layout-info merge directory, produced in the resource stage and consumed in the source stage |
| `backupDataBindingLayoutXmlDir` | `DataBindingArgsManager.reset()` | Backs up Gradle layout info so deleting an added file does not break a later Gradle build |
| `incrementalDependencyClassesFolder` | `DataBindingArgsManager` | Stores incremental artifacts for later include and base-class generation |
| `dataBindingPreProcessorSources` | `DataBindingArgsManager` | DataBinding annotation-processor trigger-source directory |
| `dataBindingDependencyArtifacts` | `DataBindingArgsManager` | Setter-store inputs for Mapper APT, isolated into subdirectories by source to prevent same-name JSON overwrites |
| `kotlinAdapterKaptAarOutDir` / `kotlinAdapterKaptLayoutInfoDir` | `DataBindingArgsManager` | Isolated KAPT output for Kotlin adapters; layout info uses an empty directory to avoid parsing layouts before store merge |
| `setterStoreCacheDir` | `DataBindingArgsManager` | Stable cache directory isolated by module + variant, not reset with the current DataBinding workspace; holds baseline hash and merged-store generation |
| `mapperDir` | `DataBindingArgsManager` | Stores delegate mapper, full mapper, and historical incremental mapper sources |
| `isKaAptRetryAptSuccess` / `isLastFallbackAptFailed` | `DataBindingArgsManager.Companion` | Compatibility state for the KAPT-fallback APT branch; `SourceDataBindingProcessor` decides the normal APT path's Kotlin-class retry from whether the current task contains Kotlin source |

---

## 4. Two-Stage Processing Flow

### 4.1 Resource Stage: Base Classes / Trigger / Stripped XML

```text
ResourceCompiler
  -> DataBindingGenBaseClassesCompiler
     -> DataBindingArgsManager resolves Gradle/Jugg DataBinding directories
     -> produces tempDataBindingLayoutXmlDir, base classes or DataBinding trigger, and stripped XML
     -> JuggCompiler passes these outputs to SourceCompiler or overlays
```

For method order within a resource-stage file, read `DataBindingGenBaseClassesCompiler` directly. This page retains the cross-stage point: it backs Gradle layout info up into a Jugg temporary directory and produces the trigger/layout info required by the source stage.

### 4.2 Source Stage: Adapter Store / Mapper / BR / Language Compilation

```text
SourceCompiler.prepareSourceCompile()
  -> SourceDataBindingProcessor.processDataBindingMapper()
     -> this run contains a Kotlin adapter declaration
        -> DataBindingGenMapperCompiler.generateKotlinAdapterStore()
           -> KotlinCompilerInvoker runs project KAPT in a Gradle JVM child process
           -> use an empty layoutInfoDir to generate only the current-module setter store
        -> ordinary KotlinCompiler generates adapter classes first
        -> DataBindingSetterStoreCache.merge()
     -> DataBindingGenMapperCompiler.doModuleCompile()
        -> do not reset argsManager; continue consuming layout info from the resource stage
        -> runAnnotationProcessor()
           -> LayoutIncludeAnalyzer.findAllIncludePath(resource)
           -> DataBindingClasspathHelper prefers valid merged stores for current module and direct project dependencies, otherwise their respective Gradle baselines; collect all AAR setter stores
           -> copy stores into dataBindingDependencyArtifacts isolated by source; the official DataBinding processor loads and merges them recursively
           -> Mapper always uses JavaCompilerInvoker apt-only
           -> official ProcessMethodAdapters adds current declarations to the in-memory store and outputs current-module store first
           -> official ProcessExpressions then uses the same in-memory store to generate BindingImpl / Mapper
        -> when the current task has adapter declarations, merge current-module store into DataBindingSetterStoreCache
        -> adapter-only task returns after updating cache; a task with layouts also generates Mapper holder and BR
        -> produces DataBinderMapperImpl_Inc_N, DataBinderMapper_IncrementalHolder, BR, stripped XML
  -> DataBinding-generated Java sources enter Java compile inputs
  -> Kotlin -> Java -> Dex/Minify continue
```

---
## 5. Incremental Essentials

- Both DataBinding and ViewBinding enter through the resource stage, but only DataBinding enters the mapper/BR stage.
- If not explicitly enabled, `DataBindingArgsManager.isUseDataBinding(module, xmlFile)` infers DataBinding from Gradle kapt output directories and `<layout` in XML. Ordinary ViewBinding layouts must not trigger the DataBinding mapper.
- BR merging uses `LinkedHashMap` to keep declaration order stable; new fields are appended so BR IDs do not fluctuate.
- The mapper uses incremental `DataBinderMapperImpl_Inc_N` numbering; `N` comes from the count of same-package incremental mappers in deployed dex.
- The source stage must not call `argsManager.reset()` because mapper generation consumes `tempDataBindingLayoutXmlDir` just written by the resource stage.
- DataBinding Mapper always uses a Java APT trigger. Only when the current run has a Kotlin adapter declaration does it run project KAPT in a Gradle JVM child process before Mapper, producing the official current-module setter store.
- Scan source for adapter declarations only after confirming that DataBinding is enabled for the module; skip non-DataBinding modules outright. Detection covers `BindingAdapter`, `BindingMethod(s)`, `BindingConversion`, `InverseBindingAdapter`, `InverseBindingMethod(s)`, `InverseMethod`, and `Untaggable`. It accepts simple names, fully qualified `androidx.databinding` / `android.databinding` names, and Kotlin alias imports; comments, name prefixes/suffixes, and nested type names are not declarations.
- `Bindable` belongs to BR generation and does not use adapter setter-store detection or isolated KAPT. The Jugg-generated trigger file still drives `BindingBuildInfo`.
- Isolated KAPT starts the project's `K2JVMCompiler` CLI directly and adds module exports/opens for javac internal packages, avoiding module restrictions inherited from the Android Studio host JBR by older KAPT.
- If the DataBinding mapper fails and the current task contains Kotlin source, `SourceDataBindingProcessor` compiles Kotlin classes first and retries mapper generation once. A second failure is final. The normal success path still invokes the DataBinding processor only once.
- `DataBindingClasspathHelper` limits annotation processing to DataBinding-related dependencies so other processors such as ARouter do not enter this side path.
- For the current module and direct project dependencies, Mapper APT prefers each module's valid Jugg merged store. Without a valid cache, it uses that variant's module `*-setter_store.json` from the latest full Gradle build. It still gathers every `data-binding/*-setter_store.json` under AAR transform roots.
- Java adapter declarations continue to be processed by Mapper APT in the same run. Kotlin adapter declarations first produce current-module store through isolated KAPT; after adapter classes compile, Jugg merges the store for Mapper APT consumption. Jugg parses only the store container and declaring type, rather than deriving adapter method signatures itself.
- Copy setter stores from different origins to separate subdirectories because the official DataBinding processor recursively reads dependency artifacts; flattening them would let same-name stores overwrite one another.
- The current module supports adding adapter declarations, changing declarations on the same declaring type, and reuse across runs without a preemptive Gradle fallback. Deleting source, removing all declarations, and renaming the declaring class are outside B1 scope.
- A Mapper failure retains the existing `SourceCompiler` failure/retry policy; this feature does not broaden it.
- `copyToGradleDir()` is not ordinary output copying. It preserves stable layout info for later Gradle compilation so deleting a newly added file does not break a full Gradle build.

---

## 6. Hidden Constraints / Design Rationale

- `isFallbackApt` is currently always true, so `isJava` defaults toward APT. If code later removes that constant policy, reassess the KAPT path through `isUseKaptForDataBinding()`.
- `JuggCompiler` passes outputs of `DataBindingGenBaseClassesCompiler` to the next `SourceCompiler` input; they do not end immediately as final source outputs.
- DataBinding stripped XML returns as `CompileOutput.Type.ResXml` and moves into overlays in `JuggCompiler` / `SourceCompiler`. Java output alone cannot establish success.
- `mergeLibraryBr()` / `mergeAppBr()` require the BR file from the previous Gradle build. If it is absent, they throw rather than create an empty BR.
- Include relationships are resolved from layout-info files into `tempDataBindingLayoutXmlDir`, not by scanning current XML text and compiling every referrer directly.
- AGP 7.2.2 and 8.4 use different intermediate paths; `DataBindingArgsManager` matches candidate directories. For path issues, inspect it before changing compiler arguments.
- The ViewBinding/DataBinding base-class generator bundled in the plugin is fixed at 7.4.2 (Java 11 bytecode, loadable in Java 11 IDEs such as Electric Eel). It normally emits `ViewBindings.findChildViewById`. That class exists only from `viewbinding:7.0.0`. Detection does not depend on AGP version or Gradle declarations: it asks whether `ICompileContext.containsApkClass()` returns a `ClassNode` for `Landroidx/viewbinding/ViewBindings;`. If the list is empty, it rewrites generated source to `rootView.findViewById`, matching official AGP 4.2.2 output.
- Mapper/BR baselines are matched by file: prefer `generated/source/kapt/<variant>`; if the corresponding file is absent, look under Java APT `generated/ap_generated_sources/<variant>/out`. DataBinding projects without KAPT use the latter. If neither exists, still fall back to the kapt path and preserve the original error message.
- A DataBinding trigger file exists only to invoke the annotation processor; real mapper and BR outputs still come from the processor.
- Setter-store cache uses a content hash of the Gradle module store as baseline identity; old generations no longer match when the baseline changes.
- Declaring types in the current-module store are removed from the previous merged store before the official result from this run is added. Removing/renaming an old declaring type that cannot be inferred from the current store is outside B1 scope.

---

## 7. Investigation Entry Points

| Symptom | First entry point |
|---------|-------------------|
| DataBinding is enabled but the mapper stage does not run | `DataBindingArgsManager.isUseDataBinding()` and module `packageName`; if layout info exists but it still reports `data binding is not enabled`, first check how `ProjectInfoSerializer` reads Groovy `useDataBinding` back |
| ViewBinding class is not generated | `DataBindingGenBaseClassesCompiler.splitLayoutXml()` / `generateBaseClasses()` |
| Incremental compilation cannot find `androidx.viewbinding.ViewBindings` | `ICompileContext.containsApkClass()` and `LegacyViewBindingLookup`; check whether the APK database returns a `ClassNode` for the class and whether generated source was rewritten to `findViewById` |
| DataBinding mapper generation fails | `DataBindingGenMapperCompiler.runAnnotationProcessor()`, especially the `runAnnotationProcessor apt output` log; if `FileNotFoundException` points to kapt `DataBinderMapperImpl.java`, also inspect Java APT `ap_generated_sources` |
| Custom property has no setter or mismatched parameter type | First check whether `DataBindingClasspathHelper` gathers valid merged/Gradle stores from the current module and direct project dependencies; then check whether `DataBindingGenMapperCompiler` obtains current-module store from `dataBindingAarOutDir` and publishes the cache |
| Layout after an adapter-only run cannot find an attribute | Check whether `SourceDataBindingProcessor` invoked the processor for an adapter declaration and whether the setter-store cache baseline hash matches |
| Old attribute remains after deleting/renaming an adapter | B1 does not support deletion semantics; use a Gradle fallback to restore a complete baseline |
| BR field missing or ID unstable | Compare baseline and current incremental BR in `mergeLibraryBr()` / `mergeAppBr()` |
| A referrer is not updated after changing `<include>` | Inspect `LayoutIncludeAnalyzer.findAllIncludePath()` and `tempDataBindingLayoutXmlDir` contents |
| Later Gradle build fails due to missing layout-info file | Inspect `DataBindingArgsManager.backupDataBindingLayoutXmlDir` and `copyToGradleDir()` |
| Retry behaves unexpectedly after DataBinding processor failure | Inspect `isKaAptRetryAptSuccess` / `isLastFallbackAptFailed` and `SourceDataBindingProcessor` |
| AGP upgrade causes missing intermediate outputs | Inspect candidate paths in `DataBindingArgsManager.gradleDataBindingLayoutXmlDir` |

---

## 8. Related Documents

- Source compilation: `02_compile_source.md`
- Resource compilation: `02_compile_resource.md`
- Project-path model: `04_engineering_project.md`
- Setter-store design: `../task/databinding_setter_store_incremental_design.md`
