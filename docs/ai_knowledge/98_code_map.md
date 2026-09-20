# 代码路径速查表（Code Map）

> 最后核对：2026-09-16
> 口径：生产代码目录（不含 `build/` 与 `src/test/`）  
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 核心层（`main/src/main/java/com/sickworm/intellij/jugg`）

| 领域 | 关键类/接口 | 目录 | 职责/说明 | 状态 | 最近同步 |
|------|-------------|------|-----------|------|----------|
| 编译总控 | `JuggCompiler`, `BaseCompiler`, `CompileTask` | `compiler/core` | 增量编译主流程、阶段顺序与循环重编译 | 稳定 | 2026-09-05 |
| 外部源码构建 | `ExternalBuildCompiler`, `ExternalBuildTaskRunner`, `resolveExternalBuilds` | `compiler/external` | Dart/C/C++/Flutter asset/CMake 配置输入变化后从全量基线派生当前 variant 的 Flutter native 输出/native merge task；一个物理 source 可解析为多个 module target，在一次 Gradle invocation 中执行全部去重 task，并按 module 收集输出；命中判定按 `excludedDirs` -> 存在的普通文件 -> `configFiles` 精确路径 -> `inputDirs` 路径覆盖 + `filterRules` 匹配的顺序执行，同一 build info 内取路径最深的实际命中目录作为 `ChangedFile.baseDir`；Flutter native 输出按归档或目录分派收集 `.so`，C++ 只收集本轮 invocation 由 collector 按 APK owner strip 契约产出的 stripped 输出，与 Flutter assets 一起接入既有增量部署；所有匹配 target 必须全部支持并成功 | 稳定 | 2026-09-15 |
| 源码编译 | `SourceCompiler`, `JuggAptCompiler`, `IJuggAptProcessor`, `JavaCompiler`, `KotlinCompiler`, `KotlinCompilerInvoker`, `AndroidJarClasspathRetry`, `KotlinComplementaryFilesCache`, `K2JVMCompilerIsolate`, `TransformerCompiler`, `HiltAndroidEntryPointTransformer`, `DexCompiler`, `DexFileMaker` | `compiler/source`, `compiler/source/apt`, `compiler/source/kotlin` | Java/Kotlin 编译与 DEX 生成；`DexCompiler` 在 D8 前单次分析 program class，并通过显式 `ClassPreparation` 依次交给 Transformer、Desugar 和 D8，已有 Hilt 生成物时恢复 Android 入口父类、super 调用与 Receiver 注入；Kotlin compiler plugin 参数与 plugin JAR 按同一 current-to-parent module 范围聚合，并按 plugin id 局部处理缺参或不支持参数的单次降级；ROM hidden API 被前置 SDK `android.jar` 遮蔽时按源文件记忆并后置重试一次；D8 优先隔离加载项目 AGP 的 R8 code source；typed common sources 与普通 KMP complementary/fragment/baseline 隔离保持 Gradle 语义 | 稳定 | 2026-09-10 |
| 资源编译 | `ComposeResourceCompiler`, `ComposeResourceGeneratorBridge`, `ComposeResourceScanner`, `ComposeValueResourceConverter`, `ResourceOverlayCompiler`, `ResourceCompiler`, `ArscCompiler`, `AssetOverlayCompiler`, `RDexForSubmoduleCompiler`, `Aapt2DaemonInvoker` | `compiler/compose`, `compiler/overlay`, `aapt2` | Compose resource 准备、unsupported fail-closed、generated diagnostic 回映射、官方 accessor generator bridge、通过 `ModuleBuildPathInfo.composeResourceGeneratedSourcePath` 回写 generated Kotlin 以支持 IDE 索引、现代 `Asset` overlay、显式 `ClasspathResource` 类型的 legacy APK 根目录 classpath resource overlay，以及 Android res/manifest 的 aapt2 link；`RDexForSubmoduleCompiler` 从宿主主 R 派生普通 module 与外部 AAR namespace 的 R.dex；Compose resource 不进入 AAPT2 | 稳定 | 2026-09-15 |
| DataBinding | `DataBindingArgsManager`, `DataBindingGenBaseClassesCompiler`, `DataBindingSetterStoreCache`, `DataBindingGenMapperCompiler`, `DataBindingClasspathHelper` | `compiler/databinding` | DataBinding/ViewBinding 增量处理；GenMapper 单次 APT/KAPT 输出 current-module store 并维护 merged setter store cache；Mapper 收集当前模块、直接工程依赖和 AAR store | 稳定 | 2026-09-07 |
| Manifest | `AndroidManifestCompiler`, `AndroidManifestMerger`, `ManifestDiffer` | `compiler/manifest` | 清单差异合并；混淆映射由 `compiler/obfuscation` 承载 | 稳定 | 2026-05-23 |
| 混淆映射 | `ClassMinifyCompiler`, `DexMinifyCompiler`, `ClassObfuscator`, `R8MappingReader`, `R8UsageReader` | `compiler/obfuscation` | release 混淆映射一致性、`usage.txt` 删除成员读取与 `_jugg_fix` compatibility stub 重写；是否混淆由 `ICompileContext.isMinified`（当前变体真实 `minifyEnabled`）决定，开启但缺 mapping 时明确失败 | 稳定 | 2026-09-16 |
| 自定义编译器 | `CustomCompilerManager`, `ICompilerCreator`, `CompileUiHandler` | `compiler/custom` | SPI 扩展、远端下载 jar、动态装载；编译交互抽象（供 IDE/CLI） | 稳定 | 2025-01-20 |
| 常量引用分析 | `ConstRefEngine`, `ConstRefAnalyzer`, `ConstRefChangeTracker`, `ConstRefImpactResolver`, `ConstRefSessionCache` | `compiler/constref` | 编译期常量定义/引用分析；按”真实变更常量 key”定位受影响源码；DB 主导+会话缓存；repo/worktree 共享缓存与过期清理 | 稳定 | 2026-03-04 |
| 部署文件管理 | `JuggDeployer`, `DeployFileManager`, `DeployFileStateTracker`, `DeployDataPlanner`, `CompileEffectAnalyzer`, `DeployHistoryManager`, `ClassFileLookupHelper` | `deploy/core` | 部署调度、文件准备；`DeployFileManager` 作为 facade，状态跟踪/部署数据计算/编译影响分析已解耦 | 稳定 | 2026-02-27 |
| 影响分析 | `DeployDataGenerator`, `CompileEffectAnalyzer`, `ClassFileParser`, `DeployDataDatabase`, `IncrementalDeployDataDatabase`, `ClassNodeComparator`, `InlineMethodDetector` | `deploy/data` | 类结构变更传播和部署数据生成；复用 pre-D8 class analysis 补齐 default interface、完整父类链与 Transformer classpath；双层数据库与引用索引；内联方法影响检测 | 稳定 | 2026-09-06 |
| 部署数据模型 | `JuggDeployData`, `DeployItem`, `LaunchResult` | `deploy/run` | 下发设备的部署数据结构；`targetApkPaths` 和 `filterForApks()` 支持多 APK 归属分流，`flutterJitRuntimeFiles` 记录本轮 Flutter JIT runtime 变化并要求完整重启 App | 稳定 | 2026-09-13 |
| App sandbox 部署 | `AppSandboxExecutor`, `DirectAppSandboxDeployTransport`, `DirectHotReloadWriter`, `DirectOverlayWriter`, `DirectOverlayStateChecker`, `RootlessCompatDeployStaging`, `RootlessCompatImportConfirmer`, `RootlessCompatDeployArchive`, `RootlessCompatDeployImporter`, `FlutterJitCacheInvalidator` | `deploy`, `deploy/hotreload`, `deploy/direct`, `deploy/flutter`, `jvmti_agent/.../hotfix` | 以 `run-as`、UID 与 SELinux context 能力选择 AS 或 Direct transport；Direct 模式固定普通 shell、root adbd 或非交互 `su`，复用同一 executor 完成 overlay、new class in-memory dex elements、JVMTI redefine、Android 11+ 资源刷新与 Activity 重建；三者全部不可用时普通 payload 转 compat redeploy，兼容 payload 由 `RootlessCompatDeployStaging` 暂存到 `/sdcard/Android/data/<package>/files/jugg/rootless-compat`，`RootlessCompatDeployImporter` 从 `Context.getExternalFilesDir(null)` 在 App 启动早期导入，`RootlessCompatImportConfirmer` 确认后才提交部署状态；`FlutterJitCacheInvalidator` 用同一 executor 删除 `app_flutter/res_timestamp-*`，让 Flutter 重启后从 overlay 重新解压 Dart JIT runtime | 稳定 | 2026-09-15 |
| AndroidTest 运行模型 | `AndroidTestRunSpec`, `TestFilter`, `InstrumentCommandBuilder`, `AndroidTestTargetResolver`, `LibraryTestApkBackfillPlanner`, `LibraryTestApkBuildHistory`, `InstrumentationOutputParser`, `InstrumentationConsoleRenderer`, `InstrumentationSmRunnerBridge`, `AndroidTestResultModel` | `deploy/instrument` | androidTest instrumentation 参数、sourcePath target 解析、library Test APK 懒加载 plan 与跨仓库 build history、`am instrument` 命令构造、输出解析、文本 console 渲染、SM Test Runner service message 映射与按 method 归档 logcat | 开发中 | 2026-05-17 |
| 项目模型 | `JuggProjectInfo`, `ModuleInfo`, `ExternalBuildInfo`, `ExternalBuildInputDir`, `ExternalBuildInputFilterRule`, `ExternalBuildInfoRequest`, `ExternalBuildInfoUpdate`, `ComposeResourceInfo`, `ComposeResourceSupportStatus`, `ComposeResourceDirectory`, `ModuleBuildPathInfo`, `JuggPathManager`, `JuggGlobalPathManager`, `ModuleApkBelongs` | `project/data`, `project` | 根快照引用项目 AGP R8 分发包；模块快照保存路径、依赖、Kotlin common roots/fragment graph、Compose support 状态、资源根及 Flutter/C++ task、native 输出与 Flutter assets 输出位置，以及外部构建的递归输入根及其文件类型规则、配置输入和排除目录；request/update 模型承载单次 Gradle invocation 的定向 metadata 刷新；项目信息读取/序列化；项目级路径与全局路径统一管理；模块到 APK 归属封装 | 稳定 | 2026-09-14 |
| 依赖变更 | `DependencyChangeManagerByGradle`, `DependencyChangeManagerBySync` | `project/dependency` | 依赖变更检测策略 | 稳定 | 2025-01-20 |
| Gradle 信息读取 | `GradleProjectInfoReaderManager`, `GradleProjectInfoReader`, `NativeBuildMetadataReader`, `NativeStripConfigCache`, `GradleVariantCollector`, `ProjectInfoSerializerInGradle`, `GradleDependencyDiffer` | `gradle/script` | 通过 Gradle 反射读取模块信息与 Android plugin classloader 中 D8 的 code source；收集 Kotlin common/fragment、Compose resource，以及当前 variant 的 Flutter/C++ 外部构建 task、带文件类型规则的递归输入根、配置输入、排除目录、native 输出和 Flutter assets 输出位置，并同步到生成 init script；`juggCollectExternalBuildInfo` 可在 external task 后只输出本 invocation 请求的 metadata，不执行完整 project-info 读取，并按 request 携带的 APK owner 复现 AGP 单文件 strip 语义产出 invocation 级 stripped native 输出；完整构建期间 `NativeStripConfigCache` 把 APK owner 的 `keepDebugSymbols` 与 ABI strip 工具发布到 `build/jugg/classpath/native_strip`，使 Configuration on Demand 下未被配置的 APK owner 也能完成 strip；`GradleVariantCollector` 只提供变体选择 | 稳定 | 2026-09-16 |
| Gradle 编译客户端 | `LocalGradleCompileClient`, `RemoteGradleCompileClient`, `GradleWrapperRepairer`, `ApkLookupPlanner`, `CmdExecutor`, `ProcessOutputReader` | `gradle/compile` | 本地/远端 Gradle 构建执行；`RemoteGradleCompileClient` 也提供不进入 Run task 流程的单条非交互远程命令执行；Windows 命令输出按行严格校验 UTF-8，失败回退 GBK；`GradleWrapperRepairer` 在已有 wrapper properties 时补齐缺失 wrapper 启动文件，并在 Windows 远程编译同步前将 Unix `gradlew` 的 CRLF 转为 LF；AndroidTest 下区分 required app/app-test APK 与 optional history library Test APK 收集 | 稳定 | 2026-08-18 |
| MCP 协议 | `McpLocalServer`, `McpBaseInvoker`, `McpToolInvoker`, `McpRequestValidator` | `ai/mcp/` | MCP HTTP + JSON-RPC 处理 | 稳定 | 2026-04-26 |
| MCP 工具 | `McpToolActionRegistry`, `CompileJobManager`, `GetCompileStatusMcpToolAction`, `LayoutDumpHelper`, `LayoutHtmlConverter`, `WaitLogsMcpToolAction`, `CrashDetector`, `LastDeployTimestampRegistry` | `ai/mcp/actions`, `ai/mcp/util` | 工具注册、异步编译状态管理；`LayoutDumpHelper` 封装 layout_dump 核心逻辑（设备解析、px→dp、公开 HTML 输出、内部 JSON 文件），`LayoutHtmlConverter` 将 JSON 视图树转为精简 HTML（含虚拟节点裁剪）；`WaitLogsMcpToolAction` 阻塞式等待 App 日志（marker/crash/timeout 判停）；`CrashDetector` 复用 crash 信号识别；`LastDeployTimestampRegistry` 记录 deploy/restart 时刻作为日志起点 | 稳定 | 2026-08-02 |
| AI 技能安装 | `JuggSkillInstaller`, `JuggHookInstaller`, `CcSwitchCommonConfigGuideExporter`, `PythonRuntimeResolver`, `CodexPermissionRuleInstaller`, `JuggCliAutoUpdater`, `ClientSetupDocExporter`, `IAgentInstaller`, `agents/*` | `ai/skills` | 安装/更新 `jugg-android-dev-loop` skill、CLI 与 hooks（资源来源 `docs/skills/*.zip`）；Gemini 安装通道同时保留 Gemini CLI 的 `~/.gemini/settings.json`，并在 `~/.gemini/config` 已存在时安装 anti-gravity skill 与命名 hooks，按其 camelCase payload、JSON 字符串化工具参数、`run_command` 和 Stop decision 契约适配；`JuggCliAutoUpdater` 比较 bundled `SKILL.md` version 与 `~/.jugg/skills/jugg-android-dev-loop/SKILL.md`，更高才覆盖 `~/.jugg/bin` 和已安装 skill；改 CLI/skill 必须同时递增 `CLI_VERSION` 与 `SKILL.md` version。hooks 安装校验 Python 3.7+（`python3` 优先、`python` 回退）；Windows CLI wrapper 运行时再按 `python3`、`python`、`py -3` 验证并选择解释器；成功安装 Claude hooks 后，安装结果关闭再异步检查桌面版 / `cc-switch-cli` 共用的配置目录，用户确认后仅导出 Jugg Claude hooks 至 `~/.jugg/cc-switch` 并打开文件，不读写 CC Switch provider 或数据库；Windows CLI 安装由 `JuggSkillInstaller` 写入用户级 PATH，macOS/Linux 创建 `~/.local/bin/jugg` symlink；Codex skill 安装时同步写入 `rules/default.rules` 的 Jugg CLI `prefix_rule`，并导出 `agent_setup.md`；`IAgentInstaller` 统一描述各 agent 的 skill/hook/rules 安装目标，Installer 仅保留调度 | 稳定 | 2026-09-20 |
| MCP ViewHierarchy 通信 | `ViewHierarchyClient`, `ViewHierarchyRequest`, `ViewHierarchyResponse` | `ai/mcp/viewhierarchy` | `layout-dump` / `tap` 元素模式 / `view-inspect` 的 App 内 LocalSocket 通道（Server-only，无 uiautomator 回退） | 稳定 | 2026-03-09 |
| 工具模块 | `Aapt2DaemonInvoker`, `ApkFileModifier`, `CustomApkSignScriptRunner`, `GitManager`, `JuggLogger`, `JuggServer`, `PublicUpdateChecker`, `PluginVersionComparator`, `JuggEventLocalStore`, `IssueReportBundleBuilder`, `IssueReportUploader`, `ExpiredArtifactCleaner`, `PlatformApi` | `aapt2/`, `apk/`, `git/`, `logger/`, `server/`, `diagnostics/`, `project/`, `platform/` | 通用基础能力；`PublicUpdateChecker` 在无后台时并发 5s 查询 Marketplace 与 GitHub 更新；`ApkFileModifier` 在同目录临时副本上插入、对齐、签名、校验后原子替换，`CustomApkSignScriptRunner` 可按 Run Configuration 用项目脚本替换默认 keystore 签名；report 事件写入 `~/.jugg/action.db`，问题诊断使用白名单包和独立单目标上传；MCP 与问题诊断临时产物分别保留 30 天和 7 天 | 稳定 | 2026-09-19 |

---

## 2. IDE 层（`idea/src/main` + `idea/src/ide_entry`）

| 入口 | 文件路径 | 说明 |
|------|----------|------|
| IDE 总管理器 | `idea/src/main/java/com/sickworm/intellij/jugg/JuggManager.kt` | 初始化、同步事件、MCP runtime 装配、检查更新编排 |
| 公开渠道更新安装 | `idea/src/main/java/com/sickworm/intellij/jugg/server/PublicUpdateInstaller.kt` | 纯反射执行公开渠道插件更新与安装 |
| 运行任务编排 | `idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/JuggRunningTask.kt`, `idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/JuggDebugSessionManager.kt` | 编译与部署串联主流程；Debug executor 成功部署后由 `JuggDebugSessionManager` 做单设备 Java debugger attach |
| 编译入口 | `idea/src/main/java/com/sickworm/intellij/jugg/compiler/JuggCompileHelper.kt` | 增量/Gradle 回退判定；外部源码按全量基线命令和最新 module metadata 预检，旧 Flutter metadata 强制刷新一次；AndroidTest Gradle build 读取 library Test APK build history 并注入回放任务 |
| 部署入口 | `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployerHelper.kt`, `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/LaunchContextFactory.kt`, `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/flow/DeployStateRecover.kt`, `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/flow/DeployRetryHandler.kt`, `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/applychanges/CustomApkInstallScriptRunner.kt`, `idea/src/main/java/com/sickworm/intellij/jugg/deploy/direct/DirectOverlaySwapTransport.kt`, `main/src/main/java/com/sickworm/intellij/jugg/deploy/direct/DirectOverlayWriter.kt`, `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeploymentCacheStore.kt`, `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/instrument/LibraryTestApkBackfillHelper.kt` | 部署策略、recover、重试、agent 协调；`LaunchContextFactory` 统一创建 deviceAdb、install session、installer metadata 与 Direct Overlay lifecycle facts，并注入部署请求携带的自定义安装脚本配置；`DeployStateRecover` 负责 `recoverDeployState` / `tryDryDeploy`，`DeployRetryHandler` 负责 `tryRetry`，均经 `IJuggDeployHelperRunHost` 回调 `JuggDeployerHelper`；普通 App install/reinstall 可由 `CustomApkInstallScriptRunner` 在本地工程根目录执行用户脚本，androidTest 继续使用默认 installer；`deploy` 分派为 `deployInstall` / `deployChanges`；Direct Overlay 统一放在 `deploy.direct` 包，Writer/StateChecker 等不依赖 IDE 的实现下沉到 `main`，deployment cache 经 `IJuggDeploymentService` 注入，磁盘 cache 由 `JuggDeploymentCacheStore` 保存 Jugg 自有 snapshot；sourcePath 命中缺失 self-targeting library Test APK 时做单模块懒加载补齐，并在成功后记录 build history |
| 核心部署器 | `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployer.kt` | install/codeSwap/fullSwap |
| 部署状态 | `idea/src/main/java/com/sickworm/intellij/jugg/deploy/DeployStateManager.kt` | 设备状态与部署可行性 |
| 插件加载 | `idea/src/ide_entry/java/com/sickworm/intellij/jugg/loader/JuggLoader.kt` | 类加载隔离与桥接；`com.sickworm.intellij.jugg.ide` 保持稳定，热更新 UI 仅通过基础类型跨边界 |
| 初始化器 | `idea/src/ide_entry/java/com/sickworm/intellij/jugg/loader/JuggInitializer.kt` | 插件生命周期入口 |
| Control Panel 桥接 | `idea/src/ide_entry/java/com/sickworm/intellij/jugg/ide/JuggControlPanelHost.kt`, `idea/src/ide_entry/java/com/sickworm/intellij/jugg/ide/ui/OpenJuggControlPanelAction.kt`, `main/src/main/java/com/sickworm/intellij/jugg/ide/controlpanel/`, `idea/src/main/java/com/sickworm/intellij/jugg/ide/ui/JuggControlPanelController.kt` | 稳定 Host/Action 仅跨边界传递 JComponent；Controller 持有 Model/Panel 并由 Manager clear；main Model 统一 facts/events |
| 运行配置 | `idea/src/ide_entry/java/com/sickworm/intellij/jugg/ide/JuggRunConfiguration.kt`, `idea/src/main/java/com/sickworm/intellij/jugg/ide/JuggDebugProgramRunner.kt` | run config 定义；`JuggDebugProgramRunner` 接管 Jugg + Debug executor，让 Debug 按钮可用 |
| androidTest 运行入口 | `idea/src/ide_entry/java/com/sickworm/intellij/jugg/ide/JuggAndroidTestRunConfiguration.kt`, `idea/src/ide_entry/java/com/sickworm/intellij/jugg/ide/JuggAndroidTestLineMarkerContributor.kt`, `idea/src/ide_entry/java/com/sickworm/intellij/jugg/ide/JuggAndroidTestConsoleProperties.kt`, `idea/src/ide_entry/java/com/sickworm/intellij/jugg/ide/JuggAndroidTestRerunFailedTestsAction.kt` | app `src/androidTest` gutter 与临时 RunConfig，生成 `AndroidTestRunSpec` 后进入 Jugg run pipeline；androidTest run 使用 SM Test Runner console，支持 Test Results 树、source navigation 与 rerun failed |
| Jugg Control Panel | `idea/src/main/java/com/sickworm/intellij/jugg/ide/ui/JuggToolWindowFactory.kt`, `JuggControlPanel.kt`, `JuggControlPanelController.kt`, `idea/src/ide_entry/java/com/sickworm/intellij/jugg/ide/ui/OpenJuggControlPanelAction.kt` | 项目级右侧 `Jugg Running Panel`；Overview / Logs / Settings 消费真实 snapshot，Settings 承载条件开关、按设备 compat、custom server 与测试操作；Run Configuration 的 `More options` 直接打开 Settings |
| 远程自定义命令 | `idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/RemoteCommandRunner.kt`, `idea/src/main/java/com/sickworm/intellij/jugg/ide/ui/RemoteCommandDialog.kt`, `main/src/main/java/com/sickworm/intellij/jugg/gradle/compile/RemoteUserCommand.kt`, `main/src/main/java/com/sickworm/intellij/jugg/ide/bean/JuggSettings.kt` | 使用当前选中的远程 Jugg Configuration，在固定远程项目目录执行非交互命令；独立 Run Content 支持 Stop，唯一完成标记隔离用户输出，`JuggSettings` 按远程目标保存最近 10 条命令 |
| Gradle Sync 监听 | `idea/src/ide_entry/java/com/sickworm/intellij/jugg/ide/JuggProjectManagerListener.kt`, `JuggGradleSyncListener.kt` | 项目打开时反射调用 `GradleSyncState.subscribe(Project, GradleSyncListener, Disposable)` 完成一次性订阅，Sync 事件上报 Jugg |

---

## 3. 兼容层与扩展模块

| 模块 | 目录 | 关键点 |
|------|------|--------|
| deploy_compat | `deploy_compat/*/src/main/java/com/sickworm/intellij/jugg/deploy/run/` | `IAsDeployerCompat` + 多版本实现（chipmunk/giraffe/hedgehog/iguana/meerkat/narwhal/narwhal_feature/otter/panda/quail/rabbit）；接口层通过 `JuggInstallSession` / overlay / cache entry wrapper 隔离 Android Studio deployer runtime 类型包迁移，并通过 `attachJavaDebugger()` 隔离 Java debugger API 迁移；持久化 cache 使用 Jugg 自有 snapshot，ADB transport 能力由 `IdeaDeviceAdbClient` 基于 `IDevice` 封装 |
| platform_compat | `platform_compat/base_api/src/main/java/` | IntelliJ/Android API mock，供 `main` 编译与测试 |
| Stub API 工具 | `tools/stub_api_generator/`, `deploy_compat/*.sh`, `deploy_compat/stub_api/` | 从已编译 compat JAR 的字节码引用闭包生成版本化 compile-only Stub；脚本负责创建模块、显式切换真实 JAR/Stub、生成 Stub，并通过 `verify_stub_api.sh` clean 构建两边产物后验证 AS API 调用一致性 |
| cmd_line | `cmd_line/src/main/java/com/sickworm/intellij/jugg/cmdline/` | `CmdLine`, `BuildGradleBaseCommand`, `BuildIncrementalApkCommand` |
| custom_compilers | `custom_compilers/src/main/java/com/sickworm/intellij/jugg/compiler/demo/` | SPI 自定义编译器示例 |
| jvmti_agent | `jvmti_agent/src/main/cpp/` + `jvmti_agent/src/main/java/com/sickworm/intellij/jugg/` | JVMTI native 能力（`native-lib.cpp`、`instrumenter.cc`）+ runtime instrument 修复（`ApplyChangesOverlayPolicy`、`ResourceOverlays`、`FlutterAssetRefresh` 等）+ App 内 ViewHierarchy LocalSocket Server；`FlutterAssetRefresh` 先通过 App ClassLoader 确认 Flutter 存在，再在宿主 AssetManager 被 Apply Changes 重建后将当前 overlay-aware AssetManager 推送给存活 FlutterEngine，非 Flutter App 不修改 package-context AssetManager；`DragonflyHierarchySource` 是 dump、selector、tap、inspect、layout verify 的唯一节点数据源，Dragonfly DEX JAR 及内置 Kotlin/协程等依赖经离线预处理为 Jugg 私有包并同时进入 instruments/runtime JAR，窗口枚举失败时 Best-effort 复用旧 ActivityThread/WindowManagerGlobal 根列表；由 `BootstrapApplication` 初始化 |

---

## 4. MCP 工具定位（代码入口）

- 工具注册：`main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/actions/McpToolActionRegistry.kt`  
- schema 复用：`main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/actions/McpToolSchemas.kt`  
- 协议入口：`main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/McpLocalServer.kt`  
- 校验与分发：`main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/McpRequestValidator.kt`、`main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/McpToolInvoker.kt`
- ViewHierarchy 客户端：`main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/viewhierarchy/ViewHierarchyClient.kt`

---

## 5. 高频定位建议

- 查“某能力是否已存在”：先 `98_code_map.md`，再对应目录搜索类名。  
- 查“编译为何回退”：从 `JuggCompilerHelper` -> `preprocessIncrementalCompile`。
- 查“部署失败恢复”：从 `JuggDeployerHelper.deploy` -> `DeployStateRecover.recoverDeployState`。
- 查“系统应用能否用 Jugg / adb install 安装”：`03_deploy_system_app.md`。默认 installer 不负责系统分区，自定义 APK 安装脚本可接管普通 App 的 install/reinstall。
- 查“APK 改写后为什么签名失败 / 如何换成服务器签名”：`03_deploy_system_app.md` §4.2、`05_utilities.md`。自定义 APK 签名脚本替换 `ApkFileModifier` 的默认 keystore 签名，失败不回退本地签名。
- 查“MCP 参数规则”：从 tool action 的 `inputSchema` 和 `execute` 实现确认。

---

## 6. 维护约定

- 新增入口类/关键工具后，优先同步本表。  
- 路径或类名变更时，同步 `99_index.md` 的专题文档目录描述。  
- 若本表滞后，回答中必须明确“以代码为准”。
