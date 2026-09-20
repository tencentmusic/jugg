# 编译部署失败日志自动上传方案

## 背景

Jugg 已支持用户手工确认后上传诊断包，但编译和部署失败仍依赖用户主动反馈。需要增加后台下发开关，在满足条件的最终失败发生时自动上传最近两份 Jugg 日志，并允许后台通过排除正则过滤无需上报的已知错误。

## 配置

在 `ProjectCustomConfig` 中增加：

- `autoUploadFailureLogs`：只有明确为 `true` 时启用自动上传。
- `autoUploadFailureLogsExcludeRegex`：可选排除正则，使用包含匹配；命中当前运行的最终错误摘要时不上传。

字段缺失、总开关为 `false` 时保持原行为。排除正则为空时不过滤；正则非法时本次不上传并记录 debug 日志，避免错误配置扩大上传范围。每次 Run 启动前刷新配置，并在本轮内使用固定快照。

## 触发范围

以下最终结果触发自动上传：

- 增量或 Gradle 编译最终失败。
- 已实际进入设备部署且最终部署失败。
- 编译部署顶层出现未捕获异常。

以下情况不触发：

- 用户主动取消。
- 跳过部署。
- 没有可用设备，部署未实际开始。
- 增量部署失败后回退 Gradle，最终运行成功。
- 排除正则命中最终错误摘要。

一次 Run 最多上传一次。多设备失败、内部重试和 Gradle fallback 不单独上传中间失败。

## 错误摘要

正则只匹配当前运行的最终错误摘要，不扫描日志全文：

- 编译失败：`failedReason` 与 Gradle `errorLog`。
- 部署失败：所有失败设备最终原因的汇总。
- 未捕获异常：异常类名与 message。

匹配使用 Kotlin `Regex.containsMatchIn()`。

## 上传内容

从 `JuggPathManager.logDir` 指向的全局工程日志目录中，按修改时间选择最近两份真实 `compile_*.log`：

- 排除 `compile_latest.log`、`compile_latest-1.log` 快捷入口。
- 排除锁文件、目录和其他文件。
- 不足两份时上传实际存在的日志。

自动诊断包只包含脱敏日志和 `diagnostics/manifest.json`，不包含 project info、环境摘要、logcat 或 hook 日志。上传继续使用固定的 `IssueReportUploader.JUGG_REPORT_URL`，不跟随 Custom Server，也不做 failover。上传异步 Best-effort 执行，失败不改变编译部署结果、不弹窗且不重试。

## 代码范围

- `main/.../server/protocols/Protocols.kt`：增加后台配置字段。
- `main/.../diagnostics/IssueReportBundleBuilder.kt`：增加只构建日志诊断包的入口。
- `main/.../server/JuggServer.kt`：选择最近两份真实日志并异步上传。
- `idea/.../ide/logic/JuggRunningTask.kt`：在最终失败边界构造错误摘要、执行排除匹配并触发一次上传。
- `idea/.../JuggManager.kt`：运行前刷新配置并传入本轮配置快照。
- 对应 `main` / `idea` 定向测试、知识库及中英文 Wiki。

## 验证

- 自动诊断包仅包含最多两份脱敏日志和 manifest。
- 日志选择排除快捷入口与锁文件，并按修改时间取最近两份。
- 总开关、空正则、命中、未命中和非法正则行为符合契约。
- 编译失败、部署失败和顶层异常触发；取消、跳过部署、无设备和 fallback 最终成功不触发。
- 运行定向测试以及 `./gradlew :idea:compileKotlin`。
