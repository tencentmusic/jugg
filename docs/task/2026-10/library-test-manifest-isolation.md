# library Test APK 的 Manifest 增量隔离

## 问题与目标

Jojo 在 2026-10-28 11:19 的增量编译先成功链接主 App，随后向 features Test APK 链接了主 App Manifest，报图标、主题和备份配置资源缺失。现场日志为 `compile_2026-10-28_09-44-09.0.log`，关键时间为 11:19:03.051～11:19:03.456。

保留 library main Manifest 同时分发给主 APK 和 self-targeting library Test APK 的行为。各目标分别合并自己的完整 Manifest，保留 package、application 配置和 instrumentation，`${applicationId}` 按目标展开，library 显式 placeholder 保留原值。

## 修改范围

- `AndroidManifestCompiler`：测试 APK 按对应 androidTest module 选择基线，按 APK 隔离缓存和输出；主 App 兼容既有缓存，缺少测试基线时明确失败。
- `ModuleBuildPathInfo`：识别并同步 androidTest 的 `packaged_manifests/<variant>` 基线，普通 variant 的候选顺序不变。
- `ArscCompiler`：隔离 Test APK 的资源链接目录和临时资源 APK。
- `JuggCompiler`：最终收集资源时保留 APK 隔离，部署相对路径仍为 `AndroidManifest.xml`、`resources.arsc` 和 `res/**`。
- 不修改 APK 归属规则、Manifest diff/删除语义、androidTest resource 支持范围、styleable 警告或 R.dex 生成机制。

## 验证策略

已确认的跨 APK 串用会破坏生成物和资源链接结果，通过测试价值门禁。先在已有 `AndroidManifestCompilerTest` 和 `ModuleBuildPathInfoTest` 增加失败回归；使用真实 XML、APK 和 aapt2 检查包身份、组件、placeholder、instrumentation、独立输出和连续增量，不验证私有方法或仅检查 mock 调用。

覆盖基线缺失、主 App 单 APK 路径和多 APK 主链路。编译编排变更通过 `AndroidManifestCompilerTest` 中调用完整 `JuggCompiler.compile()` 的等价 Flow 验证产物收集，并执行既有 `AndroidTestTopLevelFlowTest` 真机流程。涉及项目模型的修改另执行项目脚本内容与 Gradle 5/6 兼容测试。所有 JVM 测试带 `--tests` 过滤。

## 实施与验证结果

已完成上述四处生产代码修改，未改变 APK 归属和 Manifest patch 语义。新增 6 个行为回归：测试 Manifest 路径发现与同步、普通 variant 基线选择、双 APK 身份与连续增量、测试基线缺失、真实 aapt2 双资源表链接、完整编译后的最终产物收集。

失败证据：修改生产代码前，`ModuleBuildPathInfoTest` 的 packaged Manifest 用例失败（38 个用例中 1 个失败）；新增的 4 个 Manifest 用例均失败，复现基线串用、输出覆盖和测试基线缺失时错误复用主 App 的行为。日志保存在本机 `/tmp/jugg-manifest-red.log` 和 `/tmp/jugg-manifest-red-idea-jdk17.log`。

最终使用 JDK 17、Android SDK 和设备 `RF8M82H39HD` 执行以下定向回归，共 76 个用例，全部通过，无跳过：

- `:main:test`：`ModuleBuildPathInfoTest`（38）、`ReadProjectInfoScriptContentTest`（14）、`ReadProjectInfoGradle5CompatTest`（1）、`ReadProjectInfoGradle6CompatTest`（1）、`ModuleApkBelongsUtilsAndroidTestTest`（10）。
- `:idea:test`：`AndroidManifestCompilerTest`（10）、`AndroidTestTopLevelFlowTest`（2）。

最终日志：`/tmp/jugg-manifest-regression.log`；最终 `:idea:compileKotlin` 与 `git diff --check` 也已通过。新增多 APK 用例验证到真实 aapt2 链接和最终 staging；真机回归覆盖既有 App AndroidTest 流程，未在 Jojo 上执行 library Test APK 的安装运行。首次使用 JDK 21 构建测试 demo 时旧版 D8 抛出异常，切换 JDK 17 后构建与验证通过。

已同步 Manifest、资源链和项目模型知识库。此次恢复现有的 library Manifest 多 APK 行为，操作入口、支持范围与用户步骤不变，无需修改 Wiki。

## 阅读依据

- `AGENTS.md`；`docs/ai_knowledge/` 下的 `00_overview.md`、`99_index.md`、`98_code_map.md`、`09_plugin_runtime_debug.md`、`02_compile_manifest.md`、`02_compile_resource.md`、`02_compile_core.md`、`04_engineering_project.md`、`06_android_test.md`、`06_testing.md`。
- `.agents/skills/issue-handler/references/local-agent-investigation-guide.md`、同目录 `local-agent-evidence-collection.md`。
- Jojo 原始现场日志 `/Users/wormchen/IdeaProjects/jojo/android/build/jugg/log/compile_2026-10-28_09-44-09.0.log`，主 App 与 features Test APK 的 Manifest 路径和资源链接失败记录；对应 behavior owner 实现与定向回归。
