---
title: 限制
description: 汇总 Jugg 增量编译、部署、Debug、androidTest 和工具能力的边界。
status: active
tags:
  - reference
  - limits
---

# 限制

Jugg 追求的是在常见开发循环中减少完整 Gradle 构建次数，而不是替代 Android Gradle Plugin、Gradle 或 Android Studio 的所有能力。本页只汇总跨编译、部署、Debug、androidTest 和工具链的总边界；单项能力是否支持、触发什么结果，见对应能力页。

> [!IMPORTANT]
> 当 Jugg 行为和 Gradle 构建结果不一致时，以 Gradle / Android Studio 原生构建结果为准。建议执行一次完整 Gradle 构建重新建立基线。

## 总体边界

| 领域 | Jugg 优先支持 | 需要回退或原生验证的场景 |
|---|---|---|
| 源码修改 | 小范围 Java/Kotlin 修改 | 大批量跨模块修改、复杂编译器插件行为 |
| 资源修改 | 常见 `res/`、`assets/`、Manifest 修改 | source set、variant、复杂资源生成逻辑变化 |
| 部署 | install、code swap、full swap 等常见开发链路 | 设备状态异常、APK 结构大变更 |
| release | 尽量保持混淆映射一致 | R8 复杂优化、mapping 不完整或运行时不一致 |
| androidTest | 常见 app androidTest 运行 | 复杂测试 APK 归属、测试目标切换 |
| MCP / CLI 工具 | 辅助编译、部署、日志和 UI 验证 | 不替代人工判断和真实设备验证 |

## 编译限制

以下变化更容易触发 Gradle 回退，或者建议主动执行 Gradle 构建。更深的原因说明见[Gradle 回退与基线重建](../concepts/gradle-fallback-baseline.md)。

- 修改 Gradle 脚本、插件配置、版本目录或依赖声明。
- 切换 build variant、build target、运行配置或 androidTest 目标。
- 一次修改大量 Java/Kotlin 文件或多个模块。
- 修改会影响注解处理、KSP/KAPT、字节码插桩或代码生成的配置。
- 修改资源 source set、Manifest placeholder 来源或资源生成插件。
- release 场景中遇到混淆、反射、注解或类型引用相关运行时异常。

> [!WARNING]
> 变更依赖 Gradle task 的副作用时，例如生成源码、复制资源、重写 Manifest 或修改 classpath，应执行 Gradle 构建刷新基线。Jugg 不承诺复现任意 Gradle task 行为。

## 部署限制

Jugg 部署依赖当前设备、已安装 APK 和本地部署历史之间的一致性。以下场景可能需要重新安装或完整构建：

- 设备断开、重启或状态不可用。
- App 被用户手动卸载或由其他渠道覆盖安装。
- APK 结构变化，例如 split APK、dynamic feature 或 ABI 产物变化。
- Manifest、资源表或 dex 状态与本地部署历史不一致。
- 本地 `build/jugg/database/` 状态损坏或缺失。

## 资源和 Manifest 限制

资源增量依赖已安装 APK 或最近一次部署后的资源表。

建议回退 Gradle 的场景：

- 修改了资源目录结构或 source set 规则。
- 修改 Manifest placeholder 的来源或复杂合并规则。
- 修改 dynamic feature 与 base APK 的资源依赖关系。
- 资源混淆、资源 ID 或 `R.styleable` 在运行时表现异常。

## Debug 限制

Jugg Debug 会在 Jugg 编译部署成功后接入 Android Studio 的 Java Debugger。它仍受 Android Studio、设备和 App debuggable 状态影响。

常见边界：

- App 未进入 debuggable 状态时，断点不会生效。
- Java debugger attach 失败时，需要查看 Android Studio `idea.log` 与 Jugg 日志。
- 原生 Android Studio Debug 能命中但 Jugg Debug 不能命中时，应优先排查 attach 阶段。

## androidTest 限制

Jugg 支持常见 app androidTest 运行链路，但以下情况可能需要 Gradle 或手动确认：

- 从普通 App Run 切换到 androidTest，或反向切换。
- library Test APK 需要重新生成或历史记录不可用。
- 测试目标、过滤条件或 instrumentation 参数发生复杂变化。
- 测试依赖的 App APK 与 Test APK 不是同一构建基线。

## MCP / CLI 工具限制

MCP 和 CLI 是辅助能力，不是完整测试框架或设备自动化平台。

使用时需要注意：

- 工具输出依赖当前 Jugg 运行状态和日志状态。
- UI 检查依赖设备上的实际页面和可获取的 ViewHierarchy 信息。
- 日志等待、crash 检测和布局验证都可能受设备性能、页面时序和 App 实现影响。
- 工具结果应作为证据链的一部分，而不是唯一结论。

## 推荐主动 Gradle 的场景

如果遇到以下情况，建议直接执行一次 Gradle 构建：

- 刚切换分支或拉取了大量代码。
- 升级 AGP、Kotlin、Gradle 或重要构建插件。
- 修改 build script、依赖、source set 或 variant 相关配置。
- release 构建出现运行时异常。
- dynamic feature、multi-apk 或资源混淆表现不稳定。
- 你需要确认一个问题是否由 Jugg 增量链路引入。

## 相关页面

- [Jugg 工作原理](../concepts/how-jugg-works.md)
- [增量编译](../concepts/incremental-compile/)
- [资源编译](../capabilities/compile/resource-compile.md)
- [编译问题排查](../troubleshooting/compile.md)
