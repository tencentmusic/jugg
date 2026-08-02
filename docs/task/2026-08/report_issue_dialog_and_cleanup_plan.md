# 问题日志确认页与诊断产物清理方案

## 1. 目标行为

- 问题日志确认列表只展示文件路径和大小，不展示敏感等级与脱敏状态。
- 默认主按钮显示 `Update logs`；勾选 `Save locally without uploading` 后切换为 `Create Diagnostics Bundle`。
- 结果页及复制结果不展示诊断包路径。
- 仅保存到本地时，创建成功后通过系统文件管理器选中新生成的 ZIP。
- hook 调试日志在 ZIP 内使用 `diagnostics/cli/hook-debug.log`。
- `build/jugg/tmp/diagnostics` 中达到 7 天的文件自动清理，并删除空目录。
- diagnostics 清理复用 MCP 产物启动后延迟清理的时机，但保持独立调用与失败边界。

## 2. 实现范围

- `idea/src/main/java/com/sickworm/intellij/jugg/ide/ui/ReportIssueDialog.kt`
  - 精简候选项文案，并根据本地保存选项动态更新主按钮。
- `idea/src/main/java/com/sickworm/intellij/jugg/ide/ui/ReportIssueResultDialog.kt`
  - 移除页面和剪贴板中的诊断包路径。
- `idea/src/main/java/com/sickworm/intellij/jugg/JuggManager.kt`
  - 本地保存后在系统文件管理器中选中 ZIP。
  - 在 MCP 清理调度中单独执行 diagnostics 7 天清理。
- `main/src/main/java/com/sickworm/intellij/jugg/diagnostics/IssueReportBundleBuilder.kt`
  - 调整 hook 日志 ZIP entry 路径。
- `main/src/main/java/com/sickworm/intellij/jugg/project/JuggPathManager.kt`
  - 统一声明 diagnostics 临时目录。
- 过期产物清理实现
  - 将 MCP 专用清理器泛化为项目级文件清理器，由 MCP 与 diagnostics 复用。
- `main/src/test/java/com/sickworm/intellij/jugg/diagnostics/IssueReportBundleBuilderTest.kt`
  - 保护 hook 日志 ZIP entry 契约。
- 项目级过期文件清理器测试
  - 保护过期删除、未过期保留和空目录清理行为。

## 3. 验证

- 先加入并运行失败测试，再修改生产代码。
- 定向运行诊断包与过期清理测试。
- 运行 `./gradlew :idea:compileKotlin` 验证 IDE API 接线。
- 手工核对确认页按钮切换、结果页内容和本地 ZIP reveal 行为。

## 4. 文档同步

- 更新 `docs/ai_knowledge/04_engineering_ide.md`、`05_utilities.md`、`08_mcp_design.md`、`98_code_map.md`。
- 更新中文 Report Issue 指南中的确认列表与本地保存行为。

## 5. 不在范围内

- 不修改诊断包白名单、脱敏规则、上传 endpoint、协议或失败重试策略。
- 不改变候选项默认选择规则和 Jugg 日志不可取消规则。
- 上传失败时仍保留本地 ZIP，但结果页不显示其路径。
