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
  - `Tools` 分组首项支持 `Install Jugg Skills`，可多选 `Codex / Claude Code / Gemini / CodeBuddy / Cursor`，并触发 `ide/logic/JuggSkillInstaller.kt` 通过代码直接完成 skill 安装（内置 zip 资源）与 CLI 安装。  
  - 安装弹窗默认开启 agent hooks 安装，注入 `SessionStart`/`Stop` command hooks：`SessionStart` 调用 `~/.jugg/skills/hooks/start.py` 记录 `jugg status` 基线，`Stop` 调用 `~/.jugg/skills/hooks/stop.py` 对比基线并在检测到 Android 改动且未完成验证时阻断停止。安装流程会先将统一资源包 `docs/skills/docs-skills.zip` 解压到 `~/.jugg/skills`，再从该目录安装 CLI（`jugg-android-dev-loop/scripts`）并复用 hooks（`hooks/start.py`、`hooks/stop.py`）（`ide/ui/InstallJuggSkillsDialog.kt`、`ide/logic/JuggSkillInstaller.kt`、`ide/logic/JuggHookInstaller.kt`）。  
  - 安装弹窗提供 `Manual Setup Guide`，运行时直接复用 `~/.jugg/skills/install/agent_setup.md`，并复用同目录下的 `~/.jugg/skills/jugg-android-dev-loop/`；不再导出到 `build/jugg/config`（`ide/logic/ClientSetupDocExporter.kt`）。  
- 操作入口：`ide/ui/GradleCompileAction.kt`、`RestartAppAction.kt` 等。  
- 通知与对话框：`JuggCommonNotification.kt`、`BuildChangesConfirmDialog.kt`、`Report*Dialog.kt`。

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
