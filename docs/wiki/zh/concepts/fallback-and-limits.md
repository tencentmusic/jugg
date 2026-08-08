---
title: 回退与限制
description: 区分 Retry、兼容部署、状态恢复、APK 重装和 Gradle 构建，说明 Jugg 如何选择最小恢复范围及增量能力边界。
status: active
tags:
  - concept
  - fallback
---

# 回退与限制

一次 Jugg Run 遇到异常条件时，恢复动作并不只有“回到 Gradle”。传输失败可以原地重试，运行时不兼容可以改走 Hot Fix，设备状态不一致可以 Recover 或重新安装当前 APK；只有构建基线需要刷新，或者增量编译与部署无法可靠收口时，才执行完整 Gradle 构建。

这些动作修复的对象不同。理解它们的层级，可以判断本轮为什么发生重启、重装或完整构建，也能避免把正常的设备恢复误认为增量编译失效。

```text
原数据重试
  -> 调整为 Hot Fix / 兼容部署
  -> Recover 设备 checkpoint
  -> 重新安装当前 APK
  -> Gradle 生成新的完整构建基线
```

Jugg 会根据实际失败点直接进入合适的层级，不要求每次按顺序经过所有步骤。

## 先判断哪一层状态需要更新

| 失效范围 | 典型信号 | 优先处理 |
|---|---|---|
| 本次传输 | ADB 短暂离线、可恢复的超时或 I/O 失败 | 等待条件恢复后有限重试 |
| 当前部署方式 | class 无法在线替换、JVMTI 不可用、agent 无响应 | 转 Hot Fix 或兼容部署，重启 App 后加载增量产物 |
| 设备部署基线 | App 未安装、被外部覆盖、overlay checkpoint 不匹配 | Recover；校验失败时重新安装当前 APK |
| APK 内容 | Manifest patch、已经生成的 native lib 等需要成为安装包内容 | 更新基线 APK、重新签名并安装 |
| 工程构建基线 | 构建目标、编译命令、工程模型或完整产物已经变化 | 执行 Gradle，重新收集 APK、classpath、资源和生成代码 |

前四层都可以保留现有 Gradle 构建结果。完整 Gradle 是影响范围最大的一层，用于重新生成工程级基线。

## 保留现有构建基线的恢复

### Retry 会改变失败条件

Retry 只处理已知且可恢复的失败。例如等待 ADB transport 恢复、降低部署切片大小，或在状态恢复后重新下发。在线类替换失败时，重试还可以把 payload 转为 Hot Fix 或兼容部署，而不是重复执行同一条必然失败的命令。

### Recover 和 Reinstall 修复设备状态

Recover 检查本地历史、deployment cache 和设备 overlay checkpoint 是否对应同一轮成功结果。校验通过后继续增量；App 缺失或状态无法恢复时，安装最近一次可信 APK 并重建 checkpoint。

这里重新安装的是已有构建产物，工程源码通常无需重新编译。Clean Reinstall 还可以按用户要求清理 App 数据，适合测试首次启动或主动重建设备环境。

### APK 更新只重做安装包变化

可增量处理的 Manifest 和已经生成的 native lib 会写入最近一次 Gradle APK，重新签名后安装。这个过程更新安装包内容，但保留原有工程构建基线。Manifest 删除、完整 merge 上下文变化、native 源码编译或签名条件无法满足时，再进入 Gradle 路径。

## 哪些条件会触发完整 Gradle

Jugg 在增量编译前检查工程和设备是否仍能沿用当前基线，常见触发条件包括：

| 条件 | 为什么需要 Gradle |
|---|---|
| 首次运行或工程信息缺失 | 需要生成 APK、classpath、资源表和生成代码等完整起点 |
| App 与 androidTest 构建目标切换，或编译命令变化 | 需要为新的目标生成匹配的 APK 和编译上下文 |
| 变化源码跨越的文件或模块过多 | 局部编译与影响分析的成本已经不适合当前变化规模 |
| 构建脚本、插件、Variant、source set 或编译器配置变化 | Gradle 需要重新计算任务和构建模型 |
| 依赖变化无法由依赖增量明确处理 | 需要重新解析依赖并刷新 classpath 与 APK 内容 |
| 增量编译失败且结果允许回退 | 当前局部产物无法形成可信结果 |
| 再次 Run 时没有新变化，且用户选择继续构建 | 使用 Gradle 刷新并运行完整基线 |
| 用户主动选择 Gradle | 用完整构建对照结果或刷新基线 |

构建文件变化不会一律直接进入 Gradle。Jugg 可以先读取依赖差异；变化能够映射为明确的库增删改时，可继续走[依赖增量编译](./incremental-compile/dependency-incremental.md)，插件、Variant 或其他工程模型变化则交给 Gradle。

## 部署失败何时会回到 Gradle

部署阶段会先按失败原因执行 Retry、Hot Fix、兼容部署或 Recover。失败仍未解决时，每台设备会返回本轮是否允许 Gradle 回退。

单设备运行中，失败允许回退且自动回退已开启时，Run 会强制执行一次 Gradle 构建并重新部署。多设备运行会汇总所有设备结果，只有整组结果都标记为允许回退时才会一起改走 Gradle；它不会只为某一台失败设备重跑完整构建。

部分失败会直接结束当前 Run，例如用户取消、设备丢失或无法安全恢复的安装错误。增量编译也可能先报告失败，提示下一次运行改走 Gradle，而不是在当前调用中继续重跑。最终 Gradle 构建或安装仍然失败时，Jugg 会保留真实错误并停止。

## Gradle 仍然负责完整工程语义

Jugg 的增量路径服务日常开发验证，完整工程语义继续以 Gradle 为准：

- 发布 APK、AAB、签名、混淆和正式构建产物；
- Gradle plugin、复杂 Variant、source set 和自定义任务；
- 编译器插件、注解处理器、插桩及非标准生成代码的完整执行；
- C/C++ 源码到 native lib 的构建，以及 ABI、NDK 和 packaging 配置变化；
- 需要完整 Manifest merge、移除旧资源 ID 或重建完整资源表的变化。

资源增量以最近一次资源表为起点，适用于开发构建。删除资源后，旧 ID 会保留到下一次 Gradle 构建；发布产物始终应由完整 Gradle 流程生成。

## 什么时候适合主动执行 Gradle

以下场景主动刷新基线通常更直接：

- 切换分支或一次拉取了大量工程变化；
- 升级 AGP、Gradle、Kotlin、R8、NDK 或关键构建插件；
- 修改 Variant、source set、Manifest placeholder、编译器插件或注解处理器配置；
- 删除 Manifest 节点、资源，或者需要确认旧构建产物已经移除；
- 增量结果与预期不一致，需要用完整构建作对照。

操作方式见[降级 Gradle 编译](../guide/downgrade-gradle.md)。完整构建成功后，Jugg 会重新收集构建产物，下一轮小范围修改仍可进入增量路径。

## 相关页面

- [降级 Gradle 编译](../guide/downgrade-gradle.md)
- [Gradle 回退能力](../capabilities/compile/gradle-fallback.md)
- [Recover 与 Retry](../capabilities/deploy/recover-and-retry.md)
- [Clean Reinstall](../capabilities/deploy/clean-reinstall.md)
- [部署策略](./deploy-strategy.md)
- [部署状态与恢复](./deploy-state-recover.md)
- [增量编译](./incremental-compile/)
