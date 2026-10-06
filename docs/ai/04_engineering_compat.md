# Engineering: Compatibility and Standalone Runtime

> Last verified: 2026-10-07
> Consistency rule: If documentation conflicts with code, code takes precedence.

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

`AsDeployerCompat` orders implementations newest to oldest: Rabbit, Quail, Panda, Otter 2 Feature Drop, Narwhal Feature Drop, Narwhal, Meerkat, Iguana, Hedgehog, Giraffe, and Chipmunk. It selects an exact version when possible, the highest known adapter for a newer IDE, and Chipmunk for an older one. A capability call retries other adapters only for `NoSuchMethodError`, `NoSuchFieldError`, `NoClassDefFoundError`, or `IncompatibleClassChangeError`; other failures remain business failures. If every adapter has a compatibility error, it throws the original preferred-adapter error. `setAllowSelectDevice()` is an early special case that visits every adapter.

Device eligibility also depends on the compatibility-deployment setting: `JuggManager.initializeRuntime()` calls `IAsDeployerCompat.updateMinApi()` to lower the minimum from Android 11 (API 30) to Android 8 (API 26) when compatibility deployment is enabled. An older device being accepted or rejected therefore cannot be inferred from the Studio adapter alone.

Quail removed the old root `com.android.tools.deployer.*` runtime. Do not reflect over every `IAsDeployerCompat` signature at startup: resolving a missing type then can prevent project opening before capability fallback is possible. The main IDE path must use Jugg-owned `IDevice`, APK, DEX, overlay, cache-entry, and exception wrappers rather than link to an old deployer model or `StudioFlags` field. `IdeaDeviceAdbClient` owns shell, push, uninstall, PID, and arch transport through `IDeviceAdb`; these operations are not compatibility-facade methods. `IRuntimeDevice` identifies the host runtime, not an adapter version, and `Apk.runtimeObject` is a transient process-local attachment rather than a converter-owned origin map.

Successful `JuggInstallSession` creation binds the actual `IApplyChangesExecutor`. Later install, overlay, cache, swap, and debugger calls use that executor, and the IDEA memory cache is separated by executor identity. The disk `JuggDeploymentCacheStore` contains Jugg-owned APK paths and overlay snapshots; the bound executor recreates its own runtime objects on read. Re-dispatching a stateful call through the preferred adapter can mix incompatible runtimes.

Version-specific fault boundaries worth checking before changing the facade:

| Symptom or version | Boundary |
|---|---|
| Quail installer reports `AdbSession is required for installation` | Quail `AdbClient` construction must use the application `AdbSession` for daemon, delta, and full-install paths. |
| Rabbit `AdbClient` linkage fails | Rabbit changes the device argument from `IDevice` to `DeviceHolder`; the override is at `createAdbClient()`. |
| Quail 1 versus Quail 4 skip-verification linkage | Use `AdbClient.getSkipVerificationOption()` rather than either `InstallOptions.Builder.setSkipVerification()` signature. |
| Newer IDE loses install mode or debugger attach | Read install mode and attach through `IAsDeployerCompat`; Giraffe and later wait for the debugger client and ask Android Studio to create its own debug session. |
| Selected device differs from the IDE | Meerkat–Quail use `DeployTargetContext` selection. If any selected AVD is stopped or unresolved, return no devices; an ADB-connected list is not a replacement. |

Run Configuration identity and Gradle task path are separate values. `SuggestRunConfiguration` first reflects `GradleProjectPathKt.getGradleProjectPath(Module)`, then tries Bumblebee's `AndroidGradleUtil`, and only then parses `module.name`. Identity distinguishes composite builds, while task paths retain literal segments such as `:zxphone5.0`; never turn identity dots back into colons. Gradle Sync subscription uses reflected three-argument `GradleSyncState.subscribe(...)` because the owner changed from class to interface. Published bytecode must not statically reference `GradleSyncListenerWithRoot`. More generally, Marketplace Plugin Verifier checks static references even inside a `try/catch`; inspect packaged bytecode when adapting an API owner that moved between IDE versions.

### Stub API acceptance

Compat modules compile against versioned `deploy_compat/stub_api/v_*/stubapi.jar`; real Studio JARs are selected temporarily via `switch_api.sh real <jar-dir>` in ignored `deploy_compat/local.properties`. For a new adapter, create the module, adapt and verify with local real JARs, generate its Stub, then return to Stub mode and clean-build. The generator retains binary signatures, Kotlin metadata, annotations, and inline constants while removing ordinary method bodies. Rabbit platform input needs ASM support for Java 25 class files, but emitted Stubs must stay at Java 17 for this repository's build.

Final acceptance is `./deploy_compat/verify_stub_api.sh <real-api-jugg-repo>` from a Stub checkout with a separate matching real-JAR checkout. Review reported source differences, then require `MATCH` for every compat module's clean-built class entries and normalized Android/IntelliJ bytecode references, including invocation opcode. Compile success or stale JAR comparison alone does not establish equivalence. Reports are under `build/stub-api-verify/`.

## 4. Standalone Runtime and Shared Resources

`deploy_compat/standalone_deployer` fixes the Quail 1 protocol and installer resources while recompiling its required closure for Java 11. It uses ddmlib directly, not Quail IDE's adblib session. The host-neutral `DexClass` must retain D8 field-reinitialization state through conversion. Class-only optimistic swap avoids Activity restart; resource full swap refreshes `AssetManager/Resources` in the live process and expects one Activity restart. This module supplies an executor and tooling; `StandaloneProjectServices` supplies the separate project lifecycle.

`JuggResourceManager` prepares four-ABI installers plus protocol/license metadata at `~/.jugg/resources/deployer/quail`, replacing each file atomically under the Global Resource Lock. Its one-directory overwrite contract requires schema `1`, protocol hash `c52d6b25`, and stable installer paths; incompatible protocol or directory changes need a new migration boundary. The resource manager does not hash-check embedded resources at runtime, so a Java/installer mismatch first surfaces when the daemon starts. AAPT2 shares only the `resources` root, with its own versioned path.

The complete `:cmd_line:standaloneBundle` is content-addressed by JAR SHA-256 and Java 11-compatible. IDEA's plugin Bundle is a delta: it omits only JARs byte-identical to `jugg/lib`, and `StandaloneBundleInstallService` restores them before the common installation transaction. Standalone-only CLI, ddmlib, `base_api`, and standalone-deployer JARs stay outside `jugg/lib`. The installer requires a complete JDK 11+, Python 3.7+, valid manifest paths, symlinks, and hashes; it publishes immutable files before atomically switching `standalone_load_manifest.json`. It then stops old daemons under the same Jugg root. Bootstrap loads the declared JAR order and returns class-load or startup failure directly, retaining the active manifest; reinstall is the recovery path. IDEA refreshes an IDEA-managed installed CLI only when its embedded `toolingReleaseBuildId` differs, not on every compatible plugin update.

CLI startup selects `JAVA_HOME/bin/java`, then PATH `java`; compilation uses `ANDROID_HOME`/`ANDROID_SDK_ROOT` or the target project's `local.properties` SDK. The shared Python wrapper selects a usable Python 3.7+ on each invocation. Startup failures print a tail and path for `build/jugg/log/standlone_cli/standalone_startup.log`.

On POSIX, the installed standalone launcher raises its soft open-file limit toward 65536, bounded by the hard limit, before starting Java and prints the resulting limit. If WatchService or compilation fails with file-handle exhaustion, compare that startup value with the host limit before attributing the failure to project-model recovery.

### Project registration, locks, and idle status

The CLI launches or reuses a daemon for its target project. `JuggDaemon` registers startup projects, then serves MCP. `StandaloneProjectRegistry` lazily registers a project only after a valid project-scoped MCP call: global tools, malformed calls, and invalid project paths do not initialize it. Registration is deduplicated by canonical project path; an initialization failure returns `PROJECT_NOT_INITIALIZED`. `StandaloneProjectServices` has a Gradle-only model and shared compile/deploy services, and creates its first compile profile on demand from Gradle project information.

Project writes follow logical task owner → Project Runtime Lock → Global Resource Lock. `RuntimeTaskCoordinator` serializes unrelated work inside one runtime while child tasks inherit an owner's context across threads. The project file lease coordinates IDEA and standalone processes; same-runtime nested tasks share it by reference count. Global Resource Lock protects Jugg-owned files under `~/.jugg` and is a synchronous leaf: do not acquire a project lock, wait on network/process/future, or call business code while holding it. Long work such as hot-update download belongs outside the global critical section. Owner sidecars and contention logs identify the process, command, and project to inspect.

Status tries the local logical owner and project file lease without waiting; if busy, it returns a read-only snapshot instead of entering mutable project state. After runtime ownership changes, the next acquired project write reloads Compile Context, deployment history, APK/Git state, and in-memory caches before acting. The daemon's default four-hour idle timer measures external MCP activity; active jobs, project writes, or update downloads defer exit. An idle daemon closing is not evidence that a build failed.

## 5. IDE-Free CI Commands

`CmdLine` selects `CmdPlatformApi` instead of IDE `IdeaPlatformApi`; `platform_compat/base_api` provides a minimal IntelliJ/log4j surface without `com.android.*`. The legacy `buildGradleBase` command clears its Jugg baseline, performs a full Gradle build, and saves project, APK, classpath, history, database, and source-index state. `buildIncrementalApk` uses that baseline and only caller-supplied `changedFiles`, compiles and merges DEX, then writes an APK. It does not infer a VCS diff.

The incremental command marks a baseline `.dirty` on first use and rejects a second run on the same copy. Every input must exist under `sourceProjectDir`, survive `FileChangesHandler` filtering, and not be a build file; it fails instead of silently dropping inputs. Copy the untouched baseline for independent result sets. The Bundle's interactive standalone daemon and these two CI commands are distinct entry paths.

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
