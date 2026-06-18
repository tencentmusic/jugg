---
title: 增量编译
description: 说明 Jugg 如何基于 Gradle 基线产物检测变化文件，并按源码、资源、Manifest、DataBinding、依赖库等类型生成局部部署产物。
status: active
tags:
  - concept
  - compile
---

# 增量编译

Jugg 的增量编译建立在一次可信的 Gradle 构建之上。Gradle 先生成 APK、class、资源表、Manifest、DataBinding 中间产物和依赖信息；Jugg 后续只处理本轮变化文件，并输出部署阶段需要的局部产物。

它不接管 Gradle task，也不生成完整 APK。构建脚本、依赖、注解处理器或其他上下文不可信时，Jugg 会回到 Gradle，刷新下一轮增量编译的基线。

## 增量编译类型

| 子文档 | 内容 |
|---|---|
| [源码增量编译](./source.md) | Java、Kotlin、Dex 和 default method 脱糖处理。 |
| [重编译 / 扩散编译](./recompile-propagation.md) | 首轮编译后如何根据 class 结构和引用索引继续补编译源码。 |
| [常量引用分析](./const-ref.md) | Java/Kotlin 编译期常量变化后如何补编译引用方。 |
| [资源增量编译](./resource.md) | aapt2 compile / link、Jugg 定制 `inclink`、资源表加载和资源 overlay。 |
| [DataBinding / ViewBinding](./databinding-viewbinding.md) | layout split、base class、mapper、BR 和两阶段处理。 |
| [Android Manifest 编译与 release 增量编译](./manifest-minify.md) | Manifest 增量合并、release mapping、`_jugg_fix` 桥接类。 |
| [assets 与 native lib](./assets-native.md) | assets overlay 与 native lib update apk。 |
| [依赖库增量编译](./dependency-incremental.md) | build 文件确认、Gradle dependency diff、变化库差分编译和部署处理。 |
| [自定义编译器](./custom-compiler.md) | 自定义 jar 装载、SPI 创建、`CompileOrder` 插入点和 hook 语义。 |

## 主流程

```text
首次运行或需要刷新基线
  -> 执行 Gradle 构建
  -> 收集 APK、class、资源表、Manifest、DataBinding 中间产物和依赖信息
  -> 初始化项目快照、APK 解析数据库和部署记录

后续增量运行
  -> 检测 IDE 与 Git 记录中的变化文件
  -> 按文件类型进入 assets、res、source、manifest 等编译链路
  -> 输出 dex、resources.arsc、res overlay、assets、Manifest 或 APK 更新文件
  -> 分析受影响源码和 redex class，必要时继续下一轮编译
  -> 将 staging 产物交给部署阶段
```

## 文件变化来源

Jugg 同时使用 IDE 文件事件、Git 补检和部署历史。IDE 文件事件覆盖工程打开期间的实时修改；Git 补检用于工程关闭期间的修改、切分支、回滚和多仓库联调；部署历史用于判断变化是否已经成功部署过。

编译成功后，Jugg 会记录文件的 `lastModified` 与 `length` 快照。迟到的 IDE 文件事件如果快照没有变化，不会把已编译文件重新放回待编译集合。

## 编译上下文

增量编译需要复用 Gradle 结果，包括模块路径、源码目录、Manifest 路径、variant、模块依赖、库依赖、Java/Kotlin 参数、APK 路径和 DataBinding 中间产物。

Jugg 会把这些信息合并成项目快照，并在 `build/jugg` 下维护编译和部署相关数据。常见数据包括 APK 解析数据库、编译上下文记录、部署记录、项目快照和源码索引。APK 解析数据库来自 dex 解析结果，后续扩散编译、default method 处理和部署数据生成都会读取它。

## 阶段顺序

```text
JuggCompiler
  -> AssetOverlayCompiler
  -> ResourceOverlayCompiler
     -> AndroidManifestCompiler
     -> ResourceCompiler
     -> ArscCompiler
  -> RDexForSubmoduleCompiler
  -> SourceCompiler
     -> JuggApt / DataBinding mapper
     -> Kotlin
     -> Java
     -> Dex
     -> Minify
```

资源阶段可能生成 `R.java` 和 DataBinding/ViewBinding 源码。这些文件不会在资源阶段结束，而是转交给源码阶段继续编译。

## 何时回到 Gradle

以下情况通常需要 Gradle 回退：

- 首次运行，没有可复用的 APK、class、资源表和中间产物。
- 用户强制 Gradle 编译。
- 构建脚本或依赖配置变化，且不属于已确认的依赖库增量场景。
- 设备状态、文件数量、模块数量或部署条件不适合继续增量。
- 注解处理器、插桩、生成代码或其他 Gradle 上下文无法确认。
- 增量编译失败且重试策略无法恢复。

Gradle 回退成功后，Jugg 会重新收集构建产物，刷新项目快照、APK 解析数据库和部署记录。

## 相关页面

- [编译调度流程](../compile-pipeline.md)
- [部署数据与影响分析](../deploy-data-and-impact.md)
- [回退与限制](../fallback-and-limits.md)
- [源码编译能力](../../capabilities/compile/source-compile.md)
- [资源编译能力](../../capabilities/compile/resource-compile.md)
- [DataBinding / ViewBinding 能力](../../capabilities/compile/databinding-viewbinding.md)
