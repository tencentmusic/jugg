# Library Test APK source-anchored instrument 方案

> 日期：2026-05-07
> 状态：方案确认，待实现

## 1. 背景

Jugg 接下来需要支持 Library 的 Test APK。Library Test APK 和 app androidTest 的 test APK 语义不同：

- app androidTest 的 test APK 是 instrumentation 载体，测试运行在主 APK 进程内，因此只需要 INSTALL，不应参与 JVMTI、compat、code swap 或 full swap。
- Library Test APK 是独立壳 APK，有自己的进程和 runtime，不和主 APK 共享 JVMTI agent，因此需要走完整部署流程。

现有 `ApkInfo.isOtherTargetingTestApk` 已经表达了这个差异：

- `true`：app-style test APK，target package 与自身 applicationId 不同，只走 INSTALL。
- `false && isTestApk`：library-style/self-targeting test APK，按独立可运行 APK 处理。

难点不在 deploy policy 本身，而在如何快速、准确地识别一次 instrument run 应该使用哪个 Library Test APK。

## 2. 设计目标

1. 让 agent 可以低成本运行 library androidTest，不需要理解 Gradle 动态注册出来的隐性 module 信息。
2. 避免通过 package、regex、git change、已构建 APK 数量等启发式信息猜测 test APK。
3. 不为了识别目标而一次性构建所有 test APK；缺失时只懒加载当前 sourcePath 对应的 Library Test APK。
4. 保持 IDE RunConfig 和 CLI/MCP 进入同一套 target resolver。
5. 第一版只聚焦 class/method 级测试运行，完整移除 package/regex 风格入口。

## 3. 非目标

- 不对齐 `am instrument` 的完整参数能力。
- 不支持 package 级运行。
- 不支持 `tests_regex` 级运行。
- 不通过源码 package/class 名称反推 module。
- 不通过 git diff 推断唯一 test target。
- 不引入全量 AndroidTestTarget discovery/list 命令。

## 4. 核心方案

将 Jugg instrument 从“am instrument 参数包装器”调整为“source file anchored test run”。

外部接口以 source file 为目标锚点：

```bash
jugg instrument --source-path library1/src/androidTest/kotlin/com/example/FooTest.kt
jugg instrument --source-path library1/src/androidTest/kotlin/com/example/FooTest.kt --method testSomething
```

语义拆分：

| 参数 | 职责 |
|------|------|
| `sourcePath` | 定位 androidTest source set、ModuleInfo 和 test APK |
| `class` | 可选；指定文件内要运行的测试类 |
| `method` | 可选；指定测试方法 |
| `runner` / `extras` | 保留；透传给 `am instrument` |

`sourcePath` 只接受文件路径，不接受普通 package/regex 作为 target anchor。路径可以是相对路径，MCP 端按 `projectDir` 归一化为绝对路径。

## 5. 为什么移除 Package/Regex

package 和 regex 表达的是测试过滤范围，不表达 test APK 身份。在多 library test APK 场景下：

- 同一个 package 可能横跨多个 androidTest source set。
- regex 没有 module 结构信息。
- Kotlin 文件路径和 package/class 也不一定匹配。
- 用这些参数反推目标会重新引入不可靠启发式。

因此第一版直接移除 package/regex 入口。Jugg instrument 的产品定位不是完整替代 Android Studio/`am instrument`，而是为增量开发提供确定、可解释的 class/method 级验证工具。

## 6. CLI/MCP 参数调整

`instrument` MCP tool 新增：

| 参数 | 必填 | 说明 |
|------|------|------|
| `sourcePath` | library test 场景必填 | 测试文件路径，用于定位 androidTest module 和 test APK |
| `class` | 可选 | 文件内测试类；文件只有一个测试类时可省略 |
| `method` | 可选 | 测试方法；需要已唯一确定 class |

移除：

| 参数 | 处理 |
|------|------|
| `package` | 从 MCP schema 和 CLI 文档中移除 |
| `testsRegex` | 从 MCP schema 和 CLI 文档中移除 |

保留：

| 参数 | 说明 |
|------|------|
| `runner` | instrumentation runner override |
| `extras` | 额外 `-e key value` 参数 |

CLI 继续遵循 1:1 透传原则，不在 CLI 层创造额外推断语义。

## 7. IDE RunConfig 调整

`JuggAndroidTestRunConfiguration` 当前只保存测试过滤参数和关联 app run config。后续需要保存 source anchor：

- `sourcePath`：触发 gutter 的测试文件路径。
- `sourceRootPath`：可选；对应 androidTest source root，用于更快匹配和错误提示。

IDE gutter 创建临时 RunConfig 时直接写入 `sourcePath`。手动编辑场景中，Module 行可以继续展示为只读信息，但内部 target 仍以 source path 解析结果为准。

`AndroidTestRunSpec` 同步增加 `sourcePath`，让部署和 TestLauncher 阶段无需反查 RunConfig。

## 8. Target Resolver

新增或收敛一个 resolver，输入为 `sourcePath` 和当前 `projectInfo.modules`。

解析流程：

1. 将 `sourcePath` 转成绝对规范路径。
2. 校验路径存在且是文件。
3. 在 `ModuleInfo` 中筛选 `isAndroidTestModule == true`。
4. 找到 `sourcePath` 位于其 `sourceDirs` 下的 module。
5. 要求唯一匹配：
   - 0 个：报错，说明文件不在 Jugg 监控的 androidTest source set 内。
   - 多个：报错，打印候选 module/source root。
6. 用匹配到的 androidTest module 定位 test APK：
   - 优先 `apk.applicationId == module.applicationId`。
   - 其次 `apk.instrumentationTargetPackage == module.instrumentationTargetPackage && !apk.isOtherTargetingTestApk`。
   - 仍不唯一则报错，打印候选 APK 信息。
7. 如果 APK 列表中没有对应 Library Test APK，进入懒加载补齐流程。

这个 resolver 是确定性的，不使用 git、package、regex、APK 数量等启发式。

## 9. 测试类与方法解析

`sourcePath` 定位文件后，工具需要从源码解析测试 class/method：

- Java/Kotlin 都支持。
- 只识别带 `org.junit.Test` 或 `org.junit.jupiter.api.Test` 的测试方法。
- 如果文件内只有一个测试类，`class` 可省略。
- 如果文件内有多个测试类且未传 `class`，报错列出候选。
- 如果传了 `method`，必须能在最终 class 内找到对应测试方法。

第一版可以复用或抽取现有 gutter 测试识别能力，避免在 CLI/MCP 路径重复维护 PSI/源码解析逻辑。若 MCP 端不能直接使用 PSI，则可先实现轻量源码扫描，但错误信息必须明确“无法解析测试类/方法”。

## 10. 部署与运行策略

### 10.1 Deploy policy

部署阶段继续使用 `ApkInfo.isOtherTargetingTestApk` 分流：

- `isOtherTargetingTestApk == true`：app androidTest test APK，只 INSTALL。
- `isOtherTargetingTestApk == false` 的 test APK：library test APK，走完整部署流程。
- 普通 APK：现有行为不变。

### 10.2 Library 改动同步部署主 APK

第一版不区分 `library/src/main` 和 `library/src/androidTest` 的触发差异：

- 任意 library 相关改动进入部署时，同步主 APK。
- 如果存在对应 library test APK，也同步该 library test APK。

但编译产物归属仍需要保持语义干净：

- `library/src/main` 输出可进入主 APK 和 library test APK 的 target list。
- `library/src/androidTest` 输出只属于 library test APK。
- “同步主 APK”表示部署动作同步，不表示 test-only class 也写入主 APK。

### 10.3 Multi-ownership 数据流

继续沿用已确认的双视图模型：

- `getBelongsApk()` 保留单 APK 语义，供 desugar/minify/styleable 等旧逻辑使用。
- `getAllBelongsApk()` 表达所有部署目标。
- 后续实现中 `CompileOutput -> DeployItem -> JuggDeployData` 需要携带 `targetApkPaths`，否则多目标信息会在部署前丢失。

## 11. Library Test APK 懒加载补齐

Library Test APK 不能假设已经被一次性全部编译出来。Jugg 只在用户首次运行某个 library 的 test file 时补齐该 library 对应的 Test APK。

触发条件：

1. `sourcePath` 已经唯一定位到某个 androidTest `ModuleInfo`。
2. 当前持久化 APK 列表中找不到该 module 对应的 Library Test APK。
3. 该 module 属于 self-targeting / library-style test APK，不是 `isOtherTargetingTestApk` 场景。

处理原则：

- 只构建当前 androidTest module 对应的一个 Test APK。
- 不构建其他 library 的 Test APK。
- 不修改普通 Gradle 编译配置。
- 不触发 Jugg full Gradle compile 的状态重置逻辑。
- 不清空或重建现有主 APK、library path、deploy database 等已有产物。
- 只把新生成的 Test APK 拉到 Jugg 期望的持久化路径，并追加到当前持久化 APK 信息中。

推荐流程：

1. 由 `sourcePath` 定位 androidTest module。
2. 从 module 信息派生 Gradle task，例如 `:library1:assembleDebugAndroidTest`。
3. 单独执行该 task。
4. 从 Gradle 输出或约定目录定位生成的 Test APK。
5. 用 `ApkInfoReader` 读取新 APK 的 `applicationId`、`instrumentationTargetPackage`、runner 等信息。
6. 将 APK 文件复制到 Jugg 管理的 APK 持久化目录。
7. 把新的 `ApkInfo` 合并到当前 APK 列表，并持久化到 deploy target / compile context 依赖的存储中。
8. 重新执行 `sourcePath -> module -> test APK` 解析，确认新 APK 已可命中。
9. 继续后续部署与 `am instrument`。

这个流程和普通 Gradle compile 的职责不同。普通 Gradle compile 可能清理并重建一批产物；懒加载补齐只补一个缺失 Test APK，不应该影响其他已存在的 APK 和增量状态。

## 12. 错误信息

路径不在监控范围：

```text
instrument failed. Reason: sourcePath is not under any known androidTest source root.
sourcePath: library1/src/test/FooTest.kt
```

未传 sourcePath：

```text
instrument failed. Reason: sourcePath is required for Jugg instrument.
Pass a test file under src/androidTest, for example:
jugg instrument --source-path library1/src/androidTest/kotlin/com/example/FooTest.kt
```

多测试类：

```text
instrument failed. Reason: multiple test classes found in sourcePath.
Candidates:
- com.example.FooTest
- com.example.BarTest
Please rerun with --class <className>.
```

无法定位 APK：

```text
instrument failed. Reason: unable to resolve test APK for androidTest module.
module: library1.androidTest
applicationId: com.example.library1.test
```

懒加载补齐失败：

```text
instrument failed. Reason: unable to build missing Library Test APK.
module: library1.androidTest
task: :library1:assembleDebugAndroidTest
```

懒加载后仍无法识别 APK：

```text
instrument failed. Reason: Library Test APK was built but cannot be added to Jugg APK list.
module: library1.androidTest
```

## 13. 测试计划

优先 main 模块单元测试，只有依赖 IDE PSI 或 RunConfig 时才放 idea 模块。

建议覆盖：

1. `sourcePath` 命中唯一 androidTest module。
2. `sourcePath` 不在任何 androidTest source root 下时报错。
3. 多个 source root 重叠时要求唯一匹配。
4. androidTest module 通过 `applicationId` 精确匹配 test APK。
5. self-targeting library test APK 不被当成 install-only APK。
6. app-style other-targeting test APK 仍 install-only。
7. `package` / `testsRegex` 参数从 schema/CLI 文档中移除。
8. 单 class 文件可省略 `class`。
9. 多 class 文件要求显式传 `class`。
10. `method` 必须属于最终解析出的测试 class。
11. 缺失 Library Test APK 时只派生并执行当前 module 的 `assembleDebugAndroidTest`。
12. 懒加载补齐只追加一个 Test APK，不清理或重写已有 APK 列表。
13. 懒加载补齐后重新解析能命中新加入的 Test APK。

验证命令使用定向测试，禁止运行全量测试套件。

## 14. 分阶段落地

### Phase 1：API 收口

- MCP/CLI `instrument` 增加 `sourcePath`。
- 移除 `package` / `testsRegex`。
- `AndroidTestRunSpec` 增加 `sourcePath`。
- IDE gutter 写入 `sourcePath`。

### Phase 2：resolver 与 test APK 精确定位

- 实现 `sourcePath -> androidTest ModuleInfo` resolver。
- 实现 `androidTest ModuleInfo -> ApkInfo` resolver。
- 替换当前 `firstOrNull { it.isTestApk }` 的 test APK 选择逻辑。

### Phase 3：Library Test APK 懒加载补齐

- 当 resolver 找不到对应 Library Test APK 时，只执行当前 module 的 `assembleDebugAndroidTest`。
- 将新 Test APK 拉到 Jugg 持久化 APK 目录。
- 将新 `ApkInfo` 合并进当前持久化 APK 列表。
- 补齐后重新解析并继续 instrument。

### Phase 4：library test APK 完整部署

- 使用 `isOtherTargetingTestApk` 区分 install-only 和完整部署。
- 延续 multi-ownership 方案，把 `targetApkPaths` 从 `CompileOutput` 传到 `DeployItem/JuggDeployData`。
- 对 package-scoped deploy data 做过滤，避免同一份 deploy data 无差别传给所有 APK。

## 15. 结论

第一版不再追求 `am instrument` 参数完整性，而是专注于 agent 最常用、最可靠的 source file anchored class/method test run。

这个方案牺牲 package/regex 的泛化能力，换取：

- target 识别确定；
- agent 使用简单；
- 不依赖隐式 module 名；
- 不引入高风险启发式；
- 不需要一次性构建所有 test APK。
- 缺失 Library Test APK 时可以按 sourcePath 懒加载补齐单个 APK。
