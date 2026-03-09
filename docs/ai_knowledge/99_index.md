# Jugg AI 知识库总导航

> 最后核对：2026-03-09  
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 入口文档（优先级最高）

| 文档 | 用途 |
|------|------|
| `00_overview.md` | 3 分钟建立项目全局认知 |
| `97_ai_usage.md` | 任务路由与最小必读集 |
| `98_code_map.md` | 路径/类名快速定位 |

---

## 2. 专题文档导航

### 2.1 架构

| 文档 | 聚焦点 |
|------|--------|
| `01_architecture.md` | 分层架构与核心链路 |

### 2.2 编译系统（`02_*`）

| 文档 | 聚焦点 |
|------|--------|
| `02_compile_core.md` | 增量编译主流程与阶段编排 |
| `02_compile_source.md` | Java/Kotlin/Dex 编译链 |
| `02_compile_resource.md` | 资源编译与 aapt2 link |
| `02_compile_databinding.md` | DataBinding/ViewBinding 增量处理 |
| `02_compile_manifest_obfuscation.md` | Manifest 合并与混淆映射 |
| `02_compile_custom_ui.md` | 自定义编译器与编译交互协议 |

### 2.3 部署与运行时（`03_*`）

| 文档 | 聚焦点 |
|------|--------|
| `03_deploy_core.md` | install/code swap/full swap 核心机制 |
| `03_deploy_const_ref.md` | 常量引用影响分析与常量重编译排查手册 |
| `03_deploy_data_generator.md` | 影响分析与部署数据生成 |
| `03_deploy_complete.md` | 从 Run 到部署完成的端到端流程 |
| `03_runtime_jvmti.md` | JVMTI agent 与部署协同 |

### 2.4 工程化（`04_*` + `05`）

| 文档 | 聚焦点 |
|------|--------|
| `04_engineering_project.md` | 项目模型与 Gradle 信息读取 |
| `04_engineering_ide.md` | IDE 生命周期、运行配置、任务调度 |
| `04_engineering_compat.md` | AS 版本兼容层与命令行模块 |
| `05_utilities.md` | apk/git/logger/server/platform 等公共能力 |

### 2.5 MCP（`08_*`）

| 文档 | 聚焦点 |
|------|--------|
| `08_mcp_design.md` | MCP 协议分层与设计约束 |
| `08_mcp_usage.md` | MCP 工具清单、参数与排查 |
| `08_mcp_test_case.md` | MCP 全量测试用例分组执行与验收标准 |
| `08_mcp_test_case_layout_verify.md` | layout_verify 盲测评估题目（60 题） |
| `08_mcp_test_case_eval_view.md` | eval_view 盲测评估题目（30 题） |

---

## 3. 按任务快速跳转

| 你要做什么 | 先读 |
|------------|------|
| 找类路径/目录 | `98_code_map.md` |
| 修改编译策略 | `02_compile_core.md`, `02_compile_source.md` |
| 修改部署行为 | `03_deploy_core.md`, `03_deploy_complete.md` |
| 排查常量变化触发重编译 | `03_deploy_const_ref.md`, `02_compile_core.md` |
| 排查类变更传播 | `03_deploy_data_generator.md` |
| 改 IDE 初始化/Run 配置 | `04_engineering_ide.md` |
| 改 Gradle 项目信息读取 | `04_engineering_project.md` |
| 改 AS 兼容层 | `04_engineering_compat.md` |
| 新增/调整 MCP 工具 | `08_mcp_usage.md`, `08_mcp_design.md` |
| 执行 MCP 工具回归测试 | `08_mcp_test_case.md`, `08_mcp_usage.md` |
| 执行 layout_verify 盲测评估 | `08_mcp_test_case_layout_verify.md` |
| 执行 eval_view 盲测评估 | `08_mcp_test_case_eval_view.md` |

---

## 4. 知识库维护规则

- 新增公共入口或模块：更新对应专题 + `98_code_map.md`。  
- 路径变更：优先更新 `98_code_map.md`。  
- 导航变化：同步更新本页与 `97_ai_usage.md`。  
- 发现文档滞后：回答中明确“以代码为准”，并补充待同步项。
