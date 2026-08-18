# 远程自定义命令执行方案

## 1. 背景

远程编译用户需要临时执行 `git`、文件检查或工程脚本等命令。目前只能复制 SSH 信息后单独打开终端登录，操作链路割裂。

本次增加一次性远程命令执行能力：用户从 Jugg Control Panel 输入命令，Jugg 在当前远程工程目录执行，并将输出展示到独立 Run Window。

## 2. 已确认范围

- 严格使用 Android Studio 当前选中的 Jugg Run Configuration，不使用历史配置或首项 fallback。
- 当前配置必须启用 Remote Compile，并具备有效 SSH user、host 和 port。
- 命令固定在当前配置计算出的 `remoteProjectPath` 执行。
- 远程命令使用独立 SSH client、独立后台流程和独立 Run Content。
- 不接入 `JuggRunningTask`、`JuggConfigurationRunner.runTask()` 或现有编译部署状态机。
- 通用 SSH 命令执行能力放在 `main`；当前配置选择、输入对话框和 Run Window 适配留在 `idea`。
- 结构需要便于后续合并到 `/Users/wormchen/IdeaProjects/jugg/jugg_f2` 的 develop 分支；不依赖仅存在于当前 IDEA Run task 的状态或类型。
- 合并到 develop 时，`JuggSettings.remoteCommandHistoryJson` 改用其 `setting("")` JSON repository，并在仍需承接本分支 PropertiesComponent 数据时补充 legacy migration key；SSH client 继续使用 develop 的 `File projectDir` 主构造和既有 shell echo 关闭流程。

## 3. 用户行为

1. 用户在 Control Panel 的 Build Quick Actions 点击 `Remote Command...`。
2. 插件读取当前选中的 Jugg Run Configuration；不是远程配置时明确提示并终止。
3. 对话框展示 Configuration、SSH Target 和 Working Directory，并提供多行命令输入框。
4. 对话框提供当前远程目标最近执行的 10 条命令下拉框，选择后回填完整命令。
5. 用户点击 Run 后创建独立的 `Jugg Remote Command` Run Content，并将本次命令去重后置于历史首位。
6. Run Content 流式展示远程输出，并在结束时展示 exit code。
7. 用户点击 Stop 时取消本次远程命令并释放独立 SSH 连接。

## 4. 实现边界

### 4.1 main

- 在 `RemoteGradleCompileClient` 增加一次性自定义命令入口。
- 复用现有 SSH 认证、代理、环境变量、PTY、退出码解析和取消能力。
- 自定义命令不使用现有 90 秒无输出超时，避免安静运行的合法命令被误判失败。
- 命令正文编码后交给独立子 shell 执行，工作目录使用 shell quote，每次执行使用唯一完成标记；用户命令中的注释、`exit` 或伪造旧结果行不能破坏退出码协议。
- 启动前或登录阶段收到 Stop 时保留取消状态，不继续执行命令；Run Content 仅在后台流程确认结束后报告非零取消状态。
- 自定义命令的单次 SSH connect 最长等待 30 秒，Stop 会中止后续认证 fallback，避免 Run Content 长时间停留在 terminating。
- 命令正文和远程输出不写入持久化 Jugg 日志；日志只保留连接阶段、任务类型、耗时和退出结果。
- 在 `JuggSettings` 中保存远程命令历史，按 `user + host + port + remoteProjectPath` 隔离，避免切换服务器后误用命令。
- 每个远程目标最多保留 10 条，按完整命令去重并以最近执行顺序排列。
- 历史读取或写入失败时只降级历史能力，不阻断远程命令执行。
- 现有 Gradle 编译、rsync 和产物拉取路径保持原行为。

### 4.2 idea

- 新增远程命令输入对话框。
- 新增独立 runner 和专用 ProcessHandler，负责普通文本 Console、Run Content、后台线程、取消和完成状态。
- Control Panel 只增加动作入口；不把远程命令写入 compile/deploy Current Task 或 Recent Runs。
- `JuggManager` 只负责严格解析当前配置并启动独立 runner。

## 5. 不在范围内

- 不实现交互式终端，不提供 stdin，不支持 `vim`、`top` 或二次密码输入。
- 不提供命令预设、收藏、编辑历史或跨项目同步。
- 不开放自定义工作目录。
- 不自动同步本地文件到远程。
- 不增加 MCP/CLI 远程命令接口。
- 不接入 Android Studio Terminal 插件 API。
- 不允许在未明确选中远程 Jugg Configuration 时自动选择其他服务器。

## 6. 预计文件

### 修改

- `main/src/main/java/com/sickworm/intellij/jugg/gradle/compile/RemoteGradleCompileClient.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/ide/bean/JuggSettings.kt`
- `idea/src/main/java/com/sickworm/intellij/jugg/JuggManager.kt`
- `idea/src/main/java/com/sickworm/intellij/jugg/ide/ui/JuggControlPanel.kt`
- `idea/src/main/java/com/sickworm/intellij/jugg/ide/ui/JuggControlPanelController.kt`
- `docs/ai_knowledge/04_engineering_ide.md`
- `docs/ai_knowledge/04_engineering_project.md`
- `docs/ai_knowledge/98_code_map.md`

### 新增

- `main/src/main/java/com/sickworm/intellij/jugg/gradle/compile/RemoteUserCommand.kt`
- `idea/src/main/java/com/sickworm/intellij/jugg/ide/ui/RemoteCommandDialog.kt`
- `idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/RemoteCommandRunner.kt`

## 7. 验证策略

失败证据为当前 Control Panel 和 Run Configuration 只提供远程编译及 SSH 信息复制，没有直接执行远程命令的入口。

自动化测试价值判断：远程 SSH 的真实认证、输出、取消和超时依赖外部服务器，无法在 CI 中稳定自动化，且不得为测试向生产代码注入 factory/provider。命令协议隔离与唯一退出标记由 `RemoteUserCommandTest` 保护，取消状态由 `RemoteCommandProcessHandlerTest` 保护；命令历史的目标隔离、去重、容量和损坏数据降级由 `JuggSettingsTest` 保护，Control Panel 测试保护动作入口。复用 `JuggConfigurationRunnerTest` 作为未命中本功能条件的回归 owner。

替代验证：

- 执行 `./gradlew :idea:compileKotlin`。
- 定向执行现有 SSH command、Run Content 和 Control Panel 测试。
- 检查本次 diff 未修改 `JuggRunningTask` 和 `JuggConfigurationRunner.runTask()`。
- 在可用远程环境手工验证 `pwd`、正常输出、非零退出、超过 90 秒无输出命令和 Stop 取消。
- 对照 develop 分支同名类，检查新增 main API 不依赖 IDEA 专属类型，IDE 层差异集中在 client 构造与配置来源。
