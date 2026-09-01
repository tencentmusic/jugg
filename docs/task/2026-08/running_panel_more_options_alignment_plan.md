# Running Pannel More Options 能力迁移方案

## 1. 目标

将旧 `MoreOptionsManager` 中仍有效的设置和工具入口迁移到 `Jugg Running Pannel` 的 Settings，保持原有展示条件、确认流程和状态副作用，随后删除已无展示入口的旧菜单实现。

## 2. 已批准范围

### 2.1 Settings 新增入口

- Deployment：按已连接设备展示 `Force use compat deploy for <device>`。
- Integrations：增加 `Set custom server URL`。
- Advanced：增加 `Mark as project synced and re-init compiler` 与 `Mark as gradle compiled and re-init compiler`。

Overview Quick Actions 不修改。后续确认全局 compat deploy 恒为开启，不在 Settings 保留 `Enable compat deploy` 开关。

### 2.2 行为对齐

- Quick deploy、Embed APK、Project Kotlin、按设备 compat 仅在 `isEnableInjectGradleCompile` 开启时展示。
- Backup classpath 仅在 `isCanUseBackupClasspath` 为真时展示。
- 启用 Embed APK 前保留确认；取消时不修改设置。
- 切换 Backup classpath 前保留确认；成功后删除 deploy history。
- 按设备 compat 继续使用 `CompatDeployHelper` 记录，并强制下次重新安装。
- 两个测试操作保留原确认流程；Gradle compiled 操作使用当前选中的 Jugg Run Configuration，无法取得时明确提示。

### 2.3 旧实现删除

- 删除 `MoreOptionsManager.kt`。
- 删除仅被旧菜单使用的 `JuggMoreOptionsItem.kt`。
- 将更新检查、缓存清理、自定义服务器和测试操作迁移到现有 `JuggManager` / `JuggControlPanelController`。
- `IJuggManagerCaller.getMoreOptions()` 属于稳定 ClassLoader 桥接接口，暂时保留兼容签名并返回空 ActionGroup，避免旧 `ide_entry` 与新业务 JAR 组合时出现二进制不兼容。

## 3. 预计修改文件

- `main/src/main/java/com/sickworm/intellij/jugg/ide/controlpanel/JuggControlPanelModel.kt`
- `idea/src/main/java/com/sickworm/intellij/jugg/JuggManager.kt`
- `idea/src/main/java/com/sickworm/intellij/jugg/ide/ui/JuggControlPanelController.kt`
- `idea/src/main/java/com/sickworm/intellij/jugg/ide/ui/JuggControlPanel.kt`
- `idea/src/ide_entry/java/com/sickworm/intellij/jugg/ide/IJuggManagerCaller.kt`
- `idea/src/test/java/com/sickworm/intellij/jugg/ide/logic/JuggRunSettingsComponentTest.kt`
- `docs/ai_knowledge/04_engineering_ide.md`
- `docs/ai_knowledge/98_code_map.md`

## 4. 测试与验证

- 失败证据：先扩展 `JuggRunSettingsComponentTest`，证明 Settings 缺少入口、动态设备配置和条件展示。
- 测试价值：Settings 是用户可见且稳定的行为边界，复用现有 L2 owner，不新增测试类。
- 定向测试：`./gradlew :idea:test --tests "com.sickworm.intellij.jugg.ide.logic.JuggRunSettingsComponentTest"`。
- 编译验证：`./gradlew :idea:compileKotlin`。
- 静态验证：确认被删除类型零引用，执行 `git diff --check` 并检查本次日志格式。
- 完成后执行独立只读实现审查，修复有效问题后重新验证。

## 5. 排除项

- 不修改 Overview Quick Actions。
- 不删除稳定桥接接口中的 `getMoreOptions()` 兼容签名。
- 不引入新的设置抽象、接口或测试专用 seam。

## 6. 实施结果

- 旧菜单中的缺失配置和工具入口已迁移到 Settings，展示条件、确认流程及状态副作用保持一致。
- Settings 每次进入或再次打开时刷新已连接设备，保持按设备 compat 配置与旧菜单的动态行为一致。
- 全局 `isEnableCompatibleDeploymentMode` 恒为 `true`，Settings 不展示全局 compat deploy 开关。
- `MoreOptionsManager` 与 `JuggMoreOptionsItem` 已删除；稳定桥接方法保留为空 ActionGroup 兼容实现。
- Overview Quick Actions 未修改。
