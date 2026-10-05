# Compilation System: Core Architecture

> Last verified: 2026-09-15
> Consistency rule: If documentation conflicts with code, code takes precedence.

---

## 1. Purpose of This Document

This page answers the control-plane questions of incremental compilation:

- The decision chain from an IDE compile request to incremental compilation or a Gradle fallback.
- How one incremental run connects asset/resource/source/dex/minify stages.
- Why another compilation round may follow success, and why a failed run may retry or fall back.

It does not detail individual subcompilers. See `02_compile_source.md` for Java/Kotlin/Dex, `02_compile_resource.md` for resources, and `02_compile_databinding.md` for DataBinding.

---

## 2. Core Source Index

| Entry class | File | Role |
|-------------|------|------|
| `JuggCompilerHelper` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/JuggCompilerHelper.kt` | Shared compile entry; waits for initialization/file processing, selects incremental or Gradle, and handles Git checks and fallback prompts |
| `IncrementalCompilerHelper` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/IncrementalCompilerHelper.kt` | Incremental-round loop; updates undeployed/staging state and drives effect-propagation recompilation and one-time failure retry |
| `JuggCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/JuggCompiler.kt` | Composes Flutter/C++ external builds and Compose resource, asset/resource/R.dex/source/dex/minify substages, ending quickly on stage failure |
| `ExternalBuildCompiler` / `ExternalBuildTaskRunner` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/external/` | Runs current-variant Flutter/native Gradle tasks for Dart/C/C++ changes and converts new assets/`.so` files into existing incremental outputs |
| `ComposeResourceCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/compose/ComposeResourceCompiler.kt` | Prepares CVR/assets for supported Compose Multiplatform resources, generates accessor Kotlin, and compiles generated expect/actual |
| `BaseCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/BaseCompiler.kt` | Template for all compilers: type checks, module/AndroidTest grouping, APK routing, and custom-compiler hooks |
| `CompileOrder` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/CompileOrder.kt` | Ordering ranges for custom-compiler insertion, not a direct representation of all built-in stage scheduling code |
| `CompileTask` / `CompileResult` / `CompileOutput` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/ICompiler.kt` | Compile inputs, per-file results, artifact ownership, and APK-routing model |
| `GitChangesCompileChecker` / `GitChangesRetryResolver` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/` | Asynchronous Git missing-file checks before/after compile and retry for unresolved-reference failures |

---

## 3. Core State and Data Model

| Object | Lifecycle | Key meaning |
|--------|-----------|-------------|
| `CompileTask` | New for each subcompiler stage | `parentTask` carries cancellation state, compiled-file notifications, and the current Gradle command; `outputDir` changes by stage among staging/classes/overlays/tmp directories |
| `CompileResult.details` | Merged upward after a substage returns | Records success/failure per input file; on failure, `quickFailedOthers()` marks unexecuted files as skipped failures |
| `CompileResult.outputs` | Set of substage outputs | Later written to `DeployFileManager.addStagingFiles()`; deployment consumes only valid staging outputs |
| `CompileOutput.apkPath` | Artifact ownership anchor | Retains legacy single-APK meaning; a real APK artifact includes at least itself |
| `CompileOutput.targetApkPaths` | Multi-APK routing | Set of APKs actually affected by the output; resources/manifest/assets receive APK-scoped outputs through `splitApkAndCompile()` |
| `CompileLoopStatus` | One incremental compile call | Marks first round/retry and records files already compiled this run to prevent infinite effect-propagation loops |
| `CompileStatusHolder` | Shared by UI/task | Cancellation signal and current compiling-file list; substages stop quickly through `task.isShouldCancel` |

---

## 4. Core Call Chains

### 4.1 From IDE Compile to Incremental/Gradle Decision

```text
JuggCompilerHelper.compile(options, uiHandler)
  -> record LastCompileTimestampRegistry as MCP/status/hook baseline
  -> wait for initialization and pending file processing so file events are queued before deciding
  -> preprocessIncrementalCompile()
     -> start asynchronous Git missing-file check; it does not decide this run's Gradle fallback
     -> evaluate in fixed priority:
        1. Force Gradle Compile
        2. external source changed while using remote compilation, a nonstandard Gradle command, or unresolved/deleted external inputs
        3. BuildTarget switch (APP <-> ANDROID_TEST)
        4. compile command differs from full-build baseline
        5. no full-build baseline (`not gradle compile yet`)
        6. wait for project-info reconstruction of an existing full-build baseline, then check project info availability
        7. INVALID_DEVICE
        8. require full compile directly if the previous Gradle compile failed
        9. roll back files whose content did not change
        10. confirm excessive changed-file count; only Continue proceeds to later checks
        11. check build-file/dependency changes and complete user confirmation
        12. return full compile when build-file confirmation requires rebuild
     -> Continue in the excessive-change prompt affects only this run; Gradle or any forcing condition yields a fallback result
     -> enter incrementalCompile() only if the result is null
  -> incremental success: return directly
  -> incremental failure without fallback: prompt that a direct next run will fall back; return failure now
  -> fallback needed: log `Fallback to gradle compile. Reason: ...` at info level, notify fallback, run gradleCompile()
```

`checkFallback()` is a side-effect-free precheck for MCP/status. It cannot read Run options or show a dialog, so its priority differs: `no full-build baseline -> project info unavailable -> INVALID_DEVICE -> other DeployState requiring full compile -> excessive changed-file count`. If both baseline and project info are missing on first run, it reports `not gradle compile yet` first. It does not report Force Gradle, BuildTarget/command switches, dependency-difference confirmation, or no-file-change confirmation. A status reason does not replace the actual Run decision.

MCP/CLI `compile` uses `isSkipDeploy` only to skip actual deployment, not `updateDeployState()`. Compile-only and Run share the same state decision: device selection safely handles multiple devices, while compilation still consumes the fallback result from build files, previous Gradle failure, baseline, and device state.

### 4.2 One Incremental Run and Effect Propagation

```text
IncrementalCompilerHelper.compile(undeployedFiles)
  -> convert ChangedFile to CompileFile; set current files in CompileStatusHolder
  -> asyncCheckBeforeCompile() warms up the wait for const-ref analysis
  -> JuggCompiler.compile(CompileTask(stagingDir))
  -> update uncompiled state in DeployFileManager on the first round
  -> write all outputs to staging
  -> after success, getRecompileFiles()
     -> restore effectedSourceFiles as ChangedFile through IFileChangesHandler
        -> filter paths under each module's actual build directory and traditional `${moduleRootDir}/build`
     -> convert redexClasses to class inputs under tempModule
     -> recursively enter another round if files remain
  -> on failure without a previous retry: let the retryResolver chain attempt repair, then retry once
```

### 4.3 Built-In Stage Order in `JuggCompiler`

```text
JuggCompiler.doCompile(task)
  -> ComposeResourceCompiler: prepare Compose resources, generate and compile accessor Kotlin first
     -> changed Compose assets go to AssetOverlayCompiler
     -> generated classes go to later SourceCompiler/DexCompiler
  -> ExternalBuildCompiler: run Flutter/native Gradle tasks for Dart/C/C++ changes
     -> convert Flutter assets and Flutter/C++ .so files to Asset/NativeLib
  -> AssetOverlayCompiler: put assets/native libraries (including Compose and external-build outputs) into overlays
  -> ResourceOverlayCompiler: compile resources/manifest into tmp_resource first
     -> move overlay resources to overlays
     -> pass R.java to SourceCompiler
     -> stage DataBinding/ViewBinding generated sources for the next source input
  -> RDexForSubmoduleCompiler: generate R.dex from R.class where needed
  -> SourceCompiler: Kotlin/Java/DataBinding mapper/JuggApt/class -> dex/minify
     -> DexCompiler first performs common pre-D8 class preparation; currently only Hilt Android entry transformation is connected
  -> on failure or cancellation in any stage: stop later stages and finish remaining inputs as failed/cancelled results
```

## 5. Stage Order and Extension Points

### 5.1 Built-In Stages

- `compose resource`
- `asset`
- `res`
- `source`
- `minify`
- `dex`

`JuggCompiler.doCompile()` explicitly orchestrates Compose resource/asset/resource/source. The Compose stage must finish first so generated assets can enter `AssetOverlayCompiler` and generated classes can enter the source/dex chain. The source stage then handles DataBinding mapper, JuggApt, Kotlin, Java, Dex, and Minify. `CompileOrder` primarily defines custom-compiler insertion points.

Pre-D8 class preparation is internal to `DexCompiler`: it is not a new `BaseCompiler` sibling and consumes no `CompileOrder` extension point. After determining actual D8 inputs, `DexCompiler` reads and analyzes program classes once, passing the same result via explicit `ClassPreparation` to `TransformerCompiler`, `getDesugarInfo`, and D8 in sequence. The Transformer only replaces matching Hilt Android entry-point classes; it no longer parses program classes. Do not create a Transformer SPI or registry before a second real transformation requirement exists.

### 5.2 Custom-Compiler Insertion Points

`CompileOrder` offers these ranges: `atFirst`, `beforeAsset/afterAsset`, `beforeRes/afterRes`, `beforeSource/afterSource`, `beforeMinify/afterMinify`, `beforeDex/afterDex`, and `atLast`.

Each concrete `BaseCompiler` implementation runs its own before/after ranges. `JuggCompiler` itself uses `atFirst` and `atLast`; subcompilers such as `ResourceOverlayCompiler`, `JavaCompiler`, `KotlinCompiler`, and `DexCompiler` expose the corresponding stage insertion points.

---
## 6. Hidden Constraints / Design Rationale

- `DeployFileManager.updateUncompiledFiles()` removes first-round successful files from the pending-compilation set. Later effect-propagation rounds do not update that set, avoiding confusion between derived recompilation and the user's original changes.
- When a file becomes pending, Jugg records a `lastModified + length` snapshot. A late IDE/Git file event with the same snapshot is ignored and preserves its compile count; only an actual content change makes it pending again. Successful compilation refreshes the snapshot so duplicate events do not reopen compiled-but-undeployed files.
- Git missing-file checks have two layers. On failure, a resolver may refresh Git to discover a missed new file and retry once. After success, `GitChangesCompileChecker` starts another round only if new pending files appear.
- The Git check reads only Git changes through `IDeployHistoryManager.getChangedFilesSinceLastFullCompiled()`; it does not load or validate the APK, module build path, or deployed data. A runtime query failure skips only this check and must not delete deploy history or compile context. Only project-initialization recovery through `tryGetContextRecoverInfoFromDb(isOnInit = true)` may invalidate unrecoverable old history.
- Effect propagation excludes files compiled in the previous round, except for Kotlin top-level file-facade cases. `getRecompileFiles()` reads the file-facade list from `.kotlin_module`; if a caller source's `effectedByClasses` matches a facade, `topLevelFacadeEffectedSourcePaths` allows it to be compiled one more time.
- `BaseCompiler` is the template layer for all subcompilers. It handles type validation, module/androidTest batching, APK routing, and custom-compiler hooks. Read each subcompiler's implementation for its internal order.
- `splitModuleAndCompile()` batches androidTest modules separately, using a grouping key that includes module root to avoid merging test modules with the same name.
- `splitApkAndCompile()` routes APK-scoped outputs. Subclasses must retain current APK ownership in `doApkCompile()` output or multi-APK deployment loses its target.
- Module `R.dex` generated by `RDexForSubmoduleCompiler` must carry `apkPath` / `targetApkPaths` from `ModuleApkBelongs`. In a Dynamic Feature case, omitting ownership turns the output into generic class dex distributed to every APK, so R classes sharing a package name but not resource sets overwrite one another.
- DataBinding/ViewBinding sources from the resource stage in `JuggCompiler` do not end as final artifacts immediately; they become input to the following `SourceCompiler` stage.
- Dart/C/C++ sources, including Flutter assets, local-path packages inside or outside the project, and `.cmake`/assembly inputs confirmed by task metadata, become `ExternalBuildSource` when they match a module's `externalBuildInfos`. `FileChangesHandler` still creates only one `ChangedFile` per physical file; its `module` is just an anchor for compatibility with the existing model. Precheck and compilation must call `resolveExternalBuilds()` across all current modules to resolve every matching target, rather than treating the anchor as the sole execution basis. Each `inputDirs` entry is a “directory + accepted `filterRules` for that directory.” Rules are ORed: `Dart` matches `.dart`; `FlutterAsset` matches any ordinary file; `CppSource`/`CppHeader` match explicit suffixes; `NativeDirectory` matches any non-hidden ordinary file but rejects symlink files and paths through symlink directories. `configFiles` still match exactly; `excludedDirs` and toolchain cache directories (`.dart_tool`/`.cxx`/`.externalNativeBuild`) take highest precedence. After collection, normalize and remove only exact duplicates with the same path and rule set. Do not collapse subdirectories under an ancestor or combine different rule sets on the same directory; a broad root must not swallow stricter subdirectory semantics. Sources of `inputDirs` are native configuration roots (`CppSource + CppHeader`), include roots and parent directories of explicit Header sources in metadata (`CppHeader`), parent directories of metadata-confirmed non-Header sources (`NativeDirectory`, without widening when the parent equals a configuration root), Flutter package roots (`Dart`), and configured asset directories or task-confirmed subdirectory inputs (`FlutterAsset`). This model tolerates a few extra triggers; Gradle's own up-to-date check decides actual work. `pubspec.yaml` is used only to extract directory declarations from `flutter.assets` and `arb-dir` from `l10n.yaml`. Individual asset/font/shader files still depend on task inputs; Jugg neither reads `package_config.json` nor scans the disk as a fallback.
- Every external change runs the corresponding Gradle task. If one physical source matches several Native modules, tasks are deduplicated by `moduleRootDir + variant + taskPath + type`, run together in one Gradle invocation, and outputs are collected per module afterward. If any target lacks metadata, a task, or an output contract, or task execution/output collection fails, the source as a whole fails or falls back. Partial success must not remove it from pending files. Artifact CRC skips repeated deployment only, never Flutter/C++ compilation. The derived command includes the Jugg init script, explicitly appends the always-running `juggCollectExternalBuildInfo`, and uses invocation arguments to reread external metadata only for this run's module/variant/type. The collector atomically writes a temporary result directory without triggering a full project-info local fetch. After task success, the IDE merges the targeted patch into the latest Gradle snapshot, reruns the existing project-info merge, then updates active modules through `ICompileContext`. `FileChangesHandler` listens for context updates and atomically replaces the scan scope, so a new input directory takes effect immediately after this run. Missing, incomplete, or unpersistable collector results fail the run; continuing with the old monitoring scope would fabricate success.
- Jugg records only one native output location and dispatches collection by file/directory. Flutter's native task is `packJniLibsflutterBuild<Variant>` / `packLibsflutterBuild<Variant>` (archive) or `copyJniLibsflutterBuild<Variant>` (directory), depended on by `compileFlutterBuild<Variant>`. For an archive, read only `lib/<abi>/*.so`; for a directory, read only `<abi>/*.so` from the actual `destinationDir`. Neither recursively scans Flutter intermediate directories. `flutter_assets` enter the asset overlay separately from the assets output directory. For C++, collect only stripped outputs from this invocation: the collector obtains the `strip<Variant>DebugSymbols` configuration of the APK owner (base app or dynamic feature), reproduces AGP's per-file strip semantics within Gradle, and writes into the invocation directory. `ExternalBuildCompiler` never falls back to unstripped output from `merge<Variant>NativeLibs`. The preferred source of this configuration is the local `build/jugg/classpath/native_strip` cache published by an ordinary full Gradle build. Under Gradle Configuration on Demand, a derived C++ invocation requests the selected library native task and collector, but does not request an APK-owner task merely to read strip configuration. The owner may therefore be unconfigured and its strip task unreadable; the cache must be used, and strip must not be skipped. Cache entries match uniquely by normalized `moduleRootDir + variant`; the tool preferentially uses a backup executable copied with the baseline. Missing cache, damaged JSON, invalid fields, duplicate entries, or an invalid recorded tool path permits only one fallback to a live read. If the owner is unconfigured or the live read still fails, preserve the final exception and ask for a full Gradle build to refresh the cache. A project snapshot retains the unsupported state when an external input is detected but a task, assets output, or native output is missing; a matching Run precheck falls back to full Gradle. A failed external task, missing/unreadable output path, absent stripped output in a C++ invocation, unreadable strip configuration, or a damaged native archive or one with unsafe/duplicate entries explicitly fails this run. `ExternalBuildCompiler` records the concrete cause in both `CompileError` and a `warn` log, without relying on a common exit point to emit the warning. If a task succeeds and its contracted output path is accessible, the run succeeds even when it produces no assets/`.so` files or the artifact set shrinks; it emits no corresponding deployment artifact. Old content in the installed APK or overlay remains until the next full Gradle build.
- `AssetOverlayCompiler` output retains the source module in `CompileOutput.relativeModule`. Flutter external-build `flutter_assets` and Compose assets both enter staging through it. Deployment uses that ownership to distinguish Flutter JIT runtime assets actually compiled this run from same-name old files in the APK baseline. Only this compiler performs the `CompileFile` -> `CompileOutput` conversion; other subcompilers are unaffected.
- `FileChangesHandler` excludes both every module's actual Gradle build directory and traditional `${moduleRootDir}/build`. File monitoring, Git missing-file checks, recovery events, and source-effect propagation all pass through this boundary, so Gradle-generated source, resources, assets, manifests, native libraries, and build files under these paths do not enter the change list. Directory events are pruned before recursion. This does not affect JuggApt/Resource/Compose generated sources registered and handed off directly by compilers within the current run.
- External source roots may lie outside Android module directories, so they are added to the scan roots. `.dart_tool`, `.cxx`, `.externalNativeBuild`, module build directories, and metadata-declared `excludedDirs` (such as Flutter SDK and pub cache roots) always remain excluded, preventing generated files or dependency caches from being recognized as source changes. Directory rules handle only ordinary files that currently exist. Deletion of a recognized Dart/C/C++ source, Flutter asset, or configuration input is ignored directly, without recovering the input type from history or adapting deletion. Removing old Native code or Flutter assets from a device requires a full Run; a new path moved into the monitored scope is recognized as an ordinary add event.
- A deletion event removes a previously registered pending item only by path. A nonexistent file does not become a `ChangedFile` or produce class, resource, asset, or Manifest removal data. Deletion alone therefore neither fails incremental compilation nor automatically falls back; the device retains old content from the installed APK and overlays. A rename is split into old-path deletion and new-path add/modify, and only the new path can compile. Use a full Gradle build to refresh the APK baseline only when old content must truly disappear.
- Compose resource support is recognized from generator API structure exposed by project Gradle tasks, not an exact Compose/Kotlin version allowlist. The project snapshot retains “detected but unsupported” state, configured resource roots, and a user-visible reason. Resource changes still enter compilation and fail, followed by the existing next-run Gradle fallback behavior; `composeResourceInfo=null` must not silently filter them.
- Deleting a Compose resource likewise creates no compilation input. There is currently no deletion graph, generated-source/cache reuse, or complete source-set dependency graph; old generated classes and deployed resources remain until a full Gradle build refreshes the baseline.
- If cancellation interrupts recursive effect propagation, the first round rolls back changed files and clears staging so the next run can recompile them.

---
## 7. Fallback and Retry Mechanisms

### 7.1 Gradle Fallback Boundary

See §4.1 for the full priority of pre-Run decisions. Fallback conditions fall into three groups:

- User or baseline forcing: Force Gradle, BuildTarget switch, compile-command change, unavailable project info; or external-source changes with remote compilation, a nonstandard Gradle command from which tasks cannot be derived safely, an external input missing metadata/task/artifact contract, metadata marked unsupported, or deletion of Dart/C/C++ source or configuration input. Deleting a Flutter asset or shrinking the external-build artifact set does not trigger fallback.
- State forcing: Without a baseline, or after a failed previous Gradle build, require a full compile directly rather than show a useless confirmation dialog. Whether a changed build file requires rebuilding is chosen by the user after the excessive-change confirmation.
- Performance policy: When Java/Kotlin file count or module count exceeds a threshold, the IDE defaults to Gradle but lets the user choose Continue for this run only. It checks build-file/dependency changes only after Continue. MCP/CLI and `checkFallback()` show no dialog and report fallback directly.

Fallback semantics after incremental compilation begins are separate from the pre-Run check. An uninitialized compiler, no-file-change confirmation, unexpected exception, too many recursively recompiled files, or a device becoming invalid during the run can switch to Gradle in this run. Ordinary source compilation failure fails this run directly and does not automatically run Gradle. Failed files remain pending changes marked as previously compiled; only the next Run decides whether to use Gradle under the no-file-change policy. On a compile-command change, logs include both `last=` and `current=` to distinguish a task switch from selecting another Jugg Configuration.

Both the no-file-change fallback confirmation and manual `Force Gradle Compile` confirmation let the user ignore the Gradle build cache. If selected, this run appends `--no-build-cache --rerun-tasks` to the Gradle command. The option affects only this fallback, is not saved into Run Configuration, and clears after task startup.

When no files changed but the Jugg flow continues, it consistently displays `Compiling 0 files...`. This includes first run, project switch, Debug, the direct-deploy branch of androidTest, and a dry-deploy branch where the user chose `Don't fallback`. It does not display this message after switching to Gradle fallback or cancelling.

### 7.2 Retries Within Incremental Compilation

- Retry strategy interface: `IIncrementalCompileRetryResolver`, whose implementations are chained by `IncrementalCompileRetryResolverChain`.
- Current chain order:
  1. `GitChangesRetryResolver` (`idea` layer): detects errors like `unresolved reference / cannot find symbol` → invokes `GitFileChangesDetector.updateChangedFiles()` → retries once if a new file is found.
  2. `IncrementalCompileRetryResolver`: detects dependency-missing keywords → updates compile context → retries once if it changes.
- Internal language-compiler fallback is outside this chain. Metadata, plugin options, IDE filesystem conflict, compiler recreation, and moving SDK `android.jar` to the end (`AndroidJarClasspathRetry`) are handled by `KotlinCompilerInvoker` within one invocation, sharing a single automatic-retry budget rather than using `IIncrementalCompileRetryResolver`.
- Effect-propagation recompilation uses `DeployFileManager.getRecompileFiles(...)`. `IncrementalCompilerHelper` filters continued compilation in two layers: (1) exclude sources compiled in the **previous round** (`lastRoundCompiledPaths`), except Kotlin top-level file-facade callers marked by `RecompileFiles.topLevelFacadeEffectedSourcePaths`; (2) exclude sources already recompiled for the same effect-trigger key in this session (`ContinueCompileEffectFilter.resolveUncompiledEffectedFiles`). Before dispatch, `schedulePendingEffectTriggers` writes `pendingEffectTriggerKeys`; the child frame consumes pending keys into `satisfiedEffectTriggers` before filtering. A key is `effectedPath + effectedByClasses` or the first-round const-ref batch. A **new** trigger from an earlier round (for example, a structural change in definition B newly requiring recompilation of caller A) still enters another round; the same `CrashDataSource -> SafeMode` key does not ping-pong. Recursive recompilation propagates only class/dex structural effects and does not feed those sources back to `ConstRefEngine` as new changed-source inputs.
- The post-success Git check (`GitChangesCompileChecker`) starts a second incremental compile only when a Git refresh finds **new pending** files (`!hasCompiledOnce`). A file already compiled in this round that merely changed membership in the undeployed set (for example, Kuikly rewriting `KuiklyCoreEntry.kt` without a snapshot change) does not trigger it. After compilation, `getAsyncResultIfCompleted()` consumes only an asynchronous task that has finished; it does not wait for an ongoing Git query. Incomplete queries are logged at debug level and the current flow continues, and late results are not misread by a later Run. A completed result is rechecked by path against current `DeployFileManager` state so a cached `ChangedFile` with `compiledTimes=0` does not wrongly trigger `compile again`.

### 7.3 Compiler Resource Lifecycle

`JuggCompiler` held by `JuggCompilerHelper` is the root of an IntelliJ `Disposer` tree. When a Compile Context change rebinds the compiler or the helper closes, release registered children recursively through `Disposer.dispose()`; calling only the root object's `dispose()` is insufficient. The `platform_compat` Disposer used by standalone preserves identity-based registration, parent-relation cleanup after proactive child disposal, reverse-registration order for siblings, and cleanup of remaining subtrees and the parent even if one node throws.

---

## 8. Investigation Entry Points

| Symptom | First entry point |
|---------|-------------------|
| User reports “this run skipped incremental and went straight to Gradle” | `JuggCompilerHelper.preprocessIncrementalCompile()` and `checkFallback()` |
| Log shows `found effected source files, continue compile` after success | `unCompiledEffectedFiles` after `getRecompileFiles()` in `IncrementalCompilerHelper.compile()` |
| Git check causes `compile again` after success | `GitChangesCompileChecker.getAsyncResultIfCompleted()` |
| Resource/manifest/asset output affects the wrong APK | `BaseCompiler.splitApkAndCompile()` and `targetApkPaths` in subclass `doApkCompile()` output |
| R-related runtime missing class or `R.styleable` error | `R.java` -> `SourceCompiler` -> `RDexForSubmoduleCompiler` flow in `JuggCompiler` |
| Files do not recompile next time after cancellation | `rollbackChangedFile()` / `clearStagingFiles()` in the `IncrementalCompilerHelper` cancellation branch |
| Custom compiler does not enter the expected stage | Numeric `CompileOrder` range and concrete compiler's `beforeCompileOrderRange` / `afterCompileOrderRange` |
| Background resources remain or are disposed twice after Compile Context switch | `JuggCompilerHelper.juggCompiler` setter, `close()`, and the `Disposer` registration tree |

---

## 9. Related Documents

- Source compilation: `02_compile_source.md`
- Resource compilation: `02_compile_resource.md`
- DataBinding/ViewBinding: `02_compile_databinding.md`
- Custom compilers and interaction: `02_compile_custom_ui.md`
- Deployment effect analysis: `03_deploy_data_generator.md`
