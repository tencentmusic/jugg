# 工程化：IDE 插件层

> 最后核对：2026-03-23  
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 文档定位

本页描述 IDE 侧生命周期、运行配置、任务调度与 UI 交互入口。

---

## 2. 关键入口

| 入口 | 文件 | 作用 |
|------|------|------|
| 插件加载 | `idea/src/ide_entry/java/.../loader/JuggLoader.kt` | 类加载与桥接入口 |
| 初始化 | `idea/src/ide_entry/java/.../loader/JuggInitializer.kt` | 插件生命周期启动 |
| 总管理器 | `idea/src/main/java/.../JuggManager.kt` | 组装编译/部署/project/mcp 能力 |
| 运行配置 | `idea/src/ide_entry/java/.../ide/JuggRunConfiguration.kt` | Run 配置定义 |
| 运行任务 | `idea/src/main/java/.../ide/logic/JuggRunningTask.kt` | 编译+部署串联 |
| 编译助手 | `idea/src/main/java/.../compiler/JuggCompileHelper.kt` | 增量/Gradle 回退判定 |
| 部署助手 | `idea/src/main/java/.../deploy/run/JuggDeployerHelper.kt` | 设备部署执行 |
| Sync 监听 | `idea/src/ide_entry/java/.../ide/JuggGradleSyncListener.kt` | Gradle Sync 事件入口 |

---

## 3. 生命周期简图

1. IDE 加载插件，`JuggInitializer` 创建 `JuggManager`。  
2. `JuggManager.init()` 初始化配置、上下文、兼容层与服务。  
3. Sync 事件回调到 `onSyncEvent`，更新 project info 与策略。  
4. 用户点击 Run 后触发 `JuggRunningTask`。  
5. 任务结束后回写状态并更新 UI。

---

## 4. UI 与交互层

- 运行配置与设置：`ide/logic/JuggRunSettingsComponent.kt`、`JuggRunConfigurationOptionsExt.kt`。  
- More Options 下拉与工具入口：`ide/logic/MoreOptionsManager.kt`、`ide/ui/InstallJuggSkillsDialog.kt`。  
  - `Tools` 分组首项支持 `Install Jugg Skills`，可多选 `Codex / Claude Code / Gemini / CodeBuddy / Cursor`，并触发 `JuggSkillInstaller.kt` 通过代码直接完成 skill 安装（内置 zip 资源）与 CLI 安装。  
  - 安装弹窗默认开启 agent hooks 安装，按客户端注入 start/stop/edit/command 事件：Codex/Claude/CodeBuddy 使用 `UserPromptSubmit`、`Stop`、`PostToolUse`、`PreToolUse`；Gemini 使用 `BeforeAgent`、`AfterAgent`、`AfterTool`、`BeforeTool`；Cursor 使用 `beforeSubmitPrompt`、`stop`、`afterFileEdit`、`beforeShellExecution`。其中 Codex/Claude/CodeBuddy 的 tool hooks 使用精确 matcher（edit: `Edit|Write|MultiEdit|apply_patch`，command: `Bash`）；Gemini 使用精确 matcher（edit: `write_file|replace`，command: `run_shell_command`）；Cursor 维持 `*` matcher。edit hook 在 Android 源码修改后提醒验证阶段加载 `jugg-android-dev-loop`，command hook 在已有 Android 修改后阻止首次 raw Gradle 验证；stop hook 在检测到 Android 改动且没有调用过编译时阻断停止。
  - 安装弹窗提供 `Manual Setup Guide`，运行时复用 `~/.jugg/skills/install/agent_setup.md`（`main/ai/skills/ClientSetupDocExporter.kt`）。  
- 操作入口：`ide/ui/GradleCompileAction.kt`、`RestartAppAction.kt` 等。  
- 通知与对话框：`JuggCommonNotification.kt`、`BuildChangesConfirmDialog.kt`、`Report*Dialog.kt`。
- CLI/MCP/RPC 触发的 `runFirstConfigurationWithSpec` 仍会通过 `RunContentManager.showRunContent` 创建 Run content，保证 console/tab 可用；但 `RunContentDescriptor.isActivateToolWindowWhenAdded=false`，避免创建 content 时自动切到 Run 工具窗口。任务首次开始时 `JuggRunningTask` 通过 `CompileUiHandler.ensureRunWindowCreated()` 只创建 Run 工具窗口基础设施，不激活窗口；显式弹出仍由失败等场景调用 `CompileUiHandler.showRunWindow()` 控制。

---

## 5. 常见问题定位

- “插件启动后无运行配置”：看 `JuggManager.tryCreateRunConfigurations`。  
- “Sync 后状态异常”：看 `JuggManager.onSyncEvent` 与 `CompileContextManager`。  
- “Run UI 状态错乱”：看 `JuggRunningTask` 中 process handler/indicator 协调。  
- “IDE 启动后长时间卡死 / `postInit` 阶段卡住”：先读 `09_plugin_runtime_debug.md` 第 4.1.1 节，再联动 `03_deploy_const_ref.md` 第 8.1 节核对 `ConstRefEngine` 的启动期 `FULL_SCAN` 与 throttle 口径。

---

## 6. 关联文档

- 架构：`01_architecture.md`
- 项目模型：`04_engineering_project.md`
- 部署流程：`03_deploy_complete.md`
- MCP：`08_mcp_usage.md`
