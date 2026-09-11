# 运行时与 JVMTI 支持

> 最后核对：2026-09-11
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 文档定位

本页描述 Apply Changes Agent、Jugg JVMTI Agent 与 IDE 部署编排的职责边界，以及 Jugg 如何准备 startup agent、判断设备是否可用 JVMTI、安装运行时 hook，并在必要时触发兼容部署重试。

本页不展开 Direct Overlay 的传输细节、ViewHierarchy LocalSocket 协议、完整 install/code swap 流程；对应入口见 `03_deploy_core.md`、`03_deploy_complete.md`、`08_mcp_layout_verify_design.md`。

---

## 2. 核心源码索引

| 类/文件 | 路径 | 作用 |
|---|---|---|
| `AppAbiResolver` | `idea/src/main/java/com/sickworm/intellij/jugg/deploy/AppAbiResolver.kt` | 在部署入口聚合进程、已安装包、Manifest、APK 和设备证据，解析目标 ARM 位数 |
| `ApkInfoReader` | `main/src/main/java/com/sickworm/intellij/jugg/apk/ApkInfoReader.kt` | 跨 base/split APK 聚合 ARM native library，并读取 `android:use32bitAbi` |
| `JuggJvmtiAgentManager` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/JuggJvmtiAgentManager.kt` | 管理 Jugg agent bundle 的 push、app sandbox setup、attach 与清理 |
| `JuggJvmtiAgentManagerHelper` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/JuggJvmtiAgentManagerHelper.kt` | 决定部署后是否需要补 push agent，读取 flag 文件判断 JVMTI 可用性 |
| `JuggDeployerHelper` | `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployerHelper.kt` | 在 deploy 前后串联 async agent 检查、push、restart、JVMTI compat 检测和 retry |
| `DeployRetryHandler` | `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/flow/DeployRetryHandler.kt` | deploy 失败后通过 run host 触发 JVMTI 检测，必要时切换 compat deploy |
| `AsStartupAgentPusher` | `idea/src/main/java/com/sickworm/intellij/jugg/deploy/direct/AsStartupAgentPusher.kt` | Direct Overlay 路径推 Android Studio Apply Changes startup agent，不依赖 app 进程在线 |
| `DirectHotReloadWriter` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/DirectHotReloadWriter.kt` | 为 Direct app sandbox transport 生成 dynamic attach 请求、复制请求级 agent、等待结果文件 |
| `native-lib.cpp` | `jvmti_agent/src/main/cpp/native-lib.cpp` | `Agent_OnAttach` 入口，区分 startup dataDir 与 `jugg_hot_reload:` dynamic attach options |
| `class_redefiner.cc` | `jvmti_agent/src/main/cpp/class_redefiner.cc` | 解析 Hot Reload 请求；按官方职责边界在 native 侧更新 Application ClassLoader 的 in-memory DEX elements，再匹配已加载类并 batch `RedefineClasses()`，原子写结果 |
| `instrumenter.cc` | `jvmti_agent/src/main/cpp/instrumenter.cc` | 加载 `jugg-instruments.jar`，设置 class file load hook 并 retransform 目标类 |
| `InstrumentationHooks` | `jvmti_agent/src/main/java/com/sickworm/intellij/jugg/instrument/InstrumentationHooks.java` | 处理 ResourcesManager、ClassLoader resource 等 framework hook；compat deploy 启用后必须跳过普通 Apply Changes overlay 修正 |
| `ApplyChangesOverlayPolicy` | `jvmti_agent/src/main/java/com/sickworm/intellij/jugg/instrument/ApplyChangesOverlayPolicy.java` | 记录宿主 APK 路径，判断非宿主资源环境是否需要移除 Apply Changes overlay |
| `ResourceOverlays` | `jvmti_agent/src/main/java/com/sickworm/intellij/jugg/instrument/ResourceOverlays.java` | 将展开 APK 目录中的资源和 assets 接入 Android 11+ ResourcesLoader；限 Direct sandbox 标记和宿主 APK，兼容部署沿用资源 APK |
| `HotfixLoader` | `jvmti_agent/src/main/java/com/sickworm/intellij/jugg/hotfix/HotfixLoader.java` | 初始化 app code cache 路径，识别 compat flag，并安装 dex/resource patch |
| `jugg_agent_setup.sh` | `jvmti_agent/src/main/script/jugg_agent_setup.sh` | 在 app `code_cache/startup_agents` 中放置版本化 agent so |
| `buildAgentBundle.gradle` | `jvmti_agent/buildAgentBundle.gradle` | 将 Jugg runtime 与预处理后的 Dragonfly JAR 编译进 `jugg-instruments.jar`，并打包 64/32 位 so 和 setup script，生成 plugin resource |

---

## 3. 核心状态模型

| 状态/文件 | 所在位置 | 语义 |
|---|---|---|
| Jugg agent bundle | `/data/local/tmp/jugg/{AGENT_VERSION}` | 设备全局临时目录，包含 `jugg-instruments.jar`、64/32 位 so、setup script |
| App startup agent | `{app}/code_cache/startup_agents/{version}-jugg_jvmti_agent(.so/_alt.so)` | app sandbox 内真正被系统加载的 startup agent |
| Direct instrumentation JAR | `{app}/code_cache/startup_agents/{version}-jugg-instruments.jar` | 仅 Direct app sandbox 复制；让 app 进程可映射 instrumentation class，普通 `run-as` 路径继续使用全局 JAR |
| Apply Changes agent | `{app}/code_cache/startup_agents/{versionHash}-{dollName}` | Direct Overlay 复用的 AS startup agent；由 `AsStartupAgentPusher` 推送 |
| `.jugg_jvmti_available` | `{app}/code_cache/.jugg_jvmti_available` | native `Agent_OnAttach` 成功取得 JVMTI/JNI 后写入；不表示所有可选 framework hook 都成功 |
| `.jugg_jvmti_not_available` | `{app}/code_cache/.jugg_jvmti_not_available` | native 无法取得 JVMTI/JNI 时写入，触发 compat device record |
| compat device record | `CompatDeployHelper` 管理 | 某 app/device 已知 JVMTI 不可用后，后续直接进入兼容部署 |

`JuggJvmtiAgentManagerHelper.isJvmtiAvailable()` 优先读 not-available flag，再读 available flag；两个 flag 都没有时返回 `null`，表示 app 启动或 agent 初始化状态尚不确定。

### 3.1 Apply Changes 与 Jugg 的职责边界

满足 `run-as` 可回滚写入、原始 UID 位于 `10000..19999`，且探针与既有 `code_cache` 的 SELinux context 一致的应用继续复用 Android Studio Apply Changes 热重载通道。前提不成立、但 Jugg 能通过普通 shell、root adbd 或非交互 `su` 完整访问 app dataDir 时，Jugg 复用同一个自有 agent，通过 dynamic attach 执行 class redefinition。

同一个 Jugg agent 有两种入口：startup options 为 app dataDir，负责 overlay 加载、JVMTI 检测和 framework hook；dynamic options 为 `jugg_hot_reload:<requestDir>`，处理本次 class redefine、资源刷新，并按请求标志选择是否重建 Activity。Direct app sandbox transport 先持久化 Direct Overlay，dynamic attach 失败时重启进程，复用 startup 入口加载同一 overlay。

---

## 4. 核心调用链路

### 4.1 常规部署后的 Jugg agent 协同

```text
JuggDeployerHelper.runTask()
  -> 异步调用 JuggJvmtiAgentManagerHelper.isNeedPushAgentAfterDeploy()
     install 直接跳过；增量部署检查 app sandbox 中 Jugg agent 和 AS apply changes agent 是否齐备
  -> JuggDeployTask.run()
     先完成 install / apply changes / apply changes and restart activity
  -> detectJob.await() 后按需 JuggJvmtiAgentManagerHelper.pushAgentToApps()
     push bundle 到 /data/local/tmp/jugg/{AGENT_VERSION}，再通过 AppSandboxExecutor 执行 setup script
  -> 根据部署数据和用户设置决定 restart/start/no-op
  -> 若本轮 push 过 agent 且会 restart app，调用 isHasJvmtiCompatIssue()
     等待 native flag 文件，失败时记录 compat device 并抛出 redeploy-with-compat 信号
```

push agent 放在部署之后，是为了避免 Android Studio Apply Changes 首次部署清理 startup agents 后把 Jugg agent 删掉。JVMTI 检测必须等 restart 后进行，因为 startup agent 只有 app 进程启动时才会被系统加载。

`AppSandboxExecutor` 统一包装 setup script：Apply Changes 兼容应用使用 `run-as`；不兼容应用在真实 `dataDir` 以本轮固定的普通 shell、root adbd 或非交互 `su` 模式执行。普通文件修正为既有 `code_cache` 的 owner 与动态 MCS context，JVMTI `.so` 修正为 appdomain 可执行的 `apk_data_file:s0`；修复阶段输出不会混入 setup script 的 `success`/`failed` 结果。上层 agent manager 不再分别拼装权限命令。

部署入口只解析一次目标 ARM 位数，并把结果传给 Direct app sandbox 与后续 Apply Changes transport。优先级依次为：运行中进程、已安装包的 `primaryCpuAbi`、Manifest `android:use32bitAbi`、全部 base/split APK 中唯一可确定的 ARM native library 位数、设备主 ABI，最后保留 64 位缺省值。APK 同时包含 32/64 位 ARM library 或完全没有 ARM library 时保持 unknown，避免单个无 native library 的 resource split 覆盖其它 APK 的有效证据。Direct app sandbox 准备 startup agent 时必须复用这个结果，不能在已停止进程上再次调用进程架构探测。

### 4.2 失败重试中的兼容检测

```text
DeployRetryHandler.tryRetry()
  -> deployRunHost.detectJvmtiCompatIssue()
  -> JuggJvmtiAgentManagerHelper.isNeedPushAgentAfterDeploy()
     如缺 agent，先 push 再 restart app
  -> isHasJvmtiCompatIssue()
     命中 not-available flag 时记录 compat device，下一轮切 compat deploy
```

这条链路只判断“当前失败是否可能由 JVMTI 兼容性导致”。已经处于 compat deploy 的设备会跳过检测，避免重复记录和循环重试。

### 4.3 Native agent 启动

```text
系统加载 startup agent
  -> Agent_OnAttach(vm, options = app data dir)
  -> 尝试取得 JVMTI 和 JNI
     失败写 .jugg_jvmti_not_available
  -> 成功写 .jugg_jvmti_available
  -> HandleStartupAgent()
     AddCapabilities
     Direct sandbox 优先加载 app-local jugg-instruments.jar；否则使用 /data/local/tmp 中的全局 JAR
     instrument Application / AppComponentFactory / Resources
```

`options[0] == '/'` 时按 startup agent 处理；`jugg_hot_reload:` 前缀进入 dynamic redefine。dynamic 分支不写 startup 可用性 flag，也不重复安装 framework instrumentation。

### 4.4 Direct app sandbox dynamic redefine

```text
Direct Overlay 已提交
  -> request.txt + Dex 写入 code_cache/jugg_hot_reload/<requestId>
  -> 为本次请求复制独立 agent so
  -> am attach-agent <pid> <agent>=jugg_hot_reload:<requestDir>
  -> NEW class DEX 转为 in-memory dex elements，并追加到 Application ClassLoader
  -> MODIFIED class 执行 GetLoadedClasses、IsModifiableClass 与 batch RedefineClasses
  -> refreshResources=true 时更新 ResourcesLoader providers 并挂载到现存宿主 Resources
  -> restartActivity=true 时在同一个主线程任务中对当前进程全部存活 Activity 执行 recreate()
  -> result.tmp rename result.txt
```

只有 `OK` 表示请求完成。V4 请求显式区分 `NEW` 与 `MODIFIED`：新增 class 不参与 JVMTI redefine。native 侧按官方职责边界从 `ActivityThread.currentApplication()` 取得 Application ClassLoader，读取并写回 `DexPathList.dexElements`；Java `DexUtility` 只负责复用 `makeInMemoryDexElements` 创建新 elements，并按 `old + new` 顺序合并。JNI 失败结果保留异常类型和消息，上层明确说明本轮新增类无法在线加载、将重启进程使用已提交 overlay。modified class 仍通过 JVMTI batch redefine。空请求和纯资源请求都允许不携带 class，因此 `[nothing to deploy]` 会完成 overlay checkpoint 与 runtime 请求，不会因 payload 为空自动重启。`refreshResources=false` 保持纯 class HOT_RELOAD 语义，`refreshResources=true` 对齐 Apply Changes 的资源切换顺序。

`restartActivity=true` 在同一主线程任务中先刷新资源，再像 Apply Changes 一样遍历 `ActivityThread.mActivities` 并重建当前进程全部存活 Activity；读取失败时从 `WindowManagerGlobal` 收集窗口关联 Activity 作为回退，不重新调用 Android Studio `fullSwap/overlaySwap`。modified class 未加载时返回 `CLASS_NOT_FOUND`，Host 会明确说明当前进程无法 redefine 该 class，并重启 App 让已提交 overlay 生效；Host 仍兼容旧 agent 的 `MISSING` 结果。`UNMODIFIABLE`、JVMTI redefine error、attach 失败、结果超时、资源刷新或 Activity 重建失败都通过 `needsRestart` 降级为整应用重启，由 startup agent 加载已提交的同一 overlay。

终态结果返回后 Host 删除对应请求目录；超时请求在下一次请求开始前清理，避免与仍在执行的 agent 竞争文件，同时限制请求 Dex 和动态 agent so 的累积。

`.jugg_jvmti_available` 在取得 JVMTI/JNI 后、进入 `HandleStartupAgent()` 前写入。因此它只证明 JVMTI 基础环境可取得，不能用于断言后续每个 framework hook 都已安装成功。

Direct transport 复制 app-local JAR 时沿用 `AppSandboxExecutor` 的 owner/SELinux 修复，并把部署入口解析的 app arch 传给 `pushAgentToApp(packageName, sandboxExecutor, appArch)`；普通 App 的无 sandbox manager 调用及 Android Studio deploy transport 不改变。

### 4.5 非宿主资源的 Apply Changes overlay 修正

Apply Changes 可能把宿主应用的 resource overlay 带入非宿主包的 `AssetManager`。WebView provider 初始化时如果拿到包含宿主 overlay package id 的资源环境，可能触发 `java.lang.IllegalStateException: Already registered a list of actions in this process` 并导致 WebView 崩溃。

Jugg 在 `ResourcesManager#createAssetManager` 的新旧签名中记录当前 `ResourcesKey.mResDir`，并与宿主 `ApplicationInfo` 中的 APK 路径比较：

```text
创建 AssetManager
  -> resDir 属于宿主 APK：保留 Apply Changes overlay
  -> resDir 不属于宿主 APK：移除 code_cache/.overlay 中的宿主 overlay
```

宿主 APK 路径尚未记录时，策略退回到旧的 `/data/app` 路径判断。该修正只处理非宿主资源环境，不能删除宿主 Activity 正常热更新所需的 overlay；compat deploy 启用时也必须跳过这条普通 Apply Changes overlay 修正。

### 4.6 Direct sandbox 普通资源 overlay

Direct transport 在 agent 准备阶段写入 `.jugg_direct_resource_overlay`；startup agent 使用系统传入的真实 dataDir 初始化资源路径。迁移自 Android Studio 的 `ResourceOverlays` 通过 `ResourcesProvider.loadFromDirectory()` 消费 `.overlay/*.apk` 中的资源和 assets，不额外生成资源 APK。`LoadedApk.getResources()` 的 entry hook 核对 dataDir 并收集宿主 base/split APK 路径，exit hook 只向包含宿主 APK 的 Resources 添加共享 loader。非宿主 Resources 和没有 Direct 标记的普通 Apply Changes 保持原路径。

Android 11+ 的运行中资源请求会重新扫描已提交目录，通过 `ResourcesLoader.setProviders()` 更新已有 loader，再遍历 `ResourcesManager` 中现存的宿主 Resources 并补挂 loader，随后重建当前进程全部存活 Activity，包括其它 task 和多窗口实例。纯资源请求允许 Dex 列表为空；代码与资源混合时先完成 class redefine。资源刷新和 Activity 重建在同一个主线程任务中顺序执行，成功后保留进程；失败时外层重启 App，startup agent 继续从已提交 overlay 恢复。

Android 11 以下不使用 ResourcesLoader；兼容部署 flag 存在时也不加载普通目录，由既有 `resource.ap_` 加载器处理。这两类场景继续通过进程重启生效。

迁移参考：[AOSP ResourceOverlays](https://android.googlesource.com/platform/tools/base/+/refs/heads/mirror-goog-studio-main/deploy/agent/runtime/src/main/java/com/android/tools/deploy/instrument/ResourceOverlays.java)。

Direct app sandbox 与官方 Apply Changes 在 Android 版本、进程与 Activity 覆盖、class payload、切片、状态恢复和诊断协议上的完整边界见 `03_deploy_core.md` §6.4。

### 4.7 ClassLoader resource overlay

legacy Compose resource 会通过 `ClassLoader#getResource()` 读取 APK 根目录文件，而不是通过 `AssetManager` 读取 `assets/`。Jugg 对 `java/lang/ClassLoader#getResource(String)` 做 retransformation，在原方法入口执行 overlay-first 查找：

```text
InstrumentationHooks.classLoaderGetResource(classLoader, name)
  -> 仅接受宿主 Application ClassLoader 或以它为 parent 的子 ClassLoader
  -> code_cache/.overlay/base.apk/<name> 是文件：返回 file URL
  -> resource.ap_ 存在且 ZIP entry <name> 存在：返回 jar:file URL
  -> 未命中或异常：返回 null，继续原始 ClassLoader#getResource
```

hook 不限制资源名。部署到 `.overlay` 的内容是预期覆盖状态，但 `resource.ap_` 分支必须先确认 ZIP entry，不能只因 ZIP 文件存在就截断原始 fallback。

首次进入 hook 只打印一次 `Classpath resource hook in`；每次命中打印 `Classpath resource overlay hit` 并区分 `file` / `resource_ap_`。`resource.ap_` 读取可能受 `JarURLConnection` 缓存影响，部署 APK 根目录 overlay 成功后必须重启 App 进程。

---

## 5. 构建与版本约束

- native 目标库是 `jugg_jvmti_agent`，构建入口是 `jvmti_agent/CMakeLists.txt`。
- `jvmti_agent/buildAgentBundle.gradle` 生成 `BuildConfig.AGENT_VERSION`、`AGENT_BUNDLE_PATH`、flag 文件名，并把 agent bundle 放到 plugin resource 路径。
- `jugg-instruments.jar` 是 native agent 通过 `AddToBootstrapClassLoaderSearch()` 实际加载的 DEX JAR；ViewHierarchy 依赖的 Dragonfly 必须随 Jugg runtime class 一起进入该产物，不能只存在于 Gradle 注入 App 的 `jugg-runtime.jar`。
- Dragonfly 源 DEX JAR 只用于离线预处理。`jvmti_agent/libs/dragonfly/preprocess.sh` 固定并校验 dex2jar、Jar Jar Abrams 版本与 SHA-256，先转为 class JAR并将 Dragonfly API 和其内置 Kotlin、coroutines、Guava、dexlib2 依赖统一重命名到 `com.sickworm.intellij.jugg.internal.dragonfly.**`，再将 dex2jar 生成但缺少 `StackMapTable` 的 Java 8 class version 规范为 Java 6；正式 Gradle 流程只消费仓库中的 `*-jugg.jar`，避免与宿主 App 的同名类冲突及 D8 非线性控制流告警。
- `jugg-runtime.jar` 继续合并相同的预处理 Dragonfly JAR，保持 `GradleApplicationInjector` 的单 runtime JAR 接口；构建同时校验私有 Dragonfly、Kotlin runtime 入口存在且原包 class entry 不存在。Dragonfly 不再依赖宿主 App 提供 Kotlin runtime。
- 工程根 `build.gradle` 的 `agentVersion` 是设备目录、startup agent 文件名前缀和 bundle 文件名的共同版本源。
- 修改 `jvmti_agent` 里的 native、Java runtime（含 `ViewExpressionEvaluator` / `view-inspect` 求值）、setup script 或 bundle 内容后，必须递增 `agentVersion`。`isAgentBundlePushed()` 只看 `/data/local/tmp/jugg/{AGENT_VERSION}` 是否已有 4 个文件；同版本插件更新不会重推，设备会继续加载旧 `jugg-instruments.jar`。
- 32 位 app 使用 `_alt.so`：bundle 打包时把 armeabi-v7a so 改名为 `jugg_jvmti_agent_alt.so`，`attachAgentToApp()` / setup script 都依赖这个约定。
- ABI 解析当前只支持 ARM：`armeabi` / `armeabi-v7a` 映射 32 位，`arm64-v8a` 映射 64 位；不兼容 x86。所有证据都无法确定时仍按 64 位处理，覆盖大部分现有设备。
- Java runtime 入口由 `HotfixLoader` 统一做设备 API 判定；API < 26 时 `init()` 会在访问 `Context.getCodeCacheDir()` 前 return，`install()` / `installDex()` / `isNeedEnableHotfix()` 也会短路。这个判断不改变 Gradle 构建产物，`BootstrapApplication` 注入仍只受 `jugg.inject.application.enable` 控制。
- `BootstrapApplication` 查询不到 application meta-data 时按“没有原始 Application / AppComponentFactory”处理并继续启动；仅在 meta-data 中存在 Jugg 保存的原始类名时才创建和替换对应实例。
- API 29+ 的 `BootstrapAppComponentFactory.instantiateClassLoader()` 必须在 Framework 创建 Application 前委托原始 `AppComponentFactory`，并把原始工厂返回的 ClassLoader 直接交还 Framework。委托时传入恢复了原始 Application 和 AppComponentFactory 类名的 `ApplicationInfo`；原始工厂实例必须缓存并由 `BootstrapApplication` 复用，禁止在 `attachBaseContext()` 中重复调用 `instantiateClassLoader()`。
- `BootstrapApplication.attachBaseContext()` 会创建并 attach 原始 Application；启动 `ContentProvider` 随后执行，早于 `BootstrapApplication.onCreate()` 中的 Application 引用替换。该窗口内 `BootstrapApplication.getApplicationContext()` 在原始 Application 已创建后直接返回原始实例，使 Provider 通过 `context.getApplicationContext()` 获得正常 Application；`ContentProvider.getContext()` 仍是 Framework 在 `attachInfo()` 时保存的 Bootstrap Context，不属于此兼容范围。
- `BootstrapApplication` 必须把 `registerActivityLifecycleCallbacks()` / `unregisterActivityLifecycleCallbacks()` 转发到已创建的原始 Application。Framework 经 `Activity#getApplication()` 分派生命周期回调，替换完成后该实例是原始 Application，留在 bootstrap 实例上的回调永远不会被分派。`moveActivityLifecycleCallbacks()` 只在 `onCreate()` 迁移一次，只能兜住原始 Application 创建之前的窗口，不能替代转发。
- `replaceApplication()` 未替换任何 `LoadedApk#mApplication` 时必须打印 warn。该字段是 `Activity#getApplication()` 的唯一来源，全部未命中时 Activity 仍持有 bootstrap 实例，业务注册的 `ActivityLifecycleCallbacks` 会静默全部失效，且没有异常或崩溃可供定位。
- `InstrumentationHooks.isEnableHotfix()` 可能在 `HotfixLoader.init()` 之前被 ResourcesManager hook 调用。此时 `overlayFilesDir` 尚未初始化，只能临时返回 false，不能缓存判断结果；初始化完成后必须重新读取 compat flag。
- framework hook transform 遵循 Best-effort：目标类不存在或单个 `RetransformClasses` 失败时记录 warning 并继续其他 transform，不把整个 Jugg agent 判为不可用。JVMTI capability、class file load hook event 等基础步骤失败仍按 agent instrumentation 失败处理。
- ResourcesManager 两个 `createAssetManager` 签名的 exit hook 都必须在 compat deploy 启用时直接返回。否则普通模式的 `tryFixOutSideApk()` 会把路径位于 `code_cache/.overlay` 的 `resource.ap_` 当成 Apply Changes overlay 删除，导致新 Activity 的 AssetManager 丢失应用包 ID `0x7f`。
- `ClassLoader#getResource` hook 必须保持 early-return + fail-open：只有 overlay URL 非空时提前返回，未命中和异常继续原方法。不要改回 exit hook，否则原始 resource lookup 会先执行，失去真正的 overlay-first 语义。
- ClassLoader resource 的可靠刷新边界是进程重启，不是 Activity 重建。Compose resource 与 `JarURLConnection` 都可能缓存旧结果。

---

## 6. 隐形约束

- `JuggSettings.isEnableCompatibleDeploymentMode` 与 `finalIsEnableCompatibleDeploymentMode` 恒为 `true`，`pushAgentToApps()` 和 `attachAgentToApps()` 不提供用户关闭入口。
- install 没有增量部署文件，`isNeedPushAgentAfterDeploy()` 直接返回 false；不要用 install 后缺 agent 判断为 push 失败。
- Apply Changes 兼容应用的 `isNeedPushAfterDeploy()` 要同时看到 Jugg agent 和非 Jugg 的 `.so` startup agent。Direct app sandbox transport 自行准备并复用 Jugg startup agent，不再由部署后检查重复补 push。
- `isHasJvmtiCompatIssue()` 最多等待 3 秒，每 100ms 轮询一次；返回 `null` 的 app 会继续等，全部 app 都非 null 才收口。
- not-available flag 优先级高于 available flag；排查时如果两个都存在，应先按不可用处理并清理 app `code_cache` 后复测。
- `AsStartupAgentPusher` 推 AS agent 的路径不要求 app 进程在线；它用 host matryoshka 解析出的 agent so，经 `run-as cp` 放进 app sandbox。
- `CompatDeployHelper` 读取 `ro.product.manufacturer`；值去除首尾空白后等于 `asus`（忽略大小写）时，所有 App 都直接启用 compat deploy。该自动策略不写入设备兼容记录，因此 More Options 的手动 Force 选项不会自动勾选，也不能用来关闭自动策略。
- `CompatDeployHelper` 读取 `hw_sc.build.platform.version`；属性非空时即识别为 HarmonyOS 并直接启用 compat deploy，不限制系统版本。该自动策略不写入设备兼容记录，因此 More Options 的手动 Force 选项不会自动勾选，也不能用来关闭自动策略。
- `jugg_agent_setup.sh` 不再按 HarmonyOS 版本创建 `.need_fix_dex_path_list`。升级前已经存在的旧 flag 不在本轮主动清理，避免误删 `DexPathListFixer` 自检测产生的状态。
- app sandbox 已存在当前 agent 版本时不会按新解析结果替换其 so。App ABI 变化后若残留旧架构 agent，需要通过重装 App 清理 sandbox 后重新准备。
- `AndroidNClassLoader` 重建 dex path 时，仅在非 isolated split 场景使用 `sourceDir + splitSourceDirs`；无 split APK、启用 isolated split loading 或无法可靠识别隔离状态时继续沿用原有 base APK 筛选。不能只从原 `dexElements` 取 split 路径，因为应用早期启动阶段已安装的 split APK 可能尚未挂入该数组。

---

## 7. 排查入口

| 现象 | 优先入口 |
|---|---|
| agent bundle 未更新 | 根 `build.gradle` 的 `agentVersion`，再查 `/data/local/tmp/jugg/{version}` 目录时间戳与文件数 |
| `view-inspect` 无括号字段仍报 `expected '(' after method name` | 设备仍在用旧 `jugg-instruments.jar`；确认 `agentVersion` 已递增后再部署/重启 App |
| app sandbox 中没有 Jugg agent | `JuggJvmtiAgentManager.pushAgentToApp()`、`setupAgent()`、`jugg_agent_setup.sh` |
| 32/64 位 so 选错 | 检查 `AppAbiResolver` 的 source 日志、`dumpsys package` 中的 `primaryCpuAbi`、Manifest `use32bitAbi`、APK ARM library；Direct app sandbox 应直接使用已解析 arch |
| 部署后被判 JVMTI 不可用 | `JuggJvmtiAgentManagerHelper.isHasJvmtiCompatIssue()`，检查 `.jugg_jvmti_not_available` |
| 检测一直不收口 | app 是否 restart、`code_cache` 是否存在、native `Agent_OnAttach` 是否写 flag |
| Direct Overlay 缺 AS startup agent | `AsStartupAgentPusher.hasApplyChangesStartupAgent()` 与 `pushApplyChangesStartupAgent()` |
| ASUS 未进入兼容部署 | `CompatDeployHelper.isEnableCompatDeploy()` 读取的 `ro.product.manufacturer` 是否为 `asus`（忽略大小写与首尾空白） |
| HarmonyOS 未进入兼容部署 | `CompatDeployHelper.isEnableCompatDeploy()` 读取的 `hw_sc.build.platform.version`；`JuggSettings.finalIsEnableCompatibleDeploymentMode` 应恒为 `true` |
| WebView 初始化报 `Already registered a list of actions in this process` | 检查 `assetManager hook action=fix`、非宿主 `resDir` 和宿主 APK 路径是否已由 `ApplyChangesOverlayPolicy` 记录 |
| compat deploy 中 Application 资源正常、Activity 报 `Resources$NotFoundException` | 检查 `isEnableHotfix()` 是否过早缓存 false，以及 `createAssetManagerNewExit()` 是否删除了 `resource.ap_` |
| 业务 `ActivityLifecycleCallbacks` 完全不回调 | 先看 `replaceApplication: no LoadedApk#mApplication replaced` warn 是否出现；未出现时对比 Activity `getApplication()` 与业务 Application 的 identity，确认注册与分派是否落在同一实例 |
| legacy Compose resource 仍是旧值 | 检查 `java/lang/ClassLoader` retransformation、`Classpath resource hook in`、overlay hit 来源，以及部署后是否重启进程 |

---

## 8. 关联文档

- 部署核心：`03_deploy_core.md`
- 完整流程：`03_deploy_complete.md`
- Direct Overlay 与兼容层入口：`04_engineering_compat.md`
- ViewHierarchy / MCP 布局验证：`08_mcp_layout_verify_design.md`
