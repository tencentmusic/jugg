# AI 使用指引（任务路由版）

> 最后核对：2026-03-23
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
| **单元测试 / TDD / 新增 testcase** | `06_testing.md` | `main/src/test/.../DeployDataGeneratorTest.kt`, `android_demo_project/.../testcase/` |
| 整体架构理解/模块划分 | `98_code_map.md`, `01_architecture.md` | - |
| 编译失败/回退策略 | `98_code_map.md`, `02_compile_core.md`, `02_compile_source.md` | `idea/.../JuggCompileHelper.kt`, `main/.../JuggCompiler.kt` |
| 资源/Manifest/DataBinding 异常 | `98_code_map.md`, `02_compile_resource.md`, `02_compile_manifest_obfuscation.md`, `02_compile_databinding.md` | `compiler/overlay`, `compiler/manifest`, `compiler/databinding` |
| 自定义编译器/编译交互协议 | `98_code_map.md`, `02_compile_custom_ui.md` | `compiler/customui/*` |
| 部署失败/热更策略 | `98_code_map.md`, `03_deploy_core.md`, `03_deploy_complete.md` | `idea/.../JuggDeployerHelper.kt`, `idea/.../JuggDeployer.kt` |
| 常量变化重编译异常（const ref） | `98_code_map.md`, `03_deploy_const_ref.md`, `02_compile_core.md` | `main/.../compiler/constref/*`, `deploy/DeployFileManager.kt`, `deploy/data/DeployDataGenerator.kt` |
| 影响分析/类变更传播 | `98_code_map.md`, `03_deploy_data_generator.md` | `deploy/data/DeployDataGenerator.kt` |
| **修改基类触发子类级联重编译**（static 方法误传播 Bug） | `03_deploy_data_generator.md` 第5节, `docs/task/recompile_cascade_bug_analysis.md` | `DeployDataDatabaseSqLiteHelper.kt` step 2（~826行）, `DeployDataDatabase.kt`（~454行） |
| JVMTI/运行时 agent 协同 | `98_code_map.md`, `03_runtime_jvmti.md` | `runtime/jvmti/*` |
| IDE 生命周期/运行配置 | `98_code_map.md`, `04_engineering_ide.md` | `idea/.../JuggManager.kt`, `JuggRunConfiguration.kt` |
| Gradle 项目信息与依赖读取 | `98_code_map.md`, `04_engineering_project.md` | `gradle/script/GradleProjectInfoReader.kt` |
| 兼容层（AS 版本适配） | `98_code_map.md`, `04_engineering_compat.md` | `deploy_compat/*/AsDeployerCompat.kt` |
| MCP 工具设计与调用 | `98_code_map.md`, `08_mcp_usage.md`, `08_mcp_design.md` | `mcp/McpToolInvoker.kt`, `mcp/actions/*` |
| MCP 工具测试与回归 | `98_code_map.md`, `08_mcp_test_case.md`, `08_mcp_usage.md` | `mcp/actions/McpToolActionRegistry.kt`, `mcp/actions/*` |
| MCP UI 验证盲测/layout_verify/eval_view | `98_code_map.md`, `08_mcp_test_case_ui_verify.md` | `mcp/actions/*` |
| MCP UI 验证评分（评估者专用） | `08_mcp_test_case_ui_verify_answer.md` | - |
| MCP UI 验证执行规范自检 | `08_mcp_ui_verify_checklist.md` | - |
| 工具类能力（apk/git/logger/server） | `98_code_map.md`, `05_utilities.md` | `main/.../apk|git|logger|server` |
| **release 增量编译后注解/反射/类引用 crash** | `98_code_map.md`, `02_compile_manifest_obfuscation.md`, `09_plugin_runtime_debug.md` | `DexObfuscator.kt`, `DexMinifyCompiler.kt` |
| **插件运行时排查**（IDE 卡顿 / 启动期卡死 / 编译异常 / DB 问题） | `09_plugin_runtime_debug.md`, `04_engineering_ide.md`, `03_deploy_const_ref.md` | `JuggPathManager`, `DeployFileManager`, `TaskRunnerManager`, `ConstRefEngine` |

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
| `06_testing.md` | TDD 单元测试：testcase 类规范、mock 基础设施、DeployDataGeneratorTest 模式 |
| `08_mcp_design.md` | MCP 协议分层与设计约束 |
| `08_mcp_usage.md` | MCP 工具清单、参数与排查 |
| `08_mcp_test_case.md` | MCP 全量测试用例分组执行与验收标准 |
| `08_mcp_test_case_ui_verify.md` | UI 验证盲测评估题目（layout_verify 60 题 + eval_view 30 题） |
| `08_mcp_test_case_ui_verify_answer.md` | UI 验证盲测统一答案表（评估者专用，agent 禁止读取） |
| `08_mcp_ui_verify_checklist.md` | UI 验证执行规范自检清单 |
| `09_plugin_runtime_debug.md` | 运行时排查手册：目录结构、日志分析、IDE freeze 证据保全、高频问题根因、TDD 修复流程 |

---

## 5. 检索策略（强约束）

- 禁止一次性加载全量文档。
- 先读目录型文档（`99/98`），后读专题型文档。
- 每次只展开与当前任务直接相关的小节。
- 涉及接口能力时，优先检查对应实现文件而非仅看文档描述。

---

## 6. 维护约定

- 发生类名/路径变更：至少同步 `98_code_map.md`（路径同步规则详见 `98_code_map.md §6`）。
- **新增专题文档**：必须同时在第3节添加任务类型行、在第4节添加文档描述行。
- 若暂未同步文档：在结论中标注”以代码为准”。
