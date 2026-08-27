---
title: Gradle 回退与基线重建
description: 解释 Jugg 为什么会在构建基线失效、增量范围过大或失败无法恢复时执行 Gradle，以及完整构建如何成为下一轮增量起点。
status: active
tags:
  - concept
  - compile
  - fallback
---

# Gradle 回退与基线重建

Jugg 的增量编译复用最近一次 Gradle 构建生成的 APK、classpath、资源表和生成代码。当前工程无法继续沿用这些结果时，完整 Gradle 构建负责重新计算工程模型并生成下一轮增量所需的可信起点。

用户看到的结果是本轮 Run 改走原生构建和完整 APK 安装。这个选择可能发生在增量编译开始前，也可能来自增量编译或部署失败后的自动回退。

```text
开始 Run
  -> 构建基线不适用于当前目标：Gradle
  -> 当前变化超出增量范围：Gradle
  -> 增量编译无法恢复：当前或下一次 Run 使用 Gradle
  -> 部署恢复失败且允许回退：整轮 Run 重新使用 Gradle
  -> Gradle 成功：刷新本地基线并安装完整 APK
```

## 增量开始前：检查完整构建基线

每次增量编译前，Jugg 会先检查当前 Run 能否继续复用最近一次 Gradle 结果。

| 触发条件 | Gradle 需要重新处理什么 |
|---|---|
| 首次运行或工程信息缺失 | 生成 APK、classpath、资源表和生成代码等完整起点 |
| App 与 androidTest 构建目标切换 | 为新的目标生成匹配的 APK 和编译上下文 |
| 编译命令变化 | 按新的任务和参数重新建立构建结果 |
| 变化源码跨越的文件或模块过多 | 默认完整构建；确认后也可本轮继续增量 |
| 用户主动选择 Gradle | 刷新基线或用完整构建对照增量结果 |
| 再次 Run 时没有新变化，且用户选择继续构建 | 重新运行完整构建和安装流程 |

这些判断发生在局部产物生成之前。进入 Gradle 后，本轮不会再同时执行一套增量编译。

## 构建文件变化：先分辨依赖还是工程模型

修改 `build.gradle`、`settings.gradle` 或版本目录后，Jugg 需要判断变化影响的是依赖内容，还是整个工程模型。

明确的依赖库增删改可以继续走[依赖增量编译](./incremental-compile/dependency-incremental.md)，只处理变化库及其部署影响。以下变化需要 Gradle 重新读取工程：

- Gradle plugin、Variant 或 source set 变化；
- Kotlin、AGP、R8、Compose compiler 等编译器或工具链配置变化；
- Manifest placeholder、资源生成和自定义任务逻辑变化；
- 依赖差异无法映射为明确的库增删改。

这类变化会影响任务、编译参数或产物位置，旧项目快照无法描述新的构建结果。

## 增量编译失败：当前或下一次 Run 进入 Gradle

增量编译器遇到缺失文件或可恢复的依赖信息时，会先更新输入并进行有限重试。重试后仍无法得到可信产物，结果会说明是否允许 Gradle 回退。

允许立即回退的失败会在当前 Run 中执行 Gradle。部分失败会先保留错误现场并结束本轮，提示下一次运行改走 Gradle，避免在一次调用中连续执行两个失败原因不同的编译流程。

用户取消等明确停止信号不会触发自动完整构建。

## 部署恢复失败：整轮 Run 可能重新构建

增量编译成功但部署失败时，Jugg 会先 Retry、切换 Hot Fix/兼容部署、Recover 或重新安装当前 APK。现有产物无法完成部署，并且结果允许自动回退时，Run 层才会重新执行 Gradle。

这次 Gradle 构建属于整轮 Run 的路径切换，不是某个部署重试步骤。多设备场景也会统一决定是否切换，不会只为一台设备生成不同的完整构建结果。

部署侧恢复过程见[部署自愈机制](./deploy-self-healing.md)。

## Gradle 成功：刷新下一轮增量起点

完整构建成功后，Jugg 会重新读取：

- APK 及其多 APK、split、androidTest 归属；
- class、classpath、mapping 和编译参数；
- 资源表、Manifest 和资源内容；
- 注解处理器、DataBinding 等生成代码和中间产物。

这些结果形成新的本地构建基线。完整 APK 安装成功后，设备也与它对齐；下一次小范围修改仍会优先尝试增量编译。

## 发布与复杂构建：始终使用 Gradle

Jugg 的增量路径用于日常开发验证，以下工作继续使用完整 Gradle 流程：

- 发布 APK、AAB、正式签名、混淆和发布产物；
- Gradle plugin、复杂 Variant、source set 和自定义任务；
- 编译器插件、注解处理器、插桩和非标准生成代码的完整执行；
- C/C++ 源码到 native lib 的构建，以及 ABI、NDK 和 packaging 配置变化；
- 完整 Manifest merge、删除旧资源 ID 和重建完整资源表。

切换分支、升级构建工具或需要确认旧产物已经完全移除时，可以主动执行一次 Gradle。操作方式见[降级 Gradle 编译](../guide/downgrade-gradle.md)。

## 相关页面

- [降级 Gradle 编译](../guide/downgrade-gradle.md)
- [Gradle 回退能力](../capabilities/compile/gradle-fallback.md)
- [增量编译](./incremental-compile/)
- [编译流水线](./compile-pipeline.md)
- [部署自愈机制](./deploy-self-healing.md)
