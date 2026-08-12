# 工程化：IDE 插件层

> 最后核对：2026-08-18
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 文档定位

本页说明 IDE 侧如何启动 Jugg、维护 project / compile / deploy 上下文，以及 Run / androidTest / MCP / 工具入口如何进入统一任务编排。

本页不展开编译阶段、部署状态机、MCP tool schema、hook 脚本细节；分别见 `02_compile_core.md`、`03_deploy_complete.md`、`08_mcp_design.md`、`08_cli_tools_list.md`。

---

## 2. 核心源码索引

| 类/接口 | 文件 | 作用 |
|---|---|---|
| `JuggInitializer` | `idea/src/ide_entry/java/com/sickworm/intellij/jugg/loader/JuggInitializer.kt` | 项目级插件实例注册、释放、Sync 事件转发、MCP local server 生命周期 |
| `JuggLoader` | `idea/src/ide_entry/java/com/sickworm/intellij/jugg/loader/JuggLoader.kt` | 隔离加载 Jugg manager，支持热更新/embedded jars fallback |
| `JuggManagerCreator` | `idea/src/ide_entry/java/com/sickworm/intellij/jugg/loader/JuggManagerCreator.kt` | 设置 `PlatformApi.impl`、注册项目日志、创建/释放 `JuggManager` |
| `JuggManager` | `idea/src/main/java/com/sickworm/intellij/jugg/JuggManager.kt` | IDEA 项目协调入口，注入 IDEA runtime metadata，负责配置刷新、历史恢复、Compile Context 关联、monitor 接线、Run/UI/MCP 和资源释放；文件变化与 control plane 已委托共享 manager |
| `FileChangeManager` / `IdeaFileChangeMonitor` | `main/.../project/change/FileChangeManager.kt`, `idea/.../project/change/IdeaFileChangeMonitor.kt` | 共享 changed/delete/build-file/Git/pending barrier 处理；IDEA 侧仅将 VFS 事件适配到 monitor 契约 |
| `CompileUiHandler` / `JuggCompileUiHandler` | `main/.../compiler/CompileUiHandler.kt`, `idea/.../compiler/JuggCompileUiHandler.kt` | 编译流程的 Host 交互边界；IDEA 复用现有 dependency dialog，manager 只应用确认结果 |
| `HostTaskExecutor` | `idea/src/main/java/com/sickworm/intellij/jugg/runtime/HostTaskExecutor.kt` | `TaskRunnerManager` 的 IDEA 执行适配，关联 `Task.Backgroundable`、ProgressIndicator 与 EDT 状态 |
| `DeployStateManager` / `IdeaHostDeployStateResolver` | `main/.../deploy/DeployStateManager.kt`, `idea/.../deploy/IdeaHostDeployStateResolver.kt` | 共享部署状态计算；隔离 Android Studio 设备状态读取 |
| `JuggRunningTask` | `idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/JuggRunningTask.kt` | Run 按钮后的后台任务，串联编译、部署、状态回写、Run tool window |
| `JuggDebugProgramRunner` / `JuggDebugSessionManager` | `idea/src/ide_entry/java/com/sickworm/intellij/jugg/ide/JuggDebugProgramRunner.kt`, `idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/JuggDebugSessionManager.kt` | 接管 Jugg + Debug executor，让 Debug 按钮可用；Jugg 编译/部署输出挂到 Run tool window，部署成功后限制单设备并通过兼容层 attach Java debugger |
| `JuggConfigurationRunner` | `idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/JuggConfigurationRunner.kt` | 创建并运行 `JuggRunningTask`，维护是否正在编译和下一轮强制重装 |
| `RemoteCommandRunner` / `RemoteCommandDialog` | `idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/RemoteCommandRunner.kt`, `idea/src/main/java/com/sickworm/intellij/jugg/ide/ui/RemoteCommandDialog.kt` | 使用当前选中的远程 Jugg Configuration 执行非交互命令，并在独立 Run Content 中流式展示输出 |
| `JuggCompilerHelper` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/JuggCompilerHelper.kt` | IDEA 与 standalone 共享的增量/Gradle 回退判定与 compile 入口 |
| `JuggDeployerHelper` / `IdeaDeployEnvironment` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployerHelper.kt`, `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/IdeaDeployEnvironment.kt` | 共享 Helper 选择部署路径，IDEA Host 环境提供设备、ADB、prompt、debugger、AndroidTest UI。 |
| `JuggControlPanelHost` | `idea/src/ide_entry/java/com/sickworm/intellij/jugg/ide/JuggControlPanelHost.kt` | 稳定 ClassLoader 中只持有 `JComponent` 的 Tool Window 宿主；通过 `JuggInitializer.getManager(project)` 获取热更新实现 |
| `JuggControlPanelModel` / `JuggEvent` | `main/src/main/java/com/sickworm/intellij/jugg/ide/controlpanel/` | 无 Project/Swing 依赖的项目 facts、任务状态和结构化核心事件；只公开两个入口类，投影与枚举使用嵌套类型，供 IDE、MCP 与后续 CLI 复用 |
| `JuggControlPanelController` | `idea/src/main/java/com/sickworm/intellij/jugg/ide/ui/JuggControlPanelController.kt` | 热更新层项目级持有 Model/Panel，刷新 IDE facts、编排 Sync/App events 与 Panel 动作，并在 Manager dispose 时 clear 稳定 Host |
| `CompileContextManager` / `IProjectModelSource` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/context/CompileContextManager.kt`, `main/src/main/java/com/sickworm/intellij/jugg/project/info/ProjectModelSource.kt` | 共享 effective project model 与 Compile Context 生命周期 |
| `IdeaProjectModelSource` | `idea/src/main/java/com/sickworm/intellij/jugg/compiler/context/IdeaProjectModelSource.kt` | IDEA module/JDK/source root 读取，以及 IDE + Gradle project info merge 输入 |
| `IdeaCompileEnvironmentSource` | `idea/src/main/java/com/sickworm/intellij/jugg/compiler/context/IdeaCompileEnvironmentSource.kt` | 在 Compile Context 创建或本地 Gradle fetch 执行时读取当前 Android SDK 与 Gradle 环境 |
| `IdeaCliRunConfigurationManager` | `idea/src/main/java/com/sickworm/intellij/jugg/project/runtime/IdeaCliRunConfigurationManager.kt` | 将 IDEA Jugg Run Configuration 导入共享 CLI 配置集合，维护稳定 id、当前指针和 Gradle 成功后的实际配置 |
| `MoreOptionsManager` | `idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/MoreOptionsManager.kt` | More Options 菜单，挂载 Gradle compile、restart、skills、report 等操作 |
| `JuggControlPanel` / `JuggToolWindowFactory` | `idea/src/main/java/com/sickworm/intellij/jugg/ide/ui/` | 仅在存在有效 Jugg Run Configuration 时创建 `Jugg Running Pannel` 右侧 Tool Window；Overview / Logs / Settings 使用单一面板实例，Run Configuration 的 `More options` 直接定位 Settings |

---

## 3. 核心状态模型

| 状态 | 所属对象 | 生命周期 |
|---|---|---|
| `instanceSet` | `JuggInitializer` | 以 project basePath 为 key 保存 `JuggLoader`；最后一个项目释放时停止 `McpLocalServer` |
| `JuggPathManager` | `JuggManagerCreator` / `JuggManager` | 项目级 `build/jugg` 路径、日志、数据库、classpath、MCP fetch cache 的根 |
| `CompileContext` | `CompileContextManager` | Gradle/project info 更新后重建；被 compiler、deploy file manager、自定义编译器消费 |
| effective project model | `CompileContextManager` | source model 与 module custom classpath 合并后的内存模型；当前不额外持久化 identity 状态 |
| deploy history / deploy state | `DeployHistoryManager` / `DeployStateManager` | full build 后初始化，增量部署成功后 commit；启动时可从历史恢复 |
| hasRun / selected devices | `JuggRunningTaskStatusManager` | 决定“首次运行”、stop/cancel 后是否重置，以及 hook/status 语义 |
| run UI process handler | `CompileUiHandler` / `JuggRunningTask` | 承载日志、进度、取消状态；androidTest 时接入 Test Results console |
| file change / Run Configuration locks | `JuggManager` | 文件变化处理与 Run Configuration 创建分别串行，禁止通过 `JuggManager` 实例锁跨业务域互相阻塞 |
| control panel snapshot | `JuggControlPanelModel` | `JuggControlPanelController` 项目级持有；保存待处理文件、当前阶段、原始 compile/deploy 事实、会话成功统计、有界 Recent Runs 与最近 200 条核心事件；MCP、Sync、App 事件只进入事件历史，不覆盖运行任务 |
| CLI run configuration collection | `CliRunConfigurationStore` / `IdeaCliRunConfigurationManager` | `build/jugg/config/run_configurations/<id>.json` 保存独立配置，`current_run_configuration.json` 保存当前 UUID；IDEA 配置自身持久化同一稳定 id |

---

## 4. 核心调用链路

### 4.1 插件初始化与项目上下文恢复

```text
IDE project opened
  -> JuggInitializer.init(project)
     创建 JuggLoader，注册到 instanceSet，并启动 McpLocalServer
  -> JuggManagerCreator.create()
     设置 IdeaPlatformApi，创建 JuggPathManager，注册 JuggLogger
  -> JuggManager.init()
     创建 IDEA RuntimeInfo，再由 Init Jugg 后台任务首次转换并迁移旧 PropertiesComponent 字段，失败时下次启动重试，然后显式初始化 Host-neutral JuggServer；settings 在首次访问时自动加载
     通过 ProjectCustomConfigManager 刷新 custom config，初始化 AsDeployerCompat、min api、project info 与历史目录；导入已有 Jugg Run Configuration，缺失时由 Gradle project info 生成确定性默认配置
  -> JuggManager.recoverDeployContext()
     从 deploy history 恢复 compile context、APK、changed files，避免无必要全量构建
  -> background tasks
     预初始化 deployment service、检查更新；CLI/skills auto update 在全局锁内执行，MCP fetch cleanup 保持普通后台任务
```

`recoverDeployContext()` 只在 deploy history 有可恢复信息时生效；没有历史时应提示先跑 Gradle/full compile，而不是强行构造增量上下文。

插件热更新依赖一条刻意收窄的 ClassLoader 边界。`JuggLoader` 根据 load list 选择 embedded 或 hot-update jars，并用代理把 `IJuggManagerCreator` / `IJuggManagerCaller` 调用跨回稳定 ClassLoader；创建热更新实例失败时直接回退 embedded jars，保证项目仍能打开。`loader`、`ide`、IntelliJ API 以及少量跨边界 DTO 固定由原 ClassLoader 加载，避免 IDE 已注册 extension/action 的 class identity 改变；`JuggManagerCreator` 例外由热更新 ClassLoader 加载，使主要业务实现能够替换。

更新下载采用“热加载 + 标准安装”双通道：只下载缺失 jar，逐个校验 md5，文件齐全后通过临时文件替换 metadata；兼容热更新时再切换 load list，使之后新打开或重新打开的工程使用新 ClassLoader。无论能否热更新，都会把 jars 打成插件 zip 并调用 `PluginInstaller.installAfterRestart()`，确保下次 IDE 启动落到标准安装版本；若服务端标记必须 reinstall，则不更新 load list，只走冷安装。热更新因此不是在当前 manager 上替换 class，也不能让稳定边界中新增加的方法或类型自动生效。

Compile Context 消费方当前由 `JuggManager` 按 `DeployFileManager → JuggCompiler → FileChangesHandler → FileChangeManager/GitFileChangesDetector → CustomCompilerManager` 顺序关联。`JuggManager.dispose()` 关闭本地 Gradle project info executor，并释放 custom compiler classloader、deploy file runtime、TaskRunner 与 coroutine scope。

`CompileContextManager` 与 `GradleProjectInfoLocalFetchManager` 已下沉 `main`。IDEA 通过 `IdeaProjectModelSource` 提供 host model，通过 `IdeaCompileEnvironmentSource` 按使用时读取 Android SDK 与 Gradle 环境；本地 Gradle project info fetch 继续使用共享 `TaskRunnerManager` 保留项目锁、后台任务和进度语义，不再持有 IDEA `Project`。

`DeployFileManager` 可在构造期直接创建 `ConstRefEngine` 对象，但 `ConstRefEngine` 构造期不能初始化 SQLite database、repo fingerprint store 或 impact resolver，避免全局 SQLite 缓存损坏阻断 manager 创建。这些 ConstRef runtime 资源由 `ConstRefEngine` 在 `updateModuleInfos()`、源码变更事件、编译前 readiness、on-demand 分析、影响查询或 commit ack 首次需要时懒初始化；失败后降级为 no-op，主初始化、编译和部署继续。

`FileChangesHandler` 在 `CompileContext` 初始化后，以 IDE 工程目录和所有参与编译模块的根目录作为目录扫描范围。目录事件在调用 `listFiles()` 前先判断是否与该范围存在祖先或子孙关系；无关的全局目录不会递归展开，工程目录外的编译模块仍可沿其父目录分支被发现。每个模块都会用本地 `ModuleInfo.projectRootDir/moduleRootDir` 与 `buildDirRelativePath` 还原实际 build directory，并把它和传统 `${moduleRootDir}/build` 作为统一排除边界；不能直接使用远程 compile context 中可能已映射到 classpath 备份目录的 `buildPathInfo.buildDir`。目录事件在递归前剪枝，普通 changed file 在类型识别前过滤。删除事件只负责移除此前已登记的路径，不重复执行该过滤。该边界不依赖 build directory 是否位于 module root 内，也不会回溯清理当前内存中已有的变化。

### 4.2 Gradle Sync 到上下文重建

```text
JuggGradleSyncListener
  -> JuggInitializer.onSyncEvent(project, syncEvent)
  -> JuggManager.onSyncEvent()
     SUCCEEDED: updateProjectInfo(isAfterSync = true)，reconcile Active Build Variant CLI run configuration
     SKIPPED: updateProjectInfo(isAfterSync = false)，reconcile Active Build Variant CLI run configuration
     STARTED/FAILED: 通知 dependencyChangeManager
  -> CompileContextManager.updateCompileContext()
  -> IdeaProjectModelSource + JuggProjectInfoMerger
  -> GradleProjectInfoLocalFetchManager.runUpdateIfNeeded()
  -> JuggManager.rebindCompileContext()
     更新 DeployFileManager、JuggCompiler、FileChangesHandler、FileChangeManager/GitFileChangesDetector、CustomCompilerManager
```

Sync 成功会重置 hasRun，避免旧运行状态让“无文件变化”判断污染下一轮。

Sync 完成或被 IDE 标记为 `SKIPPED` 后，先更新 effective `JuggProjectInfo`，再由 `IdeaCliRunConfigurationManager.reconcileActiveBuildVariants()` 为每个 application module 补齐当前 `buildVariant` 对应的 Jugg Configuration。候选配置由 `CliRunConfigurationGenerator.generateForModule()` 确定性生成；同 module + variant 已存在时完全保留名称、command、APK output、远端参数和环境变量。当前 selected Configuration 不是 Jugg 时只创建、不改变选择和 CLI current pointer；当前选择是 Jugg 时，只切换到同 module 的 active variant 并更新 pointer。该流程不依赖已废弃的 `SuggestRunConfiguration`，也不导入普通 Android Run Configuration。

建议配置的 APK output pattern 从 Android Studio Android model 的实际 build folder 生成，支持 `${moduleDir}/build` 和项目根集中式 `build/${moduleName}`。该路径只用于创建新的 Jugg Configuration；Sync 不修改已有配置的 APK output pattern。

Composite build 的自动生成身份沿用 project info 中的 `moduleStdPath + buildVariant`，Gradle task 和稳定 UUID 均由该身份生成；已有配置优先按解析后的 module/variant 复用，无法解析时按 `moduleName + variant` best-effort 匹配。

IDEA VFS 事件由 `IdeaFileChangeMonitor` 转成 changed/delete 批次后交给 `FileChangeManager`。共享 manager 使用 Runtime 实例内锁串行更新 deploy file 和 dependency 状态，不占用 project write lock；`DeployStateManager.beginFileProcessing/endFileProcessing` 保证编译不会抢在事件落库前开始。Git checkout/pull 的补偿检测也位于 `main`。compile-on-save 的设置读取与最终编译调用暂留 `JuggManager`，共享 manager 只返回本批次是否存在有效变化。

### 4.3 Run 到编译部署

```text
JuggRunConfiguration / JuggAndroidTestRunConfiguration
  -> JuggManager.runTask(options, executor, runProfile, androidTestRunSpec)
  -> JuggConfigurationRunner.runTask()
  -> JuggRunningTask.run()
     dependency start、Run tool window 状态、JuggLogger listener、server report、结构化 task event
  -> JuggCompilerHelper.compile()
     可能走增量，也可能 fallback 到 Gradle
  -> JuggDeployerHelper.deploy()
     多设备逐个部署，汇总 deploy type 和失败可回退性
  -> compileUiHandler.onEnd()
     回写 hasRun、停止日志监听、更新 UI
```

一次 Run 使用唯一 taskId。Compile、每台设备 Deploy、fallback、取消、异常和聚合终态都进入同一个 events 体系；`JuggControlPanelModel` 只接受一个终态，Current Task、Timeline、Last Deploy、Recent Activity 和 Logs 不维护第二套任务状态。

androidTest 运行必须把 `androidTestRunSpec`、`executor`、`runProfile` 一起传入 `JuggManager.runTask()`，否则 Test Results console、source navigation、rerun failed 不能完整接入。

Debug executor 仅支持普通 Jugg RunConfiguration，不接管 androidTest。Debug 仍先复用 Jugg 的编译与部署主链路；`JuggManager.runTask()` 会把该入口标记为 `isAlwaysRestartApp=true` 与 `isDebugRun=true`，确保部署后以 `am start -D -S` 重启 App，让启动阶段等待 debugger，再由兼容层请求 Android Studio 原生 attach flow 创建/激活 `XDebugSession`。Debug attach 的完整状态模型、AS 内部 API 边界与断点不可用排查见 `04_engineering_debug_attach.md`。

---

## 5. UI 与工具入口

- 默认 Run 配置由 `CliRunConfigurationGenerator` 基于 Gradle project info 推断，不再使用 `SuggestRunConfiguration`；优先 `app` application module，否则按稳定排序选择，variant 使用当前 `buildVariant`，缺失时为 `debug`。IDEA 只导入 Jugg Run Configuration，选择/修改事件在项目锁内更新共享配置或指针。
- IDEA Runtime 的 CLI/MCP Gradle 调用优先当前选中的 Jugg Run Configuration；未选中 Jugg 时按最近成功 full build 的 command + target、command、列表首项依次回退。Gradle build 成功且 APK 已确认后回写本轮实际 task、APK pattern 和远端字段。
- More Options 统一从 `JuggManager.getMoreOptions()` 进入 `MoreOptionsManager`，挂载 Gradle compile、restart app、skill/install、report issue 等操作。
- `Jugg Running Pannel` 的稳定层只创建 `JuggControlPanelHost`；Host 经 `IJuggManagerCaller.getJuggControlPanel(page): JComponent` 挂载当前 Jugg ClassLoader 创建的真实 Panel。Model、Snapshot、Event、Controller 和具体 Panel 类型都不进入 `ide_entry` 桥接接口，后续字段与 UI 变更可通过新 ClassLoader 生效。
- `OpenJuggControlPanelAction` 位于 `ide_entry`，只调用 Host；`JuggInitializer` 不引用 Host。Manager dispose 委托 Controller clear Host，JuggManager 自身不保存 Panel、事件枚举或 Sync taskId。
- Overview 作为编译驾驶舱，固定展示 Run Status、Changed Files、按 Build / Device / Jugg Plugin 分组的 Quick Actions、This Session 和 Recent Runs。Run 开始时捕获 undeployed 输入快照，terminal 后才进入 Recent Runs；原始 compile mode、deploy type、terminal category、fallback 与各阶段耗时由结构化事件传递，Panel 只负责展示映射。Recent Runs 每行固定展示编译模式、最终结果、总耗时和状态，其中 compile-only、编译失败、部署失败与无设备分别使用明确结果文本，成功部署展示实际 deploy type；选中后再展示 Compile / Deploy / Total 分阶段详情。Changed Files 与 Recent Runs 使用 IDE 原生可选列表，Changed Files 双击打开文件；运行耗时由 Swing Timer 每秒刷新且不写回 Model。
- Logs 只展示 sync、compile、deploy、app、CLI/MCP 等结构化核心事件，使用来源和级别下拉框、当前任务与 Follow 复选框及搜索框过滤，不读取或轮询 `compile_latest.log`；日志列表支持多选和平台复制快捷键。
- MCP lifecycle 固定记录 `MCP request` / `MCP response`，tool 与结果摘要进入 detail。
- Model 保留 Run Configuration、selected devices、package、changed files、baseline 与 deploy history 等 Context/Health 数据，Overview 不展示 context 摘要；Settings 使用原生分组、复选框和文字 action，七个开关直接读写 `JuggSettings`。无真实后端的预览设置和动作不显示。
- Overview Quick Actions 按 Build、Device、Jugg Plugin 分组；`Clear app data` 复用通用确认弹窗，确认后才执行清除 App 数据、完整 Gradle 构建和重装。`Clear Jugg Build` 保留既有清理 Jugg 项目构建数据并重新初始化项目的行为。
- Build Quick Actions 最下方的 `Exec remote CMD` 只接受当前选中的远程 Jugg Configuration，不使用 full build history 或首个配置兜底。对话框固定展示 SSH target 与 `remoteProjectPath`，命令为空时只禁用 Run，不显示校验错误；支持从该目标最近 10 条命令中选择并回填，历史由 `JuggSettings` 按 `user + host + port + remoteProjectPath` 隔离。执行创建独立 `Jugg Remote Command` Run Content、专用 ProcessHandler 与 SSH client，不进入 `JuggConfigurationRunner` / `JuggRunningTask`；Stop 只取消本次命令，并在后台确认取消后以非零状态结束 Run Content。
- `MockJuggControlPanelModel` 只通过真实 Model API 构造测试场景；Panel 在 real/mock model 之间切换时复用同一个订阅和 render 路径，不保留 UI 内置 `MockData`。
- `JuggToolWindowFactory` 与 `OpenJuggControlPanelAction` 均实现 `DumbAware`；Panel 不依赖索引，IDE 处于 indexing / dumb mode 时仍可创建和打开。
- Run Configuration 保留 `More options` 名称，点击后激活 `Jugg Running Pannel` 并选中 Settings；Settings 包含持久化的 compat deploy 开关，切换后同步更新 deployer API 下限并清理已下发的 Jugg JVMTI agent；Tools 菜单的独立 action 仍从 Overview 打开。
- `Check Jugg Update` 独立 action 经 `JuggManager.checkUpdates()` 复用 `MoreOptionsManager.checkUpdates()`，行为与 More Options 中的更新检查一致；从 Run Configuration 触发更新时，执行 `Reopen IDE` / `Reopen projects` 前会先关闭更新弹窗和外层 Run Configuration，避免模态窗口阻塞 reopen。
- hot update 的下载、MD5 校验、embedded jar 同步、metadata/load manifest 发布和过期清理由共享 `JuggHotUpdateManager` 完成；`IdeaHotUpdateCoordinator` 保留 IDEA 定时检查、频控、notification、plugin install/restart 与 reopen project。`JuggHotUpdateBootstrap` 在 Loader 创建 hot-update classloader 前无锁只读 manifest，其跨 classloader API 仅暴露 JDK 平台类型，禁止返回 hot-update Runtime DTO。
- `Set custom server URL` 的 dialog 留在 `MoreOptionsManager`，输入结果通过不产生任务事件上报的全局锁后台任务交给 `JuggServer` 写共享 settings；`Clean and reset Jugg` 保持原有直接删除项目状态并 reopen project 的行为。
- `Install Jugg Skills` 由 `InstallJuggSkillsDialog` 触发 `JuggSkillInstaller`，会安装内置 skills、CLI、hooks；安装 CLI 或 hooks 前先检测 Python 3.7+（`python3` 优先，`python` 回退），未满足时不写入 CLI 或 hook 配置。成功安装 Claude hooks 且检测到 CC Switch 配置目录时，安装结果关闭后会提示用户导出 Common Config JSON，不提供单独的 CC Switch 安装选项，也不直接修改 CC Switch 配置。选择 Codex skill 时额外通过 `CodexPermissionRuleInstaller` 写入 Codex home（优先 `CODEX_HOME`，否则 `~/.codex`）下 `rules/default.rules` 的 Jugg CLI `prefix_rule`，避免 Jugg 本地端口探测反复触发提权确认，并在安装日志记录 rules file、prefix 与 installed/already_installed/fail 状态；安装完成后导出 `~/.jugg/skills/install/agent_setup.md`。hook 与 CLI 细节以 `docs/skills` 和 `08_cli_tools_list.md` 为准。
- 内置 standalone Bundle 每次安装都会替换当前 active runtime，不限制版本降级或 channel 切换，并为 `~/.jugg/bin/jugg.py` 写入可执行权限；安装进程失败时保留进程输出并弹出 `Install Failed` 错误窗口。
- CLI/MCP/RPC 在 EDT 上读取 IDE 当前选择项、Jugg configuration 列表和配置 options。优先使用当前选中的 Jugg configuration；选择项不可用或不是 Jugg configuration 时，先按最近一次成功 Gradle full build 的 `compileCommand + buildTarget` 完全匹配，再按 `compileCommand` 完全匹配，最后回退到列表中的首个 Jugg 配置。同层存在多个匹配项时使用该层首项；最终首项兜底会打印 `warn`，同时以精简 `debug` 日志记录 selected、full build、resolution source 与 chosen configuration。运行时会创建对应 Run content，但默认不激活 Run tool window；失败等需要用户注意的场景才显式 show。
- `reportIssue()` 在准备诊断数据和上传期间使用模态进度窗口；生成经过脱敏的白名单诊断候选项后，确认窗口说明运行环境日志已脱敏并用于问题分析，只展示 IDEA `log/` 和 standalone `log/standlone_cli/` 按修改时间合并后的最近 10 个 Jugg 日志文件的路径和 KB/MB 大小，默认全选，并将 Jugg 日志置顶且锁定选择。上传的 standalone 日志保留 `diagnostics/logs/standlone_cli/` 层级。上传按钮显示 `Upload logs`；选择仅保存时切换为 `Create Diagnostics Bundle`，生成后由系统文件管理器选中 ZIP。Report ID 保持为 8 位小写十六进制。上传固定提交到 `https://jugg.sickworm.com/report_issue`，不展示或持久化上传地址；结果页不展示临时 ZIP 路径。`build/jugg/tmp/diagnostics` 中达到 7 天的文件在项目启动后的延迟清理时机单独清理。

---

## 6. 排查入口

| 现象 | 优先入口 |
|---|---|
| 插件初始化后没有 manager | `JuggInitializer.instanceSet`、`JuggLoader`、`JuggManagerCreator.create()` |
| 启动期长时间卡住 | `09_plugin_runtime_debug.md`，再看 `JuggManager.init()` background task 和 `ConstRefEngine` 启动扫描 |
| 启动期 SQLite corrupt | `ConstRefEngine` 构造期不应初始化 SQLite runtime；检查 `ConstRefCacheDatabase` 损坏重建与 no-op fallback 日志 |
| 默认 Run 配置没有生成或指针错误 | `JuggManager.tryCreateRunConfigurations()`、`IdeaCliRunConfigurationManager`、`build/jugg/config/run_configurations/` 与 `current_run_configuration.json` |
| Sync 后 project info / dependency 状态异常 | `JuggManager.onSyncEvent()`、`updateProjectInfo()`、`CompileContextManager.updateCompileContext()` |
| Run UI 状态错乱或取消后下轮误判 | `JuggRunningTask.run()` finally 中 hasRun / processHandler / logger listener 收口 |
| Panel 数据不刷新或热更新后仍显示旧组件 | `JuggControlPanelHost`、`JuggInitializer.getManager(project)`、`JuggManager.getJuggControlPanel()` 与 Panel subscription dispose |
| 下载更新后当前工程仍运行旧实现 | 当前 manager 不原地换 ClassLoader；重新打开工程，若更新要求 reinstall 则重启 IDE |
| Panel Logs 内容不可读或缺事件 | 检查 `JuggManager.onSyncEvent()`、`JuggRunningTask`、`McpToolInvoker` 的结构化事件生产；Panel 不应读取 raw log |
| Jugg Debug attach 后断点不可用 | `04_engineering_debug_attach.md`，确认 WAITING、`Connected to the target VM` 与 `XDebugSession` |
| androidTest 有结果但 Test Results 不完整 | `JuggManager.runTask()` 参数传递，确认 `executor` / `runProfile` / `androidTestRunSpec` 都非空 |
| skill / hook 安装入口异常 | `MoreOptionsManager`、`InstallJuggSkillsDialog`、`JuggSkillInstaller` |
| MCP 本地服务没有启动或未停止 | `JuggInitializer.init()` / `release()` 对 `McpLocalServer.start()` / `stop()` 的调用 |

---

## 7. 关联文档

- 架构：`01_architecture.md`
- 项目模型：`04_engineering_project.md`
- 兼容层：`04_engineering_compat.md`
- Jugg Debug attach：`04_engineering_debug_attach.md`
- 部署流程：`03_deploy_complete.md`
- 插件运行时排查：`09_plugin_runtime_debug.md`
- MCP：`08_mcp_design.md`、`08_mcp_tools_list.md`
