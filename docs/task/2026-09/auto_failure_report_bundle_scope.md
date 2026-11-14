# 自动失败报告诊断范围扩展

## 目标

自动失败日志上传复用手动报告的完整诊断包准备流程，并固定包含最近 2 份真实 Jugg 日志、环境信息、工程摘要、脱敏工程快照、存在时的 hook 调试日志和 manifest。

## 范围

- `IssueReportBundleBuilder.prepare` 接收日志数量上限，由调用方明确传入。
- 手动报告保持最多 10 份日志及现有内容和交互。
- 自动报告传入日志上限 2，并传入空 logcat，禁止读取设备日志。
- 自动报告继续只上传到 `availableServerUrl`，无可用后台时跳过。
- 自动上传触发条件、排除正则、multipart 字段和 Best-effort 语义保持不变。

## 验证

- 扩展现有 `IssueReportBundleBuilderTest`，验证日志数量上限以及自动报告包含完整默认内容但不包含 logcat。
- 执行相关定向测试与 `:idea:compileKotlin`。
- 同步知识库和中英文 Wiki。
