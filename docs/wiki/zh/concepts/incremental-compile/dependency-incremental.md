---
title: 依赖库增量编译
description: 解释独立库联调中频繁更新依赖时，Jugg 如何通过用户确认、双基线和文件差分缩小编译范围。
status: active
tags:
  - concept
  - compile
  - dependency
---

# 依赖库增量编译

独立维护 Android library 时，一次联调往往需要反复发布或替换 Maven、AAR 依赖，再回到应用工程运行验证。依赖声明写在构建文件中，Jugg 检测到这类修改后默认会回到 Gradle，刷新完整 APK 基线；即使库中只改了少量 class，也会进入完整构建流程。

依赖库增量编译用于缩小这段联调循环。用户确认构建文件只包含依赖相关变化后，Jugg 通过 Gradle 读取新旧依赖，再把变化库中的 class、资源、Manifest、assets 和 native lib 交给已有增量编译流程。本页重点解释用户为什么需要确认、两份依赖基线如何处理连续升级与回退，以及哪些变化仍需完整 Gradle 构建。

## Gradle 如何把依赖库变成 APK 内容

一次完整构建会先根据仓库、版本约束和传递依赖解析出当前 variant 的依赖图，再处理每个依赖库中的不同产物：

| 依赖内容 | 标准构建中的处理 |
|---|---|
| JAR 或 AAR 中的 class | 加入编译 classpath，并由 D8/R8 转成 DEX |
| AAR 中的 `res/` | 与应用和其他依赖资源一起编译、链接 |
| AAR Manifest | 参与完整 Manifest merge |
| AAR 中的 assets | 合并后进入 APK 的 `assets/**` |
| AAR 中的 native lib | 按 ABI 进入 APK 的 `lib/<abi>/**` |

依赖版本变化还可能改变传递依赖、版本选择和编译 classpath。构建文件同时能够修改 task、source set、variant、代码生成和编译器插件配置，所以检测到构建文件变化时，默认做法仍是回到 Gradle 重建完整基线。

## 用户确认把变化限定在依赖库范围内

依赖库变化是构建文件修改中的一个受控例外。Jugg 用两次确认区分“构建脚本发生变化”和“只有依赖库需要增量处理”：

```text
检测到 build 文件变化
  -> 展示 build 文件 diff
  -> 用户选择查找变化依赖、忽略变化或回退 Gradle
  -> 查找变化依赖时，通过 Gradle 解析当前依赖图
  -> 展示检测到的依赖差异
  -> 用户再次确认增量编译或回退 Gradle
```

“查找变化依赖”仍会启动一次 Gradle 任务，用于读取当前依赖图和变化库产物；它不是完全绕过 Gradle。节省的工作发生在确认之后：Jugg 不再执行完整 assemble，而是把变化库拆回已有的源码、资源、Manifest、assets 和 native lib 增量路径。

选择“忽略变化”表示用户确认本轮构建文件修改不会影响当前开发结果。Jugg 不会验证新旧脚本等价；后续出现 classpath、生成代码或打包结果异常时，仍需完整 Gradle 构建。

## 两份依赖基线处理连续升级与回退

依赖 diff 同时使用两份比较基线：

| 比较基线 | 用途 |
|---|---|
| 上一次构建保存的依赖 | 展示相邻两次构建之间的依赖差异 |
| 最近一次完整 Gradle 构建 | 决定哪些库文件真正需要编译、替换，以及哪些增量 DEX 需要从设备移除 |

第二份基线用于处理连续增量和版本回退。例如，完整 Gradle 基线使用 `1.0`，随后通过增量部署更新到 `1.1`，再把声明改回 `1.0`。只比较相邻两次结果会看到一次普通版本变化；与完整基线比较才能确认设备上额外部署的 `1.1` 产物应被移除，而不是再叠加一份 `1.0` 产物。

## 变化库如何进入增量编译

用户确认后，Jugg 会比较新旧库内容，只把有差异的文件交给对应阶段：

```text
变化的依赖库
  -> JAR 中新增或修改的 class 进入 DEX 编译
  -> res 变化进入资源编译
  -> Manifest 变化进入增量合并
  -> assets 变化生成 asset overlay
  -> native lib 变化进入 APK 更新
  -> 产物按目标 APK 归属交给部署阶段
```

JAR 会按 class 条目的内容校验结果筛出新增和修改项，资源与 assets 目录也会过滤内容未变化的文件。这种差分减少了后续 DEX、资源链接和部署需要处理的输入，但不承诺固定耗时；依赖解析时间仍取决于 Gradle 配置、仓库访问和工程规模。

部署侧还要清理已经不属于当前依赖状态的增量 DEX：

| 场景 | 处理方式 |
|---|---|
| 正常升级且文件有变化 | 编译变化文件并部署增量产物 |
| 新增依赖库 | 新库文件进入本轮增量编译和部署判断 |
| 版本回到完整 Gradle 基线 | 移除基线之外的 library DEX，恢复使用 APK 内产物 |

## 需要回到 Gradle 的情况

依赖库增量只处理用户能够确认、且可以映射到现有增量阶段的变化。以下情况应重新执行完整 Gradle 构建：

- build 文件里除了依赖声明，还修改了会影响 APK 的配置。
- 修改了 Gradle 插件、source set、variant、注解处理器或 Kotlin 编译器插件配置。
- 缺少完整 Gradle 基线，或 Gradle 无法生成可靠的依赖 diff。
- 依赖版本回退还需要恢复 Manifest、资源、assets 或 native lib；当前回退只支持移除增量部署的 library DEX。
- 依赖变化后出现符号解析失败；Jugg 刷新一次编译上下文后仍无法恢复。
- 用户无法确认构建文件 diff 或依赖变化符合预期。

## 相关页面

- [增量编译总览](./index.md)
- [源码增量编译](./source.md)
- [资源增量编译](./resource.md)
- [工程上下文获取](../project-model.md)
- [编译阶段说明](../../guide/compile.md)
- [依赖库增量编译能力](../../capabilities/compile/dependency-incremental.md)
- [Gradle 回退](../../capabilities/compile/gradle-fallback.md)
