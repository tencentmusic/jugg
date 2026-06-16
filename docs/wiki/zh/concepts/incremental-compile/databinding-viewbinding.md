---
title: DataBinding / ViewBinding 增量编译
description: 说明 Jugg 如何在资源阶段和源码阶段处理 DataBinding 与 ViewBinding。
status: active
tags:
  - concept
  - compile
  - databinding
---

# DataBinding / ViewBinding 增量编译

DataBinding / ViewBinding 不是单一阶段完成的。Jugg 在资源阶段处理 layout split、base class 和 stripped XML，在源码阶段处理 DataBinding mapper、BR 和 Java 编译输入。

## 两阶段模型

```text
资源阶段
  -> 解析 Gradle/Jugg DataBinding 目录
  -> 处理变化 layout
  -> 生成 base class、trigger source、stripped XML 和 layout info
  -> stripped XML 进入资源 overlay
  -> generated source 转交源码阶段

源码阶段
  -> 继续消费资源阶段写入的 layout info
  -> 运行 DataBinding annotation processor
  -> 生成 mapper holder、BR 和 Java 源码
  -> 合入 Java 编译输入
```

ViewBinding 主要停留在资源阶段。DataBinding 还需要进入 mapper / BR 阶段。

## 关键目录

Jugg 使用自己的临时工作区保存 DataBinding 中间产物：

| 路径或状态 | 作用 |
|---|---|
| `tempCompileDir/data_binding/<relative module>` | 按模块隔离的 Jugg DataBinding 工作区。 |
| `dataBindingSourcesOutputDir` | base class、APT 生成源、mapper 和 BR 输出目录。 |
| `dataBindingStrippedXmlDir` | split 后的 stripped XML，后续进入资源 overlay。 |
| `tempDataBindingLayoutXmlDir` | 当前轮 layout info merge 目录。 |
| `backupDataBindingLayoutXmlDir` | 备份 Gradle layout info，避免后续 Gradle 编译缺文件。 |
| `incrementalDependencyClassesFolder` | 保存 incremental artifact，供后续 include 和 base class 生成使用。 |
| `mapperDir` | 保存 delegate mapper、full mapper 和历史 incremental mapper。 |

这些目录由 `DataBindingArgsManager` 统一维护。

## 资源阶段

资源阶段由 `ResourceCompiler` 触发 `DataBindingGenBaseClassesCompiler`。它会基于 Gradle 中间产物和当前变化 layout 生成 base class、trigger source 和 stripped XML。

Jugg 会备份 Gradle layout info。这样新增后又删除 layout 时，后续 Gradle 构建仍能看到稳定的 layout info，避免全量构建失败。

## 源码阶段

源码阶段由 `SourceDataBindingProcessor` 触发 `DataBindingGenMapperCompiler`。该阶段不能重置 `DataBindingArgsManager`，因为 mapper 生成依赖资源阶段刚写入的 `tempDataBindingLayoutXmlDir`。

当前默认路径使用 Java APT trigger 运行 DataBinding annotation processor。KAPT 失败后切换 Java APT 的分支仍保留在代码里；排查当前行为时应优先按 APT 路径看。

BR 合并会读取上一次 Gradle 生成的 BR 文件。新增字段追加到末尾，避免 BR id 抖动。

## include 影响

DataBinding 的 `<include>` 关系不是只扫描当前 XML 文本。Jugg 会基于 layout info 补齐受影响 layout，并把相关 layout info 写入当前轮临时目录，再由 mapper 阶段继续消费。

## 约束

- 普通 ViewBinding layout 不应触发 DataBinding mapper。
- DataBinding stripped XML 会作为资源产物进入 overlay，不能只看 Java 输出判断本轮是否成功。
- `DataBindingClasspathHelper` 只给 DataBinding processor 准备相关依赖，避免 ARouter 等其他 processor 进入这条旁路。
- AGP 版本不同会改变中间产物路径；路径问题优先检查 `DataBindingArgsManager` 的候选目录匹配。

## 相关页面

- [增量编译总览](./index.md)
- [资源增量编译](./resource.md)
- [源码增量编译](./source.md)
- [DataBinding / ViewBinding 能力](../../capabilities/compile/databinding-viewbinding.md)
