# Jugg 单元测试覆盖现状与 TDD 差距报告（详细版）

> **历史盘点**（2026-03-02）。测试分层与新增用例口径以 [`06_testing.md`](../ai_knowledge/06_testing.md) 与 `AGENTS.md` 为准；下文「缺单元测试」指 L1 域内覆盖缺口，不等于鼓励为编排类补 Mockito 单测。

> 日期：2026-03-02  
> 口径：**未运行任何测试**，仅基于代码与测试目录做静态分析。
> 依据文档：`00_overview.md`, `97_ai_usage.md`, `98_code_map.md`
> 一致性：文档与代码冲突时，以代码为准。

---

## 1. 全局概览

| 维度 | 数值 |
|------|------|
| 生产代码文件总数 | ~259 (main) + ~71 (idea) + ~16 (deploy_compat) + ~14 (cmd_line) ≈ **360** |
| 测试类文件总数（含 mock/辅助） | ~52 (main) + ~75 (idea) + 3 (cmd_line) + 1 (jvmti) ≈ **131** |
| 实际测试类（*Test 文件） | ~35 (main) + ~46 (idea) + 1 (cmd_line) ≈ **82** |
| @Test 方法总数 | main: **249**,  idea: **209**,  合计 **~458** |
| 测试框架 | JUnit 4.12 + Mockito 5.4 + mockito-kotlin 5.0 + kotlin-test 1.9.23 |
| 覆盖率工具 | **未配置**（无 JaCoCo / Kover） |
| 估计当前覆盖率 | ~20-30%（按文件比例和间接覆盖粗估） |

---

## 2. 按模块覆盖详情

### 2.1 main 模块（核心逻辑层，259 个生产文件）

#### 2.1.1 有测试覆盖的领域

| 领域 | 生产文件数 | 测试文件数 | @Test 数 | 覆盖评价 |
|------|-----------|-----------|---------|----------|
| compiler/constref（常量引用） | 12 | 7 | 39 | **良好** - 单元+集成覆盖全面 |
| compiler/obfuscation（混淆） | 6 | 5 | 65 | **良好** - 测试密度最高 |
| compiler/overlay（资源编译） | 13 | 2 | 16 | **中等** - 13 个文件仅 2 个测试 |
| compiler/source（源码编译） | 25 | 3 | 14 | **不足** - 25 文件仅 3 测试 |
| compiler/source/apt | 单独统计 | 1 | 8 | 中等 |
| deploy/data（影响分析） | 19 | 5 | 34 | **中等** - 核心 Generator 有测试 |
| deploy 根级 | ~20 | 4 | 7 | **不足** - 核心类无覆盖 |
| mcp 协议层 | 12 | 4 | 38 | **良好** - 协议/校验/错误处理全面 |
| mcp/actions（MCP 工具） | 27 | 3 | 21 | **严重不足** - 27 个 action 仅 3 个测试 |

#### 2.1.2 完全没有测试覆盖的领域

| 领域 | 生产文件数 | 关键类 | 优先级 |
|------|-----------|--------|--------|
| **compiler 根级** | ~15 | JuggCompiler, IncrementalCompilerHelper, CompileOrder, ForceGradleCompileHelper, BaseCompiler | **P0** |
| **compiler/databinding** | 8 | DataBindingArgsManager, DataBindingGenBaseClassesCompiler, DataBindingGenMapperCompiler | **P1** |
| compiler/manifest (main) | 6 | 注：idea 模块有 5 个测试文件部分覆盖 | P2 |
| compiler/custom | 2 | CustomCompilerManager, ICompilerCreator | P3 |
| compiler/ui | 2 | CompileUiHandler | P3 |
| **deploy 根级核心** | ~20 | DeployFileManager, DeployDataPlanner, CompileEffectAnalyzer, DeployFileStateTracker, ClassFileLookupHelper, CompatDeployHelper | **P0** |
| deploy/run | 2 | JuggDeployData, LaunchResult | P2 |
| **project** | ~12 | JuggPathManager, FileChangesHandler, ClasspathBackupHelper, CustomConfigManager, ProjectInfoSerializer | **P1** |
| **gradle** | 22 | LocalGradleCompileClient, RemoteGradleCompileClient, GradleProjectInfoReader, GradleDependencyDiffer, CmdExecutor | **P1** |
| apk | 7 | ApkFileModifier, ApkReader, ResourceApkModifier, ApkInfoReader | P2 |
| git | 6 | GitManager, FileMatcher, WorktreeRepositoryBuilder | P2 |
| logger | 5 | JuggLogger, FileLogger, LogDispatcher | P3 |
| server | 5 | JuggServer, JuggServerChooser, JuggRemoteCompileApplier | P2 |
| platform | 2 | PlatformApi, IPlatformApi | P3 |
| ide/bean + ide/logic | 8 | 数据模型与 IDE 逻辑辅助 | P2 |

### 2.2 idea 模块（IDE 插件层，71 个生产文件）

#### 2.2.1 已有测试覆盖

| 领域 | 测试文件数 | @Test 数 |
|------|-----------|---------|
| compile（编译各阶段） | ~20 | 97 |
| compiler/manifest | 5 | 28 |
| manager（顶层流程） | ~5 | 25 |
| project | 2 | 15 |
| gradle | 4 | 11 |
| git | 3 | 10 |
| mcp | 1 | 10 |
| deploy | 2 | 2 |
| aapt2/apk | 2 | 9 |
| server | 1 | 1 |
| ide/logic | 1 | 1 |

#### 2.2.2 无测试覆盖的关键区域

| 区域 | 文件数 | 关键类 | 优先级 |
|------|--------|--------|--------|
| **JuggManager** | 1 | 总管理器，初始化/同步/MCP 装配 | **P0** |
| **JuggRunningTask** | 1 | 编译与部署串联主流程 | **P0** |
| **JuggCompileHelper** | 1 | 增量/Gradle 回退判定 | **P1** |
| **JuggDeployerHelper / JuggDeployer** | 2 | 部署策略/recover/agent 协调 | **P0** |
| DeployStateManager | 1 | 设备状态与部署可行性 | P1 |
| ide/logic（6+ 文件） | ~8 | JuggConfigurationRunner, IdeSyncProblemResolver, MoreOptionsManager 等 | P2 |
| ide/ui（所有 UI 类） | ~16 | Dialog/Action/Notification 等 | P3（UI 类难以纯单测） |
| ide_entry/（loader 等） | ~14 | JuggLoader, JuggInitializer, JuggHotUpdateManager 等 | P2 |
| mcp/IdeaMcpRuntime | 1 | IDE 侧 MCP 运行时 | P1 |

### 2.3 其他模块

| 模块 | 生产文件数 | 测试文件数 | 状态 |
|------|-----------|-----------|------|
| deploy_compat/interface | 5 | 0 | **无测试** |
| deploy_compat/v_*（8 个版本） | 10 | 0 | **无测试** |
| cmd_line | 14 | 1 | **严重不足** |
| custom_compilers | 4 | 0 | 无测试 |
| platform_compat/base_api | 21 | 0 | mock 桩代码，可接受 |
| jvmti_agent | C++ 代码 | 1 (空壳) | 无实质测试 |

---

## 3. MCP Actions 覆盖详情（27 个 action 仅 3 个有测试）

### 3.1 已有测试

- `CompileAndDeployMcpToolActionTest` (3 @Test)
- `RuntimeObserveMcpToolActionTest` (2 @Test)
- `TapMcpToolActionTest` (16 @Test)

### 3.2 无测试的 action（24 个）

| Action | 功能 | 测试优先级 |
|--------|------|-----------|
| CompileOnlyMcpToolAction | 仅编译 | P1 |
| ForceGradleCompileMcpToolAction | Gradle 回退编译 | P1 |
| CleanReinstallApkMcpToolAction | 清理重装 | P1 |
| RestartAppMcpToolAction | 重启应用 | P1 |
| GetCompileStatusMcpToolAction | 编译状态查询 | P1 |
| DeviceListMcpToolAction | 设备列表 | P2 |
| ScreenshotMcpToolAction | 截图 | P2 |
| LayoutDumpMcpToolAction | UI 层级 | P2 |
| ActivityStackMcpToolAction | Activity 栈 | P2 |
| CrashReportMcpToolAction | 崩溃报告 | P2 |
| StartRecordMcpToolAction | 开始录屏 | P2 |
| StopRecordMcpToolAction | 停止录屏 | P2 |
| StartAppMcpToolAction | 启动应用 | P2 |
| StartActivityMcpToolAction | 启动 Activity | P2 |
| ListProjectsMcpToolAction | 项目列表 | P3 |
| EmulatorListMcpToolAction | 模拟器列表 | P3 |
| StartEmulatorMcpToolAction | 启动模拟器 | P3 |
| RequestRemoteSshInfoMcpToolAction | SSH 信息 | P3 |
| CompileJobManager | 异步编译管理 | P1 |
| McpToolActionRegistry | 工具注册 | P1 |
| McpToolSchemas | Schema 定义 | P2 |
| McpFetchCleaner | 过期文件清理 | P2 |
| RecordSessionRegistry | 录屏会话管理 | P3 |
| RecordToolSupport | 录屏工具支持 | P3 |

---

## 4. 从 TDD 标准审视的核心问题

### 4.1 基础设施缺失

| 问题 | 影响 |
|------|------|
| **无覆盖率工具**（JaCoCo/Kover） | 无法量化覆盖率，无法设定门槛 |
| **无 CI 测试门控** | 代码合入无强制测试通过要求 |
| **JUnit 4（非 JUnit 5）** | 缺少参数化测试、嵌套测试等现代特性 |
| **无测试分层机制** | unit/integration/device 测试混在一起 |

### 4.2 测试金字塔失衡

- 现有测试多为**集成测试**（依赖 Android 项目 assets、Gradle 构建、文件系统）
- 纯函数级、决策级**单元测试严重不足**
- `TopLevelFlowTest`、`JuggCompileTest` 等更像端到端测试
- 导致：Red 阶段定位慢、失败不稳定、Refactor 回归半径大

### 4.3 测试质量问题

| 问题 | 具体表现 |
|------|----------|
| 测试与生产不在同模块 | 部分 main 逻辑在 idea 模块测试，组织混乱 |
| 无断言测试 | `JuggServerTest`、`DeployTargetManagerTest` 仅执行不验证 |
| 硬编码路径 | `LocalTest.kt` 包含 `/Users/...` 绝对路径 |
| 测试产物污染源码树 | `cmd_line/src/test/build/` 写入测试目录 |
| 缺少边界/异常路径测试 | 现有测试主要覆盖 happy path |

### 4.4 TDD 流程差距

- 现有代码明显是"先有实现再补测试"模式，非 TDD 的 Red-Green-Refactor 流程
- 大量核心逻辑（编译调度、部署管理）完全没有测试
- 缺少 PR 模板约束和高风险类守护规则

---

## 5. TODO 清单

### P0 - 建立 TDD 基线（必须先做）

| # | 任务 | 涉及范围 | 验收标准 |
|---|------|----------|----------|
| 1 | **配置 JaCoCo/Kover 覆盖率工具** | `build.gradle` (root + main + idea) | 可运行 `./gradlew jacocoTestReport` 生成报告 |
| 2 | **建立测试分层**（unitTest / integrationTest / deviceTest） | Gradle test task 配置 | `unitTest` 不依赖设备/外部路径，可 CI 直接运行；`@RequiresDevice` 测试不进入 `unitTest` |
| 3 | 为 **JuggCompiler** 补充单元测试 | `compiler/JuggCompiler.kt` | 成功/失败/fallback/取消 各路径覆盖，≥10 个 @Test |
| 4 | 为 **IncrementalCompilerHelper** 补充测试 | `compiler/IncrementalCompilerHelper.kt` | 循环重编译、重试、fallback 决策覆盖，≥8 个 @Test |
| 5 | 为 **CompileOrder** 补充测试 | `compiler/CompileOrder.kt` | 阶段顺序、跳过条件覆盖，≥5 个 @Test |
| 6 | 为 **DeployFileManager** 补充测试 | `deploy/DeployFileManager.kt` | staging 管理、变更文件计算覆盖，≥10 个 @Test |
| 7 | 为 **DeployDataPlanner** 补充测试 | `deploy/DeployDataPlanner.kt` | 部署数据计算逻辑覆盖，≥8 个 @Test |
| 8 | 为 **CompileEffectAnalyzer** 补充测试 | `deploy/CompileEffectAnalyzer.kt` | 编译影响分析各场景覆盖，≥5 个 @Test |
| 9 | 为 **DeployFileStateTracker** 补充测试 | `deploy/DeployFileStateTracker.kt` | 状态跟踪逻辑覆盖，≥5 个 @Test |
| 10 | **修复无断言测试** | JuggServerTest, DeployTargetManagerTest | 增加可验证断言或标记为手工测试 |
| 11 | **清理非可移植测试** | LocalTest.kt | 移出默认测试集或改造为参数化 |
| 12 | **调整测试输出目录** | cmd_line 测试 | 产物移至 `build/` 目录，不写入 `src/test` |

### P1 - 提高回归质量

| # | 任务 | 涉及范围 | 预估 @Test 数 |
|---|------|----------|-------------|
| 13 | 为 **compiler/databinding** 补充测试 | 8 个生产文件 | 15~20 |
| 14 | 为 **compiler/source** 补充更多测试 | SourceCompiler, JavaCompiler, KotlinCompiler, DexCompiler 等 | 20~25 |
| 15 | 为 **project** 包补充测试 | JuggPathManager, FileChangesHandler, ClasspathBackupHelper 等 | 10~15 |
| 16 | 为 **gradle** 包补充测试 | GradleProjectInfoReader, GradleDependencyDiffer, LocalGradleCompileClient 等 | 10~12 |
| 17 | 补充 **MCP actions** 测试（P1 级别 action） | CompileOnly, ForceGradle, CleanReinstall, Restart, CompileStatus, CompileJobManager, Registry | 20~25 |
| 18 | 为 **McpRequestValidator** 补充测试 | MCP 参数校验主逻辑 | 8~10 |
| 19 | 为 **deploy/DeployHistoryDb + CompileContextDb** 补充测试 | 数据库操作类 | 5~8 |
| 20 | 为 **idea/JuggDeployerHelper** 补充测试 | 需 mock IDE 环境 | 8~10 |
| 21 | 为 **idea/JuggRunningTask** 补充测试 | Run 主链路编排 | 8~10 |
| 22 | 为 **ClassMinifyCompiler / DexMinifyCompiler** 补充边界测试 | 混淆映射边界场景 | 5~8 |

### P2 - 应该补充

| # | 任务 | 涉及范围 | 预估 @Test 数 |
|---|------|----------|-------------|
| 23 | 为 **apk** 包补充测试 | ApkFileModifier, ApkReader, ResourceApkModifier 等 | 8~10 |
| 24 | 为 **server** 包补充测试 | JuggServer, JuggServerChooser, JuggRemoteCompileApplier | 5~8 |
| 25 | 为 **cmd_line** 模块补充测试 | 14 个文件仅 1 个测试 | 8~10 |
| 26 | 为 **deploy_compat** 模块补充测试 | 各版本 AsDeployerCompat | 8~10 |
| 27 | 为 **ide/logic** 非 UI 类补充测试 | IdeSyncProblemResolver, MoreOptionsManager 等 | 5~8 |
| 28 | 补充 **MCP actions** 测试（P2 级别 action） | DeviceList, Screenshot, LayoutDump, ActivityStack 等 | 15~20 |
| 29 | 统一测试命名规范 | 所有 @Test 所在类统一 `*Test` 后缀 | - |

### P3 - 可选改进

| # | 任务 |
|---|------|
| 30 | 升级 JUnit 4 -> JUnit 5（参数化测试、嵌套测试、DisplayName 等） |
| 31 | 为 **logger** 包补充测试 |
| 32 | 为 **platform** 包补充测试 |
| 33 | 整理测试目录结构，确保 main 模块的测试在 main/src/test 下 |
| 34 | 补充 mock 基础设施文档，降低新测试编写门槛 |
| 35 | 引入变更集覆盖率阈值（diff coverage） |
| 36 | 建立 PR 模板约束（功能改动须附测试证据） |
| 37 | 建立"高风险类清单"守护规则（关键类修改必须伴随测试更新） |

---

## 6. 达到 80% 覆盖率目标的估算

| 指标 | 当前 | 目标 | 差距 |
|------|------|------|------|
| 估计覆盖率 | ~20-30% | 80% | ~50-60 个百分点 |
| @Test 方法数 | ~458 | ~1000-1200 | 新增 ~500-700 个 |
| 测试文件数 | ~82 | ~140-160 | 新增 ~50-80 个 |
| 关键前提 | - | 配置覆盖率工具 | **必须先完成 TODO #1** |

---

## 7. 结论

当前项目测试总量不低（458 个 @Test），但从 TDD 标准看存在三个核心短板：

1. **高风险核心决策类缺少直接单测** - 编译调度（JuggCompiler, IncrementalCompilerHelper）、部署管理（DeployFileManager, CompileEffectAnalyzer）等关键类零覆盖
2. **测试金字塔倒挂** - 集成测试多、纯单元测试少，导致反馈慢、稳定性差
3. **缺少工程化门禁** - 无覆盖率工具、无 CI 测试门控、无测试分层机制

优先完成 P0 项（12 项任务）后，项目即可从"有较多测试"升级为"具备可执行 TDD 能力"的状态。
