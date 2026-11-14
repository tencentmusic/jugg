# 自动失败日志上传字段适配

## 目标与边界

现有 `/report_issue` multipart 请求只有 `file`。自动上传增加 `is_auto_upload=true`、非空的 `failed_reason`，并附带 `project_name`、`username`、`plugin_version`、`report_id` 和有详情时的 `error_detail`。手动反馈保持仅上传文件；固定 HTTPS 目标、诊断包内容、失败筛选、异步 Best-effort 与无重试保持不变。

## 改动清单

- `idea/.../JuggRunningTask.kt`：在最终失败边界从 `RunResult` 提取摘要与详细错误，继续用原错误摘要执行排除规则。
- `main/.../server/JuggServer.kt`：组合已有工程、用户和插件版本信息，沿用诊断包脱敏规则处理错误文本，再传给上传器。
- `main/.../diagnostics/IssueReportUploader.kt`：在现有文件 multipart 请求上按自动上传参数附加新表单字段，手动调用不传新字段。
- `main/.../diagnostics/IssueReportBundleBuilder.kt`：复用现有文本脱敏逻辑处理明文表单错误信息，不扩大诊断包。
- `main/.../diagnostics/IssueReportUploaderTest.kt`、`idea/.../JuggRunningTaskTest.kt`：先建立协议字段缺失的失败证据，再验证自动与手动请求边界、最终失败摘要的来源。
- `docs/ai_knowledge/05_utilities.md` 与 `docs/wiki/zh/guide/report-issue.md`、`docs/wiki/guide/report-issue.md`：同步协议字段、用户可见的自动/手动区别。

## 验证

定向测试上传器的 HTTP multipart 行为及 Run 最终失败条件，运行 `:idea:compileKotlin`；Wiki 校验、构建与中英文镜像检查。字段里的错误文本遵循诊断包相同的路径、已知敏感值与凭据脱敏。服务端实际处理不在本仓库，不能由客户端测试代替。
