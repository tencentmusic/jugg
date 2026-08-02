# Jugg Control Panel 数据源与 UI 更新方案

> 本文是 [`jugg_control_panel_ui_design.md`](jugg_control_panel_ui_design.md) 的数据流落地补充。视觉层级、页面结构和交互文案仍以原 UI 方案及已批准布局规格为准。

> 实施状态（2026-07-21）：IDE/MCP 首期已落地。稳定 Host、main Model、真实 Context/Settings、real/mock 同构渲染、Sync/Run/MCP 结构化事件及 Logs 过滤已接线；`cmd_line` 独立 composition root 复用留待 CLI 下沉阶段。

## 1. 目标

将当前固定 mock Panel 改为真实数据驱动，并满足以下约束：

1. Panel、Model 和业务事件可以通过 Jugg 新 ClassLoader 热更新，不因普通 UI 或字段变化要求重装插件。
2. `JuggControlPanelModel` 下沉到 `main`，IDE、CLI 和后续下沉模块共享同一套数据源与事件模型。
3. `JuggSettings`、Run Configuration、deploy history 等现有对象继续作为业务事实来源。
4. compile、deploy、sync、CLI/MCP 调用通过结构化核心事件表达，不解析或展示 raw log。
5. Overview、Timeline、Logs 使用同一 events 体系，避免多套状态机和信息不一致。
6. 接线完成后 UI 不再包含独立 mock 渲染路径；Mock Model 走与真实 Model 完全相同的订阅和渲染逻辑。

## 2. 评审结论

本轮评审对原方案作以下调整：

| 原方案 | 调整后 |
|---|---|
| DTO、Model、listener 注册到 `ide_entry` project service | `ide_entry` 只保留稳定 JComponent 宿主；Model 和 DTO 全部可热更新 |
| Tool Window 直接创建 `JuggControlPanel` | Tool Window 创建稳定 Host，真实 Panel 经 `JuggInitializer.getManager(project)` 获取 |
| `JuggControlPanelModel` 位于 IDE 层 | Model 下沉到 `main`，不依赖 `Project` 或 Swing |
| Logs tail `compile_latest.log` | Logs 只展示结构化核心 events，不读取 raw log，不轮询 |
| Current Task 使用独立任务事件 | 任务阶段合并进统一 events 体系 |
| UI 内保留固定 `MockData` | 使用 `MockJuggControlPanelModel` 产生同构 events/snapshot |
| Logs 仅页面可见时更新 | Model 始终记录事件；UI 订阅时立即获得最新快照 |

## 3. 总体架构

```text
Stable plugin ClassLoader
┌──────────────────────────────────────────────────────────────┐
│ plugin.xml                                                   │
│   -> JuggToolWindowFactory                                   │
│      -> JuggControlPanelHost                                 │
│         -> JuggInitializer.getManager(project)               │
│         -> IJuggManagerCaller.getJuggControlPanel(page)      │
│                                      : JComponent            │
└──────────────────────────────────────┬───────────────────────┘
                                       │ only JComponent + String
                                       ▼
Jugg hot-update ClassLoader
┌──────────────────────────────────────────────────────────────┐
│ JuggManager                                                  │
│   -> cached JuggControlPanel                                 │
│   -> JuggControlPanelController                              │
│   -> JuggControlPanelModel (main)                            │
│          ▲                                                   │
│          ├── sync / project context                          │
│          ├── compile events                                  │
│          ├── deploy / launch events                          │
│          └── CLI / MCP events                                │
└──────────────────────────────────────────────────────────────┘

cmd_line
┌──────────────────────────────────────────────────────────────┐
│ creates JuggControlPanelModel                                │
│   -> records the same JuggEvent                              │
│   -> consumes snapshot/events for CLI output                 │
└──────────────────────────────────────────────────────────────┘
```

核心原则：稳定 ClassLoader 不理解 Snapshot、Event、Model、Tab 或具体 Panel 类型，只挂载一个 `JComponent`。

## 4. ClassLoader 与组件桥接

### 4.1 参考 `IJuggRunSettingsComponent`

Run Settings 当前通过 `JuggInitializer.getManager(project)` 获取热更新实现，稳定层只负责 Wrapper。Control Panel 使用同一模式，但跨 ClassLoader 只暴露基础类型：

```kotlin
interface IJuggManagerCaller : Disposable {
    // Existing APIs...

    fun getJuggControlPanel(page: String): JComponent
}
```

这里只增加一次稳定接口：

- 返回类型固定为 IntelliJ 父 ClassLoader 中的 `JComponent`。
- page 使用稳定 String，例如 `overview`、`logs`、`settings`。
- 不在 `ide_entry` 暴露 `JuggControlPanelModel`、Snapshot、Event、listener 或 page enum。
- 后续增加字段、事件类型、过滤器、Tab 内部结构时，无需更新 `ide_entry`。

### 4.2 稳定 Host

新增或收敛一个薄 `JuggControlPanelHost`，职责与 `JuggRunSettingsComponentWrapper` 相同：

1. Tool Window Factory 创建 Host，而不是直接创建真实 Panel。
2. Host 调用 `JuggInitializer.getManager(project)`。
3. Host 调用 `manager.getJuggControlPanel(page)` 获取真实 `JComponent`。
4. Host 用返回组件替换 initializing placeholder。
5. 每次打开 Tool Window 时 refresh；如果 JuggManager 已经换 ClassLoader，替换旧组件。
6. `JuggManager.dispose()` 委托 Controller clear Host；Host refresh 时也会替换旧组件，避免稳定 ClassLoader 持有旧热更新组件。

真实 `JuggControlPanel` 由 `JuggControlPanelController` 创建并缓存。重复切换 Tab 或打开 Tool Window 不重新创建 Panel，也不重复订阅 Model。

### 4.3 `ide_entry` 固定边界

本次必要的稳定层变更限定为：

```text
idea/src/ide_entry/java/com/sickworm/intellij/jugg/ide/IJuggManagerCaller.kt
idea/src/ide_entry/java/com/sickworm/intellij/jugg/ide/JuggControlPanelHost.kt
idea/src/ide_entry/java/com/sickworm/intellij/jugg/ide/ui/OpenJuggControlPanelAction.kt
idea/src/ide_entry/resources/META-INF/plugin.xml
```

`JuggToolWindowFactory`、`OpenJuggControlPanelAction` 只操作 Host / `JuggInitializer`，不直接引用 `JuggControlPanel`。`JuggInitializer` 不引用 Host，释放动作由 Manager 生命周期驱动。

## 5. `JuggControlPanelModel` 下沉到 main

### 5.1 定位

`JuggControlPanelModel` 是项目/命令执行期间的结构化状态与核心事件容器，不是 Swing ViewModel：

- 位于 `main` 模块。
- 不依赖 `Project`、Swing、ToolWindow、RunManager 或 IDE service。
- IDE 的 `JuggManager` 创建一份 Model。
- CLI 的 composition root 创建自己的 Model。
- 编译、部署等下沉到 `main` 后仍可直接记录事件，不需要反向依赖 IDEA 模块。

实际路径：

```text
main/src/main/java/com/sickworm/intellij/jugg/ide/controlpanel/
├── JuggControlPanelModel.kt
└── JuggEvent.kt
```

包名归入 `ide.controlpanel` 业务域，但不进入稳定层精确包 `com.sickworm.intellij.jugg.ide`，因此仍由 Jugg ClassLoader 热更新。虽然类名保留 `JuggControlPanelModel`，实现保持 UI 无关，CLI 可以直接调用 `snapshot()` 或 `subscribe()`。

### 5.2 所有权

Model 不注册为 IntelliJ project service：

- IDE：由 `JuggControlPanelController` 持有，与当前 Jugg ClassLoader 同生命周期；`JuggManager` 只做薄委托。
- CLI：由一次命令会话或 CLI runtime 持有。
- 测试：直接创建真实 Model 或 `MockJuggControlPanelModel`。
- JuggManager dispose 后，Model、Panel 和 listener 一起被释放，不会被稳定 ClassLoader 引用。

### 5.3 main 模块接入方式

不让所有 leaf class 都依赖 Model。只在掌握完整业务语义的上层边界记录事件：

| 事件 | 推荐生产边界 |
|---|---|
| Sync | IDE `JuggControlPanelController.recordSyncEvent()` |
| 文件变化摘要 | 文件变化进入 `DeployFileManager` 后的上层调用点 |
| Compile | `JuggCompilerHelper` / 后续下沉的 compile orchestration |
| Deploy | `JuggRunningTask`、`JuggDeployerHelper` 或后续统一 deploy orchestration |
| App launch/restart | `JuggControlPanelController` 或 deploy/run 顶层流程 |
| CLI | `cmd_line` command execution boundary |
| MCP | `McpToolInvoker` / `CompileJobManager` |

使用正常构造注入把同一个具体 `JuggControlPanelModel` 传给上述 orchestration 对象。Leaf compiler、parser、ADB helper 继续使用 `JuggLogger` 输出技术诊断，不把每条内部日志转为事件。

Model 是 concrete class，不为只有一个实现且只有一个方法的 recorder 新增接口。

为了避免事件生产者引入大量类型，公共入口只保留 `JuggEvent` 与 `JuggControlPanelModel`：Source、Category、Phase、Status、Level 嵌套在 `JuggEvent`，Context、Settings、Snapshot 等投影类型嵌套在 Model。

## 6. 统一 Events 体系

### 6.1 Event 定义

Events 只记录用户能理解、能用于判断流程和结果的核心事件：

```kotlin
data class JuggEvent(
    val id: Long,
    val taskId: String?,
    val source: Source,
    val category: Category,
    val phase: Phase?,
    val status: Status,
    val level: Level,
    val title: String,
    val detail: String?,
    val timestamp: Long,
    val durationMillis: Long? = null,
)
```

建议枚举：

```text
Source: IDE / CLI / MCP
Category: SYNC / COMPILE / DEPLOY / APP / CLI / MCP
Status: STARTED / SUCCEEDED / FAILED / CANCELED / WARNING / SKIPPED
Level: INFO / WARN / ERROR
Phase: PREPARING / DETECTING_CHANGES / COMPILING / DEPLOYING /
       LAUNCHING / RESUMING / INSTRUMENTING / COMPLETED
```

Event 必须是已经整理过的语义信息：

- title 简短描述发生了什么，例如 `Incremental compile completed`。
- detail 提供一层必要结果，例如 `2 Kotlin · 1 resource · 860ms`。
- 不保存完整 stack trace、Gradle stdout、ADB raw output 或任意技术日志。
- 失败事件提供用户下一步，例如 `Run a full Gradle build to recover`。
- 技术细节仍进入 `JuggLogger`、Run Tool Window 和 report logs。
- MCP 统一记录 `MCP request` 与 `MCP response`，tool 名称、projectDir 和结果摘要放在 detail。

### 6.2 Snapshot

Model 以 Events 和最新上下文事实生成不可变 Snapshot：

```kotlin
data class JuggControlPanelModel.Snapshot(
    val context: JuggControlPanelModel.Context,
    val currentTask: JuggControlPanelModel.TaskSnapshot?,
    val lastDeploy: JuggControlPanelModel.DeploySummary?,
    val healthItems: List<JuggControlPanelModel.HealthItem>,
    val recentEvents: List<JuggEvent>,
    val settings: JuggControlPanelModel.Settings,
    val version: Long,
)
```

Model 同时提供两类更新：

- `updateContext(...)` / `updateSettings(...)`：更新当前事实。
- `record(event)`：追加核心事件并归约 Current Task、Timeline、Last Deploy 和 Recent Activity。

约束：

- recentEvents 固定保留最近 200 条。
- 同一 taskId 的 events 按 timestamp / id 排序。
- active task 只允许一个；新任务替换旧任务时先为旧任务记录 CANCELED。
- version 只在有效状态变化时递增。
- Snapshot 只包含 String、enum、Boolean、Long 和不可变集合，不保存运行时对象。

## 7. 数据源映射

### 7.1 Context 与 Health

| UI 字段 | 真实来源 | 更新时机 |
|---|---|---|
| Run Configuration | `RunManager` 当前 Jugg configuration | Panel 打开、Run 开始、配置变更后 |
| build target / variant | `JuggGradleCompileOptions`、`CompileContextManager` | 初始化、Sync、Run 开始 |
| package | `IDeployTargetManager.getPackageNameOrNull()` | full build/recover、Run 开始 |
| selected devices | `IDeployTargetManager.getDeviceNameList()` | Panel 打开、Run 开始、部署结束 |
| changed files | `DeployFileManager.getUndeployedFiles()` | 文件变化、编译结束 |
| baseline ready | `IDeployHistoryManager.hasBeenFullCompiled` | 初始化恢复、full build、reset |
| history available | `IDeployHistoryManager.isRecoverFeatureAvailable` | 初始化、项目目录变化 |
| settings | `JuggSettings` | Panel 创建、设置改变 |

设备选择暂无统一跨版本事件时，Panel 打开和动作执行前调用热更新层 controller 刷新一次 Context；不在 UI 中启动轮询。

### 7.2 Overview、Timeline 与 Recent Activity

三者不再分别维护数据：

- Current Task：从唯一 active task 的 events 归约。
- Timeline：从当前或最近 taskId 的 events 按阶段排列。
- Last Deploy：从最近一个 DEPLOY 终态事件归约。
- Recent Activity：直接展示最近核心 events 的精简视图。
- Project Health：由 Context facts 和特定 WARNING/FAILED events 共同生成。

### 7.3 Logs 改为 Events

Logs 页面保留现有名称，但语义改为“核心事件流”，不再等同 raw log viewer：

```text
JuggControlPanelModel.recentEvents
  -> source/category/level/task/search filter
  -> event list model
  -> structured event rows
```

事件行建议展示：

```text
14:32:08  COMPILE  Success
Incremental compile completed
2 Kotlin · 1 resource · 860ms
```

过滤映射：

- Deploy：COMPILE、DEPLOY、APP，按 taskId 聚合。
- Runtime：SYNC、COMPILE、DEPLOY、APP 等 IDE 核心事件。
- CLI / MCP：source 为 CLI/MCP 或 category 为 CLI/MCP。
- Level 继续使用现有布局，过滤 Event Level：All / Info / Warning / Error。
- 任务结果由每行 Status 展示，Status 不与 Level 混为同一字段。
- Current task 直接按 taskId 过滤，不根据日志时间窗猜测。

Model 在 UI 是否可见时都持续记录事件，因此：

- 不需要读取 `compile_latest.log`。
- 不需要文件 watcher、tail offset、轮转处理或轮询线程。
- Tool Window 随时打开都能立即看到最近事件。
- CLI 可以输出同一事件流。
- 事件可读性和任务关联由生产边界保证，不依赖日志 tag 或文本解析。

完整技术日志仍通过 `Open full logs` 打开 `compile_latest.log` 或 Run Tool Window，不在 Panel 中复制 raw log 能力。

## 8. 任务事件并入 Events

任务不再定义独立于 Logs 的事件类型。一次 run 使用一个 taskId，按真实编排节点记录 events：

```text
task started
  -> PREPARING / STARTED
  -> DETECTING_CHANGES / STARTED|SUCCEEDED
  -> COMPILING / STARTED|SUCCEEDED|FAILED|CANCELED
  -> DEPLOYING / STARTED|SUCCEEDED|FAILED|SKIPPED
  -> LAUNCHING|RESUMING|INSTRUMENTING / terminal status
  -> COMPLETED / terminal status
```

关键接线位置：

1. `JuggRunningTask.run()` 开始：创建 taskId 和 PREPARING event。
2. compile orchestration 前后：记录 Detect/Compile event、文件统计、compile type 和耗时。
3. 每台设备部署前后：记录 device detail；多设备任务最终再记录聚合 event。
4. launch/resume/instrument：记录对应 APP event。
5. `compileUiHandler.onEnd()` 前：写入任务终态。
6. catch/finally：保证异常和取消都有且只有一个终态。
7. CLI/MCP 触发时保留同一 taskId，并设置正确 source。

Current Task 卡片和 Timeline 都是同一 events 的不同视图，不再通过 `ProgressIndicator` 或日志文本维护第二套状态。

## 9. UI 订阅与更新

### 9.1 真实 Model

真实 `JuggControlPanel` 与 Model 位于同一热更新 ClassLoader，可以直接使用完整类型：

```text
JuggControlPanelModel.subscribe(listener)
  -> immediately emit latest snapshot
  -> emit on every effective model update
  -> return AutoCloseable subscription
```

- 不需要把 listener 接口放进 `ide_entry`。
- Model 可以从任意线程 record event。
- Panel 把 Snapshot 切换到 EDT 后再更新 Swing。
- 普通核心事件频率低，可以即时刷新；批量 compile/device events 可在 50–100ms 内合并到最新 Snapshot。
- Panel dispose 时关闭 subscription。

### 9.2 Mock Model

删除 `JuggControlPanel.kt` 内的 `MockData`。新增 `MockJuggControlPanelModel`：

- 组合一份真实 `JuggControlPanelModel`，不要求生产 Model 为测试开放继承。
- 只通过 `updateContext()`、`updateSettings()`、`record(event)` 构造评审场景。
- 不提供 mock 专用 UI builder、字段或渲染分支。
- 支持 Ready、Running、Failed、No Device、No Baseline、Empty Events 等场景。
- Developer/Test 模式可以在 real model 和 `mock.model` 之间切换，Panel 重新订阅后走同一 render(snapshot)。
- 切回 real model 时立即显示真实 Model 已积累的最新状态和 events。

这保证 mock 视觉测试验证的是正式数据绑定路径，而不是另一套静态页面。

### 9.3 页面可见性

Model 与 Panel 是否可见无关：

- Sync、compile、deploy、CLI/MCP events 始终记录。
- Overview、Logs、Settings 订阅同一个 Snapshot。
- Tool Window 打开时立即获得最新 Context、当前任务和 recentEvents。
- Tool Window 隐藏时没有 EDT 渲染成本，但不会丢失核心事件。
- 不启动日志轮询、文件 watcher 或页面可见性 I/O。

### 9.4 局部渲染

- Overview 更新 label、icon、button enabled 和 Timeline rows。
- Logs 使用原生 Swing 行容器展示结构化事件，并支持来源、级别、当前任务和搜索过滤。
- Settings 使用 `isRenderingSettings` guard，避免 Model 回写触发第二次命令。
- Current Task elapsed 在 snapshot 渲染时按 startedAt 计算，不要求 Model 产生高频计时事件。
- 状态同时使用 icon 和文本，不能只依赖颜色。

## 10. Settings 与业务命令

真实 Panel、Controller 和 JuggManager 位于同一热更新 ClassLoader，不再为命令桥接增加 `ide_entry` 接口。

建议由 `JuggManager` 创建 `JuggControlPanelController`，负责：

- 项目级持有 Model、Panel 实例与 Sync taskId。
- 从现有对象刷新 Context 与 Settings。
- 复用 Full Gradle Build、Restart、Clean & Reinstall、Reset、Report、Skills、Update 等业务入口。
- 复用 `MoreOptionsManager` 中带确认和附加副作用的设置逻辑。
- 将动作结果记录为结构化 JuggEvent。
- 把真实 Model 和 Controller 传给 `JuggControlPanel`。
- 在 Manager dispose 时清空稳定 Host；`JuggInitializer` 不感知 UI Host。

首期启用的设置仍以现有 `JuggSettings` 为事实来源：

| Panel 设置 | 事实来源 |
|---|---|
| Confirm fallback when no files changed | `JuggSettings.isConfirmFallbackWhenNoFileChanges` |
| Always restart app after deployment | `JuggSettings.isAlwaysRestartAppAfterDeployment` |
| Quick deploy | `JuggSettings.isEnableDirectOverlayDeploy` |
| Auto fallback after deploy failure | `JuggSettings.isAutoFallbackToGradleWhenDeployError` |
| Embed changes into APK | `JuggSettings.isEmbeddedToApk` |
| Use project Kotlin compiler | `JuggSettings.isUseProjectKotlinCompiler` |
| Backup classpath | `JuggSettings.isEnableBackupClasspath` |

没有真实后端的设置或动作继续隐藏/disabled，不保留无副作用 mock 控件。

## 11. 并发、容量与生命周期

- Model 使用不可变 Snapshot 和同步归约，确保 event 顺序、active task 和唯一终态一致。
- recentEvents 固定上限 200 条，防止长期 IDE/CLI 会话无限增长。
- 生产线程 record event 后不等待 EDT。
- Panel 最多在 50–100ms 内合并一批 Snapshot 更新。
- Model 不保存 Project、IDevice、File、Logger、Throwable 或 Swing 对象。
- JuggManager dispose 时调用 Controller clear；Panel subscription 随 Manager 的 Disposer 子节点释放。
- Stable Host 在 Controller clear/refresh 时移除或替换旧 JComponent，避免旧 ClassLoader 泄漏。
- CLI 命令结束时可以直接读取最终 Snapshot，无需 UI 或 Project。

## 12. 空态与失败态

| 场景 | UI 行为 |
|---|---|
| JuggManager 尚未初始化 | Stable Host 显示 `Jugg is initializing`，打开时重试获取组件 |
| 热更新 manager 已切换 | Host refresh 后替换为新 manager 返回的 JComponent |
| 没有 Run Configuration | Context 给出 Edit/Create 入口，运行动作 disabled |
| 没有设备 | Restart/Clean & Reinstall disabled，Health 显示原因 |
| 没有 baseline | 提示 Full Gradle Build，不能显示 Hot reload available |
| 任务运行中 | 从 active task events 显示阶段、耗时和 Stop |
| 任务失败 | Timeline 停在失败 event，展示可执行下一步 |
| 没有 events | Logs 展示 `No Jugg activity yet`，不显示固定示例日志 |
| Mock 测试模式 | 明确显示 Mock/Test 标记，渲染路径与真实 Model 相同 |

## 13. 预计代码改动

| 文件 | 改动 |
|---|---|
| `idea/src/ide_entry/java/.../ide/IJuggManagerCaller.kt` | 一次性增加 `getJuggControlPanel(page): JComponent` |
| `idea/src/ide_entry/java/.../ide/JuggControlPanelHost.kt` | 稳定 Wrapper，挂载 manager 返回的 JComponent |
| `idea/src/ide_entry/resources/META-INF/plugin.xml` | Tool Window 指向稳定 Factory/Host |
| `idea/src/main/java/.../ide/ui/JuggToolWindowFactory.kt` | 退化为稳定 Host 创建逻辑，不引用真实 Panel 类型 |
| `idea/src/ide_entry/java/.../ide/ui/OpenJuggControlPanelAction.kt` | 稳定 Action，只通过 Host 打开，不引用真实 Panel 类型 |
| `main/src/main/java/.../ide/controlpanel/JuggEvent.kt` | 核心结构化事件与嵌套 enum |
| `main/src/main/java/.../ide/controlpanel/JuggControlPanelModel.kt` | 嵌套 facts/snapshot、events 归约、订阅和容量控制 |
| `idea/src/main/java/.../JuggManager.kt` | 创建 Controller，向稳定接口薄委托 JComponent 与生命周期 |
| `idea/src/main/java/.../ide/logic/JuggRunningTask.kt` | 记录统一 task events 与终态 |
| `idea/src/main/java/.../ide/ui/JuggControlPanel.kt` | 移除 MockData，仅 render Snapshot |
| `idea/src/main/java/.../ide/ui/JuggControlPanelController.kt` | 持有 Model/Panel，刷新 Context/Settings，编排事件和业务动作 |
| `idea/src/main/java/.../ide/ui/MockJuggControlPanelModel.kt` | 通过真实 event API 构造可切换测试场景 |
| `cmd_line/src/main/java/...` | 后续创建/消费同一 Model，不新增独立事件协议 |

公共类和复杂核心方法必须添加英文介绍性注释。

## 14. TDD 执行清单

本文件仅为设计方案，不修改业务代码。后续 feature 实现必须先完成以下失败测试：

| 测试路径 | 层级 | 首个失败行为 |
|---|---|---|
| `main/src/test/java/com/sickworm/intellij/jugg/ide/controlpanel/JuggControlPanelModelTest.kt` | L1 | event 归约、唯一终态、200 条上限、active task、snapshot version |
| `idea/src/test/java/com/sickworm/intellij/jugg/ide/logic/JuggRunSettingsComponentTest.kt` | L2 | Stable Host 挂载 manager JComponent；real/mock Model 切换走同一 UI 更新路径 |
| `idea/src/test/java/com/sickworm/intellij/jugg/ide/logic/JuggRunningTaskTest.kt` | L2 | compile/deploy/success/failure/cancel 记录正确 task events |
| `idea/src/test/java/com/sickworm/intellij/jugg/ide/logic/JuggPluginActionRegistrationTest.kt` | L2 | Tool Window/Action 仍注册到稳定 Host，不注册 Model service |
| `idea/src/test/java/com/sickworm/intellij/jugg/manager/TopLevelFlowTest.kt#testDeploy` | L3 | 真实 demo 流程产生完整 compile/deploy/app events 和唯一终态 |
| `cmd_line/src/test/java/com/sickworm/intellij/jugg/cmdline/CmdLineTest.kt` | L2 | CLI 接入阶段复用同一 Model/Event，不依赖 IDE Project |

Model event 归约属于确定性数据变换，可按 `06_testing.md §2` 放在 L1。不要新建只用 Mockito 验证单个 Helper 调用次数的测试。

定向验证示例：

```bash
./gradlew :main:test --tests "com.sickworm.intellij.jugg.ide.controlpanel.JuggControlPanelModelTest"
./gradlew :idea:test --tests "com.sickworm.intellij.jugg.ide.logic.JuggRunSettingsComponentTest"
./gradlew :idea:test --tests "com.sickworm.intellij.jugg.ide.logic.JuggRunningTaskTest"
./gradlew :idea:test --tests "com.sickworm.intellij.jugg.ide.logic.JuggPluginActionRegistrationTest"
./gradlew :idea:test --tests "com.sickworm.intellij.jugg.manager.TopLevelFlowTest.testDeploy"
./gradlew :cmd_line:test --tests "com.sickworm.intellij.jugg.cmdline.CmdLineTest"
./gradlew :idea:compileKotlin
```

禁止无 `--tests` 过滤的全量 `:main:test` / `:idea:test`。

## 15. 分阶段实现

### Phase 1：稳定 Host 与 main Model

1. 为 `IJuggManagerCaller` 增加唯一稳定 JComponent 获取方法。
2. Tool Window 改为 Stable Host，真实 Panel 由 Controller 创建、JuggManager 薄委托返回。
3. 在 `main` 实现 Model、Event、嵌套 Snapshot 和 L1 测试。
4. JuggControlPanelController 创建并持有真实 Model。

验收：后续改变真实 Panel/Model 不再更新 `ide_entry`；manager 热更新后 Host 可替换组件。

### Phase 2：真实 Context、Settings 与 Mock Model

1. 接入真实 Context/Health/Settings facts。
2. 移除 `MockData` 和无后端控件。
3. 增加 `MockJuggControlPanelModel` 场景及 real/mock 切换。
4. 接入已有业务动作，并把核心结果记录为 events。

验收：正式和 mock 场景使用同一 Snapshot、订阅和 render 逻辑。

### Phase 3：统一任务 Events

1. Sync、compile、deploy、app、CLI/MCP 记录结构化 events。
2. Current Task、Timeline、Last Deploy、Recent Activity 从 events 归约。
3. Logs 改为可过滤的核心事件流。
4. 完成至少一条 `TopLevelFlowTest` L3 回归。

验收：Panel 无 raw log 依赖；隐藏期间不丢事件；CLI 可复用同一数据源。

## 16. 验收标准

- `ide_entry` 只暴露 `JComponent` 和稳定 page String，不暴露 Model/Event/DTO。
- `JuggControlPanelModel` 位于 `main` 且不依赖 IDE/Swing 对象。
- Model 不注册 IntelliJ project service，由 JuggControlPanelController/CLI 自己持有。
- Tool Window 通过 `JuggInitializer.getManager(project)` 获取真实 Panel。
- Overview、Timeline、Logs 使用同一结构化 events 体系。
- Logs 不读取 raw log、不轮询文件、不处理日志轮转。
- 接线完成后不存在 UI 内置 MockData。
- Mock Model 和真实 Model 使用相同订阅与渲染路径，并可切换。
- Panel 打开时立即获得最新 facts、active task 和 recentEvents。
- JuggManager 热更新释放后，Stable Host 不持有旧组件。
- 定向 L1/L2/L3 测试与编译验证全部通过。

## 17. 非目标

- 不把 raw `compile_latest.log` 内容搬进 Panel。
- 不让 leaf compiler/deployer 为每条内部日志生成 Event。
- 不迁移 Run Configuration 参数到项目级 Settings。
- 不新增 Panel 专用数据库或跨 IDE 重启事件历史。
- 不为没有真实业务语义的 mock 按钮增加空实现。
- 不在本方案中删除现有 Run Tool Window 或完整日志文件。
