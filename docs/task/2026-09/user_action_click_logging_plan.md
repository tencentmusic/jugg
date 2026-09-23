# Jugg 业务点击写入 `[UserAction]` 日志与 Running Panel

> 创建日期：2026-09-15
> 状态：已实现
> 决策：只记录 Jugg 业务点击和确认框；文件日志前缀固定为 `[UserAction]`；同时进入 `compile_latest.log` 与 Running Panel Logs。

## 1. 目标

用户在 IDE 里点 Jugg 业务入口或确认框后，同一条文案同时出现在：

- 文件日志：`[UserAction] <title>: <detail>`
- Running Panel Logs：`category=USER_ACTION`，title/detail 与日志正文一致

不占用、不取消当前 compile/deploy task。

## 2. 范围

做：

- Overview Quick Actions、Settings 文字动作/开关、Clear app data 确认
- 菜单/工具栏：Fallback to Gradle、Restart App、Report Issue、Check updates、Install Skills
- `JuggManager` 用户入口：`resetJuggCache`、`runRemoteCommand`
- 编译确认框：无变化 Fallback、构建文件变化、变更过多
- Gradle fallback 确认框（点 Fallback to Gradle 后的 Continue 对话框）

不做：

- 全局 IDE `AnAction` / 鼠标点击
- Tab、筛选、列表选择、打开 Panel
- Run/Debug 按钮（已有 compile/deploy 事件）
- MCP/CLI 自动确认路径

## 3. 实现要点

- 单一漏斗：`JuggControlPanelController.recordUserAction()` / `recordSettingChanged()` 使用 `logger.getInstance("UserAction")` 写 `info`，再 `model.record(USER_ACTION)`。
- 面板 `actionLink` 不再单独记，避免与 `JuggManager` 入口重复。
- `JuggManager` 的 IDE 用户入口调用 `recordUserAction` 一次。
- `JuggCompileUiHandler` 与 `IdeaForceGradleCompileHelper` 在对话框返回后记一条，RPC/androidTest 自动返回不记。

## 4. 验证

- Owner：`idea/src/test/java/com/sickworm/intellij/jugg/ide/logic/JuggRunSettingsComponentTest.kt`
- L2：`recordUserAction` 写入 model，capturing logger 含 `[UserAction] Action triggered: ...`
- L2：设置开关同样带 `[UserAction]` 前缀
- 不新增 L3
- 手工：Quick Action、工具栏 Restart、Fallback 确认框，对照 Logs 页与 `compile_latest.log`

## 5. 文档

- `docs/ai_knowledge/04_engineering_ide.md`
- `docs/wiki/zh/guide/control-panel.md` 及英文镜像
