---
title: Jugg 工作原理
description: 说明 Jugg 如何基于 Gradle 基线产物执行文件变化检测、增量编译、扩散编译和混合部署。
status: active
tags:
  - concept
  - run
---

# Jugg 工作原理

Jugg 是 Android Studio / IntelliJ 插件。它不替代 Gradle，也不接管发布构建。它依赖最近一次 Gradle 构建留下的 APK、class、资源和工程参数，在日常开发中只处理变化部分。

一次 Run 通常只有两种路径：

- 首次运行或降级运行：走 Gradle，生成 APK、class、注解器生成源码等基线产物，同时初始化 Jugg 的索引和数据库。
- 后续增量运行：检测本轮变化，只编译变化文件和受影响文件，再把增量产物部署到设备。

Jugg 主要减少的是 Gradle 初始化、资源 link、APK 打包和安装这些固定开销。边界也在这里：工程配置、编译参数、设备状态或改动类型无法用增量方式确认时，就需要回到 Gradle 重新建立基线。

## 为什么绕过 Gradle task

Gradle 的构建链路需要处理完整工程语义，包括配置阶段、任务图、插件逻辑、依赖解析、源码编译、资源 link、打包、签名和安装。大型工程即使只改一处代码，也可能在 configuration、资源 link、打包或安装阶段消耗较长时间。

Jugg 选择 IDE 插件形态，取舍很直接：

| 维度 | IDE 插件路径 | Gradle task 路径 |
|---|---|---|
| 启动成本 | 不需要等待 Gradle 初始化 | 每次运行通常要进入 Gradle 流程 |
| 文件变化 | 可直接监听 IDE 文件事件 | 由 Gradle task 输入输出判断 |
| 交互能力 | 可集成 Run、弹窗、设备选择和日志上传 | 交互能力较弱 |
| 编译参数 | 需要额外收集和校正 | 天然处于 Gradle 环境 |
| 服务端构建 | 不适合作为通用构建缓存方案 | 更适合 CI / 远端构建 |

这让 Jugg 必须解决一个问题：在 IDE 插件里拿到足够准确的 Gradle 编译信息，并在没有完整 Gradle 上下文时保证增量结果可信。

## 总体流程

Jugg 的主链路可以拆成五块：

| 模块 | 职责 |
|---|---|
| 文件改动检测 | 监听或补检工程中的变化文件 |
| 编译上下文管理 | 保存工程参数、Gradle 产物记录、部署记录和索引数据库 |
| Gradle 编译 | 本地或远端执行 Gradle，生成基线产物 |
| 增量编译 | 编译变化源码、资源、assets、native lib 等文件 |
| 部署 | 将增量 DEX、资源、assets 等产物应用到设备 |

运行流程如下：

```text
首次运行或降级运行
  -> 执行 Gradle 编译
  -> 收集 APK、class、注解器生成源码等基线产物
  -> 解析 APK / dex，初始化索引和部署历史

后续增量运行
  -> 检测本轮变化文件
  -> 按类型编译变化文件
  -> 做扩散编译检查
  -> 生成 DEX、资源 overlay、assets 等部署产物
  -> 选择热重载、热修复或重新安装
  -> 部署成功后记录本轮增量结果
```

增量运行通常不生成完整 APK。Manifest、native lib 或必须更新 APK 的场景，会更新 APK 后重新安装，或直接回到 Gradle。

## 工程信息来源

早期 Jugg 主要通过 IDE API 读取工程信息，例如模块列表、源码目录、Manifest 路径、variant、模块依赖、库依赖、Java 版本和 Kotlin 版本。这种方式速度快，但 IDE 模型偶尔会漏掉 Gradle 运行时依赖。

Jugg 后来引入 Gradle init script。在 Gradle 命令执行时，插件注入独立脚本；编译完成后，脚本从 Gradle 环境读取依赖、构建产物路径和编译参数，并保存到文件。插件侧同时保留 IDE 读取结果和 Gradle 读取结果，使用时合并成项目快照。

这套机制是为了修正 IDE 模型不完整的问题。脚本使用 Kotlin 编写，并由插件源码生成，避免维护两份容易分叉的数据处理逻辑。

Jugg 的工程级数据放在 `build/jugg` 目录，常见内容包括：

| 数据 | 用途 |
|---|---|
| APK 解析数据库 | 保存 dex 解析结果，用于部署数据生成和引用关系查询 |
| 编译上下文记录 | 保存历史部署文件，用于恢复工程和设备现场 |
| 部署历史 | 记录已部署增量文件和设备切换信息 |
| 项目信息数据库 | 缓存模块、依赖、编译参数等工程信息 |
| 源码文件索引 | 通过 source file 字段反查源码路径 |

## 文件变化检测

Jugg 不只依赖 IDE 的实时文件事件。

| 来源 | 作用 |
|---|---|
| IDE 文件事件 | 工程打开期间监听文件修改 |
| Git 补检 | 工程关闭期间、切分支或回滚后的变化补齐 |
| 部署历史 | 判断文件相对已部署基线是否真的变化 |

工程打开期间，Jugg 通过 `VirtualFileManager` 监听文件改动，并结合 `ModuleManager` 过滤非源码目录和非本工程文件。工程重新打开后，IDE 无法回放关闭期间的事件，所以需要通过 Git 查询变化。

在分支切换、文件回退和多仓库联调场景中，单纯的 modified file list 不够准确。Jugg 会结合 Git diff 和历史部署记录确认文件是否相对当前部署基线发生变化。

## 源码增量编译

源码增量编译的基本思路是：复用 Gradle 基线中已经生成的 class 和依赖，只把变化源码交给编译器。

### Java 编译

Java 编译基本等同于调用 `javac`。Jugg 通过 JDK 提供的 `javax.tools.JavaCompiler` 执行编译，并根据工程信息传入 classpath、调试符号、source / target 版本和输出目录等参数。

在部分 Android Studio 版本中，`ToolProvider.getSystemJavaCompiler()` 可能返回空。Jugg 会改用 `com.sun.tools.javac.api.JavacTool` 获取 `JavaCompiler`。

### Kotlin 编译

Kotlin 编译通过 `org.jetbrains.kotlin:kotlin-compiler-embeddable` 提供的 `K2JVMCompiler` 执行。由于 Kotlin 编译器和 IntelliJ IDEA 可能存在同包名但实现不同的类，Jugg 使用独立 `ClassLoader` 加载编译器及其依赖，避免与 IDE 进程内类冲突。

Kotlin 编译参数比 Java 多。Jugg 需要设置 JVM target、language version、module name、friend paths、Java source roots、插件参数和输出目录等。其中输出目录必须指向模块 class 目录，否则 Kotlin 编译器可能把同模块 class 判断为外部模块，导致 smart cast 失败。

Java / Kotlin 混编时，Jugg 先编译 Kotlin，并通过 `-Xjava-source-roots` 把同模块 Java 源码交给 Kotlin 编译器读取，避免 Kotlin 编译阶段只看到旧 classpath。

Kotlin 顶层声明和扩展函数依赖 `.kotlin_module` 描述。单文件编译新增这类声明时，Jugg 需要把新的 `.kotlin_module` 内容合并回原模块描述，否则后续编译可能找不到扩展声明。

### DEX 编译

Java / Kotlin 编译产物是 class 文件，Jugg 再通过 D8 转成 dex。主要参数包括：

- `--output`：输出路径。
- `--file-per-class`：每个 class 生成独立 dex，用于 JVMTI 部署。
- `--lib`：传入 `android.jar`。
- `--classpath`：传入 classpath 依赖。
- `--min-api`：对应工程的 `minSdkVersion`。

D8 的 desugar 会影响 Java 8 默认方法等语义。Jugg 曾选择关闭 desugar 以减少耗时，但默认方法会带来运行时兼容问题，因此需要结合 APK 解析数据库查找受影响 class 并重新生成 dex。

## 资源增量编译

Android 资源由 aapt2 处理。aapt2 已经把资源编译拆成两步：

```text
aapt2 compile
  -> 把单个 XML / 资源文件编译为 flat 中间产物

aapt2 link
  -> 读取所有 flat 和 Manifest
  -> 分配资源 ID
  -> 输出 resources.arsc、二进制资源、Manifest 和 R.java
```

`compile` 单文件通常较快；`link` 需要读取完整资源输入，大型工程可能耗时 10 秒以上。

Jugg 定制 aapt2，新增 `inclink` 命令，把 link 拆成加载和增量链接：

```text
inclink --load
  -> 从基线 APK 读取 res、resources.arsc、Manifest 和必要 symbol
  -> 在 aapt2 daemon 中缓存 LinkContext

inclink
  -> 接收本轮变化资源编译出的 flat
  -> 在缓存上下文上执行新增或覆盖
  -> 输出增量资源产物和 resources.arsc
  -> 如果没有新增资源 ID，则跳过 R.java 生成
```

这套方案把每次重新读取全部 flat 的 link，改为基于基线 APK 资源表的增量 link。历史测试数据中，资源 link 耗时可以从 10 到 15 秒降到 0.2 秒左右，部分场景 `inclink` 命令约 100 毫秒。

边界也要记住：删除资源后，对应 ID 不会立刻从 `resources.arsc` 中消失，要等下一次 Gradle 构建刷新基线。这个限制只适合 debug 开发场景，不适合生产构建。

## 扩散编译

只编译直接改动文件会遗漏一部分编译期检查。例如删除方法、修改字段签名或给抽象父类新增抽象方法时，直接改动文件可以编译成功，但旧调用方或子类可能在运行时出现 `NoSuchMethodError`、`AbstractMethodError` 等问题。

Jugg 使用扩散编译补齐这部分检查：

```text
变化 class
  -> 与基线或已部署 class 结构对比
  -> 识别方法、字段和抽象方法变化
  -> 通过 APK 解析数据库查询引用类、子类和源码映射
  -> 把受影响源码加入下一轮编译
```

典型处理场景包括：

- 方法签名变化或删除时，编译引用类源码。
- 变量签名变化或删除时，编译引用类源码。
- 抽象父类新增抽象方法时，编译子类源码。

查询引用关系时需要考虑虚函数调用。某个方法属于类 A 时，调用方可能通过 A 的子类触发调用，因此查询范围需要包含子类引用。增量部署过的类也要使用部署后的类结构参与分析。

## 混合部署

Jugg 增量运行通常只生成 DEX、资源、assets 等局部产物，部署阶段不能只依赖完整 APK 安装。常见方案有三类：

| 方案 | 机制 | 适用点 |
|---|---|---|
| 热修复 | App 启动时反射插入 dex、native lib 或资源路径 | 需要重启后生效，覆盖面较广 |
| Apply Changes / JVMTI | 运行时替换 class 实现，并处理资源 overlay | 可不重启，但不支持部分结构变化 |
| split APK | 重制受影响 split 后安装 | 需要改变打包流程和签名流程 |

Jugg 采用热重载优先、热修复兜底的混合策略。

判断依据是新旧 class 结构差异。仅方法体变化等可在线替换的场景走热重载；删除方法、修改方法签名、修改字段等不适合 JVMTI redefine 的场景走热修复或更保守路径。

热重载部分复用 Android Studio Apply Changes 通道。Jugg 向通道提供上次部署 ID、新类字节码、可热重载类字节码和资源 overlay 等数据，由 Apply Changes 的 socket / protobuf 通道下发到设备。

热修复部分复用 Apply Changes 的存储和恢复能力。Jugg 把不支持热重载的类按新类下发，写入 overlay 并加入 dex 列表；如果该类已经加载过，就重启 App，让启动恢复流程加载新的 dex。

## 何时回到 Gradle

Jugg 回到 Gradle，是为了重新生成可信基线。常见场景包括：

- 首次运行，需要生成 APK、class 和注解器生成源码等基线产物。
- `build.gradle` 等构建配置发生变化。
- 注解处理器、插桩、部分生成代码等场景暂不适合直接增量。
- 一次性修改文件很多，继续增量可能不比 Gradle 更快。
- 用户主动触发降级，用完整构建重置状态。
- Manifest 等必须写回 APK 并安装的产物需要完整刷新或更保守处理。

降级构建完成后，Jugg 会重新收集 Gradle 产物，更新 APK / dex 解析数据库和项目快照，并把新的完整构建结果作为后续增量的基线。

## 相关页面

- [增量编译](./incremental-compile/)
- [编译调度流程](./compile-pipeline.md)
- [部署策略](./deploy-strategy.md)
- [部署数据与影响分析](./deploy-data-and-impact.md)
- [JVMTI Agent](./jvmti-agent.md)
- [回退与限制](./fallback-and-limits.md)
- [工程上下文获取](./project-model.md)
