# ARouter project KSP 双引擎生成修复

## 问题与范围

report `fb7473c7` 在 KMP 模块使用 `ksp project(':infra:arouter:arouter-ksp')`，Jugg 仍从 APT/KAPT 依赖查找 JSR 269 processor，报 `Expected one ARouter processor JAR ... found []; paths=[]`。此前 KAPT Demo 使用同一魔改 APT 并不能证明这个 KSP 模块可增量生成。

用户确认同时支持 KSP1/KSP2，并明确本次不做或运行耗时的工程编译 Flow。范围限于当前任务 metadata 读取、原生双引擎调用、ARouter 接线、固定 L1/L2 测试及说明；不扩大 Moshi 范围，不引入通用 processor 框架或额外 JVM 进程，不改 SourceCompiler/部署编排。

## 失败证据和测试价值

先加入用户提供的 annotation/KSP 源码固定 fixture，以及 `ARouterJuggAptProcessorTest.projectKsp*`：修改前两项均因空 APT processor path 失败，日志 `/tmp/jugg-ksp-red.log`。此前外部工程复现提交为 `16d2e89c9`，详见 [环境复现](./arouter_ksp_project_reproduction.md)。

新断言保护可观察的生成结果、参数语义、选择边界、失败恢复和 checkpoint，不检查生产源码字符串，不增加测试专用 seam：

| owner / 层级 | 验证结果 |
|---|---|
| `KspProcessorRunnerTest`，L1 | 固定 Kotlin 2.1.20 / KSP 2.1.20-1.0.32，同一真实 provider 分别运行 KSP1/KSP2；常量修改后旧路由消失、新路由生效，生成源码实际编译并反射调用 `loadInto`，验证定制 `app` 和八参数 `RouteMeta.build`；Java/Kotlin 混合聚合；失败后恢复；不读取旧 Gradle 输出 |
| `ARouterJuggAptProcessorTest.projectKsp*`，L2 | 缺少 APT/KAPT 依赖时选择真实 KSP provider；commonMain/androidMain 完整聚合、保留未修改路由；模拟已编译业务 class 和 Gradle KSP 输出基线，包含 generated source root；生成失败、类集合收缩保留发布目录 |
| `GradleProjectInfoReaderKotlinOptionsTest`，L1 | 当前 KMP task 的 project processor artifact、runtime、模式及参数保留，含值中 `=`；Kotlin 编译参数原路径回归 |
| `KspProjectSnapshotTest`，L1 | Groovy/Gson 保存 engine 与 processor 分离的依赖、模式与参数；KSP1/KSP2 bean 与无 KSP 模块往返保留；version 9 快照由两种 reader 保留原 version 并拒绝普通读取 |
| 既有 ARouter APT/KAPT、init-script 定向回归 | Java 聚合、KAPT typealias、无关 processor 隔离、失败 checkpoint 和类收缩策略保留；init script 的既有兼容约束保持 |

fixture 增加同 SPI/classpath 的 `fixture.UnrelatedProvider`，执行就失败；测试成功同时证明引擎只执行选中 ARouter。fixture 源码和 engine/processor configuration 都登记为 Gradle Test inputs，避免更换 fixture 或依赖后复用旧测试结果。测试 compiler 使用 Kotlin 2.1.20 的原始传递依赖，包含 Gradle 工程实际使用的 kotlin-reflect 1.6.10，不额外升级反射库。

## 实际修改

1. `GradleProjectInfoReader` 优先读取选中 KSP task。KSP1 使用 `options.apclasspath/apoption`、`pluginClasspath`，KSP2 使用 `kspConfig.processorClasspath/processorOptions`、`kspClasspath`，不扫描其他变体/target。无 task 时保留旧 configuration 路径。
2. 根据后续范围确认，将既有 `kspDependencies` 与新 metadata 统一收进 `ModuleInfo.kspInfo: KspInfo?`，保留 processor/runtime 两份依赖的职责。同步压缩快照的单一 KSP bean、Groovy loader、merger、CLI 路径转换/备份，以及既有 Kotlin/Moshi 消费方；snapshot version 9 → 10。Groovy loader 保留文件实际 version，普通读取按既有版本校验要求刷新旧快照。
3. `KspProcessorRunner`：KSP1 复用已有隔离 project compiler，Kotlin 2.x 仍走 classic frontend；KSP2 隔离加载项目 Analysis API engine，通过反射传入 JDK 类型，执行后恢复 context classloader 并关闭 loader。缺失/不兼容 engine 明确失败。
4. ARouter 根据实际 SPI 选择 APT/KAPT 或 KSP，歧义明确失败；参数按所选模式原样透传，不从其他模式补造。生成 Java/Kotlin 进入同一 checkpoint，按 package/文件名归一输出身份，消除 `java/`、`kotlin/` 目录差异；生成失败或收缩不发布。
5. 用户 Wiki 中英文同步新增 ARouter KSP1/KSP2 范围，保留 Moshi KSP2 的既有边界。

## 验证记录

- 首次失败：2 个固定 ARouter KSP 用例确实复现空 APT processor path。
- 双引擎定向验证已通过，包含 fresh output、实际路由执行、参数透传、混合源码、失败恢复、无关 provider 隔离和完整聚合。
- 最终定向回归：42 项、0 跳过、0 失败、0 错误；使用匹配 Gradle 的原始 Kotlin runtime 后再次通过，日志 `/tmp/jugg-ksp-final-matched-runtime.log`。
- KSP2 诊断保留原生 processor exception 和失败原因，补充后定向双引擎回归 8 项通过；完整 42 项 XML 另存 `/tmp/jugg-ksp-final-test-results`。
- `:cmd_line:compileKotlin` 与 `:idea:compileKotlin` 已通过。
- Wiki 链接/语言镜像检查及 production `npm run build` 已通过，目标中英文 HTML 包含新增 ARouter KSP1/KSP2 内容。
- 不运行 Android 工程 assemble/SourceCompiler Flow 或设备部署；先前验证“bug 仍存在”的负向 `ARouterKspCompileFlowTest` 标为历史并停止执行，正向 Flow 留待后续单独建设。

## 交付边界

只提交本次改动，不推送或创建 PR。原工作区六个已有文件改动保留，其中与 ARouter 共用文件的原日志标点修改不进入本次提交。用户在本工程更新 processor/engine/参数后仍需 Sync 和完整 Gradle 构建刷新 metadata/业务 class 基线；本次不维护 processor 原地替换检测或环境摘要。

## 依据

实际读取：`AGENTS.md`、`00_overview.md`、`99_index.md`、`98_code_map.md`、`06_testing.md`、`02_compile_custom_ui.md`、`04_engineering_project.md`、`09_plugin_runtime_debug.md`、`02_compile_source.md` 相关条目、`10_wiki_authoring.md`、`10_wiki_architecture.md`，以及 `wiki-writer/SKILL.md`、目标注解器/自定义编译器中英文页面和编译能力索引。定位证据包括原 report/环境包的既有复现、项目快照、实际 KSP API 字节码、`K2JVMCompilerIsolate`、ARouter 生成/checkpoint 实现、reader/serializer/merger 和最终测试 XML。

## KSP bean 范围收敛（2026-10-14）

- 两类 classpath 不能互相替代：provider 依赖与执行引擎分别对应 KSP1 的 apclasspath/pluginClasspath 和 KSP2 的 processorClasspath/kspClasspath；合并结构不合并角色。
- `main/build.gradle` 的四组 configuration 仅服务固定真实引擎测试，不参与生产插件依赖。保留它们以持续验证 KSP1/KSP2；根 `build.gradle` 的原有工作区改动未进入提交。
- `KspInfo` 保存全部 KSP module metadata；压缩边界用 `KspInfoSerialize` 保存 dependencyList 索引，不增加新执行模式或通用抽象。
- 旧 version 9（包括无 KSP 工程）需重新读取项目信息；不维护旧平铺字段迁移。显式跳过 version check 的既有诊断/Gradle 路径可恢复其他信息，KSP bean 缺失时为 null。
- 验证先行：旧版本拒绝测试修改前失败（`/tmp/jugg-ksp-bean-red.log`）；复用 snapshot/reader/init-script L1、真实 KSP1/KSP2 生成 L1 和 ARouter 协作 L2 owner，不为 bean getter 新增测试。定向验证日志：`/tmp/jugg-ksp-bean-validation.log`。工程编译 Flow 仍按用户要求暂缓。
- 本次为内部模型及快照结构变更，已检查 Wiki 注解处理器/自定义编译器页，无公开能力或操作说明变化，Wiki 无需重复修改。
- 模型重构首轮 45 项（0 失败/错误/跳过）通过，IDE/CLI 编译通过。为覆盖全局 version 变更，补跑既有 serializer/merger 回归与 Gradle 7.3.3 的 Java fixture init-script 执行，均通过，日志 `/tmp/jugg-ksp-bean-schema-regression.log`；不执行 Android 工程 Flow。
