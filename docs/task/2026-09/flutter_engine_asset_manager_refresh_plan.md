# FlutterEngine AssetManager 刷新修改方案

> 创建日期：2026-09-13
> 状态：已完成，并经真实 Jugg 编译部署流程复测确认生效。最终提交已 squash 为 `9fb3b055`；raw asset 调查与历史验证见 §14–§16，真实部署崩溃及线程模型修正见 §17，非 Flutter 影响审计与收口见 §18。
> 当前已证明结果：Apply Changes overlay 生效后，运行中的 FlutterEngine 会取得 overlay-aware AssetManager，后续创建的宿主包上下文也会在交给 Flutter 前补齐 overlay；目录 asset 全程走 Android native provider。非 Flutter App 在 Flutter 类门禁后不投递刷新任务、不读取或修改 package-context AssetManager；无 overlay 时正常 no-op；确认存在 Engine 后刷新失败则按批 warn + Toast 一次。
> 已知边界：已读入内存的 asset 与 Dart `rootBundle` 的同 key 缓存不会自动失效；`executeDartCallback()` 保存的 AssetManager 不在本轮覆盖范围内。

## 1. 问题与事实证据

用户现象：Flutter asset（`flutter_assets/**` 下的普通资源）修改后经 Jugg / Apply Changes 部署，运行中的 App 不生效；重启进程后仍可能不生效。commit `3b3d3ba1` 只处理 Flutter JIT runtime（`kernel_blob.bin` 等）的解压缓存，与本问题无关。

本方案实施前确认的源码级事实（均可复查）：

| # | 事实 | 证据位置 |
|---|---|---|
| F1 | `FlutterEngine` 构造时一次性取得 AssetManager，并交给 `DartExecutor` 长期持有 | Flutter 3.47.2 `FlutterEngine.java` 构造器：`assetManager = context.createPackageContext(context.getPackageName(), 0).getAssets();` → `new DartExecutor(flutterJNI, assetManager, engineId)` |
| F2 | 该 AssetManager 只在 `executeDartEntrypoint()` 中传给 native | `DartExecutor.java`：`flutterJNI.runBundleAndSnapshotFromLibrary(..., assetManager, ...)` |
| F3 | native 侧把该 Java AssetManager 转成 `AAssetManager*` **并缓存**，不随 Java 侧变化 | Flutter 3.47.2 engine `shell/platform/android/apk_asset_provider.cc`：`APKAssetProviderImpl` 构造时 `asset_manager_ = AAssetManager_fromJava(env, jassetManager);`，`GetAsMapping()` 只用这个缓存指针 |
| F4 | Apply Changes 的资源刷新会**替换** AssetManager：`ResourcesManager.applyAllPendingAppInfoUpdates()` 重建 `ResourcesImpl` 并 `Resources.setImpl()` | AOSP 35 `ActivityThread.handleApplicationInfoChanged()`：`ResourcesImpl beforeImpl = getApplication().getResources().getImpl(); ... applyAllPendingAppInfoUpdates(); ResourcesImpl afterImpl = ...` |
| F5 | 重建走 `ResourcesManager.createResourcesImpl()` → `createAssetManager(key, apkSupplier)` → `new AssetManager.Builder()`，并把 `key.mLoaders` 一并挂上 | AOSP 35 `ResourcesManager.java` §createResourcesImpl / createAssetManager |
| F6 | Apply Changes 的 `ResourcesLoader` 会随新 AssetManager 一起带过去 | AOSP 35 `LoadedApk.updateApplicationInfo()`：`getResources(..., mApplication.getResources().getLoaders())` |
| F7 | Flutter 提供官方 API 替换引擎内的 APKAssetProvider | Flutter 3.47.2 `FlutterJNI.updateJavaAssetManager(AssetManager, String)`；engine `platform_view_android_jni_impl.cc` → `UpdateJavaAssetResolverByType(kApkAssetProvider)`，即用新 AssetManager 重建 resolver 并替换旧的 |
| F8 | 引擎集合由 `FlutterEngine` 的静态 `idToEngine` 维护，`FlutterEngineCache` 只是它的子集 | Flutter 3.47.2 `FlutterEngine.java`：`private static final Map<Long, FlutterEngine> idToEngine = new HashMap<>();`（构造时 put，`destroy()` 时 remove）；`FlutterEngineGroup.spawn()` 的引擎同样入表 |
| F9 | asset bundle 目录不是常量，来自 Flutter loader | `FlutterLoader.findAppBundlePath()` 返回 `FlutterApplicationInfo.flutterAssetsDir`；实测 `libflutter.so` 中不存在 `flutter_assets` 字面量，说明该路径确实由 Java 侧下发 |
| F10 | 当前 Android Studio 2025.3.4 的实际 instrument JAR 仍采用共享 `ResourcesLoader` 更新 Android `Resources`，不会主动更新 Flutter 保存的外部 AssetManager 引用 | 设备实际加载的 `instruments-fa4e8d6d.jar` 反编译结果；`/Users/wormchen/aosp/deploy` 只作为实现脉络参考，版本结论以实际 JAR 为准 |
| F11 | Android 35 的 `ContextImpl.createPackageContext()` 可直接走 `createResources(..., resourcesLoader=null)`，不保证继承此前挂在 Application Resources 上的 loader | AOSP 35 `ContextImpl#createPackageContext` / `createResources`；这解释了新 FlutterEngine 或进程重启仍可能取得不含 overlay 的 AssetManager |

### 根因

F3 + F4：Apply Changes 生效后，`Resources` 被 `setImpl()` 指向**新的** `ResourcesImpl/AssetManager`（F4/F5，新对象含 overlay loader，F6），而 FlutterEngine 仍持有**旧**的 Java AssetManager，其 native 侧 `AAssetManager*`（F3）指向旧 native 实例，Dart `rootBundle` 继续读旧 asset。

冷启动路径（F1）：`context.createPackageContext(...).getAssets()` 会新建/复用一份 Resources；新建时同样经过 `createAssetManager`，可能拿不到 Apply Changes 在 `LoadedApk#getResources` 上挂的 loader，因此“重启后仍可能不生效”。

## 2. 目标与非目标

### 2.1 本次目标

- 宿主 APK 的新 AssetManager 被创建后，把**当前 overlay-aware 的 AssetManager** 推送给所有存活 FlutterEngine。
- 只在真实存在 FlutterEngine 且当前 AssetManager 已包含 Apply Changes overlay 时才刷新。
- 已确认 overlay 且检测到一个或多个 FlutterEngine 后，只要本批不能把新 AssetManager 交给其中任一 Engine，就按批次记录一次 warn 并 Toast 一次，提示需要重启 App。
- 完全 fail-open：任何 Flutter 反射或刷新异常都不得影响 Android Resources / Activity 正常流程。

### 2.2 明确不做

- 不修改 Apply Changes（Android Studio deploy）源码，不新增 Host/agent 结果协议。
- 不改 compat deploy：继续走 `resource.ap_` + 进程重启 + `ResourcesPatchLoader`。
- 不刷新 Flutter JIT runtime 解压缓存（已由 `3b3d3ba1` + `FlutterJitCacheInvalidator` 负责）。
- 不新增配置开关、持久化字段、单实现接口，不新增 framework transform。
- 不处理 Flutter Dart 代码热重载（属 Flutter Tool VM Service 范围）。

## 3. 边界：普通 Apply Changes / Direct / compat

| 场景 | 是否执行 Flutter 刷新 | 说明 |
|---|---|---|
| 普通 Apply Changes（Android 8～13 旧签名 / 14+ 新签名） | 是 | 由现有 `createAssetManager*Exit` hook 触发 |
| Direct app sandbox（Jugg 自有 overlay） | 复用同一逻辑 | Direct 用 `resources.addLoaders()` 原地更新，通常不重建 AssetManager；一旦重建（例如 Activity/配置变化）同样会被刷新，属于幂等增强，不为它单独编码 |
| compat deploy | 否 | 两个 hook 开头已有 `if (isEnableHotfix()) return;`，Flutter 逻辑放在其后，天然跳过 |
| 没有 Flutter 类 / 没有存活 Engine / 当前 AssetManager 无 overlay | 否 | 正常 no-op，不打印用户可见日志、不 Toast |

## 4. 触发时序

复用的 hook：`InstrumentationHooks.createAssetManagerExit(AssetManager)`（Android 8～13 签名）与 `createAssetManagerNewExit(AssetManager)`（Android 14+ 签名）。两者在宿主 APK 场景下 `isNeedFixThisAssetManager*` 为 false（overlay 需要保留），当前直接 return；本方案在这个分支追加一次刷新调度。

```text
Apply Changes 生效
  -> ActivityThread.handleApplicationInfoChanged()
     -> ResourcesManager.applyAllPendingAppInfoUpdates()
        -> createResourcesImpl() -> createAssetManager(host resDir, loaders)
           -> InstrumentationHooks.createAssetManagerNewExit()   [主线程]
              -> FlutterAssetRefresh.scheduleRefresh()           [只登记 + post，不做 Flutter 调用]
  -> handleApplicationInfoChanged 返回（全部 Resources 已 setImpl 完成）
  -> 主线程执行 post 的 Runnable
     -> Application.getResources().getAssets()  取当前 overlay-aware AssetManager
     -> 无 overlay / 无 Engine -> return
     -> 遍历 FlutterEngine.idToEngine -> flutterJNI.updateJavaAssetManager(assetManager, bundlePath)
     -> 有失败 -> warn 一次 + Toast 一次
```

关键时序约束与理由：

1. 不在 `createAssetManager` 调用栈内同步调用 FlutterJNI（F4：此刻 `setImpl()` 可能尚未完成，且 Flutter 侧 `updateJavaAssetManager` 要求主线程）。
2. post 到主线程后晚于当前 `createAssetManager` 调用栈；若 FlutterEngine 在同一主线程调用栈内完成构造并立即启动 Dart，Runnable 执行时通常能看到已启动状态，但 Flutter Dart 线程仍可能先完成首个 asset 读取。
3. 引擎自身 `createPackageContext().getAssets()` 若新建 AssetManager，同样会触发本 hook。批次对“构造后尚未启动 Dart”的 Engine 不停留在跳过：直接替换 `DartExecutor` 构造期保存的 AssetManager（见 §13.1），随后 Dart 启动必然使用新实例，不依赖下一次触发。
4. 用 `AtomicBoolean` 合并同一轮多次 hook 触发；同一批只做一次枚举、一次上报。

## 5. 反射兼容策略（无 Flutter 编译依赖）

| 目标 | 取法 | 不可用时 |
|---|---|---|
| Flutter 是否存在 | `Class.forName("io.flutter.embedding.engine.FlutterEngine")` | `ClassNotFoundException` → 直接 no-op |
| 存活 Engine 集合 | 反射静态字段 `FlutterEngine.idToEngine`（`Map<Long, FlutterEngine>`） | 字段缺失时无法判断是否存在 Engine；至少记录 warn，不得用 debug 伪装为正常无 Engine。兼容范围以验证过的 Flutter 版本为准 |
| 每个 Engine 的 JNI | 反射实例字段 `flutterJNI` | 缺失 → 记为失败（进入批次上报） |
| 是否可刷新 | `flutterJNI.isAttached()`；未 attach 的 Engine 无法接受更新（`updateJavaAssetManager` 内部 `ensureAttachedToNative()` 会抛异常） | 若没有确定的后续刷新机会，则属于本批失败，不能静默当作成功 |
| Dart 是否已启动 | 反射 `FlutterEngine.dartExecutor` → `DartExecutor.isExecutingDart()`（Flutter 3.47.2 公开方法） | 已启动时调用 JNI；未启动时必须更新 `DartExecutor` 将来启动所使用的 AssetManager，或增加可靠的 Dart 启动后触发点。仅跳过会永久遗漏延迟启动 Engine |
| 刷新调用 | `updateJavaAssetManager(AssetManager, String)` | 缺失/抛错 → 记为失败 |
| asset bundle path | `FlutterInjector.instance().flutterLoader().findAppBundlePath()` | 反射失败或值空 → 回退 Flutter 默认目录常量 `flutter_assets`（`FlutterApplicationInfo.DEFAULT_FLUTTER_ASSETS_DIR`） |
| 当前宿主 AssetManager | `ActivityThread.currentApplication()`（主线程）→ `getResources().getAssets()`；overlay 判定用反射 `AssetManager.getApkAssets()`，检查各 `ApkAssets.getAssetPath()` 是否含 `/code_cache/.overlay/` | Application 未就绪 → no-op |

overlay 判定复用现有 `InstrumentationHooks.isApplyChangesOverlay()` 的同一语义（路径含 `/code_cache/.overlay/`）：该谓词已被现有 `tryFixOutSideApk()` 在生产中使用，说明 overlay 的 ApkAssets 路径确实以该目录出现。**未包含 overlay 时直接跳过，绝不把旧 AssetManager 传给 Engine。**

## 6. 失败反馈（log + Toast）

- 无 Flutter / 无 Engine / 无 overlay：no-op，无 warn，无 Toast。反射契约损坏导致“无法判断是否有 Engine”不属于正常无 Engine，应记录 warn。
- overlay 已确认且检测到一个或多个存活 Engine：使用同一份 Engine 快照逐个刷新，单个失败不中断其余 Engine。
- 只要本批存在失败：`LogUtils.w` 记录一次（含失败 Engine 数量与每个失败原因），并 `Toast.makeText(context, ..., Toast.LENGTH_LONG).show()` 一次；文案明确“Flutter assets 未刷新成功，需要重启应用”。不按 Engine 重复提示。
- Engine 未 attach、Dart 未启动但又没有完成“下次启动使用新 AssetManager”的处理、批处理中途反射异常，都应计入失败；不得打印“refreshed for N engines”后实际一个也未更新。
- 全部成功：不 Toast；结果按 `LogUtils.d`（受 `/data/local/tmp/jugg/log_debug` 开关控制）记录，避免高频用户可见日志。
- hook 全程 fail-open：Flutter 反射、刷新、Toast 的异常只在内部收口，不向 `ResourcesManager` 调用栈抛出，不影响 Android 正常流程。

## 7. 代码改动清单

| 文件 | 改动 |
|---|---|
| `jvmti_agent/src/main/java/com/sickworm/intellij/jugg/instrument/FlutterAssetRefresh.java`（新增） | Engine 枚举、overlay 校验、主线程调度、批量刷新与失败上报 |
| `jvmti_agent/src/main/java/com/sickworm/intellij/jugg/instrument/InstrumentationHooks.java` | 两个 `createAssetManager*Exit` 的“保留 overlay”分支追加 `FlutterAssetRefresh.scheduleRefresh()` |
| 根 `build.gradle` | `agentVersion` 递增，确保设备不复用旧 bundle |
| `jvmti_agent/src/test/java/...`（新增测试 + fake Flutter 类） | 见 §8 |

不新增 framework transform，不修改 `ApplyChangesOverlayPolicy`、`ResourceOverlays`、`Android15ApplyChangesFixer`。

## 8. 测试与验证方案

### 8.1 测试价值门禁

- 有价值自动化断言：Engine 枚举来源（必须覆盖 `idToEngine` 而非 `FlutterEngineCache`）、单 Engine 失败不影响其他 Engine、每批只上报一次、成功批次不上报、无 Engine/无 overlay 时 no-op、已检测 Engine 后的前置/反射失败必须告警、延迟启动 Dart 的 Engine 最终使用新 AssetManager。这些是本次新增的稳定契约，被真实破坏时能确定性失败。
- 不可自动化的部分：真实 Apply Changes 时序、真实 Flutter native 行为、Toast 在设备上的可见性。这些用设备证据替代，不制造实现细节测试。
- 不新增源码字符串扫描（不锁定私有方法体）；现有 `InstrumenterSourcePolicyTest` 对 compat 早退的守卫保持有效。

### 8.2 自动化测试

新增 `FlutterAssetRefreshTest`（`jvmti_agent` JVM 单测，L1），测试源码集内提供 `io.flutter.embedding.engine.FlutterEngine` / `FlutterJNI` 的 fake（形状对齐 F8/Flutter 3.47.2，作为外部 SDK 替身，不侵入生产代码）：

| 用例 | 断言 |
|---|---|
| 无 Engine | 返回空失败列表，Toast 不触发 |
| 单个 Engine | `updateJavaAssetManager` 收到传入的 AssetManager 与 bundlePath |
| 多 Engine（含仅存在于 `idToEngine`、不在 `FlutterEngineCache` 的 Engine） | 每个 Engine 都被刷新 |
| 多 Engine 中一个抛异常 | 其余 Engine 仍被刷新；失败列表含该 Engine 与原因；`LogUtils.w` 与 `Toast.makeText` 各一次 |
| 全部成功 | 无 warn、无 Toast |
| Engine 未 attach | 其余 Engine 继续刷新；若本批无法保证该 Engine 随后拿到新 AssetManager，则计入失败并按批提示 |
| Engine 尚未启动 Dart | 更新其启动时会读取的 AssetManager，或由可靠的启动后 hook 完成 JNI 刷新；不得静默遗漏 |
| overlay 路径判定 | 含 `/code_cache/.overlay/` 为真；普通 `/data/app/.../base.apk` 为假 |

首版测试表中的“未 attach / 未启动 Dart 静默跳过”与最终失败反馈要求冲突，不应继续作为正确行为保留。还需要覆盖公开入口 `scheduleRefresh()` 到 overlay 门禁、Engine 快照、失败聚合的完整路径；仅直接调用 `refreshEngines()` 不能保护调度和前置失败语义。

### 8.3 构建与定向测试命令

```bash
./gradlew :jvmti_agent:testDebugUnitTest --tests 'com.sickworm.intellij.jugg.instrument.FlutterAssetRefreshTest'
./gradlew :jvmti_agent:testDebugUnitTest --tests 'com.sickworm.intellij.jugg.instrument.ApplyChangesOverlayPolicyTest'
./gradlew :jvmti_agent:testDebugUnitTest --tests 'com.sickworm.intellij.jugg.jvmti_agent.InstrumenterSourcePolicyTest'
./gradlew :jvmti_agent:buildAgentBundle
```

### 8.4 设备验证

- 真实设备/模拟器上确认已安装 Flutter App 的 `io.flutter.embedding.engine.FlutterEngine` / `FlutterJNI` 确实具备 `idToEngine`、`flutterJNI`、`isAttached`、`updateJavaAssetManager(AssetManager, String)`（反射契约与设备侧 Flutter 版本一致）。
- 构建出的 `jugg-instruments.jar` 必须包含 `FlutterAssetRefresh.class`。
- 端到端 V1→V2 asset 进程内刷新：依赖 Android Studio Apply Changes 现场。若本机无法加载本地插件构建（与 `flutter_jit_overlay_timestamp_refresh_plan.md` §11.5 相同的已知阻塞），明确记录未完成边界与替代证据，不伪造结果。

## 9. 文档同步

- `docs/ai_knowledge/03_runtime_jvmti.md`：新增 Flutter Engine AssetManager 刷新的触发点、边界与失败语义。
- `docs/ai_knowledge/98_code_map.md`：`jvmti_agent` 行补充 `FlutterAssetRefresh`。
- `docs/wiki`：仅当存在描述“Flutter asset 增量部署生效方式”的用户页面时同步；无则不改。

## 10. 残余风险（含实施后新增证据）

- 首版只刷新已 attach 且已启动 Dart 的 Engine；构造后延迟启动 Dart 的 Engine 没有可靠的下一次 hook，当前属于功能缺口而不是可接受窗口。
- `updateJavaAssetManager` 只替换 `kApkAssetProvider` resolver，历史已读入内存的 asset 内容不会回退重读（Dart 侧 `rootBundle` 字符串缓存行为不在本方案范围）。
- **冷启动的 Dart 启动时序**：Flutter `RunBundleAndSnapshotFromLibrary()` 在 Dart 启动时会用 `DartExecutor` 构造期捕获的 AssetManager **重新创建** `APKAssetProvider`；因此在 Dart 启动前发出的 JNI 刷新会被覆盖。首版据此跳过尚未启动 Dart 的 Engine（见 §5），冷启动场景仍可能读到旧 asset，必须按 §12 补齐启动前 AssetManager 替换或可靠的启动后触发。
- Flutter 若改变 `idToEngine` / `flutterJNI` / `dartExecutor` / `updateJavaAssetManager` 私有结构，反射会失效；失效路径为 no-op 或批次失败提示，不会破坏 App 流程。
- compat deploy 与 Android 11 以下继续依赖进程重启，不因本方案改变。

## 11. 实施结果与验证证据（2026-09-13）

### 11.1 落地内容

| 文件 | 修改 |
|---|---|
| `jvmti_agent/.../instrument/FlutterAssetRefresh.java`（新增） | Engine 枚举（`idToEngine`）、overlay 校验、主线程调度、批量刷新与失败上报 |
| `jvmti_agent/.../instrument/InstrumentationHooks.java` | 两个 `createAssetManager*Exit` 的“保留 overlay”分支调用 `scheduleRefresh()`；抽出 `APPLY_CHANGES_OVERLAY_MARKER` 常量 |
| 根 `build.gradle` | `agentVersion` 1.0.68 → 1.0.69 |
| `jvmti_agent/.../test/io/flutter/embedding/engine/{FlutterEngine,FlutterJNI,DartExecutor}.java`（新增） | 对齐 Flutter 3.47.2 形状的测试替身 |
| `jvmti_agent/.../test/.../instrument/FlutterAssetRefreshTest.java`（新增） | 8 个用例，见 §11.2 |

### 11.2 首版实施过程记录

1. **失败原因被反射包装吞掉**：`Method.invoke` 抛出 `InvocationTargetException`，warn 中只剩包装类型。测试先失败（断言 `"engine is gone"` 未出现），随后在 `reason()` 中解包。
2. **bootstrap ClassLoader 无法解析 Flutter 类**：agent 的 Java 类由 `AddToBootstrapClassLoaderSearch()` 加载，`Class.forName("io.flutter...")` 默认使用调用方（bootstrap）ClassLoader，永远 `ClassNotFoundException`，整条链路静默 no-op。设备实测确认（首次上机日志完全没有本模块输出），改为显式使用 `context.getClassLoader()`。
3. **Dart 启动前的 JNI 刷新会被覆盖**：Flutter engine `platform_view_android_jni_impl.cc` 的 `RunBundleAndSnapshotFromLibrary()` 在 Dart 启动时用 `DartExecutor` 捕获的 AssetManager 重建 `APKAssetProvider`。首版据此跳过尚未启动 Dart 的 Engine；独立复核确认这会遗漏延迟启动 Engine，仍需按 §12 修正。

### 11.3 自动化验证（定向）

| 命令 | 结果 |
|---|---|
| `:jvmti_agent:testDebugUnitTest --tests FlutterAssetRefreshTest` | 8 tests，0 failures |
| `:jvmti_agent:testDebugUnitTest`（模块内全部单测） | 133 tests，0 failures |
| `:jvmti_agent:buildAgentBundle` | BUILD SUCCESSFUL，产出 `jugg-agent-bundle-1.0.69.zip`；`jugg-instruments.jar` 内含 `FlutterAssetRefresh` 及 `updateJavaAssetManager` / `idToEngine` / `flutterJNI` / `isAttached` / `/code_cache/.overlay/` 常量 |

覆盖的契约：无 Engine / 无 Flutter 类的 ClassLoader → no-op 且不提示；多 Engine（含未进入 `FlutterEngineCache` 的 Engine）全部刷新；单个 Engine 失败不影响其他 Engine 且每批只 warn + Toast 一次；成功批次不提示；未 attach 或未启动 Dart 的 Engine 跳过；overlay 路径判定。

### 11.4 设备验证（emulator-5554，Android 15 / API 35，`dev.jugg.demo.flutter_native.debug`）

真实环境具备条件：已安装 Flutter 3.47.2 debug 应用（engine revision `a804b261…`）、存在真实 Apply Changes overlay（`code_cache/.overlay/base.apk/`，其中 `assets/flutter_assets/assets/jugg_flutter_marker.txt` = `…MARKER_V3`，而 base APK 内为 `…MARKER_V2`）、AS startup agent 同时在线。

| 验证项 | 证据 |
|---|---|
| 设备侧反射契约 | 解析已安装 APK 的 `classes.dex`：`FlutterEngine` 具备静态字段 `idToEngine`、实例字段 `flutterJNI`、`dartExecutor`；`FlutterJNI` 具备 `updateJavaAssetManager`、`isAttached`；`DartExecutor` 具备 `isExecutingDart`；`FlutterLoader` 具备 `findAppBundlePath` |
| 新 agent 可加载 | 冷启动日志：`Loading instrumentation from /data/local/tmp/jugg/1.0.69/jugg-instruments.jar`、`Finished instrumenting`、`ResourcesManager` transform success |
| hook 命中宿主分支 | `assetManager hook action=skip, … hook=createAssetManagerNew`（即新增 `scheduleRefresh()` 的分支） |
| 刷新真实执行 | `Flutter asset refresh` → `Flutter assets refreshed for 1 engine(s)`（overlay 门禁通过、真实 FlutterEngine 接受 `updateJavaAssetManager`，无异常） |
| fail-open | 上述全部流程中 App 正常启动、Flutter UI 正常渲染，无 Jugg 相关崩溃 |
| overlay 路径判定与真机一致 | 系统日志 `ApkAssets: Deleting an ApkAssets object '<empty> and /data/data/…/code_cache/.overlay/base.apk/'` 证明 loader 提供的 ApkAssets 路径确实包含 `/code_cache/.overlay/` |
| 现场恢复 | 验证后应用 startup agent 回退为 1.0.68、删除 `/data/local/tmp/jugg/log_debug` 与 1.0.69 临时目录；overlay 内容未被修改 |

**未完成的边界（不伪造结论）**：未能取得“asset 值由 V2 变为 V3”的端到端用户可见证据。原因有两条，均有证据支撑：

1. demo 只在首帧 `build()` 中读取一次 asset（`rootBundle.loadString`，`CachingAssetBundle` 会按 key 缓存），刷新到达时该次读取已完成，之后同一 key 不再重新读取；多次冷启动复现，界面始终显示 base APK 的 `…MARKER_V2`。
2. 触发点来自 `createAssetManager`，无法早于 Flutter 首次读取；Dart 启动前的刷新又会被 `RunBundleAndSnapshotFromLibrary()` 覆盖（§11.2-3）。

因此本次设备验证只证明“触发 → 门禁 → 真实 Engine 刷新调用 → App 无损”这条链路成立，不能据此声明用户问题已修复。下一轮设备验证必须至少包含：未缓存 key 或 `loadString(cache: false)` 的 V2→V3 读取、同一 key 缓存边界、已运行 Engine、构造后延迟启动 Dart 的 Engine，以及 compat deploy 不触发本逻辑。

### 11.5 与方案的差异

1. 新增 `ClassLoader` 参数与 `isExecutingDart()` 前置判断；ClassLoader 修正确有必要，单纯跳过未启动 Dart 的行为经独立复核判定不满足目标。
2. `refreshEngines()` 增加 `ClassLoader` 形参，使“无 Flutter 的 ClassLoader”可被自动化测试覆盖。

## 12. 独立复核结论与必须修正项（2026-09-13）

首版实现证明方向可行，但当前提交 `18092cbcc` 不能按“已修复”验收，原因如下：

来源标记：

- **【方案遗漏】**：我们在实现前讨论方案时没有识别或定义清楚。
- **【Claude 未完成】**：已交付的目标或正常实现质量要求，在首版代码/验证中没有完成。
- **【我未正确告知】**：我交给 Claude 的实施说明缺失、含糊或错误；这类问题不能只归责于 Claude。

1. **【方案遗漏】【Claude 未完成，但源于我未正确告知】延迟启动 Dart 的 Engine 会永久遗漏。** `idToEngine` 在构造早期就登记 Engine；Runnable 执行时如果 `isExecutingDart()` 为 false，首版直接返回成功。之后 `executeDartEntrypoint()` 仍使用 `DartExecutor` 构造期捕获的旧 AssetManager，且没有下一次必然触发。我们讨论方案时漏掉了 `RunBundleAndSnapshotFromLibrary()` 会覆盖启动前 JNI 刷新的时序；我交给 Claude 的说明又错误地把“未启动 Dart”定义为正常静默跳过。Claude 按该约束实现后仍没有满足“所有 Engine 最终使用新 AssetManager”的目标。修正方案应二选一：在未启动时 best-effort 替换 `DartExecutor` 保存的 AssetManager，或增加可靠的 Dart 启动后 hook；不能只依赖一次 post 或不确定的“下一次 createAssetManager”。
2. **【Claude 未完成】【我未正确告知部分细节】失败告警没有覆盖已确认 Engine 的全部失败。** “检测到 Engine 且 best-effort 失败时 warn + Toast”是讨论中已经明确的要求，我也把总原则交给了 Claude，因此外层异常只 warn、重复枚举后假成功属于 Claude 首版未完整实现。但我没有给出完整失败分类，并同时错误指定未 attach/未启动 Dart 静默跳过，造成要求自相矛盾。最终未 attach、未启动 Dart 被计为成功；Engine 在入口与批处理内被枚举两次，第二次失败或状态变化还可能输出 `refreshed for 0 engine(s)`。应先取得一次快照并贯穿本批，明确区分 normal no-op 与 refresh failure。
3. **【方案遗漏】【我未正确告知验收口径】【Claude 文档结论问题】用户可见结果尚无证据。** 方案讨论时没有区分“native resolver 已更新”和“Dart 对同一 key 实际重新读取”，我交付实施任务时也没有明确要求用未缓存读取作为最终验收。Claude 后续自行发现并记录了 `rootBundle` 缓存限制，因此这不是他漏写生产逻辑；但文档顶部仍一度宣称用户问题已解决，与设备只观察到 `updateJavaAssetManager()` 成功日志、未观察到 asset 内容 V2→V3 的证据矛盾。`rootBundle` 是 `PlatformAssetBundle extends CachingAssetBundle`，`loadString()` 默认按 key 缓存；验收必须使用未缓存 key 或显式 `cache: false`，并把 Dart 缓存明确列为能力边界。
4. **【Claude 未完成】【我未正确告知了错误预期】自动化测试绕过了真实入口。** Handler 调度、Application/overlay 获取失败、一次 Engine 快照、compat 早退和延迟启动后的最终 AssetManager 都没有被首版测试覆盖，属于 Claude 没有完成稳定行为保护；但测试中“未 attach/未启动静默成功”的预期来自我给出的错误约束，不能归责为 Claude 自行定义错误行为。
5. **【方案遗漏】【我未告知；非 Claude 新增】触发判定继承了并发风险。** 两套 `createAssetManager*Enter/Exit` 用进程级静态 boolean 传递单次调用状态；并发或嵌套调用可相互覆盖，使宿主分支漏调刷新。该问题是存量实现，不是 Claude 首版引入，也不属于他明确收到的修改要求；但我们选择复用该 hook 时没有先验证配对状态的并发安全性，我也没有在实施说明中提醒。修正或验收时必须纳入检查，不能把 hook 每次都准确配对当作既定事实。

验收口径：普通 Apply Changes 下，所有当时存活的 FlutterEngine 要么立即使用新 AssetManager，要么在随后启动 Dart 时确定使用新 AssetManager；任何已确认 overlay + Engine 后无法完成的情况都按批次 warn + Toast；compat deploy 保持原流程；最终以真实 asset 内容变化而非仅 API 调用日志作为成功证据。

独立复核重新执行了上述 3 组定向测试，共 18 tests、0 failures，并执行 `:jvmti_agent:buildAgentBundle` 成功。该结果确认首版代码可构建且现有测试稳定通过，但不推翻本节列出的契约与覆盖缺口。

## 13. §12 修正项的实施结果（2026-09-13）

### 13.1 修正 1：延迟启动 Dart 的 Engine 不再遗漏

`FlutterAssetRefresh.refreshEngine()` 按 Dart 状态分流，两条路径都必须成功，否则计入失败：

```text
DartExecutor.isExecutingDart() == true
  -> FlutterJNI.updateJavaAssetManager(assetManager, assetBundlePath)   // 立即生效
DartExecutor.isExecutingDart() == false
  -> 反射替换 DartExecutor.assetManager                                  // 随后启动时生效
     （Flutter 在 runBundleAndSnapshotFromLibrary() 中用它重建 APKAssetProvider；
      启动后再调 JNI 会被这次启动覆盖，所以未启动时不能只调 JNI）
```

选型依据：Flutter 3.47.2 engine `platform_view_android_jni_impl.cc` 的 `RunBundleAndSnapshotFromLibrary()` 用 `DartExecutor` 构造期保存的 AssetManager 重建 `APKAssetProvider`；`DartExecutor.executeDartEntrypoint()` 读取同一个字段。因此替换字段即可确定性地覆盖随后的 Dart 启动，不需要新增 transform、定时重试或等待下一次 `createAssetManager`。写入后回读校验，未生效按失败处理。

`executeDartCallback()` 使用独立 `DartCallback.androidAssetManager`，既不经过该字段，也不经过 APK resolver 替换，本实现不覆盖（见 §13.5）。

### 13.2 修正 2：失败反馈与单次 Engine 快照

- 入口只做一次发现：`findEngineClass()` → `snapshotEngines()` → overlay 校验；快照贯穿整批，批内不再重新枚举，也不会出现“第二次枚举为空 → refreshed for 0 engine(s)”。
- 已确认 overlay 且快照非空之后的所有异常（含批次外层异常）都进入同一批 `warn` + `Toast` 一次；确认之前的失败保持 fail-open 并记录原因，不伪造成功。
- 未 attach 的 Engine 记为失败（不再静默成功）；`idToEngine` 等契约不可读时 warn，且不当作“无 Flutter”。
- 成功仍只在 debug 级记录，并区分 `updated through FlutterJNI` 与 `will start Dart with the new AssetManager` 两条路径。

### 13.3 修正 3：Enter/Exit 配对状态

证据：Android 14+ 同时存在两个 `createAssetManager` 签名且旧签名委托新签名，设备日志显示两个 transform 都被应用（同一类两次 `ClassFileLoadHook transformed: android/app/ResourcesManager`），因此 enter/exit 会成对嵌套；AOSP 35 的 `ResourcesManager.getResources()`/`createAssetManager()` 没有线程标注，`Context.createPackageContext()`、`Context.createContextForSplit()`、`ApplicationPackageManager.getBackgroundPermissionOptionLabel()` 等都可达该路径，进程级静态 boolean 存在跨调用/跨线程覆盖风险。

修正：两套 hook 各自改用 `ThreadLocal<Boolean>`，exit 时读取并 `remove()`。同一线程内的嵌套调用走的是同一 `ResourcesKey`，取值一致，因此不需要栈结构；两套 hook 各用独立 ThreadLocal，保证非宿主 overlay 清理行为与首版一致。

### 13.4 验证证据

自动化（先失败后通过）：

| 阶段 | 结果 |
|---|---|
| RED（新测试 vs 首版行为） | `FlutterAssetRefreshTest` 10 tests / 3 failures：未启动 Dart 的 Engine 仍持有旧 AssetManager（2 例）、未 attach 的 Engine 未计入失败（1 例） |
| GREEN（修正后） | `FlutterAssetRefreshTest` 10 tests / 0 failures |
| 模块回归 | `:jvmti_agent:testDebugUnitTest` 136 tests / 0 failures（含 `InstrumenterSourcePolicyTest` 新增的旧签名 compat 早退守卫） |
| 构建 | `:jvmti_agent:buildAgentBundle` 成功，产出 `jugg-agent-bundle-1.0.70.zip`（`agentVersion` 1.0.69 → 1.0.70） |

测试覆盖：运行中 Engine 走 JNI、未启动 Dart 的 Engine 被替换字段、同一批两条路径各自处理、单 Engine 失败不阻塞其他 Engine 且只 warn + Toast 一次、未 attach 计为失败、批次只取一份快照（批中新建的 Engine 不在本批处理）、无 Flutter 的 ClassLoader no-op、注册表契约不可读时 warn 且不 Toast、无 overlay 谓词。

设备（emulator-5554 / Android 15 / `dev.jugg.demo.flutter_native.debug`；overlay marker=`JUGG_FLUTTER_ASSET_MARKER_V3`，base APK marker=`…_V2`；装有 AS startup agent 与真实 Apply Changes overlay）：

| 场景 | 证据 |
|---|---|
| 已运行 Dart 的 Engine | 冷启动与进程内重建 Activity 后均出现 `assetManager hook action=skip` → `FlutterEngine@… updated through FlutterJNI` → `Flutter assets refreshed for 1 engine(s)`；无异常、无 Toast、App 正常渲染 |
| 无 overlay | 覆盖 `code_cache/.overlay/base.apk` 后冷启动：`Flutter asset refresh skipped: no Apply Changes overlay in host Resources`，无刷新调用、无 Toast、App 正常 |
| 冷启动首帧 | UI 仍为 base APK 的 `…_V2`：批次落地（19:49:24.419）晚于首帧读取，进程内新建 Engine 同样如此（§13.5） |
| compat deploy | 两个 hook 的 `if (isEnableHotfix())` 早退由 `InstrumenterSourcePolicyTest` 旧/新签名两例守护 |
| 现场恢复 | 验证后 startup agent 恢复 1.0.68、删除 `log_debug` 与 1.0.70 临时目录、恢复 overlay 目录 |

### 13.5 未闭环项与残余风险（不得据此宣称已修复）

1. **asset 内容变化（V2→V3）未取得**：demo 只在启动时读一次 `rootBundle.loadString`，`CachingAssetBundle` 按 key 缓存；未缓存读取需要一个新 isolate 或工具端编译服务。本轮尝试过 `reloadSources(force=true)`（VM 返回 `Error while starting Kernel isolate task`）、`flutter attach`（工具侧发现/连接失败）与新引擎（新 isolate 的首帧读取仍早于批次）均未取得内容级证据。
2. **冷启动与批次之后新建的 Engine**：Engine 自身在构造期捕获的 AssetManager 已是旧实例（`createPackageContext().getAssets()` 命中较早的 Resources 缓存），而批次总是落在同一条主线程消息（onCreate→引擎构造→Flutter 初始化→Dart 启动→首帧）之后，因此首帧读取仍可能是旧 asset。§12 的验收口径只要求“overlay 生效时已存在的 Engine”，该场景不在口径内；如需覆盖，需要新增 Flutter 侧启动 hook（例如 `FlutterJNI.runBundleAndSnapshotFromLibrary` 入口替换 AssetManager 参数），本轮不做。
3. **`executeDartCallback()`**（`DartCallback.androidAssetManager`）不覆盖；当前目标场景（`FlutterActivity` + `executeDartEntrypoint`）不涉及。
4. **后台线程构造 Engine 与批次并发**时，未 attach 的瞬时状态会按失败上报，可能产生一次 Toast；窗口极短，未做特殊处理。
5. `DartExecutor.assetManager` 是 final 字段，写入依赖 ART 允许反射写非 static final 实例字段；已加入回读校验，写不进去时按批次失败上报。

## 14. 追加实现：首次 Dart launch 前的可靠边界（2026-09-13，未闭环）

### 14.1 目标

覆盖“overlay 已存在后才创建、并即将启动 Dart 的 FlutterEngine”，不再依赖 createAssetManager exit 之后的主线程 post。

### 14.2 触发点选择（含失败证据）

| 候选边界 | 结果 |
|---|---|
| `FlutterJNI.runBundleAndSnapshotFromLibrary()` entry hook | **不可用**：Flutter 是 app 类，startup agent 阶段 `jni->FindClass("io/flutter/embedding/engine/FlutterJNI")` 失败，设备日志 `Optional hook transform class not found: io/flutter/embedding/engine/FlutterJNI`；改为“Application 创建后按需安装”也失败：agent 库不是经 `System.loadLibrary` 加载，`RegisterNatives` 绑定的类副本与调用方（bootstrap 副本）不一致，设备日志 `UnsatisfiedLinkError: No implementation found for boolean com.sickworm.intellij.jugg.instrument.InstrumentationHooks.installFlutterLaunchHook(java.lang.ClassLoader)`。两条路径均已回滚，未留在代码中。 |
| `DartExecutor.executeDartEntrypoint()` entry hook | 不可用：现有 slicer 版本不支持 `Tweak::ThisAsObject`（instrumenter.h 注释已说明），entry hook 拿不到 `this`，无法写回启动输入。 |
| **`ContextImpl.createPackageContext(String,int)` exit hook** | **采用**：`FlutterEngine` 构造期正是用 `context.createPackageContext(pkg, 0).getAssets()` 取得并长期持有 AssetManager，该边界是 framework 类、可在 startup agent 阶段安装，且在 native 消费前完成；exit hook 取返回值 `Context`，只对宿主 APK 的包上下文补 overlay loader。 |

### 14.3 落地内容

| 文件 | 修改 |
|---|---|
| `instrumenter.cc` | 新增 `android/app/ContextImpl#createPackageContext(Ljava/lang/String;I)Landroid/content/Context;` 的 exit hook `handleCreatePackageContextExit`，加入 startup transform 列表 |
| `InstrumentationHooks.java` | 新增 `handleCreatePackageContextExit(Context)`（只做转发 + debug 日志） |
| `FlutterAssetRefresh.java` | `prepareHostPackageContext(Context)`（宿主包上下文判定 + 转 `prepareAssetManager`）、`hasHostApkPath(List<String>)`、`mergeLoaders`/`copyLoaders`/`loadersOf`/`apkPaths` 拆分；`prepareAssetManager` 保留为共享实现 |
| `ApplyChangesOverlayPolicy.java` | 新增 `isHostApkPath(String)` |
| 测试 | `launchInputShouldEndUpWithTheOverlayLoaders`（mergeLoaders：launch 输入必须带上 overlay loader）、`flutterEngineAssetManagerShouldComeFromThePackageContextHook`（transform↔hook 契约守卫）、原有批次/失败/compat 用例保持 |

RED→GREEN：先以 stub（`mergeLoaders` 原样返回）与缺失 transform 运行 → `FlutterAssetRefreshTest` 1 failure（`expected:<[own, overlay]> but was:<[own]>`）+ `InstrumenterSourcePolicyTest` 1 failure；实现后 `:jvmti_agent:testDebugUnitTest` **138 tests / 0 failures**，`:jvmti_agent:buildAgentBundle` 成功（`agentVersion` 1.0.70 → **1.0.71**）。

### 14.4 设备验证（emulator-5554 / Android 15，overlay=V3，base APK=V2）

| 项 | 证据 |
|---|---|
| transform 安装 | `Optional hook transform retransform success: android/app/ContextImpl` |
| 边界命中 | `handleCreatePackageContextExit: android.app.ContextImpl@…`（引擎构造期的包上下文，时间点在 Flutter 初始化之前） |
| 包上下文 AssetManager 判定 | **已经包含 overlay**：`prepareAssetManager` 的 `hasApplyChangesOverlay(...)` 为真而静默返回（因此没有 “AssetManager now carries” 日志）。即设备上该 AssetManager 的 Java 侧 `getApkAssets()` 已列出 `/code_cache/.overlay/`。 |
| 首帧读取 | **仍为 V2**（base APK 内容），UI 三次冷启动一致 |

### 14.5 未完成结论与可复现阻塞

本轮把问题收窄到一个可复现的事实：**包上下文的 AssetManager 在 Java 侧已含 overlay ApkAssets，但 Flutter 首次读取仍得到 base APK 的内容**（overlay 目录中的 `assets/flutter_assets/assets/jugg_flutter_marker.txt` 确认为 V3，base 内为 V2）。也就是说，剩余缺口不在“Engine 是否拿到 overlay-aware 的 AssetManager 实例”，而在“该 AssetManager 的原生 asset 解析为何优先命中 base APK / 是否包含 overlay 的 assets 命名空间”。

复现步骤（无需插件）：在装有 AS startup agent 与真实 overlay 的 demo 上，按 §11.4 的方式替换 startup agent 为本地构建，`am force-stop` 后冷启动，观察 `handleCreatePackageContextExit` 命中且 `flutter assets` 无 “now carries” 日志、UI 仍显示 V2。

未能取得内容级 V2→V3 证据，因此本任务状态保持**未完成**；`docs/wiki` 不对外宣传该能力。

## 15. raw asset 优先级的最终定位（2026-09-13，仍未闭环）

### 15.1 最小可判定证据

在同一设备、同一 marker 路径（`flutter_assets/assets/jugg_flutter_marker.txt`；overlay 目录内为 `…_V3`，base APK 内为 `…_V2`）上直接读取：

| 读取方 | 结果 |
|---|---|
| Java `AssetManager.open()`，对象＝FlutterEngine 构造期捕获的那个包上下文 AssetManager（`createPackageContext` exit hook 里取得） | **V2** |
| 同一时刻 `Application.getResources().getAssets()` 是否同一对象 | `false`（是独立实例） |
| Flutter UI（native `AAssetManager_open()`） | **V2** |

即：Java 与 native 的 raw asset 解析结果一致，都是 base APK 的内容；`getApkAssets()` 里出现 `/code_cache/.overlay/` 并不代表该 overlay 能提供 raw asset。

### 15.2 AOSP 侧根因

- `AssetManager2::OpenNonAsset`（android15-release `libs/androidfw/AssetManager2.cpp`，L599-624）从列表**倒序**遍历，只对 `assets->IsOverlay()` 的条目 `continue`；`ApkAssets::IsOverlay()`（`ApkAssets.h:86`）等价于 `loaded_idmap_ != nullptr`，即只跳过 IDMAP/RRO overlay，不会跳过 loader 条目。
- 因此 loader 条目确实会被搜索。这里曾错误地把 `ResourcesProvider.loadFromDirectory(dir, null)` 理解为“只提供资源”：AOSP 实际会组合 native `EmptyAssetsProvider` 与 native `DirectoryAssetsProvider`，`null` 只表示没有 Java 覆盖回调，目录中的 raw asset 仍可读取。此前 V2 现象应归因于 ApkAssets 顺序，而不是缺少 Java provider。
- 直接尝试把 overlay 目录当作普通 apk asset 追加（`ApkAssets.loadFromPath(dir)`）在设备上失败：`java.io.IOException: Failed to load asset path /data/user/0/<pkg>/code_cache/.overlay/base.apk`（该 API 不接受目录形态）。

### 15.3 剩余修复路径（本轮未完成）

重新装载 overlay 目录并追加到列表末尾，使其成为最后（优先级最高）的 regular apk asset：

```text
ResourcesProvider.loadFromDirectory(overlayDir, null)
  -> provider.getApkAssets() 追加到 AssetManager.setApkAssets(existing + overlayAssets, true)
  -> AssetManager2::OpenNonAsset 倒序命中 overlay 的文件
```

不得为此传入 Java `AssetsProvider`。Flutter 的 native asset resolver 会从未附着 JVM 的工作线程调用 `AAssetManager_open()`；非 null provider 会进入 Android `LoaderAssetsProvider` 的 Java 回调边界并触发硬性线程检查。

本轮为取得证据所做的临时探针（`readMarker`、before/after 日志、`ApkAssets.loadFromPath` 追加实验）已全部回滚，未进入提交；仓库保持在 `9fac0ce8b` 的实现状态，本任务状态仍为**未完成**：未取得运行中 Engine 与冷启动 Engine 的 V2→V3 内容证据。

## 16. 当时实现与内容证据（2026-09-13，后由 §17 修正）

### 16.1 当时机制

```text
ContextImpl.createPackageContext(String,int) exit hook   （保留，注入边界）
  -> FlutterAssetRefresh.prepareHostPackageContext(context)
     -> 宿主包上下文：applyOverlayAssets(getAssets(), overlayApkPaths())
        -> ResourcesProvider.loadFromDirectory(overlayDir, null)
           -> 把返回的 ApkAssets 追加到列表末尾（OpenNonAsset 倒序遍历 ⇒ 优先）
        -> 去重：identity 的 WeakHashMap 记录已增强的 AssetManager，同一 Resources 不再追加第二次
批量（运行中 Engine）
  -> flutterAssetManager(context) = createPackageContext().getAssets()（已被 hook 增强）+ 就绪校验
  -> 已启动 Dart：FlutterJNI.updateJavaAssetManager(该 AssetManager, bundlePath)
  -> 未启动 Dart：替换 DartExecutor.assetManager 为该 AssetManager
  -> 增强/刷新失败 ⇒ 计入整批 warn + Toast；未 attach、契约不可读仍按失败分类
```

`ResourcesProvider.loadFromDirectory(dir, null)` 在 Android native 层为目录创建
`DirectoryAssetsProvider`，不需要 Java 文件回调。append-last 仍负责 raw asset 的查找优先级。

### 16.2 设备内容证据（emulator-5554 / Android 15；base APK=V2，overlay=V3）

| 项 | 结果 |
|---|---|
| Java `AssetManager.open("flutter_assets/assets/jugg_flutter_marker.txt")`，对象＝FlutterEngine 捕获的那个包上下文 AssetManager | 修复前 **V2** → 修复后 **V3**（同一对象、同一路径） |
| 冷启动 Flutter UI（overlay 已存在，新 Engine 首帧读取） | 修复前 **V2** → 修复后 **V3**（最终构建 `1.0.73` 复测仍为 V3） |
| 批次 | `FlutterEngine@… updated through FlutterJNI` + `Flutter assets refreshed for 1 engine(s)`，无失败提示、无 Toast |
| 普通资源 / App 启动 | 正常启动、Flutter 渲染正常，无 Jugg 相关异常 |
| 无 overlay | 之前的设备实测：`no Apply Changes overlay for raw assets`，无刷新、无 Toast |

### 16.3 仍未闭环 / 边界

1. **运行中 Engine 的未缓存读取 V2→V3 未取得**：demo 只在启动读一次且 `rootBundle` 按 key 缓存；未缓存入口需要在外部 demo 增加 `loadString(key, cache: false)` 并重新生成 kernel 部署，本轮未执行。已取得的等价证据是：交给 `updateJavaAssetManager()` 的 AssetManager 与冷启动同一个增强实例（同一增强路径 + 同一就绪校验）。
2. 默认同 key `rootBundle.loadString()` 仍返回进程内首读值（Flutter 缓存语义，明确边界）。
3. `executeDartCallback()` 的 `DartCallback.androidAssetManager` 仍不在覆盖范围。
4. 因此状态记为**部分闭环**：Java open 与冷启动首读闭环，运行中未缓存读取未闭环，Wiki 不同步。

### 16.4 运行中 Engine 未缓存读取：本轮实测结果（未证明同 Engine）

按 §16.3 第 1 条的授权，临时在 demo 的 `flutter_module/lib/main.dart` 增加按钮触发
`rootBundle.loadString('assets/jugg_flutter_marker.txt', cache: false)` 并 `print`；用
`flutter build bundle` 重新生成 kernel，写入真实 Apply Changes overlay 目录
（`code_cache/.overlay/base.apk/assets/flutter_assets/kernel_blob.bin`，**通道差异：内容由 adb 写入，
运行时 hook 与产品一致**），并删除 `app_flutter/res_timestamp-*` 让 Flutter 重新解压。

时间线（进程 4723）：

| 时刻 | 事件 | 证据 |
|---|---|---|
| 20:47:58 | 冷启动，Engine `@2f987e6` 经批次刷新 | `FlutterEngine@2f987e6 updated through FlutterJNI` |
| 20:48:13 | 第一次点击未缓存读取（overlay=V2） | `JUGG_UNCACHED_READ:JUGG_FLUTTER_ASSET_MARKER_V2` |
| 20:48:5x | overlay marker 改为 V3，启动第二个 Activity（新任务）触发真实 `createPackageContext` | 新 Engine `@c876aeb` |
| 20:48:58 | 批次刷新（快照内只有新 Engine） | `FlutterEngine@c876aeb updated through FlutterJNI` / `refreshed for 1 engine(s)` |
| 20:49:19 | 第二次点击未缓存读取 | `JUGG_UNCACHED_READ:JUGG_FLUTTER_ASSET_MARKER_V3` |

结论（如实记录）：**未缓存读取确实由 V2 变为 V3，但同 Engine 身份未得到证明**——20:48:58 批次的快照里只有第二个
Engine（`@c876aeb`），第一个 Engine（`@2f987e6`）在多次 Activity 前台/后台与多任务切换后已不在 `idToEngine` 中，
因此 20:49:19 的 V3 不能确证来自读 V2 的那个 Engine。判据仍缺“同一 Engine 出现在两读之间的刷新日志”这一条。

尝试过的触发方式与结果：`am start`（NEW_TASK/singleTop）不新建实例且不产生 Assets 变更；`--activity-multiple-task`
能新建实例与 Engine 并触发批次，但同时会改变第一个 Activity 的生命周期状态；HOME/BACK 组合会让第一个 Engine 退出
`idToEngine`。本轮未能构造出“第一个 Engine 始终存活且被同一批刷新”的稳定现场，因此该项**保持未闭环**。

### 16.5 同 Engine 现场：本轮未完成及阻塞原因

要达到“同一 Engine 在两读之间被同批刷新”，必须让 Engine 的生命周期独立于 Activity。核查 demo host：
`app/src/main/java/dev/jugg/demo/host/MainActivity.java` 直接 `extends FlutterActivity`，没有 `provideFlutterEngine()`，
也没有 `FlutterEngineCache` / Application 级预热，因此每个 Activity 实例各自创建并在销毁时释放 Engine——
这正是上一轮 §16.4 里两次取样 Engine identity 不同的原因。

需要的临时改动很小（`MainActivity` 增加静态 `FlutterEngine` + `provideFlutterEngine()` 复用，
或 Application 中预热 `FlutterEngineCache`），但它属于 **host Java 代码**，必须编译进 APK 才能生效。当前两条可用通道都不满足：

| 通道 | 结果 |
|---|---|
| `./gradlew :app:installDebug`（本地重建安装） | **已执行并成功**（APK 74MB，安装后手工重建 overlay 与 agent 现场）；本节的“阻塞”结论作废，实际缺口是运行中触发事件，见 §16.6 |
| Jugg 增量部署（产品通道，可携带 host class 变更） | plugin 内置 `agentVersion=1.0.68`，不包含本次修复的 runtime，用它部署等于验证旧代码 |
| demo Dart 侧改造 | 无法改变 Engine 生命周期（Engine 由 host 创建） |

因此该项**仍未闭环**：已观察到的 V2→V3 未缓存读取（§16.4）不能确证来自同一 Engine，本轮未构造出所需现场，
不使用推断替代。后续若要收口，最小路径是在 demo host 里缓存 Engine 后重建安装，并重建 overlay 现场。

### 16.6 同 Engine 现场：临时验证 APK 已跑通，卡在运行中触发事件

按授权本轮**实际执行**了临时改造与安装（不再把 APK 体积/code_cache 清理当作阻塞）：

1. `MainActivity` 增加进程级缓存 Engine（`provideFlutterEngine()` 返回同一实例 + `shouldDestroyEngineWithHost()` 返回 false），Dart 侧保留
   `rootBundle.loadString(..., cache: false)` 按钮与日志；`./gradlew :app:installDebug` **成功**（`Installed on 1 device.`，APK 仅 74MB）。
   随后逐文件还原 demo 源码（`MainActivity.java`、`main.dart`、`pubspec.lock` 均回到验证前状态）。
2. 安装后重建现场：重推 Jugg `1.0.73` runtime/startup agent、手工重建 `code_cache/.overlay/base.apk/assets/flutter_assets/assets/jugg_flutter_marker.txt`（base APK 内为 V3，overlay 写 V2）。
3. 同 Engine 证据（已达成的部分）：
   - PID 6439、缓存 Engine `@c71b10c`（日志 `JuggDemo: Creating shared engine` 仅一次）；
   - 第一次未缓存读取：`JUGG_UNCACHED_READ:JUGG_FLUTTER_ASSET_MARKER_V2`（base 为 V3 ⇒ 明确来自 overlay 读取链路）；
   - `KEYCODE_BACK` 结束 Activity 后 **PID 仍为 6439**，Engine 未被销毁（缓存 Engine 生效）。
4. 未达成的部分——**运行中触发 `createAssetManager` 的事件**：
   - `am start --activity-multiple-task`：缓存 Engine 复用后不再新建 PackageContext，日志无 `updated through FlutterJNI`；
   - `pm install -r --dont-kill`（产品同一安装通道，原始输出 `Success`）：进程仍被结束（`pidof` 为空）且 `code_cache` 被清空（overlay 目录与 startup agent 消失），不满足“不重启进程”；
   - 次级显示 `settings put global overlay_display_devices` + `am start --display 1`：无刷新日志（displayId 路径未触发 createAssetManager）。
   因此缺少“同一 Engine 在两读之间被同批刷新”的日志，该证据链仍不完整。

结论：临时验证 APK 方案本身可行且已跑通（同 Engine、同 PID、第一次 overlay 读取 V2 均为实测），剩余缺口收敛为**运行中可触发的 Assets 重建事件**；
在不修改 Jugg 生产代码、不依赖 AS 插件的前提下，本机未找到可用触发方式（三个候选通道的实际结果如上）。

### 16.7 同 Engine 完整时间线（已闭环）

现场：临时验证 APK（缓存 Engine + 仅用于验证的 MethodChannel `triggerPackageContext`，构建安装后 demo 源码已还原），
设备重推 Jugg `1.0.73` runtime，手工重建 `code_cache/.overlay/base.apk/assets/flutter_assets/assets/jugg_flutter_marker.txt`（初始 V2）。

| 时刻 | 事件 | 证据（进程 7124，Engine `@c71b10c`） |
|---|---|---|
| 21:02:50 | 缓存 Engine 建立（全进程仅一次） | `JuggDemo: Creating shared engine` |
| 21:02:57 | 启动阶段批次刷新同一 Engine | `Host package context AssetManager serves the overlay for raw assets` → `FlutterEngine@c71b10c updated through FlutterJNI` → `Flutter assets refreshed for 1 engine(s)` |
| 21:03:33 | 第一次未缓存读取（overlay=V2） | `JUGG_UNCACHED_READ:JUGG_FLUTTER_ASSET_MARKER_V2` |
| 21:03:3x | 经产品 overlay 目录把 marker 写为 V3（adb 直写，见下） | 目录内容 `…_V3` |
| 21:03:37 | MethodChannel 触发真实框架调用 `createPackageContext(getPackageName(), 0).getAssets()` | `Triggered createPackageContext for dev.jugg.demo.flutter_native.debug`、`JUGG_TRIGGER_PACKAGE_CONTEXT:true` |
| 21:03:37 | ContextImpl 边界增强 + 批次刷新**同一 Engine** | `Host package context AssetManager serves the overlay for raw assets`（两次）→ `FlutterEngine@c71b10c updated through FlutterJNI` → `Flutter assets refreshed for 1 engine(s)` |
| 21:03:43 | 第二次未缓存读取（同一 Activity/Engine，未重启） | `JUGG_UNCACHED_READ:JUGG_FLUTTER_ASSET_MARKER_V3` |

PID 前 7124 / 后 7124，Engine identity 前 `@c71b10c` / 后 `@c71b10c`，Activity 未重建、进程未重启、base APK 未替换。

通道与边界说明：

- **替代触发**：本机没有可用的 AS/Jugg Apply Changes transport，用临时验证 APK 内的 MethodChannel 调用 `createPackageContext(packageName, 0).getAssets()`
  来产生真实的 PackageContext/AssetManager 创建事件；该 Channel 只替换“触发事件”，不是生产方案的一部分，未提交。
- overlay 内容仍由 adb 直写产品的 `code_cache/.overlay/<apk>/` 目录（与 Jugg Direct 传输的差异仅在写入方式）；
  当时的目录格式、`OverlayAssetsProvider`、`ContextImpl#createPackageContext` hook、`ResourcesManager#createAssetManager` hook、
  `Engine.idToEngine` 枚举、`FlutterJNI.updateJavaAssetManager()` 与 Flutter 未缓存读取全部为真实产品代码。
- 默认同 key `rootBundle.loadString()` 缓存不失效、`executeDartCallback()` 未覆盖，仍为明确边界。

## 17. 真实 Jugg 部署回归与线程模型修正（2026-09-13）

### 17.1 失败证据

用 Jugg 修改 Dart 文件或 Flutter asset 后执行真实编译部署，应用重启后稳定在 Flutter
`io.worker.1` 线程触发 SIGABRT。21:50:38、21:50:58、21:51:46、21:52:27 四份
tombstone 的 abort message 与调用链完全一致：

```text
Check failed: env != nullptr Current thread not attached to a Java VM.
ResourcesProvider assets cannot be retrieved on current thread.

LoaderAssetsProvider::OpenInternal
  -> MultiAssetsProvider::OpenInternal
  -> AssetManager2::Open
  -> AAssetManager_open
  -> libflutter.so
```

Flutter 在 `APKAssetProviderImpl::GetAsMapping()` 中缓存 `AAssetManager*` 并直接调用
`AAssetManager_open()`。该读取可以发生在 Flutter 自己的 native worker，而 Android 的
`LoaderAssetsProvider` 在调用 Java `AssetsProvider.loadAssetFd()` 前要求当前线程已有
`JNIEnv`；不满足时是 native `CHECK`，不是可由 Java Best-effort 捕获的异常。

### 17.2 原方案错误归因

AOSP 的 directory 加载路径会组合：

```text
LoaderAssetsProvider::Create(env, null) -> EmptyAssetsProvider
DirectoryAssetsProvider::Create(path)  -> native directory reader
MultiAssetsProvider(empty, directory)
```

因此 `ResourcesProvider.loadFromDirectory(dir, null)` 仍可提供目录 raw assets。§15 中
Java/Flutter 读取 V2 的真正缺口是 overlay ApkAssets 的顺序；§16 的 V2→V3 同时引入了
“append-last”和 Java `OverlayAssetsProvider`，错误地把结果归功于后者。Java 主线程读取及
替代触发验证没有覆盖 Flutter 未附着 JVM 的 native worker，因而未发现该崩溃。

### 17.3 修正方案

保留已验证的 Engine 枚举、运行状态分支、`FlutterJNI.updateJavaAssetManager()`、包上下文
边界和 overlay append-last；删除 Java `OverlayAssetsProvider`，append-last 时使用
`ResourcesProvider.loadFromDirectory(overlayDir, null)`，让 Flutter 的全部读取保持 native。

真实验收必须同时覆盖：Dart 变化、文本 asset 变化、`Image.asset` 异步读取、时间戳删除后的
应用重启，以及运行中 Engine 的未缓存读取。仅有 Java `AssetManager.open()` 成功不能作为
Flutter 线程安全的验收结论。

### 17.4 最终验证结果

上述修正合入并 squash 为 `9fb3b055` 后，用户通过真实 Jugg 编译部署流程复测确认 Flutter
asset 更新已经生效，应用不再出现 `LoaderAssetsProvider` / `io.worker` 的 SIGABRT。本方案至此
完成；§14～§16 保留为调查过程和错误归因的历史记录，最终行为以本节与文档顶部状态为准。

## 18. 非 Flutter 影响审计与边界收口（2026-09-13）

### 18.1 完整 diff 审计结论

以 `9fb3b0559` 的父提交 `72b77f0a8` 为基线审计完整 diff，并对照当前基线 `4041b19dc`。
`4041b19dc` 相比 `9fb3b0559` 只调整了 `FlutterAssetRefresh` 注释和最终验证文档，不改变 hook
范围或非 Flutter 判定。代码行为如下：

| 位置 | hook 范围 | 修正前的最终状态 |
|---|---|---|
| `instrumenter.cc` | startup agent 无条件尝试 retransform 两个 `ResourcesManager#createAssetManager` 签名和 `ContextImpl#createPackageContext`；Flutter 是 app 类，native 安装阶段无法据此缩小 framework transform | 普通 App 与 Flutter App 都会执行 hook |
| 两个 `createAssetManager*Exit` | 宿主 APK 分支都会调用 `scheduleRefresh()` | 非 Flutter App 也会先 post 主线程任务，Runnable 内才检测 Flutter；AssetManager 本身不在这条路径被修改 |
| `prepareHostPackageContext` | 所有 `createPackageContext` 返回值都进入；只检查 API、宿主 APK 与 overlay | **非 Flutter App 的同宿主 package context 也会 append-last overlay ApkAssets 并调用 `setApkAssets(..., true)`** |
| `ApplyChangesOverlayPolicy` | host path 包含 base、split 和 public source；非宿主 resDir 继续执行存量 overlay 移除 | WebView provider、SDK 独立资源和其他 package context 不会被 Flutter append；宿主 split 所在 context 会被视为宿主 |
| compat / API < 30 | `createAssetManager` exit 有 compat 早退；package-context 路径依赖 overlay 扫描得到空列表；API < 30 在 `prepareHostPackageContext` 内返回 | 没有 append，但 compat 的 package-context hook 仍做了不必要调用；API < 30 的 `scheduleRefresh` 仍可能 post 后才 no-op |

因此主结论是：**存在需要修复的非 Flutter 副作用**。它不是“hook 全局但最终完全 no-op”，因为普通
非 Flutter App 的宿主 package-context AssetManager 状态确实会被改写；即使 overlay 内容与宿主
Apply Changes 目标一致，也不能把额外 ApkAssets、查找优先级和 `setApkAssets` 重建视为行为等价。

### 18.2 最小修复

- `scheduleRefresh()` 在创建 `Handler` 之前先检查 API 30+，并用当前 Application ClassLoader
  `Class.forName(..., false, loader)` 确认 `FlutterEngine` 存在；非 Flutter App 不再产生主线程任务。
- `prepareHostPackageContext()` 在读取 `Resources/AssetManager` 前执行同一 Flutter 类门禁；只有
  Flutter App 的宿主 base/split package context 才可能追加 overlay ApkAssets。
- `handleCreatePackageContextExit()` 增加 compat 早退并删除所有 App 都会打印的无条件 debug 日志。
- 已运行 Dart、延迟启动 Dart、冷启动 package context、append-last native provider 和
  `idToEngine` 快照逻辑不变；compat 与 Android 11 以下继续使用原有重启路径。

修复后仍需区分“hook 执行”与“状态修改”：framework transform 仍安装到使用 startup agent 的所有
App，这是现有技术边界；非 Flutter App 每次相关 hook 最多执行 fail-open 的 API、Application 与
ClassLoader 门禁，不会继续访问 AssetManager、反射 Flutter 字段、Toast 或投递主线程任务。正常的
`ClassNotFoundException` 静默 no-op，只有门禁本身发生非预期异常时才保留 debug/warn 诊断。

### 18.3 反证与推翻条件

最强替代解释是：“虽然非 Flutter package context 被追加 overlay，但它本来就是宿主 Apply Changes
overlay，所以状态变化行为等价且无害。”该解释不足以推翻主结论：`setApkAssets(existing + overlay,
true)` 会改变普通 AssetManager 的 ApkAssets 列表和 raw asset 倒序优先级，且该调用不是普通 Android
App 修复所需。只有取得以下任一证据才能推翻“存在非 Flutter 状态修改”的结论：

1. 证明 `ContextImpl#createPackageContext` hook 只会从 `FlutterEngine` 调用栈进入；实际 transform
   目标是通用 framework 方法，与此相反。
2. 证明 `prepareHostPackageContext` 在 Flutter 类检测前不会调用 `setApkAssets`；`9fb3b0559` 与
   `4041b19dc` 的实现都没有该门禁。
3. 设备证据证明非 Flutter App 的宿主 package-context AssetManager 在 hook 前后 identity、
   ApkAssets 列表和 raw asset 解析顺序均未改变；这将与明确的 append-last 代码路径冲突，需要解释
   为什么宿主路径与 overlay 条件从未同时命中。

修复后主结论可被推翻的证据是：非 Flutter ClassLoader 仍能进入 `context.getResources()` /
`AssetManager.setApkAssets()`，或仍能观察到 Flutter refresh Runnable；对应 L1 门禁测试与代码路径审计
用于持续检查这一点。

### 18.4 验证策略

测试价值门禁通过：Flutter 类存在性是独立、稳定且会真实决定 Android AssetManager 是否可被修改的
边界，owner 为 `FlutterAssetRefreshTest`（L1）；compat 早退沿用现有
`InstrumenterSourcePolicyTest` 架构守卫。运行中/延迟启动 Engine 的既有测试继续保护 Flutter 正常路径，
不新增重复测试。Wiki 不更新：本次只收紧非 Flutter 内部影响范围，Flutter 用户可见能力和使用方式不变。
