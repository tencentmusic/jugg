---
title: 自定义编译器
description: 说明 Jugg 自定义编译器 SPI、阶段插入点和用户可获得的扩展能力。
status: active
tags:
  - capability
  - compile
  - custom
---

# 自定义编译器

Jugg 支持通过自定义编译器 SPI 扩展内置增量编译链。自定义编译器可以在 asset、resource、source、minify、dex 等阶段前后插入逻辑，处理项目特有的生成、转换或校验需求。本页说明接入能力和常见插入点，加载与阶段边界见[自定义编译器原理](../../concepts/incremental-compile/custom-compiler.md)。

## 支持的接入方式

| 场景 | 当前支持情况 | 用户可见结果 |
|---|---|---|
| 本地 jar 自定义编译器 | 支持 | 本地扩展逻辑可进入 Jugg 编译链 |
| 远端 jar 自定义编译器 | 支持 | 远端扩展下载校验后参与编译 |
| 阶段前置处理 | 支持 | 可在内置阶段前消费或改写输入 |
| 阶段后置处理 | 支持 | 可在内置阶段后处理产物 |
| 源码编译前生成 Java | 支持项目提供的适配器 | Java/Kotlin 注解输入可交给项目处理器，生成 Java 继续参与本轮编译 |
| 扩展执行失败 | 支持 | 扩展编译逻辑抛出异常时，当前任务失败并给出可见提示 |

## 接入方式

自定义编译器以 jar 提供。扩展需要实现 `ICompilerCreator`，由它为当前编译上下文创建 `ICompiler`；Jugg 通过 SPI 加载 jar 中的实现。

项目后台只需在 `customCompilers` 配置中声明 jar 文件名、路径和 md5。路径可以是本地绝对路径、相对项目目录的路径或 HTTP / HTTPS 地址。

ARouter 的 `@Route`、`@Autowired`、`@Interceptor` 已有内置增量处理，无需下发适配器。项目需保留自己的处理器依赖：APT/KAPT 使用 `AROUTER_MODULE_NAME` 或 `moduleName`，KSP 使用项目实际的 `AROUTER_MODULE_NAME` 或 `MODULE_NAME` 参数。Jugg 比较本轮源码与现有业务 class 中的相关注解值、字段类型、路由身份和必要继承关系，确认变化后才收集完整模块输入、运行项目处理器；生成 Java/Kotlin 继续参与本轮编译。同值常量、显式默认值、属性顺序、等价 import/alias、普通方法体、注释和格式变化不会重新生成。已消费索引不会反复加入普通轮次。

含 Kotlin 的模块在生成时纳入完整 Java/Kotlin 业务源码，因此新 Kotlin 类型即使没有 ARouter 注解、尚未生成 class，也能参与本轮类型解析。使用 APT/KAPT 时，混合模块通过 KAPT 完成生成，支持 Kotlin typealias 导入；纯 Java 模块使用 Java 注解处理路径。使用 KSP 时，Jugg 运行项目版本的 KSP1 compiler plugin 或 KSP2 Analysis API 引擎，透传当前变体参数，并纳入 commonMain/androidMain 输入；只消费本轮成功生成物。两条路径均仅运行选定的 ARouter processor；同时声明两种 ARouter 处理器时明确失败。生成成功后，产物才进入后续源码编译。

ARouter 生成成功且生成类集合检查通过后，Jugg 会将本轮生成源码同步到当前变体的生成源码目录，供 IDE 查看与跳转。同步只覆盖本轮文件，保留其他处理器的产物。同步失败时打印 warn，编译继续使用 Jugg 保存的生成源码；IDE 中的部分生成源码可能仍是旧版本。源码同步成功不代表后续编译或部署成功。

只修改路由常量时，已有常量引用分析先把使用方送入跟编。即使使用方文本未变化，Jugg 仍读取当前常量值，确实影响注解结果才重新生成。ARouter 与 Kuikly 在同一轮共用源码读取和语法分析结果。

业务 class 由正常 Java/Kotlin 编译维护。Jugg 按声明身份及源码归属关联同文件多类、嵌套类和移动后的声明；新增声明按空旧输入比较。全部实际注解属性及默认值都参与比较，包括 `name`、`desc` 和定制的 `app`；即使不同输入最终生成相同代码，也允许重新执行处理器。普通 ARouter runtime import 和已有部署 overlay 不会单独触发生成或告警。

无法读取相关旧 class、声明归属有歧义或无法解析相关值和类型时，本模块本轮跳过 ARouter 生成，仅在日志文件中记录 debug 信息，编译输出中不显示此告警；其他注解器和正常源码编译继续，旧索引可能滞后。如果普通编译随后更新了业务 class，再次提交相同输入不保证补齐旧索引，完整 Gradle 构建可恢复一致状态。当前语义解析不覆盖完整 Java/Kotlin 语言，也不承诺任意 fork 的新增结构依赖均可识别。

APT 成功但语言编译失败时，已生成源码由原有待完成产物跟踪保留。下一次请求仍比较当前源码与业务 class；存在差异时允许再次执行 APT，pending 期间的新路由修改也会重新判断。D8 或部署失败后，输入相同则沿用既有下游重试。

APT/KAPT 路径按 APK 基线选择 AndroidX 或旧 Support Fragment，必要时转换 processor JAR。两种类型都有、没有查询结果或多 APK 无法确定归属时使用 AndroidX；本轮编译类路径缺少目标 Fragment 时明确失败。真实 APT/KAPT/KSP 执行失败或生成源码编译错误停止本轮任务。删除路由等导致生成类集合收缩时保留旧生成目录，并要求完整 Gradle 构建。路由注册索引部署沿用现有 App 重启策略。

已加载的 ARouter 自定义处理器实例存在时，Jugg 默认处理器避让，由该实例接管；仅保留配置而未成功加载不触发避让。源码预处理适配器需按当前 Jugg 接口重新编译后使用。迁移到内置处理应移除该项配置，保留其他扩展。当前识别上述三种注解，按项目实际定义读取属性并调用项目处理器，定制 `app` 和不同的生成调用参数数量不会单独被拒绝。早期 `@Param` 或无法解析的新增结构需要核对实际结果。支持范围见[注解器](./annotation-processors.md)。

> [!NOTE]
> 自定义编译器运行在 Jugg 编译流程内，适合补充增量链路，不适合替代完整 Gradle task graph。

## 触发与结果

```text
自定义编译器配置可用
  -> 加载并校验扩展 jar
  -> 按配置插入编译阶段
  -> 参与本轮增量编译
  -> 成功产物继续交给后续阶段
```

选择插入点时，应按用户目标选择阶段，而不是按 jar 加载时机判断。

## 常见插入点

| 目标 | 推荐区间 |
|---|---|
| 最早或最后处理本轮输入和产物 | `atFirst` / `atLast` |
| assets 和 native lib 前后处理 | `beforeAsset` / `afterAsset` |
| 资源前后处理 | `beforeRes` / `afterRes` |
| Java/Kotlin 前后处理 | `beforeSource` / `afterSource` |
| 混淆前后处理 | `beforeMinify` / `afterMinify` |
| dex 前后处理 | `beforeDex` / `afterDex` |

## 相关页面

- [自定义编译器指南](../../guide/custom-compiler.md)
- [Jugg 后台项目配置下发](../../guide/jugg-backend/project-config.md)
- [源码编译](./source-compile.md)
- [自定义编译器原理](../../concepts/incremental-compile/custom-compiler.md)
