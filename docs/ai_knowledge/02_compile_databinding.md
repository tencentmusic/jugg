# 编译系统：DataBinding / ViewBinding

> 最后核对：2026-02-23  
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 文档定位

本页聚焦 DataBinding/ViewBinding 的增量编译处理链，避免与源码编译主链重复。

---

## 2. 关键类

| 类 | 文件 | 作用 |
|----|------|------|
| `DataBindingArgsManager` | `main/.../compiler/databinding/DataBindingArgsManager.kt` | 统一维护 databinding 相关路径与参数 |
| `DataBindingGenBaseClassesCompiler` | `main/.../compiler/databinding/DataBindingGenBaseClassesCompiler.kt` | 生成 binding base classes |
| `DataBindingGenMapperCompiler` | `main/.../compiler/databinding/DataBindingGenMapperCompiler.kt` | 生成 mapper / BR 增量合并 |
| `LayoutIncludeAnalyzer` | `main/.../compiler/databinding/LayoutIncludeAnalyzer.kt` | include 关系分析 |
| `DataBindingTemplates` | `main/.../compiler/databinding/DataBindingTemplates.kt` | mapper holder 等模板生成 |

---

## 3. 两阶段处理模型

### 3.1 资源阶段

- 在资源编译链中生成 DataBinding/ViewBinding 所需基础信息和部分 generated 源。

### 3.2 源码阶段

- `SourceCompiler` 触发 `DataBindingGenMapperCompiler`：
  - 注解处理生成 mapper 相关类。
  - 生成增量 mapper holder。
  - 增量合并 BR（library/app）。

---

## 4. 增量关键点

- BR 合并保持声明顺序稳定，避免抖动。  
- mapper 使用增量编号策略，避免全量重建。  
- 需要依赖 `DataBindingArgsManager` 维护的历史目录与 Gradle 中间产物路径。

---

## 5. 常见问题定位

- “明明启用了 DataBinding 但未生效”：检查 `argsManager.isUseDataBinding` 与 packageName。  
- “BR 冲突或缺字段”：检查 `mergeLibraryBr()` / `mergeAppBr()`。  
- “mapper 生成失败”：看 `runAnnotationProcessor` 输出与 generated 目录。

---

## 6. 关联文档

- 源码编译：`02_compile_source.md`
- 资源编译：`02_compile_resource.md`
- 项目路径模型：`04_engineering_project.md`
