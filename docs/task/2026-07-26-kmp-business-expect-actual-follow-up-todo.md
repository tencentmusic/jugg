# KMP 业务 expect/actual 增量编译后续 TODO

## 背景

在执行 `2026-07-26-kmp-business-expect-actual-incremental-compile-plan.md` 时，真实 Kotlin 1.9/2.1 profile 暴露出两个不属于 complementary-files 读取本身的问题。主方案当前边界已完成，本 TODO 是后续会话的独立实施入口。

## 新会话交接基线

- 当前主线基线：`2c7d01be2 [docs] support KMP expect actual incremental compilation`。该提交是测试、project-info、cache 查询、tracker 写回、产物链、设备 L3 和文档提交 squash 后的完整结果；不要再按 squash 前的分片 commit 判断完成状态。
- 开始前按仓库 `AGENTS.md` 阅读 `00_overview.md`、`99_index.md`、`98_code_map.md`、`02_compile_source.md`、`04_engineering_project.md`、`06_testing.md` 和 `09_plugin_runtime_debug.md`；出现真实编译失败时同时读取 `android_demo_project/build/jugg/log/compile_latest.log`。
- 主方案阶段 2-5 已完成。Kotlin 2.1 commonMain/androidMain 双向闭包、新增 pair tracker 写回、class -> D8 -> staging -> app APK ownership 和设备 Full Swap 已通过；Kotlin 2.3 expect-only/actual-only 已通过。
- `ModuleInfo.sourceDirs` 是全部有效源码根的扁平集合；Gradle common roots 同时加入其中。`kotlinCommonSourceDirs` 是 Gradle authoritative 的 common 子集，必须保持 `kotlinCommonSourceDirs ⊆ sourceDirs`，但不能用 `sourceDirs` 反推 common 身份。
- common 信息/cache 缺失、新增单侧声明继续 best effort：只打印 debug，由 Kotlin compiler 决定结果，不主动 Gradle fallback。
- 删除场景仍不处理；不全量编译 common/platform source set；不建立 PSI/语法索引；不按文件名、目录名或声明名配对；不增加 Kotlin/Compose 版本白名单。
- 建议实施顺序：先完成 TODO 2 Kotlin 1.9 baseline 旧 actual 隔离，补齐 direct commonMain/androidMain profile 矩阵；再完成 TODO 1 fragment graph。

### 生产代码入口

- `KotlinCompiler.kt`：Android owner、requested + complementary closure、common subset。
- `KotlinCompilerInvoker.kt`：项目 Kotlin compiler invocation、cache 查询和成功后写回。
- `K2JVMCompilerIsolate.kt`：isolated compiler arguments、tracker Services 和最终成功 tracker 结果。
- `KotlinComplementaryFilesCache.kt`：项目 Kotlin incremental cache capability adapter。
- `GradleProjectInfoReader.kt`：Android Kotlin task、common roots；TODO 1 的 fragment graph 应从同一 authoritative task/compilation 继续读取。

## TODO 1：中间 source set fragment graph

### 现象

Kotlin 2.1 的 `sharedMain actual` 与 `commonMain expect` 同时作为 `-Xcommon-sources` 输入时，compiler 报告 expect/actual 位于同一 module。

Gradle `compileDebugKotlinAndroid` 的真实参数除 `-Xmulti-platform` 外还包含：

- `-Xfragments`
- `-Xfragment-sources`
- `-Xfragment-refines`

其中 `sharedMain:commonMain` 表达中间 source set 的 refinement 关系。现有 `ModuleInfo.kotlinCommonSourceDirs` 只能判断 common 输入，无法表达 fragment 身份和 refinement graph。

### 待办

1. 从选中的 Android Kotlin compilation/task 读取 authoritative fragment graph，不根据 source-set 目录名猜测。
2. 设计兼容旧 project-info 的非空数据结构，并同步序列化、复制和合并链。
3. capability-based 构造 fragment 参数，不增加 Kotlin 版本白名单。
4. 恢复 `KmpComposeFlowReproTest#compileIntermediateSharedMainActual` 验收。

### 当前红灯与验收

- 入口：`idea/src/test/java/com/sickworm/intellij/jugg/manager/JuggCompilerTest.kt` 中的 `compileIntermediateSharedMainActual`。
- 当前失败应是 compiler 报告 expect/actual 位于同一 module，而不是 sharedMain 文件未进入变更范围；common roots 已同时存在于 `sourceDirs` 和 `kotlinCommonSourceDirs`。
- 先补 fragment project-info 序列化、旧数据默认值、merge authoritative 规则的 L1 红灯，再修改生产代码。
- L2 通过后还需回归 common-only、actual-only、both-changed、普通 common helper 和 Compose resource；不得只用 compiler toy test 代替真实 fixture。

## TODO 2：Kotlin 1.9 baseline 旧 actual 隔离

### 现象

Kotlin 1.9 联合编译 changed expect 与 complementary actual 时，baseline Kotlin output 同时位于 classpath/friend path。旧 actual class 与本轮 actual source 同时参与解析，compiler 报告 `several compatible actual declarations`。

Kotlin 2.3 未复现该诊断，但不能通过版本白名单分支规避。

### 待办

1. 基于项目 Kotlin incremental cache 的 source-to-output 信息定位 dirty closure 的旧 JVM outputs，不按文件名或声明名推断。
2. 在不破坏 baseline、失败可恢复的前提下隔离旧 outputs；不得先删除正式产物后依赖 compiler 成功。
3. 保留未变化普通 common helper、metadata、friend path 的解析能力。
4. 恢复 `KmpComposeFlowReproTest#compileBusinessExpectActualWithKotlin19` 的 expect-only 与 actual-only 验收。

### 当前红灯与验收

- 入口：`idea/src/test/java/com/sickworm/intellij/jugg/manager/JuggCompilerTest.kt` 中的 `compileBusinessExpectActualWithKotlin19`。
- 当前失败诊断是 `several compatible actual declarations`；若失败变成 cache 缺失、common 文件未识别或 Gradle fallback，说明测试环境/基线已偏离，不应直接实现隔离逻辑。
- 隔离对象必须来自项目 Kotlin incremental cache 的 source-to-output 关系，并限定为本轮 dirty closure；不能移动整个 baseline output directory。
- 编译失败、retry 中间 attempt、KSP-only phase 均必须恢复隔离状态且不得更新 complementary cache；只有最终成功 invocation 才允许提交产物和 tracker edge。
- 需要 L1 覆盖 output 定位、隔离视图和失败恢复，再运行 Kotlin 1.9 expect-only/actual-only L2；同时回归未变化 `CommonPlatformHelper.kt` 的 baseline 解析、Kotlin 2.1/2.3 和 Compose resource。

## 定向测试与环境恢复

禁止无 `--tests` 的 `:main:test` / `:idea:test`。代表性命令：

```bash
./gradlew :idea:test --tests 'com.sickworm.intellij.jugg.manager.KmpComposeFlowReproTest.compileBusinessExpectActualWithKotlin19'
./gradlew :idea:test --tests 'com.sickworm.intellij.jugg.manager.KmpComposeFlowReproTest.compileIntermediateSharedMainActual'
```

profile 测试会修改 `android_demo_project` 的 Gradle 文件。测试或人工切换结束后执行 `bash android_demo_project/switch-kotlin-version.sh 1.9` 恢复仓库基线，并检查 `git status --short`。部分新增 pair 测试会留下已暂存后又删除的临时 Kotlin 文件或 `.kotlin/sessions/*.salive`；这些是测试产物，不得夹带提交。

完成任一 TODO 后更新本文件、主方案状态、`docs/ai_knowledge/02_compile_source.md`、`04_engineering_project.md` 和 `98_code_map.md`，并按仓库规则单独提交。两个 TODO 完成前，不宣称支持中间 source set fragment graph 或 Kotlin 1.9 baseline expect/actual 联合编译。
