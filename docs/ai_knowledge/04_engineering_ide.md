# 工程化：IDE 插件层

> 最后核对：2026-05-23
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
| `JuggManager` | `idea/src/main/java/com/sickworm/intellij/jugg/JuggManager.kt` | IDE 侧总装配器，连接 compile、deploy、project info、dependency、MCP、server、UI |
| `JuggRunningTask` | `idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/JuggRunningTask.kt` | Run 按钮后的后台任务，串联编译、部署、状态回写、Run tool window |
| `JuggConfigurationRunner` | `idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/JuggConfigurationRunner.kt` | 创建并运行 `JuggRunningTask`，维护是否正在编译和下一轮强制重装 |
| `JuggCompileHelper` | `idea/src/main/java/com/sickworm/intellij/jugg/compiler/JuggCompileHelper.kt` | IDE 侧增量/Gradle 回退判定与 compile 入口 |
| `JuggDeployerHelper` | `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployerHelper.kt` | IDE 侧设备部署、recover、retry、agent 协同入口 |
| `CompileContextManager` | `idea/src/main/java/com/sickworm/intellij/jugg/project/CompileContextManager.kt` | 项目信息、编译上下文、部署上下文的 IDE 侧同步 |
| `MoreOptionsManager` | `idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/MoreOptionsManager.kt` | More Options 菜单，挂载 Gradle compile、restart、skills、report 等操作 |

---

## 3. 核心状态模型

| 状态 | 所属对象 | 生命周期 |
|---|---|---|
| `instanceSet` | `JuggInitializer` | 以 project basePath 为 key 保存 `JuggLoader`；最后一个项目释放时停止 `McpLocalServer` |
| `JuggPathManager` | `JuggManagerCreator` / `JuggManager` | 项目级 `build/jugg` 路径、日志、数据库、classpath、MCP fetch cache 的根 |
| `CompileContext` | `CompileContextManager` | Gradle/project info 更新后重建；被 compiler、deploy file manager、自定义编译器消费 |
| deploy history / deploy state | `DeployHistoryManager` / `DeployStateManager` | full build 后初始化，增量部署成功后 commit；启动时可从历史恢复 |
| hasRun / selected devices | `JuggRunningTaskStatusManager` | 决定“首次运行”、stop/cancel 后是否重置，以及 hook/status 语义 |
| run UI process handler | `CompileUiHandler` / `JuggRunningTask` | 承载日志、进度、取消状态；androidTest 时接入 Test Results console |

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
     初始化 AsDeployerCompat、custom config、默认 run config、min api、project info 打印
  -> recoverDeployContext()
     从 deploy history 恢复 compile context、APK、changed files，避免无必要全量构建
  -> background tasks
     预初始化 deployment service、检查更新、自动更新 Jugg CLI、清理 MCP fetch cache
```

`recoverDeployContext()` 只在 deploy history 有可恢复信息时生效；没有历史时应提示先跑 Gradle/full compile，而不是强行构造增量上下文。

### 4.2 Gradle Sync 到上下文重建

```text
JuggGradleSyncListener
  -> JuggInitializer.onSyncEvent(project, syncEvent)
  -> JuggManager.onSyncEvent()
     SUCCEEDED: 创建 run config，updateProjectInfo(isAfterSync = true)
     SKIPPED: 创建 run config，updateProjectInfo(isAfterSync = false)
     STARTED/FAILED: 通知 dependencyChangeManager
  -> CompileContextManager.updateCompileContext()
  -> GradleProjectInfoLocalFetchManager.runUpdateIfNeeded()
  -> reInitOnCompileContextUpdate()
     更新 DeployFileManager、JuggCompiler、FileChangesHandler、GitFileChangesDetector、CustomCompilerManager
```

Sync 成功会重置 hasRun，避免旧运行状态让“无文件变化”判断污染下一轮。

### 4.3 Run 到编译部署

```text
JuggRunConfiguration / JuggAndroidTestRunConfiguration
  -> JuggManager.runTask(options, executor, runProfile, androidTestRunSpec)
  -> JuggConfigurationRunner.runTask()
  -> JuggRunningTask.run()
     dependency start、Run tool window 状态、JuggLogger listener、server report
  -> JuggCompileHelper.compile()
     可能走增量，也可能 fallback 到 Gradle
  -> JuggDeployerHelper.deploy()
     多设备逐个部署，汇总 deploy type 和失败可回退性
  -> compileUiHandler.onEnd()
     回写 hasRun、停止日志监听、更新 UI
```

androidTest 运行必须把 `androidTestRunSpec`、`executor`、`runProfile` 一起传入 `JuggManager.runTask()`，否则 Test Results console、source navigation、rerun failed 不能完整接入。

---

## 5. UI 与工具入口

- 默认 Run 配置由 `JuggManager.tryCreateRunConfigurations()` 通过 `AsDeployerCompat.getSuggestRunConfigurations()` 推断；Sync 后如果没有可用配置会短暂重试。
- More Options 统一从 `JuggManager.getMoreOptions()` 进入 `MoreOptionsManager`，挂载 Gradle compile、restart app、skill/install、report issue 等操作。
- `Check Jugg Update` 独立 action 经 `JuggManager.checkUpdates()` 复用 `MoreOptionsManager.checkUpdates()`，行为与 More Options 中的更新检查一致。
- `Install Jugg Skills` 由 `InstallJuggSkillsDialog` 触发 `JuggSkillInstaller`，会安装内置 skills、CLI、hooks；选择 Codex skill 时额外通过 `CodexPermissionRuleInstaller` 写入 `~/.codex/rules/default.rules` 的 Jugg CLI `prefix_rule`，避免 Jugg 本地端口探测反复触发提权确认；安装完成后导出 `~/.jugg/skills/install/agent_setup.md`。hook 与 CLI 细节以 `docs/skills` 和 `08_cli_tools_list.md` 为准。
- CLI/MCP/RPC 触发首个 run configuration 时会创建 Run content，但默认不激活 Run tool window；失败等需要用户注意的场景才显式 show。
- `reportIssue()` 会 dump project info、logcat error，并通过 `JuggServer.reportAndUploadLogs()` 上传日志。

---

## 6. 排查入口

| 现象 | 优先入口 |
|---|---|
| 插件初始化后没有 manager | `JuggInitializer.instanceSet`、`JuggLoader`、`JuggManagerCreator.create()` |
| 启动期长时间卡住 | `09_plugin_runtime_debug.md`，再看 `JuggManager.init()` background task 和 `ConstRefEngine` 启动扫描 |
| 默认 Run 配置没有生成 | `JuggManager.tryCreateRunConfigurations()` 与 `AsDeployerCompat.getSuggestRunConfigurations()` |
| Sync 后 project info / dependency 状态异常 | `JuggManager.onSyncEvent()`、`updateProjectInfo()`、`CompileContextManager.updateCompileContext()` |
| Run UI 状态错乱或取消后下轮误判 | `JuggRunningTask.run()` finally 中 hasRun / processHandler / logger listener 收口 |
| androidTest 有结果但 Test Results 不完整 | `JuggManager.runTask()` 参数传递，确认 `executor` / `runProfile` / `androidTestRunSpec` 都非空 |
| skill / hook 安装入口异常 | `MoreOptionsManager`、`InstallJuggSkillsDialog`、`JuggSkillInstaller` |
| MCP 本地服务没有启动或未停止 | `JuggInitializer.init()` / `release()` 对 `McpLocalServer.start()` / `stop()` 的调用 |

---

## 7. 关联文档

- 架构：`01_architecture.md`
- 项目模型：`04_engineering_project.md`
- 兼容层：`04_engineering_compat.md`
- 部署流程：`03_deploy_complete.md`
- 插件运行时排查：`09_plugin_runtime_debug.md`
- MCP：`08_mcp_design.md`、`08_mcp_tools_list.md`
