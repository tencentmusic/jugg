# WebView shared-library 路径在资源重建后丢失

关联：[Issue #72](https://github.com/tencentmusic/jugg/issues/72)。

## 问题原因

宿主运行中追加的 WebView/Trichrome 资源路径，在 Apply Changes 的 loader 更新触发 AssetManager 重建时丢失，导致 Chromium 无法从宿主资源中打开 ICU 数据，最终触发 fatal/SIGTRAP。

完整链条如下：

1. WebView 初始化把 WebView APK、Trichrome APK 动态加入宿主当前 AssetManager。Android 16 AOSP 的旧分支调用 `addAssetPathAsSharedLibrary()`，修改当前实例，但不把这些运行时追加项同步到宿主 `ResourcesKey.mLibDirs`。
2. 后续 Service 创建再次调用 `LoadedApk.getResources()`，Android Studio 的 `ResourceOverlays.updateLoader()` 调用 `ResourcesLoader.setProviders()`，触发 `ResourcesManager.UpdateHandler.onLoaderUpdated()`。
3. ResourcesManager 按 ResourcesKey 重建 AssetManager，并替换同一 Resources 持有的 ResourcesImpl。原实例运行中追加、而 key 中没有的路径随之丢失；宿主增量 overlay 仍然存在。
4. Chromium 138 的 `ApkAssets.open()` 使用 application context 的 AssetManager 调用 `openNonAssetFd()`。本机 ICU 实际位于 Trichrome APK 的 `assets/icudtl.dat+com.google.android.webview+`；该 APK 已不在新的宿主 AssetManager 中，打开失败并返回无效 fd。
5. Chromium 的 ICU 初始化检查失败，触发 fatal/SIGTRAP。

行为 owner 是宿主 AssetManager 的重建返回边界：需要在资源交给后续消费者前，保留宿主原先成功登记的 shared libraries。语言配置更新只是现场调用链的一环；日志中该入口仍保留 WebView/Trichrome，实际丢失发生在后面的 loader 重建。非宿主 WebView context 的 overlay 过滤前后也都保留自身 APK。

日志里的 overlay ENOENT 是查找过程中的失败记录。AOSP `AssetManager2::OpenNonAsset()` 会在某个 provider 未命中后继续查找其他 provider；本次关键证据是新 AssetManager 的完整资源列表缺少 WebView/Trichrome。

## 失败现场

OPPO PLS120，Android 16 / API 36，WebView 138.0.7204.179；JOOX 稳定复现。2026-10-27 设备时间 17:37、PID 11322 的日志记录：

- event 45/47/49/59：宿主 Resources@c9291d8 的 AssetManager@88106ab 已包含 WebView 和 Trichrome APK。
- event 64：语言配置更新入口仍保留上述路径。
- event 79/82：Service 创建经 Android Studio `ResourceOverlays.updateLoader -> setProviders -> onLoaderUpdated` 触发 ResourcesManager 重建；ResourcesKey 的 libs 没有 WebView/Trichrome。
- event 85/87：同一 Resources 已对应 AssetManager@432d7d1，完整 22 条路径缺少 WebView/Trichrome，保留宿主 overlay。
- 17:37:39.088 文件 ENOENT，39.089 Chromium ICU invalid fd，随后 SIGTRAP。

静态对照 Android 16 framework：WebViewFactory 旧分支直接调用 `addAssetPathAsSharedLibrary()`；loader 更新按 ResourcesKey 重建，不读取旧实例运行中追加的路径。非宿主 WebView context 修正后自身 APK 仍保留。

原始证据保存在本机 `build/investigations/issue72/20261027-143856/`。main buffer 仅 256 KiB，ICU 请求入口部分缺失；Chromium 消费者调用链由对应版本源码补充，修复后 ICU 读取结果由进程映射另行核验。

## 实现

在 Jugg 已有 framework hook 边界修复，覆盖 Android Studio 和 Direct 触发的实例重建：

1. Android 11+ 下配对 shared-library 原调用，正 cookie 表示原调用已成功；仅记录实际包含宿主 base APK 的 AssetManager。
2. 进程内按首次加入顺序去重保存成功路径，不硬编码 WebView 包名、APK 路径或 ROM 型号。
3. 在宿主 createAssetManager 新旧签名返回前，按记录顺序复用原 shared-library API 补齐缺失路径。已有路径直接跳过；各路径恢复失败独立记录 warn，保留原返回对象。
4. 非宿主 overlay 修正和 compat 分支不变；不修改 ResourcesKey 缓存对象，不跳过 loader 更新，不调整业务语言初始化。
5. 最初修复及现场核验使用 agent 1.0.87；诊断收尾后的最终实现为 1.0.88，删除临时采集逻辑，保留修复所需 hook。

宿主归属必须使用 ApplicationInfo.sourceDir。getAllApkPaths 包含 shared libraries 与 RRO，不能用于新恢复流程的 AssetManager 宿主判断。

这项修复维护运行时已成功接受的路径，不需要修改 JOOX 的语言切换或 WebView 初始化代码。无已记录路径、路径已经存在、非宿主资源及 compat 早返回分支不执行路径补加。Android 11 以下不记录路径。

## 潜在副作用与适用边界

| 影响 | 当前实现与现场情况 | 回归关注点 |
|------|--------------------|------------|
| 宿主资源集合扩大 | 记录范围是所有成功追加的宿主 shared libraries，恢复到后续包含宿主 base APK 的新 AssetManager；不限于 WebView。复测实际恢复过 `androidx.window.extensions.jar`。这些路径原先在某个宿主实例上有效，但其他宿主配置实例未必原本拥有全部路径。 | 不同 Activity/配置下的资源与 WebView 显示是否正常。非宿主包不接受这批路径。 |
| 查找顺序变化 | 仅对缺失项按首次记录顺序补加，保留 framework 的 shared-library 标志和 loader 处理；不会重新排列已有项。因此无法保证缺失项相对全部已有项还在原来的位置，同名 asset 的查找结果可能受影响。 | 资源增量内容、主题/语言资源和共享库资源；本轮快照中宿主 overlay 仍位于最后。 |
| 重建耗时与日志量 | 有记录时需要检查新实例路径；缺失项额外执行 framework 资源加载和缓存更新，增加重建开销。1.0.87 调查版的快照曾导致 main buffer 覆盖；1.0.88 已删除快照和 ZIP 检查。 | 后续回归关注启动/资源更新耗时；尚无性能量化结果，不能宣称无额外开销。持续采集日志避免关键事件丢失。 |
| 外部 API 与路径生命周期 | 使用 framework hidden API；反射或加载失败时逐项 warn 并继续其他路径，原返回对象仍返回。进程内只保存去重后的路径字符串，没有移除旧登记路径的机制。 | 兼容失败时对应库仍可能缺失；进程内已登记路径失效的情况尚未验证。 |

当前未观察到上述副作用；已有路径直接跳过能够避免重复补加，但正常设备仍会做必要的路径检查，其回归结果待交叉验证。

## 验证

- 失败证据：上述原始日志及稳定真机复现。
- 自动化价值判断：新失败边界是实际 framework/native AssetManager 经 ResourcesLoader 重建后的 assets/fd 行为；普通 JVM mock SDK 不暴露对应 hidden API，模拟重建等同重写系统实现。本次不新增仅固定内部集合或反射调用的测试，不为测试增加 seam。
- 复用回归：ApplyChangesOverlayPolicyTest、FlutterAssetRefreshTest、InstrumenterSourcePolicyTest，保护既有过滤策略、Flutter 消费和 instrumentation 边界。
- 构建与产物：双 ABI agent、DEX、Java hook `(I)I` 签名及插件内嵌 bundle 核对；插件 buildPlugin。
- 真机：由用户部署验证。故障 OPPO 修复验证通过后，再对两台正常设备交叉验证；Agent 不执行部署。

### 2026-10-27 OPPO 修复版核验

用户部署后反馈 crash 暂未复现。只读采集保存在本机 `build/investigations/issue72/verify-20261027-183709/`，详细索引为其中的 `README.md`。设备时间比主机约快 3 小时，以下保留设备原始时间。

- 环境：同一 OPPO PLS120、Android 16/API 36、WebView 138.0.7204.179。PID 30881 的 maps 同时包含 `1.0.87-jugg_jvmti_agent.so` 和 `1.0.87/jugg-instruments.jar`，确认修复版实际加载。
- 原触发链仍发生：21:35:48.892，event 452 的调用栈包含 `ResourceOverlays.updateLoader -> setProviders -> onLoaderUpdated -> createAssetManager`。
- 重建结果正常：event 456 返回时包含 WebView APK、Trichrome APK，宿主 overlay 在最后。
- 恢复分支确实执行：21:35:48.898，`Restored runtime shared library` 记录 AssetManager@5f5b758 成功恢复 window.extensions.jar，cookie=26；event 455 对应恢复后快照。
- ICU 数据已映射：Trichrome APK 内 ICU 条目为 STORED，数据偏移 88，长度 8,896,496 字节；进程存在该 APK offset=0 的只读共享映射，长度 8,900,608 字节，与数据范围按 4096 对齐后的长度精确相等。依据为 `icu-zip-mapping-proof.json`、APK central directory 和 `app-maps.txt`。
- 进程持续存活：21:43:04 PID 仍为 30881，距启动约 8 分钟。本轮保留日志未发现同类失败，退出历史只有先前的用户 FORCE STOP 和 WebView 子进程清理。

结论：修复代码已生效，本轮原触发链执行后资源列表正常，ICU 数据成功映射。证据缺口是首次 WebView/Trichrome 登记、补回及 ICU fd-return 日志已被缓冲覆盖；且保留的 event 453 beforeFix 已含这两条路径，不能用该次快照证明两条路径刚被补回。完整的“缺失→补回”事件配对仍需从进程启动开始持续采集。

### 用户复测验收

最终 1.0.88 使用常规 logcat、进程 maps/退出信息和用户可见结果回归；需要恢复成功日志时，在进程启动前通过既有 `log_debug` 标记启用 debug。以下原始因果采集项已由 1.0.87 调查版部分覆盖，最终版不再输出临时快照：

1. WebView/Trichrome 动态加入被记录；loader 更新仍触发资源重建。
2. 重建后的宿主实例含上述路径，overlay 顺序正常且无重复。
3. ICU 请求名、实际 manager、有效 fd/offset/length 连通，原崩溃消失。
4. 再执行一次资源增量，确认新资源显示、WebView 正常；覆盖按原步骤冷启动和进程内更新。
5. 相同 APK/部署操作在另外两台设备复测，采集同类日志，解释正常设备与 OPPO 的实际分支差异。

## 为何表现与机型有关

当前因果链指向设备携带的 framework/ROM、WebView 资源注册方式，以及运行时加载时序。机型是这些软件组合的外在标识；代码中没有按 OPPO 型号区分的修复分支。

本次崩溃需要依次满足：**WebView 路径仅存在于当前宿主实例 → loader 更新重建并丢掉路径 → ICU 尚未成功打开/映射，再从缺路径的新实例读取**。设备软件行为或执行顺序改变其中任一环节，都可能使同一操作不再触发该崩溃。

| 差异维度 | 如何影响这条故障链 | 当前证据 |
|----------|--------------------|----------|
| WebView 资源注册机制 | Android 16 AOSP `WebViewFactory` 根据 `registerResourcePaths` flag 选择系统 `Resources.registerResourcePaths()` 或旧的单实例 `addAssetPathAsSharedLibrary()`；注册机制不同会改变路径跨重建保留的方式。 | 已核对 AOSP 两个分支；OPPO 故障现场已证明旧实例有路径而重建 key 缺路径。尚未取得该次启动 flag 实值，不能仅凭 Android 版本认定分支。 |
| ROM/framework 重建行为 | 是否按缺少动态项的 ResourcesKey 创建新实例、是否已自行补齐路径，决定资源会不会丢失。 | OPPO 日志已记录同一 Resources 的 manager 替换及路径丢失；其他设备待采集。 |
| 初始化时序 | ICU 若在破坏性重建之前已成功映射，后续即使资源路径变化，也可能不会在首次 ICU 初始化处崩溃；重建后才读取则暴露问题。 | 失败时序有现场证据；复测进程已映射 ICU。两台正常设备是否因时序避开故障尚未确认。 |
| WebView/provider 版本和打包布局 | 决定加载过程、ICU 资源实际存放位置与查询名，影响上述路径缺失是否命中 ICU 消费。 | 本 OPPO 的 WebView 为 138.0.7204.179，ICU 在 Trichrome APK；正常设备版本与布局待核对。 |

按用户要求，先聚焦故障 OPPO，再对两台正常设备使用相同 APK、部署操作和复现步骤交叉验证。先对照 fingerprint、WebView/provider、部署结果与 ICU 映射；若需定位具体分支，再专项采集系统 flag、动态登记、重建前后路径和首次 ICU 读取时序，给出每台设备具体缺少哪一个触发条件。当前尚不能把上表某一候选直接认定为那两台设备的实际差异原因。

## 诊断收尾与最终交付

临时诊断用于建立根因证据，不参与修复。1.0.88 收尾按以下边界精简：

| 内容 | 处理 | 原因 |
|------|------|------|
| `ResourceDiagnostics` | 删除整个辅助类及所有调用 | startup/配置/实例快照、调用栈、ZIP 条目检查和 fd 观测均已完成本次调查职责，不需要长期运行。 |
| 七个诊断专用 hook | 删除 AssetManager 的 setApkAssets/setLoaders/openNonAssetFd、Resources 的 getAssets/addLoaders/updateConfiguration、ContextImpl 的 createConfigurationContext hook | 即使不打印日志，插桩及回调仍有运行成本；移除无修复职责的 hook。原有 Flutter createPackageContext hook 保留。 |
| native hook 安装成功日志 | 删除本次增加的 `Resource diagnostic hook installed` 日志 | 保留原有安装失败告警，避免正常启动重复输出。 |
| shared-library 正常登记日志 | 删除 | 路径集合维护是内部过程，无需逐项输出。 |
| shared-library 成功恢复日志 | 改为既有 `LogUtils.d` | 默认关闭；复用 `/data/local/tmp/jugg/log_debug` 标记，不新增开关或配置。标记在类初始化时读取，需在目标进程启动前设置。 |
| `RuntimeSharedLibraries` 及 shared-library enter/exit hook | 保留 | 负责宿主归属、正 cookie 成功判断、路径去重和重建前补齐，是修复行为本体。 |
| 登记、检查、恢复失败告警 | 保留 warn | 失败会影响资源可用性，需要保留定位依据，不依赖 debug 开关。 |

agentVersion 从已验证的 1.0.87 升至 1.0.88，避免部署端复用同版本旧 bundle。此次只清理观测逻辑，宿主判定、恢复条件、原 API 调用和正常/compat 分支语义保持不变；不执行设备部署。

验证采用既有 ApplyChangesOverlayPolicyTest、FlutterAssetRefreshTest、InstrumenterSourcePolicyTest 定向回归，双 ABI native/DEX 构建和插件 buildPlugin；检查最终产物不再包含诊断类、保留 shared-library hook 的 `(I)I` 返回签名。无新增独立行为，不新增绑定日志文字或删除细节的自动化测试。26 个定向用例全部通过（4 + 12 + 10），双 ABI native/DEX 构建通过，最终插件 buildPlugin 通过。产物核对确认 DEX/runtime JAR 不含诊断类，双 ABI so 不含诊断 hook，保留 `(I)I` 修复 hook，插件内嵌 bundle/runtime 与本次输出逐字节一致。首次产物检查发现临时 `build/tmp/buildInstrumentJar/bootstrap.jar` 残留旧诊断类；删除该生成文件及对应 DEX 输出后重建，最终产物核对通过。未新增测试或执行设备部署。

按用户要求，将本问题最后三个提交（临时诊断、修复、补充分析）与本次精简合并为一个 `[bugfix][deploy]` 提交，保留 Issue #72 关联。

## 源码与证据索引

- Jugg：`RuntimeSharedLibraries.java`（记录/补齐路径）、`InstrumentationHooks.java`（createAssetManager 返回边界及 shared-library enter/exit）、`ApplyChangesOverlayPolicy.java`（宿主 sourceDir）、`instrumenter.cc`（原调用正常返回后取得 cookie）。
- [Android 16 WebViewFactory](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/java/android/webkit/WebViewFactory.java)：两种资源注册分支。
- [Android 16 ResourcesManager](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/java/android/app/ResourcesManager.java)、[AssetManager](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/java/android/content/res/AssetManager.java)：loader 更新重建与单实例路径追加。
- [Android Studio ResourceOverlays](https://android.googlesource.com/platform/tools/base/+/refs/heads/mirror-goog-studio-main/deploy/agent/runtime/src/main/java/com/android/tools/deploy/instrument/ResourceOverlays.java)：updateLoader/setProviders 调用链。
- [Chromium 138.0.7204.179 ApkAssets](https://chromium.googlesource.com/chromium/src/+/refs/tags/138.0.7204.179/base/android/java/src/org/chromium/base/ApkAssets.java)、[Android 16 AssetManager2](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/libs/androidfw/AssetManager2.cpp)：application context 读取 ICU 及 provider 未命中后继续查找。
- 上述外部源码的已读取副本与来源清单：`build/investigations/issue72/20261027-143856/research/`。AOSP 用于解释机制，OPPO 实际行为以设备日志为准。

## 文档

同步运行时知识库与代码地图。已核对 Wiki 的 jugg-runtime / jugg-jvmti-agent 概念页：现有非宿主 overlay 修正说明仍然成立；本次透明 runtime 修复不改变安装、部署操作或支持范围，无需更新 Wiki。

## 当前状态

原修复已完成，26 个现有定向用例、双 ABI agent/DEX 构建、native exit-hook 的 `(I)I` DEX 签名检查和 IDE 定向错误分析通过，agent 1.0.87 插件包已交付；最终 1.0.88 已通过去诊断后的 26 项定向回归、双 ABI/DEX 构建和插件打包，产物不含临时诊断类及 hook，尚未部署到设备。用户已在故障 OPPO 部署；本轮资源重建、补丁执行和 ICU 映射核验正常，crash 暂未复现。从启动开始的缺失/补回配对、再次资源增量的显示回归，以及两台正常设备的交叉验证仍待完成。最终原因闭环须包含三台设备的实际差异证据。
