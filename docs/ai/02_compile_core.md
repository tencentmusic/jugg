# Compilation System: Control Flow

> Last verified: 2026-10-07
> Consistency rule: If documentation conflicts with code, code takes precedence.

## 1. Scope

This page explains the incremental-versus-Gradle decision, stage and deployment handoffs, and recompilation/recovery boundaries. For stage internals, see `02_compile_source.md`, `02_compile_resource.md`, `02_compile_databinding.md`, and `02_compile_custom_ui.md`.

## 2. Core Source Index

| Owner | Location | Responsibility |
|---|---|---|
| `JuggCompilerHelper` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/JuggCompilerHelper.kt` | Shared IDEA/standalone entry; chooses incremental compilation or Gradle and owns the Run fallback boundary |
| `IncrementalCompilerHelper` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/IncrementalCompilerHelper.kt` | Runs compilation rounds, records pending/staged results, and controls effect propagation and one repair retry |
| `JuggCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/JuggCompiler.kt` | Connects Compose resources, external builds, overlays, R classes, and source/dex compilation |
| `BaseCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/BaseCompiler.kt` | Groups inputs by module or owning APK and runs concrete compiler extension ranges |
| `CompileTask`, `CompileResult`, `CompileOutput` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/ICompiler.kt` | Carry inputs, per-file outcomes, outputs, cancellation, and APK ownership across stages |
| `DeployFileStateTracker`, `DeployFileManager` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/` | Own pending, compiled, undeployed, and staging state across rounds and Runs |
| `ExternalBuildCompiler`, `ExternalBuildTaskRunner` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/external/` | Run current-variant Flutter/native Gradle tasks and return deployable assets/native libraries |
| `GitChangesCompileChecker`, `GitChangesRetryResolver` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/` | Detect missed Git changes or repair a missing-source failure |
| `GradleWrapperRepairer` | `main/src/main/java/com/sickworm/intellij/jugg/gradle/compile/GradleWrapperRepairer.kt` | Repairs a declared local Gradle Wrapper before either Host starts a full build |

## 3. State and Output Contracts

| State | Meaning at the boundary |
|---|---|
| `CompileTask` | Each stage has its own input/output directory; `parentTask` shares cancellation, progress notifications, and the Gradle command. |
| `CompileResult.details` | Per-input success/failure moves upward. A failed stage marks unexecuted inputs through `quickFailedOthers()`; outputs alone do not certify the whole run. |
| `CompileResult.outputs` | Stage artifacts enter `DeployFileManager.addStagingFiles()`; deployment reads staging, not the compiler's temporary directories. |
| `CompileOutput.apkPath` / `targetApkPaths` | `apkPath` anchors an APK artifact; `targetApkPaths` routes artifacts affecting several APKs. Resource/manifest outputs remain APK-scoped, while class/dex outputs can target several APKs. |
| `CompileLoopStatus` | One incremental call tracks first round, repair retry, previous-round files, and satisfied effect triggers, allowing new causes to propagate without cycling on the same cause. |
| `DeployFileStateTracker` | First-round success moves original changes from uncompiled to compiled. Derived rounds do not redefine the original pending files. Compiled files remain available for deployment/retry until commit. |

## 4. Main Flows

### 4.1 Run decision and fallback

```text
JuggCompilerHelper.compile()
  -> record LastCompileTimestampRegistry before either compile path; MCP status exposes lastCompileTime
  -> wait for initialization and pending file events before reading change state
  -> preprocessIncrementalCompile(): start nonblocking Git check
     -> explicit Force Gradle / incompatible external-build inputs
     -> BuildTarget or full-build command changed
     -> missing full-build baseline or unavailable project info
     -> invalid device or previous failed Gradle build
     -> roll back unchanged file events
     -> excessive-change choice, then build-file/dependency confirmation
     -> final deploy-state check
  -> if canceled after precheck: return canceled before either compilation path
  -> null result: IncrementalCompilerHelper.compile()
     -> success: return; ordinary compiler failure: keep this Run failed
     -> result permitting immediate fallback: run Gradle in this Run
  -> non-null precheck result: run Gradle and refresh baseline
```

The excessive-change prompt's **Continue** applies only to this Run; later build/dependency checks can still require Gradle. A previous failed full build and a missing baseline force a build directly. Incremental source errors normally fail the current Run and do not automatically start Gradle. The next Run may choose fallback under the no-change policy. A no-change Run can instead deploy directly for first run on a device, project switch, Debug, or androidTest, or follow the user's fallback/dry-deploy choice. Direct zero-file paths notify `Compiling 0 files...`.

`checkFallback()` is a side-effect-free status precheck: baseline, project info, device/deploy state, then excessive changes. It has no Run options or dialogs, so it cannot report Force Gradle, a BuildTarget/command switch, or dependency confirmation. Interpret its reason as a status prediction, not the actual Run decision. MCP/CLI `isSkipDeploy` skips deployment after compilation; it does not bypass `updateDeployState()` or the compile fallback decision.

Manual Force Gradle and the no-change fallback confirmation can request `--no-build-cache --rerun-tasks` for that build only. The compile command comparison logs `last=` and `current=`; a mismatch may reflect a different task or Jugg configuration.

Before a full build, shared `JuggCompilerHelper.gradleCompile()` repairs missing Wrapper launch files only when the command names a project-local `gradlew`/`gradlew.bat` and wrapper properties exist. It can restore the bundled scripts/JAR and executable bit; Windows-to-remote builds also normalize `gradlew` line endings, except result-only fetches. This runs before remote project-info preparation and before the Gradle client, so an incomplete declared Wrapper can be fixed in both IDEA and standalone flows. A command using another executable or lacking wrapper properties is left alone.

### 4.2 Compilation, staging, and further rounds

```text
IncrementalCompilerHelper.compile()
  -> convert undeployed ChangedFile inputs to CompileFile; expose current files to UI
  -> JuggCompiler.compile(CompileTask(..., stagingDir)) enters JuggCompiler.doCompile()
     -> ComposeResourceCompiler: accessors/classes and changed Compose assets
     -> ExternalBuildCompiler: current Flutter/native task outputs
     -> AssetOverlayCompiler: assets, APK-root classpath resources, native libraries
     -> ResourceOverlayCompiler: manifest + res -> .flat -> arsc/R.java/overlay
     -> compile R.java and route module/external-library R.dex
     -> SourceCompiler: generated sources + user sources/classes -> dex/minify
  -> DeployFileManager.updateUncompiledFiles() for original pending inputs; add outputs to staging
  -> success: DeployFileManager.getRecompileFiles() -> affected sources/redex classes
     -> another round only for a new effect trigger
  -> failure: a resolver may repair context/missing Git inputs and retry once
```

Compose-generated classes and DataBinding/ViewBinding sources reenter source compilation; changed Compose/Flutter assets and native libraries enter the asset overlay. A failed or cancelled stage stops later stages and marks remaining inputs failed/cancelled. `CompileOrder` supplies custom-compiler ranges (`atFirst`, before/after asset, res, source, minify, dex, `atLast`); it is not the built-in stage scheduler. Pre-D8 `ClassPreparation` and the connected Hilt transformer run inside `DexCompiler`, with no separate custom-compiler slot.

Effect propagation uses `DeployFileManager`'s class/dex impact result. `ContinueCompileEffectFilter` excludes the previous round's source, except Kotlin top-level facade callers identified from `.kotlin_module`, and suppresses an effect key already satisfied in this session. A new structural trigger can recompile the same caller later; recursive recompilation does not feed those callers back into const-reference analysis as new user edits. Too many propagated files can make this Run fall back. If cancellation interrupts a recursive round, the first round rolls back its original files and clears staging.

The asynchronous Git check reads changed paths since the last full build; it does not validate the APK or deployment history. A failed query only loses that auxiliary check and cannot invalidate deploy history or Compile Context. After compilation, only a completed result is consumed, and it starts another incremental pass only for files still **newly pending** in `DeployFileManager`; an unfinished query is not awaited or carried into the next Run. `GitChangesRetryResolver` can refresh Git after an unresolved-reference failure; the resolver chain then retries once. Kotlin compiler compatibility retries occur inside `KotlinCompilerInvoker`, outside this chain.

## 5. Cross-File Boundaries

### 5.1 File identity, ownership, and deletion

`FileChangesHandler` filters each module's actual Gradle build directory and traditional `build/` directory before recognizing changes, including events arriving through Git reconciliation and effect propagation. Sources generated directly within this compilation are handed to later stages without reentering file monitoring. A pending file records a `lastModified + length` snapshot; duplicate IDE/Git events with the same snapshot preserve its compile state. A changed snapshot reopens it as pending.

An asset/resource event first delivered during a full Gradle build remains queued for the following incremental Run according to event arrival, not the source file's `lastModified`: a copy operation can preserve an old timestamp while producing a new file after its Gradle merge task has already run.

An absent file does not become a normal compilation input. Deleting or renaming a source, `res/`, asset, Manifest, or Compose resource produces no removal overlay; old APK/overlay content can remain until a full Gradle baseline replaces it. Rename compiles only the new path. For a previously queued external-build input that is now missing, the Run precheck explicitly requests a full build. Do not infer successful removal from a zero-file incremental result.

`BaseCompiler.splitApkAndCompile()` runs APK-scoped work against each owning APK rather than copying one result to all APKs. Ownership must survive the output handoff. In particular, `RDexForSubmoduleCompiler` uses `ModuleApkBelongs` for module `R.dex`; losing it can distribute a feature's R classes to unrelated APKs. AndroidTest module grouping includes module root so equal names cannot merge separate modules.

### 5.2 External Flutter/native builds

`FileChangesHandler` can watch external source roots outside module directories. Its one `ChangedFile.module` is only an anchor: precheck and `ExternalBuildCompiler` resolve **all** matching current modules. Each input directory carries its own accepted file rules; exclusions and toolchain caches (`.dart_tool`, `.cxx`, `.externalNativeBuild`) win first. Keep separate directory/rule pairs even when roots overlap, or a broad Flutter asset root can mask a stricter native source root. Gradle metadata determines task-confirmed files and roots; `pubspec.yaml` contributes asset directories and `l10n.yaml`'s `arb-dir`, without unrestricted disk or `package_config.json` fallback.

| External input rule | Meaning of a matching directory |
|---|---|
| `Dart` / `FlutterAsset` | `.dart` files / ordinary Flutter asset files; local package and configured asset roots can extend beyond the project. |
| `CppSource` / `CppHeader` | Explicit source/header suffixes from native configuration or include metadata. |
| `NativeDirectory` | Existing, non-hidden ordinary files under metadata-confirmed native directories; symlink files and paths through symlink directories are excluded. |

Exact configuration files match separately. Keep the directory and its rule set paired during normalization: remove only identical pairs, without collapsing a stricter subdirectory into an ancestor. The accepted scope can be slightly broad because Gradle's own up-to-date check decides task work.

Every changed external input runs its matching variant task; artifact CRC suppresses repeated deployment, not compilation. Matching tasks are deduplicated by module root, variant, task path, and type, then run in one Gradle invocation. The Jugg init script and `juggCollectExternalBuildInfo` collect a targeted metadata patch after task execution. The Host merges and persists the patch, updates `ICompileContext`, and `FileChangesHandler` replaces its scan scope; an incomplete or unpersistable patch fails the whole input rather than reporting partial success. Remote builds, non-derivable commands, missing/unsupported task or output metadata, and queued removed inputs fall back before this side path. A failed task or unreadable/unsafe contracted artifact fails the current Run.

Flutter assets go to the asset overlay; its native artifact is either the task's archive (`lib/<abi>/*.so`) or output directory (`<abi>/*.so`). Neither path licenses scanning unrelated Flutter intermediate directories. C++ deploys only stripped libraries from this invocation, using the owning app/feature's strip configuration. The preferred configuration is the full-build `build/jugg/classpath/native_strip` cache because Gradle Configuration on Demand may leave the APK owner unconfigured during a library task. Entries must uniquely match module root and variant, with a valid recorded or backed-up strip tool; missing, damaged, or ambiguous cache data permits one live read. If that also fails, request a full build instead of using unstripped merge output. A successful task with an accessible output path may legitimately produce no assets or libraries: success without a new deployment artifact, while old installed content can remain. `AssetOverlayCompiler` retains `relativeModule` so deployment can distinguish this Run's Flutter assets from baseline assets.

### 5.3 Lifecycle

`JuggCompilerHelper` owns `JuggCompiler` as the root of a `Disposer` tree. Rebinding Compile Context or closing the helper disposes registered children, including compiler daemons/loaders; invoking only the root object's `dispose()` would not release the child tree.

## 6. Diagnostic Boundaries

| Observation | What it establishes | Next discriminating evidence |
|---|---|---|
| `checkFallback()` reports a reason | The status precheck's current state, without Run options/prompts | `preprocessIncrementalCompile()` result and Run target/command |
| `found effected source files, continue compile` | An effect pass scheduled another round, not a new user edit | `getRecompileFiles()` result and `ContinueCompileEffectFilter` trigger keys |
| Git check says `compile again` | A completed Git query found files still newly pending | Current `DeployFileManager` snapshots; a stale cached `ChangedFile` alone is insufficient |
| Incremental compile failed | This Run failed unless its result permits immediate fallback | `CompileResult.details`, resolver result, `CompileTaskResult.isCanFallback` |
| External task succeeded with no new `.so`/asset | Task/output contract succeeded, not old-device-content removal | Contracted output path, collected artifacts, installed baseline |

Before concluding that a missing file event, effect loop, or fallback is the cause, check the producer result and counter-evidence at its boundary: current pending snapshot, target APK ownership, and actual Run decision. For runtime log collection scope, start with `09_plugin_runtime_debug.md`.

## 7. Related Documents

- Resource and Compose handoffs: `02_compile_resource.md`
- Source/Kotlin retries and Dex: `02_compile_source.md`
- External metadata and project model: `04_engineering_project.md`
- Deployment impact and staging: `03_deploy_data_generator.md`, `03_deploy_core.md`
- Verification authority: `06_testing.md`
