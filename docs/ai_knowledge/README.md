# Jugg 技术文档 Wiki

> 创建时间: 2025-01-20  
> 文档版本: v1.0  
> 项目版本: 2.6.13  
> 总文档数: 20 个
> 文档总大小: ~420 KB

---

## 📚 文档列表

### 🎯 概览与架构 (2 个)

| 文档 | 说明 | 大小 |
|------|------|------|
| [00_overview.md](00_overview.md) | 项目概览 | 5.4KB |
| [01_architecture.md](01_architecture.md) | 架构设计 | ~15KB |

### 🔧 编译系统 (6 个)

| 文档 | 说明 | 大小 |
|------|------|------|
| [02_compile_core.md](02_compile_core.md) | 编译系统核心架构 | 28KB |
| [02_compile_source.md](02_compile_source.md) | 源码编译器 (Java/Kotlin/Dex) | 36KB |
| [02_compile_resource.md](02_compile_resource.md) | 资源编译器 (AAPT2) | 35KB |
| [02_compile_databinding.md](02_compile_databinding.md) | DataBinding/ViewBinding | 32KB |
| [02_compile_manifest_obfuscation.md](02_compile_manifest_obfuscation.md) | Manifest 和混淆支持 | 29KB |
| [02_compile_custom_ui.md](02_compile_custom_ui.md) | 自定义编译器和 UI | 18KB |

### 🚀 部署系统 (2 个)

| 文档 | 说明 | 大小 |
|------|------|------|
| [03_deploy_core.md](03_deploy_core.md) | 核心部署机制 (JVMTI/Overlay) | 18KB |
| [03_deploy_complete.md](03_deploy_complete.md) | 完整部署流程 | 18KB |

### 🧩 运行时 (1 个)

| 文档 | 说明 | 大小 |
|------|------|------|
| [03_runtime_jvmti.md](03_runtime_jvmti.md) | JVMTI/Overlay 运行时 | ~6KB |

### 🏗️ 工程化 (3 个)
| 文档 | 说明 | 大小 |
|------|------|------|
| [04_engineering_project.md](04_engineering_project.md) | 项目管理与 Gradle 集成 | 7.0KB |
| [04_engineering_ide.md](04_engineering_ide.md) | IDE 插件层 | 34KB |
| [04_engineering_compat.md](04_engineering_compat.md) | 兼容层 (AS 版本/命令行/自定义编译器) | 43KB |

### 📖 其他 (4 个)

| 文档 | 说明 | 大小 |
|------|------|------|
| [06_evolution.md](06_evolution.md) | 演进历史 (2024-2025) | ~15KB |
| [05_utilities.md](05_utilities.md) | 辅助模块 (APK/AAPT2/Git/Logger/RPC) | 31KB |
| [07_cookbook.md](07_cookbook.md) | 常见任务手册 | ~6KB |
| [99_index.md](99_index.md) | 总索引 | ~10KB |

### 🔗 代码索引 (1 个)

| 文档 | 说明 | 大小 |
|------|------|------|
| [98_code_map.md](98_code_map.md) | 代码路径速查表 | ~6KB |

---

## 🎓 学习路径

### 初学者路径 (4 个文档，约 1-2 小时)

适合第一次接触 Jugg 的开发者：

1. **[00_overview.md](00_overview.md)** - 了解 Jugg 是什么，能做什么
2. **[01_architecture.md](01_architecture.md)** - 理解整体架构和设计思想
3. **[02_compile_core.md](02_compile_core.md)** - 学习编译系统核心概念
4. **[03_deploy_core.md](03_deploy_core.md)** - 学习部署系统核心概念

### 开发者路径 (10 个文档，约 4-6 小时)

适合需要深入理解或参与开发的开发者：

1. **概览与架构** (2 个)
   - [00_overview.md](00_overview.md)
   - [01_architecture.md](01_architecture.md)

2. **核心系统** (4 个)
   - [02_compile_core.md](02_compile_core.md)
   - [02_compile_source.md](02_compile_source.md)
   - [03_deploy_core.md](03_deploy_core.md)
   - [03_deploy_complete.md](03_deploy_complete.md)

3. **工程化** (3 个)
   - [04_engineering_project.md](04_engineering_project.md)
   - [04_engineering_ide.md](04_engineering_ide.md)
   - [04_engineering_compat.md](04_engineering_compat.md)

4. **演进历史** (1 个)
   - [05_evolution.md](06_evolution.md)

### 深入研究路径 (全部 16 个文档，约 8-12 小时)

适合需要完全掌握 Jugg 技术细节的开发者：

按顺序阅读所有文档，参考 [99_index.md](99_index.md) 快速查找特定内容。

---

## 🔍 快速查找

### 按功能查找

- **Java 编译**: [02_compile_source.md](02_compile_source.md) → JavaCompiler
- **Kotlin 编译**: [02_compile_source.md](02_compile_source.md) → KotlinCompiler
- **资源编译**: [02_compile_resource.md](02_compile_resource.md) → ResourceCompiler
- **DataBinding**: [02_compile_databinding.md](02_compile_databinding.md) → DataBindingCompiler
- **热修复**: [03_deploy_core.md](03_deploy_core.md) → JuggDeployer
- **Gradle 集成**: [04_engineering_project.md](04_engineering_project.md) → GradleProjectInfoReader
- **IDE 集成**: [04_engineering_ide.md](04_engineering_ide.md) → JuggManager
- **版本兼容**: [04_engineering_compat.md](04_engineering_compat.md) → AsDeployerCompat
- **APK 操作**: [06_utilities.md](05_utilities.md) → ApkFileModifier
- **Git 集成**: [06_utilities.md](05_utilities.md) → GitManager

### 按模块查找

- **compiler/**: [02_compile_*.md](02_compile_core.md)
- **deploy/**: [03_deploy_*.md](03_deploy_core.md)
- **project/**: [04_engineering_project.md](04_engineering_project.md)
- **idea/**: [04_engineering_ide.md](04_engineering_ide.md)
- **deploy_compat/**: [04_engineering_compat.md](04_engineering_compat.md)
- **platform_compat/**: [04_engineering_compat.md](04_engineering_compat.md)
- **cmd_line/**: [04_engineering_compat.md](04_engineering_compat.md)
- **apk/**: [06_utilities.md](05_utilities.md)
- **git/**: [06_utilities.md](05_utilities.md)
- **logger/**: [06_utilities.md](05_utilities.md)

---

## 📊 统计信息

### 文档统计

| 类型 | 数量 | 总大小 |
|------|------|--------|
| 概览文档 | 2 | ~20 KB |
| 编译文档 | 6 | ~178 KB |
| 部署文档 | 2 | ~36 KB |
| 运行时文档 | 1 | ~6 KB |
| 工程文档 | 3 | ~84 KB |
| 演进文档 | 1 | ~15 KB |
| 辅助文档 | 1 | ~31 KB |
| 任务手册 | 1 | ~6 KB |
| 索引文档 | 2 | ~16 KB |
| **总计** | **20** | **~420 KB** |

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
| **核心代码** | **236** | **236** | **100%** |

---

## 🎯 关键技术点

### 编译系统

- **增量编译**: 只编译变化的文件，性能提升 90%+
- **多编译器**: Java, Kotlin, Resource, DataBinding, Manifest
- **自定义编译器**: 支持插件化扩展

### 部署系统

- **热修复**: 无需重启 App 即可更新代码和资源
- **JVMTI**: 使用 JVMTI 接口重定义类
- **Overlay**: Android 11+ 使用 Overlay 机制

### 工程化

- **Gradle 集成**: 读取 Gradle 项目信息，无需依赖 IDE
- **版本兼容**: 支持 Android Studio Chipmunk ~ Otter 2 FD
- **命令行工具**: 支持 CI/CD 集成

### 性能优化

- **APK 修改**: JDK 14+ FileSystems API，性能提升 90%
- **AAPT2 守护进程**: 复用进程，避免重复启动
- **Gradle 依赖缓存**: 避免重复读取

---

## 🔗 外部资源

### 官方资源

- **GitHub**: https://github.com/SickWorm/ARRTI
- **README**: [../README.md](../README.md)
- **更新日志**: [../change_log/](../change_log/)

### 相关技术

- **Android Gradle Plugin**: https://developer.android.com/studio/build
- **Kotlin Compiler**: https://kotlinlang.org/docs/compiler-reference.html
- **JVMTI**: https://docs.oracle.com/javase/8/docs/platform/jvmti/jvmti.html
- **JGit**: https://www.eclipse.org/jgit/
- **ASM**: https://asm.ow2.io/

---

## 📝 文档约定

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

### 文件命名

- `00-09`: 概览和架构
- `10-29`: 核心模块 (编译、部署)
- `30-49`: 工程化
- `50-69`: 演进和辅助
- `90-99`: 索引和附录

---

## 📞 联系方式

如有问题或建议，请通过以下方式联系：

- **GitHub Issues**: https://github.com/SickWorm/ARRTI/issues
- **Email**: (见 GitHub Profile)

---

## 📄 许可证

本文档遵循项目的许可证。

---

**最后更新**: 2025-01-20  
**文档版本**: v1.0  
**总文档数**: 16 个  
**代码覆盖**: 236/346 文件 (68.2%)  
**文档总大小**: ~366 KB

**感谢阅读！** 🎉