# Jugg Control Panel UI 方案

## 1. 背景与目标

当前 Jugg 的入口分散在三处：

- Run Configuration：保存当前运行配置的编译、APK、远程编译等参数。
- More Options：同时承载全局运行开关、维护工具、设备兼容开关、技能安装和测试入口。
- IDE 顶部工具栏：独立提供 Restart App、Fallback to Gradle Compile 等高频操作。

随着操作和诊断信息增加，下拉菜单无法承载状态、说明、日志和任务进度，也容易把“当前 Run Configuration 参数”与“项目级 Jugg 设置”混在一起。

本方案新增项目级 `Jugg Running Pannel` Tool Window，作为统一控制台，目标是：

1. 高频操作在一个固定入口内完成。
2. 用户随时知道当前配置、目标设备、运行阶段和最近结果。
3. 设置按作用域和风险分组，详细设置可查找。
4. 部署日志、Jugg 主日志、CLI/MCP 调用可以检索和关联。
5. 保留 Run Configuration 对单次运行参数的所有权，避免配置语义迁移错误。

## 2. 设计结论

### 2.1 产品形态

新增项目级 Tool Window：

- 名称：`Jugg Running Pannel`
- icon：复用 Jugg Run Configuration icon
- 固定设计基线：IDE 右侧停靠；首期只维护这一套布局
- 默认宽度：`420px`；允许用户在约 `360–520px` 范围内调整
- 一级内容页：`Overview`、`Logs`、`Settings`

不建议新增独立弹窗作为主入口。弹窗适合确认和短流程，不适合长期观察状态、检索日志或频繁切换设置。

### 2.2 作用域划分

| 内容 | 保留位置 | 原因 |
|---|---|---|
| Compile command、Output APK、Android Test、远程编译连接与同步参数 | Run Configuration | 属于某个运行配置，可随配置切换 |
| 当前 Run Configuration 摘要与编辑入口 | Control Panel / Overview | 只展示并跳转，不重复维护值 |
| fallback、部署、编译器、classpath 等项目级开关 | Control Panel / Settings | 全局行为应有稳定、可搜索入口 |
| Restart、Gradle Build、Clean & Reinstall、Reset Jugg | Control Panel / Overview | 高频动作需要显示目标、状态与结果 |
| 部署历史、Jugg 日志、CLI/MCP 调用 | Control Panel / Overview + Logs | 需要连续上下文和筛选能力 |

Run Configuration 最终只保留名为 `More options` 的兼容入口，点击后直接定位 Control Panel 的 Settings，避免长期维护两套设置 UI。

## 3. 信息架构

```text
Jugg Running Pannel Tool Window
├── Overview
│   ├── Context Header
│   ├── Current Task
│   ├── Quick Actions
│   ├── Deploy Timeline
│   ├── Project Health
│   └── Recent Activity
├── Logs
│   ├── Deploy
│   ├── Jugg Runtime
│   └── CLI / MCP
└── Settings
    ├── Run Behavior
    ├── Deployment
    ├── Compiler
    ├── Device Compatibility
    ├── Integrations
    └── Advanced / Developer
```

一级页只保留三个，降低切换成本。History 不单独成为一级页：近期记录在 Overview 展示，完整记录在 Logs 中按任务筛选。

## 4. Overview 设计

### 4.1 右侧面板线框

```text
┌─────────────────────────────────────┐
│ Jugg                     ● Ready  ⟳ │
├─────────────────────────────────────┤
│ run Jugg                       Edit │
│ app · debug · Pixel 8 API 35        │
│ 3 changed files · Hot reload ready  │
├─────────────────────────────────────┤
│ CURRENT TASK                        │
│ Idle                                │
│ Last deploy 14:32 · 1.8s · Success  │
├─────────────────────────────────────┤
│ QUICK ACTIONS                       │
│ [Full Gradle Build] [Restart App]   │
│ [Clean & Reinstall]  [More ▾]       │
├─────────────────────────────────────┤
│ LAST DEPLOY                         │
│ ✓ Detect changes             120ms  │
│ ✓ Compile                    860ms  │
│ ✓ Deploy                     540ms  │
│ ✓ Resume app                 210ms  │
│             View related logs →     │
├─────────────────────────────────────┤
│ PROJECT HEALTH                      │
│ ⚠ Gradle project info is outdated   │
│   Sync project                       │
├─────────────────────────────────────┤
│ RECENT ACTIVITY                     │
│ 14:32 Deploy · Hot reload · 1.8s    │
│ 14:28 CLI restart · Success         │
│ 14:20 Gradle build · Failed         │
└─────────────────────────────────────┘
```

### 4.2 Context Header

固定展示动作将影响的对象，解决“按钮很多，但不知道会作用到哪个配置/设备”的问题：

- 当前 Jugg Run Configuration 名称。
- module / variant / package 摘要。
- 当前选中设备；多设备时显示数量并可展开。
- changed files 数量。
- 当前可用部署策略：Hot reload、Code swap、Full install 或需要 Gradle fallback。
- `Edit` 跳转到当前 Run Configuration。

如果缺少配置、设备或项目上下文，直接在 Header 给出可执行修复入口，不让用户点击动作后才看到错误。

### 4.3 Current Task

运行时替换静态状态卡：

- 阶段：Preparing / Compiling / Deploying / Launching / Waiting for app / Completed。
- 当前阶段说明，复用现有 progress indicator 文本。
- 已耗时。
- `Stop`，仅任务可取消时显示。
- CLI/MCP 发起的任务标记来源，例如 `Started by CLI`。

动作点击后应在 100ms 内切换到 pending/running 状态；长任务持续显示进度，避免只依赖 Run Tool Window。

### 4.4 Quick Actions

首屏只显示 3 个主要动作和一个 More 菜单：

| 动作 | 行为 | 展示规则 |
|---|---|---|
| `Full Gradle Build` | 复用现有强制 Gradle 构建及后续安装/启动链路 | 无任务运行时可用 |
| `Restart App` | 重启当前目标设备上的 App | 有可用设备与 package 时可用 |
| `Clean & Reinstall` | 清 App 数据并重装 | 明确提示会清除 App 数据 |
| `More` | 低频、维护和高风险操作 | 始终固定在同一位置 |

`More` 内建议包含：

- `Gradle Clean`：执行 Gradle clean，只清理 Gradle 构建产物。该能力与现有 Full Gradle Build、Clean & Reinstall、Reset Jugg 均不同，落地前必须确定独立后端语义。
- `Reset Jugg Cache`：对应现有 Clean and reset Jugg，明确说明会删除 `build/jugg` 并重新打开项目。
- `Reinitialize Jugg`：重新读取项目状态并初始化，不伪造 Gradle 已编译状态。
- `Open Jugg Directory`。
- `Report Issue`。
- `Check for Updates`。
- `Install Jugg Skills`。

以下动作不能统一命名为 `Clean`：

| 动作 | 影响范围 |
|---|---|
| Gradle Clean | Gradle build outputs |
| Clean & Reinstall | 设备 App 数据和安装包 |
| Reset Jugg Cache | `build/jugg` 缓存、数据库和日志 |

高风险动作使用具体动词和影响描述。只有不可逆或明显破坏当前状态的操作弹确认框，普通成功结果使用面板内反馈或 notification。

### 4.5 Deploy Timeline

这是面板的核心识别元素，用统一时间线表达一次任务：

```text
Detect changes → Compile → Deploy → Launch / Resume / Instrument
```

每个阶段展示：

- 成功、运行中、跳过、warning、失败状态。
- 耗时。
- 关键结果，例如 `12 Kotlin / 3 Java`、`Code swap`、`2 devices`。
- 点击阶段直接打开 Logs，并自动带入 task id、时间范围和 class tag 过滤。

失败时在失败阶段下方显示“发生了什么”和“下一步”，技术堆栈留在日志页。例如：

```text
Deploy failed on Pixel 8
The device rejected code swap. Run a full Gradle build to recover.
[Full Gradle Build] [View details]
```

### 4.6 Project Health

只展示用户当前可以处理的问题，不做常驻仪表盘堆砌：

- 没有可恢复的 Gradle/full build baseline。
- Gradle project info 过期。
- 没有连接设备或选中设备。
- 当前设备被强制使用 compat deploy。
- deploy history 不可用。
- 远程编译配置不完整。
- Jugg 有可用更新。

健康项全部正常时折叠为一行 `Project is ready`，不占用首屏空间。

## 5. Logs 设计

### 5.1 日志类型

日志页使用二级 segmented tabs：

1. `Deploy`：按一次 compile/deploy task 聚合，适合看用户主链路。
2. `Runtime`：读取 `build/jugg/log/compile_latest.log`，保留完整 ClassName、level 和 timestamp。
3. `CLI / MCP`：展示外部调用记录，包括 CLI/MCP tool、开始时间、耗时、结果和关联 task。

MVP 不要求拆成三份物理日志。可以复用同一主日志，通过 `[ClassName]`、`[MCP][TOOL]`、task 时间窗和结构化事件做逻辑视图。

### 5.2 顶部工具条

为了适配 `420px` 右侧面板，工具条使用两行紧凑布局：

1. 第一行：`Deploy / Runtime / CLI-MCP` segmented tabs。
2. 第二行：搜索框、Level、Task、Follow 和 overflow。

overflow 内包含：

- Device：多设备场景筛选。
- `Clear view`：只清当前视图，不删除日志文件。
- `Open file`：使用 IDE editor 打开真实日志文件。
- `Export diagnostics`：导出相关日志和项目摘要，用于上报问题。

日志正文使用等宽字体、tabular timestamp，搜索命中只改变背景，不同时改变字号、字重和边框。

### 5.3 CLI / MCP 调用记录

每条调用建议展示：

```text
14:28:11  restart       CLI    success   420ms
14:20:03  gradle-build  MCP    failed    38.2s
```

展开后显示：

- tool name。
- caller：CLI / Codex / Claude Code / Gemini / unknown；只有能够可靠识别时才展示具体客户端。
- projectDir。
- 脱敏后的 arguments。
- result summary。
- 关联的 compile/deploy task id 与日志入口。

必须脱敏 SSH password、token、环境变量中的 secret、Authorization header 等字段。默认不展示完整 JSON-RPC payload。

### 5.4 空态和错误态

- 无日志：`No Jugg task has run in this project yet.`，提供 `Run Jugg` 或 `Full Gradle Build`。
- 主日志不存在：说明 `compile_latest.log` 是 best-effort 链接，并尝试打开最新的 `compile_*.log`。
- 日志文件轮转：保持当前 task 的已加载内容，并提示可切换到新日志。

## 6. Settings 设计

### 6.1 页面结构

设置页顶部提供搜索，下方按分组连续纵向滚动。使用平台原生设置组件，不增加左侧分类栏，也不自定义卡片式 Dashboard。

#### Run Behavior

- Confirm fallback when no file changes。
- Always restart app after deployment。

#### Deployment

- Enable quick deploy / direct overlay。
- Auto fallback to Gradle when deploy fails。
- Embed changes into APK for RemoteViews。

#### Compiler

- Use project Kotlin compiler。
- Backup classpath。
- 仅确有用户调节价值的编译设置；内部常量不暴露。

#### Device Compatibility

- 按已连接设备列出 compat deploy 状态。
- 每项展示设备名、serial 简写、触发原因或手动强制状态。

#### Integrations

- Custom server URL。
- Install Jugg Skills。
- CLI 安装状态和版本。
- Check for updates。

#### Advanced / Developer

- 普通状态只显示 Developer Mode 和 Reset settings。
- Test Mock Events 在 Developer Mode 开启后出现。
- `Mark as project synced...`、`Mark as gradle compiled...` 不进入普通用户设置。
- 高风险或仅排障使用的开关必须有说明和 Reset to default。

### 6.2 设置项表达

- label 描述用户能控制的行为，不使用内部实现名。
- switch 下方可以有一行说明和影响，不把说明塞进 tooltip。
- 依赖当前设备或功能开关的设置在条件不满足时 disabled，并说明原因。
- 修改立即保存时，在控件附近反馈；需要重启项目或下次 full build 生效时明确标记。
- 不提供任意布局和按钮顺序自定义，保留稳定的空间记忆。

## 7. Run Configuration 与现有入口调整

### 7.1 Run Configuration

保留：

- Compile command。
- Output APK name/path。
- Remote compile 与全部远程同步参数。
- Incremental Android Test。

调整：

- 移除 More Options 下拉，保留 `More options` 链接并直接打开 Control Panel 的 Settings。
- `Report issues` 移到 Control Panel；Run Configuration 中可保留链接一个版本作为过渡。
- 对 Remote Compile Options 做折叠和字段校验属于后续独立优化，不与本面板首期强绑定。

### 7.2 顶部工具栏按钮

迁移分两阶段：

1. 首期保留 Restart App 和 Gradle Compile，Tool Window 上线后观察使用习惯。
2. 稳定后默认只保留一个 `Open Jugg` toolbar action；Restart / Gradle Build 继续可以通过 action search 和用户自定义 toolbar 添加。

不建议首期直接删除现有按钮，避免高频用户工作流突变。

## 8. 视觉与交互规范

### 8.1 视觉方向

方向：`Android Studio 原生技术控制台`。

- 使用 IntelliJ Platform 主题色、字体、icon、focus 和 disabled 状态。
- 表面层级以 border 和 background tint 为主，不使用卡片阴影。
- 4px 基础间距：4 / 8 / 12 / 16 / 24。
- 圆角跟随平台组件，不创建独立大圆角设计语言。
- 颜色只用于 action、success、warning、failure、running。
- 时间、耗时、task id、serial 使用等宽或 tabular number。

### 8.2 状态表达

不能只依赖颜色：

- Success：check icon + `Success`。
- Warning：warning icon + 原因。
- Failed：error icon + `Failed`。
- Running：progress indicator + 当前阶段文本。
- Disabled action：保留 label，通过 tooltip 或下方说明解释前置条件。

### 8.3 响应式布局

- 默认宽度为 `420px`，Overview 始终使用单列纵向信息流。
- 宽度接近下限时，Quick Actions 仍保持两列；Context Header 元信息允许换行。
- Logs 不使用任务列表 + 日志详情 split view，任务通过顶部 Task 筛选器选择。
- Settings 始终为搜索框 + 纵向设置分组。
- IDE 负责记忆用户调整后的右侧面板宽度。

## 9. 实现建议

### 9.1 组件边界

建议新增：

- `JuggToolWindowFactory`：注册和创建 Tool Window。
- `JuggControlPanel`：Overview / Logs / Settings 页面容器。
- `JuggControlPanelModel`：面向 UI 的项目状态快照。
- `JuggTaskEvent` / `JuggTaskSnapshot`：统一描述编译、部署和 CLI/MCP 任务阶段。
- `JuggLogViewModel`：日志 tail、过滤、task 时间窗和文件轮转。

公共类和复杂核心方法按项目规范添加英文介绍性注释。

### 9.2 复用与解耦

- 不从 UI 模拟点击现有 `AnAction`。
- 将 MoreOptionsManager、RestartAppAction、GradleCompileAction 背后的业务动作收敛到可复用的项目级 action/service，再由 Tool Window 和 AnAction 调用。
- 设置继续使用 `JuggSettings` 作为事实来源，首期不迁移持久化 key。
- Run Configuration 参数继续使用 `JuggRunConfigurationOptions`。
- compile/deploy 进度优先订阅现有 task/progress 状态；不要为 Overview 再启动一套任务状态机。
- CLI/MCP 日志优先在 `McpToolInvoker` 边界补充结构化、脱敏后的调用事件，并关联 compile job。

### 9.3 性能要求

- Tool Window 未打开时不持续解析大日志。
- 打开 Logs 后增量 tail，避免定时全文件重读。
- UI 更新合并到合理频率，日志高频写入不能逐行阻塞 EDT。
- 大日志只保留有限内存窗口，完整内容由 `Open file` 交给 IDE editor。

## 10. 分阶段落地

### Phase 1：统一入口 MVP

- 注册 Jugg Tool Window。
- Overview Context Header、Current Task、Quick Actions。
- Settings 迁移 More Options 中的正常用户设置。
- Logs 展示 `compile_latest.log`，支持搜索、level、follow、open file。
- Run Configuration 的 `More options` 改为 Settings 深链，保留旧入口名称兼容用户习惯。

验收重点：用户不再需要进入 Run Configuration 才能执行维护操作或修改全局设置。

### Phase 2：任务时间线与调用关联

- Deploy Timeline。
- Recent Activity。
- CLI/MCP 调用列表与脱敏参数。
- task id、时间窗、日志筛选关联。
- Project Health 和可执行修复建议。

### Phase 3：诊断与入口收敛

- Export diagnostics / Report Issue 联动。
- 多设备日志筛选。
- 失败任务一键恢复建议。
- 评估移除默认顶部 Restart / Gradle Compile 按钮。
- 清理旧 More Options 内容，只保留兼容跳转。

## 11. 后续实现测试与验证清单

本文件仅为 docs 方案，不修改生产代码。后续 feature 实现需先按 TDD 写失败测试，再实现：

| 验证目标 | 建议层级 | 建议落点 |
|---|---|---|
| action enablement、任务阶段、错误恢复建议等多类协作 | L2 | 复用或扩展 `idea/src/test/.../ide/logic` 下现有协作测试，避免为每个 UI Helper 新建单文件 Mockito 测试 |
| Tool Window action 触发后复用真实 Jugg 编排入口 | L2 | `idea/src/test/.../manager/JuggControlPanelFlowTest.kt`，覆盖 UI command → manager/service → result state |
| 未改变 Run → compile → deploy 语义 | L3 | 回归 `idea/src/test/.../manager/TopLevelFlowTest` 中至少一条真实部署场景 |
| plugin.xml Tool Window / action 注册 | L2 | 追加到现有 `JuggPluginActionRegistrationTest` 或同类注册测试，不重复建测试类 |
| 日志过滤与轮转若抽成复杂确定性解析 | L1 | 仅满足 `06_testing.md §2` 协议/日志解析白名单时新增或追加测试 |

定向验证示例：

```bash
./gradlew :idea:test --tests "com.sickworm.intellij.jugg.ide.logic.JuggPluginActionRegistrationTest"
./gradlew :idea:test --tests "com.sickworm.intellij.jugg.manager.JuggControlPanelFlowTest"
./gradlew :idea:test --tests "com.sickworm.intellij.jugg.manager.TopLevelFlowTest"
./gradlew :idea:compileKotlin
```

禁止无 `--tests` 过滤的全量 `:idea:test`。

## 12. 关键产品决策

1. Control Panel 是项目级控制台，不取代 Run Configuration。
2. Overview 首屏最多三个主动作，更多能力通过固定 More 菜单渐进展示。
3. `Gradle Clean`、`Clean & Reinstall`、`Reset Jugg Cache` 必须是三个独立语义。
4. 日志先做逻辑视图，不为 UI 盲目拆分物理日志文件。
5. CLI/MCP 调用必须脱敏，并与 compile/deploy task 关联。
6. 首期保留现有工具栏和 More Options，稳定后再收敛入口。
7. 视觉遵循 IntelliJ Platform，时间线是唯一重点表达，避免把 Tool Window 设计成独立 Dashboard 产品。

## 13. HTML 布局评审稿

交互式评审文件：[`jugg_control_panel_layout_review.html`](jugg_control_panel_layout_review.html)。

原生控件布局对齐的实施和验收口径见 [`jugg_control_panel_native_layout_alignment_plan.md`](jugg_control_panel_native_layout_alignment_plan.md)。实现使用 IntelliJ Platform 原生控件；HTML 负责约束布局、内容、顺序、间距和信息层级，不要求复制 CSS 控件皮肤。

- 只保留已确认的右侧停靠方案，默认视觉宽度为 `420px`。
- Overview 的模块顺序固定为 Context、Current Task、Quick Actions、Timeline、Project Health、Recent Activity。
- Logs 使用日志类型 segmented tabs、搜索、Level、Task、Follow 和 overflow，不使用左右 split view。
- Settings 使用搜索框和纵向分组，完整覆盖 Run Behavior、Deployment、Compiler、Device Compatibility、Integrations、Advanced。
- More 菜单完整展示维护动作；快捷动作、页面切换、设置开关和日志筛选可交互。

### 13.1 文档与原型一致性规则

后续实现以本 Markdown 为功能与交互规格，以 HTML 为布局与信息层级验收基线。控件皮肤、主题色、字体渲染、focus 和 disabled 状态以 IntelliJ Platform 为准。两者的模块结构和控件名称必须保持一致：

| 区域 | 固定顺序 / 内容 |
|---|---|
| 一级导航 | Overview、Logs、Settings |
| Overview | Context Header → Current Task → Quick Actions → Last Deploy → Project Health → Recent Activity |
| Quick Actions | Full Gradle Build、Restart App、Clean & Reinstall、More |
| Logs | Deploy、Runtime、CLI / MCP；Search、Level、Task、Follow、overflow |
| Settings | Run Behavior、Deployment、Compiler、Device Compatibility、Integrations、Advanced |

如果后续评审修改 HTML，必须在同一 commit 同步本节；如果修改本节中的固定结构或控件名，也必须同步 HTML。
