# AI 使用指引（任务路由版）

> 最后核对：2026-05-23
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 目标

让 AI 用最小上下文完成任务：
- 先定位路径与入口类
- 再按任务类型读取最小专题
- 最后必要时下钻到代码

---

## 2. 推荐检索顺序

1. 先读 `98_code_map.md`：确定模块、目录和入口类。
2. 再读对应专题文档，按第3节任务路由表查找。
3. 发现文档与代码不一致：**立即以代码为准**，并记录待同步项。

---

## 3. 任务类型 -> 最小必读集

| 任务类型 | 最小必读文档 | 代码入口（示例） |
|----------|--------------|------------------|
| **测试策略 / TDD / 新增 testcase** | `06_testing.md` | `main/.../DeployDataGeneratorTest.kt`, `idea/.../manager/TopLevelFlowTest`, `docs/task/jugg_deployer_helper_deploy_flow_test_plan.md` |
| **app androidTest 支持 / instrumentation 运行链路** | `06_android_test.md`, `06_testing.md` | `JuggAndroidTestRunConfiguration.kt`, `JuggAndroidTestLineMarkerContributor.kt`, `JuggAndroidTestConsoleProperties.kt`, `JuggAndroidTestRerunFailedTestsAction.kt`, `InstrumentationSmRunnerBridge.kt`, `TestLauncher.kt` |
| 整体架构理解/模块划分 | `98_code_map.md`, `01_architecture.md` | - |
| 编译失败/回退策略 | `98_code_map.md`, `02_compile_core.md`, `02_compile_source.md` | `idea/.../JuggCompileHelper.kt`, `main/.../JuggCompiler.kt` |
| 资源/Manifest/DataBinding 异常 | `98_code_map.md`, `02_compile_resource.md`, `02_compile_manifest_obfuscation.md`, `02_compile_databinding.md` | `compiler/overlay`, `compiler/manifest`, `compiler/databinding` |
| 自定义编译器/编译交互协议 | `98_code_map.md`, `02_compile_custom_ui.md` | `compiler/custom/*`, `compiler/ui/*` |
| 部署失败/热更策略 | `98_code_map.md`, `03_deploy_core.md`, `03_deploy_complete.md`, `06_testing.md` §7.1 | `idea/.../JuggDeployerHelper.kt`, `idea/.../manager/TopLevelFlowTest` |
| 常量变化重编译异常（const ref） | `98_code_map.md`, `03_deploy_const_ref.md`, `02_compile_core.md` | `main/.../compiler/constref/*`, `deploy/DeployFileManager.kt`, `deploy/data/DeployDataGenerator.kt` |
| 影响分析/类变更传播 | `98_code_map.md`, `03_deploy_data_generator.md` | `deploy/data/DeployDataGenerator.kt` |
| **EffectedType 类型/merge 优先级/minify 移除检测** | `03_deploy_data_generator.md` §5.4-§5.7 | `EffectedClassNode.kt`, `DeployDataGenerator.kt`, `DeployDataDatabaseSqLiteHelper.kt`, `CompileEffectAnalyzer.kt` |
| **修改基类触发子类级联重编译**（static 方法误传播 Bug） | `03_deploy_data_generator.md` 第5节, `docs/task/recompile_cascade_bug_analysis.md` | `DeployDataDatabaseSqLiteHelper.kt` step 2（~826行）, `DeployDataDatabase.kt`（~454行） |
| JVMTI/运行时 agent 协同 | `98_code_map.md`, `03_runtime_jvmti.md` | `runtime/jvmti/*` |
| IDE 生命周期/运行配置 | `98_code_map.md`, `04_engineering_ide.md` | `idea/.../JuggManager.kt`, `JuggRunConfiguration.kt` |
| Gradle 项目信息与依赖读取 | `98_code_map.md`, `04_engineering_project.md` | `gradle/script/GradleProjectInfoReader.kt` |
| 兼容层（AS 版本适配） | `98_code_map.md`, `04_engineering_compat.md` | `deploy_compat/*/AsDeployerCompat.kt` |
| MCP 工具设计与调用 | `98_code_map.md`, `08_mcp_tools_list.md`, `08_mcp_design.md` | `ai/mcp/McpToolInvoker.kt`, `ai/mcp/actions/*`, `ai/mcp/util/CrashDetector.kt`, `ai/mcp/util/LastDeployTimestampRegistry.kt` |
| jugg CLI 子命令使用 / **新增或修改 CLI 参数** | `08_cli_tools_list.md` | `docs/skills/jugg-android-dev-loop/scripts/jugg.py` |
| MCP UI 布局验证设计（公开工具边界 / 证据链） | `98_code_map.md`, `08_mcp_layout_verify_design.md` | `McpToolActionRegistry.kt`, `LayoutDumpHelper.kt`, `UiFindMcpToolAction.kt`, `EvalViewMcpToolAction.kt`, `TapMcpToolAction.kt` |
| MCP 工具测试与回归 | `98_code_map.md`, `08_mcp_tools_list.md` | `ai/mcp/actions/McpToolActionRegistry.kt`, `ai/mcp/actions/*` |
| MCP UI 验证盲测 / view-inspect | `98_code_map.md`, `08_mcp_layout_verify_design.md`, `08_mcp_ui_verify_checklist.md` | `ai/mcp/actions/*` |
| figma-layout-verify 内部算法（关系提取/IoU 匹配/容差） | `08_mcp_figma_layout_verify_internals.md` | `ai/mcp/layout/RelationExtractor.kt`, `ElementMatcher.kt`, `RelationVerifier.kt` |
| 工具类能力（apk/git/logger/server） | `98_code_map.md`, `05_utilities.md` | `main/.../apk`, `main/.../git`, `main/.../logger`, `main/.../server` |
| **release 增量编译后注解/反射/类引用 crash** | `98_code_map.md`, `02_compile_manifest_obfuscation.md`, `09_plugin_runtime_debug.md` | `DexObfuscator.kt`, `DexMinifyCompiler.kt` |
| **插件运行时排查**（IDE 卡顿 / 启动期卡死 / 编译异常 / DB 问题） | `09_plugin_runtime_debug.md`, `04_engineering_ide.md`, `03_deploy_const_ref.md` | `JuggPathManager`, `DeployFileManager`, `TaskRunnerManager`, `ConstRefEngine` |
| 知识库维护 / 专题文档重整 | `97_maintenance_manual.md`, `99_index.md`, `98_code_map.md` | `docs/ai_knowledge/*` |

---

## 4. 专题文档一览

| 文档 | 聚焦点 |
|------|--------|
| `01_architecture.md` | 分层架构与核心链路 |
| `02_compile_core.md` | 增量编译主流程与阶段编排 |
| `02_compile_source.md` | Java/Kotlin/Dex 编译链 |
| `02_compile_resource.md` | 资源编译与 aapt2 link |
| `02_compile_databinding.md` | DataBinding/ViewBinding 增量处理 |
| `02_compile_manifest_obfuscation.md` | Manifest 合并与混淆映射 |
| `02_compile_custom_ui.md` | 自定义编译器与编译交互协议 |
| `03_deploy_core.md` | install/code swap/full swap 核心机制 |
| `03_deploy_const_ref.md` | 常量引用影响分析与常量重编译排查手册 |
| `03_deploy_data_generator.md` | 影响分析与部署数据生成 |
| `03_deploy_complete.md` | 从 Run 到部署完成的端到端流程 |
| `03_runtime_jvmti.md` | JVMTI agent 与部署协同 |
| `04_engineering_project.md` | 项目模型与 Gradle 信息读取 |
| `04_engineering_ide.md` | IDE 生命周期、运行配置、任务调度 |
| `04_engineering_compat.md` | AS 版本兼容层与命令行模块 |
| `05_utilities.md` | apk/git/logger/server/platform 等公共能力 |
| `06_testing.md` | 测试策略与 TDD：L1/L2/L3 分层、选型、deploy/run 落点、testcase、DeployDataGeneratorTest 模式 |
| `06_android_test.md` | app androidTest 支持：BuildTarget、test APK 识别、synthetic ModuleInfo、增量编译、gutter/RunConfig、`am instrument`、logcat 捕获与 method 归类、SM Test Runner、rerun failed 与定向测试 |
| `08_mcp_design.md` | MCP 协议分层与设计约束（§7 引用 `08_mcp_layout_verify_design.md`） |
| `08_mcp_layout_verify_design.md` | UI 布局验证设计：公开工具边界、证据链、单位流转、未注册 action 风险 |
| `08_mcp_ui_verify_checklist.md` | MCP UI 验证执行清单：页面边界、expected/actual 证据、selector、单位换算与报告口径 |
| `08_mcp_tools_list.md` | MCP 工具完整参数清单（18 个注册工具、通用行为、错误码） |
| `08_cli_tools_list.md` | `jugg` CLI（MCP 封装层）子命令参数与行为差异 |
| `08_mcp_figma_layout_verify_internals.md` | figma-layout-verify 内部算法：Figma JSON 解析、间距/对齐关系提取、IoU 元素匹配、容差验证 |
| `09_plugin_runtime_debug.md` | 运行时排查手册：目录结构、日志分析、IDE freeze 证据保全、高频问题根因、TDD 修复流程 |
| `97_maintenance_manual.md` | AI 知识库维护手册：专题文档整理标准、结构模板、密度控制、自审清单 |

---

## 5. 检索策略（强约束）

- 禁止一次性加载全量文档。
- 先读目录型文档（`99/98`），后读专题型文档。
- 每次只展开与当前任务直接相关的小节。
- 涉及接口能力时，优先检查对应实现文件而非仅看文档描述。

---

## 6. 维护约定

- 发生类名/路径变更：至少同步 `98_code_map.md`（路径同步规则详见 `98_code_map.md §6`）。
- 维护或重整专题文档时，先按 `97_maintenance_manual.md` 的质量标准与自审清单执行。
- **新增专题文档**：必须同时在第3节添加任务类型行、在第4节添加文档描述行。
- 更新已有专题时，直接描述当前最新实现；不要用“某日期起”这类时间分界来表达当前行为。
- 若暂未同步文档：在结论中标注"以代码为准"。
- **MCP/CLI 行为变更**：同步规则见 `08_mcp_design.md §9`。
