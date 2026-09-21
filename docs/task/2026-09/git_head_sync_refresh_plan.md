# Run 前同步检查 Git HEAD 方案

## 背景

用户执行 `git pull` 后立即点击 Run 时，IDE 文件事件可能尚未完成回调。现有编译前 Git 补检异步执行；无文件变化的编译路径结束很快，可能在补检完成前返回 `No file changes`。

实测同步读取所有 Git root 的 HEAD 耗时约 1ms，可以在每次 Run 中作为固定门禁。

## 目标行为

1. 每次 Run 在增量编译预处理阶段同步检查所有 Git root 的 HEAD。
2. HEAD 未变化时立即继续原流程，不等待完整 Git 刷新。
3. HEAD 变化时同步刷新 Git 变化文件，等待 `FileChangesHandler` 和 `DeployFileManager` 更新完成后再继续本轮编译。
4. HEAD 检查结果和耗时使用 debug 日志；检测到 HEAD 变化时使用 info 日志。
5. 已有异步 Git 补检继续保留，用于工作区变化或 IDE 漏事件兜底。
6. HEAD 已变化但文件刷新失败时回退 Gradle，禁止误报无文件变化。

## 流程

```text
JuggCompilerHelper.preprocessIncrementalCompile()
  -> 同步检查所有 Git root HEAD
     -> 未变化：记录 debug 结果和耗时
     -> 有变化：记录 info，阻塞刷新 Git 变化文件
        -> 成功：提交新的 HEAD 基线，继续本轮预处理
        -> 失败：保留旧 HEAD 基线，回退 Gradle
  -> force Gradle 检查
  -> 启动现有异步 Git 漏文件补检
  -> 使用刷新后的 DeployFileManager 状态执行 external/build/dependency/no-file 判断
  -> 增量编译或按原规则回退
```

## 实现范围

### `GitFileChangesDetector`

- 增加同步 HEAD 检查并按需刷新入口。
- `gitHeads` 表示最后一次成功完成 Git 文件刷新的 HEAD，不再在仅观察到变化时提前更新。
- 事件 debounce 与 Run 前检查复用同一刷新入口。
- 刷新成功后提交 HEAD；失败时保留旧 HEAD，允许下一次重试。
- HEAD 未变化记录 debug；HEAD 变化记录 info；两者都记录检查耗时。

### `JuggCompileHelper`

- 每次 Run 都同步调用 HEAD 检查，包括已经要求强制 Gradle 的运行。
- HEAD 变化时等待刷新完成，再读取和判断变化文件。
- 刷新失败返回可回退的增量失败结果。
- 保留现有异步 `GitChangesCompileChecker`。

## 并发边界

- 不持有 detector 状态锁调用 `FileChangesListener`，避免与 `JuggManager.fileChangeLock` 形成锁反转。
- Run 前同步刷新会取消尚未执行的 debounce job。
- 已经开始的后台刷新与 Run 刷新允许短暂重叠；文件落库仍由现有 `fileChangeLock` 串行化，变更文件状态按路径和快照去重。

## 失败与降级

- 无 Git 仓库：视为 HEAD 未变化，继续原流程。
- HEAD 查询失败：记录失败结果，保留原流程；只有已确认 HEAD 变化但文件刷新失败时才回退 Gradle。
- Git 历史或 diff 不可用：刷新失败，保留旧 HEAD 并回退 Gradle。
- HEAD 变化但只有非编译相关文件：刷新成功后仍可正常返回无文件变化。

## 验证

- L2 `GitFileChangesDetectorTest`：HEAD 未变化、变化刷新、成功提交基线、失败重试和事件 debounce 语义。
- L2 `JuggCompileHelperTest`：每次 Run 同步检查、刷新失败回退、刷新后使用最新文件状态。
- L3 `TopLevelFlowWithGitTest`：commit 源码变化但不发送 IDE 文件事件，立即 Run 仍能识别并编译。
- 定向执行相关测试并运行 `./gradlew :idea:compileKotlin`。

## 不在范围内

- 不替换 JGit，不启动外部 Git 进程。
- 不修改普通 IDE 文件监听实现。
- 不增加用户设置、超时配置或新的公共接口。
- 不重构 `DeployHistoryDb` 和现有 Git diff/CRC 逻辑。
