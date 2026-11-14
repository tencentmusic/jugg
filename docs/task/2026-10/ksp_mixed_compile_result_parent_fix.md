# KSP 生成 Kotlin 后混合语言结果合并失败

## 现场与根因

2026-10-15 00:12:57～00:13:02，aigc-bot-client 的 app 同时编译 JavaRouteActivity.java 与 MainActivity.kt。`compile_latest.log` 已滚动至下一次初始化；实际失败现场为 `compile_2026-10-15_00-12-38.0.log`。

日志 671～699 行：项目 KSP2 成功生成 Kotlin 索引；782～795 行：Kotlin 与 Java 编译都成功；796～848 行：`SourceCompiler.compileLanguageStages()` 在合并 Java 结果时经 `CompileResult.plus()` / `CompileTask.plus()` 抛出 `parentTask not the same`，触发 Gradle 回退。

累计结果初始绑定 preparation 的 compileTask。合并 Kotlin 子任务时，generated Kotlin 被加入累计 task.files，累计任务不再与 Java 子任务持有的 compileTask 相等；累计任务的 parent 是更上层 source task，也不同于 Java 子任务的 parent。无新增 Kotlin 文件时累计任务保持值相等，因此普通混合语言路径可正常运行。反证检查已核对原生 KSP2 和两种语言均成功，没有生成或源码诊断失败；直接异常栈与当前 SourceCompiler 第 137 行匹配，问题属于生成器下游的协作边界，不是 KSP 引擎执行失败。

## 修复范围

仅调整 SourceCompiler 中 Kotlin 结果的累计归属，保持 preparation 的 compileTask 稳定，再沿既有路径合并 Java 结果。保留所有阶段 details/outputs、Kotlin-before-Java 顺序、取消/进度传播、失败与重试策略；不放宽全局 CompileTask.plus 的父任务校验，不修改 KSP runner、快照或客户工程。

## 测试价值与验证

在已有 `ARouterJuggAptProcessorTest` owner 增加两项 L2 协作回归：同一项目 processor 分别运行真实 KSP1/KSP2，common/Android Kotlin 与 Java 混合输入，两语言相互引用，生成 Kotlin 与业务源码进入真实语言编译并交给 D8；验证成功 details、业务 class、路由索引 dex 以及反射 loadInto 的路由集合。不写 bean/getter 或私有方法测试，不增加生产 seam。

既有 APT/KAPT 混合编译与 Java 失败恢复用例保护未命中新增 Kotlin 条件的正常/失败路径。按用户既有约定不执行 Android 工程 assemble、部署或耗时工程 Flow；固定 fixture 的 SourceCompiler L2 使用 mock context，无工程构建。

验证日志：`/tmp/jugg-ksp-mixed-merge-red.log`、`/tmp/jugg-ksp-mixed-merge-validation.log`。修改前两个真实引擎用例均抛出与现场一致的 `Combining CompileTask failed: parentTask not the same`，保留失败日志后修改生产代码。修复后 8 项通过、0 失败/错误，1 项已有 `customFixtureAcceptsAppAndEightArgumentRouteMetaBuild` 用例因历史定制 APT fixture 前置条件跳过；新 KSP1/KSP2 混合用例均实际执行并产出路由 DEX，原有混合 APT、Java 失败恢复、DEX 失败恢复也通过。`:idea:compileKotlin` 通过。

## 依据与文档

实际读取：知识库 `00_overview.md`、`99_index.md`、`98_code_map.md`（本会话前序），本轮 `09_plugin_runtime_debug.md`、`02_compile_core.md`、`02_compile_source.md`，以及已读取的测试权威 `06_testing.md`。证据包括现场完整异常栈及 KSP/语言成功日志、IDE index 导航/引用、SourceCompiler、CompileTask/CompileResult 和已有 ARouter 固定测试。同步源码编译知识库，并检查 Wiki 用户说明是否受影响。

Wiki 中英文注解处理器页面已经说明 ARouter KSP1/KSP2 生成 Java/Kotlin 并进入后续编译，本次恢复该既有行为，不新增能力或用户操作，不修改 Wiki。源码编译知识库补充稳定父任务的汇总边界。

只提交本次 SourceCompiler 补丁、已有 owner 的两项测试、知识库补充和本方案。工作区原有六个修改保留，不推送、不创建 PR，不重跑客户现场或部署插件。
