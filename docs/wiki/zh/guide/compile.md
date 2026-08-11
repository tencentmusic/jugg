---
title: 编译阶段说明
description: 解释 Jugg Run 内部的编译阶段、增量与 Gradle 回退，以及常见编译结果。
status: active
tags:
  - guide
  - compile
---

# 编译阶段说明

Jugg 编译发生在你点击 Jugg Run、Jugg Debug、androidTest gutter，或通过 CLI / MCP 触发运行时。大多数日常场景不需要单独打开“编译”页面来决定怎么操作；先看 [运行 App](./run.md)，本页用于解释 Run 过程中看到的编译结果、增量判断和 Gradle 回退。

## 什么时候需要看本页

适合阅读本页的场景：

- Run 输出里出现了增量编译、Gradle 回退或依赖 diff 相关提示。
- 你想判断某类修改会走增量还是 Gradle。
- `compile` 已经失败，需要定位第一条编译错误。
- 你在 CLI / MCP 中显式调用 `jugg compile`。

> [!TIP]
> 如果不确定当前改动是否适合增量，直接运行即可。Jugg 会先判断，必要时再回退 Gradle。

## Jugg 会优先尝试什么

默认情况下，Jugg 会按下面的顺序处理：

```text
检查设备和文件变化
  -> 判断是否适合增量编译
  -> 编译变化文件
  -> 检查受影响源码并补编译
  -> 进入部署
```

如果前置检查发现风险较高，Jugg 会跳过增量，进入 Gradle 构建。Gradle 构建完成后，Jugg 会重新收集 APK、classpath、project info 等基线数据，后续才能继续稳定增量。

## 常见修改类型

| 修改类型 | 默认行为 | 说明 |
|---|---|---|
| Java / Kotlin 小范围修改 | 源码编译 | 结构变化时会通过重编译/扩散编译继续处理受影响源码 |
| layout / drawable / values 修改 | 资源增量编译 | 涉及资源符号或绑定逻辑时会生成 `R.java` 或 ViewBinding/DataBinding 源码 |
| `AndroidManifest.xml` 简单修改 | Manifest 增量处理 | 通过更新 APK 并重签名生效 |
| assets 修改 | overlay 下发 | 不需要完整 Gradle |
| native lib / `.so` 产物修改 | so 更新 | 写入 APK 并重签名；C/C++ 源码仍需先由 Gradle/NDK 产出 `.so` |
| Gradle 脚本或依赖修改 | 进入回退或依赖 diff 判断 | 取决于依赖变化判断和用户选择 |
| 仅依赖库版本变化 | 可选择依赖库增量 | 需要用户确认 diff，比完整 Gradle 少跑无关模块 |
| 大批量跨模块修改 | 回退 Gradle | Jugg 会优先保证稳定性 |
| release 混淆相关修改 | 谨慎增量 | 运行时异常时建议 Gradle 验证 |
| 删除类、资源或 Manifest 节点 | 谨慎处理 | 删除语义需要完整基线刷新时回到 Gradle |

## 什么时候会回退 Gradle

常见回退原因包括：

- 你选择了强制 Gradle 构建。
- 没有检测到可增量编译的文件变化。
- 修改文件过多或跨模块过多。
- 设备、安装状态或部署历史不满足增量条件。
- build target 发生切换，例如 App 与 androidTest 之间切换。
- 修改了构建脚本、依赖或构建插件配置。
- 增量编译失败后需要重新建立可信基线。

回退并不代表 Jugg 失效。它表示当前修改更适合交给 Gradle 处理。

## 依赖库增量编译

当 Jugg 检测到 build 文件变化时，可能会给出几个选择：

| 选择 | 含义 |
|---|---|
| Fallback to Gradle | 直接完整 Gradle 构建 |
| Find out changed Libraries | 执行依赖 diff，确认后只编译变化依赖库 |
| Ignore build changes | 忽略本次 build 文件变化，继续按增量状态运行 |
| 关闭弹窗 | 取消本轮运行 |

适合选择依赖库增量的场景：

- 只升级或回退了依赖库。
- build 文件修改对当前 APK 产物没有影响。
- 你能确认 diff 结果符合预期。

如果不确定，选择 Gradle 更稳妥。

## 如何看编译结果

你可以从运行输出或日志中判断当前状态：

| 日志 / 输出 | 含义 |
|---|---|
| `Compile files:` | Jugg 正在编译检测到的变化文件 |
| `Detect effected sources` | 编译成功后发现受影响源码，进入重编译/扩散编译 |
| `Compile finished` | 本轮编译结束 |
| `fallback` / `Fallback` | 当前进入或准备进入 Gradle 回退 |
| `Found incremental compile error` | 增量编译失败，需要查看具体错误 |
| `No file changes` | 当前没有发现可处理的文件变化 |

日志位置：

```bash
build/jugg/log/compile_latest.log
```

## 编译失败时怎么做

建议按顺序处理：

1. 先看运行输出中的第一条明确错误。
2. 打开 `build/jugg/log/compile_latest.log`，搜索 `Found incremental compile error`、`aapt2`、`unresolved reference` 等关键词。
3. 如果是源码或资源本身错误，先修复代码。
4. 如果是构建脚本、依赖、资源表或 release 混淆相关问题，执行一次 Gradle 构建重新建立基线。
5. 如果要提交问题，先备份 `build/jugg/log/` 和 `build/jugg/database/`。

> [!WARNING]
> 不建议在没有备份现场的情况下直接删除整个 `build/`。这会让后续排查失去日志和数据库证据。

## 建议的使用习惯

- **小范围业务代码修改**：直接 Jugg Run。
- **修改资源或 layout**：直接 Jugg Run，必要时观察是否触发源码补编译。
- **修改 Gradle、依赖、插件或 source set**：优先准备接受 Gradle 回退。
- **切分支或拉取大量代码后**：建议先执行一次 Gradle 构建。
- **修改 static / companion / Kotlin 顶层声明、启动初始化或单例缓存**：编译后主动重启 App 更稳。
- **删除类、资源或 Manifest 节点后结果异常**：优先 Gradle 构建对照。
- **release 问题**：先用 Gradle 构建确认是否为增量链路差异。

## 直接降级和取消

当你明确希望用 Gradle 完成本轮构建，可以使用直接降级按钮或 `jugg gradle-build`。常见原因包括：

- 手动删除过部分 `build/` 目录，导致增量依赖缺失。
- 认为本次增量结果不正确，需要完整 Gradle 对照。
- 修改了需要完整 Gradle 链路确认的注解处理、插桩或构建逻辑。

如果误触发 Gradle 回退，可以取消。取消会停止本轮 Gradle 构建；下一次运行仍会优先尝试增量。

## 相关页面

- [运行 App](./run.md)
- [增量编译](../concepts/incremental-compile/)
- [依赖库增量编译原理](../concepts/incremental-compile/dependency-incremental.md)
- [编译能力](../capabilities/compile/)
- [源码编译](../capabilities/compile/source-compile.md)
- [重编译/扩散编译](../capabilities/compile/recompile-propagation.md)
- [依赖库增量编译](../capabilities/compile/dependency-incremental.md)
- [资源编译](../capabilities/compile/resource-compile.md)
- [AndroidManifest 编译](../capabilities/compile/manifest.md)
- [so 更新](../capabilities/compile/so-update.md)
- [Gradle 回退](../capabilities/compile/gradle-fallback.md)
- [编译失败](../troubleshooting/compile-failed.md)
- [改动没有生效](../troubleshooting/changes-not-applied.md)
- [限制](../reference/limits.md)
