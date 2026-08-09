---
title: 工程上下文获取
description: 解释 Jugg 的工程信息如何从 IDE 读取演进到 IDE 与 Gradle 合并，以及当前项目快照的取值规则。
status: active
tags:
  - concept
  - project
  - context
---

# 工程上下文获取

一个源码文件属于哪个模块、需要哪份 classpath、应写入哪个输出目录，以及最终归属哪个 APK，都不能只根据文件本身判断。Jugg 最初通过 IDE 工程模型取得这些基础信息；随着增量编译支持的场景增加，又引入 Gradle 读取补齐 IDE 没有提供的信息。当前项目快照由两者合并而成，用于决定本轮变化应如何编译和部署。

## 局部编译需要哪些工程信息

Jugg 的增量编译不会重新执行完整 Gradle task graph，但仍要知道当前模块结构、variant、依赖、编译参数和产物路径。这些信息共同描述了 Gradle 最终会怎样编译工程。

这份工程描述至少需要回答三个问题：

- 变化的文件属于哪个模块，还会影响哪些模块。
- 编译时应使用哪些 source set、classpath、编译参数和输出路径。
- 新产物属于哪个应用或测试 APK，后续应部署到哪里。

缺少这些信息时，即使单个文件能够通过编译，也不能证明生成的局部产物适用于当前工程。

## 工程信息为什么从 IDE 扩展到 Gradle

Jugg 最初只读取 IDE 工程模型。它能够提供模块、源码根、资源目录、依赖和当前 variant 等基础信息，支撑了早期的源码与资源增量编译，也不要求业务工程接入额外的 Gradle 插件。

后续能力需要的信息逐渐超出 IDE 模型的范围。例如，注解处理器参数、完整的依赖库路径、Kotlin 编译参数、Compose 任务信息和自定义 build directory 都更接近 Gradle 的实际构建环境。IDE Sync 与 Gradle 构建又是相互独立的过程，仅依赖 IDE 读取还曾出现依赖信息偶尔缺失、Sync 与依赖变化无法一一对应的问题。

Jugg 因此通过 Gradle init script 在构建环境中读取工程信息，无需修改业务工程的构建脚本。从信息完整性和构建语义看，Gradle 是更权威的来源；它能够访问真实 task、variant、依赖和编译参数，也是后续增量能力继续扩展的主要信息来源。

## 为什么没有让 Gradle 信息直接覆盖 IDE

Gradle 信息更完备，但引入它时，Jugg 已经有一套长期使用的 IDE 信息读取和消费流程。直接把所有共同字段切换到 Gradle，会同时改变模块识别、路径选择和依赖关系，扩大兼容风险。

当前合并采用渐进策略：IDE 与 Gradle 都能提供的既有信息，优先保留已经稳定工作的 IDE 结果；只有 Gradle 能提供的信息直接从 Gradle 读取。需要由实际构建环境确认的字段则采用专门规则，例如依赖信息会结合两份快照的新鲜度选择，build directory 使用 Gradle 读取的实际路径，IDE 未识别但 Gradle 已确认的模块会在不会形成依赖环时补入。

这种优先级不是在判断 IDE 比 Gradle 更准确，而是在 Gradle 信息逐步接管工程模型期间控制行为变化。理论上，Gradle 可以成为完整工程信息的唯一权威来源；当前仍保留 IDE 优先的共同字段，是兼容既有工程和历史行为的稳定性取舍。

## 两类信息合并为项目快照

IDE Sync、Gradle 工程信息读取或完整 Gradle 构建完成后，Jugg 会重新合并工程信息：

```text
读取 IDE 工程结构
  -> 读取 Gradle 与 included build 工程信息
  -> 对齐两边的模块标识
  -> 合并源码根、classpath、依赖、variant 和产物路径
  -> 形成项目快照
```

同一个模块在 IDE 与 Gradle 中可能使用不同名称。合并时需要先对齐模块标识，并同步修正依赖关系中的模块引用，否则同一模块会被当成两个，后续 classpath 和 APK 归属也会随之错位。

某个模块只出现在 Gradle 信息中时，Jugg 会在不引入依赖环的前提下补回已经由 Gradle 确认的模块和依赖。included build 本轮读取失败但存在上一次有效副本时，会保留旧副本；从未成功读取过的 included build 才会被跳过。

合并器仍保留只使用 IDE 信息生成快照的兼容路径，但当前增量编译要求 Gradle 工程信息和最近一次完整构建记录有效。条件不满足时会先尝试刷新，仍不可用则转为完整 Gradle 构建。

## 项目快照如何参与一次 Run

项目快照把一次文件变化连接到最终部署结果：

```text
检测到文件变化
  -> 确定所属模块与受影响依赖
  -> 选择 classpath、编译参数和输出路径
  -> 生成局部编译产物
  -> 按 APK 归属选择部署目标
```

快照刷新后，后续增量任务会改用新的文件归属、classpath、输出路径和模块到 APK 的对应关系。部署历史也必须与新快照匹配，避免把旧工程结构下的产物继续应用到当前设备状态。

Android Test 会在这份模型中增加测试源码、测试 APK 和被测应用的对应关系。只有构建目标为 `ANDROID_TEST` 时，Gradle 才会读取 androidTest source set 并确认相应测试模块；完整执行流程见 [Android Test 流程](./android-test-flow.md)。

## 工程变化何时需要刷新快照

切换 variant 或构建目标、修改依赖或构建脚本、调整工程结构，以及改变 Gradle 编译命令后，旧快照不再足以证明本轮局部产物正确。Jugg 会刷新工程信息；如果无法恢复有效快照，则通过完整 Gradle 构建重新建立基线。

工程信息刷新期间的等待、复合构建恢复、AGP 兼容和自定义 build directory 处理，见 [工程信息刷新与恢复](./project-info-refresh.md)。

## 相关页面

- [编译调度流程](./compile-pipeline.md)
- [增量编译](./incremental-compile/)
- [Android Test 流程](./android-test-flow.md)
- [Gradle 回退与基线重建](./gradle-fallback-baseline.md)
