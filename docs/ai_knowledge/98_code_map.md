# 代码路径速查表（Code Map）

> 最后核对：2026-08-18
> 口径：生产代码目录（不含 `build/` 与 `src/test/`）  
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 核心层（`main/src/main/java/com/sickworm/intellij/jugg`）

| 领域 | 关键类/接口 | 目录 | 职责/说明 | 状态 | 最近同步 |
|------|-------------|------|-----------|------|----------|
| 编译总控 | `JuggCompiler`, `BaseCompiler`, `CompileTask` | `compiler/core` | 增量编译主流程、阶段顺序与循环重编译 | 稳定 | 2025-01-20 |
| 源码编译 | `SourceCompiler`, `JuggAptCompiler`, `IJuggAptProcessor`, `JavaCompiler`, `KotlinCompiler`, `KotlinCompilerInvoker`, `KotlinComplementaryFilesCache`, `K2JVMCompilerIsolate`, `DexCompiler`, `DexFileMaker` | `compiler/source`, `compiler/source/apt`, `compiler/source/kotlin` | Java/Kotlin 编译与 DEX 生成；D8 优先隔离加载项目 AGP 的 R8 code source，加载不兼容时回退内置版本；typed common sources 支持 Compose generated expect/actual；普通 KMP 按需读取 complementary cache、传递 Gradle fragment graph，并用 source-to-output cache 隔离 Kotlin 1.9 dirty baseline class | 稳定 | 2026-08-05 |
| 资源编译 | `ComposeResourceCompiler`, `ComposeResourceGeneratorBridge`, `ComposeResourceScanner`, `ComposeValueResourceConverter`, `ResourceOverlayCompiler`, `ResourceCompiler`, `ArscCompiler`, `AssetOverlayCompiler`, `Aapt2DaemonInvoker` | `compiler/compose`, `compiler/overlay`, `aapt2` | Compose resource 准备、unsupported fail-closed、generated diagnostic 回映射、官方 accessor generator bridge、通过 `ModuleBuildPathInfo.composeResourceGeneratedSourcePath` 回写 generated Kotlin 以支持 IDE 索引、现代 `Asset` overlay、显式 `ClasspathResource` 类型的 legacy APK 根目录 classpath resource overlay，以及 Android res/manifest 的 aapt2 link；Compose resource 不进入 AAPT2 | 稳定 | 2026-08-03 |
| DataBinding | `DataBindingArgsManager`, `DataBindingGenBaseClassesCompiler`, `DataBindingSetterStoreCache`, `DataBindingGenMapperCompiler` | `compiler/databinding` | DataBinding/ViewBinding 增量处理；GenMapper 单次 APT/KAPT 输出 current-module store并维护 merged setter store cache | 稳定 | 2026-07-22 |
| Manifest | `AndroidManifestCompiler`, `AndroidManifestMerger`, `ManifestDiffer` | `compiler/manifest` | 清单差异合并；混淆映射由 `compiler/obfuscation` 承载 | 稳定 | 2026-05-23 |
| 混淆映射 | `ClassMinifyCompiler`, `DexMinifyCompiler`, `ClassObfuscator`, `R8MappingReader`, `R8UsageReader` | `compiler/obfuscation` | release 混淆映射一致性、`usage.txt` 删除成员读取与 `_jugg_fix` compatibility stub 重写 | 稳定 | 2026-04-01 |
| 自定义编译器 | `CustomCompilerManager`, `ICompilerCreator`, `CompileUiHandler` | `compiler/custom` | SPI 扩展、远端下载 jar、动态装载；配置变化时释放旧 compiler 子作用域并关闭旧 `URLClassLoader`，Runtime dispose 时统一释放；编译交互抽象供 IDE/CLI 使用 | 稳定 | 2026-07-20 |
| 常量引用分析 | `ConstRefEngine`, `ConstRefAnalyzer`, `ConstRefChangeTracker`, `ConstRefImpactResolver`, `ConstRefSessionCache` | `compiler/constref` | 编译期常量定义/引用分析；按”真实变更常量 key”定位受影响源码；DB 主导+会话缓存；repo/worktree 共享缓存与过期清理 | 稳定 | 2026-03-04 |
| 部署文件管理 | `JuggDeployer`, `DeployFileManager`, `DeployFileStateTracker`, `DeployDataPlanner`, `CompileEffectAnalyzer`, `DeployHistoryManager`, `ClassFileLookupHelper` | `deploy/core` | 部署调度、文件准备；`DeployFileManager` 作为 facade，状态跟踪/部署数据计算/编译影响分析已解耦 | 稳定 | 2026-02-27 |
| 影响分析 | `DeployDataGenerator`, `DeployDataDatabase`, `IncrementalDeployDataDatabase`, `ClassNodeComparator`, `InlineMethodDetector` | `deploy/data` | 类结构变更传播和部署数据生成；双层数据库与引用索引；内联方法影响检测 | 稳定 | 2026-02-01 |
| 部署编排与数据模型 | `JuggDeployOrchestrator`, `JuggDeployerHelper`, `JuggDeployTask`, `JuggDeployer`, `DeployStateRecover`, `DeployRetryHandler`, `JuggDeployData`, `LaunchResult` | `deploy/run`, `deploy/direct` | 单设备 lifecycle、recover、retry、Apply Changes 与 Direct Overlay 已共享；IDEA 与 standalone 均通过 Host 环境接入同一编排 | 开发中 | 2026-08-08 |
| AndroidTest 运行模型 | `AndroidTestRunSpec`, `TestFilter`, `InstrumentCommandBuilder`, `AndroidTestTargetResolver`, `LibraryTestApkBackfillPlanner`, `LibraryTestApkBuildHistory`, `InstrumentationOutputParser`, `InstrumentationConsoleRenderer`, `InstrumentationSmRunnerBridge`, `AndroidTestResultModel` | `deploy/instrument` | androidTest instrumentation 参数、sourcePath target 解析、library Test APK 懒加载 plan 与跨仓库 build history、`am instrument` 命令构造、输出解析、文本 console 渲染、SM Test Runner service message 映射与按 method 归档 logcat | 开发中 | 2026-05-17 |
| 项目信息 | `JuggProjectInfo`, `ModuleInfo`, `ComposeResourceInfo`, `ComposeResourceSupportStatus`, `ComposeResourceDirectory`, `ModuleBuildPathInfo`, `IProjectModelSource`, `GradleProjectModelSource`, `ProjectInfoSerializer`, `JuggProjectInfoMerger`, `ModulePathMergePolicy` | `project/info` | 根快照引用项目 AGP R8 分发包；模块快照保存路径、依赖、Kotlin common roots/fragment graph、Compose support 状态及资源根；提供 IDEA/Gradle-only source 边界、序列化、合并和模块身份策略 | 开发中 | 2026-08-05 |
| 项目文件变化 | `FileChangeManager`, `ChangedFile`, `FileChangesHandler`, `IFileChangeMonitor`, `WatchServiceFileChangeMonitor`, `GitFileChangesDetector` | `project/change` | 共享 changed/delete 处理、build-file 状态、Git reconcile 与 pending barrier；standalone 使用 WatchService，IDEA monitor 位于 `idea` 同名包 | 开发中 | 2026-07-17 |
| 项目运行基础设施 | `JuggPathManager`, `JuggGlobalPathManager`, `JuggResourceManager`, `JuggSettings`, `JsonRuntimeSettingsRepository`, `ProjectCustomConfigManager`, `CliRunConfiguration`, `CliRunConfigurationStore`, `LastCompileProjectRegistry`, `HotUpdateLoadManifest` | `ide/bean`, `project/runtime` | 项目级与全局路径；`JuggResourceManager` 在固定全局写锁内按 metadata 原子释放版本化资源并校验 SHA-256；`JuggSettings` 保留在原 `ide/bean` 包并自动加载 `~/.jugg/settings.json` effective settings；`ProjectCustomConfigManager` 统一应用 server/local custom config；`CliRunConfiguration` 以独立 UUID JSON + 当前指针共享 IDEA/standalone build profile，并由 Gradle project info 确定性推断默认配置；跨项目运行状态和 hot update 完整加载快照共享。IDEA 旧设置转换与 run configuration 同步位于 `idea/.../project/runtime` | 开发中 | 2026-08-05 |
| 编译上下文 | `BaseCompileContext`, `CompileContextManager`, `JuggCompilerHelper`, `ICompileEnvironmentSource`, `IdeaProjectModelSource`, `IdeaCompileEnvironmentSource`, `StandaloneCompileEnvironmentSource` | `compiler`, `compiler/context`, `idea/.../compiler/context`, `cmd_line/.../standalone` | Compile Context 与增量/Gradle fallback 核心位于 main；IDEA/standalone 分别提供项目模型、环境与交互适配 | 开发中 | 2026-08-08 |
| 任务与执行锁 | `TaskRunnerManager`, `RuntimeTaskCoordinator`, `ExecutionLockManager.kt`, `RuntimeOwnerStore` | `project/runtime` | TaskRunner 是唯一公开锁入口；`RuntimeTaskCoordinator` 串行同 Runtime 的独立逻辑 owner，并让 TaskRunner 提交的跨线程子任务自动继承父 owner、共享重入。Project Runtime lease 只互斥不同 Runtime；`status` 依次非阻塞尝试 owner 与 lease。无父 owner 的非阻塞 Host task 保留并发语义，全局写继续使用固定 `~/.jugg/locks/global.lock` | 开发中 | 2026-08-10 |
| 项目级部署缓存 | `JuggDeploymentCacheStore`, `JuggDeploymentService` | `deploy/cache`, `deploy/run` | `<projectDir>/build/jugg/deploy_cache` 下的磁盘 snapshot + Runtime 本地 memoryCache；项目锁内刷新并原子替换，Service 内部按 owner 或内容 generation 变化失效并重载 | 开发中 | 2026-08-07 |
| 部署状态 | `DeployStateManager`, `IDeployStateManager`, `IHostDeployStateResolver` | `deploy` | 共享部署可行性、build-file 状态和 pending file-processing barrier；Host 设备状态通过 resolver 注入 | 开发中 | 2026-07-14 |
| 依赖变更 | `DependencyChangeManagerByGradle`, `DependencyChangeManagerBySync`, `GradleProjectInfoLocalFetchManager` | `project/dependency` | 依赖变更检测与确认结果应用；Host 确认复用 CompileUiHandler，manager 不依赖 PlatformApi dialog，本地 Gradle project info 调度继续通过共享 TaskRunner 保留项目锁 | 开发中 | 2026-07-17 |
| Gradle 信息读取 | `GradleProjectInfoReaderManager`, `GradleProjectInfoReader`, `GradleVariantCollector`, `ProjectInfoSerializerInGradle`, `GradleDependencyDiffer` | `gradle/script` | 通过 Gradle 反射读取模块信息与 Android plugin classloader 中 D8 的 code source；legacy variant API 无结果时使用 Android Components variant 名称回退；从 Android Kotlin task 的 commonSourceSet 与 `multiplatformStructure` 收集 authoritative common roots、fragment sources/refines/default；严格读取 Compose resource task metadata 并同步到生成 init script | 稳定 | 2026-08-05 |
| Gradle 编译客户端 | `LocalGradleCompileClient`, `RemoteGradleCompileClient`, `CopyGeneratedSourceHelper`, `GradleWrapperRepairer`, `ApkLookupPlanner`, `CmdExecutor`, `ProcessOutputReader` | `gradle/compile` | 本地/远端 Gradle 构建执行；远端产物中的 generated/custom sync 文件回写本地；`RemoteGradleCompileClient` 也提供不进入 Run task 流程的单条非交互远程命令执行；Windows 命令输出按行严格校验 UTF-8，失败回退 GBK；`GradleWrapperRepairer` 在已有 wrapper properties 时补齐缺失 wrapper 启动文件，并在 Windows 远程编译同步前将 Unix `gradlew` 的 CRLF 转为 LF；AndroidTest 下区分 required app/app-test APK 与 optional history library Test APK 收集 | 稳定 | 2026-08-18 |
| MCP 协议 | `McpLocalServer`, `McpBaseInvoker`, `McpToolInvoker`, `McpRequestValidator`, `McpToolRegistry`, `IMcpRuntime` | `ai/mcp/` | MCP HTTP + JSON-RPC 处理；Runtime 以非空 host-neutral `projectDir` 显式暴露项目能力，工具注册表统一约束 capability、`tools/list` 与 action 分发，HTTP 请求到达回调可刷新 standalone idle timer | 稳定 | 2026-08-05 |
| MCP 工具 | `McpToolActionRegistry`, `CompileJobManager`, `GetCompileStatusMcpToolAction`, `LayoutDumpHelper`, `LayoutHtmlConverter`, `WaitLogsMcpToolAction`, `CrashDetector`, `LastDeployTimestampRegistry` | `ai/mcp/actions`, `ai/mcp/util` | 工具注册、异步编译状态管理；`LayoutDumpHelper` 封装 layout_dump 核心逻辑（设备解析、px→dp、公开 HTML 输出、内部 JSON 文件），`LayoutHtmlConverter` 将 JSON 视图树转为精简 HTML（含虚拟节点裁剪）；`WaitLogsMcpToolAction` 阻塞式等待 App 日志（marker/crash/timeout 判停）；`CrashDetector` 复用 crash 信号识别；`LastDeployTimestampRegistry` 记录 deploy/restart 时刻作为日志起点 | 稳定 | 2026-08-02 |
| Standalone 项目运行域 | `StandaloneProjectServices`, `StandaloneConfigurationRunner`, `StandaloneProjectInitializer`, `StandaloneDeployTargetManager` | `cmd_line/.../standalone` | 恢复项目上下文，串联 WatchService/Git、共享增量/Gradle 编译、共享部署、MCP job 取消/轮询和真实 adb 设备边界；`cmdline-distribution.gradle` 从实际 runtimeClasspath 生成 SHA-256 内容寻址的跨平台 Bundle，并执行 Java 11 发行门禁 | 开发中 | 2026-08-09 |
| Standalone 发行与启动 | `StandaloneRuntimeInstaller`, `StandaloneActivationManager`, `StandaloneBootstrap` | `cmd_line/.../standalone`, `standalone_bootstrap/` | 校验 JDK/Python/Bundle，原子安装共享 JAR、版本化 tooling/CLI 与双 manifest；固定 Java 11 bootstrap 按 active manifest 有序加载 Runtime，记录 ready/last-known-good，并在首次链接失败时单次回退 | 稳定 | 2026-08-09 |
| AI 技能安装 | `JuggSkillInstaller`, `JuggHookInstaller`, `CcSwitchCommonConfigGuideExporter`, `PythonRuntimeResolver`, `CodexPermissionRuleInstaller`, `JuggCliAutoUpdater`, `ClientSetupDocExporter`, `IAgentInstaller`, `agents/*` | `ai/skills` | 安装/更新 `jugg-android-dev-loop` skill、CLI 与 hooks（资源来源 `docs/skills/*.zip`）；CLI/hooks 安装先校验 Python 3.7+（`python3` 优先、`python` 回退），避免写入不可用 hook；成功安装 Claude hooks 后，安装结果关闭再异步检查桌面版 / `cc-switch-cli` 共用的配置目录，用户确认后仅导出 Jugg Claude hooks 至 `~/.jugg/cc-switch` 并打开文件，不读写 CC Switch provider 或数据库；Windows CLI 安装由 `JuggSkillInstaller` 写入用户级 PATH，macOS/Linux 创建 `~/.local/bin/jugg` symlink；Codex skill 安装时同步写入 `rules/default.rules` 的 Jugg CLI `prefix_rule`，并导出 `agent_setup.md`；`IAgentInstaller` 统一描述各 agent 的 skill/hook/rules 安装目标，Installer 仅保留调度 | 稳定 | 2026-07-25 |
| MCP ViewHierarchy 通信 | `ViewHierarchyClient`, `ViewHierarchyRequest`, `ViewHierarchyResponse` | `ai/mcp/viewhierarchy` | `layout-dump` / `tap` 元素模式 / `view-inspect` 的 App 内 LocalSocket 通道（Server-only，无 uiautomator 回退） | 稳定 | 2026-03-09 |
| Runtime / hot update | `RuntimeInfo`, `RuntimeOwnerStore`, `JuggHotUpdateManager`, `IdeaHotUpdateCoordinator`, `JuggHotUpdateBootstrap` | `project/runtime`, `server`, `idea/.../server`, `idea/.../loader` | Host-neutral runtime type/version/host/build，由各 Host 的 `IPlatformApi` 单点提供；持久化 last owner；共享 hot update 下载校验与发布；IDEA 检查安装编排；Loader 启动前无锁只读 manifest | 开发中 | 2026-08-05 |
| 工具模块 | `Aapt2DaemonInvoker`, `ApkFileModifier`, `GitManager`, `JuggLogger`, `JuggServer`, `JuggEventLocalStore`, `IssueReportBundleBuilder`, `IssueReportUploader`, `ExpiredArtifactCleaner`, `PlatformApi` | `aapt2/`, `apk/`, `git/`, `logger/`, `server/`, `diagnostics/`, `project/`, `platform/` | 通用基础能力；report 事件写入 `~/.jugg/action.db`，问题诊断使用白名单包和独立单目标上传；MCP 与问题诊断临时产物分别保留 30 天和 7 天 | 稳定 | 2026-08-02 |

---

## 2. IDE 层（`idea/src/main` + `idea/src/ide_entry`）

| 入口 | 文件路径 | 说明 |
|------|----------|------|
| IDEA 项目协调 | `idea/src/main/java/com/sickworm/intellij/jugg/JuggManager.kt` | 接收 Sync/Run/UI/MCP 事件，注入 IDEA runtime metadata，管理配置刷新、历史恢复、Compile Context 关联、IDEA monitor 接线、Control Panel 和 dispose；通过 `IdeaCliRunConfigurationManager` 导入/监听 Jugg 配置并在 Gradle 成功后回写共享 build profile；文件变化处理已委托共享 `FileChangeManager` |
| IDEA 文件与编译交互 Host | `idea/src/main/java/com/sickworm/intellij/jugg/project/change/IdeaFileChangeMonitor.kt`, `idea/src/main/java/com/sickworm/intellij/jugg/compiler/JuggCompileUiHandler.kt` | 将 VFS 事件适配到共享领域契约，并提供 dependency dialog 等编译交互 |
| IDEA Task adapter | `idea/src/main/java/com/sickworm/intellij/jugg/runtime/HostTaskExecutor.kt` | 为共享 `TaskRunnerManager` 提供 `Task.Backgroundable`、ProgressIndicator 和 EDT 状态 |
| 运行任务编排 | `idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/JuggRunningTask.kt`, `idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/JuggDebugSessionManager.kt` | 编译与部署串联主流程；Debug executor 成功部署后由 `JuggDebugSessionManager` 做单设备 Java debugger attach |
| 编译入口 | `main/src/main/java/com/sickworm/intellij/jugg/compiler/JuggCompilerHelper.kt` | 增量/Gradle 回退判定；IDEA 与 standalone 共享，Host 注入环境和交互边界 |
| IDEA 部署 Host | `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/IdeaDeployEnvironment.kt`, `IdeaDeployDebugger.kt`, `run/instrument/LibraryTestApkBackfillHelper.kt` | 提供 IDEA 设备、ADB、prompt、debugger、AndroidTest UI；选择 install/embedded/incremental 的 `JuggDeployerHelper` 已位于 main |
| IDEA 部署状态 Host | `idea/src/main/java/com/sickworm/intellij/jugg/deploy/IdeaHostDeployStateResolver.kt` | 通过 `AsDeployerCompat` 读取 Android Studio 设备部署状态 |
| 插件加载 | `idea/src/ide_entry/java/com/sickworm/intellij/jugg/loader/JuggLoader.kt` | 类加载隔离与桥接；`com.sickworm.intellij.jugg.ide` 保持稳定，热更新 UI 仅通过基础类型跨边界 |
| 初始化器 | `idea/src/ide_entry/java/com/sickworm/intellij/jugg/loader/JuggInitializer.kt` | 插件生命周期入口 |
| Control Panel 桥接 | `idea/src/ide_entry/java/com/sickworm/intellij/jugg/ide/JuggControlPanelHost.kt`, `idea/src/ide_entry/java/com/sickworm/intellij/jugg/ide/ui/OpenJuggControlPanelAction.kt`, `main/src/main/java/com/sickworm/intellij/jugg/ide/controlpanel/`, `idea/src/main/java/com/sickworm/intellij/jugg/ide/ui/JuggControlPanelController.kt` | 稳定 Host/Action 仅跨边界传递 JComponent；Controller 持有 Model/Panel 并由 Manager clear；main Model 统一 facts/events |
| 运行配置 | `idea/src/ide_entry/java/com/sickworm/intellij/jugg/ide/JuggRunConfiguration.kt`, `idea/src/main/java/com/sickworm/intellij/jugg/ide/JuggDebugProgramRunner.kt` | run config 定义；`JuggDebugProgramRunner` 接管 Jugg + Debug executor，让 Debug 按钮可用 |
| androidTest 运行入口 | `idea/src/ide_entry/java/com/sickworm/intellij/jugg/ide/JuggAndroidTestRunConfiguration.kt`, `idea/src/ide_entry/java/com/sickworm/intellij/jugg/ide/JuggAndroidTestLineMarkerContributor.kt`, `idea/src/ide_entry/java/com/sickworm/intellij/jugg/ide/JuggAndroidTestConsoleProperties.kt`, `idea/src/ide_entry/java/com/sickworm/intellij/jugg/ide/JuggAndroidTestRerunFailedTestsAction.kt` | app `src/androidTest` gutter 与临时 RunConfig，生成 `AndroidTestRunSpec` 后进入 Jugg run pipeline；androidTest run 使用 SM Test Runner console，支持 Test Results 树、source navigation 与 rerun failed |
| More Options 工具菜单 | `idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/MoreOptionsManager.kt` | More options 下拉分组与工具项（含 MCP/skill 安装入口） |
| Jugg Control Panel | `idea/src/main/java/com/sickworm/intellij/jugg/ide/ui/JuggToolWindowFactory.kt`, `JuggControlPanel.kt`, `JuggControlPanelController.kt`, `idea/src/ide_entry/java/com/sickworm/intellij/jugg/ide/ui/OpenJuggControlPanelAction.kt` | 项目级右侧 `Jugg Running Pannel`；Overview / Logs / Settings 消费真实 snapshot，Logs 展示结构化核心事件；Mock Model 可切换但复用同一订阅渲染路径；Run Configuration 的 `More options` 直接打开 Settings |
| 远程自定义命令 | `idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/RemoteCommandRunner.kt`, `idea/src/main/java/com/sickworm/intellij/jugg/ide/ui/RemoteCommandDialog.kt`, `main/src/main/java/com/sickworm/intellij/jugg/gradle/compile/RemoteUserCommand.kt`, `main/src/main/java/com/sickworm/intellij/jugg/ide/bean/JuggSettings.kt` | 使用当前选中的远程 Jugg Configuration，在固定远程项目目录执行非交互命令；独立 Run Content 支持 Stop，唯一完成标记隔离用户输出，`JuggSettings` 按远程目标保存最近 10 条命令 |
| Gradle Sync 监听 | `idea/src/ide_entry/java/com/sickworm/intellij/jugg/ide/JuggGradleSyncListener.kt` | Sync 事件上报 Jugg |

---

## 3. 兼容层与扩展模块

| 模块 | 目录 | 关键点 |
|------|------|--------|
| deploy_compat | `deploy_compat/interface/src/main/java/com/sickworm/intellij/jugg/deploy/api/`, `deploy_compat/*/src/main/java/com/sickworm/intellij/jugg/deploy/run/` | `deploy.api` 仅保存同名自有设备/APK/protobuf/logger 契约并保留 D8 字段重初始化状态；legacy 继承链与 Quail 分别在版本模块内转换真实 Android 类型并持有实例级弱缓存，interface JAR 不提供 raw converter；owner-bound deploy 调用固定使用 priority compat，wrapper 隔离 deployer runtime 类型包迁移，`attachJavaDebugger()` 隔离 Java debugger API 迁移 |
| standalone_deployer | `deploy_compat/standalone_deployer/src/main/java/`, `deploy_compat/standalone_deployer/src/main/resources/deployer/quail/` | 固定 Quail 1 的 Java 11 deployer 最小闭包、`StandaloneApplyChangesExecutor`、真实 ddmlib `StandaloneDeviceManager`、installer/protocol 资源与 SHA-256 metadata；不打包或加载完整 `sdk-tools.jar` |
| platform_compat | `platform_compat/base_api/src/main/java/` | IntelliJ/log4j 最小实现，供 `main` 编译并作为 CLI runtime stub；禁止包含 `com.android.*` |
| Stub API 工具 | `tools/stub_api_generator/`, `deploy_compat/*.sh`, `deploy_compat/stub_api/` | 从已编译 compat JAR 的字节码引用闭包生成版本化 compile-only Stub；脚本负责创建模块、显式切换真实 JAR/Stub、生成 Stub，并通过 `verify_stub_api.sh` clean 构建两边产物后验证 AS API 调用一致性 |
| cmd_line | `cmd_line/src/main/java/com/sickworm/intellij/jugg/cmdline/` | 现有 `CmdLine`/CI 命令保持不变；`standalone/` 提供 daemon/project runtime、init、Gradle/增量 compile、deploy、status 与懒加载 ddmlib 生命周期 |
| custom_compilers | `custom_compilers/src/main/java/com/sickworm/intellij/jugg/compiler/demo/` | SPI 自定义编译器示例 |
| jvmti_agent | `jvmti_agent/src/main/cpp/` + `jvmti_agent/src/main/java/com/sickworm/intellij/jugg/` | JVMTI native 能力（`native-lib.cpp`、`instrumenter.cc`）+ runtime instrument 修复（`ApplyChangesOverlayPolicy` 等）+ App 内 ViewHierarchy LocalSocket Server；`DragonflyHierarchySource` 是 dump、selector、tap、inspect、layout verify 的唯一节点数据源，Dragonfly DEX JAR 及内置 Kotlin/协程等依赖经离线预处理为 Jugg 私有包并同时进入 instruments/runtime JAR，窗口枚举失败时 Best-effort 复用旧 ActivityThread/WindowManagerGlobal 根列表；由 `BootstrapApplication` 初始化 |

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
- 查“MCP 参数规则”：从 tool action 的 `inputSchema` 和 `execute` 实现确认。

---

## 6. 维护约定

- 新增入口类/关键工具后，优先同步本表。  
- 路径或类名变更时，同步 `99_index.md` 的专题文档目录描述。  
- 若本表滞后，回答中必须明确“以代码为准”。
