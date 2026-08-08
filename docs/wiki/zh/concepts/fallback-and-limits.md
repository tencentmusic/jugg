---
title: 重试、重装与 Gradle 构建
description: 解释 Jugg 一次 Run 在部署失败、设备状态不一致或构建基线失效时，如何选择重试、切换部署方式、重装 APK 或执行 Gradle 构建。
status: active
tags:
  - concept
  - fallback
---

# 重试、重装与 Gradle 构建

Jugg Run 包含增量编译和设备部署两个阶段。编译阶段无法生成可信产物时，需要 Gradle 重新构建工程；增量产物已经生成时，Jugg 会先保留这些产物，修复传输、运行时或设备状态，再决定是否扩大到完整构建。

因此，看到 Retry、Hot Fix、Recover、Reinstall 或 Gradle 时，可以先判断一个问题：本轮是否已经得到可信的增量产物。

```text
检查增量编译条件
  -> 无法生成可信增量产物：执行 Gradle 构建
  -> 已生成可信增量产物：部署到设备
       -> 传输暂时失败：Retry
       -> 当前进程无法接收：Hot Fix / 兼容部署
       -> 设备状态对不上：Recover / Reinstall
       -> 恢复仍失败且允许回退：整轮 Run 改走 Gradle
```

Manifest patch、已经生成的 native lib 等内容写回 APK 并重新安装，属于正常的增量部署路径。它们只有超出增量处理范围或安装失败无法恢复时，才会进一步触发 Gradle。

## 已有增量产物时，先修复部署

编译已经成功后，class、DEX、资源或 assets 等产物可以继续复用。此时直接重新执行 Gradle 会丢掉已经完成的增量工作，因此部署阶段先根据失败位置改变最小条件。

### 传输暂时失败时重试

ADB 短暂离线、可恢复的 I/O 失败或部署超时，可以在条件变化后重试。例如等待设备重新连接、降低单次下发的数据量，或者在恢复设备状态后重新发送原有产物。

重试次数有明确上限。相同条件下持续失败时，流程会进入下一种恢复方式或结束当前 Run，不会无限重复同一条命令。

### 当前进程无法接收时改为重启后生效

class 无法在线替换、JVMTI 不可用或 agent 无响应时，增量编译结果本身仍然可用。Jugg 会把在线部署改成 Hot Fix 或兼容部署，让 App 重启后加载新的 DEX、资源或 assets。

这个变化调整的是产物生效方式，工程源码和 Gradle 构建基线保持不变。兼容部署的触发条件和产物转换见[兼容部署](./compat-deploy.md)。

### 设备状态对不上时恢复或重装当前 APK

增量部署必须从设备上一轮成功状态继续。当本地部署历史、Android Studio deployment cache 和设备 overlay checkpoint 对不上，Jugg 会先执行 Recover，确认设备是否仍能承接本轮差异。

Recover 失败、App 未安装或已被外部覆盖时，Jugg 重新安装最近一次可信 APK，并重建设备 checkpoint。这里复用的是已有 APK，通常不需要重新编译工程。状态判断过程见[部署状态与恢复](./deploy-state-recover.md)。

## 部署恢复失败时，整轮 Run 可能改走 Gradle

Retry、Hot Fix、兼容部署和 Recover 都无法完成本轮部署时，部署结果会说明这次失败是否允许 Gradle 回退。自动回退开启且结果允许时，Run 会从编译阶段重新开始，执行 Gradle 构建并安装完整 APK。

多设备运行会汇总所有设备结果。只有整组结果都允许回退时，整轮 Run 才会一起改走 Gradle；它不会只为某一台设备执行一次不同的完整构建。

用户取消、设备丢失或无法安全恢复的安装错误会直接结束当前 Run。Gradle 构建或完整 APK 安装再次失败时，流程保留最终错误并停止。

## 没有可信增量产物时，Gradle 重新生成构建结果

增量编译开始前，Jugg 会检查当前工程是否仍能沿用最近一次 Gradle 构建结果。以下情况需要重新生成 APK、classpath、资源表和生成代码：

| 触发条件 | Gradle 需要重新处理什么 |
|---|---|
| 首次运行或工程信息缺失 | 生成后续增量编译所需的完整起点 |
| App 与 androidTest 构建目标切换，或编译命令变化 | 为新的目标生成匹配的 APK 和编译上下文 |
| 变化源码跨越的文件或模块过多 | 用完整构建替代成本过高的局部编译和影响分析 |
| Gradle plugin、Variant、source set 或编译器配置变化 | 重新计算工程模型、任务和构建参数 |
| 依赖变化无法映射为明确的库增删改 | 重新解析依赖，刷新 classpath 和 APK 内容 |
| 增量编译失败且结果允许回退 | 丢弃无法组成可信结果的局部产物 |
| 再次 Run 时没有新变化，且用户选择继续构建 | 重新运行完整构建和安装流程 |
| 用户主动选择 Gradle | 刷新基线或用完整构建对照增量结果 |

修改构建文件后，Jugg 可以先读取依赖差异。变化能够明确归结为依赖库增删改时，仍可继续[依赖增量编译](./incremental-compile/dependency-incremental.md)；插件、Variant 和其他工程模型变化交给 Gradle。

部分增量编译错误会先结束当前 Run，并提示下一次运行改走 Gradle。这种处理保留当前失败现场，避免在同一次调用中连续执行两个耗时且失败原因不同的编译流程。

## Gradle 成功后，下一轮继续增量

Gradle 构建成功后，Jugg 会重新读取 APK、class、classpath、资源表、mapping 和生成代码，形成新的本地构建基线。完整 APK 安装成功后，设备也回到与这份构建结果一致的状态。

下一次修改仍会先检查是否适合增量。Gradle 构建不是永久退出增量模式，而是为后续 Run 提供新的起点。

## 发布与复杂构建始终由 Gradle 完成

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
- [Recover 与 Retry](../capabilities/deploy/recover-and-retry.md)
- [Clean Reinstall](../capabilities/deploy/clean-reinstall.md)
- [部署策略](./deploy-strategy.md)
- [部署状态与恢复](./deploy-state-recover.md)
- [增量编译](./incremental-compile/)
