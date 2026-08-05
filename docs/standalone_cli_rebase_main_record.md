# standalone CLI 分支 rebase main 记录

## 1. 基本信息

- 执行日期：2026-08-01（Asia/Shanghai）
- 工作分支：`feature/standlone_cli`
- rebase 前 HEAD：`f09d8c8cb150b36d4e6617888e84b4ffdc70ff63`
- rebase 前与 main 的共同祖先：`6f3999ed10f490e572d232e6cb6f6f060075cfe1`
- rebase 前待重放提交数：11
- 备份分支：`feature/standlone_cli_old`
- 备份分支创建时间：2026-08-01 09:42:31 +08:00
- 备份分支指向：`f09d8c8cb150b36d4e6617888e84b4ffdc70ff63`
- 第一次 rebase 开始时的本地 main：`fe61035d05b2b2b858de1f02e996d93e7c4702a0`
- 执行期间 main 又新增 3 个提交，最终 rebase 基线：`f07fad55d64fa09242f998b3bcbbcbca32ba0a75`
- 当时的 `origin/main`：`f527ed6e9439867615774b2fc7c19ba0f098dae3`

开始 rebase 前先执行了等价于以下操作的备份：

```shell
git branch feature/standlone_cli_old feature/standlone_cli
```

第一次 rebase 完成后，发现本地 main 在操作期间从 `fe61035d0` 前进到 `f07fad55d`。为确保最终结果基于当前本地 main，暂存 rebase 后的兼容修正，再次执行增量 rebase。第二次只重放相同的 11 个提交，没有产生新冲突。

## 2. 提交重放总表

| 序号 | 原提交 | 最终提交 | 提交说明 | 第一次 rebase 结果 |
|---|---|---|---|---|
| 1 | `55cf8fbd5e2c7ada3d43403791d3d179b508ad88` | `ba195a552400fc8333fcb655c67031c0fcf4b2cb` | `[docs] add standalone jugg cli design` | 无冲突 |
| 2 | `2a228796078e6e399d5fa82af24520719c788c1b` | `8124eb4ed13e94187342a26eaf82fc48d6ae7dfe` | `[docs] expand standalone runtime design` | 无冲突 |
| 3 | `143fe8b48415b44d2a3befcfe0e70307e00b4892` | `e6d800c706c9b3dbd6761a12381a0cf54da39a01` | `[docs] align standalone CLI design decisions` | 无冲突 |
| 4 | `acecfd6c13cb900183686d14c9db11050c9f8651` | `e9cedb43c97c3d90b14c3768004ec22dfd0a35ba` | step1 establish project runtime | 有冲突 |
| 5 | `a01f421dbcd3d768bf1a79d9f721e97b3eb69191` | `0227c4e014a3c14b33dbee9b9be35870034d461e` | step2 establish task execution domain | 有冲突 |
| 6 | `47fb2ba447f79f70fc9a1f41aba0274a82204aee` | `4ba61c2362b790d7d5a2833646d8498e3e0a8b18` | reorganize project packages by responsibility | 有冲突 |
| 7 | `c9b5311cc8abdd11f59cd492de11d03a9dea7dfe` | `44f6505fad26d8d53340d5d5eed5738093f0e25a` | step3 establish project model domain | 有冲突 |
| 8 | `1e6469a53471f5496795be185d93a3d150d3227f` | `6946bccb2e20d6d19dd2d0d3fadbf8fc24e307fe` | step4 establish file change domain | 有冲突 |
| 9 | `918ffefd23687f5b6f687bf9a8d4d7a081132e2f` | `339abaae5e43a823f05bb0ed82c1f6525410fea6` | step5 establish runtime configuration | 有冲突，仅文档 |
| 10 | `a51822a8a966e7b8eddd7fae3ea080562bc15839` | `dd8ca4d37f011a3d9916784514fe75b05b55fb4d` | step6 establish server and hot update boundaries | 有冲突，仅文档 |
| 11 | `f09d8c8cb150b36d4e6617888e84b4ffdc70ff63` | `fcc43d956126402c8704f26bb23e81270e63a073` | supports standalone cli | 有冲突 |

第一次 rebase 到 `fe61035d0` 时对应的中间提交依次为：`4e19944f2`、`b53495f4d`、`3950e29f6`、`27348b695`、`8139b0c43`、`d5ae63527`、`02e27fd18`、`2d5fea68d`、`85a47af65`、`ed1949c5d`、`064ce78de`。main 前进后执行的第二次 rebase 没有冲突，最终提交以表格中的 hash 为准。

## 3. 逐提交冲突与解法

### 3.1 提交 1：add standalone jugg cli design

- 结果：自动应用，无冲突。
- 处理：保留原提交内容，不做人工调整。

### 3.2 提交 2：expand standalone runtime design

- 结果：自动应用，无冲突。
- 处理：保留原提交内容，不做人工调整。

### 3.3 提交 3：align standalone CLI design decisions

- 结果：自动应用，无冲突。
- 处理：保留原提交内容，不做人工调整。

### 3.4 提交 4：step1 establish project runtime

冲突文件：

- `idea/src/main/java/com/sickworm/intellij/jugg/JuggManager.kt`
- `docs/ai_knowledge/98_code_map.md`

冲突内容与处理：

1. `JuggManager.kt`
   - main 一侧已经加入较新的运行配置创建流程、文件变化处理和远端初始化等待逻辑。
   - standalone CLI 提交将部署状态抽到 main 模块，引入 `IDeployStateManager` 和 `IdeaHostDeployStateResolver`。
   - 解法：保留 main 的运行配置行为、独立锁和 `waitForRemoteInitUpdate()`；同时采用共享部署状态接口与 IDEA 宿主解析器。该阶段保留 main 当时的文件变化锁，后续由 step4 的共享 `FileChangeManager` 统一替换。
2. `98_code_map.md`
   - main 一侧包含较新的 Project/Gradle/Compose 索引内容，提交一侧增加部署状态域说明。
   - 解法：保留 main 的新索引，补入部署状态域、接口和 IDEA 宿主实现位置，不回退已有知识。

### 3.5 提交 5：step2 establish task execution domain

冲突文件：

- `idea/src/main/java/com/sickworm/intellij/jugg/project/TaskRunnerManager.kt`（modify/delete）
- `idea/src/test/java/com/sickworm/intellij/jugg/deploy/run/applychanges/JuggDeployerInstallTest.kt`
- `docs/ai_knowledge/03_deploy_core.md`
- `docs/ai_knowledge/04_engineering_compat.md`
- `docs/ai_knowledge/05_utilities.md`
- `docs/ai_knowledge/09_plugin_runtime_debug.md`
- `docs/ai_knowledge/98_code_map.md`

冲突内容与处理：

1. 旧 IDEA `TaskRunnerManager`
   - standalone CLI 提交删除 IDEA 私有实现，将任务执行、锁和后台任务能力下沉到 main，并通过 `HostTaskExecutor` 隔离宿主。
   - main 一侧旧实现还有较新的“只上报失败任务”修正。
   - 解法：删除旧 IDEA 实现，使用 main 中共享 `TaskRunnerManager`；把“只上报失败任务”的新行为移植到共享实现及对应测试，避免功能回退。
2. `JuggDeployerInstallTest.kt`
   - main 一侧已有更新后的安装流程用例，提交一侧新增共享任务架构和缓存场景。
   - 解法：保留 main 的现有用例，加入新架构下的安装与缓存断言。
3. 知识库文档
   - 两侧同时更新修改日期、部署锁、缓存、热更新和排查说明。
   - 解法：日期采用较新值；正文同时保留 main 的最新排查细节和提交引入的项目级任务锁、共享缓存、热更新职责边界。

### 3.6 提交 6：reorganize project packages by responsibility

主要冲突文件：

- `idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/JuggConfigurationRunner.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/compiler/databinding/DataBindingClasspathHelper.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/project/info/JuggProjectInfoMerger.kt`
- `main/src/test/java/com/sickworm/intellij/jugg/project/info/JuggProjectInfoMergerAndroidTestTest.kt`
- `main/src/test/java/com/sickworm/intellij/jugg/gradle/script/ProjectInfoSerializerInGradleAndroidTestTest.kt`
- `main/src/test/java/com/sickworm/intellij/jugg/gradle/script/GradleProjectInfoReaderAndroidTestTest.kt`
- `main/src/test/java/com/sickworm/intellij/jugg/compiler/SourceCompileDataBindingTest.kt`
- `idea/src/test/java/com/sickworm/intellij/jugg/project/change/FileChangesHandlerTest.kt`
- `idea/src/test/java/com/sickworm/intellij/jugg/manager/JuggCompilerTest.kt`
- `docs/ai_knowledge/04_engineering_ide.md`
- `docs/ai_knowledge/04_engineering_project.md`
- `docs/ai_knowledge/05_utilities.md`
- `docs/ai_knowledge/06_android_test.md`
- `docs/ai_knowledge/98_code_map.md`

冲突内容与处理：

1. 生产代码包迁移
   - standalone CLI 提交将工程能力按 `project.change`、`project.info`、`project.runtime` 等职责重组。
   - main 一侧在旧包路径上增加了实现细节。
   - 解法：采用新包路径，同时保留 main 新增行为：`JuggConfigurationRunner` 保留 `FutureTask`；`DataBindingClasspathHelper` 保留递归列表辅助逻辑；`JuggProjectInfoMerger` 保留 `java.io.File` 相关处理。
2. 测试包迁移
   - 六组测试同时发生 import/package 路径变化和 main 新断言冲突。
   - 解法：全部切换到新包路径；保留 main 的 Compose、自定义 build directory 和清理逻辑相关断言。
3. 知识库文档
   - 解法：路径更新为新职责包，同时保留 main 最近新增的 Compose、Gradle 与测试说明，修改日期采用较新值。

### 3.7 提交 7：step3 establish project model domain

冲突文件：

- `idea/src/main/java/com/sickworm/intellij/jugg/compiler/context/CompileContextManager.kt`（modify/delete）
- `idea/src/test/java/com/sickworm/intellij/jugg/compiler/context/CompileContextManagerBuildPathInfoTest.kt`
- `AGENTS.md`
- `docs/ai_knowledge/04_engineering_ide.md`
- `docs/ai_knowledge/04_engineering_project.md`
- `docs/ai_knowledge/98_code_map.md`

冲突内容与处理：

1. 旧 IDEA `CompileContextManager`
   - standalone CLI 提交删除 IDEA 私有实现，改用 main 的共享 `CompileContextManager`，并通过 `ProjectModelSource`、`CompileEnvironmentSource` 隔离宿主数据。
   - main 一侧旧实现已支持 `agpR8Classpath` 和真实 `buildDirRelativePath`。
   - 解法：删除旧 IDEA 实现；将 `agpR8Classpath` 传播和 build directory 保留逻辑移植到共享管理器与 `ProjectModelSource`；IDE 侧通过 `IdeaProjectModelSource` 提供真实构建目录。
2. `CompileContextManagerBuildPathInfoTest.kt`
   - 构造方式变为共享 model source，同时 main 增加了完整构建路径必须权威保留的测试。
   - 解法：改用 `GradleProjectModelSource` 和新构造参数，保留 main 的权威路径断言，并补齐 `agpR8Classpath`、`buildDirRelativePath`。
3. `AGENTS.md`
   - main 新增日志格式规范，提交新增“已有单行代码不要仅因参数变化而重排”的规范。
   - 解法：两条规则均保留。
4. 知识库文档
   - 解法：合并新的模型源/环境源边界与 main 的 Compose、R8、自定义 build directory 细节。

### 3.8 提交 8：step4 establish file change domain

冲突文件：

- `idea/src/main/java/com/sickworm/intellij/jugg/JuggManager.kt`
- `idea/src/test/java/com/sickworm/intellij/jugg/compiler/JuggCompileUiHandlerTest.kt`
- `docs/ai_knowledge/04_engineering_ide.md`
- `docs/ai_knowledge/04_engineering_project.md`
- `docs/ai_knowledge/98_code_map.md`

冲突内容与处理：

1. `JuggManager.kt`
   - 提交引入共享 `FileChangeManager` 并删除旧本地处理和 `fileChangeLock`。
   - main 一侧同时更新了运行配置锁与文件变化流程。
   - 解法：采用共享 `FileChangeManager`，删除旧文件变化锁；运行配置仍保留独立 `runConfigurationLock`，避免误用整个 manager 实例作为锁。
2. `JuggCompileUiHandlerTest.kt`
   - main 有缓存刷新测试，提交有 RPC 依赖确认测试。
   - 解法：两个测试场景都保留。
3. 知识库文档
   - 解法：同时记录 main 的运行配置行为和新的文件变化域、排查路径与代码地图。

### 3.9 提交 9：step5 establish runtime configuration

冲突文件：

- `docs/ai_knowledge/04_engineering_ide.md`
- `docs/ai_knowledge/05_utilities.md`
- `docs/ai_knowledge/98_code_map.md`

冲突内容与处理：

- 冲突均为文档日期、章节落点和新增运行时配置说明的交叠。
- 解法：采用较新的文档日期，保留 main 已有内容，并补入 JSON runtime settings、IDE 迁移和配置仓库职责。

### 3.10 提交 10：step6 establish server and hot update boundaries

冲突文件：

- `docs/ai_knowledge/04_engineering_ide.md`

冲突内容与处理：

- main 一侧包含较新的 skill 安装和 CLI 解析说明，提交一侧增加共享热更新、server chooser 和自定义 server 边界。
- 解法：合并两部分内容，不覆盖 main 的新安装流程，并明确共享层与 IDEA 宿主层的热更新职责。

### 3.11 提交 11：supports standalone cli

冲突文件：

- `idea/src/main/java/com/sickworm/intellij/jugg/JuggManager.kt`
- `idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/JuggConfigurationRunner.kt`
- `idea/src/test/java/com/sickworm/intellij/jugg/ide/logic/JuggConfigurationRunnerTest.kt`
- `docs/ai_knowledge/04_engineering_ide.md`
- `docs/ai_knowledge/04_engineering_project.md`

冲突内容与处理：

1. `JuggManager.kt`
   - standalone CLI 提交引入 `CliRunConfigurationStore` 和 `IdeaCliRunConfigurationManager`。
   - main 一侧已有运行配置指数退避重试和独立流程同步。
   - 解法：采用新的 CLI 配置存储和 IDEA 适配器；保留独立 `runConfigurationLock`、main 的最大重试次数和指数退避常量；删除已被新架构替代的 `SuggestRunConfiguration` 辅助方法。
2. `JuggConfigurationRunner.kt`
   - main 已实现 EDT 安全的配置解析顺序：selected、完整 build command + target、command、first fallback。
   - standalone CLI 提交中的候选实现只按 selected/first 查找，行为弱于 main。
   - 解法：完整保留 main 的强解析顺序。CLI store 只负责持久化和当前项指针，不要求降低 IDEA 运行时解析能力。
3. `JuggConfigurationRunnerTest.kt`
   - main 有四条 fallback 行为测试，提交有一条较简单的 selected/first 测试。
   - 解法：保留 main 四条测试，删除被其覆盖的重复简单测试。
4. CLI 生成路径
   - 新 CLI 运行配置最初默认使用固定 `build/outputs/apk`，与 main 已支持的自定义 build directory 不一致。
   - 解法：让生成器使用模型中的 `buildDirRelativePath`，并更新 `CliRunConfigurationTest`，保留自定义 build directory 行为。
5. 知识库文档
   - 解法：采用较新日期，同时记录 CLI store/current pointer、新生成器和 IDEA 强 fallback 行为。

## 4. 第二次增量 rebase

第一次 rebase 完成于 10:03:16，基线为开始操作时的 `fe61035d0`。期间本地 main 新增以下 3 个提交：

- `8c6730e7b` `[docs] verify open source inventory entries`
- `a3a188853` `[docs] keep agent commits focused on user problems`
- `f07fad55d` `[docs] expand embedded open source dependencies`

10:15:44 再次将相同的 11 个提交 rebase 到 `f07fad55d`。11 个提交全部自动应用，没有产生冲突；之前的冲突解法保持不变，提交 hash 更新为第 2 节表格中的最终值。

## 5. rebase 后编译兼容修正

rebase 冲突全部解决后进行了编译和定向测试。编译阶段发现若干不会以文本冲突形式出现的语义问题：

1. 部分生产代码和测试仍引用旧的 `com.sickworm.intellij.jugg.project.data` 包。
   - 解法：统一切换到 `com.sickworm.intellij.jugg.project.info`。
2. `ProjectModelSource` 新旧两侧组合后未完整传递 `agpR8Classpath`，部分构造点缺少 `buildDirRelativePath`。
   - 解法：在模型源合并时保留 `agpR8Classpath`，为生产和测试构造点补齐真实构建目录参数。
3. IDEA 测试目录仍保留三个已迁移架构的旧单文件测试：
   - `idea/src/test/java/com/sickworm/intellij/jugg/manager/JuggManagerRunConfigurationSyncTest.kt`
   - `idea/src/test/java/com/sickworm/intellij/jugg/project/TaskRunnerManagerTest.kt`
   - `idea/src/test/java/com/sickworm/intellij/jugg/deploy/run/JuggDeploymentCacheStoreTest.kt`
   - 解法：删除旧副本。对应行为已经分别由 `IdeaCliRunConfigurationFlowTest`/`CliRunConfigurationTest`/`JuggConfigurationRunnerTest`、main 的 `TaskRunnerManagerTest`、main 的 `JuggDeploymentCacheStoreTest` 覆盖。
4. `JuggManagerFullBuildFlowTest` 构造 `JuggManager` 时，mock Project 无法提供 `RunManager` service，触发 NPE。
   - 解法：将有明确宿主语义的 `RunManager` 作为 `JuggManager` 构造依赖，默认仍使用 `RunManager.getInstance(project)`；Flow 测试注入 mock。没有增加测试专用 provider、lambda 或 factory。

## 6. 测试层级与验证

本次是 rebase/refactor 兼容任务，按 `docs/ai_knowledge/06_testing.md` 选择已有测试进行定向回归，没有执行无过滤的全量 `:main:test` 或 `:idea:test`。

- L1：
  - `cmd_line/src/test/java/com/sickworm/intellij/jugg/cmdline/base/LibrariesBackupHelperTest.kt`
  - `main/src/test/java/com/sickworm/intellij/jugg/project/runtime/CliRunConfigurationTest.kt`
  - `main/src/test/java/com/sickworm/intellij/jugg/project/runtime/TaskRunnerManagerTest.kt`
  - `main/src/test/java/com/sickworm/intellij/jugg/project/info/ProjectModelSourceTest.kt`
- L2：
  - `idea/src/test/java/com/sickworm/intellij/jugg/ide/logic/JuggConfigurationRunnerTest.kt`
  - `idea/src/test/java/com/sickworm/intellij/jugg/compiler/context/CompileContextManagerBuildPathInfoTest.kt`
  - `idea/src/test/java/com/sickworm/intellij/jugg/compiler/JuggCompileUiHandlerTest.kt`
  - `idea/src/test/java/com/sickworm/intellij/jugg/project/dependency/GradleProjectInfoLocalFetchManagerTest.kt`
- L3/等价 Flow：
  - `idea/src/test/java/com/sickworm/intellij/jugg/project/runtime/IdeaCliRunConfigurationFlowTest.kt`
  - `idea/src/test/java/com/sickworm/intellij/jugg/manager/JuggManagerFullBuildFlowTest.kt`

执行命令：

```shell
./gradlew :idea:compileKotlin
./gradlew :cmd_line:test --tests com.sickworm.intellij.jugg.cmdline.base.LibrariesBackupHelperTest
./gradlew :main:test --tests com.sickworm.intellij.jugg.project.runtime.CliRunConfigurationTest --tests com.sickworm.intellij.jugg.project.runtime.TaskRunnerManagerTest --tests com.sickworm.intellij.jugg.project.info.ProjectModelSourceTest
./gradlew :idea:test --tests com.sickworm.intellij.jugg.ide.logic.JuggConfigurationRunnerTest --tests com.sickworm.intellij.jugg.compiler.context.CompileContextManagerBuildPathInfoTest --tests com.sickworm.intellij.jugg.compiler.JuggCompileUiHandlerTest
./gradlew :idea:test --tests com.sickworm.intellij.jugg.project.runtime.IdeaCliRunConfigurationFlowTest --tests com.sickworm.intellij.jugg.manager.JuggManagerFullBuildFlowTest --tests com.sickworm.intellij.jugg.project.dependency.GradleProjectInfoLocalFetchManagerTest
```

以上命令最终均通过。构建日志中仍有 NDK `riscv64` ABI 元数据和旧 IntelliJ Platform `sourceCompatibility` 提示，均为既有非阻断 warning，不是本次 rebase 引入的失败。

## 7. 最终状态核对项

- `feature/standlone_cli_old` 仍指向 rebase 前 HEAD `f09d8c8cb`。
- `feature/standlone_cli` 的共同祖先为最终本地 main `f07fad55d`。
- 11 个原提交均按原顺序重放，没有丢失或压缩。
- 冲突解决后的额外兼容修正和本记录将作为单独提交保存，不修改 11 个原提交的 message。

## 8. Rebase review 后续结论

### 8.1 Review 范围与结论

本次 rebase 完成后，使用独立 review 分别检查：

1. `main` 的变更是否完整保留。
2. `feature/standlone_cli_old` 的变更是否完整保留。
3. 当前实现是否偏离 standalone CLI 原设计方向。

已确认结论：

- old feature 的 11 个提交顺序、原始设计文档、增加/删除的主要实现均已保留，没有发现 standalone CLI 原方向被冲突解决覆盖。
- `SuggestRunConfiguration` 退出核心配置生成链路符合 standalone CLI 设计，但新 `CliRunConfiguration` 实现只替代了旧能力的一部分，Active Build Variant 自动同步能力实际被丢弃。
- 文件变化域迁移后，`runPendingFileProcessing()` 与 Run Configuration 初始化重新竞争同一个 project lock，可能使 main 已修复的 IDE 卡顿问题回归。
- review 期间 `main` 再次前进不纳入本轮剩余 TODO，按当前决策忽略。

### 8.2 `SuggestRunConfiguration` 能力缺口

旧链路通过 Android Studio deploy compat 获取 suggestion，并在 Sync 后执行以下行为：

- 根据当前 Active Build Variant 推导 module、variant、Gradle task 和 APK output。
- 为新的 module/variant 创建或复用 Jugg Run Configuration。
- 当前选择是同模块 Jugg Configuration 时，切换到新 variant 对应配置。
- 按唯一 Gradle task 去重，同时保留已有配置中的 `--offline`、`-Pxxx` 等附加参数。
- 处理 composite build 模块身份。

当前 `IdeaCliRunConfigurationManager.ensureConfiguration()` 在已有任意 Jugg Configuration 时只导入现有配置，不会再根据 Active Build Variant 创建、复用或切换配置。`CliRunConfigurationGenerator` 读取 `ModuleInfo.buildVariant`，但只在完全没有 Jugg Configuration 时用于生成一个默认配置。因此，这不是等价实现替换，而是旧 Active Build Variant 同步逻辑被直接丢弃。

本轮确认：

- 不恢复 `SuggestRunConfiguration`，也不重新依赖 deploy compat suggestion。
- 需要基于 `CliRunConfiguration`、`JuggProjectInfo` 和配置集合重新设计 Active Build Variant 同步。
- 普通 Android Run Configuration 继续不导入；standalone 与 IDEA 仍共享同一配置 schema 和当前指针。

### 8.3 文件处理锁回归

当前调用链：

```text
FileChangeManager.runPendingFileProcessing()
  -> TaskRunnerManager.runBackgroundSafe(isProjectWrite = true)
  -> 持有 <projectDir>/build/jugg/runtime.lock 执行目录扫描和过滤

JuggManager.tryCreateRunConfigurations()
  -> TaskRunnerManager.runProjectWriteLocked()
  -> 等待同一个 runtime.lock
```

这会使耗时目录扫描阻塞 Gradle Sync 回调中的 Run Configuration 初始化。文件变化处理已经通过 `DeployStateManager.beginFileProcessing/endFileProcessing` 阻止增量编译抢在事件落库前开始，且 `FileChangesHandler`、`DeployFileManager`、`DependencyChangeManager` 和 `GitFileChangesDetector` 均为当前 Runtime 实例内对象，因此该流程不需要跨 Runtime project lock。

确认的最小修正方向：

- `runPendingFileProcessing()` 不再设置 `isProjectWrite = true`。
- `FileChangeManager` 增加 Runtime 实例内的 file-processing lock，串行 monitor 事件和 overflow Git reconcile。
- 保留 pending file-processing barrier 及其取消/异常释放语义。
- `runConfigurationLock` 继续独立串行 Run Configuration 初始化。
- 完整 compile、Gradle build、deploy 等项目写事务继续使用 project lock。

## 9. Rebase review TODO 与处理结果

### P0：修复文件处理与 Run Configuration 的锁竞争

- [x] 在 `FileChangeManagerTest` 恢复失败证据：阻塞 `FileChangesHandler.filter()` 时，project write 无法完成。
- [x] 将 `runPendingFileProcessing()` 从 project lock 改为 `FileChangeManager` Runtime 实例内锁。
- [x] 验证 monitor 事件和取消路径只调用一次 `endFileProcessing()`；异常释放沿用同一 `AtomicBoolean` 收口。
- [x] 定向运行 `FileChangeManagerTest`、`CliRunConfigurationTest`、`IdeaCliRunConfigurationFlowTest`、`JuggConfigurationRunnerTest` 和 `:idea:compileKotlin`。

### P1：基于 `CliRunConfiguration` 恢复 Active Build Variant 同步

- [x] 定义 Active Build Variant 同步输入，只使用 `JuggProjectInfo` 中 application module 的 effective `buildVariant`，不恢复 `SuggestRunConfiguration`。
- [x] 在 `CliRunConfigurationGenerator` 中增加按 module 生成当前 variant 候选配置的确定性入口，复用现有 task、APK output、自定义 build directory和稳定 id 规则。
- [x] 在 `IdeaCliRunConfigurationManager` 中实现 reconcile：匹配已有 IDEA/CLI 配置、必要时创建缺失配置、更新 store，并按明确规则决定是否切换当前配置。
- [x] 保留同 module + variant 已有配置的用户字段和附加 Gradle 参数，禁止 Sync 覆盖远端配置、环境变量或用户自定义 command。
- [x] 多 application module 逐 module reconcile；module/variant 无法匹配时不切换选择，自动生成继续以 `moduleStdPath` 支持 composite build。
- [x] 增加 L1 生成规则和 L2 IDEA reconcile Flow 的失败测试，再实现生产逻辑。

### P2：清理和同步文档

- [x] 将 `docs/ai_knowledge/04_engineering_ide.md` 中旧 suggestion 流程改写为新的 `CliRunConfiguration` reconcile 流程。
- [x] 在 `docs/task/standalone_jugg_cli_design.md §7/Step 7` 补充 Active Build Variant 同步约束和兼容策略。
- [ ] Active Build Variant 新链路稳定后，再独立评估 `SuggestRunConfiguration` compat API、数据类和默认配置 UI 提示的不可达代码清理；不与本次修复混合。

## 10. `CliRunConfiguration` Active Build Variant 同步设计

### 10.1 目标与非目标

目标：

- Sync 后使用 effective project model 恢复 Active Build Variant 对应的 Jugg Configuration。
- IDEA 与 standalone 继续共享 `CliRunConfiguration` schema、配置集合和当前指针。
- 不依赖 Android Studio deploy compat suggestion。
- 已有用户配置优先，自动同步只补缺失配置，不覆盖用户字段。

非目标：

- 不导入普通 Android Run Configuration。
- 不恢复 `SuggestRunConfiguration`。
- 不在本次设计中清理 compat 层旧 API。
- 不新增配置 schema 字段，除非实现阶段证明仅靠现有 `moduleName`、`variant`、`compileCommand` 无法稳定匹配。

### 10.2 数据来源与身份

唯一模型来源为 Sync 后 `CompileContextManager.getProjectInfo()` 返回的 effective `JuggProjectInfo`。每个 application module 使用：

```text
module identity = moduleStdPath
variant identity = effective buildVariant
profile identity = moduleStdPath + variant
```

`moduleName` 继续作为展示与兼容字段，匹配优先使用从现有 `compileCommand` 解析出的 module path + variant；无法解析时才回退 `moduleName + variant`。自动生成配置继续使用 `CliRunConfigurationGenerator.stableId(moduleStdPath, variant)`，保证重复 Sync 不产生新配置。

### 10.3 Reconcile 流程

Sync 成功或 `SKIPPED` 后，先更新 effective project model，再执行一次 reconcile：

```text
读取全部 IDEA Jugg Configuration
  -> 导入/刷新 CLI store
  -> 读取每个 application module 的 effective buildVariant
  -> 为 module + variant 生成确定性候选
  -> 匹配已有 IDEA/CLI 配置
     -> 已存在：保留用户配置
     -> 不存在：创建新的 IDEA Jugg Configuration 并保存到 store
  -> 根据切换规则更新 IDEA selected configuration 和 current pointer
```

建议第一版只处理当前 effective project model 中的 application module，不根据 `variants` 列表预创建所有配置。这样只恢复 Active Build Variant 用户行为，不扩大配置数量。

### 10.4 匹配与合并规则

匹配优先级：

1. 已有配置解析出的 module path + variant 与候选一致。
2. 已有 `CliRunConfiguration` 的 `moduleName + variant` 与候选一致。
3. 自动生成配置的稳定 id 与候选一致。

命中已有配置时完全保留：

- `compileCommand`，包括 `--offline`、`-Pxxx` 等附加参数。
- `outputApkName`。
- remote compile、sync、proxy、environment 和 exclude pattern 字段。
- 配置名称和 UUID。

只有缺失配置才使用 generator 生成默认 task 和 APK output。生成失败时只跳过该 module，记录 debug；所有 application module 都无法形成候选且项目没有任何 Jugg Configuration 时，沿用现有 `ensureConfiguration()` 的失败语义，不伪造成功。

### 10.5 选择规则

- 当前 selected configuration 不是 Jugg：只创建/同步，不改变 IDEA 选择，也不改变 CLI current pointer。
- 当前 selected configuration 是 Jugg，且其 module 在本次 Active Build Variant 中有对应配置：切换到同 module 的目标 variant，并更新 current pointer。
- 当前 selected Jugg Configuration 已经匹配 Active Build Variant：保持不变，只确保 store 已同步。
- 当前 selected Jugg Configuration 的 module 已不存在或无法识别：不自动切换，保留当前选择并记录 debug。
- 项目原来完全没有 Jugg Configuration：创建默认 application module 当前 variant，选择它并写入 current pointer。

该规则保留旧行为中有明确用户价值的“同模块随 Active Build Variant 切换”，但不恢复普通 Android Run Configuration 驱动的跨模块 suggestion。

### 10.6 调用时序

当前 `onSyncEvent()` 在更新 project model 前调用 `tryCreateRunConfigurations()`，不适合新 reconcile，因为它可能读到旧 `buildVariant`。建议调整为：

```text
Sync SUCCEEDED/SKIPPED
  -> updateProjectInfo(...)
  -> effective model 更新并完成 rebind
  -> reconcile Active Build Variant configurations
```

初始化阶段仍可调用现有 `ensureConfiguration()`：有 IDEA 配置时只导入，没有配置且 project model 已就绪时生成默认配置；model 未就绪则保持有限重试。

### 10.7 失败与降级

- 单个 module 的 task 或 APK output 无法生成：跳过该 module，继续处理其他 module。
- store 单个旧配置无法反序列化：沿用 `loadAll()` 的 best-effort 跳过，不影响 IDEA 现有配置。
- 创建 IDEA Configuration 失败：保留已经成功导入/生成的其他配置，最终异常按现有 `JuggManager` warn 路径收口。
- 无法识别当前 selected Jugg Configuration 的 module/variant：不切换，不修改 current pointer。
- reconcile 在 Sync 的既有 project write 事务中执行，不额外获取 project lock；`runConfigurationLock` 继续保证同 Runtime 内初始化串行，store 自身继续原子写。文件扫描已移出 project lock，避免与该事务互相阻塞。跨 Runtime 同时写配置集合的协调仍遵循现有 CLI store 设计，若后续验证发现不足，再单独处理，不在本次预建新锁。

### 10.8 测试矩阵与实施顺序

| 层级 | Owner | 修改前失败场景 | 修改后结果 |
|---|---|---|---|
| L1 | `CliRunConfigurationTest` | 已有 debug 配置时无法为同 module 的 release effective variant 生成稳定候选 | 候选 id/task/APK output 稳定，custom build directory 正确 |
| L2 | `IdeaCliRunConfigurationFlowTest` | 已有 app debug，Sync model 变为 app release 后不创建/切换 | 创建 app release；当前选中 app debug 时切换并更新指针 |
| L2 | `IdeaCliRunConfigurationFlowTest` | 当前选中普通 Android Configuration | 只创建 app release，不改变选择和 pointer |
| L2 | `IdeaCliRunConfigurationFlowTest` | 已有 app release 含自定义参数和远端字段 | 复用原配置，字段不被 generator 覆盖 |
| L2 | 现有 Run Configuration 并发 owner | 文件过滤被阻塞时 Sync 配置 reconcile 等待 project lock | reconcile 可完成，文件处理继续由 Runtime 内锁串行 |
| L3 | `TopLevelFlowTest#testInstallAndLaunch` | 配置和锁调整破坏 Run 主链路 | 完整 Run、编译、部署仍通过 |

实施顺序：

1. 在现有 owner 中写 P0 并发失败测试，确认 project lock 竞争。
2. 将文件处理改为 Runtime 实例内锁，使并发测试通过。
3. 在 `CliRunConfigurationTest` 和 `IdeaCliRunConfigurationFlowTest` 写 Active Build Variant 失败测试。
4. 增加 generator 的单 module + variant 候选入口。
5. 增加 IDEA reconcile，并调整 Sync 后调用时序。
6. 运行 L1、L2、L3 定向回归与 `:idea:compileKotlin`。
7. 同步 `04_engineering_ide.md` 与 standalone CLI 设计文档。

## 11. 本轮落地结果

- `FileChangeManager.runPendingFileProcessing()` 已移除 project write lock，改用 Runtime 实例内锁；pending barrier、取消和异常收尾保持原语义。
- `CliRunConfigurationGenerator.generateForModule()` 已提供按 module 当前 variant 生成稳定候选的入口。
- `IdeaCliRunConfigurationManager.reconcileActiveBuildVariants()` 已基于 effective `JuggProjectInfo` 补齐 application module 配置，复用时保留用户字段，并区分 Jugg/非 Jugg 当前选择。
- `JuggManager.onSyncEvent()` 已调整为先刷新 project info，再执行 Active Build Variant reconcile，避免读取旧 variant。
- 已补充 L1/L2 测试：稳定候选、同 module variant 创建与切换、已有自定义字段保留、非 Jugg 选择不被替换、文件扫描不持有 project lock。
- L3 `TopLevelFlowTest#testInstallAndLaunch` 已执行，但测试设备返回 `ARCH_UNKNOWN`，未通过其既有 `ARCH_64_BIT` 设备架构断言；失败发生在部署设备检查，与本次 Run Configuration 和锁改动路径无关。
- 剩余独立事项：`SuggestRunConfiguration` compat API 和数据类的不可达代码清理，按设计不纳入本次 rebase 修复。

## 12. 后续 rebase

本次沉淀的通用经验与下一次执行流程已独立维护在 [standalone CLI rebase 经验与执行手册](standalone_cli_rebase_runbook.md)。

## 13. 2026-08-04 再次 rebase

### 13.1 基本信息

- 执行日期：2026-08-04（Asia/Shanghai）。
- 工作分支：`feature/standlone_cli`。
- rebase 前 HEAD：`32edd3c2bcd9f6158e40fcfa46d7f655c9e1cf8c`。
- rebase 前与 main 的共同祖先：`5859b181126d8d751dbb76705b17b30db86f7be3`。
- 固定 main 基线：`881eee542e8017e80aa053ea97c8ee50f0ebb1f0`。
- rebase 前相对 main 的提交数：main 侧 39 个，feature 侧 21 个。
- 本轮备份分支：`feature/standlone_cli_rebase_20260804_old`。
- 备份分支指向：`32edd3c2bcd9f6158e40fcfa46d7f655c9e1cf8c`。
- rebase 完成后的 15 个重放提交 HEAD：`4b5c43df64e1b6c7d281696a36e80efce7a05686`。
- rebase 与测试兼容修正完成时的 HEAD：`5b2447faf5429c0686d739a95192ac61d4e2501c`。

开始前确认工作区干净，并创建不可覆盖的新备份分支。原有 `feature/standlone_cli_old` 未修改。

### 13.2 重放范围修正

第一次直接执行 `git rebase main` 时，Git 尝试重放共同祖先之后的全部 21 个提交，并在早期开放源码文档提交中产生冲突。检查提交历史后确认，前 6 个提交属于 standalone CLI 设计开始前的旧基线改动，其内容已经被当前 main 吸收、迁移或替代；继续重放会在仓库根目录重复引入旧文档，而不是保留 standalone CLI 功能。

因此中止第一次尝试，固定真正的 feature 起点 `bd59bdeb28add42d0a3942b6756fb40809ac279c`，改为执行等价操作：

```shell
git rebase --onto 881eee542e8017e80aa053ea97c8ee50f0ebb1f0 \
  bd59bdeb28add42d0a3942b6756fb40809ac279c \
  feature/standlone_cli
```

最终只重放 15 个 standalone CLI 设计、实现、兼容修正和记录提交。该范围与备份分支中 `bd59bdeb..HEAD` 的 15 个提交一一对应。

### 13.3 提交映射

| 序号 | rebase 前提交 | rebase 后提交 | 结果 |
|---|---|---|---|
| 1 | `589e2485a` | `017fbfac5` | patch 等价 |
| 2 | `93fe0b8fa` | `0230c6170` | patch 等价 |
| 3 | `d418c52e0` | `32c7aa3b9` | patch 等价 |
| 4 | `1f18a9632` | `e916caa7c` | 冲突整合 |
| 5 | `67cf4ed46` | `f7acb6307` | 冲突整合 |
| 6 | `89aae178f` | `01c9e1f6d` | 冲突整合 |
| 7 | `ed947ac57` | `38011c536` | 冲突整合 |
| 8 | `5a8717d87` | `2b218498d` | 冲突整合 |
| 9 | `8945dc9a6` | `436c6b694` | 冲突整合 |
| 10 | `e8d4a7852` | `57ee02c4d` | 冲突整合 |
| 11 | `8af75cadc` | `80d2fca9c` | 冲突整合 |
| 12 | `e3981373b` | `805dec1ad` | 冲突整合 |
| 13 | `dc7ddf3f4` | `84cd4d1a2` | patch 等价 |
| 14 | `f84048698` | `a5098d2ee` | patch 等价 |
| 15 | `32edd3c2b` | `4b5c43df6` | patch 等价 |

rebase 完成后的验证额外发现一个测试源码旧包引用，按 runbook 要求使用独立提交修正：

- `5b2447faf [test] keep main tests compiling after package reorganization`

### 13.4 冲突与处理

#### 13.4.1 step1：project runtime

主要冲突文件：

- `idea/src/main/java/com/sickworm/intellij/jugg/JuggManager.kt`
- `docs/ai_knowledge/98_code_map.md`

处理：

- 采用 feature 下沉后的 `IDeployStateManager`、`DeployStateManager` 和 `IdeaHostDeployStateResolver` 边界。
- 保留 main 新增的 Control Panel、full build、androidTest 和运行配置行为，不用旧版 `JuggManager` 整文件覆盖。
- 知识库保留 main 最新的 Gradle、Compose、诊断和 Control Panel 索引，再补入共享部署状态职责。

#### 13.4.2 step2～step4：task、project model 与 file change domain

这一阶段同时存在包迁移和行为 owner 下沉，主要涉及：

- `JuggManager`
- `GitChangesCompileChecker` 及其测试
- `CompileContextManager`、`ProjectModelSource`
- `FileChangeManager`、IDEA file monitor 和 deploy state pending barrier
- 对应 `04_engineering_ide.md`、`05_utilities.md`、`98_code_map.md`

处理：

- 采用共享 `TaskRunnerManager`、`HostTaskExecutor`、project/global execution lock 和共享 compile context。
- 保留 main 的非阻塞 `GitChangesCompileChecker#getAsyncResultIfCompleted()` 行为，并调整测试 mock 到新的 manager 构造边界。
- `GradleProjectModelSource` 构造根项目信息时保留 main 新增的 `agpR8Classpath` 传播：从各 source 结果中选择首个非空值。
- 文件变化交由共享 `FileChangeManager`，IDEA 侧只保留 monitor adapter 和 Control Panel 结果刷新。
- `FileChangeManager.runPendingFileProcessing()` 不获取 project write lock，只使用 Runtime 实例内锁串行文件处理；pending barrier、取消和异常收尾仍由同一个 `AtomicBoolean` 保证只结束一次。

#### 13.4.3 step5：runtime configuration

主要冲突和 modify/delete 文件：

- `idea/src/main/java/com/sickworm/intellij/jugg/JuggManager.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/project/runtime/JuggGlobalPathManager.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/server/JuggServer.kt`
- 已被 main 删除的 `ReportProgressDialog.kt`
- `docs/ai_knowledge/05_utilities.md` 等知识库文件

处理：

- 接入 `JsonRuntimeSettingsRepository`、`ProjectCustomConfigManager`、旧 IDEA 设置迁移和 `JuggServer.initialize()`。
- 保留 main 的本地事件存储、诊断包、Control Panel 和过期文件清理能力。
- `JuggGlobalPathManager` 合并保留 `settingsFile`、`actionDbFile`、`deployCacheDbFile` 等双方仍在使用的路径字段。
- `ReportProgressDialog.kt` 继续保持 main 的删除结果，不恢复已经被新的诊断 UI 替代的旧类。
- `CustomCompilerManager` 调用适配新 `init(context)` 契约，不恢复旧的 compiler 参数。

#### 13.4.4 step6：server 与 hot update boundary

文本冲突文件：

- `main/src/main/java/com/sickworm/intellij/jugg/server/JuggServer.kt`
- `idea/src/test/java/com/sickworm/intellij/jugg/server/JuggServerTest.kt`
- `idea/src/main/java/com/sickworm/intellij/jugg/JuggManager.kt`
- `docs/ai_knowledge/04_engineering_ide.md`
- `docs/ai_knowledge/05_utilities.md`
- `docs/ai_knowledge/98_code_map.md`

处理：

- `JuggServer` 使用 Host 注入的 `RuntimeInfo`，hot update 下载、校验和发布下沉到共享 `JuggHotUpdateManager`。
- 保留 main 的 `JuggEventLocalStore` 和独立白名单诊断上传流程；不使用 feature 旧的 report issue UI 覆盖 main。
- `JuggServerTest` 保留“无可用 server 时事件仍写入本地数据库”的稳定断言，并补入 `RuntimeInfo` 构造参数。
- `JuggManager` 同时保留 main 的 Control Panel、诊断、Git 用户信息和缓存清理入口，以及 feature 的 `IdeaHotUpdateCoordinator`。
- 文档保留 main 的诊断、Control Panel 和新工程事实，再补入 RuntimeInfo/hot update 边界。

#### 13.4.5 standalone CLI 主入口

文本冲突文件：

- `idea/src/main/java/com/sickworm/intellij/jugg/JuggManager.kt`
- `docs/ai_knowledge/04_engineering_ide.md`
- `docs/ai_knowledge/98_code_map.md`
- `docs/ai_knowledge/99_index.md`

处理：

- 用 `CliRunConfigurationStore` 和 `IdeaCliRunConfigurationManager` 替代旧的 suggestion-only 配置创建实现。
- 保留 main 的 Control Panel 可用状态更新；`ensureConfiguration()` 成功后仍激活对应 Tool Window availability。
- Gradle full build 成功后调用 `updateAfterSuccessfulGradleBuild()` 回写共享 build profile。
- 继续保留 main 的 deploy context 恢复、deploy state 查询和运行主链路；没有因 feature 删除旧 helper 而误删这些能力。
- 知识库同时记录 CLI 配置集合、独立 Run Configuration 锁、Control Panel snapshot 和 compat stub 验证入口。

#### 13.4.6 rebase conflict cleanup commit

文本冲突和 modify/delete 文件：

- `cmd_line/src/test/java/com/sickworm/intellij/jugg/cmdline/base/LibrariesBackupHelperTest.kt`
- `main/src/test/java/com/sickworm/intellij/jugg/deploy/DeployDataPlannerTest.kt`
- `idea/src/test/java/com/sickworm/intellij/jugg/project/dependency/GradleProjectInfoLocalFetchManagerTest.kt`
- `idea/src/test/java/com/sickworm/intellij/jugg/manager/TopLevelFlowTest.kt`
- `idea/src/test/java/com/sickworm/intellij/jugg/manager/JuggManagerFullBuildFlowTest.kt`
- `idea/src/main/java/com/sickworm/intellij/jugg/JuggManager.kt`
- `idea/src/test/java/com/sickworm/intellij/jugg/manager/JuggManagerRunConfigurationSyncTest.kt`

处理：

- 测试 import 统一到 `project.info`、`project.runtime`、`compiler.context` 和 `project.change` 新包，保留 main 新增的断言与测试场景。
- `JuggManager` 增加可注入 `RunManager`，供新的 IDEA CLI configuration flow 测试隔离宿主 service。
- 删除已经由 `IdeaCliRunConfigurationFlowTest`、main 模块 `TaskRunnerManagerTest` 和新 deployment cache owner 替代的旧测试文件，不恢复重复 owner。
- 保留 main 新增的 `JuggManagerFullBuildFlowTest`，并将依赖更新到新的 runtime/file-change 边界。

### 13.5 rebase 后语义检查与独立修正

rebase 完成后，`git range-diff` 确认原 15 个提交全部有对应 patch。main 基线检查确认：

- `git merge-base HEAD main` 等于固定 main `881eee542e8017e80aa053ea97c8ee50f0ebb1f0`。
- 15 个重放提交顺序保持不变。
- Active Build Variant 修正提交完整保留，Sync 时先更新 effective project model，再执行 `reconcileActiveBuildVariants()`。
- 文件 monitor 扫描未重新持有 project write lock。

第一次执行定向 `:main:test` 时，`compileTestKotlin` 失败：

```text
JschShellTerminalHelperTest.kt: Unresolved reference: JuggException
```

原因是生产类已经从 `com.sickworm.intellij.jugg.project.JuggException` 迁到 `com.sickworm.intellij.jugg.JuggException`，该测试没有随包迁移更新 import。修正仅调整测试 import，并使用独立 `[test]` commit 保存；没有修改生产行为。

### 13.6 验证结果

以下验证均通过：

- `./gradlew :idea:compileKotlin`
- `./gradlew :cmd_line:test --tests com.sickworm.intellij.jugg.cmdline.base.LibrariesBackupHelperTest`
- `./gradlew :main:test --tests com.sickworm.intellij.jugg.project.runtime.CliRunConfigurationTest --tests com.sickworm.intellij.jugg.project.runtime.TaskRunnerManagerTest --tests com.sickworm.intellij.jugg.project.info.ProjectModelSourceTest`
- `./gradlew :idea:test --tests com.sickworm.intellij.jugg.ide.logic.JuggConfigurationRunnerTest --tests com.sickworm.intellij.jugg.compiler.context.CompileContextManagerBuildPathInfoTest --tests com.sickworm.intellij.jugg.compiler.GitChangesCompileCheckerTest --tests com.sickworm.intellij.jugg.compiler.JuggCompileUiHandlerTest --tests com.sickworm.intellij.jugg.project.runtime.IdeaCliRunConfigurationFlowTest --tests com.sickworm.intellij.jugg.manager.JuggManagerFullBuildFlowTest --tests com.sickworm.intellij.jugg.project.dependency.GradleProjectInfoLocalFetchManagerTest`
- `./gradlew :main:test --tests com.sickworm.intellij.jugg.gradle.compile.JschShellTerminalHelperTest`
- `./gradlew :idea:test --tests com.sickworm.intellij.jugg.server.JuggServerTest`
- `git diff --check`
- `git range-diff bd59bdeb..feature/standlone_cli_rebase_20260804_old 881eee542..HEAD`

构建过程中仍有既有的 NDK `riscv64` metadata、IntelliJ `sourceCompatibility` 和 Kotlin stdlib 配置警告，不影响本轮编译及定向测试结果。

### 13.7 最终结果

- 当前分支已基于 main `881eee542e8017e80aa053ea97c8ee50f0ebb1f0`。
- rebase 前状态保存在 `feature/standlone_cli_rebase_20260804_old`。
- 原 15 个 standalone CLI 提交全部完成映射；冲突提交按 main 新行为与 feature 新边界合并。
- 额外测试兼容修正保持为独立 commit `5b2447faf`。
- rebase 与验证完成后工作区干净，未执行 push。

## 14. 2026-08-05 历史整理

### 14.1 目标

在保留最终功能树和原始 WIP 顺序的前提下，将 rebase 后的兼容修正回填到实际 behavior owner，避免 standalone CLI 主系列之后继续保留宽泛的 conflict cleanup 和 bugfix 提交。

整理前状态保存在 `backup/standalone-cli-before-amend-20260805`，未执行 push。

### 14.2 回填结果

| 原提交 | 新提交 | 回填内容 |
|---|---|---|
| `e916caa7c` | `04fc7357d` | step1 project runtime，内容保持不变 |
| `f7acb6307` | `8a31ec74c` | step2 task execution，并删除已迁移的 IDEA task/cache 重复测试 |
| `01c9e1f6d` | `02afba189` | package reorganization，并吸收测试 import 与 `JuggException` 包迁移修正 |
| `38011c536` | `f9bb060ef` | step3 project model，并补齐 model source、environment source 和相关测试契约 |
| `2b218498d` | `408c073ec` | step4 file change，并回填 Runtime 内 file-processing lock 及并发测试 |
| `436c6b694` | `5c8e1bb07` | step5 runtime configuration，并同步 Flow 测试构造边界 |
| `57ee02c4d` | `a5faca82b` | step6 server/hot update，并同步 RuntimeInfo 与 IDEA coordinator 测试边界 |
| `480725a58` | `006731f5b` | step7 CLI run configuration，并回填 Active Build Variant reconcile、RunManager 隔离和新测试 owner |

原 `09ff3c0b`、`222ef5ad` 和 `3d2ac58e2` 的代码与测试变更已全部进入上述 WIP commit，不再保留独立实现提交。原 rebase review、修复过程和当时的 commit hash 仍保留在前文章节，作为执行历史而不是当前提交结构说明。

### 14.3 验证口径

- step3～step7 均执行对应模块测试源码编译，确认 API 接线随所属领域提交同步完成。
- 最终提交执行定向 L1/L2 测试、IDEA 编译和最终树差异检查。
- `FileChangeManager` 文档已改为 Runtime 实例内锁，与当前代码一致。
