# Gradle Kotlin DSL 严格警告检查兼容修复

**Purpose** — Support strict Kotlin DSL builds.
**Decision** — Remove or locally suppress warnings in injected scripts.
**Impact** — Baseline creation respects the project warning policy.

## 问题与边界

报告 `5dfa0b8b`（Jugg 3.6.7-release）在用户开启 `org.gradle.kotlin.dsl.allWarningsAsErrors=true` 时，Jugg 注入脚本的 9 条警告被提升为错误。基线构建失败后反复进入 `not gradle compile yet`，无法开始增量编译。

仅处理 Jugg 脚本内已确认的警告，保留旧 JSON 空值兜底和反射读取语义，不修改用户严格检查设置，不调整 Gradle/Kotlin 支持范围或编译部署编排。

## 实施

- 序列化兼容读取局部抑制无用 Elvis 警告，并说明旧 JSON 的空值来源。
- 反射读取局部抑制未检查泛型转换警告。
- 删除 Native strip 工具备份私有方法的无用参数。
- 通过现有任务重新生成 init script，不手改生成文件。

## 验证策略

自动化具有价值：真实 Gradle 编译并运行生成脚本，断言输出快照包含 app/lib 和自定义构建目录。复用 L2 owner `ReadProjectInfoGradleCompatTestBase`，新增继承到各版本的严格检查场景；保留默认场景。先确认 Gradle 9 严格场景失败，再实施修复。

定向执行 Gradle 5.4.1、6.8、7.3.3、9.2.1 的严格与默认基础场景，及 `ReadProjectInfoScriptContentTest`。不涉及编译部署编排，无需新增设备 Flow。验证结果见下文。

## 文档影响

更新 AI 知识库的脚本兼容约束。用户操作、入口和能力范围保持一致，无需修改 Wiki。

## 验证结果

- 修复前：新增 Gradle 9.2.1 严格检查用例失败，3 处反射泛型转换被提升为 `Script compilation errors`，退出码非零。该版本编译器输出与用户现场的 9 条警告数量不同，但复现了同一严格检查失败边界。
- 修复后：Gradle 5.4.1（Java 8）、6.8（Java 11）、7.3.3（Java 17）、9.2.1（Java 17）分别通过默认与严格场景，共 8 项；`ReadProjectInfoScriptContentTest` 14 项通过，总计 22 项通过、0 失败、0 跳过。断言真实生成项目快照、app/lib 模块和自定义 build directory。
- `:main:buildReadProjectInfoScript` 由测试构建执行，生成资源未纳入 Git；`git diff --check` 通过。本次未新增或修改日志。
- 定向命令：`JAVA_HOME=<Java 17> ./gradlew -I /tmp/jugg-strict-test-filter.gradle :main:test`，使用 `--tests` 仅选择上述 8 个真实 Gradle 场景与生成契约类。
- 仓库已有 `LibraryTestApkBuildHistoryTest.FakeGitManager` 缺少 `findNearestFirstParentCommit` 接口实现，阻断全体测试源码编译。临时 init script 在 `gradle.projectsEvaluated` 中对 `:main:compileTestKotlin` 排除 `**/LibraryTestApkBuildHistoryTest.kt`，未修改该文件。外层 Gradle 7.3.3 使用 Java 17 运行，避免 Java 21 与 Groovy init script 的 class-file 兼容问题。上述临时脚本不进入提交。
- 此回归保护注入脚本编译与项目快照输出；未在用户工程中验证完整 APK 编译及设备部署。
