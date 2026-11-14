# ARouter project KSP 环境复现

> 2026-10-14 状态更新：本页记录修复前的负向复现。实现双引擎生成后，该负向 Flow 已标记为历史用例并停止执行；用户明确延后工程编译 Flow，本次以仓库内固定 L1/L2 用例验证。当前实现与验证见 [KSP 生成修复](./arouter_ksp_generation_fix.md)。

## 目标与失败证据

用户要求先在 `/Users/wormchen/IdeaProjects/demo/aigc-bot-client` 复现真实工程环境，再在 Jugg 测试中复现。
report `fb7473c7` 的增量失败为 `Expected one ARouter processor JAR for module, found []; paths=[]`。
用户环境包确认失败模块 `:module:debug` 使用 KMP + `ksp project(':infra:arouter:arouter-ksp')`；此前本地 Demo 验证的是 KAPT，这个差异无法用同一魔改 APT JAR 的成功覆盖。

## 实际实现

Demo 新增独立构建 `arouter-ksp-reproduction`，保留 Gradle 8.11.1、AGP 8.10.1、Kotlin 2.1.20、KSP 2.1.20-1.0.32、JDK 17、SDK 36，以及 commonMain / androidMain、项目处理器依赖、KSP1、模块参数和用户的魔改 KSP / annotation 源码。
Android runtime 使用原 Demo AAR 的 classes.jar；未引入 Compose、其他 KSP 处理器或完整业务依赖。复现终止于处理器查找阶段，不覆盖这些系统和设备部署。

Jugg 新增 `ARouterKspCompileFlowTest.kmpProjectKspRouteChangeFailsWithoutPublishingStaleIndexes`：

1. 从 `JUGG_AROUTER_KSP_PROJECT` 复制外部工程到临时目录，排除构建缓存，不修改原 Demo。
2. 实际运行 Gradle assemble、项目 KSP 和当前 Jugg init script，加载真实项目快照。
3. 检查项目处理器 JAR 的 KSP provider、生成的 Kotlin 索引、业务 class 和 APK。
4. 修改 Fragment 的 `@Route` 路径并调用真实 `SourceCompiler`。
5. 验证该文件编译失败、无可部署产物、旧 KSP 索引不变。

不修改生产逻辑，也不预先手工注入 ModuleInfo 或处理器路径。后续实现 KSP 增量支持时，需要把这个负向复现升级为索引与路由生效的正向回归。

## 测试价值与 owner

这是跨 Gradle 项目信息采集、ARouter 生成和源码编译的流程复现，owner 为 `SourceCompiler` 的 ARouter 生成前置阶段，位于 `main/src/test`，不依赖 IDE。
它保护缺少可运行处理器时不发布旧路由索引的结果契约，同时固定真实失败输入。没有生产测试 seam，也不通过生产源码字符串检查替代行为。
层级按真实工程编译 Flow 验证；不声明包含 IDE Run 或部署的完整 L3。

## 运行方式与结果

设置 `JAVA_HOME` 为 JDK 17，`ANDROID_HOME` 为带 Android 36 的 SDK。

```sh
JUGG_AROUTER_KSP_PROJECT=/Users/wormchen/IdeaProjects/demo/aigc-bot-client/arouter-ksp-reproduction \
  ./gradlew :main:cleanTest :main:test --tests '*ARouterKspCompileFlowTest' --no-build-cache --console=plain
```

未指定外部 fixture 时用例跳过；已指定但缺少 SDK 或基线构建失败时明确失败。
`cleanTest --no-build-cache` 用于清除上次测试结果并禁止从缓存恢复，因为外部 fixture 不属于 Gradle 测试输入。

2026-10-14 验证：Demo `:app:assembleDebug` 成功，实际 KSP classpath 包含项目处理器 JAR，但 Jugg 快照的 APT / KAPT / KSP 依赖均为空；上述定向用例禁用缓存后执行 1 项、跳过 0、失败 0。
用例输出确认 Jugg 实际失败为 `Expected one ARouter processor JAR for module.debug, found []; paths=[]`，失败来源为 `ARouterProcessorCompatibility.projectProcessor`。
报告模块名由 IDE 重命名为 `module`，复现工程使用 Gradle 名 `module.debug`；处理器查找边界与空路径错误相同。
日志保存在 `main/build/test-results/test/TEST-com.sickworm.intellij.jugg.compiler.ARouterKspCompileFlowTest.xml`，Gradle 基线日志为 `/tmp/jugg-arouter-ksp-baseline.log`，定向测试日志为 `/tmp/jugg-arouter-ksp-flow.log`。
测试通过仅代表问题成功复现和失败隔离检查通过，问题仍未修复。

## 文档与变更边界

本次仅新增复现工程、流程用例与说明，没有功能或架构改动，不需要同步用户 Wiki 或修改已有知识库行为描述。
原工作区已有生产代码、知识库及 Demo 改动均保留；提交只包含本次新增文件。
