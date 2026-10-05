# Plugin Runtime Troubleshooting Manual

> Last checked: 2026-09-16
> Consistency rule: when documentation conflicts with code, code is authoritative.

---

## 0. Automatic action checklist for an AI reading this document

For a troubleshooting request that includes log excerpts, proceed in order:

1. Locate the incident time window in the excerpt, ideally to the millisecond.
2. Expand context above and below to establish the surrounding call chain and task state.
3. Use Section 3 keywords to find thread, state, fallback, and duration signals.
4. Locate the symptom owner from `[ClassName]`, then follow the call chain to the behavior owner.
5. Choose topical documents and initial evidence from Section 4; do not guess the implementation within this entry manual.
6. Apply the counterevidence gate in Section 2.3 before reporting root cause, call chain, and fix direction; label inference limits explicitly without direct evidence.

---

## 1. Runtime directory entry points

Defined by `JuggPathManager`; inspect these first:

```
build/jugg/                            # juggRootDir
├── log/                               # log directory
│   ├── compile_latest.log             # best-effort shortcut to current main log
│   ├── compile_latest-1.log           # best-effort shortcut to previous main log
│   ├── compile_YYYY-MM-DD_HH-mm-ss.0.log
│   └── standlone_cli/                  # separate standalone Runtime log directory
│       ├── compile_latest.log
│       ├── standalone_startup.log       # stdout/stderr captured when CLI starts daemon
│       └── compile_YYYY-MM-DD_HH-mm-ss.0.log
├── build/staging/                     # this incremental compile's output (dex/resources)
├── database/
│   ├── apk/                           # SQLite DBs from APK parsing (*.db)
│   ├── project_infos.db/              # module/APK configuration snapshot
│   │   ├── project_infos.json
│   │   └── gradle_project_infos.json
│   ├── compile_context.db/            # classpath and module information
│   │   ├── complete_flag               # marker of complete compile-context write
│   │   ├── module_builds.json          # module build-path snapshot
│   │   ├── full_build_info.json        # Gradle full-build command, BuildTarget, write time
│   └── deploy_history.db/             # deployment history (incremental recovery)
├── classpath/
│   ├── root/                          # classpath jars
│   ├── apk/                           # APK file cache
│   └── libraries/                     # dependency backups
├── config/
│   ├── custom_compilers/
│   ├── agent_setup.md
│   └── jugg-android-dev-loop/
└── tmp/diff/                          # remote-compile diff results

${projectRoot}/.gradle/jugg/
├── readProjectInfo.gradle.kts
└── jugg-runtime.jar

~/.jugg/const_ref/                     # cross-project constant-reference cache (global)
~/.jugg/locks/global.lock              # IDEA/standalone global write lock
~/.jugg/hot_update/                    # verified update jars, hot_update_data.json, load_manifest.json
```

**Code location**: `main/src/main/java/.../project/runtime/JuggPathManager.kt`.

Current `reportIssue()` still collects through `ProjectInfoReader.printInfo()`, device logcat dump, and `JuggServer.reportAndUploadLogs()`; it does not additionally generate runtime diagnostics JSON. IDEA and standalone each retain their latest 10 logs. Reporting merges by modification time, selects only the latest 10 overall, and marks standalone sources under `diagnostics/logs/standlone_cli/`. Design a shared diagnostic model when standalone doctor/report gains a real command entry point.

Standalone main logs are segmented by project Runtime lifecycle: initialization creates a segment, ordinary `compile/deploy` appends, and a successful Gradle full build starts a new segment when rebuilding the compile context. Each CLI-initiated daemon startup overwrites `standalone_startup.log`, used only for immediate startup-failure diagnosis.

---

## 2. Logs and evidence boundaries

Log format:

```text
[2026-03-16 16:13:27.109] [FINE   ] [ClassName] message
```

- Timestamps have **millisecond** precision.
- Levels: `FINE`=debug / `INFO` / `WARNING` / `SEVERE`.
- `[ClassName]` is chosen by `logger.getInstance("ClassName")` and can directly guide code lookup.
- Directories distinguish log sources: `log/` is IDEA and `log/standlone_cli/` is standalone. For `Runtime lock contention` and `Runtime lock acquired after contention`, reconstruct alternating lock ownership from `runtime`, `ownerRuntime`, `ownerPid`, `ownerCommand`, `ownerJobId`, and `waitMs`.

### 2.1 Evidence levels and interpretation limits

| Level | Typical content | Use boundary |
|-------|-----------------|--------------|
| Raw evidence | Exception stacks, protocol state, process state, source branches, command results, Git diff. | Directly supports facts at its own layer, but verify time, version, and source. |
| Derived result | CLI/UI summary copy, wrapper error, terminal-job summary, aggregate state. | Proves only that its producer classified the event that way; it does not directly establish its assumed underlying cause. |
| Investigation conclusion | Root cause, scope, version boundary, fix judgment. | Requires raw evidence or verified producer implementation. |

On a standalone remote-compile failure, first read `{projectDir}/build/jugg/log/standlone_cli/compile_latest.log` and join login, sync, Gradle, and artifact-fetch phases by `RemoteGradleCompileClient` command ID. `Standalone Runtime is non-interactive` means the configuration lacks directly usable SSH credentials or iFT still needs interactive authentication; configure it first in IDEA/profile or an external iFT client. Logs do not preserve the raw remote command, and only allowlisted path values from environment variables are visible. Do not ask a user to upload plaintext passwords or a complete environment.

---

### 2.2 Symptom owner and behavior owner

The component that prints an error, displays one, or returns aggregate state is the symptom owner; it may not decide the abnormal behavior:

1. Find who generated the observed result and which lower-level results it consumed.
2. Follow the call chain to the behavior owner that actually decides the abnormal behavior, state transition, or compatibility branch.
3. Until the behavior owner is known, do not exclude other boundaries with a narrow Git path filter; search history first by user-visible symptom, key symbol, or content change.
4. Once found, narrow to its code, version, regression owner, and fix boundary.

### 2.3 Counterevidence gate before a conclusion

1. Write the leading conclusion and the direct evidence supporting it.
2. Specify at least one observable item that would falsify or substantially weaken it.
3. Actively look for that item in existing logs, source, history, attachments, and runtime state; failure to search does not mean it is absent.
4. Explain each conflicting item found; if it cannot be explained, weaken the conclusion or continue locating the behavior owner.
5. Check whether the conclusion exceeds the time, version, host, or call-layer boundary of the evidence.

The counterevidence gate does not require exhaustive hypotheses or a fixed number of tool calls. When evidence is missing, state what is missing and the narrowest conclusion supported; do not invent certainty.

---

## 3. Common search-keyword quick reference

| Investigation target | Search keywords |
|----------------------|-----------------|
| Compile start | `Jugg compile started` |
| Incremental/full decision | `preprocessIncrementalCompile` |
| File changes and full fallback | `confirmFallbackWhenNoFileChanges` / `No file changes` / `fallback` |
| EDT and lock contention | `dispatching to background` / `waitCost=` / `waiting for TaskRunnerManager lock` |
| Post-compile Git check | `Git check after compile is still running` / `Git recovery CRC summary` |
| APK DB initialization | `initAfterInstall parsed apk start` / `database all init finish` |
| SQLite query | `getClassNodes` |
| Compile duration | `cost ${costTime}ms` |
| Compile or deploy failure | `incremental compile error` / `SEVERE` / `deploy start` |
| Standalone remote authentication | `Standalone Runtime is non-interactive` / `remote login` |
| Remote shell safety handshake | `failed to disable remote shell echo` / `Remote shell echo could not be disabled safely` |
| Remote sync and artifact fetch | `Sync file` / `Fetch` / `RemoteGradleCompileClient` |
| R class exists but resource field is missing | `module compile R.jar candidates found in module` / `R.jar candidates found in module` / `compile_r_class_jar` / `compile_only_not_namespaced_r_class_jar` |
| IDE cannot recognize a deployable process | `NO_DEPLOYABLE_APP` / `deployable client unavailable` / `ideClientPids` / `Unexpected cmdline file for PID` |
| Kotlin IR lowering internal error | `BackendException` / `Exception during IR lowering` / `copyValueParametersToStatic` / `Dispatch receiver type` / `SyntheticAccessorGenerator` |
| UI freeze | `uiFreezeStarted` / `InvocationEvent has timed out` |
| ConstRef startup and scanning | `ConstRefEngine defer initial full scan until startup stabilizes` / `ConstRefEngine io throttle enabled` / `ConstRefEngine full scan progress` |
| ConstRef fallback | `fallback to no-op const-ref` |
| IDE startup chain | `InitialVfsRefresh` / `postInit` / `clangd` |
| Release reobfuscation | `Obfuscated:` / `mapping.txt` / `Minify is enabled for the current variant` / `visitAnnotation` / `mapType` / `const-class` / `filled-new-array` / `widenAccessFlags` / `invoke-direct` / `ExternalSyntheticLambda` |
| Jugg Debug attach | `Jugg Debug attach:` / `waitForClientReadyForDebug` / `Debugger is waiting for application to start` / `Connected to the target VM` |

---

## 4. Symptom routing and first evidence

This section supplies only the first troubleshooting hop. On a symptom match, read the topical document rather than expanding historical fixes or individual visitor/API implementation lists here.

| Symptom | First evidence and interpretation boundary | Behavior owner / topic |
|---------|--------------------------------------------|------------------------|
| Brief IDE freeze on click or operation | Align freeze time in `idea.log`, Jugg log pause, and thread dump. A log gap alone does not prove Jugg held a lock. | `IdeaFileChangeMonitor`, `FileChangeManager`, `TaskRunnerManager`; `04_engineering_ide.md`. |
| Long freeze after startup | Collect Jugg log, `idea.log`, freeze dump, and on-scene `jcmd`; separate ConstRef, IDE startup, and EDT lock-contention paths. | `04_engineering_ide.md`, `03_deploy_const_ref.md`. |
| ConstRef SQLite corruption | Check corrupt-DB rebuild and `fallback to no-op const-ref`; a DB exception should not expand into Run/compile/deploy failure. | `ConstRefCacheDatabase`, `ConstRefEngine`; `03_deploy_const_ref.md`. |
| Jugg Debug breakpoint unavailable | In the same window confirm WAITING, `Connected to the target VM`, and final session creation; "waiting for debugger" does not mean the VM connected. | `04_engineering_debug_attach.md`. |
| Changes present but fallback to full Gradle | Compare changed files, IDE file events, Git follow-up, and deploy history. Do not destroy evidence by deleting history first. | `JuggCompilerHelper`, `DeployFileManager`; `02_compile_core.md`. |
| Incremental compile says a resource field cannot be found (`找不到符号: 变量 xxx`), although R class exists and resource was not deleted | First rule out a missing resource: the missing field is inside `R$xxx`, indicating classpath shadowing. In order, check which same-named `R$xxx` jar occurs first on actual javac/kotlinc `-classpath`; lastModified of `compile_r_class_jar` and `compile_only_not_namespaced_r_class_jar` under that module; selected path in `module compile R.jar candidates found in module` debug log. After a fix, one module should contribute only one Gradle R.jar. If both R layouts still enter classpath, treat as a `BaseCompileContext.getGradleRFilePaths()` regression. | `BaseCompileContext.getGradleRFilePaths()`, `ModuleBuildPathInfo.moduleCompileRFileCandidates`; `02_compile_source.md`, `04_engineering_project.md`. |
| New Flutter asset does not trigger compile | Check `Detect file changed (before filter)`, Git `no-record`, then `ChangedFile[ExternalBuildSource]`. If stopped before classification, compare Flutter `inputFiles` in `gradle_project_infos.json`, current pubspec asset file/directory declarations, and `excludedDirs`. Undeclared new files remain ignored; do not infer ownership from an arbitrary assets directory. | `FileChangesHandler`, `resolveExternalBuild`, `GradleProjectInfoReader.readFlutterInputs`; `02_compile_core.md`, `04_engineering_project.md`. |
| `not gradle compile yet` after upgrade | Check `complete_flag`, `module_builds.json` version, and recovery log; do not fabricate a missing flag manually. | `CompileContextDb`, `BuildPathInfoSerializer`; `04_engineering_project.md`. |
| `Git check after compile is still running` | This debug message only means this run does not wait for an asynchronous follow-up, not compile failure. Investigate Git query size/history only if persistent. | `GitChangesCompileChecker`; `02_compile_core.md`. |
| Slow APK DB initialization | Align APK size, isolated-parser signals, DB size, and measured time. | APK parser/database; `05_utilities.md`. |
| Compatibility resource deploy OOM, followed by persistent `FileSystemAlreadyExistsException` | Align `ResourceApkModifier` entry/byte/heap logs, `Open ZipFS` temporary path, and deploy-payload heap. Later Runs should use a new temporary URI; after OOM, the formal cache should be cleaned. | `ResourceApkModifier`, `ApkFileModifier`, `JuggDeployerHelper`; `03_deploy_core.md`, `05_utilities.md`. |
| `source_files.db` rebuilds on every startup | Check rebuild stamp, deletion failure, and `SQLITE_BUSY`; DB creation/modified time does not establish recent rebuild. | `SourceFileManager`, `SourceFileDatabaseSqLiteHelper`; §4.5 here. |
| Runtime crash after release incremental build | Confirm real minify configuration of the current variant and matching mapping source (`variants[].minifyEnabled` / `ModuleInfo.minifyEnabled`), then mapping load and `Obfuscated:`, then compare staging and APK DEX. Exception names alone cannot establish a mapping gap. Residual `outputs/mapping/<variant>/mapping.txt` for an unminified variant does not participate in the decision or obfuscation. | `ICompileContext.isMinified`, `DexMinifyCompiler`, `DexObfuscator`; `02_compile_obfuscation.md`. |
| Kotlin `INTERNAL_ERROR` with shaded `JavaVersion` in stack | Failure even after compiler recreation only strengthens a host-environment inference; also inspect host JDK, project Kotlin version, and compatibility logs. | `KotlinCompilerHostCompat`; `02_compile_source.md`. |
| Kotlin `INTERNAL_ERROR` with `DelegatingFileSystem.close`, `DescriptorLoadingContext.close` in stack | Confirm `UnsupportedOperationException` occurs in the same exception block. Then warmup caches only current compiler-classpath state; a separate JVM retry log should appear for actual sources. The subprocess takes one Kotlin argfile argument; other toolchains should not be downgraded in tandem. | `KotlinCompilerOutputParser`, `KotlinCompilerInvoker`, `KotlinCompilerProcessRunner`; `02_compile_source.md`. |
| Kotlin `cannot access ... which is a supertype of ...` / `unresolved supertypes:`, common in ROM or vehicle system apps using hidden APIs | Check whether SDK `android.jar` precedes a same-named framework/HideAPI jar in `-cp` of `kotlin compile: kotlinc`; this is not a missing HideAPI path. A match should produce a trailing retry log with a `-cp` order intentionally different from default. | `AndroidJarClasspathRetry`, `KotlinCompilerInvoker`; `02_compile_source.md`. |
| Kotlin `required plugin option not present` | Compare `kotlinPluginOptions` in `gradle_project_infos.json` with `-P plugin:` in `kotlin compile: kotlinc`. If present but still failing, check plugin/Kotlin versions; if missing, check `KotlinCompilerPluginData` reading. Fallback disabling must precisely match the plugin ID declared by `CommandLineProcessor`, not disable all plugins. | `GradleProjectInfoReader`, `KotlinCompilerInvoker`; `02_compile_source.md`. |
| Kotlin `unsupported plugin option` | Confirm the rejected argument came from `kotlinPluginOptions`. Jugg removes all arguments for that plugin ID from Gradle-resolved arguments and retries only once; it does not modify user `kotlinFreeCompilerArgs`. If repeated, check whether compiler toolchain, plugin JAR, and Gradle task belong to the same compilation. | `KotlinCompiler`, `KotlinCompilerInvoker`; `02_compile_source.md`. |
| Kotlin `BackendException: Exception during IR lowering`, caused by `copyValueParametersToStatic` and `Dispatch receiver type ... is not a subtype of ...` | Verify the real inheritance chain, whether failure is in Gradle/Kotlin incremental compilation, and whether clean restores success. With a valid chain and clean recovery, investigate an intermittent Kotlin compiler IR synthetic-accessor defect first, not a source-type error or Jugg missed dependency compile. | §4.7 here; `02_compile_source.md`. |
| Windows command output garbles Chinese | Preserve the raw-byte path; `�` may mean irreversible decode loss has already happened. | `ProcessOutputReader`; `04_engineering_compat.md`. |
| System app cannot install, lacks `FLAG_SYSTEM`, or privileged permission is denied | First see whether `codePath` is under `/system/` and whether this run used only `pm install` / `JuggDeployer.install`. Do not treat it first as an ordinary deploy failure. | `JuggDeployer.install`; `03_deploy_system_app.md`. |
| System app Run says cannot update / signature mismatch | Compare certificates of the `/system` baseline APK and the APK being installed. A debug keystore cannot update a platform-signed system package. | `03_deploy_system_app.md`. |
| App runs but log shows `NO_DEPLOYABLE_APP` | Align Jugg and `idea.log`; use `pidof` and `run-as` to distinguish missing IDE Client from real non-debuggability. Successful Direct Overlay is a best-effort fallback and this state alone does not prove failure. | `DeployStateManager`, `DirectOverlaySwapTransport`; §4.8 here and `03_deploy_core.md`. |

### 4.1 IDE freeze and ConstRef startup evidence

Collect first:

1. Current or recent `compile_*.log`.
2. `idea.log` from the same time window.
3. `threadDumps-freeze-*`.
4. An on-scene `jcmd <pid> Thread.print -l`.

Anchor on `uiFreezeStarted` or the user's perceived time, aligning Jugg's active task with worker stacks:

- An active ConstRef full scan in Jugg logs together with a worker stack in const-ref/SQLite supports a ConstRef high-load conclusion.
- When `ApplicationImpl.postInit`, `InitialVfsRefresh`, or `clangd` is more active and Jugg has no corresponding work signal, first inspect the IDE startup chain.
- Continue investigating lock-contention ownership only when `waitCost=`, `TaskRunnerManager lock`, and an EDT stack appear together.

If source defaults differ from on-scene logs, check the installed plugin version, system properties, and runtime overrides first; current HEAD cannot override incident facts.

#### 4.1.1 Long freeze after startup (`postInit / InitialVfsRefresh / clangd / ConstRef` contention)

1. Find a pause interval (over 100 ms between two timestamps with no intervening log).
2. Search `waitCost=` for lock waiting and `dispatching to background` for file-change dispatch; a log gap alone does not identify the lock owner.
3. Check the EDT stack for a synchronous file-change path or `@Synchronized` call before attributing the freeze to Jugg.

Historical notes describe an approximately 150 ms EDT VFS wait on a compile-held `DeployFileManager` lock, a shared `JuggManager` lock between file processing and Run Configuration creation, and recursive expansion of unrelated VFS directories before filtering. Current `FileChangesHandler` prunes directory traversal against project and module scan roots, including module roots outside the project directory. For a new incident, navigate from `IdeaFileChangeMonitor`, `FileChangeManager`, `FileChangesHandler`, `TaskRunnerManager`, and `HostTaskExecutor`; the former `FileChangesDetector` is a historical class name, not a current source path. Check `04_engineering_ide.md` for current ownership and do not assume any historical cause recurred.

#### 4.1.2 ConstRef startup and cache failure boundary

- `DeployFileManager` may construct `ConstRefEngine`, but its constructor must not initialize SQLite runtime resources; a cache exception must not fail `JuggManager` initialization.
- `ConstRefCacheDatabase` corruption triggers close, DB/WAL/SHM rebuild, then at most one retry of the operation that failed. Failed runtime initialization degrades ConstRef to a no-op for this process; other operation failures remain local to the current operation.
- If database rebuild or `RepoSharedFingerprintStore` initialization still fails, inspect `fallback to no-op const-ref` and confirm the main compile/deploy path continues. An initialization failure disables this process's ConstRef runtime; a later operation failure affects that operation. The exact scope is described in `03_deploy_const_ref.md` §5.

### 4.2 Distinguishing evidence for a release runtime crash

| Exception pattern | Next distinguishing evidence |
|-------------------|-----------------------------|
| Annotation/reflection lookup failure | Compare annotation type descriptors in staging and APK DEX. |
| `NoClassDefFoundError` | Check whether `const-class`, arrays, exception tables, and other type references in caller DEX still use original names. |
| `IllegalAccessError` / `IncompatibleClassChangeError` | Compare member access flags, direct/virtual sections, and invocation form. |
| `AbstractMethodError` for a new class, anonymous class, or lambda | Check whether method mapping can be inferred from interfaces/superclasses when the class has no mapping of its own. |
| `AbstractMethodError` after repeatedly incrementally compiling an implementation while its default-method interface is unchanged | Identify the actual receiver class and compare the installed, baseline-APK, and staging DEX method signatures and forwarding methods. Check `$-CC` / `$DefaultImpls`, the D8 owner variant `minApi`, resolved default interfaces, and the external superclass chain in the temporary D8 classpath. An unchanged interface source or the exception name alone does not identify the failed boundary; if only release fails, compare method mapping before attributing the crash to desugaring. |
| `NoSuchMethodError` in a Kotlin facade or keep class | Inspect R8 synthesized entry method names, argument format, and identity-mapping coverage. |
| An unminified variant still produces obfuscated names | A residual `mapping.txt` from an earlier minified build exists in that variant directory; verify `variants[].minifyEnabled` is `false` and project info came from this Gradle read. |

Current implementation constraints for these patterns are recorded in `02_compile_obfuscation.md`. Neither exception type nor "target class name absent from logs" alone confirms a specific gap; verify collection scope and DEX/mapping evidence.

### 4.3 Asynchronous Git follow-up after compilation

**Signal**: `Git check after compile is still running, continue without waiting.` appears after a compile.

**Current expected behavior**:
- Start the Git follow-up asynchronously before incremental compilation to find disk modifications missed by IDE file events.
- After compilation, consume only completed follow-up results, without waiting for queries still running.
- An unfinished query logs debug only; current compilation and deployment continue. Late results do not trigger a second compile this run and are not misread by a subsequent Run.
- A background query may finish naturally and its file-refresh results may enter pending state for a later Run.

**Investigation steps**:
1. Search `gitManager.getChangedFiles` and `gitManager.getUncommittedFiles` to separate commit-diff and working-tree scan durations.
2. Search `Git recovery CRC summary` for candidate-file and historical-CRC scale.
3. This log alone does not mean this Run failed. Inspect repository size, untracked files, and deployment history only if it appears persistently and frequently.

### 4.4 Slow APK database initialization

**Signal**: X > 3000 in `database all init finish, cost Xms`.

**Investigation steps**:
1. Check APK size in `build/jugg/classpath/apk/`.
2. Search `APK size exceeds threshold` for isolated-process parsing.
3. Check DB file sizes under `build/jugg/database/apk/`.

### 4.5 `source_files.db` rebuilds on every startup

**Signal**: IDEA or standalone initialization repeatedly logs `source file db is too old, recreate database`, inflating source-index scan time and potentially followed by `SQLITE_BUSY`.

**Current expected behavior**:
- Last full rebuild time lives in `build/jugg/database/source_files.rebuild_at`, not DB creation or last-modified time.
- Update the stamp only after database creation/rebuild, schema initialization, and a complete `updateSourceDirs()` commit; ordinary incremental `updateFiles()` does not refresh it.
- A legacy DB with no stamp, corrupt stamp, stamp older than 14 days, or stamp obviously in the future is fully rebuilt once. A failed rebuild does not update the stamp.
- Failure to delete the old DB raises an error in the database helper and is logged by the manager; do not treat the old file as a successful rebuild.
- `Clear Jugg Build` deletes both DB and stamp; reopening the project initializes a new database normally.

**Investigation steps**:
1. Check whether `source_files.db` and `source_files.rebuild_at` both exist.
2. Search `source file db rebuild stamp` / `source file db daysSinceRebuilt` to determine whether the stamp is absent, corrupt, in the future, or older than 14 days.
3. Search `Failed to delete database` and `SQLITE_BUSY`, aligning IDEA and `standlone_cli` logs to see whether another Runtime is writing.
4. Do not repair the stamp manually using creation or last-modified time. For recovery, use `Clear Jugg Build`, or close relevant Runtimes then delete `source_files.db` and `source_files.rebuild_at`.

**Key classes**:
```
main/.../deploy/data/SourceFileManager.kt
main/.../deploy/data/SourceFileDatabaseSqLiteHelper.kt
```

### 4.6 Annotation-type mismatch crash after release incremental compilation

**Signal**: a runtime crash reports a class "has no public methods with @Subscribe annotation", or another failed annotation lookup such as `EventBusException`, Dagger/Hilt injection failure, or annotation type mismatch.

**Investigation steps**:
1. Confirm that the current variant is minified, its matching mapping loaded, and relevant `Obfuscated:` output exists; do not infer a mapping gap from the exception text alone.
2. Compare annotation type descriptors on the affected class, method, and field in staging DEX and the installed APK DEX. Verify the reflected method's presence and visibility before treating this as a type-remapping failure.
3. If descriptors differ, inspect `DexObfuscator` class/field/method `visitAnnotation()` and annotation-value `mapType()` paths against the actual mapping. Route other release crash patterns through §4.2 and `02_compile_obfuscation.md`.

**Key classes**:
```
main/.../compiler/obfuscation/DexMinifyCompiler.kt
main/.../compiler/obfuscation/DexObfuscator.kt
```

### 4.7 Intermittent dispatch-receiver type assertion during Kotlin IR lowering

**Typical signal**:

```text
org.jetbrains.kotlin.backend.common.BackendException: Exception during IR lowering
java.lang.AssertionError: Dispatch receiver type A is not a subtype of B
org.jetbrains.kotlin.ir.util.IrUtilsKt.copyValueParametersToStatic
org.jetbrains.kotlin.backend.common.lower.inline.SyntheticAccessorGenerator
```

The JOOX Android report `jugg_scene_JOOX_Android_ext_20260911_144438` confirmed one complete case:

- The incident used Kotlin 2.0.21. Failure occurred in remote Gradle `:wemusic:compileDebugKotlin`, with stack entering `IncrementalJvmCompilerRunner`.
- The error claimed `PlayerGeneralSongInfoFragment` was not a subtype of `AbsPlayerFragment`, but the actual APK/Dex inheritance chain is `PlayerGeneralSongInfoFragment -> AbsPlayerPagerSubCellFragment -> AbsPlayerFragment`, which is valid.
- After modifying `AbsPlayerFragment.kt`, the previous Jugg incremental compile correctly cascaded to the intermediate class and `PlayerGeneralSongInfoFragment.kt` and succeeded. Current evidence does not support a stable missed impact-analysis compile.
- Many tasks in the failed Gradle build were `UP-TO-DATE`; running the same Gradle configuration after clean succeeded. Current evidence does not support a stable source-semantic error.
- `PlayerGeneralSongInfoFragment`'s Kotlin SMAP contains inline-code mappings from `AbsPlayerFragment.kt`, consistent with the synthetic-accessor lowering boundary in the exception stack.

**Current conclusion**:

- High-confidence root cause: an intermittent internal Kotlin JVM IR compiler defect. Public issue [KT-73245](https://youtrack.jetbrains.com/issue/KT-73245) closely matches the incident exception, Kotlin version, and intermittence, and was merged into [KT-51944](https://youtrack.jetbrains.com/issue/KT-51944).
- Medium-high-confidence trigger: Gradle/Kotlin incremental compilation state. Remote source synchronization excludes ordinary `build` directories, so remote Gradle/Kotlin artifacts persist across builds; successive changes to a base class, indirect subclass, and inline access may expose the compiler defect more readily.
- The report did not include the remote Kotlin cache at the instant of failure, so the specific corrupt cache entry cannot be identified, nor can deterministic dirty incremental state be distinguished from a nondeterministic compiler race.
- No `-Xbackend-threads` was seen, so parallel IR backend cannot be assumed. KT-51944 remains open; a Kotlin upgrade is a candidate experiment, not a guaranteed fix.

**Counterevidence boundary**:

- If failure reproduces consistently after clean, reconsider source, compiler-plugin, and fixed-toolchain compatibility and reduce weight on the incremental-state hypothesis.
- If the Dex/source inheritance chain does not actually satisfy the asserted subtype relationship, this is a real type or mixed-version input problem; do not apply this case.
- If logs show the relevant base or intermediate classes were not synchronized to remote, investigate sync inputs first rather than label the missing sync a compiler bug.

**Minimum preservation and distinguishing steps on recurrence**:

1. Before clean, save the full Jugg report and back up remote module `build/kotlin/compileDebugKotlin`, `build/tmp/kotlin-classes`, project `.gradle/kotlin`, and `.kotlin/errors`. Record a missing path as not generated without inventing its cause.
2. Retry the original command once without changing source. Recovery without a change strengthens the nondeterministic compiler-bug or race interpretation.
3. Try targeted `./gradlew :<module>:compileDebugKotlin -Pkotlin.incremental=false`; recovery only with Kotlin incremental disabled strengthens the incremental-state interpretation.
4. Try module-level `:<module>:clean` next to see whether the whole project need not be cleaned.
5. Save `--info` output or actual Kotlin compiler arguments to check dirty sources, classpath, compiler plugins, and `-Xbackend-threads`.

Without this recurrence evidence, do not automatically clean the entire project based on this exception alone. If a Jugg-side fallback is later needed, match this exact exception chain and first evaluate one bounded retry with module-level clean or Kotlin incremental disabled so other IR lowering errors are not hidden.

### 4.8 App is running but Android Studio shows `NO_DEPLOYABLE_APP`

**Typical signals**:

- Jugg logs show `IdeDeployState(state=NO_DEPLOYABLE_APP, message=Android Studio deployable client unavailable)` or older wording `app not running or not debuggable`.
- Deployment logs show `ideClientPids=[]`, while `adb shell pidof <packageName>` still returns a process.
- Android Studio `idea.log` in the same window may show DDMLib process-recognition errors such as `Unexpected cmdline file for PID`.
- A newer Android Studio sees the app on the same device, but an older version does not.
- On Android 15 or later with an Android Studio version before Meerkat, the first resource deployment may restart the app for JVMTI compatibility. Although the app is in foreground afterward, the next deployment still gets `NO_DEPLOYABLE_APP`.

**Interpretation boundaries**:

- `NO_DEPLOYABLE_APP` is an Android Studio Apply Changes client observation, not direct evidence of the APK `debuggable` property or device process state.
- `ideClientPids` comes from Android Studio/DDMLib's client list; it is not equivalent to real device processes from `pidof`.
- Successful `run-as <packageName>` proves the sandbox-access prerequisite for ordinary Direct Overlay; a missing Android Studio Client alone does not make Direct Overlay unavailable.
- Ordinary Direct Overlay independently validates deployment cache and device overlay checkpoint, but commits only sandbox files and does not refresh the running process. `Direct Overlay fallback succeeded` means the best-effort backup write channel committed. This path must propagate a restart requirement through the deployment lifecycle even if the app is currently in foreground.
- Do not confuse ordinary Direct Overlay with `DirectAppSandboxDeployTransport`. The latter attempts runtime apply to the running process and determines restart from the actual result; the former always requires restart after success.

**Current correct behavior**:

1. When `NO_DEPLOYABLE_APP` occurs with app in foreground and Direct Overlay enabled, print `App is running but not deployable by Android Studio. Direct Deploy will restart the app after deployment.` first, so the user knows about this run's restart before writing.
2. After ordinary Direct Overlay succeeds, expect `Direct Overlay fallback succeeded`, `after direct overlay deploy`, `Restarting app...`, and corresponding `am start -S`, in order. `App foreground, no need to restart app.` should not appear.
3. Even if the original deployment type is `HOT_RELOAD`, an actual restart this run requires final user output `Jugg HOT_FIX SUCCESSFUL ...` and `App restarted.`, not `Jugg HOT_RELOAD SUCCESSFUL ...` / `App deployed.`.

`needsRestartApp` describes only whether this run actually needs a restart; it does not encode which deployment path was used. Print the Direct-Overlay-specific message at the choice point where `NO_DEPLOYABLE_APP`, app foreground, and the Direct toggle are known. Do not infer "must be Direct Deploy" in finish from `needsRestartApp && deployType == HOT_RELOAD`.

**Investigation steps**:

1. From `compile_*.log`, record `NO_DEPLOYABLE_APP`, `ideClientPids`, app foreground, Direct Overlay enable/canTry, overlay checkpoint, and final fallback result.
2. Align the same millisecond window in Android Studio `idea.log`; search `Unexpected cmdline file for PID`, DDMLib, JDWP, and client-related logs.
3. Without restarting the incident scene, run `adb shell pidof <packageName>` to confirm the real device process.
4. Run read-only `adb shell run-as <packageName> pwd` to verify sandbox access. Preserve the original error on failure; do not interpret it as merely an IDE observation issue.
5. If the Direct Overlay checkpoint matches and commit succeeds, confirm `am start -S` restarted the old process and final output is `HOT_FIX` / `App restarted.`. If only `HOT_RELOAD` / `App deployed.` appears, or the process did not change, the overlay was written but the restart lifecycle contract was not met.
6. If `run-as`, cache, or checkpoint fails too, proceed to recovery/reinstall or return an explicit failure.

Scope incident conclusions to Android Studio version, Android API, plugin version, and time window. An older Android Studio observation defect cannot be generalized to all IDE versions or all `NO_DEPLOYABLE_APP` cases.

---

## 5. Before troubleshooting: preserve the scene

Back up before any cleanup, retry, reinstall, or another Run:

```bash
BACKUP=~/Desktop/jugg_debug_$(date +%Y%m%d_%H%M%S)
mkdir -p "$BACKUP"
cp -r {projectDir}/build/jugg/log/ "$BACKUP/log/"
cp -r {projectDir}/build/jugg/database/ "$BACKUP/database/"
```

`compile_*.log` are primary logs; `compile_latest*.log` are only shortcuts.

Attach by scenario when filing an issue:

| File | Path/source | Scenario |
|------|-------------|----------|
| Jugg main log | `build/jugg/log/compile_*.log` | All problems. |
| IDE main log | `idea.log` | Freeze, startup, debug attach, IDE lifecycle. |
| Freeze dump / on-scene thread stack | `threadDumps-freeze-*`, `jcmd <pid> Thread.print -l` | Stalls and deadlocks. |
| Project information | `build/jugg/database/project_infos.db/` | Module, variant, included build, APK ownership. |
| APK database | `build/jugg/database/apk/` | APK parsing and database state. |
| Deployment history | `build/jugg/database/deploy_history.db/` | Incremental state and recovery. |
| Crash / logcat / device overlay | Device scene. | Runtime crash, resources, and deployment. |

Use `tools/collect_jugg_scene.command <projectDir>` to save APKs, R.jars, device crash/logcat, actually installed APKs, and overlay artifacts in one step. ADB resolution is recorded in `meta/adb_resolution.txt`. If a user does not have this repository, send the full `tools/collect_jugg_scene_prompt.md` to them for their Agent to run in the affected Android project. The Agent downloads the official script from GitHub; after collection, the file manager opens the desktop `jugg_scene_*.zip`. For runtime resource issues, collect before another Run, reinstall, or data clear overwrites staging and device overlay.

For included-build resource IDs and Application/Dynamic Feature ownership, continue at troubleshooting entry points in `02_compile_source.md` and `04_engineering_project.md`; do not repeat project-model and classpath rules here.

---

## 6. Runtime fix verification flow

`06_testing.md` is the sole authority for test value, TDD, L0–L3, and test placement. This manual adds runtime-problem evidence requirements only:

1. Preserve stable failure evidence before editing; record the incident version, host environment, time window, and reproducible steps.
2. Identify the behavior owner and failure boundary before choosing automated tests or real-device/IDE/external-process alternative verification.
3. If automation would bind only to private implementation or require a test-only seam, do not add it; retain exception logs, reproduction steps, and judgment criteria.
4. After a fix, verify at the same failure boundary and add normal-path evidence for conditions outside the fix trigger.
5. Apply the Section 2.3 counterevidence gate again before concluding; verify that fix signals match user-observable outcomes rather than treating a newly added log line as the result.

---

## 7. Related documents

- Core compile flow and fallback: `02_compile_core.md`.
- Source/Kotlin/Dex: `02_compile_source.md`.
- Release obfuscation mapping: `02_compile_obfuscation.md`.
- ConstRef: `03_deploy_const_ref.md`.
- IDE lifecycle: `04_engineering_ide.md`.
- Jugg Debug attach: `04_engineering_debug_attach.md`.
- Project snapshots and APK ownership: `04_engineering_project.md`.
- Compatibility and command output: `04_engineering_compat.md`.
- Testing and verification: `06_testing.md`.
