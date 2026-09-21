# 文件变化过多时降级确认（Run Panel 开关）方案

## 需求背景
目前当源文件变更数量或受影响模块数量超过阈值（`TooManyChanges`）时，Jugg 会弹出 `TooManyChangesConfirmDialog` 对话框，询问用户是降级到 Gradle 还是倒计时后继续增量编译。
用户希望参考 `Confirm fallback when no files changed` 的机制，在 **Run Panel**（即右侧 `Jugg Running Panel` 的 Settings 中）增加一个开关：
- 命名与文案：`Confirm fallback when too many changes`，帮助说明：`Ask before running a full Gradle build.`。
- 默认开启（`true`），开启时保留弹窗确认行为。
- 当用户关闭此开关（`false`）时，遇到文件变化过多不再弹出确认对话框，而是直接自动降级到 Gradle 编译。

---

## 涉及模块与改动文件

### 1. `main` 模块（配置与编译核心）
- **`main/src/main/java/com/sickworm/intellij/jugg/ide/bean/JuggSettings.kt`**
  - 在 `Run options` 分组中增加持久化属性：
    ```kotlin
    var isConfirmFallbackWhenTooManyChanges: Boolean by propertiesComponent.delegate(defaultValue = true)
    ```
  - 默认值为 `true`（开启时弹窗确认；关闭为 `false` 后自动降级不弹窗）。

- **`main/src/main/java/com/sickworm/intellij/jugg/ide/controlpanel/JuggControlPanelModel.kt`**
  - 在 `JuggControlPanelModel.Settings` 中增加不可变字段：
    ```kotlin
    val confirmFallbackWhenTooManyChanges: Boolean = true,
    ```

- **`main/src/main/java/com/sickworm/intellij/jugg/compiler/IncrementalCompilerHelper.kt`**
  - 在 `checkFilesFallback()` 中，如果 `tooManyChanges != null && !skipTooManyChangesCheck`：
    当 `!JuggSettings.isConfirmFallbackWhenTooManyChanges` 时，直接判定为 `TooManyChangesConfirmResult.FALLBACK`，无需调用 `uiHandler.confirmTooManyChanges()`。

### 2. `idea` 模块（IDE 控制面板与编译交互）
- **`idea/src/main/java/com/sickworm/intellij/jugg/ide/ui/JuggControlPanelController.kt`**
  - 在 `Setting` 枚举中增加 `CONFIRM_FALLBACK_WHEN_TOO_MANY_CHANGES("Confirm fallback when too many changes")`。
  - 在 `currentSettings()` 中读取 `JuggSettings.isConfirmFallbackWhenTooManyChanges`。
  - 在 `updateSetting()` 中处理 `Setting.CONFIRM_FALLBACK_WHEN_TOO_MANY_CHANGES -> JuggSettings.isConfirmFallbackWhenTooManyChanges = enabled`，触发配置持久化、模型更新与用户操作事件记录。

- **`idea/src/main/java/com/sickworm/intellij/jugg/ide/ui/JuggControlPanel.kt`**
  - 在 `createSettings()` 的 `Run behavior` 组中增加开关：
    ```kotlin
    settingToggle(
        "Confirm fallback when too many changes",
        "Ask before running a full Gradle build.",
        JuggControlPanelController.Setting.CONFIRM_FALLBACK_WHEN_TOO_MANY_CHANGES
    )
    ```
  - 在 `renderSettings()` 中将 `Setting.CONFIRM_FALLBACK_WHEN_TOO_MANY_CHANGES` 映射到 `settings.confirmFallbackWhenTooManyChanges`。

- **`idea/src/main/java/com/sickworm/intellij/jugg/compiler/JuggCompileHelper.kt`**
  - 在 `checkFilesFallback()` 中，若 `tooManyChanges != null`：
    当 `!JuggSettings.isConfirmFallbackWhenTooManyChanges` 时，直接判定为 `TooManyChangesConfirmResult.FALLBACK`，不调用 `uiHandler?.confirmTooManyChanges()`。
  - 说明：UI Handler（`JuggCompileUiHandler`）保持纯 UI 职责，不侵入业务开关判断。

---

## 验证与测试计划

### 1. 自动化测试
- **`idea/src/test/java/com/sickworm/intellij/jugg/compiler/JuggCompileHelperTest.kt`**
  - 测试：在预处理编译检查中，当超限且 `!isConfirmFallbackWhenTooManyChanges` 时，直接返回 fallback 结果，并且 `uiHandler.confirmTooManyChanges` 未被调用。
- **`idea/src/test/java/com/sickworm/intellij/jugg/ide/logic/JuggRunSettingsComponentTest.kt`**
  - 测试：验证 Control Panel 中该开关的关闭/开启能正确更新 `JuggSettings.isConfirmFallbackWhenTooManyChanges`，记录用户操作事件，并正确绑定 CheckBox 状态。

### 2. 编译与运行验证
- 运行 `./gradlew :idea:compileKotlin :main:compileKotlin` 确认编译无错误。
- 运行定向测试：
  - `./gradlew :idea:test --tests "com.sickworm.intellij.jugg.compiler.JuggCompileUiHandlerTest"`
  - `./gradlew :idea:test --tests "com.sickworm.intellij.jugg.compiler.JuggCompileHelperTest"`
  - `./gradlew :idea:test --tests "com.sickworm.intellij.jugg.ide.logic.JuggRunSettingsComponentTest"`

---

## 文档同步
- **`docs/ai_knowledge/02_compile_core.md`**：更新编译决策链中关于变更文件过多的确认行为说明。
- **`docs/wiki/zh/capabilities/compile/gradle-fallback.md`** 与 **`docs/wiki/capabilities/compile/gradle-fallback.md`**：同步更新“文件过多或模块过多”场景支持在 Running Panel 设置自动降级跳过确认直接 Gradle。
