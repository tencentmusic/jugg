# Jugg 技术文档 - 总索引

> 创建时间: 2025-01-20
> 最后更新: 2025-01-20
> 文档版本: v1.0
---

## 📚 文档导航

### 🎯 快速开始
| 文档 | 说明 | 适合人群 |
|------|------|---------|
| [00_overview.md](00_overview.md) | 项目概览 | 所有人 |
| [01_architecture.md](01_architecture.md) | 架构设计 | 开发者 |

### 🔧 核心模块

#### 编译系统 (6 个文档)
| 文档 | 说明 | 关键类 |
|------|------|--------|
| [02_compile_core.md](02_compile_core.md) | 编译系统核心架构 | `JuggCompiler`, `BaseCompiler`, `CompileTask` |
| [02_compile_source.md](02_compile_source.md) | 源码编译器 | `JavaCompiler`, `KotlinCompiler`, `DexCompiler` |
| [02_compile_resource.md](02_compile_resource.md) | 资源编译器 | `ResourceCompiler`, `Aapt2Invoker` |
| [02_compile_databinding.md](02_compile_databinding.md) | DataBinding/ViewBinding | `DataBindingGenBaseClassesCompiler`, `DataBindingGenMapperCompiler` |
| [02_compile_manifest_obfuscation.md](02_compile_manifest_obfuscation.md) | Manifest 和混淆 | `ManifestCompiler`, `ObfuscationCompiler` |
| [02_compile_custom_ui.md](02_compile_custom_ui.md) | 自定义编译器和 UI | `CustomCompilerManager`, `CompileUiHandler` |

#### 部署系统 (3 个文档)

| 文档 | 说明 | 关键类 |
|------|------|--------|
| [03_deploy_core.md](03_deploy_core.md) | 核心部署机制 | `JuggDeployer`, `DeployFileManager` |
| [03_deploy_data_generator.md](03_deploy_data_generator.md) | 增量影响分析与类结构变更检测 | `DeployDataGenerator`, `ClassNodeComparator`, `DeployDataDatabase` |
| [03_deploy_complete.md](03_deploy_complete.md) | 完整部署流程 | `IncrementalDeployHelper`, `DeployHistoryManager` |

#### 运行时 (1 个文档)
| 文档 | 说明 | 关键类 |
|------|------|--------|
| [03_runtime_jvmti.md](03_runtime_jvmti.md) | JVMTI/Overlay 运行时 | `agent.cpp` |

#### 工程化 (3 个文档)
| 文档 | 说明 | 关键类 |
|------|------|--------|
| [04_engineering_project.md](04_engineering_project.md) | 项目管理与 Gradle 集成 | `JuggProjectInfo`, `GradleProjectInfoReader` |
| [04_engineering_ide.md](04_engineering_ide.md) | IDE 插件层 | `JuggManager`, `JuggLoader` |
| [04_engineering_compat.md](04_engineering_compat.md) | 兼容层 | `AsDeployerCompat`, `CmdLine` |

#### 辅助模块 (1 个文档)
| 文档 | 说明 | 关键类 |
|------|------|--------|
| [05_utilities.md](05_utilities.md) | 辅助模块 | `ApkFileModifier`, `GitManager`, `JuggLogger` |

#### 代码索引 (1 个文档)
| 文档 | 说明 |
|------|------|
| [98_code_map.md](98_code_map.md) | 代码路径速查表 |

## 🔍 按功能查找

### 编译相关

- **Java 编译**: [02_compile_source.md](02_compile_source.md) → JavaCompiler
- **Kotlin 编译**: [02_compile_source.md](02_compile_source.md) → KotlinCompiler
- **Dex 编译**: [02_compile_source.md](02_compile_source.md) → DexCompiler
- **资源编译**: [02_compile_resource.md](02_compile_resource.md) → ResourceCompiler
- **DataBinding**: [02_compile_databinding.md](02_compile_databinding.md) → DataBindingGenMapperCompiler
- **ViewBinding**: [02_compile_databinding.md](02_compile_databinding.md) → DataBindingGenBaseClassesCompiler
- **Manifest**: [02_compile_manifest_obfuscation.md](02_compile_manifest_obfuscation.md) → ManifestCompiler
- **混淆支持**: [02_compile_manifest_obfuscation.md](02_compile_manifest_obfuscation.md) → ObfuscationCompiler
- **自定义编译器**: [02_compile_custom_ui.md](02_compile_custom_ui.md) → CustomCompilerManager

### 部署相关

- **APK 安装**: [03_deploy_core.md](03_deploy_core.md) → JuggDeployer.install
- **代码热修**: [03_deploy_core.md](03_deploy_core.md) → JuggDeployer.codeSwap
- **热点修**: [03_deploy_core.md](03_deploy_core.md) → JuggDeployer.fullSwap
- **类结构比较**: [03_deploy_data_generator.md](03_deploy_data_generator.md) → ClassNodeComparator
- **影响分析**: [03_deploy_data_generator.md](03_deploy_data_generator.md) → DeployDataDatabase.getEffectedSourceAndClass
- **内联检测**: [03_deploy_data_generator.md](03_deploy_data_generator.md) → InlineMethodDetector
- **部署数据生成**: [03_deploy_data_generator.md](03_deploy_data_generator.md) → DeployDataGenerator.buildDeployData
- **增量部署**: [03_deploy_complete.md](03_deploy_complete.md) → IncrementalDeployHelper
- **部署历史**: [03_deploy_complete.md](03_deploy_complete.md) → DeployHistoryManager
- **文件管理**: [03_deploy_core.md](03_deploy_core.md) → DeployFileManager

### 项目管理相关

- **项目信息**: [04_engineering_project.md](04_engineering_project.md) → JuggProjectInfo
- **Gradle 集成**: [04_engineering_project.md](04_engineering_project.md) → GradleProjectInfoReader
- **依赖解析**: [04_engineering_project.md](04_engineering_project.md) → DependencyResolver
- **Gradle 编译**: [04_engineering_project.md](04_engineering_project.md) → LocalGradleCompileClient

### IDE 集成相关

- **插件加载**: [04_engineering_ide.md](04_engineering_ide.md) → JuggLoader
- **热更新**: [04_engineering_ide.md](04_engineering_ide.md) → JuggHotUpdateManager
- **运行配置**: [04_engineering_ide.md](04_engineering_ide.md) → JuggRunConfiguration
- **事件监听**: [04_engineering_ide.md](04_engineering_ide.md) → JuggGradleSyncListener
- **文件变化**: [04_engineering_ide.md](04_engineering_ide.md) → FileChangesDetector
- **部署状态**: [04_engineering_ide.md](04_engineering_ide.md) → DeployStateManager

### 兼容性相关

- **Android Studio 兼容**: [04_engineering_compat.md](04_engineering_compat.md) → AsDeployerCompat
- **平台 Mock**: [04_engineering_compat.md](04_engineering_compat.md) → platform_compat
- **命令行工具**: [04_engineering_compat.md](04_engineering_compat.md) → CmdLine
- **自定义编译器示例**: [04_engineering_compat.md](04_engineering_compat.md) → custom_compilers

### 辅助功能相关

- **APK 操作**: [05_utilities.md](05_utilities.md) → ApkFileModifier
- **AAPT2 调用**: [05_utilities.md](05_utilities.md) → Aapt2DaemonInvoker
- **Git 集成**: [05_utilities.md](05_utilities.md) → GitManager
- **日志系统**: [05_utilities.md](05_utilities.md) → JuggLogger
- **MCP 通信**: [05_utilities.md](05_utilities.md) → McpLocalServer
- **远程编译**: [05_utilities.md](05_utilities.md) → JuggServer
---

## 🎓 学习路径

### 初学者路径

1. **了解项目** → [00_overview.md](00_overview.md)
2. **理解架构** → [01_architecture.md](01_architecture.md)
3. **编译系统** → [02_compile_core.md](02_compile_core.md)
4. **部署系统** → [03_deploy_core.md](03_deploy_core.md)
5. **影响分析** → [03_deploy_data_generator.md](03_deploy_data_generator.md)

### 开发者路径

1. **项目管理** → [04_engineering_project.md](04_engineering_project.md)
2. **IDE 集成** → [04_engineering_ide.md](04_engineering_ide.md)
3. **兼容层** → [04_engineering_compat.md](04_engineering_compat.md)
4. **辅助模块** → [05_utilities.md](05_utilities.md)

### 深入研究路径

1. **源码编译** → [02_compile_source.md](02_compile_source.md)
2. **资源编译** → [02_compile_resource.md](02_compile_resource.md)
3. **DataBinding** → [02_compile_databinding.md](02_compile_databinding.md)
4. **混淆支持** → [02_compile_manifest_obfuscation.md](02_compile_manifest_obfuscation.md)
5. **影响分析** → [03_deploy_data_generator.md](03_deploy_data_generator.md)
6. **完整部署** → [03_deploy_complete.md](03_deploy_complete.md)
---

## 📊 统计信息

### 文档统计

| 类型 | 数量 | 总大小 |
|------|------|--------|
| 概览文档 | 2 | ~6 KB |
| 编译文档 | 6 | ~178 KB |
| 部署文档 | 3 | ~96 KB |
| 运行时文档 | 1 | ~6 KB |
| 工程文档 | 3 | ~84 KB |
| 辅助文档 | 1 | ~25 KB |
| 任务手册 | 1 | ~6 KB |
| 代码索引 | 1 | ~6 KB |
| 演进文档 | 1 | ~15 KB |
| **总计** | **21** | **~430 KB** |

### 代码覆盖

| 模块 | 文件数 | 已分析 | 覆盖率 |
|------|--------|--------|--------|
| main/compiler | 62 | 62 | 100% |
| main/deploy | 17 | 17 | 100% |
| main/project | 30 | 30 | 100% |
| idea/ | 50 | 50 | 100% |
| deploy_compat/ | 15 | 15 | 100% |
| platform_compat/ | 19 | 19 | 100% |
| cmd_line/ | 14 | 14 | 100% |
| custom_compilers/ | 4 | 4 | 100% |
| main/辅助模块 | 25 | 25 | 100% |
| **总计** | **236** | **236** | **100%** |
---

## 🔗 外部资源

### 官方资源
- **GitHub**: https://github.com/SickWorm/ARRTI
- **文档**: README.md
- **更新日志**: change_log/

### 相关技术

- **Android Gradle Plugin**: https://developer.android.com/studio/build
- **Kotlin Compiler**: https://kotlinlang.org/docs/compiler-reference.html
- **JVMTI**: https://docs.oracle.com/javase/8/docs/platform/jvmti/jvmti.html
- **JGit**: https://www.eclipse.org/jgit/
- **ASM**: https://asm.ow2.io/
---

## 📝 文档约定

### 文件命名

- `00-09`: 概览和架构
- `10-29`: 核心模块
- `30-49`: 工程化
- `50-69`: 辅助模块
- `70-89`: 其他
- `90-99`: 索引和附录

### 章节结构

1. **一、模块概述**: 模块职责和核心组件
2. **二、核心类**: 关键类的详细说明
3. **三、设计亮点**: 技术亮点和设计模式
4. **四、总结**: 关键技术点和扩展点
5. **附录**: 文件清单

### 代码示例

- 使用 Kotlin 语法高亮
- 包含关键注释
- 展示核心逻辑

---

## 🎯 快速参考

### 关键概念

| 概念 | 说明 | 文档 |
|------|------|------|
| **增量编译** | 只编译变化的文件 | [02_compile_core.md](02_compile_core.md) |
| **热修复** | 无需重启 App 即可更新代码 | [03_deploy_core.md](03_deploy_core.md) |
| **Overlay** | Android 11+ 的热修复机制 | [03_deploy_core.md](03_deploy_core.md) |
| **JuggProjectInfo** | 项目信息数据模型 | [04_engineering_project.md](04_engineering_project.md) |
| **AsDeployerCompat** | Android Studio 版本兼容层 | [04_engineering_compat.md](04_engineering_compat.md) |

### 关键流程

| 流程 | 说明 | 文档 |
|------|------|------|
| **编译流程** | 源码 → 字节码 → DEX | [02_compile_source.md](02_compile_source.md) |
| **部署流程** | DEX → Overlay → 热修复 | [03_deploy_complete.md](03_deploy_complete.md) |
| **Gradle 集成** | 读取项目信息 → 执行编译 | [04_engineering_project.md](04_engineering_project.md) |
| **插件加载** | 加载 JAR → 创建实例 → 初始化 | [04_engineering_ide.md](04_engineering_ide.md) |

### 关键技术

| 技术 | 说明 | 文档 |
|------|------|------|
| **JVMTI** | Java 虚拟机工具接口 | [03_deploy_core.md](03_deploy_core.md) |
| **ASM** | 字节码操作框架 | [02_compile_source.md](02_compile_source.md) |
| **JGit** | Git Java 实现 | [05_utilities.md](05_utilities.md) |
| **AAPT2** | Android 资源打包工具 | [02_compile_resource.md](02_compile_resource.md) |
| **Kotlin Compiler** | Kotlin 编译器 | [02_compile_source.md](02_compile_source.md) |

### 其他文件

| 文件            | 说明         | 文档                               |
|---------------|------------|----------------------------------|
| `docs/task`   | 任务计划存放位置   | [docs/task](../../docs/task)     |
| `docs/skills` | skill 存放位置 | [docs/skills](../../docs/skills) |
| `tools`       | 工具脚本存放位置   | [tools](../../tools)             |
| `change_log`  | 改动日志存放位置   | [tools](../../change_log)        |
