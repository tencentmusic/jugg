# Report Jugg Log 对话框调整方案

## 1. 目标行为

- 问题报告固定上传到 `https://jugg.sickworm.com/report_issue`，不展示或允许编辑上传地址。
- 所有诊断候选项默认勾选。
- `diagnostics/logs/*` 下的 Jugg 日志显示在列表顶部，保持勾选且不可取消。
- 环境、项目摘要、logcat 和 hook 日志仍允许用户取消选择。
- 保留“仅保存到本地，不上传”能力。

## 2. 实现范围

- `idea/src/main/java/com/sickworm/intellij/jugg/ide/ui/ReportIssueDialog.kt`
  - 移除上传地址输入和校验。
  - 将 Jugg 日志排序到候选列表顶部并禁用对应复选框。
- `idea/src/main/java/com/sickworm/intellij/jugg/JuggManager.kt`
  - 使用固定问题报告 endpoint，不再读取和保存历史地址。
- `main/src/main/java/com/sickworm/intellij/jugg/diagnostics/IssueReportBundleBuilder.kt`
  - hook 日志默认选中，使所有生成的候选项默认勾选。
- `main/src/main/java/com/sickworm/intellij/jugg/diagnostics/IssueReportUploader.kt`
  - 定义固定的 Jugg 问题报告 endpoint。
- `main/src/main/java/com/sickworm/intellij/jugg/ide/bean/JuggSettings.kt`
  - 删除不再使用的 `reportUploadUrl` 配置。
- `main/src/test/java/com/sickworm/intellij/jugg/diagnostics/IssueReportBundleBuilderTest.kt`
  - 保护所有候选项默认选中的稳定行为。
- 同步 `docs/ai_knowledge/04_engineering_ide.md`、`docs/ai_knowledge/05_utilities.md` 和 `docs/wiki/guide/jugg-backend/diagnostics.md`。

## 3. 验证

- 先运行新增的 L1 定向测试取得失败证据，再修改生产代码。
- 运行 `IssueReportBundleBuilderTest` 和 `IssueReportUploaderTest`。
- 运行 `./gradlew :idea:compileKotlin` 验证 IDE 模块接线。
- UI 排序和禁用状态不新增绑定 Swing 私有结构的自动化测试，使用代码审查和 IDE 手工矩阵作为替代验证。

## 4. 不在范围内

- 不调整诊断包白名单、脱敏和 manifest 格式。
- 不调整上传协议、失败重试和本地保存流程。
- 不新增自定义 endpoint 配置或 fallback。
