# 代码路径速查表 (Code Map)

> 同步状态: 若代码与文档不一致，以代码为准；下表附状态/更新时间，便于核对。
> 目的: 为 AI/开发者提供"类/模块 → 文件路径 → 职责/入口"速查，减少全局搜索。

---

## 一、核心层 (main/src/main/java/com/sickworm/intellij/jugg)

| 模块 | 关键类/接口 | 文件路径 | 职责/说明 | 状态 | 最近同步 |
|------|-------------|----------|-----------|------|-----------|
| compiler | JuggCompiler, BaseCompiler, CompileTask | compiler/core | 编译调度与任务编排 | 稳定 | 2025-01-20 |
| compiler | JavaCompiler, KotlinCompiler, DexCompiler | compiler/source | 源码/字节码/DEX 编译 | 稳定 | 2025-01-20 |
| compiler | ResourceCompiler, Aapt2Invoker | compiler/resource | AAPT2 资源编译与调用 | 稳定 | 2025-01-20 |
| compiler | DataBindingGenBaseClassesCompiler, DataBindingGenMapperCompiler | compiler/databinding | DB/VB 处理 | 稳定 | 2025-01-20 |
| compiler | ManifestCompiler, ObfuscationCompiler | compiler/manifest | Manifest 处理/混淆 | 稳定 | 2025-01-20 |
| compiler | CustomCompilerManager, CompileUiHandler | compiler/custom | 自定义编译器插件 | 稳定 | 2025-01-20 |
| deploy | JuggDeployer, DeployFileManager | deploy/core | 部署调度、文件准备 | 稳定 | 2025-01-20 |
| deploy | DeployDataGenerator, ClassNodeComparator | deploy/data | 类结构比较、影响分析 | 稳定 | 2026-02-01 |
| deploy | DeployDataDatabase, IncrementalDeployDataDatabase | deploy/data | 双层数据库、引用索引 | 稳定 | 2026-02-01 |
| deploy | InlineMethodDetector | deploy/data | 内联方法影响检测 | 稳定 | 2026-02-01 |
| deploy | IncrementalDeployHelper, DeployHistoryManager | deploy/core | 增量部署与历史管理 | 稳定 | 2025-01-20 |
| project | JuggProjectInfo, GradleProjectInfoReader | project | 项目信息读取/序列化 | 稳定 | 2025-01-20 |
| project | DependencyResolver, LocalGradleCompileClient | project | 依赖解析与 Gradle 调用 | 稳定 | 2025-01-20 |
| apk | ApkFileModifier | apk | APK 修改/签名 | 稳定 | 2025-01-20 |
| aapt2 | Aapt2DaemonInvoker | aapt2 | AAPT2 守护进程调用 | 稳定 | 2025-01-20 |
| git | GitManager | git | Git 集成 | 稳定 | 2025-01-20 |
| logger | JuggLogger | logger | 日志体系 | 稳定 | 2025-01-20 |
| mcp | McpLocalServer, McpInvoker, GradleCompileJobManager | mcp | 本地 MCP/工具命令注册、force_gradle_compile 异步任务状态 | 稳定 | 2026-02-22 |
| server | JuggServer | server | 远程编译/服务端 | 稳定 | 2025-01-20 |
| platform | PlatformApi | platform | 平台 API 注入点 | 稳定 | 2025-01-20 |

> 说明：表中“路径”以模块目录表示，实际文件在 `main/src/main/java/com/sickworm/intellij/jugg/<模块>/` 下；状态列如有“实验/废弃”需优先跟代码确认。

---

## 二、IDE 层 (idea/src/main/java/com/sickworm/intellij/jugg)

| 关键类 | 文件路径 | 职责/说明 | 状态 | 最近同步 |
|--------|----------|-----------|------|-----------|
| JuggLoader | idea/load | 插件加载、类加载隔离 | 稳定 | 2025-01-20 |
| JuggManager | idea/core | IDE 侧核心管理器 | 稳定 | 2025-01-20 |
| JuggRunConfiguration | idea/run | 运行配置入口 | 稳定 | 2025-01-20 |
| JuggHotUpdateManager | idea/hotupdate | 热更新管理 | 稳定 | 2025-01-20 |
| FileChangesDetector | idea/file | 文件变化检测 | 稳定 | 2025-01-20 |
| DeployStateManager | idea/deploy | 部署状态机/策略 | 稳定 | 2025-01-20 |

---

## 三、兼容层与工具

| 模块 | 关键类 | 文件路径 | 职责 | 状态 | 最近同步 |
|------|--------|----------|------|------|-----------|
| deploy_compat | AsDeployerCompat (多版本子类) | deploy_compat | Android Studio 版本适配 | 稳定 | 2025-01-20 |
| platform_compat | Logger/Project/Disposable Mock | platform_compat | IntelliJ/Android SDK API Mock | 稳定 | 2025-01-20 |
| cmd_line | CmdLine, CmdExecutor | cmd_line | 无 IDE/CI 命令行入口 | 稳定 | 2025-01-20 |
| custom_compilers | SampleCompiler | custom_compilers | 自定义编译器示例 | 稳定 | 2025-01-20 |
| jvmti_agent | agent.cpp 等 | jvmti_agent | JVMTI 热修复运行时 | 稳定 | 2025-01-20 |

---

## 四、快速定位指南

- **找类**：在表中确认模块 → 直接跳到对应文件夹。
- **找流程**：编译/部署流程见 `01_architecture.md` 和对应 `02/03` 章节，配合表中路径定位实现。
- **改扩展点**：自定义编译器、平台 API、RPC 命令，见各自模块下入口类与文档章节。
> 若发现接口改动未同步到本表，请先以代码为准，并在提交时更新“状态/最近同步”列。

---

## 五、维护约定

- 新增关键类/入口时，同步更新本表。
- 如目录变更，同步替换为新路径，避免索引漂移。
