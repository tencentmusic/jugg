# 内网日志上传结果支持本地查看

## 需求与已批准范围

用户已批准：内网日志报告继续直接上传，在上传成功或失败的结果弹窗中增加 `Save to local`。点击后通过系统文件管理器打开压缩包所在目录并选中本次 ZIP，弹窗保留，方便继续复制结果或重试。

## 现状与最小实现

- `JuggManager.reportIssue()` 在存在可用后台时跳过文件与地址预览，生成诊断包后调用 `uploadIssueReport()`。
- 当前 `ReportIssueResultDialog.createActions()` 仅提供复制结果，以及失败时的重试；没有本地查看入口。这是本次缺失行为的代码证据。
- 外网仅本地保存分支已使用 `RevealFileAction.openFile(bundle.file)` 打开并选中 ZIP。本次复用该方式，使用已生成的同一份诊断包，不重新打包。
- 结果弹窗增加可选 ZIP 参数，仅后台上传传入该参数。成功和失败都显示 `Save to local`，点击后不关闭弹窗；失败重试继续使用同一诊断包与目标。外网原有上传和仅本地保存行为保持不变。

## 文件清单

- `idea/src/main/java/com/sickworm/intellij/jugg/ide/ui/ReportIssueResultDialog.kt`：接收可选 ZIP 并增加本地查看动作。
- `idea/src/main/java/com/sickworm/intellij/jugg/JuggManager.kt`：仅在后台上传时传入本次 ZIP。
- `docs/ai_knowledge/05_utilities.md`、`04_engineering_ide.md`：同步后台直传的结果操作与公共服务分支。
- `docs/wiki/zh/guide/report-issue.md`、`docs/wiki/guide/report-issue.md`：补充后台上传后本地查看的入口，区分上传后查看和仅本地保存。
- 本文件：保存已批准设计、实现与验证记录。

## 验证策略

- 本次只增加普通 UI 动作并复用已有打开文件行为，按 `06_testing.md` 的价值门禁，不通过反射、源码字符串或测试专用 seam 新增实现细节测试。
- 执行 `:idea:compileKotlin`，并定向运行已有 `ReportIssueResultDialogTest`，保护结果复制的外网及后台成功/失败行为。
- 对照代码检查后台成功、失败、重试和外网原有分支；文件管理器实际打开行为如未在新插件中手工验证，明确记录限制。
- 执行 Wiki 镜像与链接校验、production build，抽查中英文 HTML 含新增按钮说明，并运行 `git diff --check`。
- 完成后委派只读子 Agent 审查已批准范围与适用 `AGENTS.md`，处理有效问题后仅提交本次改动。

## 实施结果

- 已按清单完成两处生产代码接线，后台成功/失败均可查看同一 ZIP；结果弹窗保持打开，重试仍绑定原诊断包与原目标。未改变公共服务上传或仅本地保存分支。
- `./gradlew :idea:compileKotlin :idea:test --tests 'com.sickworm.intellij.jugg.ide.ui.ReportIssueResultDialogTest'` 通过；已有复制结果测试 1 项通过，覆盖公共服务及后台成功/失败的复制文本。
- Android Studio MCP 对两处生产文件的错误诊断均为空。
- Wiki 镜像/链接校验及 production `npm run build` 通过，中英文 `guide/report-issue.html` 均包含 `Save to local`。
- 本次文件的 `git diff --check` 通过。全工作区检查发现用户原有 `MainActivity.kt` 末尾空行提示，未修改该文件。
- 未在运行中的新插件里点击按钮或向真实后台上传；文件管理器的实际打开行为仍需手工验收。本次以编译、IDE 诊断、既有测试和分支代码审查作为替代验证，不宣称已完成 UI 实测。
- 已委派只读实现审查，未发现 actionable findings；审查确认后台成功/失败、本地查看、重试、复制与公共服务分支符合已批准范围。
