# Jugg Control Panel 原生控件布局对齐方案

布局关系提取结果见 [`jugg_control_panel_layout_spec.json`](jugg_control_panel_layout_spec.json)，Review 说明见 [`jugg_control_panel_layout_spec_review.md`](jugg_control_panel_layout_spec_review.md)。规格状态确认前不开始业务代码修改。

> 实施状态：规格已批准，原生 mock 实现和 420px Darcula 对比截图已完成，等待视觉 Review。

## 1. 目标

在保留 IntelliJ 原生控件、主题、字体渲染、焦点和 disabled 状态的前提下，使 `JuggControlPanel` 的布局、信息层级和内容密度与 `jugg_control_panel_layout_review.html` 一致。

本阶段使用固定 mock 数据，只还原一套可稳定复现的 Ready / 最近部署成功状态，用于视觉验收。暂不接入真实任务状态、日志、设备、部署历史和设置持久化，也不扩展其他状态。

## 2. 已确认决策

1. 控件外观以 IntelliJ Platform 为准，不复制 HTML 的 CSS 皮肤。
2. HTML 是布局、内容、顺序、间距和信息层级基线。
3. 首轮只验收 Darcula、420 逻辑像素宽、固定 mock 数据。
4. Tool Window 标题栏由 IDE 提供，不在 `JuggControlPanel` 内重复实现。
5. 当前实现作为入口和行为脚手架使用，但页面组件树按基线重新组织，不继续做零散 margin 修补。

## 3. 范围

### 3.1 本阶段包含

- Overview、Logs、Settings 三个一级页。
- 与 HTML 一致的模块顺序、文案和 mock 数据。
- 原生 IntelliJ 控件替换当前通用 Swing 控件。
- 420px 基准宽度下的尺寸、对齐、换行和滚动行为。
- 页面切换、日志搜索、设置搜索、toggle 和 More 菜单的本地预览交互。
- 真实 IDE 中的截图和半透明叠图验收。

### 3.2 本阶段不包含

- 真实 compile / deploy 任务状态订阅。
- 真实设备、changed files、deploy history 和 project health 数据。
- 真实日志 tail、level/task/device 筛选和日志轮转。
- 新增业务 action 或改变现有 compile / deploy 链路。
- Running、Failed、无配置、无设备、空日志等其他状态。
- HTML 固定色值、阴影、圆角和 switch 皮肤的逐像素复制。

## 4. 验收边界

### 4.1 对比区域

HTML 中只对比 `.panel-tabs` 和三个 `.panel-page`。`.jugg-titlebar`、IDE toolbar、Project、Editor 和 Status Bar 仅用于表达使用环境，不属于 `JuggControlPanel` 的实现范围。

实际截图从 Tool Window 原生标题栏下方开始裁切，包含一级 tabs 和当前页面内容。

### 4.2 主验收环境

| 项目 | 基线 |
|---|---|
| Theme | Darcula |
| Tool Window 内容宽度 | 420 logical px |
| UI scale | 当前 IDE 默认逻辑缩放 |
| 页面状态 | 固定 Ready / Last deploy success mock |
| 内容语言 | 与 HTML 相同的英文文案 |

Light theme、360px 和 520px 只做无溢出、无截断、可滚动的 smoke check，不作为首轮逐项视觉验收对象。

### 4.3 一致性定义

| 项目 | 要求 |
|---|---|
| 页面及模块顺序 | 必须完全一致 |
| 文案和 mock 数据 | 必须完全一致 |
| 主区域边界 | 与基线相差不超过 2 logical px |
| section padding / grid gap | 必须使用方案定义值 |
| 420px 下的换行行数 | 必须一致 |
| Quick Actions | 固定 2×2、等宽、等高 |
| Settings 行 | label/help 左对齐，控制项右对齐 |
| 横向滚动 | 禁止出现 |
| 原生控件皮肤 | 不纳入像素一致性比较 |
| 字体抗锯齿差异 | 不纳入像素一致性比较 |

## 5. 布局 token

所有尺寸使用未缩放逻辑值声明，由 `JBUI.scale()` 或原生布局自动缩放。

| Token | 值 | 用途 |
|---|---:|---|
| `PAGE_INSET` | 12 | Overview section 内边距 |
| `SECTION_TITLE_GAP` | 8 | eyebrow 与 section 内容 |
| `CONTENT_GAP` | 4 | 同一信息块的主次文本 |
| `GRID_GAP` | 8 | Quick Actions 行列间距 |
| `ACTION_HEIGHT` | 48 | 两行 Quick Action 按钮高度 |
| `SETTINGS_INSET` | 10 | Settings 页面边距 |
| `SETTINGS_GROUP_GAP` | 10 | Settings 分组间距 |
| `SETTING_ROW_V_INSET` | 9 | Settings 行垂直内边距 |
| `SETTING_ROW_H_INSET` | 10 | Settings 行水平内边距 |
| `SETTING_TEXT_CONTROL_GAP` | 12 | 文案与控制项间距 |
| `LOG_TOOLBAR_GAP` | 8 | Logs 工具区域间距 |
| `DIVIDER_HEIGHT` | 1 | section / row 分隔线 |

禁止为单个 section 增加未列入 token 的特殊 margin。视觉微调确有必要时，先更新 token 或在方案中记录光学校正原因。

## 6. 原生组件选择

| 区域 | 组件 | 说明 |
|---|---|---|
| 根容器 | `JBPanel` + `BorderLayout` | 不设置固定背景色 |
| 一级 tabs | `JBTabbedPane` | 使用平台 tab 皮肤；页面自行管理内边距 |
| 纵向页面 | `JPanel` + `VerticalLayout(0)` | 子项自动填满宽度，移除 BoxLayout maximumSize 修补 |
| 滚动 | `JBScrollPane` | 关闭横向滚动，viewport view 跟随可视宽度 |
| 标题/正文 | `JBLabel` | 字体从当前 LAF 派生，不写固定字体族和字号 |
| 富文本行 | `SimpleColoredComponent` 或多个 `JBLabel` | 不用一整段 HTML 模拟复杂布局 |
| 页面链接 | `AnActionLink` | 不使用普通无边框 JButton 模拟链接 |
| Quick Actions | `JButton` | 保留原生按钮皮肤，统一 48px 高度和两行内容 |
| Logs 搜索 | `JBTextField` | 使用 `emptyText` |
| Logs 内容 | `JBTextArea` + `JBScrollPane` | 等宽字体由平台字体派生 |
| Logs source | `JToggleButton` + `ButtonGroup` | 三列等宽原生 segmented-like 布局 |
| Settings toggle | `OnOffButton` | 替换无 label 的 `JCheckBox` |
| Settings action | `JButton` | 宽度由最长基线文案统一 |
| 状态图标 | `AllIcons` | 不使用 Unicode 字符模拟状态 icon |
| 分隔线/颜色 | `JBColor`、`UIUtil` | 不复制 HTML 色值 |

不引入自绘 UI delegate，不覆盖 `paintComponent()`，不新增 CSS 色板对应层。

## 7. 固定 mock 数据

mock 数据集中放在 `JuggControlPanel.kt` 内的单一 private object，页面构建方法只消费该对象，禁止将字符串分散硬编码在多个 builder 中。本阶段不新增公共 model、service 或接口。

### 7.1 Overview

```text
Configuration: run Jugg
Module / variant: app · debug
Package: com.sickworm.demo
Device: Pixel 8 API 35
Changed files: 3 changed files
Strategy: Hot reload is available

Current task: Idle
Summary: Last deploy 14:32 · Hot reload
Duration: 1.8s

Timeline:
- Detect changes · 3 files · 120ms
- Compile · 2 Kotlin · 1 resource · 860ms
- Deploy · Code swap · 540ms
- Resume app · 210ms

Project health:
- Gradle project info may be outdated · Sync
- Deploy baseline is ready

Recent activity:
- 14:32 · Deploy · Hot reload · Success
- 14:28 · CLI · restart · Success
- 14:20 · Gradle build · Failed
```

### 7.2 Logs

使用 HTML 中现有八条日志作为固定内容，初始 source 为 Deploy。搜索和 source 切换只过滤内存中的 mock 行，不读取 `compile_latest.log`。

工具区保持两行：

1. Deploy / Runtime / CLI-MCP。
2. Search / All levels / Current task / Follow / overflow。

### 7.3 Settings

分组、行顺序、文案和初始值与 HTML 完全一致：

1. Run behavior。
2. Deployment。
3. Compiler。
4. Device compatibility。
5. Integrations。
6. Advanced。

toggle 只修改当前面板内的 mock 状态，不写入 `JuggSettings`。搜索只控制分组可见性。

### 7.4 Preview action 规则

- 页面切换、搜索、toggle 和 More 菜单必须可交互。
- Quick Actions 和设置 action 在本阶段不触发真实 compile、deploy、reset 或安装操作。
- 点击 mock action 只提供面板内轻量反馈，禁止弹出破坏视觉验收流程的模态框。
- 该 mock 版本属于视觉评审中间态，不作为功能完成版本发布。

## 8. 页面布局

### 8.1 Overview

固定顺序：

```text
Context Header
Current Task
Quick Actions
Last Deploy
Project Health
Recent Activity
```

要求：

- 每个 section 全宽、内容高度、底部 1px divider。
- Context Header 在 420px 下固定为两行 meta，避免 FlowLayout 高度不稳定。
- Quick Actions 使用 2×2 GridLayout，按钮高度统一为 48px。
- Timeline 使用三列：状态 icon、内容、耗时；耗时右对齐。
- Project Health 使用 icon、说明、action 三列。
- Recent Activity 使用 time、description、result 三列。

### 8.2 Logs

- 页面根布局使用 `BorderLayout`。
- toolbar 固定在 NORTH，console 填满 CENTER。
- source selector 使用 3 等分列。
- 第二行控件在 420px 下不换行；Search 占用剩余宽度。
- 日志列宽固定为 timestamp、level、class、message，message 使用剩余宽度。
- 本阶段允许 console 文本按固定列格式渲染，不新增日志 table model。

### 8.3 Settings

- 页面外层 10px padding。
- Search 固定在 NORTH，分组列表在滚动区。
- 分组标题和 rows 使用原生背景、border 和字体。
- 每个 row 使用 `BorderLayout(12, 0)`：文案 CENTER，控制项 EAST。
- `OnOffButton` 不设置 HTML 的 30×16 固定皮肤尺寸，以平台 preferred size 为准。
- 同类 action button 统一最小宽度，避免右侧边界跳动。

## 9. TDD 与验证

本阶段是用户可见 UI 布局重构，不改变 Run → compile → deploy 主链路。自动化测试复用现有测试文件，不新增单文件 Mockito 测试；视觉一致性由真实 IDE 截图证明。

### 9.1 业务代码修改前的失败测试

| 测试路径 | 层级 | 覆盖内容 |
|---|---|---|
| `idea/src/test/java/com/sickworm/intellij/jugg/ide/logic/JuggRunSettingsComponentTest.kt` | L2 | 原生组件类型、三个页面、Overview 顺序、Quick Actions 2×2、Settings 分组顺序、420px 无横向溢出 |
| `idea/src/test/java/com/sickworm/intellij/jugg/ide/logic/JuggPluginActionRegistrationTest.kt` | L2 | Tool Window 注册和入口保持不变，仅回归现有用例 |

首个失败测试至少断言：

1. tabs 是 `JBTabbedPane`。
2. Settings toggle 是 `OnOffButton`。
3. Overview 六个 section 顺序与 HTML 一致。
4. 在 420px 容器完成 layout 后，各 section 宽度等于 viewport 宽度。
5. Quick Actions 是 2×2，gap 为 8，四个按钮等宽等高。
6. Logs 和 Settings 的固定控件及分组齐全。

### 9.2 定向验证

```bash
./gradlew :idea:test --tests "com.sickworm.intellij.jugg.ide.logic.JuggRunSettingsComponentTest"
./gradlew :idea:test --tests "com.sickworm.intellij.jugg.ide.logic.JuggPluginActionRegistrationTest"
./gradlew :idea:compileKotlin
```

不运行无 `--tests` 过滤的全量 `:idea:test`。

### 9.3 视觉验收证据

视觉实现完成后保存：

```text
docs/task/assets/jugg_control_panel_layout/
├── reference_overview_420.png
├── actual_overview_420.png
├── overlay_overview_420.png
├── reference_logs_420.png
├── actual_logs_420.png
├── overlay_logs_420.png
├── reference_settings_420.png
├── actual_settings_420.png
└── overlay_settings_420.png
```

叠图使用 50% 透明度。只比较本方案第 4.1 节定义的区域。

## 10. 实施顺序

1. Review 并确认 `jugg_control_panel_layout_spec.json` 中的四项待决策内容，将状态改为 `approved`。
2. 在现有 `JuggRunSettingsComponentTest.kt` 写失败测试，锁定原生组件类型、结构和 420px 几何约束。
3. 在 `JuggControlPanel.kt` 集中定义 mock 数据和布局 token。
4. 替换根 tabs 和纵向布局，先解决全宽、滚动和页面 inset。
5. 按 Context → Current Task → Quick Actions → Timeline → Health → Activity 顺序完成 Overview。
6. 完成 Logs 两行工具区和 mock console。
7. 完成 Settings 搜索、分组、原生 `OnOffButton` 和 action rows。
8. 运行定向测试和 `:idea:compileKotlin`。
9. 在真实 IDE 中分别截图 Overview、Logs、Settings，生成叠图并逐项修正。
10. 视觉验收通过后，再单独制定真实数据接入方案；不在本任务内顺手接线。

## 11. 完成标准

- 三个页面均使用 IntelliJ 原生控件和主题能力。
- 420px Darcula 下布局通过第 4.3 节全部验收项。
- mock 文案、顺序和内容与 HTML 一致。
- 不存在横向滚动、section 宽度漂移或按钮尺寸不一致。
- 定向测试与 Kotlin 编译通过。
- 三个页面均有 reference、actual、overlay 截图。
- 未新增真实状态监听、业务接口或测试专用生产注入点。

## 12. 实施结果

- `JuggControlPanel` 已改为 `JBTabbedPane`、`JBScrollPane`、`JBTextField`、`ActionLink`、`OnOffButton` 等原生组件。
- Overview、Logs、Settings 使用固定 mock 数据；预览 action 不产生业务副作用。
- 420px Darcula 下的 reference、actual 和 50% overlay 已保存到 [`assets/jugg_control_panel_layout`](assets/jugg_control_panel_layout)。
- 定向验证已通过：`JuggRunSettingsComponentTest`、`JuggPluginActionRegistrationTest`、`:idea:compileKotlin`。
- 原生字体、控件皮肤和 preferred height 按已批准决策视为软指标；最终视觉取舍以截图 Review 为准。
