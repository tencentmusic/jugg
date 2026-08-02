# Report Jugg Issue 交互优化方案

## 背景

问题诊断在展示文件选择窗口前会抓取设备 logcat 并完成日志脱敏。该过程可能耗时较长，但当前没有可见的 loading 状态，用户无法判断操作是否仍在进行。

文件选择窗口还存在提示不清晰、上传按钮含义不准确，以及文件大小直接显示 bytes、可读性较差的问题。

## 已审批范围

- 准备诊断数据时展示模态 loading dialog，复用改版前 `ReportProgressDialog` 的状态文案加不定进度条样式。
- loading dialog 显示 `Preparing diagnostics...`，准备完成后自动关闭，再展示诊断文件选择窗口。
- 准备过程异常时关闭 loading dialog，保留现有后台任务的失败日志行为。
- 上传及重试上传期间展示同款 loading dialog，状态显示 `Uploading logs...`，上传结束后关闭并展示结果窗口。
- 文件选择提示改为说明运行环境日志已脱敏，并将用于问题分析。
- 上传按钮由 `Update logs` 改为 `Upload logs`。
- 文件大小最小使用 KB、最大使用 MB，不再显示 bytes。
- Report ID 恢复为 8 位小写十六进制，并统一用于诊断目录、ZIP 文件名、manifest 和上传结果。
- 同步 `docs/ai_knowledge/04_engineering_ide.md` 的当前行为。

## 实现边界

- 不修改诊断文件收集、脱敏、打包和上传协议。
- 不恢复旧版上传结果、服务器地址或复制 Issue ID 行为。
- 不为单一 loading 状态引入新的通用任务抽象。

## 验证策略

8 位小写十六进制 Report ID 是稳定且用户可观察的外部命名契约，在现有 `IssueReportBundleBuilderTest` 中增加 L1 断言。精确断言 Swing 私有组件或普通文案会绑定实现细节，因此 UI 调整不新增自动化测试。

替代验证包括：

- `./gradlew :idea:compileKotlin`
- `./gradlew :main:test --tests com.sickworm.intellij.jugg.diagnostics.IssueReportBundleBuilderTest`
- `git diff --check`
- 静态核对准备、上传及重试上传的成功和异常路径均关闭 loading dialog
- 静态核对 bytes 到 KB/MB 的边界
