# 代码路径速查表 (Code Map)

> 同步状态: 若代码与文档不一致，以代码为准。
> 统计口径: 仅统计生产代码（`src/main` + `idea/src/ide_entry`），不含 `build/` 与 `src/test/`。

---

## 一、核心层（main/src/main/java/com/sickworm/intellij/jugg）

| 模块 | 关键类/接口 | 文件路径 | 职责/说明 | 状态 | 最近同步 |
|------|-------------|----------|-----------|------|-----------|
| compiler | JuggCompiler, BaseCompiler, CompileTask | `compiler/` | 编译调度与任务编排 | 稳定 | 2026-02-22 |
| compiler | JavaCompiler, KotlinCompiler, DexCompiler | `compiler/source/`, `compiler/source/kotlin/` | 源码/字节码/DEX 编译 | 稳定 | 2026-02-22 |
| compiler | ResourceCompiler, ResourceOverlayCompiler | `compiler/overlay/` | 资源增量编译与 Overlay 处理 | 稳定 | 2026-02-22 |
| compiler | AndroidManifestCompiler, ManifestDiffer | `compiler/manifest/` | Manifest 增量处理 | 稳定 | 2026-02-22 |
| compiler | ClassMinifyCompiler, DexMinifyCompiler | `compiler/obfuscation/` | 混淆映射与类名还原处理 | 稳定 | 2026-02-22 |
| compiler | DataBindingGenBaseClassesCompiler, DataBindingGenMapperCompiler | `compiler/databinding/` | DataBinding/ViewBinding 处理 | 稳定 | 2026-02-22 |
| compiler | CustomCompilerManager, CompileUiHandler | `compiler/custom/`, `compiler/` | 自定义编译器扩展点与 UI 回调 | 稳定 | 2026-02-22 |
| deploy | DeployFileManager, DeployHistoryManager | `deploy/` | 部署文件准备与历史管理 | 稳定 | 2026-02-22 |
| deploy | DeployDataGenerator, ClassNodeComparator, InlineMethodDetector | `deploy/data/` | 类结构比较与影响分析 | 稳定 | 2026-02-22 |
| deploy | DeployDataDatabase, IncrementalDeployDataDatabase | `deploy/data/` | 部署数据与增量索引数据库 | 稳定 | 2026-02-22 |
| deploy | IncrementalDeployHelper | `compiler/` | 增量部署策略辅助 | 稳定 | 2026-02-22 |
| project | JuggProjectInfo, ProjectInfoSerializer | `project/data/`, `project/` | 项目信息模型与序列化 | 稳定 | 2026-02-22 |
| project | DependencyChangeManagerByGradle, DependencyChangeManagerBySync | `project/dependency/` | 依赖差异解析 | 稳定 | 2026-02-22 |
| gradle | GradleProjectInfoReader, GradleDependencyDiffer | `gradle/script/` | Gradle 侧信息读取与依赖比对 | 稳定 | 2026-02-22 |
| gradle | LocalGradleCompileClient, CmdExecutor | `gradle/compile/` | 本地/远程 Gradle 编译调用 | 稳定 | 2026-02-22 |
| mcp | McpLocalServer, McpToolInvoker, McpBaseInvoker, IMcpInvoker | `mcp/` | MCP 协议入口与请求路由（HTTP: `/jugg-mcp`） | 稳定 | 2026-02-22 |
| mcp | CompileJobManager | `mcp/actions/` | 编译任务异步状态管理 | 稳定 | 2026-02-22 |
| aapt2 | Aapt2DaemonInvoker | `aapt2/` | AAPT2 守护进程调用 | 稳定 | 2026-02-22 |
| apk | ApkFileModifier | `apk/` | APK 修改/签名 | 稳定 | 2026-02-22 |
| git | GitManager | `git/` | Git 集成 | 稳定 | 2026-02-22 |
| logger | JuggLogger | `logger/` | 日志体系 | 稳定 | 2026-02-22 |
| server | JuggServer | `server/` | 远程编译/服务端能力 | 稳定 | 2026-02-22 |
| platform | PlatformApi | `platform/` | 平台 API 注入点 | 稳定 | 2026-02-22 |

---

## 二、IDE 层（idea/src/main + idea/src/ide_entry）

| 关键类 | 文件路径 | 职责/说明 | 状态 | 最近同步 |
|--------|----------|-----------|------|-----------|
| JuggManager | `idea/src/main/java/com/sickworm/intellij/jugg/JuggManager.kt` | IDE 侧核心管理器 | 稳定 | 2026-02-22 |
| JuggLoader | `idea/src/ide_entry/java/com/sickworm/intellij/jugg/loader/JuggLoader.kt` | 插件加载与类加载隔离 | 稳定 | 2026-02-22 |
| JuggRunConfiguration | `idea/src/ide_entry/java/com/sickworm/intellij/jugg/ide/JuggRunConfiguration.kt` | 运行配置入口 | 稳定 | 2026-02-22 |
| JuggHotUpdateManager | `idea/src/ide_entry/java/com/sickworm/intellij/jugg/loader/JuggHotUpdateManager.kt` | 热更新管理 | 稳定 | 2026-02-22 |
| JuggGradleSyncListener | `idea/src/ide_entry/java/com/sickworm/intellij/jugg/ide/JuggGradleSyncListener.kt` | Gradle Sync 事件监听 | 稳定 | 2026-02-22 |
| FileChangesDetector | `idea/src/main/java/com/sickworm/intellij/jugg/project/FileChangesDetector.kt` | 文件变化检测 | 稳定 | 2026-02-22 |
| DeployStateManager | `idea/src/main/java/com/sickworm/intellij/jugg/deploy/DeployStateManager.kt` | 部署状态机/策略 | 稳定 | 2026-02-22 |
| JuggDeployer | `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployer.kt` | install/codeSwap/fullSwap 核心部署器 | 稳定 | 2026-02-22 |
| AsDeployerCompat | `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/AsDeployerCompat.kt` | Android Studio 版本适配入口 | 稳定 | 2026-02-22 |

---

## 三、兼容层与工具模块

| 模块 | 关键类 | 文件路径 | 职责 | 状态 | 最近同步 |
|------|--------|----------|------|------|-----------|
| deploy_compat | IAsDeployerCompat, ChipmunkAsDeployerCompat 等 | `deploy_compat/*/src/main/java/com/sickworm/intellij/jugg/deploy/run/` | 多版本 Android Studio Deploy API 兼容 | 稳定 | 2026-02-22 |
| platform_compat | Logger/Project/Disposable 等兼容 API | `platform_compat/base_api/src/main/java/` | IntelliJ/Android API Mock 与兼容桩 | 稳定 | 2026-02-22 |
| cmd_line | CmdLine, BuildGradleBaseCommand, BuildIncrementalApkCommand | `cmd_line/src/main/java/com/sickworm/intellij/jugg/cmdline/` | 无 IDE/CI 命令行入口 | 稳定 | 2026-02-22 |
| custom_compilers | ExampleAssembleCustomCompiler 等 | `custom_compilers/src/main/java/com/sickworm/intellij/jugg/compiler/demo/` | 自定义编译器示例 | 稳定 | 2026-02-22 |
| jvmti_agent | agent.cpp 等 | `jvmti_agent/` | JVMTI 热修复运行时 | 稳定 | 2026-02-22 |

---

## 四、快速定位指南

- 找类：先在本表按模块定位，再跳到对应目录。
- 找流程：编译/部署流程见 `01_architecture.md` 与对应 `02/03` 专题文档。
- MCP 入口：优先从 `McpToolInvoker` 与 `McpToolActionRegistry` 追工具执行链。

---

## 五、维护约定

- 新增关键类/入口时，同步更新本表。
- 类重命名或目录调整后，优先更新“关键类/文件路径”列。
