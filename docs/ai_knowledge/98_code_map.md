# 代码路径速查表（Code Map）

> 最后核对：2026-07-30
> 口径：生产代码目录（不含 `build/` 与 `src/test/`）  
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 核心层（`main/src/main/java/com/sickworm/intellij/jugg`）

| 领域 | 关键类/接口 | 目录 | 职责/说明 | 状态 | 最近同步 |
|------|-------------|------|-----------|------|----------|
| 编译总控 | `JuggCompiler`, `BaseCompiler`, `CompileTask` | `compiler/core` | 增量编译主流程、阶段顺序与循环重编译 | 稳定 | 2025-01-20 |
| 源码编译 | `SourceCompiler`, `JuggAptCompiler`, `IJuggAptProcessor`, `JavaCompiler`, `KotlinCompiler`, `KotlinCompilerInvoker`, `KotlinComplementaryFilesCache`, `K2JVMCompilerIsolate`, `DexCompiler`, `DexFileMaker` | `compiler/source`, `compiler/source/apt`, `compiler/source/kotlin` | Java/Kotlin 编译与 DEX 生成；D8 优先隔离加载项目 AGP 的 R8 code source，加载不兼容时回退内置版本；typed common sources 支持 Compose generated expect/actual；普通 KMP 按需读取 complementary cache，在 Android owner invocation 中补齐输入，并用项目 compiler tracker 在成功后原地刷新双向 edge | 稳定 | 2026-07-30 |
| 资源编译 | `ComposeResourceCompiler`, `ComposeResourceGeneratorBridge`, `ComposeResourceScanner`, `ComposeValueResourceConverter`, `ResourceOverlayCompiler`, `ResourceCompiler`, `ArscCompiler`, `AssetOverlayCompiler`, `Aapt2DaemonInvoker` | `compiler/compose`, `compiler/overlay`, `aapt2` | Compose resource 准备、unsupported fail-closed、generated diagnostic 回映射、官方 accessor generator bridge、通过 `ModuleBuildPathInfo.composeResourceGeneratedSourcePath` 回写 generated Kotlin 以支持 IDE 索引、现代 `Asset` overlay、显式 `ClasspathResource` 类型的 legacy APK 根目录 classpath resource overlay，以及 Android res/manifest 的 aapt2 link；Compose resource 不进入 AAPT2 | 稳定 | 2026-08-03 |
| DataBinding | `DataBindingArgsManager`, `DataBindingGenBaseClassesCompiler`, `DataBindingSetterStoreCache`, `DataBindingGenMapperCompiler` | `compiler/databinding` | DataBinding/ViewBinding 增量处理；GenMapper 单次 APT/KAPT 输出 current-module store并维护 merged setter store cache | 稳定 | 2026-07-22 |
| Manifest | `AndroidManifestCompiler`, `AndroidManifestMerger`, `ManifestDiffer` | `compiler/manifest` | 清单差异合并；混淆映射由 `compiler/obfuscation` 承载 | 稳定 | 2026-05-23 |
| 混淆映射 | `ClassMinifyCompiler`, `DexMinifyCompiler`, `ClassObfuscator`, `R8MappingReader`, `R8UsageReader` | `compiler/obfuscation` | release 混淆映射一致性、`usage.txt` 删除成员读取与 `_jugg_fix` compatibility stub 重写 | 稳定 | 2026-04-01 |
| 自定义编译器 | `CustomCompilerManager`, `ICompilerCreator`, `CompileUiHandler` | `compiler/custom` | SPI 扩展、远端下载 jar、动态装载；编译交互抽象（供 IDE/CLI） | 稳定 | 2025-01-20 |
| 常量引用分析 | `ConstRefEngine`, `ConstRefAnalyzer`, `ConstRefChangeTracker`, `ConstRefImpactResolver`, `ConstRefSessionCache` | `compiler/constref` | 编译期常量定义/引用分析；按”真实变更常量 key”定位受影响源码；DB 主导+会话缓存；repo/worktree 共享缓存与过期清理 | 稳定 | 2026-03-04 |
| 部署文件管理 | `JuggDeployer`, `DeployFileManager`, `DeployFileStateTracker`, `DeployDataPlanner`, `CompileEffectAnalyzer`, `DeployHistoryManager`, `ClassFileLookupHelper` | `deploy/core` | 部署调度、文件准备；`DeployFileManager` 作为 facade，状态跟踪/部署数据计算/编译影响分析已解耦 | 稳定 | 2026-02-27 |
| 影响分析 | `DeployDataGenerator`, `DeployDataDatabase`, `IncrementalDeployDataDatabase`, `ClassNodeComparator`, `InlineMethodDetector` | `deploy/data` | 类结构变更传播和部署数据生成；双层数据库与引用索引；内联方法影响检测 | 稳定 | 2026-02-01 |
| 部署数据模型 | `JuggDeployData`, `DeployItem`, `LaunchResult` | `deploy/run` | 下发设备的部署数据结构；`targetApkPaths` 和 `filterForApks()` 支持多 APK 归属分流 | 稳定 | 2026-05-08 |
| AndroidTest 运行模型 | `AndroidTestRunSpec`, `TestFilter`, `InstrumentCommandBuilder`, `AndroidTestTargetResolver`, `LibraryTestApkBackfillPlanner`, `LibraryTestApkBuildHistory`, `InstrumentationOutputParser`, `InstrumentationConsoleRenderer`, `InstrumentationSmRunnerBridge`, `AndroidTestResultModel` | `deploy/instrument` | androidTest instrumentation 参数、sourcePath target 解析、library Test APK 懒加载 plan 与跨仓库 build history、`am instrument` 命令构造、输出解析、文本 console 渲染、SM Test Runner service message 映射与按 method 归档 logcat | 开发中 | 2026-05-17 |
| 项目模型 | `JuggProjectInfo`, `ModuleInfo`, `ComposeResourceInfo`, `ComposeResourceSupportStatus`, `ComposeResourceDirectory`, `ModuleBuildPathInfo`, `JuggPathManager`, `JuggGlobalPathManager`, `ModuleApkBelongs` | `project/data`, `project` | 根快照引用项目 AGP R8 分发包；模块快照保存路径、依赖、Kotlin common roots/fragment graph、Compose support 状态及资源根；项目信息读取/序列化；项目级路径与 `~/.jugg` 全局路径统一管理；模块到 APK 归属封装 | 稳定 | 2026-08-05 |
| 依赖变更 | `DependencyChangeManagerByGradle`, `DependencyChangeManagerBySync` | `project/dependency` | 依赖变更检测策略 | 稳定 | 2025-01-20 |
| Gradle 信息读取 | `GradleProjectInfoReaderManager`, `GradleProjectInfoReader`, `GradleVariantCollector`, `ProjectInfoSerializerInGradle`, `GradleDependencyDiffer` | `gradle/script` | 通过 Gradle 反射读取模块信息与 Android plugin classloader 中 D8 的 code source；legacy variant API 无结果时使用 Android Components variant 名称回退；从 Android Kotlin task 的 commonSourceSet 与 `multiplatformStructure` 收集 authoritative common roots、fragment sources/refines/default；严格读取 Compose resource task metadata 并同步到生成 init script | 稳定 | 2026-08-05 |
| Gradle 编译客户端 | `LocalGradleCompileClient`, `RemoteGradleCompileClient`, `GradleWrapperRepairer`, `ApkLookupPlanner`, `CmdExecutor`, `ProcessOutputReader` | `gradle/compile` | 本地/远端 Gradle 构建执行；Windows 命令输出按行严格校验 UTF-8，失败回退 GBK；`GradleWrapperRepairer` 在已有 wrapper properties 时补齐缺失 wrapper 启动文件，并在 Windows 远程编译同步前将 Unix `gradlew` 的 CRLF 转为 LF；AndroidTest 下区分 required app/app-test APK 与 optional history library Test APK 收集 | 稳定 | 2026-08-05 |
| MCP 协议 | `McpLocalServer`, `McpBaseInvoker`, `McpToolInvoker`, `McpRequestValidator` | `ai/mcp/` | MCP HTTP + JSON-RPC 处理 | 稳定 | 2026-04-26 |
| MCP 工具 | `McpToolActionRegistry`, `CompileJobManager`, `GetCompileStatusMcpToolAction`, `LayoutDumpHelper`, `LayoutHtmlConverter`, `WaitLogsMcpToolAction`, `CrashDetector`, `LastDeployTimestampRegistry` | `ai/mcp/actions`, `ai/mcp/util` | 工具注册、异步编译状态管理；`LayoutDumpHelper` 封装 layout_dump 核心逻辑（设备解析、px→dp、公开 HTML 输出、内部 JSON 文件），`LayoutHtmlConverter` 将 JSON 视图树转为精简 HTML（含虚拟节点裁剪）；`WaitLogsMcpToolAction` 阻塞式等待 App 日志（marker/crash/timeout 判停）；`CrashDetector` 复用 crash 信号识别；`LastDeployTimestampRegistry` 记录 deploy/restart 时刻作为日志起点 | 稳定 | 2026-08-02 |
| AI 技能安装 | `JuggSkillInstaller`, `JuggHookInstaller`, `CcSwitchCommonConfigGuideExporter`, `PythonRuntimeResolver`, `CodexPermissionRuleInstaller`, `JuggCliAutoUpdater`, `ClientSetupDocExporter`, `IAgentInstaller`, `agents/*` | `ai/skills` | 安装/更新 `jugg-android-dev-loop` skill、CLI 与 hooks（资源来源 `docs/skills/*.zip`）；CLI/hooks 安装先校验 Python 3.7+（`python3` 优先、`python` 回退），避免写入不可用 hook；成功安装 Claude hooks 后，安装结果关闭再异步检查桌面版 / `cc-switch-cli` 共用的配置目录，用户确认后仅导出 Jugg Claude hooks 至 `~/.jugg/cc-switch` 并打开文件，不读写 CC Switch provider 或数据库；Windows CLI 安装由 `JuggSkillInstaller` 写入用户级 PATH，macOS/Linux 创建 `~/.local/bin/jugg` symlink；Codex skill 安装时同步写入 `rules/default.rules` 的 Jugg CLI `prefix_rule`，并导出 `agent_setup.md`；`IAgentInstaller` 统一描述各 agent 的 skill/hook/rules 安装目标，Installer 仅保留调度 | 稳定 | 2026-07-25 |
| MCP ViewHierarchy 通信 | `ViewHierarchyClient`, `ViewHierarchyRequest`, `ViewHierarchyResponse` | `ai/mcp/viewhierarchy` | `layout-dump` / `tap` 元素模式 / `view-inspect` 的 App 内 LocalSocket 通道（Server-only，无 uiautomator 回退） | 稳定 | 2026-03-09 |
| 工具模块 | `Aapt2DaemonInvoker`, `ApkFileModifier`, `GitManager`, `JuggLogger`, `JuggServer`, `JuggEventLocalStore`, `IssueReportBundleBuilder`, `IssueReportUploader`, `ExpiredArtifactCleaner`, `PlatformApi` | `aapt2/`, `apk/`, `git/`, `logger/`, `server/`, `diagnostics/`, `project/`, `platform/` | 通用基础能力；report 事件写入 `~/.jugg/action.db`，问题诊断使用白名单包和独立单目标上传；MCP 与问题诊断临时产物分别保留 30 天和 7 天 | 稳定 | 2026-08-02 |

---

## 2. IDE 层（`idea/src/main` + `idea/src/ide_entry`）

| 入口 | 文件路径 | 说明 |
|------|----------|------|
| IDE 总管理器 | `idea/src/main/java/com/sickworm/intellij/jugg/JuggManager.kt` | 初始化、同步事件、MCP runtime 装配 |
| 运行任务编排 | `idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/JuggRunningTask.kt`, `idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/JuggDebugSessionManager.kt` | 编译与部署串联主流程；Debug executor 成功部署后由 `JuggDebugSessionManager` 做单设备 Java debugger attach |
| 编译入口 | `idea/src/main/java/com/sickworm/intellij/jugg/compiler/JuggCompileHelper.kt` | 增量/Gradle 回退判定；AndroidTest Gradle build 读取 library Test APK build history 并注入回放任务 |
| 部署入口 | `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployerHelper.kt`, `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/LaunchContextFactory.kt`, `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/flow/DeployStateRecover.kt`, `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/flow/DeployRetryHandler.kt`, `idea/src/main/java/com/sickworm/intellij/jugg/deploy/direct/DirectOverlaySwapTransport.kt`, `main/src/main/java/com/sickworm/intellij/jugg/deploy/direct/DirectOverlayWriter.kt`, `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeploymentCacheStore.kt`, `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/instrument/LibraryTestApkBackfillHelper.kt` | 部署策略、recover、重试、agent 协调；`LaunchContextFactory` 统一创建 deviceAdb、install session、installer metadata 与 Direct Overlay lifecycle facts；`DeployStateRecover` 负责 `recoverDeployState` / `tryDryDeploy`，`DeployRetryHandler` 负责 `tryRetry`，均经 `IJuggDeployHelperRunHost` 回调 `JuggDeployerHelper`；`deploy` 分派为 `deployInstall` / `deployChanges`；Direct Overlay 统一放在 `deploy.direct` 包，Writer/StateChecker 等不依赖 IDE 的实现下沉到 `main`，deployment cache 经 `IJuggDeploymentService` 注入，磁盘 cache 由 `JuggDeploymentCacheStore` 保存 Jugg 自有 snapshot；sourcePath 命中缺失 self-targeting library Test APK 时做单模块懒加载补齐，并在成功后记录 build history |
| 核心部署器 | `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployer.kt` | install/codeSwap/fullSwap |
| 部署状态 | `idea/src/main/java/com/sickworm/intellij/jugg/deploy/DeployStateManager.kt` | 设备状态与部署可行性 |
| 插件加载 | `idea/src/ide_entry/java/com/sickworm/intellij/jugg/loader/JuggLoader.kt` | 类加载隔离与桥接；`com.sickworm.intellij.jugg.ide` 保持稳定，热更新 UI 仅通过基础类型跨边界 |
| 初始化器 | `idea/src/ide_entry/java/com/sickworm/intellij/jugg/loader/JuggInitializer.kt` | 插件生命周期入口 |
| Control Panel 桥接 | `idea/src/ide_entry/java/com/sickworm/intellij/jugg/ide/JuggControlPanelHost.kt`, `idea/src/ide_entry/java/com/sickworm/intellij/jugg/ide/ui/OpenJuggControlPanelAction.kt`, `main/src/main/java/com/sickworm/intellij/jugg/ide/controlpanel/`, `idea/src/main/java/com/sickworm/intellij/jugg/ide/ui/JuggControlPanelController.kt` | 稳定 Host/Action 仅跨边界传递 JComponent；Controller 持有 Model/Panel 并由 Manager clear；main Model 统一 facts/events |
| 运行配置 | `idea/src/ide_entry/java/com/sickworm/intellij/jugg/ide/JuggRunConfiguration.kt`, `idea/src/main/java/com/sickworm/intellij/jugg/ide/JuggDebugProgramRunner.kt` | run config 定义；`JuggDebugProgramRunner` 接管 Jugg + Debug executor，让 Debug 按钮可用 |
| androidTest 运行入口 | `idea/src/ide_entry/java/com/sickworm/intellij/jugg/ide/JuggAndroidTestRunConfiguration.kt`, `idea/src/ide_entry/java/com/sickworm/intellij/jugg/ide/JuggAndroidTestLineMarkerContributor.kt`, `idea/src/ide_entry/java/com/sickworm/intellij/jugg/ide/JuggAndroidTestConsoleProperties.kt`, `idea/src/ide_entry/java/com/sickworm/intellij/jugg/ide/JuggAndroidTestRerunFailedTestsAction.kt` | app `src/androidTest` gutter 与临时 RunConfig，生成 `AndroidTestRunSpec` 后进入 Jugg run pipeline；androidTest run 使用 SM Test Runner console，支持 Test Results 树、source navigation 与 rerun failed |
| More Options 工具菜单 | `idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/MoreOptionsManager.kt` | More options 下拉分组与工具项（含 MCP/skill 安装入口） |
| Jugg Control Panel | `idea/src/main/java/com/sickworm/intellij/jugg/ide/ui/JuggToolWindowFactory.kt`, `JuggControlPanel.kt`, `JuggControlPanelController.kt`, `idea/src/ide_entry/java/com/sickworm/intellij/jugg/ide/ui/OpenJuggControlPanelAction.kt` | 项目级右侧 `Jugg Running Pannel`；Overview / Logs / Settings 消费真实 snapshot，Logs 展示结构化核心事件；Mock Model 可切换但复用同一订阅渲染路径；Run Configuration 的 `More options` 直接打开 Settings |
| Gradle Sync 监听 | `idea/src/ide_entry/java/com/sickworm/intellij/jugg/ide/JuggGradleSyncListener.kt` | Sync 事件上报 Jugg |

---

## 3. 兼容层与扩展模块

| 模块 | 目录 | 关键点 |
|------|------|--------|
| deploy_compat | `deploy_compat/*/src/main/java/com/sickworm/intellij/jugg/deploy/run/` | `IAsDeployerCompat` + 多版本实现（chipmunk/giraffe/hedgehog/iguana/meerkat/narwhal/narwhal_feature/otter/panda/quail）；接口层通过 `JuggInstallSession` / overlay / cache entry wrapper 隔离 Android Studio deployer runtime 类型包迁移，并通过 `attachJavaDebugger()` 隔离 Java debugger API 迁移；持久化 cache 使用 Jugg 自有 snapshot，ADB transport 能力由 `IdeaDeviceAdbClient` 基于 `IDevice` 封装 |
| platform_compat | `platform_compat/base_api/src/main/java/` | IntelliJ/Android API mock，供 `main` 编译与测试 |
| Stub API 工具 | `tools/stub_api_generator/`, `deploy_compat/*.sh`, `deploy_compat/stub_api/` | 从已编译 compat JAR 的字节码引用闭包生成版本化 compile-only Stub；脚本负责创建模块、显式切换真实 JAR/Stub、生成 Stub，并通过 `verify_stub_api.sh` clean 构建两边产物后验证 AS API 调用一致性 |
| cmd_line | `cmd_line/src/main/java/com/sickworm/intellij/jugg/cmdline/` | `CmdLine`, `BuildGradleBaseCommand`, `BuildIncrementalApkCommand` |
| custom_compilers | `custom_compilers/src/main/java/com/sickworm/intellij/jugg/compiler/demo/` | SPI 自定义编译器示例 |
| jvmti_agent | `jvmti_agent/src/main/cpp/` + `jvmti_agent/src/main/java/com/sickworm/intellij/jugg/` | JVMTI native 能力（`native-lib.cpp`、`instrumenter.cc`）+ runtime instrument 修复（`ApplyChangesOverlayPolicy` 等）+ App 内 ViewHierarchy LocalSocket Server；`DragonflyHierarchySource` 是 dump、selector、tap、inspect、layout verify 的唯一节点数据源，窗口枚举失败时 Best-effort 复用旧 ActivityThread/WindowManagerGlobal 根列表，Android 节点保留原始 View，Compose 节点复用同一 snapshot；由 `BootstrapApplication` 初始化 |

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
- 查“编译为何回退”：从 `JuggCompileHelper` -> `preprocessIncrementalCompile`。  
- 查“部署失败恢复”：从 `JuggDeployerHelper.deploy` -> `DeployStateRecover.recoverDeployState`。  
- 查“MCP 参数规则”：从 tool action 的 `inputSchema` 和 `execute` 实现确认。

---

## 6. 维护约定

- 新增入口类/关键工具后，优先同步本表。  
- 路径或类名变更时，同步 `99_index.md` 的专题文档目录描述。  
- 若本表滞后，回答中必须明确“以代码为准”。
