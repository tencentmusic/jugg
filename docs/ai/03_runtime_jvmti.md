# Runtime Agent and JVMTI Coordination

> Last verified: 2026-10-07
> Consistency rule: If documentation conflicts with code, code takes precedence.

## 1. Scope

This page explains the boundary among Android Studio Apply Changes, Jugg's startup and dynamic JVMTI agent, compat runtime, and Host deployment state. For install and overlay selection, see `03_deploy_core.md`; for rootless system-app transport, see `03_deploy_system_app.md`.

## 2. Core Source Index

| Entry | Location | Responsibility |
|---|---|---|
| `JuggDeployOrchestrator` / `DeployRetryHandler` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/run/` | Order deploy, agent push, restart, compatibility detection, and rootless import confirmation. |
| `JuggJvmtiAgentManager` / `JuggJvmtiAgentManagerHelper` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/` | Prepare agent bundle and app sandbox copy; read availability flags and trigger compat records. |
| `AppAbiResolver` / `AppAbiCache` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/` | Select ARM bitness once per device/package/APK set. |
| `AppSandboxExecutor` / `AsStartupAgentPusher` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/`; `main/src/main/java/com/sickworm/intellij/jugg/deploy/direct/` | Distinguish Apply Changes access from direct sandbox access and prepare the AS startup agent for Direct Overlay. |
| `DirectHotReloadWriter` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/DirectHotReloadWriter.kt` | Send request-scoped dynamic attach and read terminal result. |
| `native-lib.cpp` / `class_redefiner.cc` / `instrumenter.cc` | `jvmti_agent/src/main/cpp/` | Separate startup from dynamic attach, perform class load/redefine, and install framework hooks. |
| `InstrumentationHooks` / `ApplyChangesOverlayPolicy` / `ResourceOverlays` | `jvmti_agent/src/main/java/com/sickworm/intellij/jugg/instrument/` | Keep host resource overlays out of non-host resources and attach Direct resource providers. |
| `FlutterAssetRefresh` | `jvmti_agent/src/main/java/com/sickworm/intellij/jugg/instrument/FlutterAssetRefresh.java` | Refresh each live Flutter Engine's AssetManager and prepare host package contexts for new Engines. |
| `BootstrapApplication` / `HotfixLoader` / `RootlessCompatDeployImporter` | `jvmti_agent/src/main/java/com/sickworm/intellij/jugg/hotfix/` | Initialize code-cache overlay paths, import rootless requests before load, and preserve the business Application lifecycle. |

## 3. Agent and Deployment State

| State | Meaning and limit |
|---|---|
| `/data/local/tmp/jugg/{AGENT_VERSION}` | Global agent bundle. Its four-file existence check is version-based, not a byte-for-byte refresh check. |
| App `code_cache/startup_agents` | Jugg and AS startup `.so` files actually used on process start. Direct sandbox also copies an app-readable `jugg-instruments.jar`; ordinary `run-as` uses the global JAR. |
| `.jugg_jvmti_available` | Native startup obtained JVMTI/JNI. It is written before later framework instrumentation, so optional hook success is not implied. |
| `.jugg_jvmti_not_available` | Native startup could not obtain JVMTI/JNI. When both flags exist, this one wins. |
| No availability flag | Unknown startup/agent state, not evidence that JVMTI is available or unavailable. |
| Compat device record | A known unavailable app/device enters compat deployment on later runs; automatic ASUS/HarmonyOS policy does not create this manual record. |

`JuggSettings.finalIsEnableCompatibleDeploymentMode` is true in the current build. `CompatDeployHelper` also selects compat on pre-Android-11 devices, ASUS (`ro.product.manufacturer`, trimmed and case-insensitive), and a nonempty HarmonyOS `hw_sc.build.platform.version`. These automatic decisions do not toggle the manual Force UI choice.

## 4. Deployment, Startup, and Retry Flow

```text
JuggDeployOrchestrator.execute() starts an asynchronous JuggJvmtiAgentManagerHelper.isNeedPushAgentAfterDeploy() check
  -> JuggDeployTask completes install or incremental transport first
  -> ordinary incremental path calls JuggJvmtiAgentManagerHelper.pushAgentToApps() after deploy when needed
  -> process restart loads startup agent and writes JVMTI availability flag
  -> Host calls isHasJvmtiCompatIssue() when a new agent was pushed and restart occurred
     unavailable -> record compat device and request compat redeploy
  -> a failed ordinary deploy can also ask DeployRetryHandler to detect JVMTI
     and retry once through the existing compat path
```

Install has no incremental files and skips post-install agent push. The push follows Apply Changes because its first deployment can clear startup agents. An Apply Changes-compatible app needs both Jugg and a non-Jugg `.so` startup agent before the helper considers preparation complete. Direct app sandbox transport prepares the agent itself and does not ask this post-deploy check to push again. `AsStartupAgentPusher` can place the AS `.so` without a running process.

The availability check polls at 100 ms intervals for at most 3 seconds and waits until all target apps return non-null flags. A timeout currently returns `false` for “has compat issue” and logs an available conclusion; that conclusion is not direct evidence of native success. Check process restart, app `code_cache`, and native flag-writing logs before classifying a no-flag case. A not-available flag should be judged first; clear app `code_cache` and retest when stale flags conflict.

Deployment resolves target ARM bitness from running process, Manifest `use32bitAbi`, determinable native libraries across base/split APKs, installed `primaryCpuAbi`, device primary ABI, then a 64-bit default. Ambiguous/no APK ARM libraries leave that source unknown; query the installed package only when local evidence cannot decide. `AppAbiCache` keys by serial, package, and sorted APK path/size/mtime; a failed package query yields a usable fallback for this run but is not cached. Direct transport reuses the deployment-entry result after the process stops. Only ARM 32/64-bit agent variants are packaged; the 32-bit copy uses `_alt.so`. A changed app ABI with an existing same-version startup copy can require reinstall to clear the old sandbox agent.

When an older Android Studio startup agent on Android 15+ misses a first modern Compose resource overlay, `ComposeResourceRestartHelper` applies only to the first ordinary full-resource deploy with a compiled `assets/composeResources/**` overlay. It waits briefly for both framework-transform cache entries, then restarts again; Direct and compat paths do not use this extra restart. Cache timeout still restarts, so the message alone does not prove transforms were complete.

### 4.1 Native Startup and Dynamic Attach

`Agent_OnAttach` treats an absolute app dataDir option as startup, writes the availability flag after JVMTI/JNI acquisition, and then installs capabilities, bootstrap `jugg-instruments.jar`, and framework hooks. Missing optional target classes or one failed retransform warn and leave other hooks eligible; foundational JVMTI or class-file-hook failure is a different boundary. `jugg_hot_reload:<requestDir>` enters dynamic attach and does not rewrite startup flags or reinstall all framework hooks.

```text
Direct Overlay commits first
  -> DirectHotReloadWriter.apply() writes request-scoped DEX and result path, then am attach-agent
  -> native agent adds NEW classes as in-memory Dex elements to Application ClassLoader
  -> MODIFIED loaded classes pass IsModifiableClass and batched RedefineClasses
  -> optional Android 11+ resource refresh precedes Activity recreation on main thread
  -> terminal result file lets Host decide live success or process restart
```

New classes are not JVMTI redefinitions; modified classes that are unloaded return `CLASS_NOT_FOUND` (older agents may say `MISSING`). `UNMODIFIABLE`, redefine/attach/result timeout, resource refresh, or Activity recreation failure degrades to a process restart that loads the already committed overlay. Empty and resource-only requests still need a terminal result, not automatic failure. For Activity recreation, the agent traverses the current process's surviving Activities, with a window-root fallback if `ActivityThread.mActivities` cannot be read; it does not reenter Android Studio fullSwap/overlaySwap.

### 4.2 Rootless Compat Startup

When the Host cannot write the app sandbox, it stages a compat request in app external files and prepares no agent. `BootstrapApplication.attachBaseContext()` runs `HotfixLoader.init()` → `RootlessCompatDeployImporter.importPending()` → `isNeedEnableHotfix()` → overlay install. Thus a confirmed import can load on that same process start. The importer serializes requests with a private lock, validates package/protocol/request/digest/expected overlay ID and safe ZIP paths, then commits the new overlay ID last. Only the app's `__JUGG_ROOTLESS_IMPORT__ OK <requestId>` lets `JuggDeployOrchestrator` store the deployment cache; timeout and `FAILED` preserve the prior Host state. Details of staging and system-app conditions are in `03_deploy_system_app.md`.

## 5. Resource and Asset Boundaries

| Path | Active boundary | Failure interpretation |
|---|---|---|
| Apply Changes host overlay | `ApplyChangesOverlayPolicy` records host base/split APK paths and removes the host overlay from a non-host `AssetManager`, such as WebView. Compat deployment skips this repair. | A WebView `Already registered a list of actions in this process` crash needs the resource `resDir` and host-path classification checked before blaming class deployment. |
| Direct ordinary resources | A Direct marker lets `ResourceOverlays` add a shared directory-backed `ResourcesLoader` only to host Resources. Android 11+ live updates rescan providers and recreate Activities; older Android and compat `resource.ap_` use process restart. | A ResourceLoader failure requests restart; a non-host Resource or absent marker should remain unchanged. |
| Flutter raw assets | Startup framework hooks prepare host package contexts; running Engines receive `FlutterJNI.updateJavaAssetManager()`, while Engines whose Dart has not started replace `DartExecutor.assetManager`. | A new `ResourcesImpl` alone does not update Flutter's retained native `AAssetManager`; a pre-Dart JNI update can be overwritten at launch. |
| Legacy Compose APK-root files | `ClassLoader#getResource` entry hook checks `code_cache/.overlay/base.apk/<name>` first, then an existing entry in `resource.ap_`, before original lookup. | A miss/exception must fall through. `JarURLConnection` or client caching makes process restart the reliable visibility boundary. |

Before `ApplyChangesOverlayPolicy` has host APK paths, it uses the older `/data/app` path test. A decision made in that window is weaker evidence than a later host-path comparison.

Flutter handling starts only on Android 11+ outside compat mode and only if the app ClassLoader resolves FlutterEngine. `InstrumentationHooks.handleCreatePackageContextExit()` augments only host-APK contexts, so a new Engine captures overlay-aware raw assets without changing WebView/SDK package contexts. `InstrumentationHooks.createAssetManagerExit()` / `createAssetManagerNewExit()` call `FlutterAssetRefresh.scheduleRefresh()`; its later main-thread batch snapshots all `FlutterEngine.idToEngine` entries, including uncached Engines. A missing app, Flutter class, Engine, or overlay before the batch is a no-op; after an overlay and Engines are confirmed, every failed Engine contributes to one warning and Toast. Check both the running-Dart and not-yet-running-Dart success messages; a silent precondition miss is not a refresh. The agent's own classes load through bootstrap, so Flutter reflection must use the app Context ClassLoader. The framework boundary is used because Flutter classes are not reliably transformable at agent startup.

For raw assets, the overlay is appended last as a native directory `ApkAssets` (`ResourcesProvider.loadFromDirectory(..., null)`), with weak identity deduplication. A Java `AssetsProvider` can abort Flutter's native worker access without `JNIEnv`; the owning code comment explains this local choice. Already loaded data and Dart `rootBundle` caches do not reread, so verify an uncached key or new isolate before calling refresh ineffective. `executeDartCallback()` uses a separate `DartCallback.androidAssetManager` and is outside this path.

The ClassLoader hook accepts the host Application ClassLoader and its children, and confirms a ZIP entry before returning a `jar:file` URL; existence of `resource.ap_` alone is insufficient. `Classpath resource hook in` proves entry only. `Classpath resource overlay hit` identifies a file or ZIP hit, not whether the caller retained an older cached value.

## 6. Compat Runtime and Build Constraints

`BootstrapApplication` constructs the original Application from saved metadata before Provider startup, exposes it through `getApplicationContext()` during that window, and replaces Framework's Application references in `onCreate()`. `BootstrapAppComponentFactory` must delegate `instantiateClassLoader()` to the original factory before Application creation on API 29+. After substitution, Activity lifecycle callbacks must register on the original Application; the one-time migration covers only earlier registrations. If `replaceApplication()` reports no `LoadedApk#mApplication` replacement, Activities can retain bootstrap while business callbacks silently stop. A Provider's own `getContext()` remains Framework's bootstrap Context. Missing original-class metadata means no original component to create.

`HotfixLoader` short-circuits on API < 26 before using code cache. Its compat flag cannot be permanently cached by a ResourcesManager hook before initialization; a provisional false must be checked again. A resource-only compat overlay avoids App ClassLoader replacement because `DexPatchLoader` has no DEX to inject, allowing `ResourcesPatchLoader` to continue. `AndroidNClassLoader` handles base plus split paths only when split isolation is confidently disabled; its local fallback and `DrawableInflater` hidden-field exception are explained beside the implementation.

`jugg_agent_setup.sh` does not create `.need_fix_dex_path_list` based on HarmonyOS version. An existing file may reflect `DexPathListFixer` self-detection; do not clear it merely because setup did not create it this run.

Root `build.gradle` supplies `agentVersion` for bundle and device paths. Change it after native, Java runtime, setup-script, or bundled-content changes: `isAgentBundlePushed()` sees only four existing files at that version, so same-version plugin updates can leave old device code. `jvmti_agent/buildAgentBundle.gradle` puts relocated Dragonfly and Kotlin runtime in both app `jugg-runtime.jar` and bootstrap DEX `jugg-instruments.jar`, checking required entries and absence of original packages. `jvmti_agent/libs/dragonfly/preprocess.sh` pins tool digests, removes unsupported optional classes, and normalizes dex2jar output; runtime must not rely on host-app same-name dependencies. See `06_testing.md` §10 for the JDK/D8 bundle-build diagnostic comparison.

## 7. Investigation Entry Points

| Symptom | First evidence and interpretation boundary |
|---|---|
| New plugin still runs old agent | Compare `agentVersion`, global bundle directory, app startup copy, and loaded `jugg-instruments.jar`; a versioned directory's presence does not prove new bytes. |
| No agent or wrong bitness | Check `JuggJvmtiAgentManager` setup result, ABI-resolution source/cache, `primaryCpuAbi`, and app startup-agent filename. Install itself does not push an agent. |
| JVMTI judged unavailable or detection inconclusive | Compare both flag files, process restart, and native `Agent_OnAttach`; available flag proves acquisition only, while no flag remains unknown even if the timeout log says available. |
| WebView or Activity resources crash | Compare host vs non-host `resDir`, compat flag, and whether `resource.ap_` survived. Hook entry is not proof that repair was applied. |
| Lifecycle callbacks stop without crash | Check `replaceApplication: no LoadedApk#mApplication replaced`, then compare `Activity#getApplication()` identity with the registration owner. |
| Flutter asset remains old | Distinguish precondition skip, per-Engine refresh failure, not-yet-started Dart handoff, and Dart-side cached value. Preserve the Engine-specific logs and test an uncached read. |
| Legacy Compose file remains old | Check ClassLoader retransformation, entry/hit logs, and whether the process restarted after APK-root overlay deployment. |

Before assigning a root cause from a wrapper log, seek the raw flag, DEX/resource state, or native exception that could refute it. Scope conclusions to the current device, app process, agent version, and deployment mode.

## 8. Related Documents

- `03_deploy_core.md` — Apply Changes and Direct Overlay transport.
- `03_deploy_system_app.md` — system placement and rootless staging.
- `03_deploy_complete.md` — Host state commit and recovery.
- `08_mcp_layout_verify_design.md` — ViewHierarchy consumer of agent instrumentation.
