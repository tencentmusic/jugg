# Standalone CLI 日志可观测性实施记录

## 目标

为 standalone CLI 提供独立项目日志目录，并让问题报告、MCP 日志路径和跨 Runtime 项目锁竞争具备可诊断性。

## 已批准范围

- standalone 日志写入 `build/jugg/log/standlone_cli`；IDEA 和既有日志继续写入 `build/jugg/log`。
- Issue Report 分别从两个目录选取最近 10 份真实日志，合并后按修改时间仅上报最近 10 份；归档保留 standalone 子路径并继续脱敏。
- standalone MCP 返回自己的 `compile_latest.log` 相对路径。
- 项目锁仅在首次非阻塞获取失败时记录竞争，获得锁后记录等待耗时和 owner 快照；无竞争路径不记录。
- 不为每个 standalone MCP job 增加生命周期日志，不新增 standalone report 命令。

## 验证策略

- `IssueReportBundleBuilderTest` 保护两目录合并、总量限制、归档路径和脱敏行为。
- 项目锁使用两个真实 JVM worker 产生 `idea → standalone → idea` 竞争；验证等待边界和竞争日志，不增加无竞争 happy case。
- 执行定向 Gradle 测试和 `:idea:compileKotlin`。
