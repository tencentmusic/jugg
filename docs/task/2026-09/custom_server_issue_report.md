# 后台服务的问题报告直传

## 已确认范围

- 当前选中且可用的后台服务（自动选服或用户设置的 Custom Server，URL 非空）作为手动问题报告目标，拼接 `/report_issue`；跳过诊断候选项确认面板，按原默认选中集合直接打包并上传。
- 后台服务结果的复制文本在 `Jugg report: ...` 下一行增加 `Server Url: <后台根地址>`；失败及重试使用同一目标。
- 没有可用后台地址时继续使用固定公网 HTTPS 地址、候选项确认与本地保存选项；自动失败日志上传保持现状，不加入后台路由。
- 后台直传允许 HTTP 或 HTTPS，公共服务仍仅允许 HTTPS；沿用现有脱敏诊断包，不尝试 server failover，后台失败不得静默回落到公网。

## 文件与职责

- `idea/src/main/java/com/sickworm/intellij/jugg/JuggManager.kt`：选择目标并在有后台服务时直接打包，向结果窗口传递实际选中的后台根地址。
- `idea/src/main/java/com/sickworm/intellij/jugg/ide/ui/ReportIssueResultDialog.kt`：追加复制内容的可选服务器地址；原公共服务文案保持不变。
- `main/src/main/java/com/sickworm/intellij/jugg/server/JuggServerChooser.kt` 与 `JuggServer.kt`：读取当前选中且可用的后台地址，不再限于手动 Custom Server。不改 `JuggSettings` 数据格式或 `ReportIssueDialog` UI。
- `main/src/main/java/com/sickworm/intellij/jugg/diagnostics/IssueReportUploader.kt`：拼接后台目标；只对后台直传放行 HTTP，请求体协议不变。
- 对应 `main/src/test` 与 `idea/src/test`：先记录现有缺失行为，再断言目标选择、直传默认项、公共服务旧路径和复制结果。
- `docs/ai_knowledge/05_utilities.md`、`docs/wiki/zh/guide/report-issue.md`、`docs/wiki/guide/report-issue.md`：同步当前行为及中英文镜像。
- `docs/wiki/zh/guide/jugg-backend/{diagnostics,index,self-hosting}.md` 及对应英文页面：修正手工报告不请求后台服务的旧说法；自动失败上传仍使用公共服务。

## 验证

通过测试价值门禁的 HTTP 目标、请求边界与复制内容使用定向测试；定向 Kotlin 编译、Wiki 镜像检查与生产构建、最终 diff 检查。`report_issue` 的服务器实现不在本仓库，客户端只验证形成的 HTTP/HTTPS 请求及失败收口。
