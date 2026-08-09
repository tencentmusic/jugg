---
title: Android Test 流程
description: 解释 Android Test 的双 APK 基线、增量产物运行时归属、instrumentation 启动顺序，以及部署状态与测试结果为何分开提交。
status: active
tags:
  - concept
  - android-test
---

# Android Test 流程

Android instrumentation test 同时依赖被测 app APK、test APK，以及 test APK 中声明的 runner 和 target package。完整 Gradle 构建会生成这些产物并建立它们之间的关系；Jugg 在这份基线上增量编译 app 与 androidTest 源码，先让变化在正确的运行时环境中生效，再执行 instrumentation。

这意味着 Android Test 不能只按源码目录判断产物应该写入哪个 APK，也不能把测试断言失败等同于部署失败。本页解释 Jugg 如何维护双 APK 基线、按运行时归属部署增量产物，以及如何分别提交部署状态和测试结果。

## Android Test 同时依赖两组 APK

普通 App Run 只需要安装并启动 app。Android Test 还需要安装 test APK，由 instrumentation runner 在 target package 对应的进程中加载测试并驱动被测代码。因此，一次可运行的 Android Test 基线至少需要包含：

- 被测 app APK，以及它在设备上的运行状态。
- test APK、runner 和 target package。
- app 与 androidTest 源码所属的模块和运行时包。

Jugg 通过一次 Android Test 目标的完整 Gradle 构建取得这些信息。完整安装时先安装被测 app APK，再安装 test APK，避免 test APK 指向的 target package 尚未出现在设备上。

## 可信基线如何接入增量编译

基线建立后，后续 Android Test Run 不需要重复执行完整构建。Jugg 将 app 与 androidTest 源码纳入同一轮变化检测，只编译本轮修改及受影响代码，再把增量产物交给已有部署策略：

```text
可信的 app APK 与 test APK 基线
  -> 检测 app 和 androidTest 源码变化
  -> 编译本轮变化及受影响代码
  -> 按运行时归属拆分增量产物
  -> 部署到对应 app 或 test 运行时
  -> 部署成功后执行 am instrument
```

如果本轮没有文件变化，Android Test 可以直接进入空部署和 instrumentation，不必为了重跑同一个测试再次执行 Kotlin、D8 或完整 Gradle 构建。基线缺失、构建目标发生变化或现有产物不再可信时，Jugg 会重新执行 Gradle 构建，避免 app 与 test 产物来自不同基线。

## 增量产物按运行时归属部署

androidTest 源码位于 test source set，但源码目录并不能直接决定增量代码最终写入 test APK。Jugg 根据 instrumentation 的运行时包选择部署目标：

| 测试形态 | 运行时位置 | 增量产物归属 |
|---|---|---|
| Application Android Test，test APK 指向被测 app | 被测 app 进程 | 被测 app 的运行时覆盖 |
| self-targeting Library Android Test，test package 与 target package 相同 | 独立的 test package | 对应 library test APK |

这种分流让 app-style Android Test 的测试代码跟随实际加载它的 app 运行时更新，同时保留 self-targeting library test APK 的独立安装和部署状态。部署多个 APK 时，每个运行时包只接收属于自己的 class、资源覆盖和 APK 更新，避免 app 与 test 产物互相错投。

## 缺失的 Library Test APK 如何补齐

self-targeting Library Android Test 有独立的 test package，因此当前 APK 列表中必须存在对应 test APK。源码锚点能够唯一确定 library androidTest 模块、但 test APK 缺失时，Jugg 只执行该模块对应的 Android Test Gradle 任务，并将新产物作为完整 APK 安装，而不是构建项目中的所有 library test APK。

补齐成功后，Jugg 会记录近期使用过的 Gradle 任务和 APK 输出匹配信息，供后续 Android Test 完整构建回放。记录只帮助重新找到需要构建的 library test APK；产物已经被删除或失效时，仍会重新进入补齐流程。

## 部署完成后才启动 instrumentation

Jugg 在设备完成安装、代码替换或 APK 更新后，才根据 test APK 中的 runner 和当前测试范围执行 `am instrument`。部署失败时不会启动测试；部署成功后，instrumentation 输出再进入 Run 窗口和 Test Results。

多设备运行时，每台设备分别完成部署和 instrumentation，并维护独立的测试结果。任一设备部署失败、instrumentation 中止或测试失败，都会让整轮 Android Test Run 返回失败；各设备的结果仍分别保留，便于判断失败范围。

## 部署状态与测试结果分别提交

测试断言发生在部署完成之后。断言失败表示本轮测试没有通过，但不表示已经写入设备的 class、资源覆盖或 APK 更新失效。因此，Jugg 会按已完成的部署推进部署历史和运行时状态，同时把本轮 Run 标记为测试失败。

这种状态分离使下一次重跑能够继续使用已经刷新的 app 与 test 运行时。没有新文件变化时，Jugg 可以直接再次执行 instrumentation，而不会因为上一次断言失败重新编译或重装全部产物。

## 使用边界

- Android Test 仍依赖可信的 Gradle 基线；缺少 app APK、test APK 或有效测试模块信息时会回到 Gradle 构建。
- 缺失 APK 的定向补齐只适用于源码锚点唯一命中的 self-targeting Library Android Test。
- androidTest 资源、注解处理和 Debug Executor 等具体支持范围以[测试能力](../capabilities/test/)页面为准。
- instrumentation 失败不会撤销已经成功的部署，但仍会让本轮 Android Test Run 返回失败。

## 相关页面

- [Android Test 指南](../guide/android-test.md)
- [测试能力](../capabilities/test/)
- [Application Android Test](../capabilities/test/application-android-test.md)
- [Library Android Test](../capabilities/test/library-android-test.md)
- [Test Results UI](../capabilities/test/test-results-ui.md)
- [部署策略](./deploy-strategy.md)
