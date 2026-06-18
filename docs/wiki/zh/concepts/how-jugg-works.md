---
title: Jugg 工作原理
description: 从常规 Run 的固定耗时出发，解释 Jugg 如何依托 Gradle 基线，用定制编译器、扩散编译和混合部署减少资源链接、编译器加载和整包刷新的固定开销。
status: active
tags:
  - concept
  - run
---

# Jugg 工作原理

Jugg 是 Android Studio / IntelliJ 插件。它不替代 Gradle，也不接管发布构建，而是依赖最近一次 Gradle 构建留下的 APK、class、资源和工程参数，在日常开发中只处理变化部分。

日常 Run 常有一些与本轮小改动不成比例的固定开销：资源 link、编译器加载、APK 打包安装和运行状态重建。Jugg 依托可信 Gradle 基线、局部编译、影响分析和混合部署减少这些开销；状态不可信时，再回到 Gradle。

## 常规 Run 的固定耗时

Gradle 一次完整 Run 要处理整个工程语义：配置阶段、任务图、插件逻辑、依赖解析、源码编译、资源 link、打包、签名和安装。

其中不少耗时与本轮改动大小无关。资源 link 要读取完整资源输入，APK 打包和安装偏向“重做整包”，编译器进程的启动与 classpath 加载也按工程规模收费。在百万行级、数百依赖的工程里，即使只改一行代码，configuration、资源 link、打包或安装阶段仍会消耗固定时间。

Jugg 不让 Gradle 本身变快，而是在日常开发里减少进入这些固定阶段的次数。下面几节分别讲工程信息、源码编译、资源编译和部署四个环节。

## 可信 Gradle 基线

增量能力必须有一个可信起点，否则无从判断“只编译变化部分”是否安全。

Jugg 把这个起点交给 Gradle：首次运行、用户主动降级或状态不可信时，先执行一次完整 Gradle 编译，拿到 APK、class、注解器生成源码、资源表和工程参数，写入 `build/jugg` 作为下一轮增量的基线。后续 Run 不再从零构建：

```text
可信 Gradle 基线
  -> 文件变化检测
  -> 增量编译变化文件
  -> 影响分析决定是否补编译
  -> 生成 DEX / resource overlay / asset overlay 等 staging 产物
  -> 根据 class 结构和设备状态选择部署策略
  -> 部署成功后提交新的历史状态
```

这也有代价：基线会过期。`build.gradle`、依赖或编译参数变化，或本轮改动无法用增量结果证明可信时，Jugg 必须回到 Gradle 重建基线（见[何时回到 Gradle](#何时回到-gradle)）。

## 工程信息的双源校验

增量编译需要准确的 classpath、source roots、resource roots、applicationId 和 APK 归属。这里的瓶颈不是速度，而是单一信息源的可信度。

仅读 IDE project model 速度快，能立刻拿到模块、source set、运行配置和设备交互信息，但 Gradle 执行阶段才生成的运行时依赖和高版本 AGP 调整后的输出路径需要 Gradle 信息补齐。仅读 Gradle 信息准确，但要等 Gradle 执行后才能刷新。

Jugg 同时读取两份信息，并合并为一份项目快照：

| 来源 | 用途 | 局限 |
|---|---|---|
| IDE project model | 快速拿到模块、source set、运行配置和设备交互信息 | 缺少 Gradle execution phase 才能确认的依赖或产物路径 |
| Gradle init script | 在 Gradle 环境中读取 variant、classpath、APK 输出和依赖信息 | 需要等 Gradle 执行后才能刷新 |

合并后的快照再叠加 include build 信息，作为后续每一轮增量编译的输入。代价是这份快照的可信度依赖 Gradle 基线的新鲜度：当工程结构在 IDE 之外被改动时，快照需要随基线一起重建。

## 增量编译：绕过编译器的固定耗时

源码增量编译复用基线中的 class 和依赖，只把变化源码交给编译器。但“调用编译器”本身在两类语言上都不是直接调用，否则会撞上进程内的类冲突和资源链接的固定耗时。

### Kotlin 编译器的类冲突与 Smart Cast 对齐

直接在 IntelliJ IDEA 进程里调用 Kotlin 编译器会遇到一个时序与依赖冲突：编译器和 IDEA 进程中存在同包名、但实现不同的类时，混用会让编译行为不确定。

Jugg 用独立 `ClassLoader` 加载 Kotlin 编译器，把它与 IDE 进程的同名类隔离开。这里还有一个容易被忽略的约束：Kotlin 输出目录必须指向模块自身的 class 目录，否则编译器会把同模块 class 误判为外部模块，导致 smart cast 失败。隔离加载和正确的输出落点共同保证增量编译的 Kotlin 结果与全量构建一致。

Java 编译没有这层进程内冲突，接近直接调用 `javac`。两类语言还有固定的阶段顺序：注解器生成源码和 DataBinding mapper 必须在语言编译前完成，Kotlin 必须早于 Java，混淆重映射必须在 dex 之后；顺序错位会让后续阶段在缺少前置产物的情况下报出误导性错误。

### 资源 link 的内存级缓存

资源增量编译的固定耗时更直接。aapt2 原生流程把资源分成 compile 和 link 两步：

```text
aapt2 compile
  -> 单个资源文件生成 flat

aapt2 link
  -> 读取全部 flat、Manifest 和 symbol
  -> 输出 resources.arsc、二进制资源、Manifest 和 R.java
```

`compile` 很快，瓶颈在 `link`：它缺乏局部状态缓存，每次都要全量加载 flat 中间产物。在超大型工程中，单次 link 有 10 到 15 秒的固定耗时，与本轮只改一个资源无关。

Jugg 定制 aapt2，新增 `inclink`：先从基线 APK 载入资源表，把 link 上下文常驻为内存级缓存，本轮只把变化资源编译出的 flat 注入这个缓存上下文，使日常增量从全量 link 转为轻量级 overlay 注入。历史测试数据中，资源 link 从 10 到 15 秒降到 0.2 秒左右，部分 `inclink` 调用约 100 毫秒。

资源表只增不减。删除资源后，对应 ID 不会立刻从 `resources.arsc` 中消失，要等下一次 Gradle 构建刷新基线。因此 `inclink` 适合 debug 开发，不用于生产构建。

## 扩散编译：结构变化的防御性补编译

只编译直接改动的文件并不安全。删除方法、修改字段签名或给抽象父类新增抽象方法时，改动文件本身可能编译成功，但旧调用方或子类会在运行时抛出 `NoSuchMethodError`、`AbstractMethodError` 等问题。

Jugg 对新旧 class 结构做对比，再通过 APK / deploy 数据库查询引用关系，把受影响的源码一并拉进本轮编译：

```text
变化 class
  -> 对比方法、字段、抽象方法和泛型签名
  -> 查询调用方、字段访问方、子类和源码映射
  -> 把受影响源码加入下一轮编译
```

release/minify 场景还要额外处理 R8 inline 和被移除的成员；常量引用有独立的分析入口，避免 `const val` 这类编译期内联的改动只更新定义方、漏掉所有内联使用方。代价是分析范围越大，本轮编译的文件越多；当扩散范围过大时，Jugg 会回到 Gradle，避免把增量判定成本和补编译成本继续放大。

## 混合部署：产物与设备状态驱动的路径选择

增量运行生成的是局部产物：DEX、resource overlay、assets、native lib 或需要写回 APK 的文件。直接重装 APK 会丢掉增量带来的速度收益，因此部署阶段要按产物类型和设备状态选择路径。

| 路径 | 适用场景 | 结果 |
|---|---|---|
| Hot reload | 方法体等可在线替换的 class 变化 | 尽量不重启 App |
| Hot fix | class 结构变化、已加载 class 或需要 overlay 恢复 | 重启 App 后生效 |
| Reinstall / Gradle fallback | APK 需要完整刷新，或状态不可信 | 重新建立设备与本地基线 |

Jugg 复用 Android Studio Apply Changes 通道下发在线替换和 overlay，同时维护自己的部署历史。这里有一条硬约束：只有整轮部署成功后，staging 产物才提交为新的历史状态；失败轮不能更新全局部署历史，否则下一轮会以错误基线继续增量，造成累积性偏差。

### 运行时与环境对抗

在线替换 class 依赖运行时改写能力，Jugg 通过自带的 JVMTI agent 提供。围绕 agent 有几处针对真实设备环境的防御性设计：

- **加载时序对抗**：Android Studio Apply Changes 首次部署会清理 app 的 startup agents，Jugg agent 也在清理范围内。因此 Jugg 把 agent 的推送放在部署之后，并在 App 重启后才检测 JVMTI 是否可用；startup agent 只有进程启动时才会被系统加载。
- **国内定制系统兼容**：在部分定制系统（如 HarmonyOS 4.2 及以上）上，应用的 dex 加载路径与 AOSP 不一致，Jugg 会下发一个兼容修复信号让运行时修正加载路径；这个信号不代表 JVMTI 不可用。
- **设备 ABI 适配**：32 位与 64 位应用需要不同的 agent 二进制，Jugg 按目标进程的架构选择对应版本。
- **JVMTI 不可用的退路**：某些设备或机型上 JVMTI 取不到。Jugg 在检测到不可用后记录该设备状态，后续直接走兼容部署，不再反复尝试在线替换。

代价是这套协同有自己的可信状态（部署历史、deployment cache、设备 overlay id）。三者任一不一致都会触发重装或状态恢复，把设备和本地基线重新对齐。

## 何时回到 Gradle

回到 Gradle，是为了重建可信基线。常见触发场景：

- 首次运行，需要生成 APK、class 和注解器生成源码等基线产物。
- `build.gradle`、依赖或编译参数发生变化。
- 注解处理器、插桩或部分生成代码无法由本轮增量结果确认。
- 一次性修改的文件很多，增量判定和补编译成本超过完整构建收益。
- Manifest、native lib 或必须写回 APK 的产物需要完整刷新。
- 设备状态、overlay id 或 deployment cache 与本地历史不一致。

降级构建完成后，Jugg 会重新收集 Gradle 产物，更新 APK / dex 解析数据库和项目快照，并把这次完整构建结果作为后续增量的新基线。

## 相关页面

- [增量编译](./incremental-compile/)
- [编译调度流程](./compile-pipeline.md)
- [部署策略](./deploy-strategy.md)
- [部署数据与影响分析](./deploy-data-and-impact.md)
- [JVMTI Agent](./jvmti-agent.md)
- [回退与限制](./fallback-and-limits.md)
- [工程上下文获取](./project-model.md)
