# AI 使用指引（任务路由版）

> 最后核对：2026-03-05
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
2. 再读对应专题文档（`02/03/04/05/08`）。  
3. 不确定时再读 `99_index.md` 做二次导航。  
4. 发现文档与代码不一致：**立即以代码为准**，并记录待同步项。

---

## 3. 任务类型 -> 最小必读集

| 任务类型 | 最小必读文档 | 代码入口（示例） |
|----------|--------------|------------------|
| 编译失败/回退策略 | `98_code_map.md`, `02_compile_core.md`, `02_compile_source.md` | `idea/.../JuggCompileHelper.kt`, `main/.../JuggCompiler.kt` |
| 资源/Manifest/DataBinding 异常 | `98_code_map.md`, `02_compile_resource.md`, `02_compile_manifest_obfuscation.md`, `02_compile_databinding.md` | `compiler/overlay`, `compiler/manifest`, `compiler/databinding` |
| 部署失败/热更策略 | `98_code_map.md`, `03_deploy_core.md`, `03_deploy_complete.md` | `idea/.../JuggDeployerHelper.kt`, `idea/.../JuggDeployer.kt` |
| 常量变化重编译异常（const ref） | `98_code_map.md`, `03_deploy_const_ref.md`, `02_compile_core.md` | `main/.../compiler/constref/*`, `deploy/DeployFileManager.kt`, `deploy/data/DeployDataGenerator.kt` |
| 影响分析/类变更传播 | `98_code_map.md`, `03_deploy_data_generator.md` | `deploy/data/DeployDataGenerator.kt` |
| IDE 生命周期/运行配置 | `98_code_map.md`, `04_engineering_ide.md` | `idea/.../JuggManager.kt`, `JuggRunConfiguration.kt` |
| Gradle 项目信息与依赖读取 | `98_code_map.md`, `04_engineering_project.md` | `gradle/script/GradleProjectInfoReader.kt` |
| 兼容层（AS 版本适配） | `98_code_map.md`, `04_engineering_compat.md` | `deploy_compat/*/AsDeployerCompat.kt` |
| MCP 工具设计与调用 | `98_code_map.md`, `08_mcp_usage.md`, `08_mcp_design.md` | `mcp/McpToolInvoker.kt`, `mcp/actions/*` |
| MCP 工具测试与回归 | `98_code_map.md`, `08_mcp_test_case.md`, `08_mcp_usage.md` | `mcp/actions/McpToolActionRegistry.kt`, `mcp/actions/*` |
| 工具类能力（apk/git/logger/server） | `98_code_map.md`, `05_utilities.md` | `main/.../apk|git|logger|server` |

---

## 4. 检索策略（强约束）

- 禁止一次性加载全量文档。
- 先读目录型文档（`98/99`），后读专题型文档。
- 每次只展开与当前任务直接相关的小节。
- 涉及接口能力时，优先检查对应实现文件而非仅看文档描述。

---

## 5. 维护约定

- 发生类名/路径变更：至少同步 `98_code_map.md`（路径同步规则详见 `98_code_map.md §6`）。
- 新增公共入口能力：同步对应专题文档与 `99_index.md`。
- 若暂未同步文档：在结论中标注”以代码为准”。
