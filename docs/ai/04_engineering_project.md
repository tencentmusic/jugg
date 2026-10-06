# Engineering: Project Model and Gradle Information

> Last verified: 2026-10-07
> Consistency rule: If documentation conflicts with code, code takes precedence.

## 1. Scope

This page explains the Gradle, IDE, and included-build snapshots that form the effective module model for compilation and deployment. It covers model ownership, serialization boundaries, dependency truth, and refresh order. Compilation stages, external-build execution, androidTest behavior, and IDE task orchestration have their own topic pages.

## 2. Core Source Index

| Owner | Location | Responsibility |
|---|---|---|
| `JuggProjectInfo` / `ModuleInfo` / `ModuleBuildPathInfo` | `main/src/main/java/com/sickworm/intellij/jugg/project/info/JuggProjectInfo.kt` | Root and module facts, including variant, dependencies, source roots, build paths, and nullable compatibility metadata |
| `GradleProjectInfoReaderManager` / `GradleProjectInfoReader` | `main/src/main/java/com/sickworm/intellij/jugg/gradle/script/` | Init-script entry and reflection-based Gradle task/model reads |
| `GradleVariantCollector` | `main/src/main/java/com/sickworm/intellij/jugg/gradle/script/GradleVariantCollector.kt` | Android Components variant fallback when legacy AGP variant APIs are unavailable |
| `ProjectInfoSerializerInGradle` / `ProjectInfoSerializer` | `main/src/main/java/com/sickworm/intellij/jugg/gradle/script/ProjectInfoSerializerInGradle.kt`, `main/src/main/java/com/sickworm/intellij/jugg/project/info/ProjectInfoSerializer.kt` | Groovy/JSON output and IDE/Gson read compatibility |
| `JuggProjectInfoMerger` / `ModulePathMergePolicy` | `main/src/main/java/com/sickworm/intellij/jugg/project/info/` | Reconciles IDE, primary Gradle, and included-build module identity and dependencies |
| `IProjectModelSource` / `GradleProjectModelSource` | `main/src/main/java/com/sickworm/intellij/jugg/project/info/ProjectModelSource.kt` | Gradle-only model for standalone/IDE-free runs |
| `IdeaProjectModelSource` | `idea/src/main/java/com/sickworm/intellij/jugg/compiler/context/IdeaProjectModelSource.kt` | IDE model plus Gradle snapshot source for IDEA |
| `CompileContextManager` / `BaseCompileContext` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/context/` | Applies effective model, custom classpath, and compatible compile-context recovery |
| `GradleProjectInfoLocalFetchManager` | `main/src/main/java/com/sickworm/intellij/jugg/project/dependency/GradleProjectInfoLocalFetchManager.kt` | Local Gradle read scheduling and missing-snapshot/remote-init completion |
| `GradleDependencyDiffer` | `main/src/main/java/com/sickworm/intellij/jugg/gradle/script/GradleDependencyDiffer.kt` | Full-baseline versus prior-build dependency changes |
| `JuggPathManager` | `main/src/main/java/com/sickworm/intellij/jugg/project/runtime/JuggPathManager.kt` | Project snapshot, classpath, and compile-context storage paths |

## 3. Snapshot State and Merge

| Artifact | Meaning |
|---|---|
| `build/jugg/database/project_infos.db/project_infos.json` | Raw IDEA model snapshot |
| `build/jugg/database/project_infos.db/gradle_project_infos.json` | Primary Gradle model produced by injected reader |
| `build/jugg/database/project_infos.db/include_build_*_gradle_project_infos.json` and `gradle_include_builds.txt` | Included-build snapshots and the current list of valid copies |
| `build/jugg/database/project_infos.db/is_dirty` | Request to refresh project info |
| `build/jugg/classpath/` | Full-build classpath/APK/library baseline and native strip tools |

The final merged `JuggProjectInfo` lives in Compile Context memory; it is not written back over an input JSON file. IDEA starts from its module/source structure and merges Gradle facts, while standalone/IDE-free mode merges primary and included Gradle snapshots without fabricating an IDE model. The model source then applies custom classpath in memory. Inspecting one JSON timestamp or one module field cannot prove the effective context used for a Run.

```text
IDE Sync or full Gradle build
  -> local fetch / compile client injects readProjectInfo.gradle.kts with -I
  -> GradleProjectInfoReaderManager.readAndSave() → GradleProjectInfoReader.getProjectInfo() reads selected tasks, variants, dependencies and outputs
  -> write primary snapshot and collect included-build snapshots
  -> IdeaProjectModelSource or GradleProjectModelSource selects input snapshots
  -> JuggProjectInfoMerger reconciles modules, dependencies and root AGP R8 path
  -> CompileContextManager.updateCompileContext() applies custom classpath
  -> IDEA: JuggManager.rebindCompileContext() reconnects file monitoring, compiler and deploy state
```

`jugg.projectDir` identifies the IDEA project root when it differs from Gradle's root; an included build still writes its own snapshot at its own root before the primary reader copies it. Only a composite root with included builds injects extra read tasks. If an included-build read fails, keep its prior valid copy at the same index; omit it from the list only when no prior copy exists. The root snapshot must remain usable. Included-build identity comes from snapshot origin, not a guess based on a module being outside the root directory.

The IDEA merger gives Gradle-confirmed fields priority where Gradle has the authoritative task/variant value, while retaining IDE source/module structure. It restores missing Gradle module edges whose targets remain in the final model; it never removes IDE edges and skips an edge that would create a cycle. A same-name Application from another Gradle build can replace an IDE module only when it belongs to the primary snapshot and has a real R.jar; otherwise ordinary field merge applies. For the project-level `agpR8Classpath`, this merger prefers the snapshot containing the final Application module so an included build's AGP R8 cannot silently win. The Gradle-only model keeps primary-snapshot order and takes the first available root R8 path.

Build target is part of model selection: `APP` filters Gradle-only synthetic androidTest modules, whereas `ANDROID_TEST` includes them. Synthetic-module identity and Test APK ownership are documented in `06_android_test.md`; a name ending in `.androidTest` alone is not a valid test-module identity. When IDEA or standalone takes project ownership after another Runtime, the next successful project-locked business chain reloads Gradle snapshots, Compile Context, history, and file state before using an old in-process model.

## 4. Gradle Facts That Change Compilation

**Variant and output paths.** Prefer legacy AGP variants when available; if they yield none, use names and effective minSdk captured by `androidComponents.onVariants` during configuration. Select the requested variant by the longest exact task suffix before Debug/Release fallback. `ModuleInfo.minSdkVersion` comes from the selected variant's merged flavor, with defaultConfig only as fallback. `variants[].minifyEnabled` is similarly read from actual AGP variant/build-type APIs; an unreadable value stays `null`, which compile context treats as not enabled. A stale mapping file cannot prove minification is currently enabled.

`ModuleBuildPathInfo.buildDirRelativePath` is the actual Gradle build directory relative to the IDEA project root. Gradle reads `project.layout.buildDirectory`, IDE reads the Android model, and Gradle wins during merge. An empty value from an old snapshot retains `${moduleRootDir}/build`. Classpath, Manifest, mapping, APK/test APK, and remote-sync paths derive from this build directory, which may be outside the module root. The file monitor reconstructs the *local* build directory from module/project roots instead of trusting a remote-backed `buildPathInfo.buildDir`; it also filters traditional `build/`. Keep generated source roots in project info when Kotlin needs them, while excluding build-generated file events from ordinary source changes.

`ModuleBuildPathInfo` chooses the newest existing Java output and the newest among Built-in Kotlin, KMP Android, and legacy Kotlin outputs, with defined tie/fallback order; remote sync must return each candidate location. Gradle R.jar has two separate candidate groups: aggregate APK R and module compile R. Pick the newest existing candidate within a group, never compare timestamps across groups or put both into ordinary `allClassPath`. `BaseCompileContext` chooses one R provider by module identity; synthetic androidTest uses its own aggregate R. See `02_compile_source.md` for the compilation order and included-build host-R priority.

**Dependencies.** Application and Dynamic Feature roots read the selected variant's resolved runtime project graph, after exclusions and substitutions, for APK ownership. An authoritative empty `runtimeModuleDependencies` is different from `null`: only `null` means an old or unreadable snapshot and uses legacy compile-dependency traversal. Runtime external libraries are gathered separately from the selected runtime classpath; other module types keep compile-dependency reads. Missing or failed runtime configuration resolution for an APK owner fails that Gradle read rather than publishing a false empty graph. Dependency diffs include runtime libraries only after the most recent full-build baseline recorded a nonempty runtime-library set; old/empty baselines keep compile-only comparison. The display diff compares previous-build dependencies, while `diffResultWithFull` compares the last full Gradle baseline to decide which library artifacts truly need recompilation or rollback.

Library R namespace metadata (`LibraryDependency.rPackageName`) comes best-effort from AGP `android-symbol-with-package-name`; the consumer falls back to AAR Manifest package when unavailable. It is metadata on the `res` dependency, not a new file/CRC change. External AAR R handling is in `02_compile_resource.md`. `isUseDataBinding` must survive Gradle JSON to IDE Gson read: Groovy writes JavaBean `useDataBinding` while the Kotlin field is `isUseDataBinding`. `ProjectInfoSerializer` aliases Boolean `is*` properties before deserialization; checking merger alone does not establish the field arrived.

**Kotlin and Compose.** Read typed Kotlin 2.x `compilerOptions` from the selected Android Kotlin task before legacy `kotlinOptions`; typed `optIn` markers become ordered `-opt-in=` free arguments. Keep Gradle-resolved subplugin options from one current compilation, falling back to a parent only if empty. Gradle-authoritative `kotlinCommonSourceDirs` and K2 fragment/refinement data also come from that selected task; flat IDEA `sourceDirs` must not replace them or infer common roots by name. Compose resource roots come from task `originalResourcesDir` and `fileSuffix`, including custom or not-yet-existing directories. A detected but unsupported task keeps status, reason, and readable roots so resource edits fail visibly rather than disappear. See `02_compile_source.md` and `02_compile_resource.md` for consumers.

**External builds.** `ExternalBuildInfo` stores current-variant Flutter/C++ task identity, recursive input roots with accepted file rules, configuration inputs, exclusions, native output, and Flutter assets output. Output is one `File` whose runtime type is archive or directory. Unsupported/incomplete task or output metadata remains an explicit unsupported record. `inputDirs` must be `{directory, filterRules}` objects with nonempty rules; an old string array is rejected at the JSON read boundary. Older serialized `outputDir`, `nativeLibsArchive`, or `nativeLibsDir` values are recovered deterministically to the current asset/native outputs. After an external task succeeds, `juggCollectExternalBuildInfo` produces a targeted module/variant/type update in the *same Gradle invocation*. Validate the invocation/result key and required outputs, merge it into the latest Gradle snapshot, then update Compile Context and file-monitor roots together; failure of collection, merge, or writeback fails that input instead of reporting success with stale monitoring. External execution and native strip behavior are in `02_compile_core.md`.

The full-build `build/jugg/classpath/native_strip/` cache stores APK-owner strip configuration and best-effort executable copies of per-ABI strip tools. A CI baseline copied to another worker must carry that whole directory with executable permissions; copying only `classpath/root`, APKs, and libraries leaves C++ incremental stripping dependent on the original machine's NDK path. See `02_compile_core.md` for cache-miss recovery.

## 5. Read and Recovery Boundaries

The Gradle reader attaches its normal snapshot write to the task graph's final task; a dry run reads immediately because no real task executes. Source under `gradle/script` is also embedded in generated `readProjectInfo.gradle.kts`; changing its inputs requires generated-script syntax verification under `06_testing.md` §7.4, not just a modern Gradle functional run. Older Gradle Kotlin DSL needs trailing-comma and inner-class adaptations. Diff mode writes dependency differences and removes temporary project info, while a normal read writes the formal snapshot.

After a full build, if Android Studio does not report a reliable Sync success, `JuggManager` refreshes IDE project info once but keeps library dependencies from the Gradle snapshot produced by that same build. The IDE read supplies module/source structure; its JSON timestamp must not replace the build's authoritative dependency graph. Normal Sync keeps its existing freshness judgment.

An existing Gradle JSON means only that the snapshot can be read. Incremental compilation also needs a full-build compile command and completion of any missing-snapshot rebuild. `GradleProjectInfoLocalFetchManager.isRebuildingMissingProjectInfo` is set for absent JSON; an intended incremental Run waits for that refresh and falls back to full Gradle if it fails. Ordinary background reads must not hold a global initialization gate. When remote compile command changes, run one local project-info dry run with that command in parallel with the remote build, then wait for it only during remote incremental initialization. Read fresh project info before fetching classpath; if classpath fetch fails after remote Gradle succeeds, retain the old compile context and history rather than declaring incremental initialization complete.

Dependency-change confirmation is a correctness boundary: a build script can alter behavior beyond its artifact list. `DependencyChangeManagerByGradle` / `DependencyChangeManagerBySync` apply a `CompileUiHandler` decision; a user choice to ignore applies only to this Run. Diff/read failure or refusal preserves Gradle fallback semantics. The full-build baseline determines real library changes, not just the most recent incremental result. See `02_compile_core.md` and `04_engineering_ide.md` for prompt order and UI flow.

Gradle clients prune fetched APKs absent from this run's found set only after required APK lookup and downloads succeed; a failed fetch retains the previous cache. The local client respects `isCleanupFetchedApks`, while the remote client performs cleanup after a successful fetch. Do not use an old file remaining under `build/jugg/classpath/apk/` by itself as evidence that it belonged to the latest successful build.

Project-info compatibility must be handled at the read/merge boundary. `ModuleInfo` additions need Gradle/Groovy serialization, IDE/Gson read, merger, compile-context and classpath-backup/CLI relocation paths checked. Root fields such as nullable `agpR8Classpath` are not copied by reconstructing a modules-only root object. The `compile_context.db/module_builds.json` writer uses version 2 and its reader accepts version 1 by restoring absent `buildDirRelativePath` as a traditional build path; missing `complete_flag` still requires a successful full build. A new incompatible `ExternalBuildInfo.inputDirs` shape fails explicitly rather than inventing roots from old fields. Do not raise a serialization version solely when deterministic old data can be read.

## 6. Diagnostic Boundaries

| Observation | What it establishes | Next evidence |
|---|---|---|
| A module field is absent from one JSON | That snapshot lacks it; it does not describe the merged model | Gradle/IDE JSON inputs, `ProjectInfoSerializer`, merger output, Compile Context log |
| Minified output persists after minify was disabled | A mapping file alone is not evidence of current variant minify state | Selected `variants[].minifyEnabled`, module field, `ICompileContext.isMinified` |
| An R class resolves but a new field does not | The first same-name provider may be an older R.jar | Actual classpath order, selected aggregate/module candidates and timestamps |
| Same-name app gets the wrong module or R.jar | Composite identity may be ambiguous | Primary-snapshot membership, module paths, real R.jar candidates, merger conflict log |
| APK/Manifest/Java output is absent with a custom build directory | Output inference or remote relocation may use the wrong root | `buildDirRelativePath`, actual Gradle build folder, classpath backup and APK lookup |
| DataBinding or Compose appears disabled despite Gradle enabling it | Deserialization or task-support metadata may have been lost before merge | Groovy JSON field, Boolean alias/support status, merger result, consumer input |
| Runtime-only library change is absent from diff | An old or empty full-build runtime baseline may intentionally use compile-only comparison | Full-build `runtimeLibraryDependencies`, current resolved runtime classpath, both diff baselines |
| External source edit does not enter incremental build | Snapshot roots, rules, or targeted refresh may be incomplete | Changed-file before-filter log, `externalBuildInfos` rules/exclusions, collector result key, refreshed monitor scope |

## 7. Related Documents

- Compilation and external task execution: `02_compile_core.md`, `02_compile_source.md`, `02_compile_resource.md`
- Deployment and androidTest: `03_deploy_complete.md`, `06_android_test.md`
- IDE lifecycle: `04_engineering_ide.md`
- Gradle init-script verification: `06_testing.md` §7.4
- Runtime investigation: `09_plugin_runtime_debug.md`
