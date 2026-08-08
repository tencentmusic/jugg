---
title: 自定义编译器
description: 解释工程专用生成、转换或插桩任务如何通过 SPI 接入 Jugg，并在正确的增量编译阶段处理输入和产物。
status: active
tags:
  - concept
  - compile
  - custom-compiler
---

# 自定义编译器

大型 Android 工程经常在标准编译之外增加专用步骤。例如，PB 文件变化后需要重新生成协议代码并打包 jar，配置文件需要转换成源码，或者编译后的 class 还要经过工程自己的插桩。完整 Gradle 构建会通过 plugin 或 task 执行这些步骤，而 Jugg 的内置增量链只处理已经支持的 Android 输入。

自定义编译器用于把这些工程专用动作接入 Jugg。团队把已有生成器、转换器或校验逻辑封装成扩展 jar，并声明它应该在资源、源码、混淆或 DEX 的哪个阶段执行。相关文件变化后，Jugg 可以继续生成本轮增量产物，无需因为一项工程专用步骤直接回到完整 Gradle 构建。

## Gradle 中的额外动作如何进入增量流程

Gradle 完整构建会根据 task 依赖关系组织工程专用步骤和 Android 标准编译：

```text
PB / 配置 / 模板等工程输入
  -> Gradle plugin 或自定义 task
  -> generated source、资源、class、jar 等中间产物
  -> Java / Kotlin、aapt2、D8/R8 等标准阶段
```

Jugg 不执行完整 Gradle task graph，也无法从任意 task 推断输入、输出和执行时机。自定义编译器把这项知识交给工程自身：扩展明确识别哪些文件、调用哪套工具、生成什么产物，并选择与内置编译链衔接的位置。

这种分工已经用于两类工程流程：协议文件变化后触发协议 jar 打包，以及对本轮增量 class 补充工程专用插桩。前者补齐标准源码编译之前缺少的生成步骤，后者处理内置阶段已经产生的 class。

## SPI 如何装载工程扩展

自定义编译器以 jar 提供，通过 JVM `ServiceLoader` 暴露创建入口。Jugg 按项目配置选择本地 jar，或在远端 jar 下载完成后加载对应实现：

```text
项目的自定义编译器配置
  -> 定位本地 jar，或下载远端 jar
  -> 校验 md5
  -> 创建扩展 ClassLoader
  -> ServiceLoader 发现 ICompilerCreator
  -> 为当前工程上下文创建自定义编译器
```

jar 只负责提供实现，`CompileOrder` 才决定执行时机。同一个 jar 可以提供多个扩展，分别参与不同阶段。

## before 与 after 连接输入和产物

每个自定义编译器声明一个插入点，Jugg 据此把它放在内置阶段之前或之后：

| 插入点 | 语义 |
|---|---|
| `atFirst` | 在主要内置阶段开始前处理本轮输入 |
| `beforeAsset` / `afterAsset` | assets 和 native lib 阶段前后 |
| `beforeRes` / `afterRes` | 资源阶段前后 |
| `beforeSource` / `afterSource` | Java / Kotlin / class 输入阶段前后 |
| `beforeMinify` / `afterMinify` | release 混淆处理前后 |
| `beforeDex` / `afterDex` | DEX 阶段前后 |
| `atLast` | 在主要内置阶段完成后处理最终增量产物 |

before 扩展先处理当前阶段的输入，并决定哪些文件继续交给内置编译器。例如，协议扩展可以消费工程专用输入并调用已有打包工具，避免同一文件再被不适用的内置阶段处理。after 扩展只在内置阶段成功后执行，它接收该阶段生成的产物，适合做插桩、转换、校验或补充输出。

选择插入点时需要同时考虑输入形态和产物去向。协议生成、资源预处理通常位于对应内置阶段之前，处理 class 的扩展则位于 class 产生之后、DEX 生成之前。扩展还要明确返回本轮编译结果，只有选择 `CompileOrder` 并不会让任意输出目录自动进入后续阶段。

## 扩展加载与失败边界

扩展 jar 进入 Jugg 进程后，需要遵守以下边界：

- **远端 jar 下载完成后再参与编译**：下载在后台执行，本轮开始时尚未准备好的扩展会从本轮跳过；下载和校验完成后，下一轮重新加载。
- **md5 只校验配置与文件是否匹配**：本地文件和下载文件都要匹配项目配置中的 md5，校验失败的 jar 不会参与编译。md5 不提供代码隔离或可信来源证明。
- **只加载团队可信的扩展**：自定义编译器是在 Jugg 进程内执行的代码，可以访问编译上下文并调用工程工具，不属于受限脚本环境。
- **扩展 ClassLoader 仍继承 Jugg API**：自定义 jar 可以调用 SPI 所需类型，同时也可能与 Jugg 已加载的同名依赖发生版本冲突。扩展应避免重复打包冲突依赖。
- **扩展失败会终止当前增量任务**：异常会转成用户可见的编译失败，而不是静默跳过工程专用处理；异常不会继续穿透并终止 IDE 进程。
- **自定义编译器补充增量阶段**：需要完整 Gradle 生命周期、跨 task 编排或无法明确输入输出的逻辑，仍应由 Gradle 执行。

## 相关页面

- [增量编译总览](./index.md)
- [自定义编译器能力](../../capabilities/compile/custom-compiler.md)
- [自定义编译器指南](../../guide/custom-compiler.md)
