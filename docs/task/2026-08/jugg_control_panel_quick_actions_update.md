# Jugg Control Panel Quick Actions 调整

## 背景

Jugg Running Pannel 的 Quick Actions 与 Settings 当前存在命名不清晰、分类不准确和安装入口描述过于宽泛的问题。`Clean & Reinstall` 实际会清除 App 数据，但入口没有二次确认。

## 变更范围

### Quick Actions

- Build：`Fallback to Gradle`、`Clear Jugg Build`
- Device：`Restart App`、`Clear app data`
- Jugg Plugin：`Report Issue`、`Check updates`、`Install CLI & Skill`
- `Clear app data` 执行前复用 `CommonConfirmDialog`，确认后继续调用既有 `cleanAndReinstall()` 流程。

### Settings

- `Update channel` 改为 `Check Jugg updates`。
- `Jugg CLI and skills` 改为 `Install CLI and agent skills`，说明明确包含 CLI、agent skills、hooks 和必要权限。
- Advanced 下的 `Reset Jugg cache` 改为 `Clear Jugg Build`，保留既有清理 Jugg 项目构建数据并重新初始化项目的行为。

## 实现文件

- `idea/src/main/java/com/sickworm/intellij/jugg/ide/ui/JuggControlPanel.kt`
- `idea/src/main/java/com/sickworm/intellij/jugg/ide/ui/JuggControlPanelController.kt`
- `idea/src/test/java/com/sickworm/intellij/jugg/ide/logic/JuggRunSettingsComponentTest.kt`
- `docs/ai_knowledge/04_engineering_ide.md`

## 验证

- 复用 Control Panel 现有 UI 契约测试，验证 Quick Actions 分类、顺序和新文案。
- 执行定向测试与 `:idea:compileKotlin`。
- 手工验证 `Clear app data` 的确认、取消路径。

## 非目标

- 不改变 Gradle fallback、清除 App 数据、Jugg build 清理或 skills 安装的底层行为。
- 不修改 More Options 菜单中的独立入口文案。
- 不引入新的 action 类型、设置项或测试专用依赖注入。
