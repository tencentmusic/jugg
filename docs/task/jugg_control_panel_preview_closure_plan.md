# Jugg Control Panel Preview 收口计划

## 1. 目标

按 `jugg_control_panel_layout_review.html` 分五个独立批次收口生产实现。每个批次遵循 TDD，完成定向验证后单独提交，避免把行为、状态模型和纯视觉调整混在同一 commit。

## 2. 批次

### 批次 1：Run Configuration 跳转

- `More options` 触发宿主窗口默认 OK 动作，保存并关闭 Run Configuration。
- 仅在窗口成功关闭后打开 `Jugg Running Pannel` 的 Settings。
- 测试：`JuggRunSettingsComponentTest`（L2）。

### 批次 2：Context 与 Current Task

- 展示当前 Run Configuration、module / variant / package、设备和变更文件摘要。
- 复用现有运行状态，展示 Idle / Running、阶段说明与耗时。
- 测试：复用 `idea/src/test` 中现有 UI / manager 协作测试（L2）。

### 批次 3：Last Deploy

- 展示 Detect changes / Compile / Deploy / Resume 的最近任务时间线。
- 支持跳转 Logs。
- 测试：状态到 UI 的协作测试（L2）；不改变 deploy 主链路，因此不新增 L3。

### 批次 4：Settings 动态状态

- Device compatibility 展示真实设备与策略。
- Integrations 展示 CLI、skills、版本和更新状态。
- 测试：Settings 状态映射协作测试（L2）。

### 批次 5：视觉与反馈

- 收口 Tab 指示、switch、圆角、危险操作和操作反馈。
- 保持 IntelliJ Platform 主题、focus、disabled 与可访问性语义。
- 测试：Swing 结构和交互测试（L2）。

## 3. 验证约束

- 禁止无 `--tests` 的全量 `:idea:test`。
- 每批至少执行对应定向测试和 `./gradlew :idea:compileKotlin`。
- 每批使用独立英文 commit message。
