---
title: 编译调度流程
description: 说明 Jugg 如何从一次 Run 进入增量或 Gradle 编译，并在增量编译中推进 staging、补编译和失败收口。
status: active
tags:
  - concept
  - compile
  - scheduling
---

# 编译调度流程

这篇不讲 Java、Kotlin、资源分别怎么编译。那些内容在增量编译子页里。

这里讲 Jugg 的调度层：一次 Run 如何决定走增量还是 Gradle，增量结果如何写入 staging，什么时候继续补编译，什么时候停止并回退。

## 调度入口

IDE 侧入口是 `JuggCompilerHelper.compile()`。它先记录本轮 compile 时间戳，然后进入 `doCompile()`。

```text
JuggCompilerHelper.compile()
  -> 等待增量初始化完成
  -> 等待 pending file processing
  -> preprocessIncrementalCompile()
  -> 可以增量: incrementalCompile()
  -> 需要回退: gradleCompile()
```

`preprocessIncrementalCompile()` 返回 `null` 才会进入增量编译。返回失败结果时，后续会走 Gradle。

## 编译前检查

增量编译前会做几类检查：

| 检查 | 结果 |
|---|---|
| 用户强制 Gradle | 返回 `Force fallback`。 |
| build target 变化 | 强制 Gradle，重新生成 app / androidTest 对应 APK。 |
| 设备状态不可用 | 返回设备状态里的失败原因。 |
| 变化源码过多或模块过多 | 返回 `Too many changes`。 |
| build file 变化 | 读取依赖 diff，按依赖变化结果决定是否还能增量。 |
| 部署状态不支持增量编译 | 返回 deploy state message。 |

文件回滚检查也在这里做。Jugg 会用部署历史过滤未真正变化的文件，命中的文件会从 changed set 中移除。

## 增量编译循环

增量循环由 `IncrementalCompilerHelper.compile()` 负责。它把 `ChangedFile` 转成 `CompileFile`，再交给 `JuggCompiler.compile()`。

```text
undeployed ChangedFile
  -> CompileFile
  -> CompileTask(stagingDir)
  -> JuggCompiler.compile()
  -> 首轮更新 uncompiled 状态
  -> outputs 写入 staging
  -> 成功后查询 recompile files
  -> 有受影响源码或 redex class: 递归进入下一轮
```

首轮成功文件会从未编译集合中移除。后续补编译轮不再更新这组状态，避免把派生出来的重编译文件当成用户原始改动。

所有编译产物都会先写入 staging。部署阶段只消费 staging 中的有效产物。

## `JuggCompiler` 阶段编排

`JuggCompiler` 接收同一批 `CompileFile`，按内置阶段拆分：

```text
AssetOverlayCompiler
  -> 处理 asset / native lib，输出 overlay 或 native lib item
ResourceOverlayCompiler
  -> resource / Manifest 先编译到 tmp_resource
  -> res overlay 移到 staging/overlays
  -> R.java 交给 SourceCompiler
  -> DataBinding / ViewBinding 生成源转给源码阶段
RDexForSubmoduleCompiler
  -> 必要时生成 R.dex
SourceCompiler
  -> JuggApt / DataBinding mapper / Kotlin / Java / Dex / Minify
```

任一阶段失败后，`quickFailedOthers()` 会把还没执行的输入标记为跳过失败。取消信号来自 `CompileTask.isShouldCancel`，子阶段会快速收口。

## 编译任务和输出契约

调度层只认这几类对象：

| 对象 | 作用 |
|---|---|
| `CompileTask` | 一批输入文件、输出目录、父任务和取消状态。 |
| `CompileFile` | 输入文件类型、文件路径、baseDir、所属 module。 |
| `CompileResult.details` | 每个输入文件成功、失败或被 quick-fail。 |
| `CompileResult.outputs` | 编译产物，后续写入 staging。 |
| `CompileOutput.apkPath` | 旧单 APK 锚点。 |
| `CompileOutput.targetApkPaths` | 产物实际影响的 APK 集合。 |

多 APK 场景下，部署侧看 `targetApkPaths`。`apkPath` 仍保留旧单 APK 语义，不能只靠它判断资源、dex 或 Manifest 的真实目标。

## 模块和 APK 分流

`BaseCompiler` 提供两层分流。

第一层是 module 分流：

```text
CompileTask
  -> splitModuleAndCompile()
  -> 非 androidTest module 一组
  -> androidTest module 单独一组
  -> 按 modulesWithOrder 编译
```

androidTest module 的分组 key 包含 module root，避免同名测试模块被合并。

第二层是 APK 分流：

```text
splitApkAndCompile()
  -> moduleBelongsApkMap 找到模块影响的 APK
  -> 一个模块可属于多个 APK
  -> 每个 APK 单独调用 doApkCompile()
  -> 子编译器输出必须保留 targetApkPaths
```

资源、Manifest 和 assets 这类产物会走 APK scoped 输出。target 丢失时，部署阶段会把产物发到错误 APK 或漏发。

## 成功后的继续编译

一轮成功后，Jugg 会调用 `DeployFileManager.getRecompileFiles()`。

返回结果分两类：

- `effectedSourceFiles`：受 method/field/subclass/generic/const-ref 影响的源码。
- `redexClasses`：需要重新转 dex 的 class。

`ContinueCompileEffectFilter` 会过滤已经满足过的触发键，避免同一批影响反复进入下一轮。Kotlin top-level file facade 命中时，可以突破上一轮已编译过滤。

## 失败后的重试和回退

增量失败后只重试一次。重试由 `IncrementalCompileRetryResolverChain` 决定：

```text
GitChangesRetryResolver
  -> unresolved reference / cannot find symbol 时刷新 Git 变化
IncrementalCompileRetryResolver
  -> 依赖缺失关键词命中时更新 compile context
```

重试仍失败时，结果返回给 `JuggCompilerHelper`。如果失败不可自动回退，Jugg 会返回当前增量失败结果；如果可以回退，后续进入 `gradleCompile()`。

增量成功后还会等待一次异步 Git 补检。补检发现新的待编译文件时，Jugg 会再跑一次增量编译。

## 相关页面

- [工程上下文获取](./project-model.md)
- [增量编译](./incremental-compile/)
- [重编译 / 扩散编译](./incremental-compile/recompile-propagation.md)
- [部署数据与影响分析](./deploy-data-and-impact.md)
