# Engineering: Compatibility and Standalone Runtime

> Last verified: 2026-10-27
> Consistency rule: If documentation conflicts with code, code takes precedence.

Standalone can start with zero projects to serve global `version`, `list-projects`, and `settings`. Settings remain user-global across Hosts sharing the same root. Its project recovery boundary consumes pending setting effects under the existing project lock before using a baseline; see [05_utilities.md](05_utilities.md).

## 1. Scope

This page covers Android Studio API isolation, the IDE-free runtime and Bundle boundary, and the two-phase CI command. Project-model facts belong to `04_engineering_project.md`; IDE task UI belongs to `04_engineering_ide.md`.

## 2. Source Owners

| Boundary | Owner |
|---|---|
| IDE deployer selection and fallback | `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/AsDeployerCompat.kt`; `deploy_compat/interface/src/main/java/com/sickworm/intellij/jugg/deploy/run/IAsDeployerCompat.kt` |
| Host-neutral deploy API and session binding | `deploy_compat/interface/src/main/java/com/sickworm/intellij/jugg/deploy/api/DeployApiTypes.kt`; `deploy_compat/interface/src/main/java/com/sickworm/intellij/jugg/deploy/run/` (`IApplyChangesExecutor.kt`, `JuggDeployCompatTypes.kt`) |
| Studio-specific adapters | `deploy_compat/v_*/src/main/java/com/sickworm/intellij/jugg/deploy/run/` |
| Standalone Apply Changes | `deploy_compat/standalone_deployer/src/main/java/com/sickworm/intellij/jugg/deploy/run/`; `main/src/main/java/com/sickworm/intellij/jugg/project/runtime/JuggResourceManager.kt` |
| Standalone project lifecycle | `cmd_line/src/main/java/com/sickworm/intellij/jugg/cmdline/standalone/` (`JuggDaemon.kt`, `StandaloneProjectRegistry.kt`, `StandaloneProjectServices.kt`) |
| Cross-runtime locking | `main/src/main/java/com/sickworm/intellij/jugg/project/runtime/` (`TaskRunnerManager.kt`, `ExecutionLockManager.kt`) |
| Bundle transaction and bootstrap | `cmd_line/src/main/java/com/sickworm/intellij/jugg/cmdline/standalone/StandaloneRuntimeInstaller.kt`; `cmd_line/standalone_bootstrap/src/main/java/com/sickworm/intellij/jugg/bootstrap/StandaloneBootstrap.java` |
| CI commands | `cmd_line/src/main/java/com/sickworm/intellij/jugg/cmdline/base/BuildGradleBaseCommand.kt`; `cmd_line/src/main/java/com/sickworm/intellij/jugg/cmdline/incremental/BuildIncrementalApkCommand.kt` |

## 3. Android Studio Compatibility

`AsDeployerCompat` orders implementations newest to oldest: Stingray, Rabbit, Quail, Panda, Otter 2 Feature Drop, Narwhal Feature Drop, Narwhal, Meerkat, Iguana, Hedgehog, Giraffe, and Chipmunk. It selects an exact version when possible, the highest known adapter for a newer IDE, and Chipmunk for an older one. A capability call retries other adapters only for `NoSuchMethodError`, `NoSuchFieldError`, `NoClassDefFoundError`, or `IncompatibleClassChangeError`; other failures remain business failures. If every adapter has a compatibility error, it throws the original preferred-adapter error. `setAllowSelectDevice()` is an early special case that visits every adapter.

Device eligibility also depends on the compatibility-deployment setting: `JuggManager.initializeRuntime()` calls `IAsDeployerCompat.updateMinApi()` to lower the minimum from Android 11 (API 30) to Android 8 (API 26) when compatibility deployment is enabled. An older device being accepted or rejected therefore cannot be inferred from the Studio adapter alone.

Quail removed the old root `com.android.tools.deployer.*` runtime. Do not reflect over every `IAsDeployerCompat` signature at startup: resolving a missing type then can prevent project opening before capability fallback is possible. The main IDE path must use Jugg-owned `IDevice`, APK, DEX, overlay, cache-entry, and exception wrappers rather than link to an old deployer model or `StudioFlags` field. `IdeaDeviceAdbClient` owns shell, push, uninstall, PID, and arch transport through `IDeviceAdb`; these operations are not compatibility-facade methods. `IRuntimeDevice` identifies the host runtime, not an adapter version, and `Apk.runtimeObject` is a transient process-local attachment rather than a converter-owned origin map.

Successful `JuggInstallSession` creation binds the actual `IApplyChangesExecutor`. Later install, overlay, cache, swap, and debugger calls use that executor, and the IDEA memory cache is separated by executor identity. The disk `JuggDeploymentCacheStore` contains Jugg-owned APK paths and overlay snapshots; the bound executor recreates its own runtime objects on read. Re-dispatching a stateful call through the preferred adapter can mix incompatible runtimes.

Version-specific fault boundaries worth checking before changing the facade:

| Symptom or version | Boundary |
|---|---|
| Quail installer reports `AdbSession is required for installation` | Quail `AdbClient` construction must use the application `AdbSession` for daemon, delta, and full-install paths. |
| Stingray 1 Canary 2 install or overlay linkage fails | Canary 1 and Canary 2 share API build `263.7307.200`. Starting at full build `263.7307.200.2631.16977666`, the Stingray adapter looks up the selected serial in the application ADB session's connected-device tracker for up to five seconds and constructs the device holder from that connected device. The synchronous lookup keeps coroutines in the IDE class loader. The adapter converts Jugg DEX changes to the relocated `com.android.tools.deployer.DexClass` when building an overlay. |
| Rabbit `AdbClient` linkage fails | Rabbit changes the device argument from `IDevice` to `DeviceHolder`; the override is at `createAdbClient()`. |
| Quail 1 versus Quail 4 skip-verification linkage | Use `AdbClient.getSkipVerificationOption()` rather than either `InstallOptions.Builder.setSkipVerification()` signature. |
| Newer IDE loses install mode or debugger attach | Read install mode and attach through `IAsDeployerCompat`; Giraffe and later wait for the debugger client and ask Android Studio to create its own debug session. |
| Selected device differs from the IDE | Meerkat–Quail use `DeployTargetContext` selection. If any selected AVD is stopped or unresolved, return no devices; an ADB-connected list is not a replacement. |

Run Configuration identity and Gradle task path are separate values. `SuggestRunConfiguration` first reflects `GradleProjectPathKt.getGradleProjectPath(Module)`, then tries Bumblebee's `AndroidGradleUtil`, and only then parses `module.name`. Identity distinguishes composite builds, while task paths retain literal segments such as `:zxphone5.0`; never turn identity dots back into colons. Gradle Sync subscription uses reflected three-argument `GradleSyncState.subscribe(...)` because the owner changed from class to interface. Published bytecode must not statically reference `GradleSyncListenerWithRoot`. More generally, Marketplace Plugin Verifier checks static references even inside a `try/catch`; inspect packaged bytecode when adapting an API owner that moved between IDE versions.

### Supporting a new Android Studio release

1. Use the requested full build when available. For a codename alone, select its latest official stable build, or its latest published preview if it has no stable release. Check local installations first, then use the [official archive](https://developer.android.com/studio/archive) or [preview downloads](https://developer.android.com/studio/preview). Use the published link for the host architecture, verify the published checksum when available, and use a browser if the command-line download fails. On macOS, mount the DMG read-only, copy the application into a separate directory with `ditto`, then detach it. Record the release, channel, architecture, source, API build and full build from the distribution metadata and runtime log.
2. Reproduce the failure or compare the failing member and its owner with `javap` on the target and previous builds. Check the existing adapter first. If a new module is needed, register it in the build and version dispatcher. Use a separate checkout for real JARs. The real-API switch affects every module. A temporary Gradle configuration can supply the target module with its distribution's JARs and JBR compiler while preserving the output bytecode target and using Stubs for older adapters.
3. Generate the Stub from the real-API artifact, clean-build in Stub mode, run targeted tests and package the plugin. Check that the ZIP contains the adapter. Record which modules used real inputs in the Stub comparison.
4. Start the target IDE with its bundled JBR and isolated config, system, plugins and log directories. Replace the entire test plugin directory from the ZIP. Sync a small Android project with a compatible Gradle JDK and registered SDK, select the test emulator, and check plugin loading and adapter selection. Run a full build, install and launch, then edit a visible method and repeat through incremental deployment. Verify the changed app, successful compile and deployment, and that the second run used the incremental path. Retain both logs. Exercise the nearest supported build and both sides of any full-build threshold, recording ABI-only checks and runtime scenarios still unverified.

### Stub API acceptance

Compat modules compile against versioned `deploy_compat/stub_api/v_*/stubapi.jar`; real Studio JARs are selected temporarily via `switch_api.sh real <jar-dir>` in ignored `deploy_compat/local.properties`. For a new adapter, create the module, adapt and verify with local real JARs, generate its Stub, then return to Stub mode and clean-build. The generator retains binary signatures, Kotlin metadata, annotations, and inline constants while removing ordinary method bodies. Rabbit platform input needs ASM support for Java 25 class files, but emitted Stubs must stay at Java 17 for this repository's build.

Final acceptance is `./deploy_compat/verify_stub_api.sh <real-api-jugg-repo>` from a Stub checkout with a separate matching real-JAR checkout. Review reported source differences, then require `MATCH` for every compat module's clean-built class entries and normalized Android/IntelliJ bytecode references, including invocation opcode. Compile success or stale JAR comparison alone does not establish equivalence. Reports are under `build/stub-api-verify/`.

## 4. Standalone Runtime and Shared Resources

`deploy_compat/standalone_deployer` fixes the Quail 1 protocol and installer resources while recompiling its required closure for Java 11. It uses ddmlib directly, not Quail IDE's adblib session. The host-neutral `DexClass` must retain D8 field-reinitialization state through conversion. Class-only optimistic swap avoids Activity restart; resource full swap refreshes `AssetManager/Resources` in the live process and expects one Activity restart. This module supplies an executor and tooling; `StandaloneProjectServices` supplies the separate project lifecycle.

`JuggResourceManager` prepares four-ABI installers plus protocol/license metadata at `~/.jugg/resources/deployer/quail`, replacing each file atomically under the Global Resource Lock. Its one-directory overwrite contract requires schema `1`, protocol hash `c52d6b25`, and stable installer paths; incompatible protocol or directory changes need a new migration boundary. The resource manager does not hash-check embedded resources at runtime, so a Java/installer mismatch first surfaces when the daemon starts. AAPT2 shares only the `resources` root, with its own versioned path.

The complete `:cmd_line:standaloneBundle` is content-addressed by JAR SHA-256 and Java 11-compatible. IDEA's plugin Bundle is a delta: it omits only JARs byte-identical to `jugg/lib`, and `StandaloneBundleInstallService` restores them before the common installation transaction. Standalone-only CLI, ddmlib, `base_api`, and standalone-deployer JARs stay outside `jugg/lib`. The installer requires a complete JDK 11+, Python 3.7+, valid manifest paths, symlinks, and hashes; it publishes immutable files before atomically switching `standalone_load_manifest.json`. It then stops old daemons under the same Jugg root. Bootstrap shares the portable `JuggGlobalPathManager` source with main and packages its Kotlin runtime dependencies, so `.juggdir` is resolved before runtime JAR selection. Bootstrap loads the declared JAR order and returns class-load or startup failure directly, retaining the active manifest; reinstall is the recovery path. IDEA refreshes an IDEA-managed installed CLI only when its embedded `toolingReleaseBuildId` differs, not on every compatible plugin update.

CLI startup selects `JAVA_HOME/bin/java`, then PATH `java`; compilation uses `ANDROID_HOME`/`ANDROID_SDK_ROOT` or the target project's `local.properties` SDK. The shared Python wrapper selects a usable Python 3.7+ on each invocation. Startup failures print a tail and path for `build/jugg/log/standlone_cli/standalone_startup.log`.

On POSIX, the installed standalone launcher raises its soft open-file limit toward 65536, bounded by the hard limit, before starting Java and prints the resulting limit. If WatchService or compilation fails with file-handle exhaustion, compare that startup value with the host limit before attributing the failure to project-model recovery.

### Project registration, locks, and idle status

The CLI launches or reuses a daemon for its target project. `JuggDaemon` registers startup projects, then serves MCP. `StandaloneProjectRegistry` lazily registers a project only after a valid project-scoped MCP call: global tools, malformed calls, and invalid project paths do not initialize it. Registration is deduplicated by canonical project path; an initialization failure returns `PROJECT_NOT_INITIALIZED`. `StandaloneProjectServices` has a Gradle-only model and shared compile/deploy services, and creates its first compile profile on demand from Gradle project information.

Project writes follow logical task owner → Project Runtime Lock → Global Resource Lock. `RuntimeTaskCoordinator` serializes unrelated work inside one runtime while child tasks inherit an owner's context across threads. The project file lease coordinates IDEA and standalone processes; same-runtime nested tasks share it by reference count. Global Resource Lock protects Jugg-owned files under `~/.jugg` and is a synchronous leaf: do not acquire a project lock, wait on network/process/future, or call business code while holding it. Long work such as hot-update download belongs outside the global critical section. Owner sidecars and contention logs identify the process, command, and project to inspect.

Status tries the local logical owner and project file lease without waiting; if busy, it returns a read-only snapshot instead of entering mutable project state. After runtime ownership changes, the next acquired project write reloads Compile Context, deployment history, APK/Git state, and in-memory caches before acting. The daemon's default four-hour idle timer measures external MCP activity; active jobs, project writes, or update downloads defer exit. An idle daemon closing is not evidence that a build failed.

## 5. IDE-Free CI Commands

`CmdLine` selects `CmdPlatformApi` instead of IDE `IdeaPlatformApi`; `platform_compat/base_api` provides a minimal IntelliJ/log4j surface without `com.android.*`. The legacy `buildGradleBase` command clears its Jugg baseline, performs a full Gradle build, and saves project, APK, classpath, history, database, and source-index state. `buildIncrementalApk` uses that baseline and only caller-supplied `changedFiles`, compiles and merges DEX, then writes an APK. It does not infer a VCS diff.

The incremental command requires an explicit `changedFiles` parameter. An empty value exports the baseline APKs without initializing compilation or marking the baseline dirty. Nonempty inputs must exist within the project or a modeled module and be fully recognized, excluding build files. Before compilation the command consumes the baseline copy by writing `.dirty`. Use separate copies for independent builds. An explicit `skipSign=true` skips signing checks, signing and verification when rewriting a temporary APK. Alignment and atomic replacement still run. CI must sign and verify before distribution or installation.

`CiProjectInfo` restores included-build snapshots relative to the snapshot manifest. Duplicate module roots keep primary backup paths, and conflicting roots for the same module name fail. `getChangedFiles` reads baseline metadata without initializing APK data or the ConstRef index. It reports the main commit range and each modeled repository's full-build history commit through its current HEAD in `repositories`, including unchanged ranges. Each changed file carries its gitRoot. Unknown entries retain type Unknown and an empty module. A missing external baseline commit fails the query. See [ConstRef baseline scan and restore](03_deploy_const_ref.md#ci-baseline-index).

`findNearestBaseCommit` follows first parents. CI owns checkout, clean-tree policy, artifact storage and incremental eligibility. The demo in `cmd_line/src/demo/jugg_ci/build.py` finds candidate baselines per repository, deduplicates records and validates archived history before selecting the eligible candidate with fewest effective changes. Older records use main-repository selection. Build files, unsupported changes, more than 100 files, or configured annotation changes can require Gradle. The sample ignores a root README and can export an unchanged baseline. Failed incremental compilation clears partial APKs before one Gradle attempt. The HTTP service accepts ZIPs at POST /artifacts and records at PUT /base-builds, retaining the latest 100 per project. `demo_flow/run.py` runs successive commits in one prepared project. Inspect build-result.json for actual outputs, dry-run-result.json for previews and flow-results.json for scenario history.

## 6. Diagnostic Start Points

| Observation | Inspect first |
|---|---|
| Android Studio linkage error | `AsDeployerCompat` selected adapter, compatibility fallback logs, then that `deploy_compat/v_*` call site. |
| Adapter compiles with Stub but fails in an IDE | Real-JAR verification result and published bytecode owner/opcode references. |
| Standalone installer or bootstrap fails after update | Active manifest, ordered JARs, SHA-256/Java version, daemon startup log, and Quail protocol version. |
| Project-scoped MCP call returns `PROJECT_NOT_INITIALIZED` | Request validation and `StandaloneProjectRegistry.initialize()` failure before inspecting compile/deploy. |
| Status appears stale while a build runs | Check lock owner and read-only busy response; then check whether runtime ownership changed and recovery ran. |
| CI incremental reports `.dirty` | The baseline copy was consumed; repeat from untouched `buildGradleBase` output. |

## 7. Related Documents

- `04_engineering_project.md` — project snapshots and merge.
- `04_engineering_ide.md` — IDE task and host lifecycle.
- `05_utilities.md` — CLI setup and command portability.
- `08_mcp_design.md` — MCP validation and tool responses.
- `03_deploy_core.md` and `03_runtime_jvmti.md` — deployment and runtime effects.

## 8. IDE module-path and local-tool compatibility

Panda first reflectively reads ExternalSystemApiUtil.getExternalProjectPath(Module), accepting nonempty absolute paths, then retains Gradle DSL/content-root/module-file fallback. Its GradleBuildModel.moduleRootDirectory can misidentify source-set main as a Gradle project or retain unresolved settingsDir. Other adapters retain their path rules. Quail/Rabbit non-Android KMP JVM modules can use confirmed GRADLE External System association and absolute path for minimal IdeModuleInfo; do not guess SDK/Manifest/build directory, and keep source-set names. Failed reads debug and omit that module only.

CopyEmbeddedDistributionPaths reflectively checks IdeInfo.isAndroidStudio at its development-path assertion; failed reflection debugs and uses ApplicationInfo productCode AI, while a real false stays false. Installed/downloaded directory search order is unchanged.

Windows tool setup errors distinguish missing tools from start/probe/conversion errors and report controlled paths/reasons/exit codes. Only confirmed missing tools suggest installation/PATH adjustment. Sharing-conflict exit code does not identify an owner. See `04_engineering_project.md` for the one preparation-only retry; neither version probing nor actual rsync transmission is replayed.
