# 代码路径速查表（Code Map）

> 最后核对：2026-02-23  
> 口径：生产代码目录（不含 `build/` 与 `src/test/`）  
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 核心层（`main/src/main/java/com/sickworm/intellij/jugg`）

| 领域 | 关键类/接口 | 目录 | 说明 |
|------|-------------|------|------|
| 编译总控 | `JuggCompiler`, `IncrementalCompilerHelper`, `CompileOrder` | `compiler/` | 增量编译主流程、阶段顺序与循环重编译 |
| 源码编译 | `SourceCompiler`, `JuggAptCompiler`, `IJuggAptProcessor`, `JavaCompiler`, `KotlinCompiler`, `DexCompiler` | `compiler/source/`, `compiler/source/apt/`, `compiler/source/kotlin/` | Java/Kotlin 编译与 DEX 生成（含生成源码增量改写） |
| 资源编译 | `ResourceOverlayCompiler`, `ResourceCompiler`, `ArscCompiler` | `compiler/overlay/` | res/manifest 编译与 aapt2 link |
| DataBinding | `DataBindingArgsManager`, `DataBindingGenBaseClassesCompiler`, `DataBindingGenMapperCompiler` | `compiler/databinding/` | DataBinding/ViewBinding 增量处理 |
| Manifest | `AndroidManifestCompiler`, `AndroidManifestMerger`, `ManifestDiffer` | `compiler/manifest/` | 清单差异合并 |
| 混淆映射 | `ClassMinifyCompiler`, `DexMinifyCompiler`, `ClassObfuscator`, `R8MappingReader` | `compiler/obfuscation/` | release 混淆映射一致性 |
| 自定义编译器 | `CustomCompilerManager`, `ICompilerCreator` | `compiler/custom/` | SPI 扩展、远端下载 jar、动态装载 |
| 编译 UI 协议 | `CompileUiHandler`, `RunResult`, `BuildChangesConfirmResult` | `compiler/`, `compiler/ui/` | 编译交互抽象（供 IDE/CLI） |
| 部署文件管理 | `DeployFileManager`, `DeployHistoryManager`, `ClassFileLookupHelper` | `deploy/` | 变更文件、历史记录、staging 管理与 class 文件检索复用 |
| 影响分析 | `DeployDataGenerator`, `DeployDataDatabase`, `ClassNodeComparator`, `InlineMethodDetector` | `deploy/data/` | 类结构变更传播和部署数据生成 |
| 部署数据模型 | `JuggDeployData`, `LaunchResult` | `deploy/run/` | 下发设备的部署数据结构 |
| 项目模型 | `JuggProjectInfo`, `ModuleInfo`, `ModuleBuildPathInfo` | `project/data/` | 模块、路径、依赖等快照 |
| 依赖变更 | `DependencyChangeManagerByGradle`, `DependencyChangeManagerBySync` | `project/dependency/` | 依赖变更检测策略 |
| Gradle 信息读取 | `GradleProjectInfoReader`, `GradleDependencyDiffer` | `gradle/script/` | 通过 Gradle 反射读取模块信息 |
| Gradle 编译客户端 | `LocalGradleCompileClient`, `RemoteGradleCompileClient`, `CmdExecutor` | `gradle/compile/` | 本地/远端 Gradle 构建执行 |
| MCP 协议 | `McpLocalServer`, `McpBaseInvoker`, `McpToolInvoker`, `McpRequestValidator` | `mcp/` | MCP HTTP + JSON-RPC 处理 |
| MCP 工具 | `McpToolActionRegistry`, `CompileJobManager`, `GetCompileStatusMcpToolAction`, `McpFetchCleaner` | `mcp/actions/` | 工具注册、异步编译状态管理与 `JuggPathManager.mcpFetchDir` 过期文件清理 |
| 工具模块 | `Aapt2DaemonInvoker`, `ApkFileModifier`, `GitManager`, `JuggLogger`, `JuggServer`, `PlatformApi` | `aapt2/`, `apk/`, `git/`, `logger/`, `server/`, `platform/` | 通用基础能力 |

| 模块 | 关键类/接口 | 文件路径 | 职责/说明 | 状态 | 最近同步 |
|------|-------------|----------|-----------|------|-----------|
| compiler | JuggCompiler, BaseCompiler, CompileTask | compiler/core | 编译调度与任务编排 | 稳定 | 2025-01-20 |
| compiler | SourceCompiler, JuggAptCompiler, IJuggAptProcessor, JavaCompiler, KotlinCompiler, DexCompiler | compiler/source | 源码/生成源码改写/字节码/DEX 编译 | 稳定 | 2026-02-23 |
| compiler | ResourceCompiler, Aapt2Invoker | compiler/resource | AAPT2 资源编译与调用 | 稳定 | 2025-01-20 |
| compiler | DataBindingGenBaseClassesCompiler, DataBindingGenMapperCompiler | compiler/databinding | DB/VB 处理 | 稳定 | 2025-01-20 |
| compiler | ManifestCompiler, ObfuscationCompiler | compiler/manifest | Manifest 处理/混淆 | 稳定 | 2025-01-20 |
| compiler | CustomCompilerManager, CompileUiHandler | compiler/custom | 自定义编译器插件 | 稳定 | 2025-01-20 |
| compiler | ConstRefEngine, ConstRefAnalyzer, ConstRefChangeTracker, ConstRefImpactResolver, ConstRefSessionCache | compiler/constref | 编译期常量定义/引用分析；按“真实变更常量 key”定位受影响源码；`legacy`(全量内存索引) / `db_session`(DB 主导+会话缓存) 双模式；repo/worktree 共享缓存与过期清理 | 稳定 | 2026-03-01 |
| deploy | JuggDeployer, DeployFileManager, DeployFileStateTracker, DeployDataPlanner, CompileEffectAnalyzer | deploy/core | 部署调度、文件准备；`DeployFileManager` 作为 facade，状态跟踪/部署数据计算/编译影响分析已解耦 | 稳定 | 2026-02-27 |
| deploy | DeployDataGenerator, ClassNodeComparator | deploy/data | 类结构比较、影响分析 | 稳定 | 2026-02-01 |
| deploy | DeployDataDatabase, IncrementalDeployDataDatabase | deploy/data | 双层数据库、引用索引 | 稳定 | 2026-02-01 |
| deploy | InlineMethodDetector | deploy/data | 内联方法影响检测 | 稳定 | 2026-02-01 |
| deploy | IncrementalDeployHelper, DeployHistoryManager | deploy/core | 增量部署与历史管理 | 稳定 | 2025-01-20 |
| project | JuggProjectInfo, GradleProjectInfoReader, JuggPathManager | project | 项目信息读取/序列化；统一路径管理 | 稳定 | 2026-02-22 |
| project | DependencyResolver, LocalGradleCompileClient | project | 依赖解析与 Gradle 调用 | 稳定 | 2025-01-20 |
| apk | ApkFileModifier | apk | APK 修改/签名 | 稳定 | 2025-01-20 |
| aapt2 | Aapt2DaemonInvoker | aapt2 | AAPT2 守护进程调用 | 稳定 | 2025-01-20 |
| git | GitManager | git | Git 集成 | 稳定 | 2025-01-20 |
| logger | JuggLogger | logger | 日志体系 | 稳定 | 2025-01-20 |
| mcp | McpLocalServer, McpInvoker | mcp | 本地 MCP/工具命令注册 | 稳定 | 2026-02-09 |
| server | JuggServer | server | 远程编译/服务端 | 稳定 | 2025-01-20 |
| platform | PlatformApi | platform | 平台 API 注入点 | 稳定 | 2025-01-20 |

---

## 2. IDE 层（`idea/src/main` + `idea/src/ide_entry`）

| 入口 | 文件路径 | 说明 |
|------|----------|------|
| IDE 总管理器 | `idea/src/main/java/com/sickworm/intellij/jugg/JuggManager.kt` | 初始化、同步事件、MCP runtime 装配 |
| 运行任务编排 | `idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/JuggRunningTask.kt` | 编译与部署串联主流程 |
| 编译入口 | `idea/src/main/java/com/sickworm/intellij/jugg/compiler/JuggCompileHelper.kt` | 增量/Gradle 回退判定 |
| 部署入口 | `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployerHelper.kt` | 部署策略、recover、agent 协调 |
| 核心部署器 | `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployer.kt` | install/codeSwap/fullSwap |
| 部署状态 | `idea/src/main/java/com/sickworm/intellij/jugg/deploy/DeployStateManager.kt` | 设备状态与部署可行性 |
| 插件加载 | `idea/src/ide_entry/java/com/sickworm/intellij/jugg/loader/JuggLoader.kt` | 类加载隔离与桥接 |
| 初始化器 | `idea/src/ide_entry/java/com/sickworm/intellij/jugg/loader/JuggInitializer.kt` | 插件生命周期入口 |
| 运行配置 | `idea/src/ide_entry/java/com/sickworm/intellij/jugg/ide/JuggRunConfiguration.kt` | run config 定义 |
| Gradle Sync 监听 | `idea/src/ide_entry/java/com/sickworm/intellij/jugg/ide/JuggGradleSyncListener.kt` | Sync 事件上报 Jugg |

---

## 3. 兼容层与扩展模块

| 模块 | 目录 | 关键点 |
|------|------|--------|
| deploy_compat | `deploy_compat/*/src/main/java/com/sickworm/intellij/jugg/deploy/run/` | `IAsDeployerCompat` + 多版本实现（chipmunk/giraffe/hedgehog/iguana/meerkat/narwhal/narwhal_feature/otter） |
| platform_compat | `platform_compat/base_api/src/main/java/` | IntelliJ/Android API mock，供 `main` 编译与测试 |
| cmd_line | `cmd_line/src/main/java/com/sickworm/intellij/jugg/cmdline/` | `CmdLine`, `BuildGradleBaseCommand`, `BuildIncrementalApkCommand` |
| custom_compilers | `custom_compilers/src/main/java/com/sickworm/intellij/jugg/compiler/demo/` | SPI 自定义编译器示例 |
| jvmti_agent | `jvmti_agent/src/main/cpp/` | `native-lib.cpp`, `instrumenter.cc`, `native_callbacks.cc` |

---

## 4. MCP 工具定位（代码入口）

- 工具注册：`main/src/main/java/com/sickworm/intellij/jugg/mcp/actions/McpToolActionRegistry.kt`  
- schema 复用：`main/src/main/java/com/sickworm/intellij/jugg/mcp/actions/McpToolSchemas.kt`  
- 协议入口：`main/src/main/java/com/sickworm/intellij/jugg/mcp/McpLocalServer.kt`  
- 校验与分发：`main/src/main/java/com/sickworm/intellij/jugg/mcp/McpRequestValidator.kt`、`main/src/main/java/com/sickworm/intellij/jugg/mcp/McpToolInvoker.kt`

---

## 5. 高频定位建议

- 查“某能力是否已存在”：先 `98_code_map.md`，再对应目录搜索类名。  
- 查“编译为何回退”：从 `JuggCompileHelper` -> `preprocessIncrementalCompile`。  
- 查“部署失败恢复”：从 `JuggDeployerHelper.deploy` -> `recoverDeployState`。  
- 查“MCP 参数规则”：从 tool action 的 `inputSchema` 和 `execute` 实现确认。

---

## 6. 维护约定

- 新增入口类/关键工具后，优先同步本表。  
- 路径或类名变更时，同步 `99_index.md` 的导航描述。  
- 若本表滞后，回答中必须明确“以代码为准”。
