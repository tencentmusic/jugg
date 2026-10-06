# Plugin Runtime Investigation

> Last checked: 2026-10-07
> If this page conflicts with implementation or the incident's installed version, follow that evidence.

## 1. Start with the incident, not the surface message

For a plugin runtime failure, capture the incident time window, installed plugin/Android Studio/Android/Kotlin versions, project and device, and the exact user action. Read the surrounding Jugg log before and after the visible error, then align `idea.log`, device logs, process state, and thread dumps on the same clock. A `[ClassName]` tag identifies a log producer, not necessarily the component that decided the bad state. Follow its inputs and caller to the behavior owner before selecting a fix.

Separate three evidence levels:

| Evidence | What it establishes |
|---|---|
| Raw stack, command output, DEX/APK, actual device process, thread dump, or source branch | A fact at that layer, provided time and version match. |
| UI/CLI summary, wrapper exception, aggregate deploy state, or a “success” log | The producer's classification; underlying cause and user outcome need separate checks. |
| Root-cause and fix claim | An inference requiring raw evidence or verified producer logic. |

Before concluding, name one observable result that would weaken the leading explanation, search for it in the available scene, and account for conflicts. Limit claims to the observed time, version, host, and call layer. Missing evidence supports a narrower conclusion, not certainty. For a code change, `06_testing.md` defines verification policy; reproduce the failure first and check both the fixed boundary and an unaffected normal path.

## 2. Files and logs to preserve

`JuggPathManager` defines project paths. IDEA writes under `{projectDir}/build/jugg/log/`; standalone writes under its deliberately spelled `standlone_cli/` subdirectory. Read the timestamped `compile_YYYY-MM-DD_HH-mm-ss.0.log` for primary evidence; `compile_latest.log` and `compile_latest-1.log` are shortcuts whose target can change. The standalone `standalone_startup.log` captures daemon-start stdout/stderr and is overwritten on the next CLI-initiated startup. Ordinary standalone jobs append to a Runtime segment; a successful full Gradle build may start another segment. Align segments and both Runtime logs when ownership or locks matter.

| Scene evidence | Location / use |
|---|---|
| Compile/deploy logs | `build/jugg/log/compile_*.log` and `build/jugg/log/standlone_cli/compile_*.log`. Millisecond timestamps, Java levels (`FINE`/`INFO`/`WARNING`/`SEVERE`), and class tags support cross-log alignment. |
| Compile input/output | `build/jugg/build/staging/`; `build/jugg/classpath/{root,apk,libraries}/`. Capture before another Run replaces staging. |
| Project and compile context | `build/jugg/database/project_infos.db/` and `compile_context.db/`, including `complete_flag`, `module_builds.json`, and `full_build_info.json`. |
| Deploy state | `build/jugg/database/deploy_history.db/`, APK databases under `database/apk/`, deployment cache, device overlay and installed APK. |
| Host and lock state | `idea.log`, `threadDumps-freeze-*`, an on-scene `jcmd <pid> Thread.print -l`, `build/jugg/runtime.lock.owner.json`, and `~/.jugg/locks/global.lock`. |
| Gradle reader and remote work | `{projectDir}/.gradle/jugg/` generated reader/runtime JAR and `build/jugg/tmp/diff/`; preserve remote Kotlin/Gradle caches before cleanup when relevant. |

`tools/collect_jugg_scene.command <projectDir>` collects logs, APKs, R jars, device crash/logcat, installed APKs, and overlay artifacts; its `meta/adb_resolution.txt` records ADB selection. If the user lacks this repository, use `tools/collect_jugg_scene_prompt.md` for the affected project. Capture before clean, reinstall, data clear, or another Run. Issue reporting is a separate allowlisted/redacted bundle flow described in `05_utilities.md`; it is not a substitute for the intact local incident scene.

For a standalone remote build, begin at `build/jugg/log/standlone_cli/compile_latest.log` and join login, sync, Gradle, and artifact fetch by `RemoteGradleCompileClient` command ID. `Standalone Runtime is non-interactive` indicates unavailable directly usable SSH credentials or iFT authentication requiring interaction. The log omits the raw remote command and restricts exposed environment paths; request the relevant phase and configuration state, not plaintext credentials or an entire environment.

## 3. Route by symptom and discriminating evidence

| Symptom | First distinction | Owner / topic |
|---|---|---|
| IDE freeze or startup stall | Align `uiFreezeStarted` / `InvocationEvent has timed out`, Jugg work, `idea.log`, and an EDT thread stack. A log gap or active ConstRef scan alone does not prove Jugg held the EDT. Compare `waitCost=` / `Runtime lock contention` with owner Runtime, PID, command, job ID, and wait duration. | `IdeaFileChangeMonitor`, `FileChangeManager`, `TaskRunnerManager`; `04_engineering_ide.md` and `03_deploy_const_ref.md`. |
| ConstRef cache exception at startup | Check DB rebuild and `fallback to no-op const-ref`. Constructor-time cache failure should not stop manager creation; initialization failure disables ConstRef for this process, while a later operation failure is local. | `ConstRefEngine`, `ConstRefCacheDatabase`; `03_deploy_const_ref.md`. |
| Changes appear ignored or full Gradle fallback starts | Compare VFS/Git changed-file registration, `preprocessIncrementalCompile`, history, and actual compile command before deleting state. | `JuggCompilerHelper`, `DeployFileManager`; `02_compile_core.md`. |
| R class exists but one resource field is absent | Verify the missing field in `R$...` and actual javac/kotlinc classpath order. Same-named module Gradle R jars may shadow one another; compare `compile_r_class_jar` and `compile_only_not_namespaced_r_class_jar` candidates. | `BaseCompileContext.getGradleRFilePaths()`; `02_compile_source.md` and `04_engineering_project.md`. |
| Gradle clean cannot delete `R.jar` on Windows | First identify which process holds the JAR. If Android Studio owns the handle after Jugg javac, check whether `JavaCompilerInvoker` closed its per-compilation file manager; the symptom alone does not prove Jugg owns the handle. | `JavaCompilerInvoker`; `02_compile_source.md`. |
| New Flutter asset ignored | Check pre-filter change detection, Git no-record result, Flutter `inputFiles` / pubspec declarations, and `excludedDirs` before assuming arbitrary assets belong to the build. | `FileChangesHandler` and Gradle Flutter input reader; `02_compile_core.md`, `04_engineering_project.md`. |
| Compile context says `not gradle compile yet` after upgrade | Compare `complete_flag`, `module_builds.json` version, full-build info, and recovery log. A missing marker is not repaired by inventing one. | `CompileContextDb`; `04_engineering_project.md`. |
| `Git check after compile is still running` | This is a debug notice: this Run does not wait for an unfinished Git follow-up. If the check completes during compilation and finds newly uncompiled files, Jugg recompiles once in the same Run; late changes can remain pending for another Run. | `GitChangesCompileChecker`, `JuggCompilerHelper`; `02_compile_core.md`. |
| Slow APK DB initialization | Compare `database all init finish, cost Xms` with APK size, isolated-parser logs, and `database/apk/` size. | APK parser/database; `05_utilities.md`. |
| `source_files.db` repeatedly rebuilds | Compare DB with `database/source_files.rebuild_at`. Only a completed create/rebuild + source-dir update writes the stamp; missing, invalid, >14-day-old, or far-future stamps trigger rebuild. A failed rebuild does not prove freshness. Check deletion errors and `SQLITE_BUSY` across Runtimes before cleanup. | `SourceFileManager`, `SourceFileDatabaseSqLiteHelper`. |
| Android Studio Debug output but no breakpoint | `WAITING` is attach readiness, not VM connection. Require `Connected to the target VM`, an active Debug window, and breakpoint suspension. | `04_engineering_debug_attach.md`. |
| Android Studio reports `NO_DEPLOYABLE_APP` while app runs | Compare IDE `ideClientPids` and same-window DDMLib logs with device `pidof` and `run-as`. Missing IDE Client does not prove missing process or unavailable Direct Overlay. After ordinary Direct Overlay success, verify restart and final `HOT_FIX` / `App restarted` result. | `DeployStateManager`, `DirectOverlaySwapTransport`; `03_deploy_core.md`. |
| Resource deployment OOM then ZipFS error | Compare APK rewrite heap/byte logs and temporary ZipFS path; distinguish original OOM from stale filesystem handling on later Runs. | `ResourceApkModifier`; `03_deploy_core.md`, `05_utilities.md`. |
| System app install/signature/privilege failure | Compare device `codePath`, package flags, and certificates; default `pm install` does not place an APK in `/system`. | `03_deploy_system_app.md`. |
| Windows command output garbles text | Preserve raw bytes before interpreting replacement characters as source text; strict UTF-8/GBK decoding is in the output reader. | `ProcessOutputReader`; `04_engineering_compat.md`. |

For Kotlin compiler plugin, hidden-API classpath, shaded `JavaVersion`, worker close, and toolchain errors, use `02_compile_source.md` with the exact exception and logged compiler arguments. Do not treat all `INTERNAL_ERROR` cases as one failure. For remote Gradle, verify synchronized source and project-info dry-run inputs before attributing an error to Kotlin itself.

## 4. Release crash evidence boundary

Confirm the current variant's actual `minifyEnabled` and the matching mapping source before blaming obfuscation. A leftover `outputs/mapping/<variant>/mapping.txt` does not make an unminified variant minified. Compare installed APK/baseline DEX with staging DEX and the actual receiver or reflected type:

| Failure | Distinguishing comparison |
|---|---|
| Annotation/reflection lookup failure | Annotation type descriptors on class, method, and field; reflected method presence/visibility. |
| `NoClassDefFoundError` | Class references in `const-class`, arrays, exception tables, and other caller DEX instructions. |
| `IllegalAccessError` / `IncompatibleClassChangeError` | Member access flags, direct/virtual sections, and invocation form. |
| `AbstractMethodError` | Receiver class, mapped signatures, interface default-method forwarding (`$-CC` / `$DefaultImpls`), D8 variant `minApi`, and external superclass chain. |
| `NoSuchMethodError` in a Kotlin facade/keep class | R8 synthesized entry name, arguments, and identity mapping coverage. |

Neither exception name nor absence of a target class in one log proves the mapping gap; verify collection scope. `02_compile_obfuscation.md` owns the current DEX/remapping rules. The historical annotation-type repair is recorded in `docs/task/2026-03/dex_obfuscator_annotation_type_mapping_fix.md`.

## 4.7 Intermittent Kotlin IR dispatch-receiver assertion

When `BackendException: Exception during IR lowering` includes `copyValueParametersToStatic` and `Dispatch receiver type A is not a subtype of B`, first verify the real source/APK/Dex inheritance chain, then distinguish remote sync and Kotlin/Gradle incremental state. The JOOX incident `jugg_scene_JOOX_Android_ext_20260911_144438` failed in remote `:wemusic:compileDebugKotlin` on Kotlin 2.0.21. It had a valid indirect inheritance chain and succeeded after clean; earlier Jugg incremental compilation had reached the dependent sources. That evidence weakens a stable source-type error or missed impact-analysis claim, but the remote Kotlin cache at failure time was unavailable, so a specific corrupt entry or compiler race was not proven. A related Kotlin issue is a hypothesis, not a guaranteed fix.

Before clean, preserve the remote module's `build/kotlin/compileDebugKotlin` and `build/tmp/kotlin-classes`, project `.gradle/kotlin` and `.kotlin/errors`, plus the full failing command and `--info` output. Retry the original command once without source changes; then try the affected Kotlin task with `-Pkotlin.incremental=false`; then a module-level clean. Recovery only after disabling incremental compilation strengthens the incremental-state explanation. A reproducible failure after clean, a genuinely invalid inheritance chain, or missing synchronized sources points elsewhere. Do not automatically clean the whole project or add broad retry for arbitrary IR errors.

## 5. Finish with observable verification

Preserve the original failure signal and scene, identify the behavior owner, and select automated or manual evidence through `06_testing.md`. After a fix, rerun the affected flow and an unaffected normal path. A new log line is not proof that the user-visible outcome occurred: verify process restart, VM connection, APK/DEX result, or compile/deploy completion at the layer the claim concerns. Apply the counterevidence gate again before closing the investigation.
