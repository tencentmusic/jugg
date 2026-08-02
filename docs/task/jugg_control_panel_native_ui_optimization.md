# Jugg Control Panel IntelliJ 原生化方案

## 目标

- Quick Actions 按 Build、Device、Jugg Plugin 分组，并使用文字链接。
- Logs 使用 IntelliJ 原生筛选控件与可选择列表，支持复制选中日志。
- Settings 使用 `JBCheckBox`、`ActionLink` 和无卡片分组。
- Changed Files 与 Recent Runs 使用可选择列表。
- 实际触发 install 的成功运行在 This session 中只计一次 Install，不再误计 Hot fix。

## 变更范围

- `idea/src/main/java/com/sickworm/intellij/jugg/ide/ui/JuggControlPanel.kt`
  - 原生化 Overview、Logs、Settings 控件和布局。
- `idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/JuggRunningTask.kt`
  - 在终态事件中记录本次运行是否执行 install。
- `main/src/main/java/com/sickworm/intellij/jugg/ide/controlpanel/JuggEvent.kt`
  - 保存 install 事实，不改变原始 deploy type。
- `main/src/main/java/com/sickworm/intellij/jugg/ide/controlpanel/JuggControlPanelModel.kt`
  - 根据 install 事实更新 session 统计。
- `main/src/test/java/com/sickworm/intellij/jugg/ide/controlpanel/JuggControlPanelModelTest.kt`
  - 保护 install 与 hot fix 互斥计数行为。
- `idea/src/test/java/com/sickworm/intellij/jugg/ide/logic/JuggRunSettingsComponentTest.kt`
  - 更新 Control Panel 原生控件结构回归。

## 约束

- 不修改部署类型判定和部署流程。
- 不修改设置持久化语义。
- 同一次多设备运行最多增加一次 Install。
- 失败、取消或未部署成功的运行不增加 Install。

## 验证

- 先运行新增的 session 统计用例取得失败证据。
- 运行 Control Panel Model 和 UI 的定向测试。
- 执行 `./gradlew :idea:compileKotlin`。
- 手工检查筛选、列表选择、复制和窄 Tool Window 布局。
