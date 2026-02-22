# Jugg 技术文档 - 总索引

---

## 📚 文档导航

### 🎯 快速开始
| 文档 | 说明 | 适合人群 |
|------|------|---------|
| [00_overview.md](00_overview.md) | 项目概览 | 所有人 |
| [01_architecture.md](01_architecture.md) | 架构设计 | 开发者 |

### 🔧 核心模块

#### 编译系统（6 个文档）
| 文档 | 说明 | 关键类 |
|------|------|--------|
| [02_compile_core.md](02_compile_core.md) | 编译系统核心架构 | `JuggCompiler`, `BaseCompiler`, `CompileTask` |
| [02_compile_source.md](02_compile_source.md) | 源码编译器 | `JavaCompiler`, `KotlinCompiler`, `DexCompiler` |
| [02_compile_resource.md](02_compile_resource.md) | 资源编译器 | `ResourceCompiler`, `Aapt2DaemonInvoker` |
| [02_compile_databinding.md](02_compile_databinding.md) | DataBinding/ViewBinding | `DataBindingGenBaseClassesCompiler`, `DataBindingGenMapperCompiler` |
| [02_compile_manifest_obfuscation.md](02_compile_manifest_obfuscation.md) | Manifest 和混淆 | `AndroidManifestCompiler`, `ClassMinifyCompiler` |
| [02_compile_custom_ui.md](02_compile_custom_ui.md) | 自定义编译器和 UI | `CustomCompilerManager`, `CompileUiHandler` |

#### 部署系统（3 个文档）
| 文档 | 说明 | 关键类 |
|------|------|--------|
| [03_deploy_core.md](03_deploy_core.md) | 核心部署机制 | `JuggDeployer`, `DeployFileManager` |
| [03_deploy_data_generator.md](03_deploy_data_generator.md) | 增量影响分析与类结构变更检测 | `DeployDataGenerator`, `ClassNodeComparator`, `DeployDataDatabase` |
| [03_deploy_complete.md](03_deploy_complete.md) | 完整部署流程 | `IncrementalDeployHelper`, `DeployHistoryManager` |

#### 运行时（1 个文档）
| 文档 | 说明 | 关键类 |
|------|------|--------|
| [03_runtime_jvmti.md](03_runtime_jvmti.md) | JVMTI/Overlay 运行时 | `agent.cpp` |

#### 工程化（3 个文档）
| 文档 | 说明 | 关键类 |
|------|------|--------|
| [04_engineering_project.md](04_engineering_project.md) | 项目管理与 Gradle 集成 | `JuggProjectInfo`, `GradleProjectInfoReader` |
| [04_engineering_ide.md](04_engineering_ide.md) | IDE 插件层 | `JuggManager`, `JuggLoader` |
| [04_engineering_compat.md](04_engineering_compat.md) | 兼容层 | `AsDeployerCompat`, `CmdLine` |

#### 辅助模块（1 个文档）
| 文档 | 说明 | 关键类 |
|------|------|--------|
| [05_utilities.md](05_utilities.md) | 辅助模块 | `ApkFileModifier`, `GitManager`, `JuggLogger` |

#### MCP（2 个文档）
| 文档 | 说明 | 关键类 |
|------|------|--------|
| [08_mcp_design.md](08_mcp_design.md) | MCP 架构设计（设计稿） | `McpToolInvoker`, `McpToolRegistry`, `McpToolAction` |
| [08_mcp_usage.md](08_mcp_usage.md) | MCP 工具使用说明 | 工具参数与错误码（精简版，无 curl 示例） |

#### 代码索引（1 个文档）
| 文档 | 说明 |
|------|------|
| [98_code_map.md](98_code_map.md) | 代码路径速查表 |

---

## 🔍 按功能查找

### 编译相关

- Java 编译: [02_compile_source.md](02_compile_source.md) → `JavaCompiler`
- Kotlin 编译: [02_compile_source.md](02_compile_source.md) → `KotlinCompiler`
- Dex 编译: [02_compile_source.md](02_compile_source.md) → `DexCompiler`
- 资源编译: [02_compile_resource.md](02_compile_resource.md) → `ResourceCompiler`
- DataBinding: [02_compile_databinding.md](02_compile_databinding.md) → `DataBindingGenMapperCompiler`
- ViewBinding: [02_compile_databinding.md](02_compile_databinding.md) → `DataBindingGenBaseClassesCompiler`
- Manifest: [02_compile_manifest_obfuscation.md](02_compile_manifest_obfuscation.md) → `AndroidManifestCompiler`
- 混淆支持: [02_compile_manifest_obfuscation.md](02_compile_manifest_obfuscation.md) → `ClassMinifyCompiler`, `DexMinifyCompiler`
- 自定义编译器: [02_compile_custom_ui.md](02_compile_custom_ui.md) → `CustomCompilerManager`

### 部署相关

- APK 安装: [03_deploy_core.md](03_deploy_core.md) → `JuggDeployer.install`
- 代码热修: [03_deploy_core.md](03_deploy_core.md) → `JuggDeployer.codeSwap`
- 全量热修: [03_deploy_core.md](03_deploy_core.md) → `JuggDeployer.fullSwap`
- 类结构比较: [03_deploy_data_generator.md](03_deploy_data_generator.md) → `ClassNodeComparator`
- 影响分析: [03_deploy_data_generator.md](03_deploy_data_generator.md) → `DeployDataDatabase.getEffectedSourceAndClass`
- 内联检测: [03_deploy_data_generator.md](03_deploy_data_generator.md) → `InlineMethodDetector`
- 部署数据生成: [03_deploy_data_generator.md](03_deploy_data_generator.md) → `DeployDataGenerator.buildDeployData`
- 增量部署: [03_deploy_complete.md](03_deploy_complete.md) → `IncrementalDeployHelper`
- 部署历史: [03_deploy_complete.md](03_deploy_complete.md) → `DeployHistoryManager`

### 项目管理相关

- 项目信息: [04_engineering_project.md](04_engineering_project.md) → `JuggProjectInfo`
- Gradle 信息读取: [04_engineering_project.md](04_engineering_project.md) → `GradleProjectInfoReader`
- 依赖差异计算: [04_engineering_project.md](04_engineering_project.md) → `GradleDependencyDiffer`, `DependencyChangeManagerByGradle`
- Gradle 编译: [04_engineering_project.md](04_engineering_project.md) → `LocalGradleCompileClient`

### IDE 集成相关

- 插件加载: [04_engineering_ide.md](04_engineering_ide.md) → `JuggLoader`
- 热更新: [04_engineering_ide.md](04_engineering_ide.md) → `JuggHotUpdateManager`
- 运行配置: [04_engineering_ide.md](04_engineering_ide.md) → `JuggRunConfiguration`
- 事件监听: [04_engineering_ide.md](04_engineering_ide.md) → `JuggGradleSyncListener`
- 文件变化: [04_engineering_ide.md](04_engineering_ide.md) → `FileChangesDetector`
- 部署状态: [04_engineering_ide.md](04_engineering_ide.md) → `DeployStateManager`

### MCP 相关

- MCP 架构入口: [08_mcp_design.md](08_mcp_design.md) → `McpToolInvoker`, `McpBaseInvoker`
- 工具参数与错误码: [08_mcp_usage.md](08_mcp_usage.md)
- 设备相关工具: [08_mcp_usage.md](08_mcp_usage.md) → `device_list`, `activity_stack`, `screenshot`, `record`, `layout_dump`
- 编译相关工具: [08_mcp_usage.md](08_mcp_usage.md) → `compile_and_deploy`, `force_gradle_compile`, `get_compile_status`

---

## 📊 统计信息

### 文档统计

| 类型 | 数量 | 说明 |
|------|------|------|
| 概览文档 | 2 | overview, architecture |
| 编译文档 | 6 | compile_* 系列 |
| 部署文档 | 3 | deploy_* 系列 |
| 运行时文档 | 1 | runtime_jvmti |
| 工程文档 | 3 | engineering_* 系列 |
| 辅助文档 | 1 | utilities |
| MCP 文档 | 2 | mcp_design, mcp_usage |
| AI 使用指引 | 1 | ai_usage |
| 代码索引 | 1 | code_map |
| 总索引 | 1 | index |
| **总计** | **21** | |

### 代码覆盖（2026-02-22 复核）

> 口径：仅统计生产代码（`src/main` + `idea/src/ide_entry`），不含测试与构建产物。

| 模块 | 文件数 | 已分析 | 覆盖率 |
|------|--------|--------|--------|
| main/compiler | 72 | 72 | 100% |
| main/deploy | 38 | 38 | 100% |
| main/project | 20 | 20 | 100% |
| main/gradle | 22 | 22 | 100% |
| idea（main + ide_entry） | 71 | 71 | 100% |
| deploy_compat | 15 | 15 | 100% |
| platform_compat | 21 | 21 | 100% |
| cmd_line | 14 | 14 | 100% |
| custom_compilers | 4 | 4 | 100% |
| main/辅助模块（aapt2/apk/git/logger/mcp/platform/server/ide） | 76 | 76 | 100% |
| **总计** | **353** | **353** | **100%** |

---

## 🔗 外部资源

- GitHub: https://github.com/SickWorm/ARRTI
- 更新日志: [change_log](../../change_log)
- Android Gradle Plugin: https://developer.android.com/studio/build
- Kotlin Compiler: https://kotlinlang.org/docs/compiler-reference.html
- JVMTI: https://docs.oracle.com/javase/8/docs/platform/jvmti/jvmti.html

---

## 📝 文档维护约定

- `97-99` 文档属于 AI 检索入口，优先保证“类名与路径”准确。
- 类重命名/目录变更后，至少同步更新 `98_code_map.md` 与本索引。
- 若发现文档与代码冲突，在文档中明确注明“以代码为准”。
