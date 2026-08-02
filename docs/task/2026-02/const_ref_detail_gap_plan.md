# 常量重编译细节对齐差距计划

更新时间：2026-02-21

## 1. 目标

对照 `docs/task/2026-02/const_ref_plan.md` 与当前代码实现，识别与“扫描/检测/缓存/测试”细节要求的不一致点，并给出后续可执行迭代计划。

说明：若文档与代码冲突，以代码为准。

## 2. 对齐结论总览（以代码为准）

| 细节 | 当前实现 | 结论 | 代码依据 |
|---|---|---|---|
| 初始化编译触发扫描 | `initCompile()` -> `reInitOnCompileContextUpdate()` -> `updateModuleInfos()` -> `initializeFullScan()` | 一致 | `idea/src/main/java/com/sickworm/intellij/jugg/JuggManager.kt:536`, `idea/src/main/java/com/sickworm/intellij/jugg/JuggManager.kt:527`, `main/src/main/java/com/sickworm/intellij/jugg/deploy/DeployFileManager.kt:351` |
| 文件变化触发扫描 | `processFileChanged()` -> `addChangedFile()` -> `onFileSaved()` | 一致 | `idea/src/main/java/com/sickworm/intellij/jugg/JuggManager.kt:305`, `main/src/main/java/com/sickworm/intellij/jugg/deploy/DeployFileManager.kt:106`, `main/src/main/java/com/sickworm/intellij/jugg/deploy/DeployFileManager.kt:122` |
| 编译前补齐扫描 | `compile()` 首轮调用 `awaitConstRefAnalysis(changedSourcePaths)` | 基本一致 | `main/src/main/java/com/sickworm/intellij/jugg/compiler/IncrementalCompilerHelper.kt:50` |
| 触发场景“有且仅有三类” | 额外存在“编译取消回滚 -> addChangedFile -> onFileSaved” | 不一致 | `main/src/main/java/com/sickworm/intellij/jugg/compiler/IncrementalCompilerHelper.kt:174` |
| 文件变更不立即检测，改为“下一个文件变更时检测上一个文件” | 当前是 250ms debounce，静默后会自动检测 | 不一致 | `main/src/main/java/com/sickworm/intellij/jugg/compiler/constref/ConstRefScheduler.kt:21`, `main/src/main/java/com/sickworm/intellij/jugg/compiler/constref/ConstRefScheduler.kt:43`, `main/src/main/java/com/sickworm/intellij/jugg/compiler/constref/ConstRefScheduler.kt:201` |
| 各场景相互独立，同场景同一时间仅一个 | 通过全局 `analysisMutex` 串行化，场景间并不独立；且 `runningJob` 未实际承载运行态 | 不一致 | `main/src/main/java/com/sickworm/intellij/jugg/compiler/constref/ConstRefScheduler.kt:22`, `main/src/main/java/com/sickworm/intellij/jugg/compiler/constref/ConstRefScheduler.kt:238`, `main/src/main/java/com/sickworm/intellij/jugg/compiler/constref/ConstRefScheduler.kt:33` |
| 异步任务统一由 `TaskRunnerManager#runBackgroundSafe` 启动 | 常量扫描使用 `coroutineScope.launch`，未走 `TaskRunnerManager` | 不一致 | `main/src/main/java/com/sickworm/intellij/jugg/deploy/DeployFileManager.kt:116`, `main/src/main/java/com/sickworm/intellij/jugg/compiler/constref/ConstRefScheduler.kt:139`, `idea/src/main/java/com/sickworm/intellij/jugg/project/TaskRunnerManager.kt:29` |
| 检测时机集成在 `DeployDataGenerator#buildDeployData` 且受 `isNeedCheckRecompile` 控制 | 常量引用检测在 `DeployFileManager#getRecompileFiles()`，不在 `buildDeployData()` | 不一致 | `main/src/main/java/com/sickworm/intellij/jugg/deploy/DeployFileManager.kt:380`, `main/src/main/java/com/sickworm/intellij/jugg/deploy/data/DeployDataGenerator.kt:31` |
| 检测阶段发现未完成时同步补齐 | 无“全量完成检查 + 同步补齐”逻辑（仅首轮 compile 前按 changed files await） | 不一致 | `main/src/main/java/com/sickworm/intellij/jugg/compiler/IncrementalCompilerHelper.kt:54`, `main/src/main/java/com/sickworm/intellij/jugg/deploy/data/DeployDataGenerator.kt:138` |
| 多项目同 git/worktree：checksum 相同、last_modified 不同可命中且避免重复 hash 计算 | 当前 mtime 变化后仍会读取文件计算 CRC32；且数据库位于项目内，不跨项目共享 | 不一致 | `main/src/main/java/com/sickworm/intellij/jugg/compiler/constref/ConstRefScheduler.kt:268`, `main/src/main/java/com/sickworm/intellij/jugg/compiler/constref/ConstRefScheduler.kt:334`, `main/src/main/java/com/sickworm/intellij/jugg/project/JuggPathManager.kt:15` |
| `DeployDataGeneratorTest` 需要相关用例 | 现有测试未覆盖常量引用重编译链路 | 不一致 | `main/src/test/java/com/sickworm/intellij/jugg/deploy/data/DeployDataGeneratorTest.kt:22` |

## 3. 迭代计划（仅针对不一致项）

## P0：扫描任务编排对齐

### P0-1 改造为“下一个文件变更触发上一个文件检测”

- 目标：替换纯时间防抖逻辑，满足“非立即检测”与“下一次变更再触发”。
- 拟改文件：
`main/src/main/java/com/sickworm/intellij/jugg/compiler/constref/ConstRefScheduler.kt`
- 实施要点：
1. 引入 `currentEditingFile` 与 `pendingAnalyzeFiles` 双状态。
2. `onFileSaved(fileX)` 时，仅把“前一个编辑文件”放入可分析队列；当前文件仅更新为 `currentEditingFile`。
3. `awaitAnalysis()` 触发时强制 flush 当前编辑文件，避免编译前遗漏。
4. 保持方法短小，拆分为状态迁移函数与执行函数，避免长方法。
- 验收：
1. 连续修改同一文件，不触发立即分析。
2. 修改 A 后修改 B，A 被分析，B 进入待分析态。
3. 编译前 `awaitAnalysis()` 可补齐 B。

### P0-2 明确三场景与单实例约束

- 目标：保证“同场景同一时刻仅一个任务”，并移除场景外触发入口。
- 拟改文件：
`main/src/main/java/com/sickworm/intellij/jugg/compiler/IncrementalCompilerHelper.kt`
`main/src/main/java/com/sickworm/intellij/jugg/compiler/constref/ConstRefScheduler.kt`
- 实施要点：
1. 取消“编译取消回滚”路径对 `addChangedFile()` 的常量扫描副作用（保留文件状态回滚，但不二次触发扫描）。
2. 为 `fullScan/fileChange/preCompile` 建立独立任务状态，避免共用全局模糊状态。
3. `runningJob` 改为真实运行态或删除，避免伪状态变量。
- 验收：
1. 运行时只存在三个受控入口。
2. 每个入口在任意时刻最多一个活跃任务。

### P0-3 异步入口统一到 `TaskRunnerManager`

- 目标：统一异步任务生命周期、日志和异常收敛。
- 拟改文件：
`main/src/main/java/com/sickworm/intellij/jugg/deploy/DeployFileManager.kt`
`main/src/main/java/com/sickworm/intellij/jugg/compiler/constref/ConstRefScheduler.kt`
`idea/src/main/java/com/sickworm/intellij/jugg/JuggManager.kt`
`idea/src/main/java/com/sickworm/intellij/jugg/project/TaskRunnerManager.kt`
- 实施要点：
1. 在 `main` 层定义最小后台执行接口（避免直接依赖 `idea` 层类，保持低耦合）。
2. `JuggManager` 注入基于 `TaskRunnerManager.runBackgroundSafe()` 的实现到 `DeployFileManager`/`ConstRefScheduler`。
3. 保持扫描模块仅依赖抽象接口，不直接依赖 UI/IDE 组件。
- 验收：
1. 扫描相关异步任务均通过统一 runner 启动。
2. 扫描失败日志统一落在背景任务日志体系中。

## P1：检测模块集成对齐

### P1-1 将常量检测入口迁移到 `DeployDataGenerator#buildDeployData`

- 目标：满足“检测时机集成在 `buildDeployData`，并由 `isNeedCheckRecompile` 控制”。
- 拟改文件：
`main/src/main/java/com/sickworm/intellij/jugg/deploy/data/DeployDataGenerator.kt`
`main/src/main/java/com/sickworm/intellij/jugg/deploy/DeployFileManager.kt`
- 实施要点：
1. 在 `DeployDataGenerator` 中引入 `ConstRefEffectProvider`（接口注入）。
2. 仅当 `isNeedCheckRecompile=true` 时调用 provider 获取常量影响文件。
3. `DeployFileManager#getRecompileFiles()` 移除直接访问 `constRefScheduler.getEffectedFiles()`。
- 验收：
1. `isNeedCheckRecompile=false` 不触发常量检测。
2. `isNeedCheckRecompile=true` 触发且结果并入 recompile 集合。

### P1-2 “未完成检测时同步补齐”落地

- 目标：检测阶段具备同步兜底能力，避免漏检。
- 拟改文件：
`main/src/main/java/com/sickworm/intellij/jugg/compiler/constref/ConstRefScheduler.kt`
`main/src/main/java/com/sickworm/intellij/jugg/deploy/data/DeployDataGenerator.kt`
- 实施要点：
1. 新增 `ensureReadyForRecompile(changedFiles)`：检查扫描完成度，不足则同步补齐。
2. `buildDeployData()` 中 provider 调用前执行 readiness 检查。
3. 超时与异常统一上报，失败时降级为“只使用已完成结果 + 明确日志”。
- 验收：
1. 全量扫描未就绪时仍能在构建部署数据阶段得到稳定结果。
2. 不出现“变更常量但遗漏受影响文件”的回归。

## P1：缓存模块对齐

### P1-3 支持跨项目/worktree 的内容级缓存命中

- 目标：同 git 仓库/worktree 中，文件内容未变但 mtime 不同，可直接命中，不重复全文件 hash。
- 拟改文件：
`main/src/main/java/com/sickworm/intellij/jugg/compiler/constref/ConstRefCacheDatabase.kt`
`main/src/main/java/com/sickworm/intellij/jugg/compiler/constref/ConstRefScheduler.kt`
新增：`main/src/main/java/com/sickworm/intellij/jugg/compiler/constref/RepoSharedFingerprintStore.kt`
- 实施要点：
1. 增加仓库级共享缓存（按 `gitRoot + relativePath + blobId/contentKey`）。
2. mtime 变化时优先走共享指纹命中路径；仅在 miss 时回退到 CRC32 计算。
3. 共享缓存与项目缓存分层，避免单库污染全局。
- 验收：
1. 同仓库多项目切换下，mtime 漂移不导致重复 hash 计算。
2. 缓存命中率与分析耗时在日志可观测。

## P1：测试补齐

### P1-4 为 `DeployDataGeneratorTest` 增加常量重编译相关用例

- 目标：补齐你要求的测试入口，覆盖新集成点与兜底逻辑。
- 拟改文件：
`main/src/test/java/com/sickworm/intellij/jugg/deploy/data/DeployDataGeneratorTest.kt`
可新增：`main/src/test/java/com/sickworm/intellij/jugg/deploy/data/FakeConstRefEffectProvider.kt`
- 实施要点：
1. 用 fake provider 验证 `isNeedCheckRecompile` 开关行为。
2. 增加“未完成检测 -> 同步补齐 -> 返回影响文件”的行为测试。
3. 增加“常量变化影响文件并入 recompile 结果”的集成断言。
- 验收：
1. `DeployDataGeneratorTest` 含常量影响链路用例。
2. 关键分支（开关/就绪/未就绪）均有断言覆盖。

## 4. 代码结构约束（本轮迭代遵循）

1. 单类单责，场景状态机、缓存访问、任务调度分离。
2. 控制流程不超过 5 层嵌套，超出即提炼私有函数。
3. 单方法尽量不超过 100 行，复杂路径拆分为“小函数 + 编排函数”。

## 5. 建议落地顺序

1. P0-1 -> P0-2 -> P0-3（先把触发和调度统一）。
2. P1-1 -> P1-2（把检测入口移到 `buildDeployData` 并补同步兜底）。
3. P1-3（缓存跨项目优化）。
4. P1-4（测试补齐收口）。
