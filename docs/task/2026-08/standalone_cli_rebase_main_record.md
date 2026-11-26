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

## 15. 2026-08-06 再次 rebase

### 15.1 基本信息

- 执行日期：2026-08-06（Asia/Shanghai）。
- 工作分支：`feature/standlone_cli`。
- rebase 前 HEAD：`afe89f805fc4d2768c48cf0d3b6b10fcafe79f7f`。
- rebase 前共同祖先：`881eee542e8017e80aa053ea97c8ee50f0ebb1f0`。
- rebase 前 feature 提交数：14。
- 备份分支：`feature/standlone_cli_rebase_20260806_old`。
- 备份分支指向：`afe89f805fc4d2768c48cf0d3b6b10fcafe79f7f`。
- 第一次固定 main 基线：`52db22a70b11e53241b65ea097c6df37908dff67`。
- 执行期间 main 新增提交：`775bc23a7 [feature] enable compat deploy on all HarmonyOS devices`。
- 最终 main 基线：`775bc23a7266b3a65fc5cee45be23c5342077a71`。
- rebase 后 14 个重放提交 HEAD：`226fceaac9a39286cc9c209a6740c825b63d0a3f`。
- rebase 后测试兼容修正：`fa0651e2a [test] keep MCP tests compiling after project runtime rebase`。

开始前确认工作区干净，并创建不可覆盖的新备份分支。第一次按旧记录中的 `bd59bdeb` 作为起点尝试时，Git 计划重放 53 个提交；检查当前提交图后确认该边界只适用于历史整理前的分支，因此立即中止。最终使用当前共同祖先 `881eee542` 作为旧基线，只重放 `main..feature` 的 14 个提交。

第一次完成 rebase 后，本地 main 从 `52db22a70` 前进到 `775bc23a7`。按 runbook 再次执行增量 rebase，14 个提交全部自动重放，最终共同祖先与最新 main 一致。

### 15.2 提交映射

| 序号 | rebase 前提交 | rebase 后提交 | 结果 |
|---|---|---|---|
| 1 | `d67f32a82` | `943a72b97` | patch 等价 |
| 2 | `bcb0b7c91` | `f58ec3d8e` | patch 等价 |
| 3 | `b5f1a68d3` | `95d233ed9` | patch 等价 |
| 4 | `04fc7357d` | `01ddf1d81` | 冲突整合 |
| 5 | `165916e56` | `f2171fce9` | 冲突整合 |
| 6 | `59ee44212` | `b91c3b9f7` | 冲突整合 |
| 7 | `47da76ca8` | `9ff8c39ee` | 冲突整合 |
| 8 | `184067c6c` | `28db30eb3` | 冲突整合 |
| 9 | `98f97be8b` | `cf10db0f7` | patch 含 main 文档演进 |
| 10 | `65b66ee94` | `c93cd5c7f` | patch 等价 |
| 11 | `c73e2323f` | `8b36315ce` | patch 含 main 文档演进 |
| 12 | `a28be584a` | `901cf29c4` | patch 等价 |
| 13 | `0d186e839` | `fb6c457be` | 冲突整合 |
| 14 | `afe89f805` | `226fceaac` | patch 含 main 文档演进 |

### 15.3 冲突与处理

1. step1、step2、包重组和 step4 的文本冲突集中在 `docs/ai_knowledge/98_code_map.md`。
   - 保留 main 新增的 Kotlin common roots/fragment graph、Windows Gradle wrapper CRLF、Compose 和 Gradle 信息。
   - 同时保留 feature 的共享部署状态、任务锁、部署缓存、`project.info` / `project.runtime` / `project.change` 新边界。
2. step3 的 `GradleProjectInfoLocalFetchManagerTest` 同时发生构造边界和 main 新行为变化。
   - 测试改用共享 `JuggPathManager`、`CompileContextManager`、`ICompileEnvironmentSource` 和六参数 `runTaskSafe` 契约。
   - 保留 main 的“缺失 project info 重建完成前禁止增量编译”断言，以及 remote init 等待行为。
3. step8 的 `CompileAndDeployMcpToolActionTest` 同时发生 `IMcpRuntime` host-neutral 化和 main 新增 projectDir 相关行为。
   - 改用 `TestMcpRuntime`，显式覆盖传入的 `projectDir`，保留 last-deploy timestamp 和 no-pending-deploy 断言。
   - `98_code_map.md` 保留 main 最新 Gradle/KMP 信息，并补入 `McpToolRegistry`、`IMcpRuntime` 与 standalone idle callback 边界。
4. 其他生产代码、测试和最终 HarmonyOS main 提交均自动应用；没有整文件选择 ours/theirs。

### 15.4 rebase 后语义修正

第一次执行定向 `:main:test` 时，`compileTestKotlin` 失败：

```text
McpInvokerTestBase.kt: Unresolved reference: ProjectDirNormalizer
```

生产类已迁移到 `com.sickworm.intellij.jugg.project.runtime.ProjectDirNormalizer`，测试仍引用旧包。修正只更新测试 import，以独立 `[test]` commit `fa0651e2a` 保存，没有修改生产行为。

### 15.5 验证结果

以下验证通过：

- `./gradlew :idea:compileKotlin`
- `./gradlew :main:test --tests com.sickworm.intellij.jugg.project.info.ProjectModelSourceTest --tests com.sickworm.intellij.jugg.project.runtime.TaskRunnerManagerTest --tests com.sickworm.intellij.jugg.project.change.FileChangeManagerTest --tests com.sickworm.intellij.jugg.ai.mcp.actions.CompileAndDeployMcpToolActionTest --tests com.sickworm.intellij.jugg.project.runtime.JuggResourceManagerTest`
- `./gradlew :idea:test --tests com.sickworm.intellij.jugg.project.dependency.GradleProjectInfoLocalFetchManagerTest --tests com.sickworm.intellij.jugg.project.runtime.IdeaCliRunConfigurationFlowTest --tests com.sickworm.intellij.jugg.manager.JuggManagerFullBuildFlowTest`
- `./gradlew :cmd_line:test --tests com.sickworm.intellij.jugg.cmdline.standalone.DaemonIdleTimerTest --tests com.sickworm.intellij.jugg.cmdline.standalone.StandaloneRuntimeTest`
- `./gradlew :deploy_compat:standalone_deployer:test --tests com.sickworm.intellij.jugg.deploy.run.StandaloneApplyChangesExecutorTest --tests com.sickworm.intellij.jugg.deploy.run.StandaloneDeployerArchitectureTest --tests com.sickworm.intellij.jugg.deploy.run.StandaloneDeployerDeviceFlowTest --tests com.sickworm.intellij.jugg.deploy.run.StandaloneDeployerResourceTest`
- `python3 docs/skills/jugg-android-dev-loop/tests/test_jugglib.py`
- `python3 docs/skills/jugg-android-dev-loop/tests/test_cmd_version.py`
- `python3 docs/skills/hooks/tests/test_hooks_guard.py`
- `git diff --check`
- `git range-diff 881eee542..feature/standlone_cli_rebase_20260806_old 775bc23a7..226fceaac`

`unittest discover` 因 tests 目录与 `scripts/py` 中同名 `test_cmd_status` 模块发生导入冲突，改为直接运行本次变更相关的三个 Python 测试文件后全部通过。构建仍输出既有 NDK `riscv64` metadata、IntelliJ `sourceCompatibility`、Kotlin stdlib 和少量 Kotlin 编译 warning，不影响本轮结果。

### 15.6 最终结果

- 当前分支基于 main `775bc23a7266b3a65fc5cee45be23c5342077a71`。
- rebase 前状态保存在 `feature/standlone_cli_rebase_20260806_old`。
- 原 14 个 standalone CLI 提交全部完成映射，顺序保持不变。
- rebase 后测试包迁移修正保持为独立 commit `fa0651e2a`。
- 未执行 push。

## 16. 2026-08-10 再次 rebase

### 16.1 基本信息

- 执行日期：2026-08-10（Asia/Shanghai）。
- 工作分支：`feature/standlone_cli`。
- rebase 前 HEAD：`405d38437e2f83c19196cb38656434a00990592c`。
- rebase 前共同祖先：`453b67e74a655657138078b143f247fe733e1aff`。
- 固定 main 基线：`362f9b5e78645f9623238ad2988a888036143d19`。
- 备份分支：`feature/standlone_cli_rebase_20260810_old`。
- 备份分支指向：`405d38437e2f83c19196cb38656434a00990592c`。
- rebase 后 standalone 提交数：26；原图标提交已由 main 包含，因此未生成新提交。
- rebase 后测试兼容修正：`b6b1c39d0 [test] keep deploy recovery tests compiling after runtime rebase`。

开始前工作区存在用户对 `android_demo_project/app/src/main/java/com/example/myapplication/MainActivity.kt` 的未提交改动。该文件单独保存到 `stash@{0}`，未混入 rebase、冲突解决或后续提交，全部工作完成后恢复。

当前 main 与 feature 曾分别重写历史，直接比较得到双方各 222 个提交。`git cherry main HEAD` 显示 193 个 patch 等价提交和 29 个候选独有提交，但其中 `53a7829c9`、`342ad8348` 是旧主线提交，不属于 standalone feature。最终保留 27 个真实 feature patch：图标提交由 main 自动跳过，剩余 26 个按原顺序重放。

### 16.2 提交映射

| 序号 | rebase 前提交 | rebase 后提交 | 结果 |
|---|---|---|---|
| 1 | `9d8ee7b65` | - | main 已包含相同图标 patch |
| 2 | `700544c7b` | `a225a6f62` | patch 等价 |
| 3 | `a5889a743` | `4185e9bf0` | patch 等价 |
| 4 | `b497ee9b6` | `36d9bd815` | patch 等价 |
| 5 | `52d04d238` | `45dbe493a` | 冲突整合 |
| 6 | `6388d917f` | `07576fc38` | 冲突整合 |
| 7 | `e8703a52c` | `3f27a686e` | 自动重放 |
| 8 | `7ea4c8023` | `c6773da3f` | 冲突整合 |
| 9 | `0cd329b97` | `d14862fa5` | 冲突整合 |
| 10 | `073a67dbe` | `aaada1fc0` | 冲突整合 |
| 11 | `1ed532035` | `57beecc84` | 冲突整合 |
| 12 | `4c9ce2542` | `0d44a2cb1` | 冲突整合 |
| 13 | `8cd400254` | `d6213de7a` | patch 含 main 文档演进 |
| 14 | `685d94795` | `c4ff2c444` | 冲突整合 |
| 15 | `e1c90a65b` | `96b6a4257` | 冲突整合 |
| 16 | `9deaeb10b` | `5027e3d67` | patch 等价 |
| 17 | `9ef9dd5fe` | `75e8470ea` | 冲突整合 |
| 18 | `2b348eb63` | `3d82d3178` | patch 等价 |
| 19 | `3f74c7cb6` | `1b5b6ed7f` | 冲突整合 |
| 20 | `11c4bfc67` | `d6c49c2a3` | patch 含 main 文档演进 |
| 21 | `5fe35de5e` | `07a641745` | 冲突整合 |
| 22 | `18400686c` | `769ad4d2c` | patch 等价 |
| 23 | `c73cd89de` | `6ecb5721c` | patch 等价 |
| 24 | `191f58386` | `464420d3a` | 冲突整合 |
| 25 | `76c3c4576` | `ace79b809` | patch 等价 |
| 26 | `b815404ff` | `edb59fad3` | patch 等价 |
| 27 | `405d38437` | `20f435b8c` | patch 等价 |

### 16.3 提交边界误判

1. `53a7829c9 [docs] refine wiki style` 被 Git 作为第 1 个候选提交重放，并在以下文件产生 add/add 冲突：
   - `docs/wiki/.vitepress/theme/index.ts`
   - `docs/wiki/.vitepress/theme/style.css`
   - `docs/wiki/public/assets/run_configuration.svg`
   该提交属于旧主线，不是 standalone feature。未解决文件内容，直接 `git rebase --skip`，由当前 main 保持最新 Wiki 实现。
2. `342ad8348 [docs] make coding guidance easier for agents to apply...` 自动重放但同样属于旧主线。rebase 完成后用 `git rebase --onto` 从最终历史移除，23 个后续 standalone 提交无冲突重新重放。
3. `9d8ee7b65 [feature] update run_configuration.svg` 被 Git 判定 patch 已在 main，自动 drop。最终图标仍由 main 提供，没有丢失产物。

### 16.4 冲突提交与解决方案

1. `52d04d238` step1：`docs/ai_knowledge/04_engineering_ide.md`。
   - 保留 main 的 hot-update ClassLoader、标准插件安装和 IDE 生命周期说明。
   - 补入共享 `DeployStateManager`、`IdeaHostDeployStateResolver` 与 Compile Context consumer 边界。
2. `6388d917f` step2：`docs/ai_knowledge/04_engineering_ide.md`。
   - 保留 hot-update 说明，采用 feature 的 `HostTaskExecutor` 和 `TaskRunnerManager` 释放语义。
3. `7ea4c8023` step3：`04_engineering_ide.md`、`04_engineering_project.md`。
   - 保留 main 的 Compose resource、第三方发行合规门禁和 hot-update 内容。
   - 采用 `IProjectModelSource`、`IdeaProjectModelSource`、`ICompileEnvironmentSource`、共享 `CompileContextManager` 和 Gradle fetch 边界。
4. `0cd329b97` step4：`04_engineering_ide.md`。
   - 保留 main 的 hot-update 说明，采用 `FileChangeManager`、`IdeaFileChangeMonitor`、Host compile UI 和 pending barrier 边界。
5. `073a67dbe` step5：`04_engineering_ide.md`。
   - 保留 main 内容，并更新 dispose 契约为释放 custom compiler classloader、deploy runtime、TaskRunner 和 coroutine scope。
6. `1ed532035` step6：`04_engineering_ide.md`。
   - 旧 `JuggHotUpdateDownloader` 已迁移为共享 `JuggHotUpdateManager` + `IdeaHotUpdateCoordinator`，删除过时索引项并采用新的 runtime metadata 描述。
7. `4c9ce2542` step7：`idea/.../JuggRunConfigurationOptions.kt`。
   - 同时保留 main 新增的 `isRemoteSyncExcludePatternsCustomized` 和 feature 的 `cliRunConfigurationId`。
   - 按 Options 顺序持久化约束，将新字段追加在现有字段之后，避免旧配置序号错位。
8. `685d94795` step8：`04_engineering_project.md`。
   - 仅日期冲突，保留 main 更新的 `2026-08-08`，正文完整接收 standalone daemon/runtime owner 内容。
9. `e1c90a65b` step9：`04_engineering_compat.md`。
   - 仅日期冲突，保留 main 更新日期，同时接收 Quail standalone deployer、资源 metadata 和 `JuggResourceManager` 说明。
10. `9ef9dd5fe` owned API refactor：`04_engineering_compat.md`。
    - 仅日期冲突；保留较新日期，正文采用自有 deploy API、converter owner 和 `base_api` 禁止 Android runtime class 的边界。
11. `3f74c7cb6` step10：`04_engineering_ide.md`、`04_engineering_compat.md`、`03_runtime_jvmti.md`。
    - 三处均为日期冲突，分别保留每份文档较新的日期；共享 deploy orchestrator、Host environment 和 JVMTI 边界完整重放。
12. `5fe35de5e` step12：`idea/build.gradle`、`04_engineering_compat.md`。
    - `prepareSandbox` 同时嵌入 `:cmd_line:standaloneBundle`、`third_party` 和 `THIRD_PARTY_NOTICES.md`。
    - `buildPlugin` 同时依赖 standalone Bundle 并在结束后执行 `verifyThirdPartyCompliance`，没有牺牲任一发行门禁。
    - 文档同时保留原 CI 两阶段命令和新增 standalone installer/bootstrap 入口。
13. `191f58386` startup failure：`04_engineering_compat.md`。
    - 保留 CI 命令索引，更新 standalone 语义为 active manifest 不自动 rollback，启动失败直接显式返回。

所有冲突均按行为 owner 合并，没有整文件选择 ours/theirs。

### 16.5 rebase 后语义修正

首次定向 `:main:test` 在 `compileTestKotlin` 失败：

```text
DeployFileManagerRecoverTest.kt: Cannot find a parameter with this name: backgroundTaskRunner
DeployFileManagerRecoverTest.kt: No value passed for parameter 'taskRunnerManager'
```

`DeployFileManager` 已迁移到共享 `TaskRunnerManager`，测试中一个自动合并的构造点仍使用旧 `backgroundTaskRunner`。修正复用该测试已有的 `createImmediateTestTaskRunnerManager()`。

随后 `DeployCompatArchitectureTest` 失败：

```text
Cannot find idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployerHelper.kt
```

step10 已将 `JuggDeployerHelper` 移到 main，静态架构守卫仍检查旧路径。修正只更新 owner 路径，legacy deployer forbidden types 断言保持不变。两项修正合并为独立提交 `b6b1c39d0`，未修改生产行为。

### 16.6 验证结果

以下验证通过：

- `./gradlew :idea:compileKotlin`
- `./gradlew :main:test --tests com.sickworm.intellij.jugg.project.info.ProjectModelSourceTest --tests com.sickworm.intellij.jugg.project.runtime.TaskRunnerManagerTest --tests com.sickworm.intellij.jugg.project.change.FileChangeManagerTest --tests com.sickworm.intellij.jugg.ai.mcp.actions.CompileAndDeployMcpToolActionTest --tests com.sickworm.intellij.jugg.project.runtime.JuggResourceManagerTest --tests com.sickworm.intellij.jugg.deploy.DeployFileManagerRecoverTest`
- `./gradlew :idea:test --tests com.sickworm.intellij.jugg.project.dependency.GradleProjectInfoLocalFetchManagerTest --tests com.sickworm.intellij.jugg.project.runtime.IdeaCliRunConfigurationFlowTest --tests com.sickworm.intellij.jugg.manager.JuggManagerFullBuildFlowTest --tests com.sickworm.intellij.jugg.deploy.run.DeployCompatArchitectureTest --tests com.sickworm.intellij.jugg.deploy.run.JuggDeployerHelperDeployFlowTest`
- `./gradlew :cmd_line:test --tests com.sickworm.intellij.jugg.cmdline.standalone.DaemonIdleTimerTest --tests com.sickworm.intellij.jugg.cmdline.standalone.StandaloneRuntimeTest --tests com.sickworm.intellij.jugg.cmdline.standalone.StandaloneRuntimeInstallerTest --tests com.sickworm.intellij.jugg.cmdline.CmdLineDistributionArchitectureTest`
- `./gradlew :deploy_compat:standalone_deployer:test --tests com.sickworm.intellij.jugg.deploy.run.StandaloneApplyChangesExecutorTest --tests com.sickworm.intellij.jugg.deploy.run.StandaloneDeployerArchitectureTest --tests com.sickworm.intellij.jugg.deploy.run.StandaloneDeployerDeviceFlowTest --tests com.sickworm.intellij.jugg.deploy.run.StandaloneDeployerResourceTest`
- `./gradlew :standalone_bootstrap:test --tests com.sickworm.intellij.jugg.bootstrap.StandaloneBootstrapTest`
- `./gradlew :idea:buildPlugin`
- `python3 docs/skills/jugg-android-dev-loop/tests/test_jugglib.py`
- `python3 docs/skills/jugg-android-dev-loop/tests/test_cmd_version.py`
- `python3 docs/skills/hooks/tests/test_hooks_guard.py`
- `git diff --check`
- `git range-diff d343413cc..b497ee9b6 main..36d9bd815`
- `git range-diff 342ad8348..feature/standlone_cli_rebase_20260810_old 36d9bd815..20f435b8c`

`:idea:buildPlugin` 同时执行并通过 `:cmd_line:standaloneBundle` 与 `:idea:verifyThirdPartyCompliance`。构建仍输出既有 NDK `riscv64` metadata、IntelliJ `sourceCompatibility`/Kotlin stdlib、deprecated Gradle feature 和少量 Kotlin warning，不影响本轮结果。

### 16.7 最终结果

- 当前分支基于 main `362f9b5e78645f9623238ad2988a888036143d19`。
- rebase 前状态保存在 `feature/standlone_cli_rebase_20260810_old`。
- 27 个真实 standalone patch 均已审计：1 个由 main 包含，26 个完成映射且顺序保持不变。
- 两个旧主线误判提交未进入最终历史。
- rebase 后测试兼容修正保持为独立 commit `b6b1c39d0`。
- 未执行 push。

## 17. 2026-08-12 按 main drop 结果重新 rebase

### 17.1 重做原因与基线

第一次 rebase 将 main 中的 `dda77ecd6 [refactor] make IDE module discovery easier to maintain` 一并带入了 `develop`。该提交实际应从 main 历史中 drop，因此本轮没有在第一次结果上继续修补，而是按以下方式重新开始：

- rebase 前原始 `develop`：`884c62839eaa0f4d757095a118fdd6e49bf7e1cd`。
- 原始状态备份：`backup/develop-before-rebase-20260812`。
- 第一次 rebase 结果：`a9cc3af99ebaab6db713eab62fbbf6e7418ac901`。
- 第一次结果备份：`backup/develop-first-rebase-result-20260812`，只用于审计，不再作为后续基线。
- 用户 drop 后的 main：`48a9513aaa387391bd94269e5cb661706a5a1fa0`。
- 从原始 `develop` 重新执行 rebase 后的功能 HEAD：`bc36e15ddd1efad80c26c8bab479d7350c3ba43d`。

原始 35 个 feature commit 均按原顺序重放。以旧共同基线 `5b254c9d2` 和新 main 为边界执行 `git range-diff`，35 个提交均有一对一映射；标记为 `!` 的差异均来自本节记录的冲突解法或新 main 已存在的行为，没有 feature commit 丢失或额外 drop。

### 17.2 冲突与解决方式

本轮共出现 5 个冲突暂停点：

1. `49954204d` package restructure：`CompileEffectAnalyzerTest.kt`。
   - 使用 feature 重组后的 `project.change.ChangedFile`、`project.info.ModuleInfo` 和 `project.runtime.JuggPathManager` 包路径。
   - 同时保留 main 新增的 `AssembleAndroidProjectOnce`、`TestGlobal`、`@Rule` 以及 desugar superclass-chain 回归场景。
   - 重放后提交：`6c7ae0675`。
2. `1c617a53f` runtime settings：`JuggSettings.kt`、`JuggManager.kt`。
   - `isEnableCompatibleDeploymentMode` 使用新 settings owner 的 `setting(true)` 持久化方式。
   - `JuggManager` 同时保留 main 的 `updateCompatibleDeploymentMode()` 和 feature 的 `prepareRun()`，避免兼容部署开关与 standalone 准备流程互相覆盖。
   - 重放后提交：`52cdb8f2a`。
3. `de4f31e6f` standalone runtime MCP：`CompileAndDeployMcpToolAction.kt`。
   - 项目路径统一从 `runtime.projectDir.takeIf { it.isNotBlank() }` 获取，符合共享 runtime 契约。
   - 删除冲突产生的重复声明，同时保留 main 的无待部署文件提示和部署完成时间记录。
   - 重放后提交：`02e6973ba`。
4. `8ce00d494` CLI 文档：`08_cli_tools_list.md`。
   - 将 `init` 放在 `compile` 前，保持命令顺序清晰。
   - 同时保留 main 的 compile no-op 输出说明，以及 feature 的 standalone 单设备选择规则和直接完成/异步轮询输出一致性。
   - 重放后提交：`03a4c7a42`。
5. `67a85f5d1` 版本更新：`build.gradle`、中英文 changelog YAML 与 HTML。
   - 当前版本保持 feature 目标 `4.0.0`。
   - 历史版本顺序为 `4.0.0`、main 新增的 `3.2.4`、既有 `3.2.3`；HTML 直接复用 `3.2.4` 的内容，不创建空版本标题。
   - 重放后提交：`3e93916a7`。

`75b2ff44d` step3 本轮自动应用，没有冲突。由于新 main 已删除模块扫描重构，本轮没有从第一次 rebase 结果迁移 `readModuleInfo`、`createModuleCandidate`、`ModuleScanResult` 等拆分结构；`IdeaProjectModelSource` 保持 drop 后 main 的扫描实现与 feature 的 project-model owner 变更组合。

### 17.3 drop 结果核对

- `git merge-base HEAD main` 返回 `48a9513aaa387391bd94269e5cb661706a5a1fa0`，确认当前分支基于用户更新后的 main。
- `git merge-base --is-ancestor dda77ecd6 HEAD` 返回非零，确认被 drop 的提交不在新历史中。
- `IdeaProjectModelSource.kt` 中不存在第一次结果带入的 `readModuleInfo`、`createModuleCandidate`、`ModuleScanResult` 或 `ModuleCandidate`。
- 第一次结果仍保存在独立备份分支，需要时可用于对照，但不会被当前 `develop` 继承。

### 17.4 验证结果

以下验证通过：

- `./gradlew :idea:compileKotlin`
- `./gradlew :main:test --tests com.sickworm.intellij.jugg.deploy.CompileEffectAnalyzerTest --tests com.sickworm.intellij.jugg.project.info.ProjectModelSourceTest --tests com.sickworm.intellij.jugg.project.runtime.JuggSettingsTest --tests com.sickworm.intellij.jugg.ai.mcp.actions.CompileAndDeployMcpToolActionTest`
- `./gradlew :idea:test --tests com.sickworm.intellij.jugg.compiler.context.CompileContextManagerBuildPathInfoTest --tests com.sickworm.intellij.jugg.compiler.context.CompileContextManagerAndroidTestFilterTest --tests com.sickworm.intellij.jugg.manager.JuggManagerFullBuildFlowTest --tests com.sickworm.intellij.jugg.manager.JuggManagerRunConfigurationSyncTest`
- `git range-diff 5b254c9d2..backup/develop-before-rebase-20260812 48a9513aa..bc36e15dd`
- changelog YAML 解析、版本顺序及 HTML 历史内容一致性检查
- 冲突标记扫描与 `git diff --check`

未执行 push。

## 18. 2026-08-13 rebase 到 3.2.5 main

### 18.1 基线与结果

- rebase 前 `develop`：`1e019b6b5a6680777ac9d9c6b68c619cc3782fdf`。
- rebase 前共同祖先：`c26d056063330da77d8dabbd12623374d5d1e54e`。
- 固定 main 基线：`cd6079fbe325591e639c9baaa76b97f912d0a412`（`3.2.5`）。
- rebase 前分叉计数：main 侧 9 个提交，develop 侧 40 个提交。
- 备份分支：`backup/develop-before-rebase-20260813`。
- 40 个提交重放完成时的功能 HEAD：`e0f5104672fe`。
- rebase 后测试兼容修正：`52bcc7699 [test] keep main regressions compiling after project model rebase`。
- 验证完成时的功能历史相对 main 包含 41 个提交：40 个原提交和 1 个独立兼容修正；本节记录另以 docs commit 保存。

### 18.2 冲突点、解决方式与风险等级

| 冲突提交 | 冲突文件 / 类型 | 解决方式 | 风险等级 |
|---|---|---|---|
| `05059fa6a` package restructure | `ModulePathMergePolicy.kt`、`ModulePathMergePolicyTest.kt` 内容冲突 | 使用 feature 的 `project.info` 包 owner；完整保留 main 新增的 `findIncludedBuildModuleRoots()` 和对应回归用例。重放后提交为 `14362bfa8`。 | 中：冲突集中在包迁移，但若整侧覆盖会丢失 included-build 识别行为。 |
| `d37f042db` project model domain | IDEA `CompileContextManager.kt` modify/delete | 保留 feature 的共享 `CompileContextManager` + `IdeaProjectModelSource` 架构；将 main 的 included-build roots 结果加入 `ProjectModelResult`，由 IDEA/standalone project-model source 计算，并传递到 `BaseCompileContext`。重放后提交为 `e25264232`。 | 高：简单接受删除会静默丢失 main 的 included-build R classpath 修复，可能重新引入资源 ID crash。 |
| `e686ac6c8` version update | `build.gradle`、中英文 changelog YAML/HTML 内容冲突 | 版本保持 feature 目标 `4.0.0`；完整保留 main 的 `3.2.5` 历史内容，并保留 main 的 `agentVersion=1.0.57` 和测试串行化配置。重放后提交为 `147eb7ff9`。 | 中：主要风险是覆盖发布版本历史、agent 版本或测试任务约束。 |

### 18.3 文本冲突之外的语义修正

首次定向验证在 `:main:compileTestKotlin` 阶段失败，发现两个自动合并遗留：

1. `BaseCompileContextModuleDependenciesTest.kt` 仍引用迁移前的 `project.data` 和旧 `BaseCompileContext` 包。
2. `DeployFileManagerDexMergeTest.kt` 仍使用已移除的 `backgroundTaskRunner` 构造参数。

两处都只调整测试到当前 behavior owner：前者改用 `compiler.context.BaseCompileContext` 与 `project.info` 模型，后者复用测试已有的 `taskRunnerManager`。没有修改生产行为，独立提交为 `52bcc7699`。

### 18.4 验证结果

以下验证通过：

- `./gradlew :idea:compileKotlin`
- `./gradlew :main:test --tests com.sickworm.intellij.jugg.project.info.ModulePathMergePolicyTest --tests com.sickworm.intellij.jugg.project.BaseCompileContextModuleDependenciesTest --tests com.sickworm.intellij.jugg.project.info.ProjectModelSourceTest --tests com.sickworm.intellij.jugg.project.info.ProjectModelFlowTest --tests com.sickworm.intellij.jugg.deploy.DeployFileManagerDexMergeTest`
- `./gradlew :idea:test --tests com.sickworm.intellij.jugg.compiler.context.CompileContextManagerBuildPathInfoTest --tests com.sickworm.intellij.jugg.compiler.context.CompileEnvironmentSourceTest`
- `git range-diff c26d056063330da77d8dabbd12623374d5d1e54e..backup/develop-before-rebase-20260813 cd6079fbe325591e639c9baaa76b97f912d0a412..e0f5104672fe`
- `git merge-base HEAD main` 返回 `cd6079fbe325591e639c9baaa76b97f912d0a412`。
- changelog YAML 解析、版本顺序、冲突标记扫描和 `git diff --check`。

`range-diff` 确认原 40 个提交均有一对一映射；差异来自上述冲突解法和 main 已新增的行为，没有 feature commit 丢失。构建中的 NDK `riscv64` metadata、IntelliJ `sourceCompatibility`/Kotlin stdlib 和既有 Kotlin warning 均为非阻断提示。未执行 push。

## 19. 2026-08-18 rebase 到 3.3.0 main

### 19.1 基线与结果

- rebase 前 `develop`：`2b5de5344007d07220ca2a49cff32ee4f928babd`。
- rebase 前共同祖先：`a5f8e50b73ee7376dc673af5dbfa4d1800e77a13`（`3.2.6`）。
- 固定 main 基线：`e858822358990a5823b6854effcbe1cc19549871`（`3.3.0`）。
- rebase 前分叉计数：main 侧 5 个提交，develop 侧 61 个提交。
- 备份分支：`backup/develop-before-rebase-20260818`。
- 61 个提交重放完成时的功能 HEAD：`479c409a72fa`。
- rebase 后测试兼容修正：`87af026d8 [test] keep remote command tests compiling after json settings rebase`。
- 验证完成时的功能历史相对 main 包含 62 个提交：61 个原提交和 1 个独立兼容修正；本节记录另以 docs commit 保存。

main 独有提交为：

- `387d596c0 [other] clarify bug report guidance`
- `a191f754a [feature] run custom commands on the selected remote server`
- `3c1e27bfd [bugfix] prevent nested CLI projects from being routed to parent IDEA`
- `3f9966f00 [optimize] simplify remote command entry`
- `e85882235 [other] update version to 3.3.0`

其中 nested CLI 与 develop HEAD 修复同一问题，按维护者要求保留 develop 实现，不保留 main 的 prefix-match-to-parent 行为。

### 19.2 冲突点、解决方式与风险等级

| 冲突提交 | 冲突文件 / 类型 | 解决方式 | 风险等级 |
|---|---|---|---|
| `366bc56fc` package restructure | `JuggManager.kt` import、`98_code_map.md` Gradle 客户端行 | 同时保留 main 的 `RemoteCommandDialog` 与 feature 的 `CopyGeneratedSourceHelper`；知识库行合并远端命令说明和 generated sync 回写。重放后提交为 `e122baed8`。 | 低：仅 import 与文档行合并。 |
| `8cd583add` runtime configuration | `JuggSettings.kt` 内容冲突 | 保留 feature 的 `JsonRuntimeSettingsRepository`；把 main 的远程命令历史改写为 `setting("")`，并在 IDEA legacy migration 中补 `remoteCommandHistoryJson`。重放后提交为 `1c513770e`。 | 高：整侧覆盖会丢失远程命令历史或把 JSON 设置打回 PropertiesComponent。 |
| `3e778f17e` server and hot update | `05_utilities.md` | 同时保留 main 的远程命令历史约束和 feature 的 RuntimeInfo / hot update 约束。重放后提交为 `cbeea3bc2`。 | 低：仅文档。 |
| `ed8df835e` standalone runtime | `jugglib.py`、`98_code_map.md` | `jugglib.py` 采用 feature 的 Runtime 选择结构，nested CLI 留给最后一提交；知识库保留远端命令说明并采用 feature 的 MCP Runtime 描述。重放后提交为 `aefcd1c66`。 | 中：若此时并入 main 的 nested CLI，会与最后一提交再次冲突。 |
| `cbc3b3eb9` share deploy lifecycle | `98_code_map.md` 日期 | 保留较新核对日期 `2026-08-18`。重放后提交为 `59d3bba69`。 | 低。 |
| `2c590e1e2` connect compile and deploy | `RemoteGradleCompileClient.kt`、`JuggManager.kt`、`04_engineering_ide.md` | 保留 feature 的 `Project` 构造和 main 的 `@Volatile session`、远端命令入口文档。重放后提交为 `1536b7a5f`。 | 中：漏掉 `Project` 构造会破坏 standalone 编译入口。 |
| `35dcafb0f` version 4.0.0 | `build.gradle`、中英文 changelog | 版本保持 feature 目标 `4.0.0`；完整保留 main 的 `3.3.0` 历史条目。重放后提交为 `b11380ab9`。 | 中：覆盖会丢失 3.3.0 远程命令发布记录。 |
| `95f1b386a` runtime diagnostics | 三份工程文档日期 | 保留较新核对日期 `2026-08-18`。重放后提交为 `5017b5f23`。 | 低。 |
| `ac34f7a7d` server failures | `05_utilities.md` 日期 | 保留较新核对日期 `2026-08-18`。重放后提交为 `9d798a220`。 | 低。 |
| `f2a14454c` IDEA CLI runtime discovery | `05_utilities.md` | 采用 feature 的 PlatformApi 宿主 ClassLoader 约束，并保留 main 的远程命令历史说明。重放后提交为 `6bfe135fa`。 | 低：仅文档。 |
| `665202070` standalone remote compile profiles | `RemoteGradleCompileClient.doLogin()` | 合并 main 的 `connectTimeoutMs` / `throwIfCanceled()` 与 feature 的 `disableShellEcho()`、失败时关闭连接。重放后提交为 `d31a9ac61`。 | 高：只取一侧会分别丢失远程命令超时取消或 standalone 认证失败资源回收。 |
| `2b5de5344` nested CLI project routing | `jugglib.py`、CLI 文档、Python 测试 | 按维护者要求完整采用 develop 版本：显式路径先做 Runtime 精确匹配，不再把子目录解析到父 IDEA 项目。重放后提交为 `479c409a7`。 | 中：误用 main 版本会把嵌套 Gradle 工程重新路由到父 IDEA。 |

### 19.3 文本冲突之外的语义修正

首次 `:idea:compileTestKotlin` 失败：

1. `RemoteUserCommandTest` 用无类型 `mock()` 构造 `RemoteGradleCompileClient`，与 rebase 后同时存在的 `Project` / `File` 构造冲突。
2. IDEA `JuggSettingsTest` 仍断言 PropertiesComponent，而 settings 已迁移到共享 JSON。

只调整测试到当前 behavior owner：前者显式使用 `File` 构造；后者把远程命令历史用例迁到 `main` 的 `JuggSettingsTest`，并删除过时的 IDEA PropertiesComponent 用例。没有修改生产行为，独立提交为 `87af026d8`。

### 19.4 验证结果

以下验证通过：

- `./gradlew :idea:compileKotlin :main:compileTestKotlin :cmd_line:compileTestKotlin`
- `python3 -m unittest test_jugglib.py`（66 tests）
- `./gradlew :main:test --tests com.sickworm.intellij.jugg.project.info.ModulePathMergePolicyTest --tests com.sickworm.intellij.jugg.project.BaseCompileContextModuleDependenciesTest --tests com.sickworm.intellij.jugg.project.info.ProjectModelSourceTest --tests com.sickworm.intellij.jugg.project.info.ProjectModelFlowTest --tests com.sickworm.intellij.jugg.deploy.DeployFileManagerDexMergeTest --tests com.sickworm.intellij.jugg.project.runtime.JuggSettingsTest --tests com.sickworm.intellij.jugg.gradle.compile.RemoteUserCommandTest`
- `./gradlew :idea:test --tests com.sickworm.intellij.jugg.compiler.context.CompileContextManagerBuildPathInfoTest --tests com.sickworm.intellij.jugg.compiler.context.CompileEnvironmentSourceTest --tests com.sickworm.intellij.jugg.gradle.compile.RemoteUserCommandTest --tests com.sickworm.intellij.jugg.ide.ui.RemoteCommandDialogTest --tests com.sickworm.intellij.jugg.ide.logic.RemoteCommandProcessHandlerTest --tests com.sickworm.intellij.jugg.project.runtime.IdeaRuntimeSettingsMigrationTest`
- `./gradlew :cmd_line:test --tests com.sickworm.intellij.jugg.cmdline.standalone.StandaloneRemoteCompileFlowTest`
- `git range-diff a5f8e50b73ee7376dc673af5dbfa4d1800e77a13..backup/develop-before-rebase-20260818 e858822358990a5823b6854effcbe1cc19549871..479c409a72fa`
- `git merge-base HEAD main` 返回 `e858822358990a5823b6854effcbe1cc19549871`。
- changelog YAML 解析、版本顺序、冲突标记扫描和 `git diff --check`。

`range-diff` 确认原 61 个提交均有一对一映射；差异来自上述冲突解法和 main 已新增的远程命令行为，没有 feature commit 丢失。构建中的 NDK `riscv64` metadata、IntelliJ `sourceCompatibility`/Kotlin stdlib 和既有 Kotlin warning 均为非阻断提示。未执行 push。
