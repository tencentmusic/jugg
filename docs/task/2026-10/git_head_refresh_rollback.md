# Git HEAD 刷新时清理已恢复内容的待编译文件

## 问题与目标

HEAD 变化后的 Git 补检会追加漏掉的文件并清理物理缺失文件，但已恢复成有效历史基线内容的待编译记录仍会保留。普通 Run 的回滚检查受校验开关、文件数量及类型门槛限制，不能保证单文件回滚被清理。

统一 Run 前同步检查与 `onSourceFileChanged()` 防抖任务的入口：HEAD 判断 → 补漏 → 内容回滚检查 → 推进 HEAD 基线。HEAD 未变时保持快速返回；刷新或回滚检查失败时保留旧 HEAD，Run 沿用既有 Gradle 回退。

## 实现范围

- 将 `JuggCompilerHelper.checkFilesRollback()` 的实际执行部分抽取至已有的 `GitFileChangesDetector`，复用现有部署历史与文件管理依赖，不新增接口或对象。
- 普通 Run 的检查保留现有触发门槛，并调用抽出的执行方法。
- 两个 HEAD 检查调用点统一改为 `refreshFileChangesIfHeadChanged()`；HEAD 变化时直接检查 `uncompiledFiles`，不受普通 Run 的校验开关、数量和类型门槛限制。
- 比较复用 `IDeployHistoryManager.filterUnchangedFiles()`：优先比较成功部署记录的 CRC，没有记录才比较全量构建 commit。已部署 B 后源码恢复为 A 时，保留需要重新编译部署 A 的变更。
- 保持已编译未部署文件与 staging 的既有处理方式，不扩展为全状态回滚，也不改写工作区文件。
- 沿用既有 Git 比较契约：没有部署 CRC 时，比较方法也会考虑索引相对基线的变化。HEAD/索引保留 B、仅工作区恢复 A 的场景仍可能保留待编译记录；本次不修改 Git 比较算法。

## 验证设计

- 失败证据：先在原实现上验证单文件恢复后仍留在待编译集合，以及回滚检查失败仍推进 HEAD。
- 测试价值：保护待编译集合、部署历史比较基线和失败后的 HEAD 重试语义，属于稳定可观察行为。
- L2 owner：`GitFileChangesDetectorTest`，使用真实 Git、历史比较与文件状态管理；只替换外部任务执行和历史接口。覆盖补漏与回滚同时发生、HEAD 未变、已部署内容不同、失败重试和后台防抖入口。
- L2 owner：`JuggCompileHelperTest`，复用原有预处理、回退与无文件变化回归。
- L3 owner：`TopLevelFlowWithGitTest`，覆盖 Run 前恢复文件清理与既有漏事件补检。
- 定向测试后执行 `:idea:compileKotlin`、`git diff --check`，检查日志格式。

## 文档影响

同步 `02_compile_core.md` 的统一入口、回滚范围与失败语义。现有 Wiki 未描述 HEAD 回滚门槛，也无需改变操作步骤、设置或支持范围，因此不修改 Wiki。

## 执行结果

- 原实现的两个定向失败断言已确认：恢复后的 `Base.kt` 仍与漏检的 `Missed.kt` 一起留在待编译集合；预期保留 HEAD 的回滚检查失败场景实际返回 `REFRESHED`。
- 最终使用 `ANDROID_HOME=/Users/wormchen/Library/Android/sdk`、本机 Corretto JDK 17 执行下列定向命令，32 项测试全部通过，无跳过；`:idea:compileKotlin` 同次通过：

  ```bash
  ./gradlew :idea:test \
    --tests 'com.sickworm.intellij.jugg.project.GitFileChangesDetectorTest' \
    --tests 'com.sickworm.intellij.jugg.compiler.JuggCompileHelperTest.preprocessIncrementalCompile*' \
    --tests 'com.sickworm.intellij.jugg.manager.TopLevelFlowWithGitTest.runAfterHeadChangeDiscardsRestoredPendingSourceWithoutCompilingIt' \
    :idea:compileKotlin --console=plain
  ```

- L2 `GitFileChangesDetectorTest` 共 9 项通过，包含 5 项新增行为回归；`JuggCompileHelperTest` 的 22 项预处理回归通过。新 L3 在 `emulator-5554` 上通过，确认恢复内容的新提交不会产生增量编译/部署产物；既有 `runAfterCommittedSourceChangeWithoutIdeEvent_compilesPulledChange` 也在前一轮通过。
- 首次完整执行两个 L2 owner 时，53 项中 52 项通过。存量 `compile_incrementalFailure_nonRpcModePrintsDirectRunFallbackHint` 断言旧文案 `Run again directly will fall back to gradle compile.`，修改前 HEAD 的生产代码已经使用 `Running again without changing any files will fall back to a Gradle build.`；独立执行同样失败，本次未修改无关文案或断言。
- L3 环境修正：补齐 `ANDROID_HOME`；JDK 21 下 demo 的 AGP 7.3.1/D8 在 `dexBuilderDebug` 抛出 NPE，切换 JDK 17 后构建和部署完成。未修改工程工具链配置。
- 实际实现与方案一致；L3 使用源文件内容恢复的新提交验证 HEAD 场景，前述索引与工作区不一致的既有比较边界继续保留。
- `git diff --check`、调用点检索和本次差异中的日志格式检查通过。
