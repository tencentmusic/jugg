# Git 变更补检非阻塞化

## 背景

`GitChangesCompileChecker` 会在增量编译开始前异步查询 Git，补偿 Android Studio 文件事件可能遗漏的磁盘修改。当前实现会在编译结束后最多等待 10 秒，超时后由 `withTimeout` 抛出异常并中断当前 Run，与补检的辅助能力定位不符。

## 已批准行为

- Git 补检继续在增量编译期间异步执行。
- 增量编译结束后不再等待补检任务。
- 补检已经完成时，消费结果；发现新的待编译文件时保持现有二次增量编译行为。
- 补检尚未完成时，记录 debug 日志并继续当前编译、部署流程。
- 迟到的补检结果不得被后续 Run 误读。
- 后台 Git 查询可以自然完成，本次不强制中止阻塞中的 JGit 调用。

## 实施范围

- `idea/src/main/java/com/sickworm/intellij/jugg/compiler/GitChangesCompileChecker.kt`
  - 移除超时等待。
  - 提供仅消费已完成任务结果的非阻塞入口。
  - 将异步结果绑定到单次任务，避免迟到结果污染后续 Run。
- `idea/src/main/java/com/sickworm/intellij/jugg/compiler/JuggCompileHelper.kt`
  - 机会式消费 Git 补检结果。
  - 未完成时不告警、不失败、不回退。
- `idea/src/test/java/com/sickworm/intellij/jugg/compiler/GitChangesCompileCheckerTest.kt`
  - 保护未完成任务立即忽略、已完成结果正常消费和迟到结果隔离。
- `docs/ai_knowledge/02_compile_core.md`
  - 同步编译成功后 Git 补检的非阻塞语义。
- `docs/ai_knowledge/09_plugin_runtime_debug.md`
  - 同步运行时日志和排查口径。

## 明确排除

- 不增加 Jugg Running Pannel UI。
- 不增加 `JuggSettings` 开关。
- 不配置或保留等待超时时间。
- 不优化 JGit 查询性能。
- 不强制终止已经进入阻塞调用的 JGit 任务。

## 验证策略

- 失败证据：未完成异步任务调用旧等待入口时触发 `TimeoutCancellationException`。
- L2 owner：`GitChangesCompileCheckerTest`。
- 定向测试：`./gradlew :idea:test --tests "*GitChangesCompileCheckerTest*"`。
- 编译验证：`./gradlew :idea:compileKotlin`。
- 静态验证：`git diff --check`，并检查本次日志格式。
