# Runtime and JVMTI Support

> Last checked: 2026-09-15
> Consistency rule: when documentation conflicts with code, follow the code.

---

## 1. Scope

This page defines the responsibilities of Apply Changes Agent, Jugg JVMTI Agent, and IDE deployment orchestration. It explains how Jugg prepares the startup agent, checks device JVMTI capability, installs runtime hooks, and triggers compatible-deployment retry when needed.

For Direct Overlay transport, the ViewHierarchy LocalSocket protocol, and complete install/code-swap flow, see `03_deploy_core.md`, `03_deploy_complete.md`, and `08_mcp_layout_verify_design.md`.

---

## 2. Core Source Index

| Class/file | Path | Role |
|---|---|---|
| `AppAbiResolver` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/AppAbiResolver.kt` | Combines process, Manifest, APK, installed-package, and device evidence at deployment entry to determine target ARM bitness. |
| `AppAbiCache` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/AppAbiCache.kt` | Reuses ABI resolution by device, package, and complete APK-file fingerprint, evicting old results on APK changes. |
| `ApkInfoReader` | `main/src/main/java/com/sickworm/intellij/jugg/apk/ApkInfoReader.kt` | Aggregates ARM native libraries across base/split APKs and reads `android:use32bitAbi`. |
| `JuggJvmtiAgentManager` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/JuggJvmtiAgentManager.kt` | Manages pushing the Jugg agent bundle, app-sandbox setup, attach, and cleanup. |
| `JuggJvmtiAgentManagerHelper` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/JuggJvmtiAgentManagerHelper.kt` | Decides whether to push a missing agent after deployment and reads flags for JVMTI availability. |
| `JuggDeployOrchestrator` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployOrchestrator.kt` | Coordinates async agent check, push, restart, and JVMTI compatibility detection around deployment. |
| `DeployRetryHandler` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/run/flow/DeployRetryHandler.kt` | After deployment failure, asks the run host to detect JVMTI compatibility and switches to compat deployment if needed. |
| `AsStartupAgentPusher` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/direct/AsStartupAgentPusher.kt` | Pushes the Apply Changes startup agent for Direct Overlay without requiring an online app process. |
| `native-lib.cpp` | `jvmti_agent/src/main/cpp/native-lib.cpp` | `Agent_OnAttach` entry point; writes `.jugg_jvmti_available` / `.jugg_jvmti_not_available` and starts instrumentation. |
| `DirectHotReloadWriter` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/DirectHotReloadWriter.kt` | Builds dynamic-attach requests, copies a per-request agent, and waits for a result file for Direct app sandbox transport. |
| `native-lib.cpp` | `jvmti_agent/src/main/cpp/native-lib.cpp` | `Agent_OnAttach` entry point distinguishing startup dataDir from `jugg_hot_reload:` dynamic attach options. |
| `class_redefiner.cc` | `jvmti_agent/src/main/cpp/class_redefiner.cc` | Parses Hot Reload requests. Native code updates Application ClassLoader in-memory DEX elements, then matches loaded classes, batches `RedefineClasses()`, and atomically writes the result. |
| `instrumenter.cc` | `jvmti_agent/src/main/cpp/instrumenter.cc` | Loads `jugg-instruments.jar`, sets the class-file-load hook, and retransforms target classes. |
| `InstrumentationHooks` | `jvmti_agent/src/main/java/com/sickworm/intellij/jugg/instrument/InstrumentationHooks.java` | Handles framework hooks for ResourcesManager and ClassLoader resources; must skip ordinary Apply Changes overlay repair under compat deployment. |
| `ApplyChangesOverlayPolicy` | `jvmti_agent/src/main/java/com/sickworm/intellij/jugg/instrument/ApplyChangesOverlayPolicy.java` | Records host APK paths and decides whether to remove Apply Changes overlay from non-host resource environments. |
| `ResourceOverlays` | `jvmti_agent/src/main/java/com/sickworm/intellij/jugg/instrument/ResourceOverlays.java` | Connects resources/assets in the extracted APK directory to Android 11+ ResourcesLoader, limited to Direct sandbox marker and host APK; compat deployment retains the resource-APK path. |
| `FlutterAssetRefresh` | `jvmti_agent/src/main/java/com/sickworm/intellij/jugg/instrument/FlutterAssetRefresh.java` | Supplies an overlay-aware AssetManager to FlutterEngine: running Dart Engines use `updateJavaAssetManager()`, not-yet-running ones replace `DartExecutor.assetManager`, and host package contexts gain an overlay loader at `ContextImpl#createPackageContext` exit. Failures generate a per-batch warning and Toast. |
| `HotfixLoader` | `jvmti_agent/src/main/java/com/sickworm/intellij/jugg/hotfix/HotfixLoader.java` | Initializes app code-cache paths, recognizes the compat flag, and installs Dex/resource patches. |
| `RootlessCompatDeployImporter` | `jvmti_agent/src/main/java/com/sickworm/intellij/jugg/hotfix/RootlessCompatDeployImporter.java` | Early in `BootstrapApplication.attachBaseContext()`, imports a Host-staged rootless compat request: validates protocol, package, requestId, payload SHA-256, and expected overlay ID; atomically commits private staging into `code_cache/.overlay`, writing overlay ID last. Failure preserves the old overlay and emits one result log line. |
| `jugg_agent_setup.sh` | `jvmti_agent/src/main/script/jugg_agent_setup.sh` | Places a versioned agent `.so` under app `code_cache/startup_agents`. |
| `buildAgentBundle.gradle` | `jvmti_agent/buildAgentBundle.gradle` | Compiles Jugg runtime and preprocessed Dragonfly JAR into `jugg-instruments.jar`, bundles 64/32-bit `.so` files and setup script, and generates a plugin resource. |

---

## 3. Core State Model

| State/file | Location | Semantics |
|---|---|---|
| Jugg agent bundle | `/data/local/tmp/jugg/{AGENT_VERSION}` | Device-global temporary directory containing `jugg-instruments.jar`, 64/32-bit `.so` files, and setup script. |
| App startup agent | `{app}/code_cache/startup_agents/{version}-jugg_jvmti_agent(.so/_alt.so)` | Startup agent in the app sandbox actually loaded by the system. |
| Direct instrumentation JAR | `{app}/code_cache/startup_agents/{version}-jugg-instruments.jar` | Copied only for Direct app sandbox, enabling the app process to map instrumentation classes; ordinary `run-as` continues to use the global JAR. |
| Rootless compat request | `/sdcard/Android/data/{package}/files/jugg/rootless-compat/{requestId}/` | Shell-writable request readable by the app through `Context.getExternalFilesDir(null)`: `payload.zip` + `request.properties` + `ready` written last; imported early in `BootstrapApplication` startup. |
| Rootless import result | `jugg-agent` tag log line | `__JUGG_ROOTLESS_IMPORT__ OK|FAILED <requestId> [<stage> <reason>]`, the only import terminal state readable by Host. |
| Apply Changes agent | `{app}/code_cache/startup_agents/{versionHash}-{dollName}` | AS startup agent reused by Direct Overlay and pushed by `AsStartupAgentPusher`. |
| `.jugg_jvmti_available` | `{app}/code_cache/.jugg_jvmti_available` | Written after native `Agent_OnAttach` obtains JVMTI/JNI; it does not establish that every optional framework hook succeeded. |
| `.jugg_jvmti_not_available` | `{app}/code_cache/.jugg_jvmti_not_available` | Written when native cannot obtain JVMTI/JNI, triggering a compat-device record. |
| compat device record | Managed by `CompatDeployHelper` | Once JVMTI is known unavailable for an app/device, later deployments go directly to compat deployment. |

`JuggJvmtiAgentManagerHelper.isJvmtiAvailable()` reads the not-available flag before the available flag. With neither flag, it returns `null`: app startup or agent initialization is not yet known.

### 3.1 Responsibility Boundary Between Apply Changes and Jugg

Apps meeting all three conditions continue to use Android Studio Apply Changes hot reload: a rollback-safe `run-as` write, raw UID in `10000..19999`, and matching SELinux contexts for the probe and existing `code_cache`. If these prerequisites fail but Jugg can fully access app dataDir through ordinary shell, root adbd, or noninteractive `su`, it uses its own same agent via dynamic attach for class redefinition.

One Jugg agent has two entry points. Startup options contain app dataDir for overlay loading, JVMTI detection, and framework hooks. Dynamic options are `jugg_hot_reload:<requestDir>` for this request's class redefine and resource refresh, with optional Activity recreation. Direct app sandbox transport persists Direct Overlay first; if dynamic attach fails, process restart uses the startup entry point to load that same overlay.

---

## 4. Core Call Chain

### 4.1 Jugg Agent Coordination After Ordinary Deployment

```text
JuggDeployOrchestrator.execute()
  -> call JuggJvmtiAgentManagerHelper.isNeedPushAgentAfterDeploy() asynchronously
     skip install; for incremental deployment, check Jugg and AS Apply Changes agents in app sandbox
  -> JuggDeployTask.run()
     complete install / Apply Changes / Apply Changes and restart Activity first
  -> after detectJob.await(), call JuggJvmtiAgentManagerHelper.pushAgentToApps() if needed
     push bundle to /data/local/tmp/jugg/{AGENT_VERSION}, then run setup script through AppSandboxExecutor
  -> decide restart/start/no-op from deployment data and user settings
  -> if agent was pushed this run and app will restart, call isHasJvmtiCompatIssue()
     wait for native flag files; on failure, record compat device and throw redeploy-with-compat signal
```

Pushing after deployment prevents Android Studio's first Apply Changes deployment from clearing startup agents and deleting Jugg's agent. JVMTI detection must follow restart because the system loads the startup agent only when the app process starts.

On Android 15+ with Android Studio older than Meerkat, if the first ordinary Apply Changes deployment is a full-resource overlay, an old startup agent may generate only a framework-transform cache without applying those cached transforms in the current process. If historical deployed files are empty, modern Compose resources were explicitly compiled this run, and the overlay contains `assets/composeResources/**`, Host polls `.studio/instruments-*.jar.cache` for `android-app-ResourcesManager` and `android-app-LoadedApk` after the first process restart. If both appear, restart immediately again. If still incomplete after about 5 seconds, warn and restart anyway so the committed overlay consumes cached transforms. Only ordinary Deployer gets this compatibility restart; Direct Overlay, compat deployment, existing successful deployment history, and legacy Compose resources do not.

`AppSandboxExecutor` wraps the setup script uniformly. Apply Changes-compatible apps use `run-as`; incompatible apps use this run's fixed ordinary-shell, root-adbd, or noninteractive-`su` mode at actual `dataDir`. Ordinary files receive the existing `code_cache` owner and dynamic MCS context; JVMTI `.so` gets executable `apk_data_file:s0` for appdomain. Repair-stage output cannot mix into the setup script's `success`/`failed` result. The upper agent manager no longer assembles separate privilege commands.

Deployment entry resolves target ARM bitness once, passing it to Direct app sandbox and subsequent Apply Changes transport. Evidence priority is: running process, Manifest `android:use32bitAbi`, uniquely determinable ARM native-library bitness across all base/split APKs, installed package `primaryCpuAbi`, primary device ABI, then the existing 64-bit default. If APKs contain both 32- and 64-bit ARM libraries or no ARM libraries, keep the APK evidence unknown so one resource split without native libraries cannot override other valid evidence. Query `dumpsys package` only when local Manifest and APK evidence cannot decide; avoid synchronous ADB cost when local evidence is conclusive. Direct app sandbox must reuse this resolved value when preparing startup agent, not probe process architecture again after the process has stopped.

`JuggDeployOrchestrator` owns the ABI-result cache, keyed by device serial, packageName, and sorted `absolute path + size + mtime` of every APK. One APK set resolves once across multiple deployment slices, internal degradation, and later incremental deployments. If local evidence is insufficient, query installed package at most once during that cache period. On APK-fingerprint change, resolve again and evict old results for that device/package. If `dumpsys package` throws, fallback evidence may still serve this run, but do not cache the result so a recovered device can supply stronger evidence next time. Debug logs separately report installed-package query duration and whole ABI-resolution `source`, `cacheHit`, and duration.

### 4.2 Compatibility Detection During Failed Retry

```text
DeployRetryHandler.tryRetry()
  -> deployRunHost.detectJvmtiCompatIssue()
  -> JuggJvmtiAgentManagerHelper.isNeedPushAgentAfterDeploy()
     if agent absent, push then restart app
  -> isHasJvmtiCompatIssue()
     on not-available flag, record compat device; switch next round to compat deployment
```

This path only asks whether JVMTI incompatibility could explain the current failure. Devices already in compat deployment skip this detection, avoiding repeated records and retry loops.

### 4.3 Native Agent Startup

```text
system loads startup agent
  -> Agent_OnAttach(vm, options = app data dir)
  -> attempt to obtain JVMTI and JNI
     on failure write .jugg_jvmti_not_available
  -> on success write .jugg_jvmti_available
  -> HandleStartupAgent()
     AddCapabilities
     Direct sandbox prefers app-local jugg-instruments.jar; otherwise use global JAR under /data/local/tmp
     instrument Application / AppComponentFactory / Resources
```

Treat `options[0] == '/'` as startup agent; prefix `jugg_hot_reload:` enters dynamic redefine. The dynamic branch neither writes startup-availability flags nor reinstalls framework instrumentation.

### 4.3.1 Startup Import of a Rootless Compat Payload

Without an available sandbox, Host pushes no agent. The APK's `jugg-runtime.jar` supplies `BootstrapApplication`:

```text
BootstrapApplication.attachBaseContext(base)
  -> HotfixLoader.init(base)                     initialize codeCacheDir / overlayFilesDir
  -> RootlessCompatDeployImporter.importPending(base)
     -> scan Context.getExternalFilesDir(null)/jugg/rootless-compat for ready-marked requests (take newest)
     -> serialize with app-private file lock; process unable to acquire it returns and consumes only committed overlay
     -> validate protocolVersion / packageName / requestId / payload SHA-256 / expected overlay id
     -> extract to code_cache/rootless_import/<requestId>/ (reject absolute paths, `..`, backslashes, duplicate entries)
     -> commit under Direct Overlay rules: delete old id, clear payload targets, move files, chmod dex 0444, write id last
     -> emit `__JUGG_ROOTLESS_IMPORT__ OK|FAILED <requestId> [<stage> <reason>]` under jugg-agent tag
  -> HotfixLoader.isNeedEnableHotfix()            load new overlay on this same process start
  -> HotfixLoader.install(base)
```

A failed import does not change the committed overlay or leave an enable flag that `HotfixLoader` could mistake for success. Host uses the result line to decide whether to commit deployment cache, history, and file state; missing result or timeout is failure.

### 4.4 Direct App Sandbox Dynamic Redefine

```text
Direct Overlay committed
  -> write request.txt + Dex to code_cache/jugg_hot_reload/<requestId>
  -> copy a separate agent so for this request
  -> am attach-agent <pid> <agent>=jugg_hot_reload:<requestDir>
  -> turn NEW class DEX into in-memory dex elements and append to Application ClassLoader
  -> for MODIFIED class, run GetLoadedClasses, IsModifiableClass, and batch RedefineClasses
  -> when refreshResources=true, update ResourcesLoader providers and attach to existing host Resources
  -> when restartActivity=true, call recreate() for all surviving Activities in the current process in one main-thread task
  -> rename result.tmp to result.txt
```

Only `OK` means the request completed. V4 requests explicitly distinguish `NEW` from `MODIFIED`; new classes do not undergo JVMTI redefine. At the official responsibility boundary, native code obtains Application ClassLoader from `ActivityThread.currentApplication()` and reads/writes `DexPathList.dexElements`. Java `DexUtility` only reuses `makeInMemoryDexElements` to create elements and merges in `old + new` order. JNI failure results retain exception type and message. The upper layer explicitly says new classes could not be loaded live this run and will restart the process using the committed overlay. Modified classes still use JVMTI batch redefine. Empty and resource-only requests may contain no classes, so `[nothing to deploy]` still completes the overlay checkpoint and runtime request without automatically restarting for an empty payload. `refreshResources=false` retains pure-class HOT_RELOAD semantics; `refreshResources=true` follows Apply Changes' resource-switch order.

With `restartActivity=true`, one main-thread task refreshes resources first, then traverses `ActivityThread.mActivities` as Apply Changes does and recreates every surviving Activity in the current process. If reading fails, collect window-associated Activities from `WindowManagerGlobal` as fallback. Do not reenter Android Studio `fullSwap/overlaySwap`. An unloaded modified class returns `CLASS_NOT_FOUND`; Host explains that this process cannot redefine it and restarts the app to load the committed overlay. Host remains compatible with old agent result `MISSING`. `UNMODIFIABLE`, JVMTI redefine error, attach failure, result timeout, resource-refresh failure, and Activity-recreation failure all degrade through `needsRestart` to a full app restart, where startup agent loads the same committed overlay.

Host deletes a request directory after the terminal result. It cleans a timed-out request before the next request starts, avoiding file races with a still-running agent while limiting accumulated request Dex and dynamic-agent `.so` files.

`.jugg_jvmti_available` is written after JVMTI/JNI acquisition and before `HandleStartupAgent()`. It establishes only that the basic JVMTI environment is available, not that every later framework hook installed successfully.

When Direct transport copies an app-local JAR, it uses `AppSandboxExecutor` owner/SELinux repair and passes the deployment-entry app arch to `pushAgentToApp(packageName, sandboxExecutor, appArch)`. Ordinary apps' manager calls without sandbox and Android Studio deploy transport remain unchanged.

### 4.5 Apply Changes Overlay Repair for Non-Host Resources

Apply Changes can carry the host app's resource overlay into a non-host package's `AssetManager`. If WebView provider initialization receives resources containing the host overlay package ID, it may throw `java.lang.IllegalStateException: Already registered a list of actions in this process` and crash WebView.

Jugg records current `ResourcesKey.mResDir` in both old and new signatures of `ResourcesManager#createAssetManager`, then compares it with APK paths in host `ApplicationInfo`:

```text
create AssetManager
  -> resDir belongs to host APK: retain Apply Changes overlay
  -> resDir is outside host APK: remove host overlay from code_cache/.overlay
```

Before host APK paths have been recorded, policy falls back to the old `/data/app` path test. This repair addresses only non-host resource environments; it must not delete the overlay needed by the host Activity's normal hot update. Skip ordinary Apply Changes overlay repair under compat deployment too.

### 4.6 Ordinary Resource Overlay in Direct Sandbox

During agent preparation, Direct transport writes `.jugg_direct_resource_overlay`; startup agent initializes resource paths using the actual dataDir passed by the system. Migrated Android Studio `ResourceOverlays` consumes resources/assets under `.overlay/*.apk` through `ResourcesProvider.loadFromDirectory()`, without generating an extra resource APK. The entry hook of `LoadedApk.getResources()` validates dataDir and gathers host base/split APK paths. Its exit hook adds a shared loader only to Resources containing a host APK. Non-host Resources and ordinary Apply Changes without a Direct marker keep their original path.

On Android 11+, a live resource request rescans committed directories, updates an existing loader through `ResourcesLoader.setProviders()`, attaches it to existing host Resources held by `ResourcesManager`, then recreates all surviving Activities in the current process, including other tasks and multiwindow instances. Resource-only requests may have an empty Dex list; for mixed code/resource changes, class redefine completes first. Resource refresh and Activity recreation run in order in one main-thread task, preserving the process on success. On failure, the outer layer restarts the app and startup agent restores from committed overlay.

Before Android 11, ResourcesLoader is unused. With a compat-deployment flag, ordinary directories are likewise not loaded; the existing `resource.ap_` loader handles them. Both cases still take effect after process restart.

Migration reference: [AOSP ResourceOverlays](https://android.googlesource.com/platform/tools/base/+/refs/heads/mirror-goog-studio-main/deploy/agent/runtime/src/main/java/com/android/tools/deploy/instrument/ResourceOverlays.java).

See `03_deploy_core.md` §6.4 for the complete Direct app sandbox versus official Apply Changes boundaries around Android version, process/Activity coverage, class payloads, slicing, recovery, and diagnostics.

### 4.8 FlutterEngine AssetManager Refresh

`FlutterEngine` obtains an AssetManager once during construction through `createPackageContext(...).getAssets()`. Native `APKAssetProvider` then caches the corresponding `AAssetManager*` at construction. Apply Changes rebuilds `ResourcesImpl/AssetManager` through `ResourcesManager.applyAllPendingAppInfoUpdates()` and calls `Resources.setImpl()`, but the Engine still holds its old instance and continues reading old assets.

The reliable boundary before first Dart launch (Flutter classes cannot be hooked during startup-agent initialization, as explained below) is:

```text
InstrumentationHooks.handleCreatePackageContextExit(Context)   [ContextImpl#createPackageContext exit hook]
  -> compat / below Android 11: return immediately
  -> no FlutterEngine in App ClassLoader: return without reading AssetManager
  -> FlutterAssetRefresh.prepareHostPackageContext(context)
     -> for a host-APK package context: applyOverlayAssets(context.getResources().getAssets())
        -> append the Apply Changes overlay directory as the last native ApkAssets entry
```

At construction, `FlutterEngine` obtains and holds the AssetManager from `context.createPackageContext(pkg, 0).getAssets()`. The hook supplements it before native code consumes it. The batch path for running and delayed-start Engines is:

```text
InstrumentationHooks.createAssetManager*Exit() (host APK branch)
  -> below Android 11 / no FlutterEngine in App ClassLoader: return without posting
  -> FlutterAssetRefresh.scheduleRefresh()      register + post only; do not call Flutter yet
  -> on main thread: recreate host package context and obtain overlay-aware AssetManager
     no live Engine / no overlay (path lacks /code_cache/.overlay/) -> no-op
     reflection contract such as idToEngine unreadable -> warn; do not treat as “no Flutter”
  -> handle each Engine from one FlutterEngine.idToEngine snapshot (including Engines absent from FlutterEngineCache or spawned)
     -> flutterJNI.isAttached() false -> record failure
     -> DartExecutor.isExecutingDart() true
        -> flutterJNI.updateJavaAssetManager(assetManager, FlutterLoader.findAppBundlePath())
     -> otherwise -> replace DartExecutor.assetManager so later runBundleAndSnapshotFromLibrary() uses the new instance
  -> any failure in batch: warn once + Toast once; all succeed: debug log only (distinguish both paths)
```

Boundaries and constraints:

- `ResourcesManager` and `ContextImpl` are framework hooks installed by startup agent in both ordinary and Flutter apps. Hook installation and entry do not themselves imply a state change. On Android 11+, continue only if App ClassLoader resolves `FlutterEngine`; a non-Flutter app neither posts a main-thread task nor reads or rewrites a package-context AssetManager.
- Compat deployment returns at the start of both `createAssetManager` exit hooks and the `createPackageContext` exit hook. Before Android 11, return before Flutter scheduling and package-context processing. Both continue through `resource.ap_` plus process restart.
- `createPackageContext` augments only an AssetManager containing host base/split APKs. WebView providers, SDK-specific resources, and other package contexts lack host APKs and stay unchanged. An ordinary same-host package context within a Flutter app shares this augmentation; at the framework boundary, callers cannot be distinguished, and this is the minimal effect needed for a cold-start Engine.
- Do not hook Flutter classes. `io/flutter/embedding/engine/FlutterJNI` cannot be resolved at startup-agent time (`Optional hook transform class not found`). Installing on demand also fails: the agent library is not loaded with `System.loadLibrary`, so `RegisterNatives` cannot bind the caller's copy (`UnsatisfiedLinkError`). Use the framework `ContextImpl#createPackageContext` startup boundary instead.
- An Engine whose Dart has not started must replace the AssetManager captured in its `DartExecutor` constructor. Flutter's `RunBundleAndSnapshotFromLibrary()` rebuilds `APKAssetProvider` from that instance on Dart startup, overriding any JNI refresh sent earlier. Simply skipping this Engine would leave it stale indefinitely.
- Enumerate the Engine snapshot once per batch. Entry-location misses (no Application, Flutter, overlay, or Engine) are normal no-ops. Once overlay and a nonempty snapshot are confirmed, every failure contributes to the same batch warning and Toast; a log-only failure or `refreshed for N` message must not conceal a stale Engine.
- A `ThreadLocal` stores paired state for both `createAssetManager` Enter/Exit signatures. Android 14+ hooks both, and the old signature delegates to the new one recursively; `ResourcesManager.getResources()` may enter from any thread.
- The agent's Java classes load through bootstrap ClassLoader. Resolve Flutter classes with `context.getClassLoader()` or `Class.forName` will always fail.
- The whole path is fail-open. Missing Flutter, reflection failure, and refresh failure remain internal and do not break Android Resources/Activity flow. Once overlay and Engines are confirmed, however, failure must produce a per-batch warning and Toast, not silent apparent success.
- Only the `kApkAssetProvider` resolver is replaced. Already loaded assets and Dart-side `rootBundle` string caches do not reread; observe an old value for the same key only through a new isolate or uncached read.
- For raw assets, obtain Android native directory ApkAssets through `ResourcesProvider.loadFromDirectory(dir, null)`, then append it as the **last** ApkAssets of the host package-context AssetManager (`OpenNonAsset` searches backward, giving it priority). Deduplicate with an identity WeakHashMap. Here `null` means no Java override callback; native `DirectoryAssetsProvider` still reads directory files. Do not give Flutter a Java `AssetsProvider`: Flutter calls `AAssetManager_open()` on an `io.worker` thread unattached to JVM, where Android `LoaderAssetsProvider` aborts because it cannot obtain `JNIEnv`. The final implementation was verified through real Jugg compile/deploy: asset updates took effect without that crash. Historical evidence is in proposal §§16–17.
- `executeDartCallback()` uses `DartCallback.androidAssetManager` and is likewise outside this coverage.

### 4.9 ClassLoader Resource Overlay

Legacy Compose resources use `ClassLoader#getResource()` to read APK-root files, not `AssetManager` to read `assets/`. Jugg retransforms `java/lang/ClassLoader#getResource(String)` and performs an overlay-first lookup at original-method entry:

```text
InstrumentationHooks.classLoaderGetResource(classLoader, name)
  -> accept only host Application ClassLoader or a child with it as parent
  -> code_cache/.overlay/base.apk/<name> is a file: return file URL
  -> resource.ap_ exists and ZIP entry <name> exists: return jar:file URL
  -> miss or exception: return null and continue original ClassLoader#getResource
```

The hook does not restrict resource names. Content deployed to `.overlay` is intended override state, but the `resource.ap_` branch must confirm the ZIP entry before suppressing the original fallback; ZIP existence alone is insufficient.

On first hook entry, log `Classpath resource hook in` once. On each hit, log `Classpath resource overlay hit` with `file` or `resource_ap_`. Reads of `resource.ap_` may be affected by `JarURLConnection` caching, so restart the app process after successfully deploying an APK-root overlay.

---
## 5. Build and Version Constraints

- The native target library is `jugg_jvmti_agent`; its build entry point is `jvmti_agent/CMakeLists.txt`.
- `jvmti_agent/buildAgentBundle.gradle` generates `BuildConfig.AGENT_VERSION`, `AGENT_BUNDLE_PATH`, and flag-file names, and places the agent bundle under plugin resources.
- `jugg-instruments.jar` is the DEX JAR actually loaded by native agent through `AddToBootstrapClassLoaderSearch()`. Dragonfly needed by ViewHierarchy must be included in this artifact with Jugg runtime classes, not only in the Gradle-injected app `jugg-runtime.jar`.
- Most Dragonfly code comes from the upstream AAR's source-compiled `classes.jar`; AAR Manifest, assets, and native libraries do not enter Jugg. `jvmti_agent/libs/dragonfly/preprocess.sh` uses a fixed-SHA-256 Jar Jar Abrams to relocate private package names and removes `DragonflyJvmtiBridge`, unused by Jugg and carrying a `NestHost` attribute unreadable by old D8. A fixed-version dex2jar still converts `implementation_0.jar`. Preprocessing removes a dexlib2 subtree used only by Kuikly hooks that otherwise crashes AGP 8.8 D8 frame analysis; Dragonfly already contains that optional call within a `Throwable` boundary.
- A dex2jar-generated Kotlin subset in implementation is replaced by a source-compiled Kotlin stdlib 2.0.0 with fixed SHA-256 and relocated to `com.sickworm.intellij.jugg.internal.dragonfly.runtime.kotlin.**`. After deleting old Kotlin and dexlib2, remaining dex2jar classes lacking `StackMapTable` are normalized from Java 8 class version to Java 6. The official Gradle flow consumes only repository `*-jugg.jar` files, avoiding same-name host-app dependencies and any need for host-provided Kotlin runtime.
- `jugg-runtime.jar` also merges the same preprocessed Dragonfly JAR, preserving `GradleApplicationInjector`'s single-runtime-JAR interface. The build checks that private Dragonfly and Kotlin runtime entry points exist and original-package class entries do not.
- Root `build.gradle`'s `agentVersion` is the common version source for device directories, startup-agent filename prefixes, and bundle filenames.
- Increment `agentVersion` after changing native code, Java runtime (including `ViewExpressionEvaluator` / `view-inspect` evaluation), setup script, or bundle contents under `jvmti_agent`. `isAgentBundlePushed()` checks only whether four files already exist under `/data/local/tmp/jugg/{AGENT_VERSION}`; a same-version plugin update does not push again, leaving the device on old `jugg-instruments.jar`.
- A 32-bit app uses `_alt.so`: bundle packaging renames the armeabi-v7a `.so` to `jugg_jvmti_agent_alt.so`. Both `attachAgentToApp()` and setup script depend on this convention.
- ABI resolution currently supports ARM only: `armeabi` / `armeabi-v7a` map to 32-bit, `arm64-v8a` to 64-bit; x86 is incompatible. If evidence cannot decide, keep the 64-bit default, which covers most current devices.
- `HotfixLoader` centrally checks device API at Java runtime entry. For API < 26, `init()` returns before accessing `Context.getCodeCacheDir()`; `install()`, `installDex()`, and `isNeedEnableHotfix()` short-circuit too. This does not change Gradle artifacts; `BootstrapApplication` injection still depends only on `jugg.inject.application.enable`.
- If `BootstrapApplication` cannot find application meta-data, treat it as no original Application/AppComponentFactory and continue startup. Create and substitute those instances only when meta-data includes their original class names saved by Jugg.
- On API 29+, `BootstrapAppComponentFactory.instantiateClassLoader()` must delegate to the original `AppComponentFactory` before Framework creates Application, returning its ClassLoader directly to Framework. Pass an `ApplicationInfo` with original Application and AppComponentFactory names restored. Cache the original factory for reuse by `BootstrapApplication`; never call `instantiateClassLoader()` again in `attachBaseContext()`.
- `BootstrapApplication.attachBaseContext()` creates and attaches the original Application. Startup `ContentProvider`s then run before Application-reference replacement in `BootstrapApplication.onCreate()`. During that window, after original Application creation, `BootstrapApplication.getApplicationContext()` returns the original instance so a Provider calling `context.getApplicationContext()` sees a normal Application. `ContentProvider.getContext()` remains the Bootstrap Context retained by Framework at `attachInfo()` and is outside this compatibility behavior.
- `BootstrapApplication` must forward `registerActivityLifecycleCallbacks()` / `unregisterActivityLifecycleCallbacks()` to the created original Application. Framework dispatches through `Activity#getApplication()`; after substitution that instance is the original Application, so callbacks left on bootstrap will never fire. `moveActivityLifecycleCallbacks()` migrates once in `onCreate()` and covers only the interval before original Application creation; it cannot replace forwarding.
- If `replaceApplication()` replaces no `LoadedApk#mApplication`, log a warning. That field is the sole source of `Activity#getApplication()`. If every replacement misses, Activities keep the bootstrap instance and business `ActivityLifecycleCallbacks` silently stop firing, with no exception or crash for diagnosis.
- A ResourcesManager hook can call `InstrumentationHooks.isEnableHotfix()` before `HotfixLoader.init()`. `overlayFilesDir` is then uninitialized; return false provisionally without caching the decision, and reread the compat flag after initialization.
- Framework hook transformation follows best-effort behavior: if a target class is absent or one `RetransformClasses` fails, warn and continue other transforms without treating the whole Jugg agent as unavailable. Failure of foundational steps such as JVMTI capability or class-file-load-hook event still counts as agent instrumentation failure.
- Both `ResourcesManager#createAssetManager` exit-hook signatures must return immediately under compat deployment. Otherwise ordinary-mode `tryFixOutSideApk()` can delete `resource.ap_` under `code_cache/.overlay` as though it were Apply Changes overlay, leaving the new Activity's AssetManager without app package ID `0x7f`.
- Keep `ClassLoader#getResource` hook early-return and fail-open: return early only with a nonempty overlay URL; on miss or exception, continue the original method. An exit hook would run the original lookup first and lose true overlay-first semantics.
- The reliable refresh boundary for a ClassLoader resource is process restart, not Activity recreation. Compose resources and `JarURLConnection` may both cache old results.
- Flutter JNI refresh can run only after an Engine is attached and Dart has started. An AssetManager sent to JNI before Dart launch is overwritten by Flutter's own `RunBundleAndSnapshotFromLibrary()`. Do not simply skip an Engine whose Dart has not started and wait for an uncertain future ResourcesManager hook.
- Increment `agentVersion` in root `build.gradle` after changing runtime classes such as `FlutterAssetRefresh` so the device loads a new `jugg-instruments.jar`.

---

## 6. Hidden Constraints

- `JuggSettings.isEnableCompatibleDeploymentMode` and `finalIsEnableCompatibleDeploymentMode` are always `true`; `pushAgentToApps()` and `attachAgentToApps()` have no user-facing off switch.
- Install has no incremental deployment files, so `isNeedPushAgentAfterDeploy()` returns false immediately. Absence of agent after install does not establish a push failure.
- For an Apply Changes-compatible app, `isNeedPushAfterDeploy()` must see both the Jugg agent and a non-Jugg `.so` startup agent. Direct app sandbox transport prepares and reuses Jugg startup agent itself, rather than having the post-deploy check push it again.
- `isHasJvmtiCompatIssue()` waits at most 3 seconds, polling every 100 ms. It continues waiting for apps returning `null` and concludes when all apps have non-null results.
- The not-available flag outranks available. If both exist during investigation, treat JVMTI as unavailable first, clear app `code_cache`, and retest.
- `AsStartupAgentPusher` needs no running app process. It uses the agent `.so` resolved from host matryoshka and places it in the app sandbox with `run-as cp`.
- `CompatDeployHelper` reads `ro.product.manufacturer`. When trimmed value equals `asus` case-insensitively, compat deployment is enabled for every app. This automatic policy writes no device compatibility record, so More Options' manual Force option is neither auto-selected nor able to disable it.
- `CompatDeployHelper` reads `hw_sc.build.platform.version`. Any nonempty value identifies HarmonyOS and enables compat deployment regardless of system version. This automatic policy writes no device compatibility record, so More Options' manual Force option is neither auto-selected nor able to disable it.
- `jugg_agent_setup.sh` no longer creates `.need_fix_dex_path_list` by HarmonyOS version. Do not proactively clear an old flag left from an earlier version; it could erase state created by `DexPathListFixer` self-detection.
- An app sandbox already containing the current agent version does not replace its `.so` for a newly resolved ABI. If an old-architecture agent remains after app ABI changes, reinstall to clear sandbox and prepare again.
- When rebuilding Dex paths, `AndroidNClassLoader` uses `sourceDir + splitSourceDirs` only without isolated split loading. With no split APK, enabled isolated split loading, or unreliable isolation detection, retain the previous base-APK filtering. Do not derive split paths only from old `dexElements`: an installed split APK may not be in that array during early app startup.
- When `AndroidNClassLoader` updates `DrawableInflater#mClassLoader`, Android 12+ hidden API returns `NoSuchFieldException` for targetR-and-above apps; some ROMs remove the field as well. For this specific `NoSuchFieldException`, best-effort skip and warn, leaving XML drawables on the original ClassLoader. Preserve existing behavior for other exceptions (ignore for Incremental APK, throw otherwise); do not broaden this into swallowing every exception.
- `DexPatchLoader` replaces App ClassLoader only when it collected embedded/overlay Dex. For resource-only overlay, skip injection so `HotfixLoader` can continue to `ResourcesPatchLoader` instead of failing app startup on framework-private fields when there is no Dex.

---
## 7. Investigation Entry Points

| Symptom | Start with |
|---|---|
| Agent bundle did not update | `agentVersion` in root `build.gradle`, then directory timestamp and file count under `/data/local/tmp/jugg/{version}`. |
| `view-inspect` field without parentheses still reports `expected '(' after method name` | Device still uses old `jugg-instruments.jar`; increment `agentVersion`, then deploy/restart the app. |
| No Jugg agent in app sandbox | `JuggJvmtiAgentManager.pushAgentToApp()`, `setupAgent()`, `jugg_agent_setup.sh`. |
| Wrong 32/64-bit `.so` selected | Check `AppAbiResolver` source log, `primaryCpuAbi` in `dumpsys package`, Manifest `use32bitAbi`, and APK ARM libraries; Direct app sandbox should use the already resolved arch. |
| JVMTI judged unavailable after deployment | `JuggJvmtiAgentManagerHelper.isHasJvmtiCompatIssue()` and `.jugg_jvmti_not_available`. |
| Detection never concludes | Check whether app restarted, whether `code_cache` exists, and whether native `Agent_OnAttach` wrote a flag. |
| Direct Overlay lacks AS startup agent | `AsStartupAgentPusher.hasApplyChangesStartupAgent()` and `pushApplyChangesStartupAgent()`. |
| ASUS does not enter compat deployment | Does `CompatDeployHelper.isEnableCompatDeploy()` read `ro.product.manufacturer` as `asus`, ignoring case and surrounding whitespace? |
| HarmonyOS does not enter compat deployment | Check `hw_sc.build.platform.version` read by `CompatDeployHelper.isEnableCompatDeploy()`; `JuggSettings.finalIsEnableCompatibleDeploymentMode` should always be `true`. |
| WebView initialization reports `Already registered a list of actions in this process` | Check `assetManager hook action=fix`, non-host `resDir`, and whether `ApplyChangesOverlayPolicy` recorded host APK paths. |
| Under compat deployment, Application resources work but Activity reports `Resources$NotFoundException` | Check whether `isEnableHotfix()` cached false too early and whether `createAssetManagerNewExit()` removed `resource.ap_`. |
| Business `ActivityLifecycleCallbacks` never fire | First check for warning `replaceApplication: no LoadedApk#mApplication replaced`. If absent, compare identity of Activity `getApplication()` with business Application to see whether registration and dispatch use the same instance. |
| Legacy Compose resource remains old | Check `java/lang/ClassLoader` retransformation, `Classpath resource hook in`, overlay-hit source, and whether the process restarted after deployment. |
| Flutter asset remains old after Apply Changes | Check for `assetManager hook action=skip`, `FlutterEngine@… updated through FlutterJNI` (Dart already started), or `… will start Dart with the new AssetManager` (Dart not started). If absent, investigate `Flutter asset refresh skipped` and `not refreshed for N engine(s)` causes: no Application, overlay, Engine, or native shell, or unreadable contract. If refresh succeeded but a value remains old, test an uncached key or `cache: false` to exclude Dart `rootBundle` caching of the same key. |

---

## 8. Related Documents

- Deployment core: `03_deploy_core.md`
- Complete flow: `03_deploy_complete.md`
- Direct Overlay and compatibility-layer entry point: `04_engineering_compat.md`
- ViewHierarchy / MCP layout verification: `08_mcp_layout_verify_design.md`
