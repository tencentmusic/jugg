# Engineering: Compatibility Layer and Command-Line Module

> Last checked: 2026-09-08
> Consistency rule: when documentation conflicts with code, follow the code.

---

## 1. Scope

This page explains how Jugg isolates IDE/Android Studio API changes and how the IDE-free command line, platform stubs, and custom-compiler examples connect to the main path.

For install, code swap, and Direct Overlay deployment behavior, see `03_deploy_core.md`, `03_deploy_complete.md`, and `03_runtime_jvmti.md`.

---

## 2. Core Source Index

| Class/interface | File | Role |
|---|---|---|
| `AsDeployerCompat` | `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/AsDeployerCompat.kt` | Unified IDE facade. Every capability selects a preferred implementation for the current AS version and retains compatibility fallback. After session creation, the successful implementation owns this run's Apply Changes runtime. |
| `AsDeployerCompatDispatcher` | `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/AsDeployerCompat.kt` | Compatibility-method dispatcher. It tries other version implementations only for known Android Studio API linkage errors and preserves business exceptions. |
| `IAsDeployerCompat` | `deploy_compat/interface/src/main/java/com/sickworm/intellij/jugg/deploy/run/IAsDeployerCompat.kt` | Deployment compatibility interface covering AS-version differences in install sessions, swap, IDE deploy state, module info, and Java debugger attach. |
| `IApplyChangesExecutor` | `deploy_compat/interface/src/main/java/com/sickworm/intellij/jugg/deploy/run/IApplyChangesExecutor.kt` | Host-neutral Apply Changes execution surface, using only `deploy.api`-owned device, APK, overlay, arch, and logger types. |
| `DeployApiTypes` | `deploy_compat/interface/src/main/java/com/sickworm/intellij/jugg/deploy/api/DeployApiTypes.kt` | Jugg-owned deployment contracts retaining the existing call surface of `IDevice`, `Apk`, `ApkEntry`, `DexClass`, and `ByteString`. `DexClass` preserves field-reinitialization state already used in D8 swap. |
| Deploy API converters | `deploy_compat/v_chipmunk/.../LegacyDeployApiConverter.kt`, `deploy_compat/v_quail/.../QuailDeployApiConverter.kt`, `deploy_compat/standalone_deployer/.../StandaloneDeployApiConverter.java` | Convert owned types at version-API boundaries to real ddmlib/deployer/protobuf types. Device unwraps through a common runtime handle; APK carries a process-local transient runtime object directly, so converters keep no APK-origin map. |
| `JuggDeployCompatTypes` | `deploy_compat/interface/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployCompatTypes.kt` | Runtime-neutral wrappers. `JuggInstallSession` records its successful executor so installer, overlay, cache, and redefiner remain in one Apply Changes runtime. |
| `StandaloneApplyChangesExecutor` / `StandaloneDeployerResources` | `deploy_compat/standalone_deployer/src/main/java/com/sickworm/intellij/jugg/deploy/run/` | Java 11 standalone install/session/cache/optimistic-swap implementation and preflight of fixed Quail installer/protocol resources. |
| `JuggResourceManager` | `main/src/main/java/com/sickworm/intellij/jugg/project/runtime/JuggResourceManager.kt` | Maps classpath resources to a fixed global `resources` directory and refreshes atomically via temporary files under a global write lock. |
| `JuggDeploymentCacheStore` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/cache/JuggDeploymentCacheStore.kt` | Project-level disk deployment checkpoint (`build/jugg/database/deploy_cache.db`). Under project lock, persists APK paths and overlay snapshots via atomic temporary-file replacement, without AS deployer runtime types. IDEA Service retains a separate Runtime-local memoryCache. |
| `*AsDeployerCompat` | `deploy_compat/v_*/src/main/java/com/sickworm/intellij/jugg/deploy/run/` | Version-specific Android Studio API adapters. |
| `StubApiGenerator` | `tools/stub_api_generator/` | Generates versioned compile-time Stub APIs from compatibility-build reference closure and an explicit Android Studio JAR directory. |
| `IdeVersion` | `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/AsDeployerCompat.kt` | Chooses compatibility implementation from `ApplicationInfo` product code/API version. |
| `PlatformApi` | `main/src/main/java/com/sickworm/intellij/jugg/platform/PlatformApi.kt` | Global abstraction for platform capabilities used by main. |
| `IdeaPlatformApi` | `idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/IdeaPlatformApi.kt` | `PlatformApi` implementation in IDE runtime. |
| `CmdPlatformApi` | `cmd_line/src/main/java/com/sickworm/intellij/jugg/cmdline/CmdPlatformApi.kt` | `PlatformApi` implementation in command-line runtime. |
| `IDeviceAdb` / `IdeaDeviceAdb` / `IdeaDeviceAdbClient` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/IDeviceAdb.kt`, `idea/src/main/java/com/sickworm/intellij/jugg/deploy/IdeaDeviceAdb.kt`, `idea/src/main/java/com/sickworm/intellij/jugg/deploy/IdeaDeviceAdbClient.kt` | Device ADB semantic abstraction. The IDE wraps shell/push/pid/arch/uninstall through `IDevice`, rather than putting these transport capabilities on deployer compat. |
| `CmdLine` | `cmd_line/src/main/java/com/sickworm/intellij/jugg/cmdline/CmdLine.kt` | CLI entry point dispatching `buildGradleBase` / `buildIncrementalApk`. |
| `BuildGradleBaseCommand` / `BuildIncrementalApkCommand` | `cmd_line/src/main/java/com/sickworm/intellij/jugg/cmdline/` | Two-stage CI build: establish a reusable baseline, then generate an incremental APK from explicit caller-supplied changed files. |
| `StandaloneRuntimeInstaller` / `StandaloneBootstrap` | `cmd_line/.../standalone/StandaloneRuntimeInstaller.kt`, `cmd_line/standalone_bootstrap/.../StandaloneBootstrap.java` | Three-platform Bundle install transaction, active manifest, version takeover, fixed Java 11 bootstrap, and ordered classloader. Startup failure returns the exception; it does not automatically switch to an old Runtime. |
| `StandaloneEmbeddedBundle` / `StandaloneBundleInstallService` | `idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/` | SHA-256 delta pruning and pre-install restoration of IDEA's embedded Bundle. Only JARs byte-identical to plugin `jugg/lib` are reused; after restoration, the common install transaction runs. |
| `CmdExecutor` / `ProcessOutputReader` | `main/src/main/java/com/sickworm/intellij/jugg/gradle/compile/` | Command execution and raw-output reading; Windows adapts mixed UTF-8/GBK output by line. |
| `CustomCompilerManager` / `ICompilerCreator` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/custom/` | Custom compiler SPI loading and lifecycle management. |

---

## 3. Core Boundary Model

### 3.1 deploy_compat Version Layers

| Directory | Adapted version |
|---|---|
| `deploy_compat/v_rabbit` | Android Studio Rabbit |
| `deploy_compat/v_quail` | Android Studio Quail |
| `deploy_compat/v_panda` | Android Studio Panda |
| `deploy_compat/v_otter` | Android Studio Otter 2 Feature Drop |
| `deploy_compat/v_narwhal_feature` | Android Studio Narwhal Feature Drop |
| `deploy_compat/v_narwhal` | Android Studio Narwhal |
| `deploy_compat/v_meerkat` | Android Studio Meerkat |
| `deploy_compat/v_iguana` | Android Studio Iguana |
| `deploy_compat/v_hedgehog` | Android Studio Hedgehog |
| `deploy_compat/v_giraffe` | Android Studio Giraffe |
| `deploy_compat/v_chipmunk` | Android Studio Chipmunk |

`AsDeployerCompat.compatImplList` must be ordered newest to oldest. Use an exact implementation when the IDE version matches; if above the highest known version, use the highest and warn; if below the lowest, fall back to Chipmunk.

Each compat version normally uses `deploy_compat/stub_api/v_*/stubapi.jar` as `compileOnly`; the repository no longer stores real Android Studio JARs. For a new version, run `create_compat_module.sh`, explicitly select local real JARs with `switch_api.sh real <jar-dir>`, adapt and verify in the real IDE, generate Stub with `generate_stub_api.sh`, then return to `switch_api.sh stub`. Scripts do not autodetect Android Studio installations. The local choice is written to ignored `deploy_compat/local.properties`.

The Stub helper retains classes, inheritance, member descriptors, generics, Kotlin metadata, inner classes, method declarations and annotations, and inlined constants, removing only ordinary method implementations. Method annotations such as nullability and `@JvmStatic` affect Kotlin compilation and must not be dropped. Pure source details such as unused imports do not appear in compiled artifacts. After generation, return to Stub and clean-compile. On failure, first remove invalid imports or make the smallest source adaptation; do not expand the Stub without bounds.

Rabbit's platform JAR uses Java 25 class files. The Stub generator's ASM must support at least `Opcodes.V25` or generation fails with `Unsupported class file major version 69` while reading platform classes. Generated compile-only Stubs must cap class-file version at Java 17 for the repository's JDK 17 build.

Final Stub-generation acceptance must run `./deploy_compat/verify_stub_api.sh <real-api-jugg-repo>` from a Stub checkout. Its argument must be a separate local Jugg checkout with real Android Studio JARs and matching compat modules; the script does not discover it. It first reports source differences for every `v_*` module for human review; generated files and invalid-import differences do not determine the result directly. It then clean-builds all compat JARs in both checkouts and compares class entries and normalized bytecode references to `com.android.*`, `com.intellij.*`, and `org.jetbrains.android.*`, including invocation opcode, owner, member name, and descriptor. Every module must show `MATCH`; any artifact difference fails acceptance. Detailed manifests and diffs are saved under `build/stub-api-verify/`. Do not substitute stale JARs, mere compile success, or a target-name comparison that ignores opcode.

Android Studio Quail (`AI-261.x`) no longer ships the old `com.android.tools.deployer.*` runtime, including `AdbClient`. Therefore `AsDeployerCompat` cannot use a mechanism such as `Proxy.newProxyInstance(IAsDeployerCompat::class.java)` that reflectively resolves every interface method signature at startup: missing deployer types would throw `NoClassDefFoundError` while opening a project. Facade methods must use an explicit dispatcher that catches `NoSuchMethodError`, `NoSuchFieldError`, `NoClassDefFoundError`, or `IncompatibleClassChangeError` only when a compatibility capability is actually called, then tries another version implementation.

The IDE's main deployment path—such as `JuggDeployerHelper`, `JuggDeployTask`, `JuggDeployer`, `JuggDeploymentService`, and `IdeaDeviceAdb`—must not directly import, construct, or retain old deployer runtime types: `AdbClient`, `Installer`, `InstallOptions`, `UIService`, `OverlayId`, `DeploymentCacheDatabase.Entry`, or `DeployerException`. Create those locally in `deploy_compat` version implementations and return wrappers such as `JuggInstallSession`, `JuggOverlayId`, `JuggDeploymentCacheEntry`, and `JuggDeployerException` to the main path. `JuggInstallSession` binds the executor that created it successfully; `LaunchContext` uses that executor and its debugger thereafter. `JuggDeploymentCacheStore` persists only Jugg-owned snapshots. After loading, the currently bound executor reparses APKs and rebuilds OverlayId and `DeploymentCacheDatabase.Entry`; memory cache is isolated by executor identity too. `IdeaDeviceAdbClient` wraps ADB `shell`, `push`, `uninstall`, PID, and arch queries through `IDevice`; these transport operations do not belong on the AS-deployer compatibility interface. `IDeviceAdb.isAdbTransportReady()` exposes the business meaning of ADB recovery checks; callers do not inject shell-ready probes.

Shared calls use `IDevice`, `Apk`, `ApkEntry`, `DexClass`, `ByteString`, `DexComparator.ChangedClasses`, `Deploy.Arch`, and `ILogger` from `com.sickworm.intellij.jugg.deploy.api`. Keep class names and already used members so migration mainly changes imports. `IRuntimeDevice` says a device belongs to the current host runtime, not a deployer-compat version. Legacy, Quail, and IDEA ADB boundaries unwrap the real ddmlib device from the same handle. `Apk.runtimeObject` is a process-local, unserialized raw APK attachment so an owned APK need not depend on a converter's private origin map. Shared API must still not statically expose ddmlib, deployer model, deploy proto, shaded protobuf, or Android logger types.

Resolve Gradle module identity and task module path separately for Run Configuration. Both first reflectively call `GradleProjectPathKt.getGradleProjectPath(Module)` for project path/build root and use external project ID to distinguish composite builds. If that API is unavailable, both try Bumblebee's `AndroidGradleUtil.getModuleGradleProjectPath(Module)`, falling back to the existing `module.name` parsing only if neither API is available. Identity names the Jugg Configuration, so `:app` in a Bumblebee root project also becomes `jugg:app`, regardless of the IDE module-name prefix. The task path preserves Gradle project-path segments and their dots verbatim, such as `:zxphone5.0`; never reconstruct it from identity by reversing `.` into `:`. Reflection is an optional enhancement: catch `Throwable` around it as a whole so it cannot break Configuration creation on older Android Studio.

Gradle Sync listening consistently uses three-argument `GradleSyncState.subscribe(Project, GradleSyncListener, Disposable)`. This static entry point exists on both 211 and newer versions, but `GradleSyncState` changed from class to interface. Direct compiled calls bind published bytecode to one owner shape, risking `IncompatibleClassChangeError`; the stable IDE entry must invoke by reflected class name and method signature. Subscription still passes the old `GradleSyncListener`; from 221 onward an internal Android Studio adapter forwards to `GradleSyncListenerWithRoot`. `plugin.xml` must not register both Sync topics, and published bytecode must not reference `GradleSyncListenerWithRoot`.

The main deployment path must not directly import or access fields of `StudioFlags` either. For example, obtain install mode through `IAsDeployerCompat.getInstallMode()`: legacy compat can read old `StudioFlags.DELTA_INSTALL`, while Quail compat implements it without that removed flag, avoiding `NoSuchFieldError` in `JuggDeployTask` on newer Android Studio.

Debug attach must also use `IAsDeployerCompat.attachJavaDebugger()`, not import Android Studio debugger internals into the main IDE path. Giraffe and later adapters first use `AndroidDebugClientReadyWaiter` to reflectively call AS `waitForClientReadyForDebug` and wait for target-app `ClientData.DebuggerStatus.WAITING`. Then `AndroidStudioDebuggerAttachStarter` reflectively calls the native AS `AndroidConnectDebugger.closeOldSessionAndRun(project, AndroidJavaDebugger(), client, null)`, allowing Android Studio itself to create/activate `XDebugSession` and Debug tool window. Older versions return “unsupported” by default; callers present the specific cause in Run output and notifications.

Quail moved deployer APIs into `com.android.tools.deployer.common` and `com.android.tools.deployer.install`; `OptimisticApkUpdater` no longer exists. `deploy_compat/v_quail` must implement independently rather than inherit the legacy compat chain, lest a superclass or method signature resolve old root-deployer types during startup.

Quail's new `AdbClient` requires an `AdbSession` for standard/full install; without it the client no longer falls back to ddmlib and throws `AdbSession is required for installation`. `QuailAsDeployerCompat` must construct `AdbClient` with three arguments including the application session from `AdbLibApplicationService`. The same helper serves daemon installer and `ApkInstaller` so fallback from delta to full install still works.

Rabbit changed the `AdbClient` constructor parameter from `IDevice` to `DeviceHolder`. `RabbitAsDeployerCompat` inherits Quail deployment behavior and, only at the shared `createAdbClient()` boundary, constructs `DeviceHolder` from legacy `IDevice` while continuing to pass the application `AdbSession`. Daemon installer and full install thus use the same Rabbit API shape.

Quail 4 changed the device argument of `InstallOptions.Builder.setSkipVerification()` from `IDevice` to `DeviceHolder`, which Quail 1 lacks. `deploy_compat/v_quail` calculates the option with `AdbClient.getSkipVerificationOption()`, available in both versions, and adds a nonempty result to `InstallOptions`. This avoids static references to either version-specific signature. Use that same `AdbClient` to calculate the option and run `ApkInstaller`.

On Meerkat–Panda and Quail, device selection obtains the current deploy target through `DeployTargetContext`, then reads IDE-selected order with side-effect-free `getAndroidDevices(project)`. Return the full list only when every selected device is running and resolves to `IDevice`. If any selected AVD is stopped, return empty; neither start the AVD nor silently deploy to a subset. The ADB-connected device list cannot substitute for IDE selection: a single selected device would otherwise deploy to all online devices.

### 3.2 Standalone Quail Deployer

`deploy_compat/standalone_deployer` pins Android Studio Quail 1 build `AI-261.23567.138.2611.15503007`. It retains only the actual transitive closure needed for install, APK model/cache, diff, D8 split, and `OptimisticApkSwapper`, recompiled for Java 11. Runtime must not depend on a full `sdk-tools.jar` or any Quail class with major version 65. Protocol consists only of repository Java 8 `deploy_java_proto.jar`, `studio-proto.jar`, and four-ABI installer binaries.

Resource metadata's protocol version must match `Version.hash()`. `JuggResourceManager` releases installers, Apache 2.0 license, NOTICE, and `SOURCE_CLASSES.sha256` to fixed `~/.jugg/resources/deployer/quail`. Each preparation copies to temporary files in the same directory under a global write lock, sets executable permissions, then atomically replaces final files. Runtime does not verify SHA-256 of embedded resources or Java protocol dependencies; a Java/installer protocol mismatch fails immediately when daemon starts. AAPT2 retains `~/.jugg/resources/tools/<os>/aapt2-inclink-<version>`, sharing only the resource root rather than filenames or replacement policy.

One-directory overwrite requires strict backward compatibility from Standalone Deployer. Metadata `schemaVersion` remains `1`, current `Version.hash()` / protocol version remains `c52d6b25`, and installer paths remain stable for `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64`. Future additions may only be optional data ignored by older Runtime; an incompatible protocol or directory change needs a new migration boundary instead of overwriting this directory. After fully installing tooling, committing the new active manifest, and stopping the old daemon, reacquire the global lock to remove historical `~/.jugg/runtime` while preserving `~/.jugg/resources`.

Standalone uses a real ddmlib `AdbClient` and does not depend on the Quail IDE runtime's adblib application session. D8-split field-reinitialization state must round-trip through owned `DexClass` into `OptimisticApkSwapper`, not disappear at conversion boundaries. Class-only Apply Changes calls `OptimisticApkSwapper(restartActivity=false)`. Resource full swap matches existing IDEA `JuggDeployer.fullSwap`: `restartActivity=true` refreshes `AssetManager/Resources`, keeps the process, and causes only one expected Activity restart. Step 9 delivers executor and resources only; it does not migrate IDEA deployment lifecycle or register standalone MCP deploy capability.

### 3.3 Platform Abstraction

| Runtime | Where `PlatformApi.impl` is set | Semantics |
|---|---|
| IDE plugin | `JuggManagerCreator.create()` | Set to `IdeaPlatformApi` so main can access IDE services. |
| Command line | `CmdLine` companion init | Set to `CmdPlatformApi` so main does not depend directly on IDE runtime. |
| main/test compilation and CLI runtime | `platform_compat/base_api` | Minimal IntelliJ/log4j implementations, without `com.android.*`, for safe CLI packaging without Android class-owner conflicts. |

---
## 4. Core Call Chain

### 4.1 Android Studio API Compatibility Call

```text
JuggManager.init()
  -> AsDeployerCompat.init(logger)
     read ApplicationInfo and select priorityImpl
  -> business layer calls any AsDeployerCompat capability
       -> try priorityImpl first
       -> on compatibility error, try other version implementations in order
     session created successfully
       -> session records actual executor
       -> LaunchContext uses that executor and its debugger
       -> later stateful calls enter bound executor directly, bypassing facade dispatch
       -> install / APK / overlay / cache / swap stay in one Apply Changes runtime
     only if all implementations fail: warn and throw original priority compatibility exception
```

The compatibility layer covers Android Studio API-shape differences only. It must not swallow business exceptions as compatibility failures, which would conceal real deployment failures.

### 4.2 Command-Line Entry

`:cmd_line:standaloneBundle` builds one complete self-contained cross-platform ZIP from `installDist`'s actual runtimeClasspath. Root build's `releaseBuildId` enters IDEA/standalone metadata and Bundle manifest. Bundle JAR filenames are SHA-256 content-addressed, and ordinary class entries must satisfy the Java 11 major-55 boundary. After copying that complete Bundle, IDEA `prepareSandbox` rewrites it into a delta ZIP used only for plugin installation: keep the full manifest, scripts, CLI, and non-JAR files; omit runtime/bootstrap JARs only when `jugg/lib` contains the exact SHA-256. `StandaloneBundleInstallService` restores omitted files in a temporary install directory according to manifest and SHA-256 before invoking the same Bundle installer. IDEA hot-update plugin reinstalls create the same delta ZIP from current `jarFiles`. Standalone-only `cmd_line`, real ddmlib, `base_api`, and `standalone_deployer` JARs must stay out of `jugg/lib/`; the external `:cmd_line:standaloneBundle` must never be pruned. Artifact verification must prove exact restoration of every omitted JAR from `jugg/lib` and reject Apache Ant or optional JNA used only for best-effort Android Tools Rosetta detection from standalone Runtime. IDEA and Standalone both use Java 11-compatible Data Binding compiler 7.4.2 because both directly invoke its layout processing and binding-class generation for incremental resources. The embedded plugin delta must not also carry those Data Binding JARs recoverable exactly from `jugg/lib`.

The Bundle also carries a versioned Python CLI, fixed `standalone_bootstrap`, and Gson. At each run, Bundle install entry and stable daemon launcher dynamically select Java in order: valid `JAVA_HOME/bin/java`, then PATH `java`. Before Java starts, the POSIX daemon launcher best-effort raises the process soft `nofile` limit toward 65536 without exceeding the hard limit and logs the actual value. Standalone compilation prefers `ANDROID_HOME` / `ANDROID_SDK_ROOT`; if absent, it reads `sdk.dir` from target-project root `local.properties`. Bundle and IDEA installation share one Python wrapper. Each invocation checks `python3`, then `python`, for Python 3.7+, trying the latter if the first is too old; it does not save an absolute command resolved at installation time. External scripts and plugin Install CLI both run `StandaloneRuntimeInstaller`. Before commit, it validates full JDK 11+, Python 3.7+, SHA-256, basename/path, and symlinks; it publishes immutable JAR/tooling/CLI first, then atomically replaces `standalone_load_manifest.json`. After successful commit and release of Global Resource Lock, the installer uses `ProcessHandle` to forcibly stop daemons under the same Jugg root whose main class is `StandaloneBootstrap`; the next CLI invocation starts under the new active manifest. If CLI is installed and its active manifest is IDEA-managed, IDEA project startup compares the embedded Bundle `releaseBuildId` with active `toolingReleaseBuildId`, refreshing runtime with the same transaction only if tooling differs. Compatible hot updates on the same tooling build and externally managed runtimes are not automatically overwritten. When CLI starts standalone, it writes child output to project `build/jugg/log/standlone_cli/standalone_startup.log` and displays ongoing wait progress. On early exit or startup timeout, print log tail and full path. Bootstrap creates `URLClassLoader` in declared order without scanning a shared pool. Class-load, linkage, or daemon-initialization failure returns the exception directly and retains the active manifest; users recover by reinstalling.

```text
main(args)
  -> CmdLine.run(args)
     set PlatformApi.impl = CmdPlatformApi
  -> cmd=buildGradleBase
     run full Gradle baseline build and prepare incremental-compilation context
  -> cmd=buildIncrementalApk
     run incremental build from existing project info / classpath / history
```

The command-line entry reuses main-layer compilation but lacks IDE Run Configuration, Run tool window, and device-selection UI. When comparing CLI and IDE behavior, inspect `CmdPlatformApi` and `IdeaPlatformApi` first.

CI splits the command-line build into two auditable phases:

1. `buildGradleBase` clears the target Jugg directory, runs a full Gradle build, and saves APK, project info, classpath, deployment history, APK database, and source index as a CI-managed read-only baseline.
2. `buildIncrementalApk` restores context from that baseline, compiles only caller-supplied `changedFiles`, merges Dex, and writes the incremental output back to the APK-output directory.

Jugg deliberately does not infer a CI diff. The caller supplies files to compile; the command verifies each exists, lies under `sourceProjectDir`, is fully recognizable by `FileChangesHandler`, and contains no build file. On first use, write `.dirty` into the baseline directory. A second execution against the same mutable baseline fails, preventing a previous incremental run's rewritten history/database from being treated as clean input. For multiple incremental result sets, copy a separate baseline for each rather than sharing one directory concurrently.

---

## 5. Hidden Constraints

- `IAsDeployerCompat.updateMinApi()` switches minimum device API between Android 11 and Android 8 according to the compat-deployment switch. Do not judge older-device support solely from the current AS version.
- `setAllowSelectDevice()` is an early special API. `AsDeployerCompat` tries it across all implementations; do not restrict it to priorityImpl.
- Device belongs to host runtime, not Legacy/Quail compat. New version adapters must implement `IRuntimeDevice` rather than reinstating casts to a concrete Adapter class.
- Raw APK attachment must travel with the owned APK and remain transient; do not reintroduce per-converter APK-origin maps.
- Install-session creation permits compatibility fallback. After success, this run's `LaunchContext` must use the session-bound executor and debugger; isolate deployment memory cache by executor identity as well.
- Every `AsDeployerCompat` interface capability must retain compatibility-error fallback; do not reintroduce priority-only calls bypassing the dispatcher. The session-bound executor maintains stateful-owner consistency.
- On a new Android Studio version, add at least a `deploy_compat/v_*` implementation and update `AsDeployerCompat.compatImplList` ordering and this page's version table.
- Real Android Studio JARs may be attached only temporarily through local `deploy_compat/local.properties`, never restored under `deploy_compat/v_*/libs`.
- Do not reflect over every `IAsDeployerCompat` method during `AsDeployerCompat` startup. Newer AS may have removed an old deployer type; reflective resolution would terminate plugin initialization before business fallback runs.
- Do not access `StudioFlags` fields directly from `JuggDeployTask`, `JuggDeployer`, or other main paths. New flag reads go through compatibility interface or safe reflection.
- `platform_compat/base_api` must contain no `com/android/**`; ddmlib, standalone deployer, or protocol JAR alone must supply Android runtime classes.
- Custom compiler examples live under `custom_compilers`. Production loading reads `build/jugg/config/custom_compilers` through `CustomCompilerManager`; examples are not default compilation stages.
- `changedFiles` for `buildIncrementalApk` is an external contract, not a hint. Fail explicitly if filtering changes input count, a path escapes bounds, or a build file appears; do not silently skip files and still produce an APK.
- When `CompileProjectCommand` injects Gradle init script/project directory or changes local working directory, pass paths as separate quoted arguments so spaces do not truncate Windows `-I` or macOS/Linux `cd`.
- When `SyncLocalClasspathCommand` invokes local rsync, quote executable, source directory, and destination directory separately so project or backup paths containing spaces remain single arguments.
- One Windows command pipe may mix UTF-8 and GBK. `ProcessOutputReader` must retain raw bytes line by line, validate UTF-8 strictly, and fall back to GBK on failure. Do not construct strings with a fixed encoding first or lock one encoding for the process. Once a log contains `�`, the original bytes may be lost and changing viewer encoding cannot recover them.

---

## 6. Investigation Entry Points

| Symptom | Start with |
|---|---|
| Deployment API crashes on an AS version | `AsDeployerCompat` priorityImpl selection and proxy-fallback logs. |
| `NoSuchMethodError` / `NoClassDefFoundError` on newer AS | Corresponding `deploy_compat/v_*/*AsDeployerCompat.kt`; determine whether a higher-version implementation is needed. |
| Device selection differs from IDE behavior | Version implementation of `IAsDeployerCompat.getSelectedDevices()`. |
| Main module cannot compile because of missing IDE API | Check missing stub in `platform_compat/base_api`. |
| CLI differs from IDE | `CmdLine`, `CmdPlatformApi`, `IdeaPlatformApi`. |
| Windows command output in Chinese is garbled | Check whether `CmdExecutor` sends stdout/stderr through `ProcessOutputReader` and whether bytes were decoded earlier. |
| CI incremental baseline reports `.dirty` | That baseline was consumed by an incremental build; copy an untouched `buildGradleBase` output and retry. |
| Custom compiler does not load | `CustomCompilerManager` and `build/jugg/config/custom_compilers`. |

---

## 7. Related Documents

- IDE layer: `04_engineering_ide.md`
- Jugg Debug attach: `04_engineering_debug_attach.md`
- Project model: `04_engineering_project.md`
- Deployment core: `03_deploy_core.md`
- JVMTI/startup agent: `03_runtime_jvmti.md`
- Custom compiler: `02_compile_custom_ui.md`
