# Too many changes 确认框

## 背景

源码变化超过内部阈值时，Jugg 会直接回退 Gradle，用户只看到 `Too many changes`。阈值（模块数 / 文件点数）不适合做成常驻设置。改为一次性确认：默认仍走 Gradle，用户可在倒计时后选择本轮继续增量。

## 目标行为

预处理超限和影响分析跟编超限共用同一张确认框：

- 展示本轮将编译的 Kotlin / Java / 模块数量，说明增量通常更慢。
- 右侧默认按钮 `Fallback to Gradle` 立刻可点。
- 左侧 `Continue Incremental Compile` 倒计时 2 秒后可点，只对当前 Run 有效。
- 关窗取消本轮。
- MCP / CLI / `checkFallback()` 不弹窗，直接 Gradle。

不暴露内部点数，不增加 Control Panel 设置。

## 改动范围

- `CompileUiHandler.confirmTooManyChanges()` 与 `TooManyChangesConfirmResult`
- `TooManyChangesConfirmDialog`：复用 build.gradle 变更框的 2 秒倒计时
- `JuggCompileHelper` / `IncrementalCompilerHelper`：超限时先确认再决定
- Wiki：`gradle-fallback`、`downgrade-gradle`、`jugg-slow-or-stuck`
- 测试：`JuggCompileHelperTest`、`IncrementalCompilerHelperTest`、`JuggCompileUiHandlerTest`

## 验证

预处理三种选择、状态查询不弹窗、跟编超限同一套确认、RPC 自动 Gradle。对话框倒计时对照 `BuildChangesConfirmDialog` 手工点一次。
