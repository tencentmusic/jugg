# styleable 基线同步与目标 APK 归属修复

## 问题与范围

Jojo 在 2026-10-28 22:43:20 修改 integrations main Manifest 后，主 APK 和 library Test APK 的独立 Manifest 编译成功，但 `StyleableFileGenerator` 为 Test APK 仍选择 `[app]`，并连续提示源 Manifest 没有 package、styleable 文件生成失败。

现场日志：`/Users/wormchen/IdeaProjects/jojo/android/build/jugg/log/compile_2026-10-28_22-39-26.0.log`，重点为 1271～1276、1376～1381 行。原工程存在 `app/build/intermediates/compile_and_runtime_r_class_jar/debug/processDebugResources/R.jar`，Jugg 基线副本缺少该目录。App 的 Gradle namespace 为 `com.tme.jojo`，源 Manifest 未声明 package。

根因有三处：`allBuildPaths` 漏掉 AGP 9 aggregate R 目录；styleable 把没有 split name 的 Test APK 当成主 App；旧版 class 输出回退路径直接读 Manifest package，没有优先使用已有 namespace。

按用户要求，仅修改 R.jar 基线同步、styleable 目标模块与 namespace 读取。四 APK 重签/重装问题不纳入本次修改；APK 归属规则、部署编排和 androidTest resource 支持范围保持原状。

## 实现

- `ModuleBuildPathInfo.allBuildPaths` 增加 `rFilePathDirAgp9`，复用本地与远端现有同步流程，覆盖主 variant 和 androidTest variant。
- `StyleableFileGenerator` 通过 `ApkInfo.isTestApk` 与物理 APK 路径识别目标，再按 applicationId 查找独立 androidTest module；测试模块缺失时只跳过 styleable 辅助输入，不借用 App 声明。
- Java classpath 回退使用选中模块的非空 namespace；缺失时保留源 Manifest package 的旧版读取方式。正常主 APK 和 dynamic feature 的 aggregate R 选择方式保留。

## 验证设计

同步后能否保留真实构建文件，以及生成的 styleable 声明是否来自正确 APK，均是可观察行为，通过测试价值门禁。未新增测试专用生产接口。

- 将现有 `StyleableFileGeneratorTest` 从 idea 移到 main：用 Mockito 隔离编译上下文，使用真实 ASM class 与 ZIP R.jar，替换依赖 demo 全量构建的两个粗粒度 smoke 用例。8 个 L1 用例断言最终 styleables.txt 内容，覆盖主 App、多 library Test APK、无宿主 App、测试模块缺失、namespace 缺失/优先级、无源 Manifest 与 dynamic feature。
- 在 `SyncLocalClasspathCommandTest` 增加真实 rsync 回归，检查 AGP 9 与旧版 aggregate R 在 debug/debugAndroidTest 基线中均保留，同时排除无关文件；不只检查路径 getter。
- 定向执行已有 `ModuleBuildPathInfoTest`、项目脚本内容与 Gradle 5/6 兼容测试，并构建插件核对分发字节码。
- 本次没有修改 compile/deploy 编排，采用上述 L1 与真实同步验证，不扩展真机部署流程。

## 验证结果

- 修改前，styleable 8 个用例中 6 个失败，直接复现读取 App 声明、namespace 未生效和测试模块缺失时误用主包的问题；日志为 `/tmp/jugg-styleables-red.log`。
- 同步测试首次断言误把 rsync 输出根当成源目录内容，修正为实际的 `<destination>/<source-name>` 布局后，暂时撤回 AGP 9 目录补丁重新验证：旧版两个 R.jar 均成功同步，AGP 9 R.jar 缺失，回归按预期失败；日志为 `/tmp/jugg-styleables-sync-red.log`。
- 恢复补丁后，64 个定向用例全部通过且无跳过：`StyleableFileGeneratorTest`（8）、`SyncLocalClasspathCommandTest`（2）、`ModuleBuildPathInfoTest`（38）、`ReadProjectInfoScriptContentTest`（14）、Gradle 5/6 兼容测试（各 1）。JDK 17；日志为 `/tmp/jugg-styleables-verified.log`。
- `:idea:buildPlugin`、第三方合规检查和 `git diff --check` 通过。分发 ZIP 中的 `StyleableFileGenerator.class` 与 `ModuleBuildPathInfo.class` 均与通过测试的编译产物字节一致。
- 已生成 `idea/build/distributions/jugg-3.6.6-release.zip`，本次未替换正在运行的 Android Studio 插件。安装新包后，应通过一次完整构建后的基线同步补齐 Jojo 已有缓存缺失的 R.jar；未在 Jojo 上执行构建或设备部署。

## 文档与依据

已同步 `02_compile_resource.md` 和 `04_engineering_project.md`。Wiki 的资源机制和能力页已要求从目标 APK 的 R 声明加载，并提示通过 Gradle 构建刷新缺失基线；此次修复恢复该契约，无需改写 Wiki。

阅读依据：`AGENTS.md`；`docs/ai_knowledge/` 下的 `00_overview.md`、`99_index.md`、`98_code_map.md`、`09_plugin_runtime_debug.md`、`02_compile_manifest.md`、`02_compile_resource.md`、`03_deploy_core.md`、`04_engineering_project.md`、`06_android_test.md`、`06_testing.md`；调查指南 `local-agent-investigation-guide.md`、`local-agent-evidence-collection.md`，以及上述现场日志、同步命令和 styleable 实现。
