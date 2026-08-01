# Jugg Control Panel 编译驾驶舱设计

> 最后核对：2026-08-01
> 状态：已实现
> 一致性规则：实现阶段发现本文与代码冲突时，以代码为准，并同步修订本文。

## 1. 文档定位

本文记录 `JuggControlPanel` Overview 页面的新版信息架构、展示口径、交互规则、数据来源、实现范围和验证策略，作为下一会话的实现依据。

本次设计只调整 Control Panel 的信息展示和必要的结构化运行摘要，不改变 Jugg 编译、部署、fallback、deploy type 或部署历史的业务语义。

## 2. 背景与问题

当前 Overview 主要展示 Run Configuration、package、selected devices、baseline 等项目上下文。这些信息大多已经能在 IDE 其他区域看到，而以下 Jugg 特有信息没有形成清晰、连续的展示：

- 当前是否有待编译文件，以及具体修改了哪些文件。
- 当前任务处于哪个阶段，已运行多久。
- 本次会话已经完成多少次编译、Hot Reload、Hot Fix 和 Install。
- 最近几次 Run 的编译、部署和总耗时。
- 最近一次 Run 实际采用的编译模式、部署策略、fallback 路径和输入文件。

新版 Overview 定位为“编译驾驶舱”，优先回答：

1. 现在正在发生什么。
2. 当前有哪些待处理变更。
3. 本次会话的 Jugg 使用结果如何。
4. 最近几次 Run 为什么快、慢、成功或失败。

## 3. 设计原则

- 当前任务是最高优先级信息，始终位于 Overview 顶部。
- 状态切换不能改变顶部区域高度，避免运行开始、结束时整个页面上下位移。
- 同一事实只展示一次；不重复展示 pending file count 或 baseline readiness。
- 保存原始运行事实，Hot Reload、Hot Fix、Install 分类只在 UI 展示和统计阶段映射。
- 使用 IntelliJ/JB 默认组件、主题颜色、按钮和交互，不引入独立视觉体系。
- 数据值和耗时使用等宽字体；颜色只用于状态表达，且必须同时提供文字或图标。
- 列表默认只占有限高度，用户可调整可见记录数，超出内容在列表内部滚动。
- 不展示无法可靠计算的百分比、剩余时间或“节省时间”。

## 4. Overview 信息架构

Overview 从上到下固定为以下五个区域：

1. 实时状态。
2. Changed Files。
3. Quick Actions。
4. This Session。
5. Recent Runs。

示意布局：

```text
┌────────────────────────────────────────────────┐
│ ● Ready for incremental compile                │
│ 7 pending files · 5 source · 2 resources       │
└────────────────────────────────────────────────┘

CHANGED FILES                              5 rows ▾
  Build   app/build.gradle.kts
  Kotlin  idea/.../JuggControlPanel.kt
  Kotlin  main/.../JuggControlPanelModel.kt
  Java    main/.../LegacyCompiler.java
  XML     app/src/main/res/layout/main.xml
  ↕ scroll

QUICK ACTIONS
[Full Gradle Build] [Restart App]       [Clean & Reinstall]
[Install Skills]     [Check Updates]     [More…]

THIS SESSION
┌──────────┬────────────┬─────────┬─────────┐
│ COMPILES │ HOT RELOAD │ HOT FIX │ INSTALL │
│    12    │      8     │    3    │    1    │
└──────────┴────────────┴─────────┴─────────┘

RECENT RUNS                                5 rows ▾
▾ 14:32  Incremental → Hot reload        1.8s  ✓
    Compile 1.8s · Deploy 2.3s · Total 4.5s
    Changed files
      Kotlin  idea/.../JuggControlPanel.kt
      Kotlin  main/.../JuggControlPanelModel.kt
      XML     idea/.../jugg_control_panel.xml

▸ 14:27  Incremental → Hot fix           2.4s  ✓
▸ 14:03  Gradle → Install               38.6s  ✓
▸ 13:58  Incremental                     3.1s  !
▸ 13:41  Incremental → Hot reload        1.6s  ✓
```

## 5. 实时状态

### 5.1 固定高度

实时状态区域固定为两行，高度建议为 52～60px。状态变化时只替换已有 label 内容，不新增、删除或展开组件。

空闲且存在待处理变更：

```text
● Ready for incremental compile
7 pending files · 5 source · 2 resources
```

运行中：

```text
● Compiling changes · Incremental                 00:18
Detect ✓  ·  Compile ●  ·  Deploy ○  ·  Launch ○
```

失败：

```text
● Last run failed
Unresolved reference: UserRepository             View logs →
```

没有变更时显示 `Up to date`。没有可用增量基线时用 `Full Gradle build required` 替换主状态，不再额外显示 `Incremental baseline ready`。

### 5.2 运行中状态

运行中按结构化事件切换以下状态：

- `Detecting changes`
- `Compiling changes`
- `Deploying changes`
- `Launching app`
- `Running instrumentation`
- `Canceling task`

右侧耗时显示当前阶段已运行时间，并使用固定宽度格式：

- 小于一小时：`00:18`、`03:42`
- 一小时及以上：`1:03:42`

Panel 使用 Swing Timer 每秒刷新耗时。Timer 只更新 label，不向 Model 写入周期事件；任务结束或 Panel dispose 时停止。

顶部只展示紧凑阶段指示，不展示每个阶段的详细耗时。完整 Compile、Deploy、Total 耗时在任务完成后进入 Recent Runs 展开态。

### 5.3 高度与历史关系

活动任务不同时出现在 Recent Runs。只有任务进入 terminal state 后，才转换为 Recent Run 并插入历史首位，顶部同时恢复为空闲、失败或无变更状态。

## 6. Changed Files

### 6.1 数据口径

Changed Files 展示 `DeployFileManager.getUndeployedFiles()` 对应的当前待处理文件。

顶部实时状态只展示一次总结：

```text
7 pending files · 5 source · 2 resources
```

Changed Files section 标题不重复显示总数。

Build file 等已知会影响增量能力的输入应反映到顶部主状态或第二行，例如：

```text
Full Gradle build required
7 pending files · includes build configuration
```

不得把这个文案扩展为 `Next run will be incremental` 等预测性结论，因为真正的 fallback 决策还依赖设备、依赖变化、文件数量和编译结果。

### 6.2 排序

文件类型严格按以下优先级排序：

1. Build files
2. Kotlin
3. Java
4. XML
5. Manifest
6. SO
7. Other

同一类型内按“模块名 + 相对路径”升序，确保列表不会因文件事件到达顺序变化而跳动。

分类优先依据 `ChangedFile.type`，不能只根据扩展名判断，否则 `AndroidManifest.xml` 会被归入普通 XML。

建议映射：

| 展示分类 | 数据语义 |
|---|---|
| Build | Gradle、properties、version catalog 等构建输入 |
| Kotlin | Kotlin source |
| Java | Java source |
| XML | 普通 Android resource XML |
| Manifest | Android Manifest |
| SO | Native library |
| Other | asset、AIDL、Compose resource 等其余类型 |

### 6.3 行展示与导航

每行展示：

- IntelliJ 文件类型图标。
- 相对项目路径。
- 必要时展示模块名，以区分 composite build 或工程外模块。
- Tooltip 中展示完整绝对路径。

单击或按 Enter 使用 IDE 原生文件导航打开目标文件。

### 6.4 高度与滚动

默认显示 5 个文件记录，超过后在 Changed Files 内部滚动。

标题右侧提供轻量可见行数选择：`3 / 5 / 8 / 10 / Auto`。Changed Files 与 Recent Runs 独立保存，默认均为 5。该选择属于项目级 IDE UI preference，不进入 Jugg 编译业务配置。

`Auto` 根据当前 Tool Window 高度分配可见区域。

## 7. Quick Actions

Quick Actions 位于 Changed Files 之后、This Session 之前。

使用三列、行数自动增长的网格：

```text
[Action 1] [Action 2] [Action 3]
[Action 4] [Action 5] [Action 6]
```

约束：

- 使用 IDEA 默认 button 样式。
- 不使用 HTML 文案。
- 不显示副标题。
- 新增按钮时直接增加网格项，每三项换行。
- 保持按钮位置稳定，不根据使用频率动态排序。
- 任务运行期间，不允许并发执行的按钮置灰。

首版按钮顺序：

1. `Full Gradle Build`
2. `Restart App`
3. `Clean & Reinstall`
4. `Install Skills`
5. `Check Updates`
6. `More…`

## 8. This Session

This Session 使用四列紧凑统计：

```text
COMPILES | HOT RELOAD | HOT FIX | INSTALL
```

四项均采用当前 `JuggManager` 生命周期内的成功结果：

- Compiles：成功完成的编译轮次。
- Hot Reload：成功的 `HOT_RELOAD`。
- Hot Fix：成功的 `HOT_FIX` 与 `COMPAT_HOT_FIX`。
- Install：成功的 `INSTALL` 与 `EMBEDDED`。

失败和取消进入 Recent Runs，但不增加成功统计。

当前设计不新增跨 IDE 重启的统计持久化。现有 `DeployHistoryData.incDeployTimes` 只有增量部署总数，无法准确反推历史 Hot Reload、Hot Fix 和 Install 分类。

## 9. DeployType 展示映射

Model、Event 和 Run Summary 必须保留原始 `JuggDeployData.DeployType`。UI 只在展示和统计阶段进行以下映射：

| 原始 DeployType | UI 展示 | This Session 分类 |
|---|---|---|
| `HOT_RELOAD` | Hot reload | Hot Reload |
| `HOT_FIX` | Hot fix | Hot Fix |
| `COMPAT_HOT_FIX` | Hot fix | Hot Fix |
| `INSTALL` | Install | Install |
| `EMBEDDED` | Install | Install |

不得把 `COMPAT_HOT_FIX` 覆写为 `HOT_FIX`，也不得把 `EMBEDDED` 覆写为 `INSTALL`。原始类型仍用于日志、诊断和后续能力扩展。

## 10. Recent Runs

### 10.1 数据范围

Recent Runs 保存当前会话最近完成的 Run，默认展示 5 个运行记录，超过后在列表内部滚动。

每条 Run Summary 至少保存：

- taskId。
- 开始与完成时间。
- 原始 compile mode。
- 原始 deploy type。
- terminal status。
- compile duration。
- deploy duration。
- total duration。
- fallback 路径。
- failure reason。
- 本次 Run 的 changed-file 输入快照。

### 10.2 收缩态

收缩态固定为一行：

```text
▸ 14:32  Incremental → Hot reload        1.8s  ✓
```

右侧耗时固定表示 compile duration，以满足快速比较最近编译耗时的需求。

### 10.3 展开态

展开态显示：

- Compile、Deploy、Total 耗时。
- fallback 路径。
- failure reason 和 `View logs →`。
- 本次 Run 的修改文件列表。

示例：

```text
▾ 14:32  Incremental → Hot reload        1.8s  ✓
    Compile 1.8s · Deploy 2.3s · Total 4.5s
    Changed files
      Kotlin  idea/.../JuggControlPanel.kt
      Kotlin  main/.../JuggControlPanelModel.kt
      XML     idea/.../jugg_control_panel.xml
```

Fallback 示例：

```text
▾ 14:18  Incremental failed → Gradle → Install   ✓
    Compile 42.3s · Deploy 12.1s · Total 58.1s
```

同一时间只展开一个 Run。切换展开项时保持其标题在可视范围内。

Recent Runs viewport 高度按“可见收缩记录数”计算。展开内容只增加 Recent Runs 内部的可滚动内容，不推动 Quick Actions、This Session 或其他 section 位移。

### 10.4 Changed-file 输入快照

Recent Run 展开的修改文件列表口径固定为：

> 本次 Run 开始时捕获的 undeployed changed files。

不混入：

- 影响传播产生的派生重编译文件。
- generated source。
- Run 开始后新增、应留给下一轮的 queued changes。

Incremental fallback 到 Gradle 时仍保留最初输入快照。失败或取消的 Run 也保留其输入快照。

文件不存在时仍显示历史路径，但导航动作置灰，并提示 `File no longer exists`。

展开的文件列表不创建第三层滚动条；其内容直接属于 Recent Runs 的滚动内容。

## 11. 滚动规则

Overview 包含以下滚动区域：

1. Changed Files。
2. Recent Runs。
3. Overview 外层。

鼠标位于内部列表时优先滚动内部列表；内部列表到达顶部或底部后，继续滚动外层 Overview，避免滚轮被困在嵌套区域。

两个内部列表的高度由可见记录数决定，不随数据量无限增长。

## 12. 删除或降级的信息

Overview 不再常驻展示：

- Run Configuration 名称。
- Package name。
- Selected devices。
- `Incremental baseline ready`。
- 正常状态下的 `Project setup is healthy`。
- 泛化的 Recent Activity。

设备名称只在正在部署或 Recent Run 需要解释目标设备时出现。健康信息只在存在用户可行动问题时显示。

以下信息不纳入本次设计：

- 估算剩余时间或百分比进度。
- “相比 Gradle 节省时间”。
- 平均耗时或成功率。
- CPU、内存和缓存大小。
- 跨 IDE 重启的 Run Summary 或分类统计持久化。

## 13. 数据与状态设计

### 13.1 Model owner

`JuggControlPanelModel` 继续作为无 Project/Swing 依赖的事实 owner，负责：

- 当前任务及阶段开始时间。
- 原始 compile mode 和 deploy type。
- 有界 Recent Run Summary。
- 原始 deploy type 的会话成功计数。
- changed-file 输入快照。

UI 不能通过英文 title、detail 字符串或日志文本推断 compile mode、deploy type、status 或 duration。

### 13.2 Controller owner

`JuggControlPanelController` 负责从真实项目对象刷新：

- 当前 undeployed changed files。
- 设置与 UI preference。
- 可行动 health 状态。

文件变化处理完成、Run 开始和 Run terminal state 后必须刷新 Context，避免 Panel 只在首次打开时看到文件数量。

### 13.3 Panel owner

`JuggControlPanel` 负责纯展示行为：

- deploy type 文案与四类会话统计映射。
- 文件类型展示排序。
- 两个列表的行数与滚动。
- Run 展开/收缩。
- Swing Timer 驱动的实时耗时 label。
- 文件导航和缺失文件提示。

## 14. 预计代码变更

### 14.1 生产代码

| 文件 | 变更职责 |
|---|---|
| `main/src/main/java/com/sickworm/intellij/jugg/ide/controlpanel/JuggEvent.kt` | 增加结构化 compile mode、原始 deploy type 和阶段事实，禁止 UI 解析文案 |
| `main/src/main/java/com/sickworm/intellij/jugg/ide/controlpanel/JuggControlPanelModel.kt` | 聚合当前阶段、会话原始统计和有界 Run Summary，保存 changed-file 输入快照 |
| `idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/JuggRunningTask.kt` | 写入真实 compile mode、deploy type、各阶段耗时、fallback 和 terminal result |
| `idea/src/main/java/com/sickworm/intellij/jugg/ide/ui/JuggControlPanelController.kt` | 提供实时 changed files、health 和 UI preference 刷新 |
| `idea/src/main/java/com/sickworm/intellij/jugg/JuggManager.kt` | 在文件状态与 Run 状态边界触发 Control Panel Context 刷新 |
| `idea/src/main/java/com/sickworm/intellij/jugg/ide/ui/JuggControlPanel.kt` | 按本文重构 Overview、固定高度状态、三列按钮、列表滚动和 Run 展开 |
| `idea/src/main/java/com/sickworm/intellij/jugg/ide/ui/MockJuggControlPanelModel.kt` | 增加 idle、running、failure、large file set、fallback 等预览场景 |

首版不新增生产类、接口、数据库实体或统计文件。新增数据结构优先作为现有 Model/Event 的嵌套类型或现有运行结果字段。

### 14.2 测试

| 文件 | 变更职责 |
|---|---|
| `main/src/test/java/com/sickworm/intellij/jugg/ide/controlpanel/JuggControlPanelModelTest.kt` | 保护原始 deploy type、会话计数、Run Summary、terminal 转换、fallback 和有界窗口 |

只有实现阶段确认存在独立且稳定的 UI 纯映射契约时，才考虑在 `idea/src/test` 增加最小测试；不得为了测试 Swing 私有组件属性创建实现细节测试或测试专用 seam。

### 14.3 文档

实现完成后同步：

- `docs/ai_knowledge/04_engineering_ide.md`：更新 Control Panel Overview 状态和数据流。
- `docs/ai_knowledge/98_code_map.md`：若新增关键入口或 owner 路径发生变化则更新；仅字段和布局变化不强制修改。
- 本文：记录最终实现与已批准设计之间的差异。

## 15. 验证策略

### 15.1 失败证据

当前代码已确认以下缺失行为：

- 实时耗时仅在 Model snapshot 更新时重算，没有每秒刷新。
- Context 主要在 Panel 打开时刷新，changed file count 可能滞后。
- Overview 只显示 changed file count，不展示文件列表。
- 结构化事件包含 duration，但没有完整 Run Summary、原始 deploy type 分类和 changed-file 输入快照。
- Current Task、Last Deploy、Recent Activity 分散展示，无法形成统一 Recent Runs 历史。

### 15.2 自动化测试价值判断

以下行为通过测试价值门禁，复用 `JuggControlPanelModelTest`（L1）：

- task terminal 后才进入 Recent Runs。
- active task 不重复进入 Recent Runs。
- 原始 deploy type 不被展示分类覆写。
- `HOT_FIX` 与 `COMPAT_HOT_FIX` 保持不同原始值。
- `INSTALL` 与 `EMBEDDED` 保持不同原始值。
- 会话成功计数只统计成功结果。
- fallback 保留原始输入文件快照。
- Recent Runs 保持有界窗口。
- 新任务替换活动任务时仍只有一个 terminal result。

文件排序、Swing 高度、按钮列数、滚动边界和展开视觉属于 UI 呈现，不通过反射或组件私有属性测试，采用替代验证。

### 15.3 定向验证

计划执行：

```bash
./gradlew :main:test --tests "com.sickworm.intellij.jugg.ide.controlpanel.JuggControlPanelModelTest"
./gradlew :idea:compileKotlin
```

不得运行无 `--tests` 过滤的全量 `:main:test` 或 `:idea:test`。

### 15.4 手工矩阵

| 场景 | 预期 |
|---|---|
| Idle，有 1～5 个文件 | 状态两行，列表无滚动或仅占配置高度 |
| Idle，超过 5 个文件 | Changed Files 内部滚动，Overview 其他 section 不位移 |
| 文件类型混合 | 按 Build/Kotlin/Java/XML/Manifest/SO/Other 排序 |
| 运行开始和结束 | 顶部状态区域高度不变化 |
| 编译超过 60 秒 | 耗时从 `00:59` 稳定切换为 `01:00` |
| Incremental + Hot Reload | 四项统计与 Recent Run 正确 |
| Hot Fix / Compat Hot Fix | UI 都显示 Hot fix，原始类型仍不同 |
| Install / Embedded | UI 都显示 Install，原始类型仍不同 |
| Incremental fallback 到 Gradle | Recent Run 展示完整路径和最终结果 |
| Run 过程中继续修改文件 | 当前 Run 文件快照不混入 queued changes |
| Recent Run 展开大量文件 | 只滚动 Recent Runs，不推动其他 section |
| 历史文件已删除 | 路径保留，导航 disabled 并提示文件不存在 |
| 3/5/8/10/Auto | 两个列表独立保存并正确计算 viewport |
| 明暗主题 | 文字、图标、边框和状态均可辨识 |
| 窄 Tool Window | 三列按钮、四列统计和单行 Run 不溢出或遮挡 |
| Dumb mode | Panel 可创建、列表可展示；不可导航时安全降级 |

## 16. 实现约束与风险

- Recent Run 的 changed-file snapshot 必须在 Run 开始边界捕获，不能在完成后读取当前 undeployed files。
- 多设备部署需要明确 deploy duration 和最终 deploy type 的聚合口径，复用现有 `JuggRunningTask` 优先级，不建立第二套规则。
- fallback 可能在同一 taskId 下发生多轮 compile；Compiles 统计按成功完成的真实编译轮次计数，Recent Runs 仍按一次用户 Run 聚合。
- Swing Timer 必须在 dispose 时停止，防止热更新后旧 Panel 持续持有监听。
- 内部列表到达滚动边界后需要把滚轮事件传递给 Overview 外层。
- 可见行数属于 UI preference，不得污染 `JuggSettings` 的编译/部署业务开关。
- 状态文案需要适配窄宽度；完整失败原因通过 Tooltip 和 Logs 保留，不能让状态区域增高换行。

## 17. 下一会话起点

下一会话从本文开始，并按以下顺序实施：

1. 先为 `JuggControlPanelModelTest` 增加 Run Summary、原始 deploy type、会话计数和 terminal 转换的失败测试。
2. 扩展 `JuggEvent` / `JuggControlPanelModel` 的结构化事实，不触碰 Swing。
3. 让 `JuggRunningTask` 生产真实 compile mode、deploy type、duration、fallback 和输入文件快照。
4. 补齐 changed files 与 Run terminal 边界的 Controller Context 刷新。
5. 最后重构 `JuggControlPanel`，完成固定高度状态、三列按钮、Changed Files 和 Recent Runs。
6. 运行定向测试、`idea:compileKotlin` 和手工 UI 矩阵。

若实现过程中需要新增数据库、生产类、公共接口或跨会话统计持久化，应停止实现并重新确认范围。
