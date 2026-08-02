# Jugg Control Panel UI 优化方案

## 目标

- Changed Files 与 Recent Runs 使用代码常量控制可见行数，不向用户提供行数配置。
- Changed Files 使用 IntelliJ 文件类型图标，并仅展示模块名与文件名。
- Changed Files 与 Recent Runs 的 item 使用 IntelliJ 主题分割线。
- 首块信息增加 `Run status` 标题。
- Quick Actions 移除 `More…`，直接展示全部已有动作。
- 没有有效 Jugg Run Configuration 的项目自动隐藏 Jugg Tool Window。

## 实现范围

- `idea/src/main/java/com/sickworm/intellij/jugg/ide/ui/JuggControlPanel.kt`
  - 删除行数下拉与用户配置入口，增加两组私有行数常量。
  - Changed Files 使用 IDE 文件类型 icon，显示 `moduleName / fileName`。
  - 两组列表 item 增加 `JBColor.border()` 主题分割线。
  - 增加 `Run status` 标题。
  - 将 Report Issue、Reset Jugg Cache 等现有 More 菜单动作直接放入 Quick Actions。
- `idea/src/main/java/com/sickworm/intellij/jugg/ide/ui/JuggControlPanelController.kt`
  - Changed File 模块名优先使用 `gradleModuleName`，缺失时使用 `name`。
  - 删除行数偏好读写接口。
- `idea/src/main/java/com/sickworm/intellij/jugg/ide/ui/JuggToolWindowFactory.kt`
  - 复用 `hasRunnableJuggConfiguration` 控制 Tool Window 可用性。
- `docs/ai_knowledge/04_engineering_ide.md`
  - 同步 Control Panel 展示与 Tool Window 可用性行为。

## 验证

- 自动化测试价值判断：不新增。文件 icon、边框、布局与标题属于 Swing 展示细节，自动化断言会绑定组件实现。
- 执行 `./gradlew :idea:compileKotlin` 验证 IntelliJ API 与类型接线。
- 手工检查有/无有效 Jugg Run Configuration、不同文件类型、缺失文件、Recent Runs 展开和全部 Quick Actions。

## 非目标

- 不增加用户级行数配置。
- 不新增 Quick Action 能力。
- 不重构 Control Panel 列表架构。
- 不增加 Run Configuration 变更监听；Tool Window 使用 IntelliJ 官方工厂可用性入口判断项目打开时的状态。
